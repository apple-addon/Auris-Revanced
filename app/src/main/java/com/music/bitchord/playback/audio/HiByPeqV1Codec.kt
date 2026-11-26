package com.music.bitchord.playback.audio

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/** One of the ten bands in a HiBy PEQ v1 share. */
data class HiByPeqBand(
    val filter: Int,
    val frequency: Float,
    val on: Int,
    val gain: Float,
    val q: Float,
) {
    init {
        require(filter in 0..255) { "PEQ filter must fit in an unsigned byte" }
        require(on in 0..255) { "PEQ on flag must fit in an unsigned byte" }
        require(frequency.isFinite()) { "PEQ frequency must be finite" }
        require(gain.isFinite()) { "PEQ gain must be finite" }
        require(q.isFinite()) { "PEQ Q must be finite" }
    }
}

data class HiByPeqData(
    val name: String,
    val device: String,
    val description: String,
    val preamp: Float,
    val bands: List<HiByPeqBand>,
) {
    init {
        require(preamp.isFinite()) { "PEQ preamp must be finite" }
        require(bands.size == HiByPeqV1Codec.BAND_COUNT) {
            "PEQ must contain exactly ${HiByPeqV1Codec.BAND_COUNT} bands"
        }
    }
}

/** Encoder and decoder for hiby://plg_peq/v1/ shares. */
object HiByPeqV1Codec {
    const val URI_PREFIX = "hiby://plg_peq/v1/"
    const val BAND_COUNT = 10

    private const val FLOAT_BYTES = 4
    private const val BAND_BYTES = 14
    private const val MAX_TEXT_BYTES = 255

    fun encode(data: HiByPeqData): String =
        URI_PREFIX + Base64.getEncoder().encodeToString(encodeToBytes(data))

    fun decode(uri: String): HiByPeqData {
        require(uri.startsWith(URI_PREFIX)) { "Not a HiBy PEQ v1 URI" }
        val payload = uri.substring(URI_PREFIX.length)
        require(payload.isNotEmpty()) { "PEQ payload is empty" }
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed PEQ Base64 payload", error)
        }
        return decodeFromBytes(bytes)
    }

    fun encodeToBytes(data: HiByPeqData): ByteArray {
        require(data.preamp.isFinite()) { "PEQ preamp must be finite" }
        require(data.bands.size == BAND_COUNT) { "PEQ must contain exactly $BAND_COUNT bands" }

        val name = data.name.utf8Field("name")
        val device = data.device.utf8Field("device")
        val description = data.description.utf8Field("description")
        val output = ByteArrayOutputStream(
            3 + name.size + device.size + description.size + FLOAT_BYTES + BAND_COUNT * BAND_BYTES,
        )
        output.writeLengthPrefixed(name)
        output.writeLengthPrefixed(device)
        output.writeLengthPrefixed(description)
        output.writeFiniteFloat(data.preamp, "preamp")
        data.bands.forEach { band ->
            require(band.filter in 0..255) { "PEQ filter must fit in an unsigned byte" }
            require(band.on in 0..255) { "PEQ on flag must fit in an unsigned byte" }
            output.write(band.filter)
            output.writeFiniteFloat(band.frequency, "frequency")
            output.write(band.on)
            output.writeFiniteFloat(band.gain, "gain")
            output.writeFiniteFloat(band.q, "q")
        }
        return output.toByteArray()
    }

    fun decodeFromBytes(bytes: ByteArray): HiByPeqData {
        val reader = Reader(bytes)
        val name = reader.readText("name")
        val device = reader.readText("device")
        val description = reader.readText("description")
        val preamp = reader.readFloat("preamp")
        val bands = List(BAND_COUNT) {
            HiByPeqBand(
                filter = reader.readUnsignedByte("filter"),
                frequency = reader.readFloat("frequency"),
                on = reader.readUnsignedByte("on"),
                gain = reader.readFloat("gain"),
                q = reader.readFloat("q"),
            )
        }
        reader.requireEnd()
        return HiByPeqData(name, device, description, preamp, bands)
    }

    private fun String.utf8Field(label: String): ByteArray {
        val bytes = toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) {
            "PEQ $label must be at most $MAX_TEXT_BYTES UTF-8 bytes"
        }
        return bytes
    }

    private fun ByteArrayOutputStream.writeLengthPrefixed(bytes: ByteArray) {
        write(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeFiniteFloat(value: Float, label: String) {
        require(value.isFinite()) { "PEQ $label must be finite" }
        val bits = java.lang.Float.floatToRawIntBits(value)
        write(bits ushr 24)
        write(bits ushr 16)
        write(bits ushr 8)
        write(bits)
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun readUnsignedByte(label: String): Int {
            require(offset < bytes.size) { "Truncated PEQ $label" }
            return bytes[offset++].toInt() and 0xff
        }

        fun readText(label: String): String {
            val length = readUnsignedByte("$label length")
            return String(readBytes(length, label), StandardCharsets.UTF_8)
        }

        fun readFloat(label: String): Float {
            val bits = (readUnsignedByte(label) shl 24) or
                (readUnsignedByte(label) shl 16) or
                (readUnsignedByte(label) shl 8) or
                readUnsignedByte(label)
            val value = java.lang.Float.intBitsToFloat(bits)
            require(value.isFinite()) { "PEQ $label must be finite" }
            return value
        }

        fun requireEnd() {
            require(offset == bytes.size) { "Trailing data after PEQ bands" }
        }

        private fun readBytes(length: Int, label: String): ByteArray {
            require(length <= bytes.size - offset) { "Truncated PEQ $label" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }
}
