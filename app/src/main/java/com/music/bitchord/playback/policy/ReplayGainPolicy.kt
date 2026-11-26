package com.music.bitchord.playback.policy

import kotlin.math.log10
import kotlin.math.pow

/** Preferred loudness metadata. SMART uses album gain only for continuous album playback. */
enum class ReplayGainSource {
    TRACK,
    ALBUM,
    SMART,
}

/** The tag actually selected after source fallback. */
enum class ReplayGainTagSource {
    TRACK,
    ALBUM,
    NONE,
}

/** Parsed, finite ReplayGain values. Peaks are linear full-scale ratios, not dB. */
data class ReplayGainTags(
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumGainDb: Double? = null,
    val albumPeak: Double? = null,
) {
    val hasTrackGain: Boolean get() = trackGainDb != null
    val hasAlbumGain: Boolean get() = albumGainDb != null

    companion object {
        @JvmStatic
        fun parse(tags: Map<String, String?>): ReplayGainTags = ReplayGainTagParser.parse(tags)
    }
}

/**
 * Tolerant parser for Vorbis/FLAC/Opus, ID3 TXXX and MP4 freeform tag names.
 *
 * ReplayGain gains are decimal dB. R128 gains without a dB suffix are Opus signed Q7.8
 * integers, so -768 means -3 dB. A decimal R128 value, or one explicitly suffixed with
 * dB, is accepted as dB for metadata extractors which have already done that conversion.
 * Peaks are linear by default; an explicit dB suffix is converted with 20-log scaling.
 * ReplayGain wins when both families contain a valid value; R128 is the fallback.
 * Invalid values never hide a later valid duplicate.
 */
object ReplayGainTagParser {
    private const val R128_Q8_SCALE = 256.0
    private const val MAX_ABS_GAIN_DB = 200.0

    private val number = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
    private val gainPattern = Regex("^($number)(?:\\s*dB)?$", RegexOption.IGNORE_CASE)
    private val explicitDbPattern = Regex("^($number)\\s*dB$", RegexOption.IGNORE_CASE)
    private val integerPattern = Regex("^[+-]?\\d+$")
    private val peakPattern = Regex("^($number)(?:\\s*dB)?$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun parse(tags: Map<String, String?>): ReplayGainTags =
        parse(tags.entries.map { it.key to it.value })

    @JvmStatic
    fun parse(tags: Iterable<Pair<String, String?>>): ReplayGainTags {
        val values = mutableMapOf<Tag, Double>()
        for ((rawKey, rawValue) in tags) {
            val tag = tagFor(rawKey) ?: continue
            if (values.containsKey(tag)) continue
            val value = when (tag.family) {
                Family.REPLAYGAIN_GAIN -> parseReplayGain(rawValue)
                Family.R128_GAIN -> parseR128Gain(rawValue)
                Family.PEAK -> parsePeak(rawValue)
            }
            if (value != null) values[tag] = value
        }
        return ReplayGainTags(
            trackGainDb = values[Tag.RG_TRACK_GAIN] ?: values[Tag.R128_TRACK_GAIN],
            trackPeak = values[Tag.RG_TRACK_PEAK] ?: values[Tag.R128_TRACK_PEAK],
            albumGainDb = values[Tag.RG_ALBUM_GAIN] ?: values[Tag.R128_ALBUM_GAIN],
            albumPeak = values[Tag.RG_ALBUM_PEAK] ?: values[Tag.R128_ALBUM_PEAK],
        )
    }

    private fun parseReplayGain(raw: String?): Double? {
        val text = clean(raw) ?: return null
        val match = gainPattern.matchEntire(text) ?: return null
        return validGain(match.groupValues[1].toDoubleOrNull())
    }

    private fun parseR128Gain(raw: String?): Double? {
        val text = clean(raw) ?: return null
        explicitDbPattern.matchEntire(text)?.let { match ->
            return validGain(match.groupValues[1].toDoubleOrNull())
        }
        if (integerPattern.matches(text)) {
            val q8 = text.toLongOrNull() ?: return null
            if (q8 < Short.MIN_VALUE.toLong() || q8 > Short.MAX_VALUE.toLong()) return null
            return validGain(q8 / R128_Q8_SCALE)
        }
        val match = gainPattern.matchEntire(text) ?: return null
        return validGain(match.groupValues[1].toDoubleOrNull())
    }

    private fun parsePeak(raw: String?): Double? {
        val text = clean(raw) ?: return null
        val match = peakPattern.matchEntire(text) ?: return null
        val numeric = match.groupValues[1].toDoubleOrNull() ?: return null
        val isDb = text.endsWith("db", ignoreCase = true)
        val peak = if (isDb) 10.0.pow(numeric / 20.0) else numeric
        return peak.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun validGain(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it in -MAX_ABS_GAIN_DB..MAX_ABS_GAIN_DB }

    private fun clean(value: String?): String? =
        value?.trim { it.isWhitespace() || it == '\u0000' }?.takeIf { it.isNotEmpty() }

    private fun tagFor(rawKey: String): Tag? {
        // TXXX:... and ----:com.apple.iTunes:... both put the useful name last.
        val key = rawKey.substringAfterLast(':')
            .filter(Char::isLetterOrDigit)
            .uppercase()
        return Tag.byNormalizedKey[key]
    }

    private enum class Family {
        REPLAYGAIN_GAIN,
        R128_GAIN,
        PEAK,
    }

    private enum class Tag(val normalizedKey: String, val family: Family) {
        RG_TRACK_GAIN("REPLAYGAINTRACKGAIN", Family.REPLAYGAIN_GAIN),
        RG_TRACK_PEAK("REPLAYGAINTRACKPEAK", Family.PEAK),
        RG_ALBUM_GAIN("REPLAYGAINALBUMGAIN", Family.REPLAYGAIN_GAIN),
        RG_ALBUM_PEAK("REPLAYGAINALBUMPEAK", Family.PEAK),
        R128_TRACK_GAIN("R128TRACKGAIN", Family.R128_GAIN),
        R128_TRACK_PEAK("R128TRACKPEAK", Family.PEAK),
        R128_ALBUM_GAIN("R128ALBUMGAIN", Family.R128_GAIN),
        R128_ALBUM_PEAK("R128ALBUMPEAK", Family.PEAK),
        ;

        companion object {
            val byNormalizedKey: Map<String, Tag> = entries.associateBy(Tag::normalizedKey)
        }
    }
}

data class ReplayGainSettings(
    val enabled: Boolean = true,
    val source: ReplayGainSource = ReplayGainSource.TRACK,
    /** Applied whenever a gain tag was selected. */
    val preampDb: Double = 0.0,
    /** Applied instead of [preampDb] when neither track nor album gain exists. */
    val noTagPreampDb: Double = 0.0,
    val preventClipping: Boolean = true,
    /** Linear full-scale ceiling. 1.0 is 0 dBFS. */
    val ceiling: Double = 1.0,
) {
    init {
        require(preampDb.isFinite() && preampDb in -200.0..200.0) {
            "preampDb must be finite and within +/-200 dB"
        }
        require(noTagPreampDb.isFinite() && noTagPreampDb in -200.0..200.0) {
            "noTagPreampDb must be finite and within +/-200 dB"
        }
        require(ceiling.isFinite() && ceiling > 0.0) { "ceiling must be finite and positive" }
    }
}

data class ReplayGainSelection(
    val requestedSource: ReplayGainSource,
    val tagSource: ReplayGainTagSource,
    val gainDb: Double?,
    val peak: Double?,
    val usedFallback: Boolean,
)

/** Final scalar for one track. Apply once, before later EQ/DSP headroom and volume stages. */
data class ReplayGainSample(
    val linearGain: Double,
    val unclippedLinearGain: Double,
    val appliedGainDb: Double,
    val tagGainDb: Double?,
    val preampDb: Double,
    val peak: Double?,
    val tagSource: ReplayGainTagSource,
    val usedFallback: Boolean,
    val clippingPrevented: Boolean,
) {
    val sampleGain: Float get() = linearGain.toFloat()
}

/** Pure selection and sample-gain math; no decoder, Android metadata, or DSP dependency. */
object ReplayGainPolicy {
    @JvmStatic
    @JvmOverloads
    fun select(
        tags: ReplayGainTags,
        source: ReplayGainSource,
        albumPlayback: Boolean = false,
    ): ReplayGainSelection {
        val order = when (source) {
            ReplayGainSource.TRACK -> listOf(ReplayGainTagSource.TRACK, ReplayGainTagSource.ALBUM)
            ReplayGainSource.ALBUM -> listOf(ReplayGainTagSource.ALBUM, ReplayGainTagSource.TRACK)
            ReplayGainSource.SMART -> if (albumPlayback) {
                listOf(ReplayGainTagSource.ALBUM, ReplayGainTagSource.TRACK)
            } else {
                listOf(ReplayGainTagSource.TRACK, ReplayGainTagSource.ALBUM)
            }
        }
        val selected = order.firstOrNull { tags.gain(it) != null }
        val peakOwner = selected ?: order.firstOrNull { tags.peak(it) != null }
        return ReplayGainSelection(
            requestedSource = source,
            tagSource = selected ?: ReplayGainTagSource.NONE,
            gainDb = selected?.let { tags.gain(it) },
            peak = peakOwner?.let { tags.peak(it) },
            usedFallback = selected != null && selected != order.first(),
        )
    }

    @JvmStatic
    @JvmOverloads
    fun sampleGain(
        tags: ReplayGainTags,
        settings: ReplayGainSettings = ReplayGainSettings(),
        albumPlayback: Boolean = false,
    ): ReplayGainSample {
        if (!settings.enabled) {
            return ReplayGainSample(
                linearGain = 1.0,
                unclippedLinearGain = 1.0,
                appliedGainDb = 0.0,
                tagGainDb = null,
                preampDb = 0.0,
                peak = null,
                tagSource = ReplayGainTagSource.NONE,
                usedFallback = false,
                clippingPrevented = false,
            )
        }

        val selection = select(tags, settings.source, albumPlayback)
        val preamp = if (selection.gainDb == null) settings.noTagPreampDb else settings.preampDb
        val requestedDb = (selection.gainDb ?: 0.0) + preamp
        val unclipped = dbToLinear(requestedDb)
        val peak = selection.peak?.takeIf { it > 0.0 }
        val peakLimitedGain = if (settings.preventClipping && peak != null) {
            settings.ceiling / peak
        } else {
            Double.POSITIVE_INFINITY
        }
        val linear = minOf(unclipped, peakLimitedGain)
        return ReplayGainSample(
            linearGain = linear,
            unclippedLinearGain = unclipped,
            appliedGainDb = linearToDb(linear),
            tagGainDb = selection.gainDb,
            preampDb = preamp,
            peak = selection.peak,
            tagSource = selection.tagSource,
            usedFallback = selection.usedFallback,
            clippingPrevented = linear < unclipped,
        )
    }

    @JvmStatic
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    @JvmStatic
    fun linearToDb(gain: Double): Double = 20.0 * log10(gain)

    private fun ReplayGainTags.gain(source: ReplayGainTagSource): Double? = when (source) {
        ReplayGainTagSource.TRACK -> trackGainDb
        ReplayGainTagSource.ALBUM -> albumGainDb
        ReplayGainTagSource.NONE -> null
    }

    private fun ReplayGainTags.peak(source: ReplayGainTagSource): Double? = when (source) {
        ReplayGainTagSource.TRACK -> trackPeak
        ReplayGainTagSource.ALBUM -> albumPeak
        ReplayGainTagSource.NONE -> null
    }
}
