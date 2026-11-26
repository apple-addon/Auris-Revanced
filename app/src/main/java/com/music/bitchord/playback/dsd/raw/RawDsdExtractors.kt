package com.music.bitchord.playback.dsd.raw

import androidx.media3.common.C
import androidx.media3.common.Format
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
import java.io.EOFException

/**
 * Media3 adapter for a raw packet reader. The custom `audio/x-dsd` sample MIME and codec string are
 * intentional: these bytes are native DSD, not `audio/raw` PCM. A renderer consumes packet sample
 * counts/timestamps from the reader API, or uses Format.encoderPadding for the final U32 word.
 */
@UnstableApi
abstract class BaseRawDsdExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var reader: RawDsdPacketReader? = null
    private var track: TrackOutput? = null

    protected abstract fun openReader(input: RawDsdInput): RawDsdPacketReader

    final override fun init(output: ExtractorOutput) {
        this.output = output
    }

    final override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        val current = reader
        if (current == null) {
            val opened = openReader(ExtractorRawDsdInput(input))
            reader = opened
            val openedTrack = requireNotNull(output).track(0, C.TRACK_TYPE_AUDIO).also { track = it }
            openedTrack.format(
                Format.Builder()
                    .setContainerMimeType(opened.format.container.mimeType)
                    .setSampleMimeType(RawDsdConstants.MIME_DSD)
                    .setCodecs(opened.format.packing.codec)
                    .setSampleRate(opened.format.bitRate)
                    .setChannelCount(opened.format.channels)
                    .setAverageBitrate(opened.format.averageBitRate)
                    .setMaxInputSize(opened.maxPacketBytes)
                    .setEncoderDelay(0)
                    .setEncoderPadding(opened.format.finalPaddingSamples)
                    .build(),
            )
            requireNotNull(output).seekMap(RawDsdSeekMap(opened))
            requireNotNull(output).endTracks()
            Extractor.RESULT_CONTINUE
        } else {
            val packet = current.readPacket()
            if (packet == null) {
                Extractor.RESULT_END_OF_INPUT
            } else {
                val outputTrack = requireNotNull(track)
                outputTrack.sampleData(ParsableByteArray(packet.data), packet.data.size)
                outputTrack.sampleMetadata(
                    packet.timeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    packet.data.size,
                    0,
                    null,
                )
                Extractor.RESULT_CONTINUE
            }
        }
    } catch (seek: RawDsdRepositionException) {
        seekPosition.position = seek.targetPosition
        Extractor.RESULT_SEEK
    } catch (failure: IllegalArgumentException) {
        ParserException.createForMalformedContainer(failure.message ?: "Invalid raw DSD stream.", failure)
            .let { throw it }
    } catch (failure: EOFException) {
        throw ParserException.createForMalformedContainer("Truncated raw DSD stream.", failure)
    } catch (failure: UnsupportedOperationException) {
        throw ParserException.createForUnsupportedContainerFeature(failure.message ?: "Unsupported raw DSD feature.")
    }

    final override fun seek(position: Long, timeUs: Long) {
        reader?.seekToTime(timeUs)
    }

    final override fun release() {
        reader?.close()
        reader = null
        track = null
    }
}

/** DSF/DFF extractor; the existing factory is deliberately left untouched. */
@UnstableApi
class RawDsdExtractor @JvmOverloads constructor(
    private val packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE,
    private val dstDecoderFactory: RawDstDecoderFactory = NativeRawDstDecoderFactory,
) : BaseRawDsdExtractor() {
    private var sniffedContainer: RawDsdContainer? = null

    override fun sniff(input: ExtractorInput): Boolean {
        sniffedContainer = null
        return try {
            val signature = ByteArray(4)
            if (!input.peekFully(signature, 0, 4, true)) return false
            sniffedContainer = when (String(signature, Charsets.US_ASCII)) {
                "DSD " -> RawDsdContainer.DSF
                "FRM8" -> RawDsdContainer.DFF
                else -> null
            }
            sniffedContainer != null
        } catch (_: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun openReader(input: RawDsdInput): RawDsdPacketReader = when (sniffedContainer) {
        RawDsdContainer.DSF -> RawDsdReaders.openDsf(input, packing)
        RawDsdContainer.DFF -> RawDsdReaders.openDff(input, packing, dstDecoderFactory)
        else -> throw IllegalArgumentException("DSF/DFF extractor was not sniffed before reading.")
    }
}

/** Selected-area/selected-track SACD extractor. [SacdIsoParser] exposes the other tracks to callers. */
@UnstableApi
class SacdIsoExtractor @JvmOverloads constructor(
    private val area: SacdAreaKind = SacdAreaKind.STEREO,
    private val trackNumber: Int = 1,
    private val packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE,
    private val dstDecoderFactory: RawDstDecoderFactory = NativeRawDstDecoderFactory,
) : BaseRawDsdExtractor() {
    init {
        require(trackNumber in 1..255) { "Invalid SACD track number." }
    }

    override fun sniff(input: ExtractorInput): Boolean {
        val offsets = longArrayOf(
            SacdIsoParser.MASTER_TOC_LSN * 2_048,
            SacdIsoParser.MASTER_TOC_LSN * 2_064 + 12,
        )
        return try {
            for (offset in offsets) {
                input.resetPeekPosition()
                var remaining = offset
                while (remaining > 0) {
                    val amount = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
                    input.advancePeekPosition(amount)
                    remaining -= amount
                }
                val signature = ByteArray(8)
                if (input.peekFully(signature, 0, 8, true) &&
                    String(signature, Charsets.US_ASCII) == "SACDMTOC") return true
            }
            false
        } catch (_: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun openReader(input: RawDsdInput): RawDsdPacketReader =
        SacdIsoReader.open(input, area, trackNumber, packing, dstDecoderFactory)
}

@UnstableApi
private class RawDsdSeekMap(private val reader: RawDsdPacketReader) : SeekMap {
    override fun isSeekable(): Boolean = true
    override fun getDurationUs(): Long = reader.format.durationUs
    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val point = reader.getSeekPoint(timeUs)
        return SeekMap.SeekPoints(SeekPoint(point.timeUs, point.filePosition))
    }
}

@UnstableApi
private class ExtractorRawDsdInput(private val input: ExtractorInput) : RawDsdInput {
    override val length: Long get() = input.length

    override fun readFully(position: Long, destination: ByteArray, offset: Int, length: Int) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= destination.size - length) {
            "Invalid extractor read range."
        }
        if (position < input.position) throw RawDsdRepositionException(position)
        var remaining = position - input.position
        while (remaining > 0) {
            val amount = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(amount)
            remaining = position - input.position
        }
        input.readFully(destination, offset, length)
    }
}
