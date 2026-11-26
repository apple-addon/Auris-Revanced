package com.music.bitchord.playback.dsd

/** DoP 1.1 constants. A marker is shared by every channel in one PCM frame. */
object Dop {
    const val MARKER_FIRST: Int = 0x05
    const val MARKER_SECOND: Int = 0xfa
    const val DSD_BITS_PER_CHANNEL: Int = 16
    const val PCM_BYTES_PER_CHANNEL: Int = 3

    internal fun alternate(marker: Int): Int = if (marker == MARKER_FIRST) MARKER_SECOND else MARKER_FIRST
}

/** Thrown when a DoP stream has an invalid size, word, or marker sequence. */
class DopFormatException(message: String) : IllegalArgumentException(message)

/**
 * Converts chronological, byte-interleaved, MSB-first DSD into DoP 1.1.
 *
 * Each channel contributes two bytes to one 24-bit word. The older byte is
 * bits 15..8 and the newer byte bits 7..0; the marker occupies bits 23..16.
 * Marker phase survives calls so transport-sized blocks need not be aligned
 * to an even number of PCM frames.
 */
class DopEncoder(val channels: Int) {
    private var marker = Dop.MARKER_FIRST

    init {
        require(channels in 1..255) { "DoP channel count must be in 1..255." }
    }

    val nextMarker: Int get() = marker

    /** Restarts a stream at a seek boundary; the first frame always uses 0x05. */
    fun seek() {
        marker = Dop.MARKER_FIRST
    }

    fun reset() = seek()

    /** Returns unsigned 24-bit DoP words in the low 24 bits of each Int. */
    @JvmOverloads
    fun encodeWords(dsd: ByteArray, offset: Int = 0, length: Int = dsd.size - offset): IntArray {
        checkedRange(dsd.size, offset, length)
        val inputFrameBytes = channels * 2
        if (length % inputFrameBytes != 0) {
            throw DopFormatException("DSD input must contain two bytes per channel and frame.")
        }
        val result = IntArray(length / 2)
        var source = offset
        var currentMarker = marker
        var output = 0
        val frames = length / inputFrameBytes
        repeat(frames) {
            repeat(channels) {
                val older = dsd[source++].toInt() and 0xff
                val newer = dsd[source++].toInt() and 0xff
                result[output++] = (currentMarker shl 16) or (older shl 8) or newer
            }
            currentMarker = Dop.alternate(currentMarker)
        }
        marker = currentMarker
        return result
    }

    /** Encodes USB S24_3LE bytes: newer DSD byte, older DSD byte, then marker. */
    @JvmOverloads
    fun encode(dsd: ByteArray, offset: Int = 0, length: Int = dsd.size - offset): ByteArray {
        val words = encodeWords(dsd, offset, length)
        val result = ByteArray(words.size * 3)
        for (index in words.indices) {
            val word = words[index]
            val output = index * 3
            result[output] = word.toByte()
            result[output + 1] = (word ushr 8).toByte()
            result[output + 2] = (word ushr 16).toByte()
        }
        return result
    }
}

/** Strict DoP decoder. All channels must carry the expected marker in every frame. */
class DopDecoder(val channels: Int) {
    private var marker = Dop.MARKER_FIRST

    init {
        require(channels in 1..255) { "DoP channel count must be in 1..255." }
    }

    val nextMarker: Int get() = marker

    /** Discards marker history after a seek; decoding must restart with 0x05. */
    fun seek() {
        marker = Dop.MARKER_FIRST
    }

    fun reset() = seek()

    /**
     * Decodes unsigned 24-bit words into chronological, byte-interleaved DSD.
     * Validation is transactional: a bad word does not advance marker phase.
     */
    @JvmOverloads
    fun decodeWords(pcm: IntArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        checkedRange(pcm.size, offset, length)
        if (length % channels != 0) {
            throw DopFormatException("DoP words must contain complete multichannel PCM frames.")
        }
        val result = ByteArray(length * 2)
        var currentMarker = marker
        var output = 0
        for (index in offset until offset + length) {
            val word = pcm[index]
            val relative = index - offset
            if (word and 0xff000000.toInt() != 0) {
                throw DopFormatException("DoP word $relative has bits outside its 24-bit sample.")
            }
            val channel = relative % channels
            val frame = relative / channels
            val actualMarker = (word ushr 16) and 0xff
            if (actualMarker != currentMarker) {
                throw DopFormatException(
                    "DoP frame $frame channel $channel has marker 0x${actualMarker.toString(16)}; " +
                        "expected 0x${currentMarker.toString(16)}.",
                )
            }
            result[output++] = (word ushr 8).toByte()
            result[output++] = word.toByte()
            if (channel == channels - 1) currentMarker = Dop.alternate(currentMarker)
        }
        marker = currentMarker
        return result
    }

    /** Decodes USB S24_3LE bytes and rejects truncated samples or PCM frames. */
    @JvmOverloads
    fun decode(pcm24Le: ByteArray, offset: Int = 0, length: Int = pcm24Le.size - offset): ByteArray {
        checkedRange(pcm24Le.size, offset, length)
        val wireFrameBytes = channels * Dop.PCM_BYTES_PER_CHANNEL
        if (length % wireFrameBytes != 0) {
            throw DopFormatException("DoP input must contain complete 24-bit multichannel PCM frames.")
        }
        val words = IntArray(length / 3)
        var source = offset
        for (index in words.indices) {
            val low = pcm24Le[source++].toInt() and 0xff
            val middle = pcm24Le[source++].toInt() and 0xff
            val high = pcm24Le[source++].toInt() and 0xff
            words[index] = (high shl 16) or (middle shl 8) or low
        }
        return decodeWords(words)
    }
}

private fun checkedRange(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) {
        throw IllegalArgumentException("Invalid byte range: offset=$offset length=$length size=$size.")
    }
}
