package com.music.bitchord.playback.dsd.raw

import java.util.ArrayDeque

/**
 * Reassembles one selected SACD track's multiplexed 1/75-second frames, then emits raw U32 DSD.
 * Track timecodes and sector ranges are hard boundaries: lead-in, adjacent tracks and padding never
 * contribute samples. DST sectors use the same decoder abstraction as DFF.
 */
class SacdIsoReader private constructor(
    private val input: RawDsdInput,
    private val disc: SacdIsoDisc,
    private val audioArea: SacdArea,
    private val track: SacdTrack,
    packing: RawDsdPacking,
    private val decoderFactory: RawDstDecoderFactory,
) : RawDsdReaderBase() {
    override val format = RawDsdFormat(
        RawDsdContainer.SACD_ISO,
        track.bitRate,
        track.channels,
        track.sampleCount,
        packing,
    )
    override val maxPacketBytes: Int = RawDsdConstants.DST_FRAME_BYTES_PER_CHANNEL * format.channels
    override var nextSamplePosition: Long = 0L
        private set

    private var targetFrame = track.startFrame
    private var nextExpectedFrame = track.startFrame
    private var nextLsn = track.startLsn
    private var sector: AudioSector? = null
    private var packetIndex = 0
    private var currentFrame: FrameBuilder? = null
    private var lastFrameStart: Int? = null
    private val completedFrames = ArrayDeque<SacdEncodedFrame>()
    private var decoder: RawDstDecoder? = null
    private var closed = false

    override fun locate(samplePosition: Long): RawDsdSeekPoint {
        if (samplePosition == format.sampleCount) {
            return RawDsdSeekPoint(
                samplePosition,
                format.durationUs,
                disc.layout.dataPosition(track.endLsnExclusive),
                0,
            )
        }
        val relativeFrame = samplePosition / RawDsdConstants.SACD_FRAME_SAMPLES
        val aligned = relativeFrame * RawDsdConstants.SACD_FRAME_SAMPLES
        val resumeLsn = if (audioArea.frameFormat.dst) {
            // DST has no mandatory byte index. Restarting the selected track is exact; skipped
            // frames are demuxed to find the requested timecode but are never decoded.
            track.startLsn
        } else {
            val sectorGroup = relativeFrame / 3
            (track.startLsn + sectorGroup * audioArea.frameFormat.sectorsPerThreeFrames)
                .coerceAtMost(track.endLsnExclusive - 1)
        }
        return RawDsdSeekPoint(
            aligned,
            format.timestampUs(aligned),
            disc.layout.dataPosition(resumeLsn),
            (samplePosition - aligned).toInt(),
        )
    }

    override fun moveTo(point: RawDsdSeekPoint) {
        val relativeLsn = point.filePosition - disc.layout.dataOffset
        require(relativeLsn >= 0 && relativeLsn % disc.layout.sectorSize == 0L) { "Invalid SACD resume position." }
        val lsn = relativeLsn / disc.layout.sectorSize
        require(lsn in track.startLsn..track.endLsnExclusive) { "SACD resume sector is outside the track." }
        nextSamplePosition = point.samplePosition
        targetFrame = track.startFrame + (point.samplePosition / RawDsdConstants.SACD_FRAME_SAMPLES).toInt()
        nextExpectedFrame = targetFrame
        nextLsn = lsn
        sector = null
        packetIndex = 0
        currentFrame = null
        lastFrameStart = null
        completedFrames.clear()
    }

    override fun readPacket(): RawDsdPacket? {
        check(!closed) { "SACD reader is closed." }
        val first = nextSamplePosition
        if (first == format.sampleCount) return null
        check(first in 0 until format.sampleCount && first % RawDsdConstants.SACD_FRAME_SAMPLES == 0L) {
            "SACD reader is not on a frame boundary."
        }
        val encoded = readSelectedFrame()
            ?: throw IllegalArgumentException("SACD audio ended before the declared track duration.")
        val expectedFrame = track.startFrame + (first / RawDsdConstants.SACD_FRAME_SAMPLES).toInt()
        require(encoded.frame == expectedFrame) { "SACD frame timecode does not match the track timeline." }
        val canonical = if (audioArea.frameFormat.dst) {
            require(encoded.data.size in 2..MAX_FRAME_BYTES) { "Invalid SACD DST frame size." }
            val decoded = ByteArray(maxPacketBytes)
            if (decoder == null) decoder = decoderFactory.create(format.channels)
            requireNotNull(decoder).decode(encoded.data, decoded)
            decoded
        } else {
            require(encoded.data.size == maxPacketBytes) {
                "Plain SACD frame must contain $maxPacketBytes bytes, got ${encoded.data.size}."
            }
            encoded.data
        }
        nextSamplePosition = first + RawDsdConstants.SACD_FRAME_SAMPLES
        return RawDsdPacketizer.packet(
            format,
            canonical,
            first,
            RawDsdConstants.SACD_FRAME_SAMPLES.toInt(),
        )
    }

    private fun readSelectedFrame(): SacdEncodedFrame? {
        while (true) {
            val frame = demuxFrame() ?: return null
            if (frame.frame < targetFrame) continue
            if (frame.frame >= track.endFrameExclusive) return null
            require(frame.frame == nextExpectedFrame) {
                "SACD track has a timecode gap: expected $nextExpectedFrame, got ${frame.frame}."
            }
            nextExpectedFrame++
            return frame
        }
    }

    /** Returns the next non-empty multiplexed frame, irrespective of the selected time range. */
    private fun demuxFrame(): SacdEncodedFrame? {
        while (true) {
            if (completedFrames.isNotEmpty()) return completedFrames.removeFirst()
            val currentSector = sector
            if (currentSector == null || packetIndex == currentSector.packets.size) {
                if (nextLsn == track.endLsnExclusive) {
                    finishCurrentFrame()
                    if (completedFrames.isNotEmpty()) continue
                    return null
                }
                require(nextLsn < track.endLsnExclusive) { "SACD demuxer passed its track end." }
                val position = disc.layout.dataPosition(nextLsn)
                sector = parseAudioSector(input.readBytes(position, SacdIsoParser.SECTOR_BYTES))
                nextLsn++
                packetIndex = 0
                continue
            }
            val packet = currentSector.packets[packetIndex++]
            packet.frameStart?.let { frameStart ->
                finishCurrentFrame()
                require(lastFrameStart?.let { frameStart > it } != false) {
                    "SACD frame timecodes overlap or run backwards."
                }
                lastFrameStart = frameStart
                currentFrame = FrameBuilder(frameStart)
            }
            if (packet.dataType == DATA_TYPE_AUDIO) {
                val builder = requireNotNull(currentFrame) { "SACD audio packet has no frame start." }
                require(packet.length <= MAX_FRAME_BYTES - builder.size) { "SACD multiplexed frame is too large." }
                currentSector.bytes.copyInto(
                    builder.bytes,
                    builder.size,
                    packet.dataOffset,
                    packet.dataOffset + packet.length,
                )
                builder.size += packet.length
            }
        }
    }

    private fun finishCurrentFrame() {
        val builder = currentFrame ?: return
        currentFrame = null
        if (builder.size > 0) {
            completedFrames += SacdEncodedFrame(builder.frame, builder.bytes.copyOf(builder.size))
        }
    }

    private fun parseAudioSector(bytes: ByteArray): AudioSector {
        val header = bytes.u8(0)
        val packetCount = header ushr 5
        val frameStartCount = header shr 2 and 7
        require(packetCount in 1..7 && header and 2 == 0 && (header and 1 != 0) == audioArea.frameFormat.dst) {
            "Invalid SACD audio sector header."
        }
        val frameInfoSize = if (audioArea.frameFormat.dst) 4 else 3
        val frameInfoPosition = 1 + 2 * packetCount
        val dataStart = frameInfoPosition + frameInfoSize * frameStartCount
        require(dataStart <= bytes.size) { "Truncated SACD audio sector headers." }

        val infos = IntArray(packetCount)
        var dataBytes = 0
        var starts = 0
        for (index in 0 until packetCount) {
            val info = bytes.u16(1 + 2 * index)
            infos[index] = info
            val length = info and 0x7ff
            require(info and 0x4000 == 0 && length > 0) { "Invalid SACD packet info." }
            val type = info shr 11 and 7
            require(type == DATA_TYPE_AUDIO || type == DATA_TYPE_SUPPLEMENTARY || type == DATA_TYPE_PADDING) {
                "Unsupported SACD packet data type: $type."
            }
            if (info and 0x8000 != 0) starts++
            dataBytes += length
        }
        require(starts == frameStartCount && dataStart <= bytes.size - dataBytes) {
            "SACD packet data exceeds its sector or frame-info count."
        }
        val frameTimes = IntArray(frameStartCount)
        for (index in 0 until frameStartCount) {
            val position = frameInfoPosition + index * frameInfoSize
            frameTimes[index] = audioTimecode(bytes, position)
            if (audioArea.frameFormat.dst) {
                val hint = bytes.u8(position + 3)
                val channels = when {
                    hint and 0x02 != 0 -> 6
                    hint and 0x01 != 0 -> 5
                    else -> 2
                }
                require(channels == format.channels) { "SACD DST frame channel hint disagrees with its area." }
            }
        }

        var dataPosition = dataStart
        var nextStart = 0
        val packets = ArrayList<AudioPacket>(packetCount)
        for (info in infos) {
            val length = info and 0x7ff
            packets += AudioPacket(
                frameStart = if (info and 0x8000 != 0) frameTimes[nextStart++] else null,
                dataType = info shr 11 and 7,
                dataOffset = dataPosition,
                length = length,
            )
            dataPosition += length
        }
        return AudioSector(bytes, packets)
    }

    override fun close() {
        if (!closed) {
            closed = true
            decoder?.close()
            decoder = null
        }
    }

    companion object {
        private const val DATA_TYPE_AUDIO = 2
        private const val DATA_TYPE_SUPPLEMENTARY = 3
        private const val DATA_TYPE_PADDING = 7
        private const val MAX_FRAME_BYTES = 16 * (SacdIsoParser.SECTOR_BYTES - 3)

        @JvmOverloads
        fun open(
            input: RawDsdInput,
            area: SacdAreaKind = SacdAreaKind.STEREO,
            trackNumber: Int = 1,
            packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE,
            dstDecoderFactory: RawDstDecoderFactory = NativeRawDstDecoderFactory,
        ): SacdIsoReader = open(SacdIsoParser.parse(input), input, area, trackNumber, packing, dstDecoderFactory)

        @JvmOverloads
        fun open(
            disc: SacdIsoDisc,
            input: RawDsdInput,
            area: SacdAreaKind = SacdAreaKind.STEREO,
            trackNumber: Int = 1,
            packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE,
            dstDecoderFactory: RawDstDecoderFactory = NativeRawDstDecoderFactory,
        ): SacdIsoReader {
            val selectedArea = disc.area(area)
            return SacdIsoReader(input, disc, selectedArea, selectedArea.track(trackNumber), packing, dstDecoderFactory)
        }

        private fun audioTimecode(bytes: ByteArray, offset: Int): Int {
            val minutes = bytes.u8(offset)
            val seconds = bytes.u8(offset + 1)
            val frames = bytes.u8(offset + 2)
            require(seconds < 60 && frames < RawDsdConstants.SACD_FRAME_RATE) { "Invalid SACD audio timecode." }
            return (minutes * 60 + seconds) * RawDsdConstants.SACD_FRAME_RATE + frames
        }
    }
}

private data class SacdEncodedFrame(val frame: Int, val data: ByteArray)
private class FrameBuilder(val frame: Int) {
    val bytes = ByteArray(16 * (SacdIsoParser.SECTOR_BYTES - 3))
    var size = 0
}
private data class AudioSector(val bytes: ByteArray, val packets: List<AudioPacket>)
private data class AudioPacket(val frameStart: Int?, val dataType: Int, val dataOffset: Int, val length: Int)
