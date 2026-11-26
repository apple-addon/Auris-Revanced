package com.music.bitchord.playback.preset

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** One HiBy BandItem. Float narrowing on encode intentionally matches the original codec. */
data class PeqBand(
    val type: Int,
    val frequencyHz: Double,
    /** Raw signed byte used by HiBy's `on` field; normally 0 or 1. */
    val on: Int,
    val gainDb: Double,
    val q: Double,
) {
    constructor(type: Int, frequencyHz: Double, enabled: Boolean, gainDb: Double, q: Double) :
        this(type, frequencyHz, if (enabled) 1 else 0, gainDb, q)

    init {
        if (type !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            throw PresetCodecException("PEQ filter type must fit in one signed byte")
        }
        if (on !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            throw PresetCodecException("PEQ on must fit in one signed byte")
        }
        PeqV1Codec.requireFiniteFloat(frequencyHz, "frequency")
        PeqV1Codec.requireFiniteFloat(gainDb, "gain")
        PeqV1Codec.requireFiniteFloat(q, "Q")
    }

    val enabled: Boolean get() = on != 0
    val filter: Int get() = type
    val freq: Double get() = frequencyHz
    val gain: Double get() = gainDb
}

/** HiBy `PluginDataManager.PeqData`; exactly ten bands are required by the v1 decoder. */
data class PeqPreset(
    val name: String,
    val device: String = "",
    val description: String = "",
    val preampDb: Double = 0.0,
    val bands: List<PeqBand>,
) {
    init {
        if (bands.size != PeqV1Codec.BAND_COUNT) {
            throw PresetCodecException("PEQ must contain exactly ${PeqV1Codec.BAND_COUNT} bands")
        }
        PeqV1Codec.requireFiniteFloat(preampDb, "preamp")
    }

    val preAmp: Double get() = preampDb
}

/**
 * Exact structural codec for `hiby://plg_peq/v1/`.
 *
 * Layout: three unsigned-byte UTF-8 lengths and their bytes, one big-endian float preamp, then
 * ten 14-byte records: signed filter byte, BE float frequency, signed on byte, BE float gain,
 * BE float Q. The URI payload is canonical padded RFC 4648 Base64.
 */
object PeqV1Codec {
    const val PREFIX = "hiby://plg_peq/v1/"
    const val BAND_COUNT = 10
    private const val BAND_BYTES = 14
    private const val FIXED_BYTES = 3 + 4 + BAND_COUNT * BAND_BYTES
    private const val MAX_TEXT_BYTES = 255
    private const val MAX_BINARY_BYTES = FIXED_BYTES + 3 * MAX_TEXT_BYTES
    private const val MAX_BASE64_CHARS = 4 * ((MAX_BINARY_BYTES + 2) / 3)

    fun canDecode(content: String): Boolean = content.startsWith(PREFIX)

    fun toBytes(preset: PeqPreset): ByteArray {
        if (preset.bands.size != BAND_COUNT) {
            throw PresetCodecException("PEQ must contain exactly $BAND_COUNT bands")
        }
        requireFiniteFloat(preset.preampDb, "preamp")
        val name = WireText.encode(preset.name, "PEQ name", MAX_TEXT_BYTES)
        val device = WireText.encode(preset.device, "PEQ device", MAX_TEXT_BYTES)
        val description = WireText.encode(preset.description, "PEQ description", MAX_TEXT_BYTES)
        val output = ByteArrayOutputStream(FIXED_BYTES + name.size + device.size + description.size)
        output.writeLengthAndBytes(name)
        output.writeLengthAndBytes(device)
        output.writeLengthAndBytes(description)
        output.writeFloat(preset.preampDb, "preamp")
        preset.bands.forEachIndexed { index, band ->
            if (band.type !in Byte.MIN_VALUE..Byte.MAX_VALUE || band.on !in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                throw PresetCodecException("PEQ band $index has an out-of-range byte field")
            }
            output.write(band.type)
            output.writeFloat(band.frequencyHz, "band $index frequency")
            output.write(band.on)
            output.writeFloat(band.gainDb, "band $index gain")
            output.writeFloat(band.q, "band $index Q")
        }
        return output.toByteArray()
    }

    fun generateContent(preset: PeqPreset): String =
        PREFIX + Base64.getEncoder().encodeToString(toBytes(preset))

    fun decode(content: String): PeqPreset {
        if (content.isEmpty()) throw PresetCodecException("PEQ content is empty")
        if (!canDecode(content)) throw PresetCodecException("PEQ content has the wrong prefix")
        val payload = content.substring(PREFIX.length)
        if (payload.isEmpty() || payload.length > MAX_BASE64_CHARS || payload.length % 4 != 0) {
            throw PresetCodecException("PEQ Base64 payload has an invalid length")
        }
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (error: IllegalArgumentException) {
            throw PresetCodecException("PEQ payload is not valid Base64", error)
        }
        // Reject non-canonical unused bits and alternate spellings instead of accepting ambiguous data.
        if (Base64.getEncoder().encodeToString(bytes) != payload) {
            throw PresetCodecException("PEQ Base64 payload is not canonical")
        }
        return decodeBytes(bytes)
    }

    fun decodeBytes(bytes: ByteArray): PeqPreset {
        if (bytes.size > MAX_BINARY_BYTES) throw PresetCodecException("PEQ payload is too large")
        val reader = Reader(bytes)
        val name = reader.readText("name")
        val device = reader.readText("device")
        val description = reader.readText("description")
        val preamp = reader.readFloat("preamp").toDouble()
        val bands = List(BAND_COUNT) { index ->
            val type = reader.readSignedByte("band $index filter")
            val frequency = reader.readFloat("band $index frequency").toDouble()
            val on = reader.readSignedByte("band $index on")
            val gain = reader.readFloat("band $index gain").toDouble()
            val q = reader.readFloat("band $index Q").toDouble()
            PeqBand(type, frequency, on, gain, q)
        }
        if (reader.position != bytes.size) {
            throw PresetCodecException("PEQ payload has ${bytes.size - reader.position} trailing bytes")
        }
        return PeqPreset(name, device, description, preamp, bands)
    }

    internal fun requireFiniteFloat(value: Double, field: String) {
        if (!value.isFinite() || !value.toFloat().isFinite()) {
            throw PresetCodecException("PEQ $field must be a finite 32-bit float")
        }
    }

    private fun ByteArrayOutputStream.writeLengthAndBytes(bytes: ByteArray) {
        write(bytes.size)
        write(bytes, 0, bytes.size)
    }

    private fun ByteArrayOutputStream.writeFloat(value: Double, field: String) {
        requireFiniteFloat(value, field)
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(value.toFloat()).array()
        write(bytes, 0, bytes.size)
    }

    private class Reader(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        fun readText(field: String): String {
            val length = readUnsignedByte("$field length")
            requireAvailable(length, field)
            val value = WireText.decode(bytes, position, length, "PEQ $field")
            position += length
            return value
        }

        fun readSignedByte(field: String): Int {
            requireAvailable(1, field)
            return bytes[position++].toInt()
        }

        fun readFloat(field: String): Float {
            requireAvailable(4, field)
            val value = ByteBuffer.wrap(bytes, position, 4).order(ByteOrder.BIG_ENDIAN).float
            position += 4
            if (!value.isFinite()) throw PresetCodecException("PEQ $field is not finite")
            return value
        }

        private fun readUnsignedByte(field: String): Int {
            requireAvailable(1, field)
            return bytes[position++].toInt() and 0xFF
        }

        private fun requireAvailable(count: Int, field: String) {
            if (count > bytes.size - position) {
                throw PresetCodecException("PEQ payload is truncated at $field")
            }
        }
    }
}
