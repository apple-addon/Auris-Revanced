package com.music.bitchord.playback.tracker

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.Locale

/** The stock extractors with MOD/IT/S3M/XM decoding ahead of them. */
@UnstableApi
class TrackerExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        arrayOf(TrackerExtractor(), *delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = arrayOf(
        TrackerExtractor(uri.path?.substringAfterLast('.', missingDelimiterValue = "")),
        *delegate.createExtractors(uri, responseHeaders),
    )
}

/**
 * Media3 extractor that turns a tracker module into gapless raw Float32 PCM.
 *
 * libopenmpt needs the complete module for sample/pattern random access. The
 * input is therefore read once into a direct buffer; playback and seeking then
 * consume no further file bytes. One native output buffer and one DataReader
 * are reused for every PCM block.
 */
@UnstableApi
class TrackerExtractor(extensionHint: String? = null) : Extractor {
    private var type = trackerTypeForExtension(extensionHint)
    private lateinit var output: ExtractorOutput
    private var track: TrackOutput? = null
    private var decoder: TrackerDecoder? = null
    private var pendingSeekUs: Long? = null

    private val sniffBytes = ByteArray(MOD_HEADER_SIZE)
    private val sniffBuffer = ByteBuffer.wrap(sniffBytes)
    private val pcm = ByteBuffer.allocateDirect(BLOCK_FRAMES * TrackerDecoder.BYTES_PER_FRAME)
    private val pcmReader = DirectPcmReader(pcm)

    override fun sniff(input: ExtractorInput): Boolean {
        type?.let { return true }
        return try {
            sniffBytes.fill(0)
            val completeModHeader = input.peekFully(sniffBytes, 0, sniffBytes.size, true)
            val detected = detectTrackerType(sniffBuffer, completeModHeader)
            if (detected != null) type = detected
            detected != null
        } catch (_: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        decoder?.let { return readPcm(it) }
        if (input.position != 0L) {
            seekPosition.position = 0
            return Extractor.RESULT_SEEK
        }

        val moduleBytes = readModuleBytes(input)
        val moduleType = type ?: detectTrackerType(moduleBytes, moduleBytes.remaining() >= MOD_HEADER_SIZE)
            ?: throw IllegalArgumentException("Unrecognized tracker module.")
        type = moduleType
        val newDecoder = TrackerDecoder(moduleBytes)
        decoder = newDecoder
        pendingSeekUs?.let(newDecoder::seek)
        pendingSeekUs = null

        track = output.track(0, C.TRACK_TYPE_AUDIO).also { pcmTrack ->
            pcmTrack.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setContainerMimeType(moduleType.containerMimeType)
                    .setCodecs(moduleType.extension)
                    .setSampleRate(TrackerDecoder.SAMPLE_RATE)
                    .setChannelCount(TrackerDecoder.CHANNEL_COUNT)
                    .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                    .setAverageBitrate(TrackerDecoder.SAMPLE_RATE * TrackerDecoder.CHANNEL_COUNT * 32)
                    .setMaxInputSize(pcm.capacity())
                    .build(),
            )
        }
        output.seekMap(TrackerSeekMap(newDecoder.durationUs))
        output.endTracks()
        Extractor.RESULT_CONTINUE
    } catch (failure: EOFException) {
        throw ParserException.createForMalformedContainer("Truncated tracker module.", failure)
    } catch (failure: IllegalArgumentException) {
        throw ParserException.createForMalformedContainer(
            failure.message ?: "Malformed tracker module.",
            failure,
        )
    }

    private fun readPcm(decoder: TrackerDecoder): Int {
        pcm.clear()
        val timeUs = decoder.positionUs
        val frames = decoder.read(pcm, BLOCK_FRAMES)
        if (frames == 0) return Extractor.RESULT_END_OF_INPUT

        val byteCount = frames * TrackerDecoder.BYTES_PER_FRAME
        pcmReader.reset(byteCount)
        val pcmTrack = checkNotNull(track)
        val written = pcmTrack.sampleData(
            pcmReader,
            byteCount,
            false,
            TrackOutput.SAMPLE_DATA_PART_MAIN,
        )
        check(written == byteCount && !pcm.hasRemaining()) { "Media3 consumed an incomplete tracker PCM block." }
        pcmTrack.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, byteCount, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        // Every seek point has byte position zero because the complete module
        // stays loaded natively; only the libopenmpt play position changes.
        val boundedTime = timeUs.coerceAtLeast(0)
        val current = decoder
        if (current == null) pendingSeekUs = boundedTime else current.seek(boundedTime)
    }

    override fun release() {
        decoder?.close()
        decoder = null
        track = null
        pendingSeekUs = null
    }

    private class TrackerSeekMap(private val durationUs: Long) : SeekMap {
        override fun isSeekable(): Boolean = durationUs != TrackerDecoder.TIME_UNSET

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val position = if (isSeekable) timeUs.coerceIn(0, durationUs) else 0
            return SeekMap.SeekPoints(SeekPoint(position, 0))
        }
    }

    /** Adapts the reused direct native buffer to TrackOutput without a heap copy in this layer. */
    private class DirectPcmReader(private val buffer: ByteBuffer) : DataReader {
        fun reset(byteCount: Int) {
            buffer.limit(byteCount)
            buffer.position(0)
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!buffer.hasRemaining()) return -1
            val count = minOf(length, buffer.remaining())
            buffer.get(target, offset, count)
            return count
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 4096
        const val INPUT_CHUNK_BYTES = 64 * 1024
        const val MOD_HEADER_SIZE = 1084
        const val MAX_MODULE_BYTES = Int.MAX_VALUE.toLong() - 8L

        fun readModuleBytes(input: ExtractorInput): ByteBuffer {
            val length = input.length
            return if (length == C.LENGTH_UNSET.toLong()) {
                readUnknownLength(input)
            } else {
                require(length > 0 && length <= MAX_MODULE_BYTES) { "Invalid tracker module length." }
                readKnownLength(input, length.toInt())
            }
        }

        fun readKnownLength(input: ExtractorInput, length: Int): ByteBuffer {
            val result = ByteBuffer.allocateDirect(length)
            val chunk = ByteArray(minOf(INPUT_CHUNK_BYTES, length))
            while (result.hasRemaining()) {
                val count = minOf(chunk.size, result.remaining())
                input.readFully(chunk, 0, count)
                result.put(chunk, 0, count)
            }
            result.flip()
            return result
        }

        fun readUnknownLength(input: ExtractorInput): ByteBuffer {
            var result = ByteBuffer.allocateDirect(INPUT_CHUNK_BYTES)
            val chunk = ByteArray(INPUT_CHUNK_BYTES)
            var total = 0L
            while (true) {
                if (!result.hasRemaining()) result = grow(result)
                val wanted = minOf(chunk.size, result.remaining())
                val count = input.read(chunk, 0, wanted)
                if (count < 0) break
                if (count == 0) {
                    input.readFully(chunk, 0, 1)
                    result.put(chunk[0])
                    total++
                } else {
                    result.put(chunk, 0, count)
                    total += count.toLong()
                }
                require(total <= MAX_MODULE_BYTES) { "Tracker module is too large." }
            }
            require(total > 0) { "Tracker input is empty." }
            result.flip()
            return result
        }

        fun grow(source: ByteBuffer): ByteBuffer {
            require(source.capacity() < MAX_MODULE_BYTES) { "Tracker module is too large." }
            val doubled = source.capacity().toLong() * 2
            val capacity = minOf(MAX_MODULE_BYTES, maxOf(doubled, source.capacity() + 1L)).toInt()
            val result = ByteBuffer.allocateDirect(capacity)
            source.flip()
            result.put(source)
            return result
        }
    }
}

private enum class TrackerType(val extension: String, val containerMimeType: String) {
    MOD("mod", "audio/x-mod"),
    IT("it", "audio/x-it"),
    S3M("s3m", "audio/x-s3m"),
    XM("xm", "audio/x-xm"),
}

private fun trackerTypeForExtension(extension: String?): TrackerType? =
    when (extension?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT)) {
        "mod" -> TrackerType.MOD
        "it" -> TrackerType.IT
        "s3m" -> TrackerType.S3M
        "xm" -> TrackerType.XM
        else -> null
    }

private fun detectTrackerType(input: ByteBuffer, completeModHeader: Boolean): TrackerType? {
    val base = input.position()
    return when {
        matches(input, base, 0, "Extended Module: ") -> TrackerType.XM
        matches(input, base, 0, "IMPM") -> TrackerType.IT
        matches(input, base, 44, "SCRM") -> TrackerType.S3M
        completeModHeader && hasModMagic(input, base) -> TrackerType.MOD
        else -> null
    }
}

private fun matches(input: ByteBuffer, base: Int, offset: Int, magic: String): Boolean {
    if (offset < 0 || base < 0 || base > input.limit() - offset - magic.length) return false
    for (index in magic.indices) {
        if (input.get(base + offset + index) != magic[index].code.toByte()) return false
    }
    return true
}

private fun hasModMagic(input: ByteBuffer, base: Int): Boolean {
    if (base > input.limit() - 1084) return false
    val magic = input.getInt(base + 1080)
    when (magic) {
        0x4D2E4B2E, // M.K.
        0x4D214B21, // M!K!
        0x4D264B21, // M&K!
        0x4E2E542E, // N.T.
        0x464C5434, // FLT4
        0x464C5438, // FLT8
        0x4F4B5441, // OKTA
        0x4F435441, // OCTA
        0x43443831 // CD81
        -> return true
    }
    val b0 = magic ushr 24
    val b1 = magic ushr 16 and 0xFF
    val b2 = magic ushr 8 and 0xFF
    val b3 = magic and 0xFF
    val digit = '0'.code
    val upperDigit = '9'.code
    return (b0 in digit + 1..upperDigit && b1 == 'C'.code && b2 == 'H'.code && b3 == 'N'.code) ||
        (b0 in digit..upperDigit && b1 in digit..upperDigit && (b0 > digit || b1 > digit) && b2 == 'C'.code && b3 == 'H'.code) ||
        (b0 == 'T'.code && b1 == 'D'.code && b2 == 'Z'.code && b3 in digit + 1..upperDigit)
}
