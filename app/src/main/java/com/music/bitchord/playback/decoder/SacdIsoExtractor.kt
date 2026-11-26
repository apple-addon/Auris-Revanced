package com.music.bitchord.playback.decoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.music.bitchord.playback.dsd.DsdBlockDecoder
import com.music.bitchord.playback.dsd.DsdContainer
import com.music.bitchord.playback.dsd.DsdFormat
import com.music.bitchord.playback.dsd.DsdRawBlock
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

/** Raw-DSD SACD extractor with an exact 75 Hz index and optional PCM fallback. */
@UnstableApi
class SacdIsoExtractor(
    private val trackIndex: Int = 0,
    private val transport: Transport = Transport.DOP,
) : Extractor {
    enum class Transport { DOP, RAW_UAC2, PCM }

    private lateinit var output: ExtractorOutput
    private var source: CachingSacdSource? = null
    private var area: SacdArea? = null
    private var track: SacdTrack? = null
    private var index: SacdTrackIndex? = null
    private var reader: SacdTrackReader? = null
    private var pcmDecoder: DsdBlockDecoder? = null
    private var dop = DopPacker(2)
    private var rawUac2 = RawDsdUac2Packer(2)
    private var trackOutput: TrackOutput? = null
    private var ready = false

    override fun sniff(input: ExtractorInput): Boolean = try {
        val signature = ByteArray(8)
        input.advancePeekPosition(MASTER_TOC_OFFSET)
        input.peekFully(signature, 0, signature.size, true)
        signature.contentEquals("SACDMTOC".toByteArray(Charsets.US_ASCII))
    } catch (_: EOFException) {
        false
    } finally {
        input.resetPeekPosition()
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        if (!ready) {
            require(input.position == 0L) { "SACD input must begin at zero." }
            val random = CachingSacdSource.open(input)
            source = random
            val disc = SacdIsoParser.parse(random)
            val selectedArea = disc.stereoArea ?: disc.multiChannelArea
            requireNotNull(selectedArea) { "SACD image has no playable area." }
            val selectedTrack = selectedArea.tracks.getOrElse(trackIndex) {
                throw SacdFormatException("SACD track $trackIndex does not exist (count=${selectedArea.tracks.size}).")
            }
            val selectedIndex = SacdTrackIndex.build(random, selectedArea, selectedTrack)
            area = selectedArea
            track = selectedTrack
            index = selectedIndex
            reader = SacdTrackReader(random, selectedArea, selectedTrack) { NativeRawDstDecoder(it) }
                .also { it.seekToStart() }
            val channels = selectedArea.channelCount
            dop = DopPacker(channels)
            rawUac2 = RawDsdUac2Packer(channels)
            if (transport == Transport.PCM) {
                val format = DsdFormat(
                    container = DsdContainer.DFF,
                    bitRate = SACD_SAMPLE_RATE,
                    channels = channels,
                    sampleCount = selectedTrack.sampleCount,
                    dataOffset = 0,
                    dataBytes = 1,
                )
                pcmDecoder = DsdBlockDecoder(format)
            }
            trackOutput = output.track(0, C.TRACK_TYPE_AUDIO).also { trackOutput ->
                trackOutput.format(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_RAW)
                        .setContainerMimeType("audio/x-sacd-iso")
                        .setCodecs(if (transport == Transport.PCM) "dsd-pcm" else "dsd-dop")
                        .setSampleRate(
                            when (transport) {
                                Transport.DOP -> DsdTransport.dopCarrierRate(SACD_SAMPLE_RATE)
                                Transport.RAW_UAC2 -> DsdTransport.rawUac2CarrierRate(SACD_SAMPLE_RATE)
                                Transport.PCM -> 176_400
                            },
                        )
                        .setChannelCount(channels)
                        .setPcmEncoding(
                            when (transport) {
                                Transport.DOP -> C.ENCODING_PCM_24BIT
                                Transport.RAW_UAC2 -> C.ENCODING_PCM_32BIT
                                Transport.PCM -> C.ENCODING_PCM_FLOAT
                            },
                        )
                        .setAverageBitrate(if (transport == Transport.DOP) SACD_SAMPLE_RATE * 3 / 2 else 176_400 * 32)
                        .setMaxInputSize(if (transport == Transport.DOP) SACD_BYTES_PER_CHANNEL_FRAME * channels * 3 / 2 else 65_536)
                        .build(),
                )
            }
            output.seekMap(SacdSeekMap(selectedTrack, selectedIndex))
            output.endTracks()
            ready = true
        }
        val raw = reader?.readFrame() ?: return Extractor.RESULT_END_OF_INPUT
        when (transport) {
            Transport.DOP -> {
                val packed = dop.pack(raw.bytes)
                write(packed, raw.timeUs)
            }
            Transport.RAW_UAC2 -> {
                val packed = rawUac2.pack(raw.bytes)
                write(packed, raw.timeUs)
            }
            Transport.PCM -> {
                val decoder = checkNotNull(pcmDecoder)
                val channels = area!!.channelCount
                val chunkCapacity = 8192 - 8192 % channels
                var offset = 0
                while (offset < raw.bytes.size) {
                    val size = minOf(chunkCapacity, raw.bytes.size - offset)
                    val samplesBefore = offset / channels * 8L
                    val block = DsdRawBlock(
                        raw.bytes.copyOfRange(offset, offset + size),
                        size,
                        raw.firstSample + samplesBefore,
                        size / channels * 8L,
                    )
                    val pcm = FloatArray(65_536 / 4)
                    val count = decoder.decode(block, pcm)
                    val bytes = java.nio.ByteBuffer.allocate(count * channels * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    repeat(count * channels) { bytes.putFloat(pcm[it]) }
                    write(bytes.array(), raw.timeUs + samplesBefore * 1_000_000L / SACD_SAMPLE_RATE)
                    offset += size
                }
            }
        }
        Extractor.RESULT_CONTINUE
    } catch (error: SacdFormatException) {
        throw ParserException.createForMalformedContainer(error.message ?: "Invalid SACD image.", error)
    }

    override fun seek(position: Long, timeUs: Long) {
        val point = index?.seek(timeUs) ?: return
        reader?.seek(point)
        dop.reset()
        pcmDecoder?.reset(
            point.point.relativeFrame * SACD_SAMPLES_PER_FRAME / 8,
            point.point.timeUs * 176_400 / 1_000_000,
        )
    }

    override fun release() {
        reader?.close()
        pcmDecoder?.close()
        source?.close()
        reader = null
        pcmDecoder = null
        source = null
        ready = false
    }

    private fun write(bytes: ByteArray, timeUs: Long) {
        val output = trackOutput ?: return
        output.sampleData(ParsableByteArray(bytes), bytes.size)
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    private inner class SacdSeekMap(
        private val sacdTrack: SacdTrack,
        private val sacdIndex: SacdTrackIndex,
    ) : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = sacdTrack.durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val point = sacdIndex.seek(timeUs)
            val bytePosition = point.sourceLsn?.let { it * SACD_SECTOR_BYTES } ?: 0L
            return SeekMap.SeekPoints(SeekPoint(point.point.timeUs, bytePosition))
        }
    }

    /** Disk-backed random access; a large ISO is never retained in the Java heap. */
    private class CachingSacdSource private constructor(
        private val delegate: FileRandomAccessSource,
        private val temporary: File,
    ) : RandomAccessSource by delegate, AutoCloseable {
        override fun close() {
            delegate.close()
            temporary.delete()
        }

        companion object {
            fun open(input: ExtractorInput): CachingSacdSource {
                val file = File.createTempFile("auris-sacd-", ".iso")
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer, 0, buffer.size)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                }
                return CachingSacdSource(FileRandomAccessSource(file), file)
            }
        }
    }

    private companion object {
        const val MASTER_TOC_OFFSET = 510 * SACD_SECTOR_BYTES
    }
}
