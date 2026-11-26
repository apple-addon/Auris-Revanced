package com.music.bitchord.playback.dsd.raw

import com.music.bitchord.playback.dsd.DstFrameCrc
import java.nio.ByteBuffer

private data class DffProperties(val rate: Int, val channels: Int, val dst: Boolean)
private data class DffDstEntry(val offset: Long, val size: Int, val crc: Int? = null)

/** Parses FRM8/DSD chunks and chooses the plain or DST raw reader. */
internal object DffRawDsdReader {
    fun open(
        input: RawDsdInput,
        packing: RawDsdPacking,
        dstDecoderFactory: RawDstDecoderFactory,
    ): RawDsdPacketReader {
        val header = ByteBuffer.wrap(input.readBytes(0, 16))
        require(id(header) == "FRM8") { "Invalid DFF FRM8 header." }
        val formSize = header.long
        require(formSize >= 4) { "Invalid DFF form size." }
        val fileEnd = checkedEnd(12, formSize, Long.MAX_VALUE)
        verifyDeclaredLength(input, fileEnd)
        require(id(header) == "DSD ") { "Unsupported DFF form type." }

        var scan = 16L
        var chunks = 0
        var versionSeen = false
        var properties: DffProperties? = null
        var dataOffset = -1L
        var dataBytes = -1L
        var dstFrames = emptyList<DffDstEntry>()
        var declaredDstIndex: List<Pair<Long, Int>>? = null
        var dstIndexSeen = false

        while (scan < fileEnd) {
            require(++chunks <= 4_096 && fileEnd - scan >= 12) { "Invalid DFF chunk count or truncated chunk header." }
            val chunkHeader = ByteBuffer.wrap(input.readBytes(scan, 12))
            val chunkId = id(chunkHeader)
            val size = chunkHeader.long
            val payload = scan + 12
            val end = checkedEnd(payload, size, fileEnd)
            val next = checkedEnd(end, size and 1, fileEnd)
            require(versionSeen || chunkId == "FVER") { "DFF must begin with FVER." }
            when (chunkId) {
                "FVER" -> {
                    require(!versionSeen && size == 4L) { "Invalid or duplicate DFF version chunk." }
                    val version = ByteBuffer.wrap(input.readBytes(payload, 4)).int
                    require(version ushr 24 == 1) { "Unsupported DFF version." }
                    versionSeen = true
                }
                "PROP" -> {
                    require(properties == null && dataOffset < 0 && size in 4..65_536) {
                        "Invalid or duplicate DFF sound properties."
                    }
                    properties = soundProperties(input.readBytes(payload, size.toInt()))
                }
                "DSD " -> {
                    require(properties?.dst == false && dataOffset < 0 && size > 0) {
                        "Invalid or duplicate plain DFF sound data."
                    }
                    dataOffset = payload
                    dataBytes = size
                }
                "DST " -> {
                    require(properties?.dst == true && dataOffset < 0 && size >= 18) {
                        "Invalid or duplicate DST sound data."
                    }
                    dataOffset = payload
                    dataBytes = size
                    dstFrames = readDstFrames(input, payload, end)
                }
                "DSTI" -> {
                    require(properties?.dst == true && !dstIndexSeen && size in 12..12_000_000 && size % 12 == 0L) {
                        "Invalid or duplicate DST index."
                    }
                    dstIndexSeen = true
                    val index = ByteBuffer.wrap(input.readBytes(payload, size.toInt()))
                    declaredDstIndex = List(size.toInt() / 12) {
                        val offset = index.long
                        val length = index.int
                        require(offset >= 0 && length >= 2) { "Invalid DST index entry." }
                        offset to length
                    }
                }
            }
            scan = next
        }

        val p = requireNotNull(properties) { "DFF is missing sound properties." }
        require(versionSeen && dataOffset >= 0 && dataBytes > 0) { "DFF is missing sound data." }
        val format: RawDsdFormat
        if (p.dst) {
            require(dstFrames.isNotEmpty()) { "DFF contains no DST frames." }
            declaredDstIndex?.let { index ->
                require(index.size == dstFrames.size && index.indices.all {
                    index[it].first == dstFrames[it].offset && index[it].second == dstFrames[it].size
                }) { "DST index does not match its frame chunks." }
            }
            format = RawDsdFormat(
                RawDsdContainer.DFF,
                p.rate,
                p.channels,
                dstFrames.size.toLong() * RawDsdConstants.SACD_FRAME_SAMPLES,
                packing,
            )
            return DffDstReader(input, format, dstFrames, dataOffset + dataBytes, dstDecoderFactory)
        }
        require(dataBytes % p.channels == 0L && dataBytes / p.channels <= Long.MAX_VALUE / 8) {
            "Plain DFF data is not channel aligned or is too large."
        }
        format = RawDsdFormat(
            RawDsdContainer.DFF,
            p.rate,
            p.channels,
            dataBytes / p.channels * 8,
            packing,
        )
        return DffPlainReader(input, format, dataOffset, dataOffset + dataBytes)
    }

    private fun readDstFrames(input: RawDsdInput, start: Long, end: Long): List<DffDstEntry> {
        val frames = ArrayList<DffDstEntry>()
        var scan = start
        var declaredCount = 0
        var subchunks = 0
        while (scan < end) {
            require(++subchunks <= 2_000_002 && end - scan >= 12) { "Truncated DST subchunk header." }
            val header = ByteBuffer.wrap(input.readBytes(scan, 12))
            val chunkId = id(header)
            val size = header.long
            val payload = scan + 12
            val chunkEnd = checkedEnd(payload, size, end)
            val next = checkedEnd(chunkEnd, size and 1, end)
            when (chunkId) {
                "FRTE" -> {
                    require(declaredCount == 0 && scan == start && size == 6L) { "Invalid DST frame-rate chunk." }
                    val info = ByteBuffer.wrap(input.readBytes(payload, 6))
                    declaredCount = info.int
                    require(declaredCount in 1..1_000_000 && info.short.toInt() == RawDsdConstants.SACD_FRAME_RATE) {
                        "Unsupported DST frame count or rate."
                    }
                }
                "DSTF" -> {
                    require(declaredCount > 0 && frames.size < declaredCount && size in 2..1_048_576) {
                        "Invalid or excess DST frame."
                    }
                    frames += DffDstEntry(payload, size.toInt())
                }
                "DSTC" -> {
                    require(size == 4L && frames.isNotEmpty() && frames.last().crc == null) { "Invalid DST CRC chunk." }
                    val previous = frames.last()
                    require(payload == previous.offset + previous.size + (previous.size and 1) + 12) {
                        "DST CRC must immediately follow its frame."
                    }
                    frames[frames.lastIndex] = previous.copy(crc = ByteBuffer.wrap(input.readBytes(payload, 4)).int)
                }
                else -> throw IllegalArgumentException("Unsupported DST subchunk: $chunkId.")
            }
            scan = next
        }
        require(declaredCount > 0 && frames.size == declaredCount) { "DST frame count does not match FRTE." }
        require(frames.none { it.crc != null } || frames.all { it.crc != null }) { "Incomplete DST CRC list." }
        return frames
    }

    private fun soundProperties(payload: ByteArray): DffProperties {
        val buffer = ByteBuffer.wrap(payload)
        require(buffer.remaining() >= 4 && id(buffer) == "SND ") { "Invalid DFF PROP/SND chunk." }
        var rate = 0
        var channels = 0
        var compression: String? = null
        var chunks = 0
        while (buffer.hasRemaining()) {
            require(++chunks <= 128 && buffer.remaining() >= 12) { "Invalid DFF property chunk list." }
            val chunkId = id(buffer)
            val size = buffer.long
            require(size >= 0 && size <= buffer.remaining() && size + (size and 1) <= buffer.remaining()) {
                "Truncated DFF property chunk."
            }
            val end = buffer.position() + size.toInt()
            when (chunkId) {
                "FS  " -> {
                    require(rate == 0 && size == 4L) { "Invalid or duplicate DFF sample rate." }
                    rate = buffer.int
                }
                "CHNL" -> {
                    require(channels == 0 && size >= 2) { "Invalid or duplicate DFF channel list." }
                    channels = buffer.short.toInt() and 0xffff
                    require(channels in 1..6 && size == 2L + 4L * channels) { "Invalid DFF channel count." }
                    val channelIds = List(channels) { id(buffer) }
                    require(channelIds.all { id -> id.all { it in ' '..'~' } } && channelIds.toSet().size == channels) {
                        "Invalid DFF channel identifiers."
                    }
                    if (channels == 2) require(channelIds == listOf("SLFT", "SRGT")) {
                        "Stereo DFF must use SLFT/SRGT channel order."
                    }
                }
                "CMPR" -> {
                    require(compression == null && size >= 5) { "Invalid or duplicate DFF compression declaration." }
                    compression = id(buffer)
                    val nameBytes = buffer.get().toInt() and 0xff
                    val padding = size - 5 - nameBytes
                    require(padding == 0L || padding == 1L && size % 2 == 0L && payload[end - 1] == 0.toByte()) {
                        "Invalid DFF compression name."
                    }
                    require(compression == "DSD " || compression == "DST ") {
                        "Unsupported DFF compression: $compression."
                    }
                }
            }
            buffer.position(end + (size and 1).toInt())
        }
        require(rate > 0 && channels > 0 && compression != null) { "Incomplete DFF sound properties." }
        require(compression != "DST " || rate == RawDsdConstants.DSD64_RATE) { "DST requires DSD64." }
        return DffProperties(rate, channels, compression == "DST ")
    }

    private fun id(buffer: ByteBuffer): String {
        require(buffer.remaining() >= 4) { "Truncated four-character chunk identifier." }
        return CharArray(4) { (buffer.get().toInt() and 0xff).toChar() }.concatToString()
    }
}

private class DffPlainReader(
    private val input: RawDsdInput,
    override val format: RawDsdFormat,
    private val dataOffset: Long,
    private val dataEnd: Long,
) : RawDsdReaderBase() {
    override val maxPacketBytes: Int = 4_096 * format.channels
    override var nextSamplePosition: Long = 0L
        private set

    override fun locate(samplePosition: Long): RawDsdSeekPoint {
        if (samplePosition == format.sampleCount) {
            return RawDsdSeekPoint(samplePosition, format.durationUs, dataEnd, 0)
        }
        val aligned = samplePosition / SAMPLES_PER_PACKET * SAMPLES_PER_PACKET
        return RawDsdSeekPoint(
            aligned,
            format.timestampUs(aligned),
            dataOffset + aligned / 8 * format.channels,
            (samplePosition - aligned).toInt(),
        )
    }

    override fun moveTo(point: RawDsdSeekPoint) {
        nextSamplePosition = point.samplePosition
    }

    override fun readPacket(): RawDsdPacket? {
        val first = nextSamplePosition
        if (first == format.sampleCount) return null
        check(first in 0 until format.sampleCount && first % SAMPLES_PER_PACKET == 0L) {
            "Plain DFF reader is not on a packet boundary."
        }
        val count = minOf(SAMPLES_PER_PACKET, format.sampleCount - first).toInt()
        val canonical = input.readBytes(dataOffset + first / 8 * format.channels, count / 8 * format.channels)
        nextSamplePosition = first + count
        return RawDsdPacketizer.packet(format, canonical, first, count)
    }

    private companion object {
        const val SAMPLES_PER_PACKET = 4_096 * 8L
    }
}

private class DffDstReader(
    private val input: RawDsdInput,
    override val format: RawDsdFormat,
    private val frames: List<DffDstEntry>,
    private val dataEnd: Long,
    private val decoderFactory: RawDstDecoderFactory,
) : RawDsdReaderBase() {
    override val maxPacketBytes: Int = RawDsdConstants.DST_FRAME_BYTES_PER_CHANNEL * format.channels
    override var nextSamplePosition: Long = 0L
        private set
    private var decoder: RawDstDecoder? = null
    private var closed = false

    override fun locate(samplePosition: Long): RawDsdSeekPoint {
        if (samplePosition == format.sampleCount) {
            return RawDsdSeekPoint(samplePosition, format.durationUs, dataEnd, 0)
        }
        val frame = samplePosition / RawDsdConstants.SACD_FRAME_SAMPLES
        val aligned = frame * RawDsdConstants.SACD_FRAME_SAMPLES
        return RawDsdSeekPoint(
            aligned,
            format.timestampUs(aligned),
            frames[frame.toInt()].offset,
            (samplePosition - aligned).toInt(),
        )
    }

    override fun moveTo(point: RawDsdSeekPoint) {
        nextSamplePosition = point.samplePosition
    }

    override fun readPacket(): RawDsdPacket? {
        check(!closed) { "DST reader is closed." }
        val first = nextSamplePosition
        if (first == format.sampleCount) return null
        check(first in 0 until format.sampleCount && first % RawDsdConstants.SACD_FRAME_SAMPLES == 0L) {
            "DST reader is not on a frame boundary."
        }
        val entry = frames[(first / RawDsdConstants.SACD_FRAME_SAMPLES).toInt()]
        val encoded = input.readBytes(entry.offset, entry.size)
        val decoded = ByteArray(maxPacketBytes)
        if (decoder == null) decoder = decoderFactory.create(format.channels)
        requireNotNull(decoder).decode(encoded, decoded)
        require(entry.crc == null || entry.crc == DstFrameCrc.calculate(decoded)) { "DST frame checksum failed." }
        nextSamplePosition = first + RawDsdConstants.SACD_FRAME_SAMPLES
        return RawDsdPacketizer.packet(format, decoded, first, RawDsdConstants.SACD_FRAME_SAMPLES.toInt())
    }

    override fun close() {
        if (!closed) {
            closed = true
            decoder?.close()
            decoder = null
        }
    }
}
