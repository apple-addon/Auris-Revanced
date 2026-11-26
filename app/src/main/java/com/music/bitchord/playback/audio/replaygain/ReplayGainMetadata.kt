package com.music.bitchord.playback.audio.replaygain

import kotlin.math.min

/** Selects which ReplayGain pair supplies the track's level correction. */
enum class ReplayGainSource {
    TRACK,
    ALBUM,

    /** Album in album order, track elsewhere; either falls back to the other pair. */
    SMART,
}

/**
 * Immutable, sanitized ReplayGain values for one track.
 *
 * Numeric constructor arguments are gains in dB and peaks in linear full-scale
 * units. Invalid and non-finite values become absent. Gains are bounded to
 * +/-60 dB and peaks to 1000x full scale so corrupt tags cannot create an
 * unbounded audio gain. A peak must be greater than zero to be usable.
 */
class ReplayGainMetadata(
    trackGainDb: Number? = null,
    albumGainDb: Number? = null,
    trackPeak: Number? = null,
    albumPeak: Number? = null,
) {
    val trackGainDb: Float? = sanitizeGain(trackGainDb)
    val albumGainDb: Float? = sanitizeGain(albumGainDb)
    val trackPeak: Float? = sanitizePeak(trackPeak)
    val albumPeak: Float? = sanitizePeak(albumPeak)

    val hasReplayGain: Boolean
        get() = trackGainDb != null || albumGainDb != null

    val isEmpty: Boolean
        get() = trackGainDb == null && albumGainDb == null && trackPeak == null && albumPeak == null

    companion object {
        const val TRACK_GAIN_KEY = "REPLAYGAIN_TRACK_GAIN"
        const val ALBUM_GAIN_KEY = "REPLAYGAIN_ALBUM_GAIN"
        const val TRACK_PEAK_KEY = "REPLAYGAIN_TRACK_PEAK"
        const val ALBUM_PEAK_KEY = "REPLAYGAIN_ALBUM_PEAK"

        const val MIN_GAIN_DB = -60f
        const val MAX_GAIN_DB = 60f
        const val MAX_PEAK = 1000f

        @JvmField
        val EMPTY = ReplayGainMetadata()

        /**
         * Parses Vorbis-comment/TXXX-style tags. Gain values are dB with an
         * optional `dB` suffix. Peak values are linear by default and may use
         * an explicit `dB` suffix. Keys are matched case-insensitively.
         */
        @JvmStatic
        fun fromMetadata(tags: Map<String, *>?): ReplayGainMetadata {
            if (tags == null) return EMPTY
            return ReplayGainMetadata(
                trackGainDb = parseGain(tags.valueFor(TRACK_GAIN_KEY)),
                albumGainDb = parseGain(tags.valueFor(ALBUM_GAIN_KEY)),
                trackPeak = parsePeak(tags.valueFor(TRACK_PEAK_KEY)),
                albumPeak = parsePeak(tags.valueFor(ALBUM_PEAK_KEY)),
            )
        }

        /** Alias for callers whose metadata source is already a tag map. */
        @JvmStatic
        fun fromTags(tags: Map<String, *>?): ReplayGainMetadata = fromMetadata(tags)

        private fun Map<String, *>.valueFor(key: String): Any? =
            this[key] ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

        private fun parseGain(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is CharSequence -> numericPart(value.toString()).first.toDoubleOrNull()
            else -> null
        }

        private fun parsePeak(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is CharSequence -> {
                val (number, isDb) = numericPart(value.toString())
                val parsed = number.toDoubleOrNull() ?: return null
                if (!isDb || !parsed.isFinite()) {
                    parsed
                } else if (parsed >= MAX_PEAK_DB) {
                    // Clamp before pow so a finite but enormous dB tag cannot
                    // overflow to infinity and accidentally become absent.
                    MAX_PEAK.toDouble()
                } else {
                    Math.pow(10.0, parsed / 20.0)
                }
            }
            else -> null
        }

        /** Returns the numeric text and whether an explicit dB unit was present. */
        private fun numericPart(raw: String): Pair<String, Boolean> {
            val trimmed = raw.trim()
            val isDb = trimmed.endsWith("dB", ignoreCase = true)
            return (if (isDb) trimmed.dropLast(2).trim() else trimmed) to isDb
        }

        private fun sanitizeGain(value: Number?): Float? {
            val number = value?.toDouble() ?: return null
            if (!number.isFinite()) return null
            return number.coerceIn(MIN_GAIN_DB.toDouble(), MAX_GAIN_DB.toDouble()).toFloat()
        }

        private fun sanitizePeak(value: Number?): Float? {
            val number = value?.toDouble() ?: return null
            if (!number.isFinite() || number <= 0.0) return null
            val peak = number.coerceAtMost(MAX_PEAK.toDouble()).toFloat()
            return peak.takeIf { it > 0f }
        }

        private const val MAX_PEAK_DB = 60.0 // 20 * log10(MAX_PEAK)
    }
}

/**
 * One complete processor state, including all derived gain decisions.
 *
 * Instances are built off the audio thread and published with a single
 * volatile reference. The audio thread therefore never observes a new source
 * with an old preamp, peak, or enable flag.
 */
class ReplayGainSnapshot internal constructor(
    val enabled: Boolean,
    val source: ReplayGainSource,
    val metadata: ReplayGainMetadata,
    val albumContext: Boolean,
    rgPreampDb: Float,
    nonRgPreampDb: Float,
    val preventClipping: Boolean,
) {
    val rgPreampDb: Float = sanitizePreamp(rgPreampDb)
    val nonRgPreampDb: Float = sanitizePreamp(nonRgPreampDb)

    /** Actual gain source after fallback; null means non-RG preamp is used. */
    val selectedSource: ReplayGainSource?
    val selectedGainDb: Float?
    val selectedPeak: Float?

    /** Metadata gain plus the applicable preamp, before peak prevention. */
    val targetGainDb: Float

    /** Final finite linear multiplier, including metadata peak prevention. */
    val linearGain: Float

    init {
        val preferAlbum = source == ReplayGainSource.ALBUM ||
            (source == ReplayGainSource.SMART && albumContext)
        selectedSource = when {
            preferAlbum && metadata.albumGainDb != null -> ReplayGainSource.ALBUM
            preferAlbum && metadata.trackGainDb != null -> ReplayGainSource.TRACK
            !preferAlbum && metadata.trackGainDb != null -> ReplayGainSource.TRACK
            !preferAlbum && metadata.albumGainDb != null -> ReplayGainSource.ALBUM
            else -> null
        }
        selectedGainDb = when (selectedSource) {
            ReplayGainSource.TRACK -> metadata.trackGainDb
            ReplayGainSource.ALBUM -> metadata.albumGainDb
            else -> null
        }
        selectedPeak = when (selectedSource) {
            ReplayGainSource.TRACK -> metadata.trackPeak
            ReplayGainSource.ALBUM -> metadata.albumPeak
            else -> null
        }

        val preampDb = if (selectedGainDb == null) this.nonRgPreampDb else this.rgPreampDb
        targetGainDb = (selectedGainDb ?: 0f) + preampDb
        var gain = dbToLinear(targetGainDb)
        if (preventClipping) {
            val peak = selectedPeak
            if (peak != null) gain = min(gain, 1f / peak)
        }
        // All inputs are bounded above, so this is a final defensive guard,
        // not part of normal gain selection. Unity is the safe failure mode.
        linearGain = gain.takeIf { it.isFinite() && it >= 0f } ?: 1f
    }

    /** True only when processing returns before reading or writing a sample. */
    val isBypassed: Boolean
        get() = !enabled || linearGain == 1f

    companion object {
        const val MIN_PREAMP_DB = -24f
        const val MAX_PREAMP_DB = 24f

        private fun sanitizePreamp(value: Float): Float =
            if (value.isFinite()) value.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB) else 0f

        private fun dbToLinear(valueDb: Float): Float =
            Math.pow(10.0, valueDb.toDouble() / 20.0).toFloat()
    }
}
