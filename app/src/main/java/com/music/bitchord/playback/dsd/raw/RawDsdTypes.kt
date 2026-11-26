package com.music.bitchord.playback.dsd.raw

import com.music.bitchord.playback.dsd.DstDecoder
import com.music.bitchord.playback.dsd.NativeDsdPacker
import com.music.bitchord.playback.dsd.NativeDsdPacking
import java.io.Closeable
import java.io.EOFException
import java.io.IOException

/** Raw DSD word layouts understood by the native-DSD renderer. */
enum class RawDsdPacking(internal val native: NativeDsdPacking, val codec: String) {
    DSD_U32_BE(NativeDsdPacking.DSD_U32_BE, "dsd_u32_be"),
    DSD_U32_LE(NativeDsdPacking.DSD_U32_LE, "dsd_u32_le"),
    ;

    fun frameBytes(channels: Int): Int = native.frameBytes(channels)
}

enum class RawDsdContainer(val mimeType: String) {
    DSF("audio/x-dsf"),
    DFF("audio/x-dff"),
    SACD_ISO("audio/x-sacd-iso"),
}

object RawDsdConstants {
    const val DSD64_RATE = 2_822_400
    const val SACD_FRAME_RATE = 75
    const val SACD_FRAME_SAMPLES = 37_632L
    const val DST_FRAME_BYTES_PER_CHANNEL = 4_704
    const val MIME_DSD = "audio/x-dsd"
}

/** All counts and positions are one-bit samples per channel, never PCM frames. */
data class RawDsdFormat(
    val container: RawDsdContainer,
    val bitRate: Int,
    val channels: Int,
    val sampleCount: Long,
    val packing: RawDsdPacking,
) {
    init {
        require(bitRate in SUPPORTED_RATES) { "Unsupported DSD bit rate: $bitRate." }
        require(channels in 1..6) { "Raw DSD supports one through six channels." }
        require(sampleCount > 0 && sampleCount <= bitRate.toLong() * MAX_DURATION_SECONDS) {
            "Invalid DSD sample count: $sampleCount."
        }
    }

    val durationUs: Long get() = timestampUs(sampleCount)
    val averageBitRate: Int get() = bitRate * channels
    val finalPaddingSamples: Int get() = ((32 - sampleCount % 32) % 32).toInt()

    /** Floor of the exact rational timestamp; packet durations use differences of these values. */
    fun timestampUs(samplePosition: Long): Long {
        require(samplePosition in 0..sampleCount) { "DSD sample position is outside the stream." }
        val seconds = samplePosition / bitRate
        val remainder = samplePosition % bitRate
        return seconds * 1_000_000L + remainder * 1_000_000L / bitRate
    }

    fun sampleAtTime(timeUs: Long): Long = when {
        timeUs <= 0L -> 0L
        timeUs >= durationUs -> sampleCount
        else -> {
            val seconds = timeUs / 1_000_000L
            val remainder = timeUs % 1_000_000L
            (seconds * bitRate + remainder * bitRate / 1_000_000L).coerceIn(0L, sampleCount)
        }
    }

    private companion object {
        const val MAX_DURATION_SECONDS = 7L * 24 * 60 * 60
        val SUPPORTED_RATES = setOf(2_822_400, 5_644_800, 11_289_600, 22_579_200, 45_158_400)
    }
}

/**
 * One renderer-ready access unit. [data] contains complete interleaved U32 words. Only
 * [sampleCount] bits per channel are valid; the final word is zero-padded and must not extend
 * playback beyond [timeUs] + [durationUs]. The array is loaned to the consumer and is not reused.
 */
class RawDsdPacket internal constructor(
    val format: RawDsdFormat,
    val data: ByteArray,
    val firstSample: Long,
    val sampleCount: Int,
) {
    init {
        require(firstSample >= 0 && sampleCount > 0 && firstSample + sampleCount <= format.sampleCount) {
            "Invalid raw DSD packet range."
        }
        require(data.isNotEmpty() && data.size % format.packing.frameBytes(format.channels) == 0) {
            "Raw DSD packets must contain complete multichannel U32 words."
        }
        val capacity = data.size / format.packing.frameBytes(format.channels) * 32
        require(sampleCount <= capacity && capacity - sampleCount < 32) {
            "Raw DSD packet has missing or excess words."
        }
    }

    val timeUs: Long = format.timestampUs(firstSample)
    val endSampleExclusive: Long get() = firstSample + sampleCount
    val endTimeUs: Long get() = format.timestampUs(endSampleExclusive)
    val durationUs: Long get() = endTimeUs - timeUs
}

/** A byte resume position plus the exact raw-sample position reached after demuxing from it. */
data class RawDsdSeekPoint(
    val samplePosition: Long,
    val timeUs: Long,
    val filePosition: Long,
    val skipSamples: Int,
) {
    init {
        require(samplePosition >= 0 && timeUs >= 0 && filePosition >= 0 && skipSamples >= 0) {
            "Invalid raw DSD seek point."
        }
    }
}

interface RawDsdPacketReader : Closeable {
    val format: RawDsdFormat
    val maxPacketBytes: Int
    val nextSamplePosition: Long

    /** Pure query suitable for a Media3 SeekMap; does not move the reader. */
    fun getSeekPoint(timeUs: Long): RawDsdSeekPoint

    /**
     * Moves to the decodable word/frame at or before [samplePosition]. [RawDsdSeekPoint.skipSamples]
     * is the exact amount of leading DSD preroll in the first returned packet.
     */
    fun seekToSample(samplePosition: Long): RawDsdSeekPoint

    fun seekToTime(timeUs: Long): RawDsdSeekPoint = seekToSample(format.sampleAtTime(timeUs))

    /** Returns null only at the declared gapless end of the stream. */
    fun readPacket(): RawDsdPacket?
}

/** Random-access reads; implementations may translate backward reads into extractor seek results. */
interface RawDsdInput {
    val length: Long

    fun readFully(position: Long, destination: ByteArray, offset: Int = 0, length: Int = destination.size - offset)

    companion object {
        const val UNKNOWN_LENGTH = -1L
    }
}

class ByteArrayRawDsdInput(private val bytes: ByteArray) : RawDsdInput {
    override val length: Long get() = bytes.size.toLong()

    override fun readFully(position: Long, destination: ByteArray, offset: Int, length: Int) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= destination.size - length) {
            "Invalid raw DSD read range."
        }
        if (position > bytes.size.toLong() - length) throw EOFException("Truncated raw DSD input.")
        bytes.copyInto(destination, offset, position.toInt(), position.toInt() + length)
    }
}

fun RawDsdInput.readBytes(position: Long, size: Int): ByteArray {
    require(size >= 0) { "Negative raw DSD read size." }
    return ByteArray(size).also { readFully(position, it) }
}

/** Requests a Media3 reposition without losing the packet reader's state. */
internal class RawDsdRepositionException(val targetPosition: Long) : IOException("Seek to $targetPosition.")

abstract class RawDsdReaderBase : RawDsdPacketReader {
    protected abstract fun locate(samplePosition: Long): RawDsdSeekPoint
    protected abstract fun moveTo(point: RawDsdSeekPoint)

    final override fun getSeekPoint(timeUs: Long): RawDsdSeekPoint = locate(format.sampleAtTime(timeUs))

    final override fun seekToSample(samplePosition: Long): RawDsdSeekPoint {
        require(samplePosition in 0..format.sampleCount) { "DSD seek is outside the stream." }
        return locate(samplePosition).also(::moveTo)
    }

    override fun close() = Unit
}

internal object RawDsdPacketizer {
    /** Converts chronological byte-interleaved MSB-first DSD into renderer words. */
    fun packet(
        format: RawDsdFormat,
        canonical: ByteArray,
        firstSample: Long,
        sampleCount: Int,
    ): RawDsdPacket {
        require(firstSample >= 0 && firstSample % 8 == 0L && sampleCount > 0) {
            "Canonical DSD blocks must start on a byte boundary."
        }
        require(firstSample + sampleCount <= format.sampleCount) { "DSD block exceeds the declared stream." }
        val validBytes = (sampleCount + 7) / 8
        require(canonical.size == validBytes * format.channels) {
            "Canonical DSD block size does not match its valid sample count."
        }
        val paddedSamples = (sampleCount + 31) / 32 * 32
        val padded = ByteArray(paddedSamples / 8 * format.channels)
        canonical.copyInto(padded, 0, 0, canonical.size)
        val usedBits = sampleCount % 8
        if (usedBits != 0) {
            val mask = (0xff shl (8 - usedBits)) and 0xff
            val last = (validBytes - 1) * format.channels
            for (channel in 0 until format.channels) {
                padded[last + channel] = (padded[last + channel].toInt() and mask).toByte()
            }
        }
        return RawDsdPacket(
            format,
            NativeDsdPacker.pack(padded, format.channels, format.packing.native),
            firstSample,
            sampleCount,
        )
    }
}

/** Injectable so container tests do not need an Android native library. */
interface RawDstDecoder : AutoCloseable {
    fun decode(encoded: ByteArray, decoded: ByteArray)
}

fun interface RawDstDecoderFactory {
    fun create(channels: Int): RawDstDecoder
}

class NativeRawDstDecoder(channels: Int) : RawDstDecoder {
    private val decoder: DstDecoder

    init {
        if (channels !in 1..2) {
            throw UnsupportedOperationException("The bundled DST decoder supports mono and stereo only.")
        }
        decoder = DstDecoder(channels)
    }

    override fun decode(encoded: ByteArray, decoded: ByteArray) = decoder.decode(encoded, decoded)
    override fun close() = decoder.close()
}

object NativeRawDstDecoderFactory : RawDstDecoderFactory {
    override fun create(channels: Int): RawDstDecoder = NativeRawDstDecoder(channels)
}

internal fun checkedEnd(position: Long, size: Long, limit: Long): Long {
    require(position >= 0 && size >= 0 && limit >= position && size <= limit - position) {
        "DSD chunk exceeds its containing chunk or file."
    }
    return position + size
}

internal fun verifyDeclaredLength(input: RawDsdInput, declaredLength: Long) {
    require(declaredLength >= 0 && (input.length == RawDsdInput.UNKNOWN_LENGTH || input.length == declaredLength)) {
        "DSD container length does not match the input."
    }
}
