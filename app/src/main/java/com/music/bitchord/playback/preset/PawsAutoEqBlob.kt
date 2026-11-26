package com.music.bitchord.playback.preset

/** PAWS band type values stored in the low byte of each PaP v1 band descriptor. */
enum class PawsBandType(val wireValue: Int) {
    BASS(0),
    TREBLE(1),
    GRAPHIC(2),
    PEAKING(3),
    LOW_SHELF(4),
    HIGH_SHELF(5);

    companion object {
        fun fromWire(value: Int): PawsBandType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw PresetCodecException("Unsupported PAWS band type $value")
    }
}

/** One PAWS `com.auris.dsp.data.Band`, with values in the same units as PAWS JSON export. */
data class PawsBand(
    val type: PawsBandType,
    val channels: Int,
    val frequencyHz: Int,
    val gain: Float,
    val enabled: Boolean,
    /** Present only for parametric non-tone bands. */
    val q: Float? = null,
    /** Raw packed PAWS/Android color integer; present only when Q is present. */
    val color: Int? = null,
)

/**
 * Parameters decoded from PAWS's native `PaP\u0001` preset representation.
 *
 * [bands] includes PAWS's two leading tone records (bass and treble). [regularBands] is the
 * 32-band graphic or 10-band parametric AutoEq correction. [preamp] is PAWS's raw preamp value,
 * not a value relabeled as HiBy dB. This is configuration data only: no filtering is performed.
 */
data class PawsEqParameters(
    val parametric: Boolean,
    val preamp: Float,
    val bands: List<PawsBand>,
) {
    val regularBands: List<PawsBand> get() = bands.drop(2)
}

/** Strict little-endian reader for the PAWS preset blobs stored in `auto_eq.db`. */
object PawsAutoEqBlob {
    private const val MAGIC = 0x01506150 // Bytes "PaP\u0001", read little-endian.
    private const val HEADER_BYTES = 12
    private const val TONE_BANDS = 2
    private const val BAND_BYTES = 12
    private const val PARAMETRIC_FLAG = 0x00010000
    private const val NORMAL_BAND_FLAG = 0x00001000
    private const val Q_FLAG = 0x00002000
    private const val ENABLED_FLAG = 0x00004000

    fun decode(blob: ByteArray): PawsEqParameters {
        if (blob.size < HEADER_BYTES) throw PresetCodecException("PAWS preset header is truncated")
        val reader = Reader(blob)
        if (reader.int("magic") != MAGIC) throw PresetCodecException("PAWS preset is not PaP v1")
        val descriptor = reader.int("band descriptor")
        if (descriptor and 0xFFFEF000.toInt() != 0) {
            throw PresetCodecException("PAWS preset has unsupported header flags")
        }
        val count = descriptor and 0x0FFF
        if (count < TONE_BANDS || count > (blob.size - HEADER_BYTES) / BAND_BYTES) {
            throw PresetCodecException("PAWS preset band count $count is impossible for its size")
        }
        val parametric = descriptor and PARAMETRIC_FLAG != 0
        val preamp = reader.float("preamp")
        if (!preamp.isFinite() || preamp !in -24.0f..24.0f) {
            throw PresetCodecException("PAWS preamp is outside the supported range")
        }

        val bands = ArrayList<PawsBand>(count)
        repeat(count) { index ->
            val regular = index >= TONE_BANDS
            val flags = reader.int("band $index flags")
            if (flags and 0xFFFF8000.toInt() != 0) {
                throw PresetCodecException("PAWS band $index has unsupported flags")
            }
            val hasQ = flags and Q_FLAG != 0
            val isNormal = flags and NORMAL_BAND_FLAG != 0
            if (regular != isNormal || (regular && hasQ != parametric) || (!regular && hasQ)) {
                throw PresetCodecException("PAWS band $index has inconsistent parametric flags")
            }
            val type = PawsBandType.fromWire(flags and 0xFF)
            if ((!regular && type != if (index == 0) PawsBandType.BASS else PawsBandType.TREBLE) ||
                (regular && !parametric && type != PawsBandType.GRAPHIC) ||
                (regular && parametric && type.wireValue < PawsBandType.PEAKING.wireValue)
            ) {
                throw PresetCodecException("PAWS band $index has an invalid type for this preset")
            }
            val channels = (flags and 0x0F00) ushr 8
            if (channels > 2) throw PresetCodecException("PAWS band $index has invalid channels")
            val gain = reader.float("band $index gain")
            val minimumGain = if (regular) -15.0f else 0.0f
            if (!gain.isFinite() || gain !in minimumGain..15.0f) {
                throw PresetCodecException("PAWS band $index gain is outside the supported range")
            }
            val frequency = reader.int("band $index frequency")
            if (frequency !in 1..24_000) {
                throw PresetCodecException("PAWS band $index frequency is outside 1..24000 Hz")
            }
            val q: Float?
            val color: Int?
            if (hasQ) {
                q = reader.float("band $index Q")
                if (!q.isFinite() || q !in 0.1f..12.0f) {
                    throw PresetCodecException("PAWS band $index Q is outside the supported range")
                }
                color = reader.int("band $index color")
            } else {
                q = null
                color = null
            }
            bands += PawsBand(
                type = type,
                channels = channels,
                frequencyHz = frequency,
                gain = gain,
                enabled = flags and ENABLED_FLAG != 0,
                q = q,
                color = color,
            )
        }
        if (reader.position != blob.size) {
            throw PresetCodecException("PAWS preset has ${blob.size - reader.position} trailing bytes")
        }
        return PawsEqParameters(parametric, preamp, bands)
    }

    private class Reader(private val bytes: ByteArray) {
        var position = 0
            private set

        fun int(field: String): Int {
            requireAvailable(4, field)
            val value = (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }

        fun float(field: String): Float = Float.fromBits(int(field))

        private fun requireAvailable(count: Int, field: String) {
            if (count > bytes.size - position) {
                throw PresetCodecException("PAWS preset is truncated at $field")
            }
        }
    }
}
