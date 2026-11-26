package com.music.bitchord.playback

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The shape of one filter in [EqLayout].
 *
 * Shelves rather than bells at the two ends of the manual tab: a bell centred
 * at 60 Hz leaves 40 Hz almost untouched, so "turn the bass up" doesn't turn up
 * the part of the bass people mean. A shelf lifts everything below its corner,
 * which is what the bottom slider is asked for, and the same argument upside
 * down applies to 14 kHz.
 */
enum class FilterKind { BELL, LOW_SHELF, HIGH_SHELF }

/** A slot's identity: its shape and where on the spectrum it sits. Both are mutable so the band layout can be reconfigured (see [EqLayout.configureManualBands]). */
class FilterSlot(var kind: FilterKind, var frequencyHz: Float)

/**
 * Every filter either tab can ask for, in one fixed list.
 *
 * Both tabs drive the *same* slots, and a slot the current tab doesn't use is
 * held at 0 dB rather than removed. That costs nothing — a section at 0 dB is
 * skipped outright by [EqualizerProcessor] — and it buys the thing that
 * matters: the set of running filters never changes shape, so switching tab or
 * preset is a gain change like any other and glides silently. Rebuilding the
 * cascade instead would drop filter state on the floor mid-signal, which is a
 * click every time.
 *
 * ## Variable band counts
 *
 * The manual tab supports 5..32 bands (the reference's 5/10/12/15/16/24/31/32
 * list, or a fully custom layout). Slots 0..[MAX_MANUAL_COUNT]-1 are the
 * manual bands; [configureManualBands] sets how many are active and where
 * each sits. Slots at or beyond [manualCount] simply stay at 0 dB.
 */
object EqLayout {

    /** Poweramp-compatible ten-band graphic centres — the default layout. */
    val MANUAL_BANDS_HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

    /**
     * Wide enough to overlap its neighbours — the centres are about 1.3 octaves
     * apart, so a Q near 1 means pushing every slider up lifts the whole band
     * evenly instead of leaving scallops between them.
     */
    const val MANUAL_Q = 1.0f

    /** Slots 0..9: the manual tab with the default layout. */
    const val MANUAL_FIRST = 0
    const val DEFAULT_MANUAL_COUNT = 10
    const val MANUAL_COUNT = DEFAULT_MANUAL_COUNT
    const val MIN_MANUAL_COUNT = 5
    const val MAX_MANUAL_COUNT = 32

    /** Slots MAX_MANUAL_COUNT..+2: the tone pad's tilt pair and its mid contour. */
    const val TONE_LOW = MAX_MANUAL_COUNT
    const val TONE_MID = MAX_MANUAL_COUNT + 1
    const val TONE_HIGH = MAX_MANUAL_COUNT + 2

    const val SLOTS = MAX_MANUAL_COUNT + 3

    /** How many manual bands are active right now, always in MIN..MAX. */
    @Volatile
    var manualCount: Int = DEFAULT_MANUAL_COUNT
        private set

    val slots: List<FilterSlot> = List(SLOTS) { index ->
        when {
            index < DEFAULT_MANUAL_COUNT -> {
                val kind = when (index) {
                    0 -> FilterKind.LOW_SHELF
                    DEFAULT_MANUAL_COUNT - 1 -> FilterKind.HIGH_SHELF
                    else -> FilterKind.BELL
                }
                FilterSlot(kind, MANUAL_BANDS_HZ[index])
            }
            index < MAX_MANUAL_COUNT -> FilterSlot(FilterKind.BELL, 0f)
            index == TONE_LOW -> FilterSlot(FilterKind.LOW_SHELF, 250f)
            index == TONE_MID -> FilterSlot(FilterKind.BELL, 1_000f)
            else -> FilterSlot(FilterKind.HIGH_SHELF, 4_000f)
        }
    }

    /**
     * Re-lays the manual bands out over [frequenciesHz]: the first becomes a
     * low shelf, the last a high shelf, the rest bells. Slots beyond the new
     * count are left in place but every curve builder zeroes them.
     */
    @Synchronized
    fun configureManualBands(frequenciesHz: FloatArray) {
        val count = frequenciesHz.size.coerceIn(MIN_MANUAL_COUNT, MAX_MANUAL_COUNT)
        manualCount = count
        for (index in 0 until count) {
            val slot = slots[index]
            slot.kind = when (index) {
                0 -> FilterKind.LOW_SHELF
                count - 1 -> FilterKind.HIGH_SHELF
                else -> FilterKind.BELL
            }
            slot.frequencyHz = frequenciesHz[index].coerceIn(5f, 96_000f)
        }
    }

    /** The active manual band centres, in slot order. */
    fun manualFrequenciesHz(): FloatArray = FloatArray(manualCount) { slots[it].frequencyHz }

    /** How far a manual slider travels either way. */
    const val MANUAL_RANGE_DB = 12f

    /** How far the tone pad travels on each axis, in whole steps. */
    const val TONE_STEPS = 5

    /** Decibels per step of the pad, so a corner is ±6 dB. */
    const val TONE_DB_PER_STEP = 1.2f

    /** Frequency of the tone pad's tilt pair, for the UI to describe. */
    val TONE_TILT_HZ = floatArrayOf(250f, 4_000f)
}

/**
 * The reference band-count list (paws `pref_peq_equ_bands` / entries): 5,
 * "10 (ISO)", 12, "15 (ISO)", 16, 24, "31 (ISO)", 32 — default 10 — plus the
 * custom-band layout (count 5..32 log-spaced between a first and last
 * frequency). Pure functions, so tests need no Android.
 */
object EqBandLayout {
    val BAND_COUNTS = intArrayOf(5, 10, 12, 15, 16, 24, 31, 32)
    val BAND_COUNT_LABELS = arrayOf("5", "10 (ISO)", "12", "15 (ISO)", "16", "24", "31 (ISO)", "32")

    const val DEFAULT_COUNT = EqLayout.DEFAULT_MANUAL_COUNT
    const val MIN_COUNT = EqLayout.MIN_MANUAL_COUNT
    const val MAX_COUNT = EqLayout.MAX_MANUAL_COUNT

    /** Exact reference entry label for [count]; unknown counts print as-is. */
    fun labelFor(count: Int): String {
        val index = BAND_COUNTS.indexOf(count)
        return if (index >= 0) BAND_COUNT_LABELS[index] else count.toString()
    }

    fun isStandardCount(count: Int): Boolean = count in BAND_COUNTS

    /** ISO 1/3-octave centre series, 20 Hz .. 20 kHz (31 points). */
    val ISO_SERIES_HZ = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f,
        500f, 630f, 800f, 1_000f, 1_250f, 1_600f, 2_000f, 2_500f, 3_150f, 4_000f, 5_000f,
        6_300f, 8_000f, 10_000f, 12_500f, 16_000f, 20_000f,
    )

    /**
     * Centres for a standard [count]. 10 keeps the classic Poweramp 31..16k
     * octave table (so existing presets mean the same thing); counts up to 31
     * pick evenly spaced entries from the ISO 1/3-octave series (always
     * strictly increasing); 32 (non-ISO) is log-spaced 20 Hz..20 kHz.
     */
    fun frequenciesFor(count: Int): FloatArray = when (val n = count.coerceIn(MIN_COUNT, MAX_COUNT)) {
        10 -> EqLayout.MANUAL_BANDS_HZ.copyOf()
        in MIN_COUNT..31 -> {
            val last = ISO_SERIES_HZ.lastIndex
            FloatArray(n) { index ->
                ISO_SERIES_HZ[(index.toDouble() * last / (n - 1).coerceAtLeast(1)).roundToInt()]
            }
        }
        else -> logSpaced(n, 20f, 20_000f)
    }

    /**
     * Custom-band centres (reference `equ_custom_bands_num/first_fr/last_fr`):
     * [count] log-spaced between [firstHz] (5..200) and [lastHz] (14k..20k).
     */
    fun customFrequencies(count: Int, firstHz: Float, lastHz: Float): FloatArray = logSpaced(
        count.coerceIn(MIN_COUNT, MAX_COUNT),
        firstHz.coerceIn(5f, 200f),
        lastHz.coerceIn(14_000f, 20_000f),
    )

    /**
     * Maps gains laid out over [oldFreqs] onto [newFreqs] by nearest centre, so
     * changing the band count, adding or deleting a band keeps each surviving
     * band's gain.
     */
    fun remapGains(oldFreqs: FloatArray, oldGains: List<Float>, newFreqs: FloatArray): List<Float> =
        List(newFreqs.size) { index ->
            val target = newFreqs[index]
            val nearest = oldFreqs.indices.minByOrNull { abs(kotlin.math.ln(oldFreqs[it] / target)) } ?: 0
            oldGains.getOrElse(nearest) { 0f }
        }

    private fun logSpaced(count: Int, firstHz: Float, lastHz: Float): FloatArray {
        val first = firstHz.coerceAtLeast(1f)
        val last = lastHz.coerceAtLeast(first + 1f)
        return FloatArray(count) { index ->
            if (count == 1) {
                first
            } else {
                (first.toDouble() * (last.toDouble() / first).pow(index.toDouble() / (count - 1))).toFloat()
            }
        }
    }
}

/**
 * A tuning, rendered: what every slot in [EqLayout] is set to right now.
 *
 * Gains and Qs both, because Broad/Focused moves Q while the pad stays put —
 * see [toneCurve].
 */
class EqCurve(
    val gainsDb: FloatArray,
    val qs: FloatArray,
    /**
     * Make-up attenuation, always at or below zero. Boosting a band raises the
     * peak of the signal with it, and 16-bit PCM has no headroom above full
     * scale to put that in, so the whole curve is pulled down by however much
     * its loudest point was pushed up.
     */
    val preampDb: Float,
) {
    companion object {
        val FLAT = of(FloatArray(EqLayout.SLOTS), FloatArray(EqLayout.SLOTS) { 0.707f })

        /** Builds a curve and works out the attenuation it needs. */
        fun of(gainsDb: FloatArray, qs: FloatArray): EqCurve =
            EqCurve(gainsDb, qs, preampFor(gainsDb, qs))
    }
}

/**
 * The manual sliders, as a curve. Slots the tone pad owns stay flat, and manual
 * slots at or beyond [bandCount] stay at 0 dB so an inactive band never runs.
 */
fun manualCurve(bandsDb: List<Float>, bandCount: Int = EqLayout.manualCount): EqCurve {
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    val count = bandCount.coerceIn(EqLayout.MIN_MANUAL_COUNT, EqLayout.MAX_MANUAL_COUNT)
    for (band in 0 until count) {
        gains[EqLayout.MANUAL_FIRST + band] =
            bandsDb.getOrElse(band) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        qs[EqLayout.MANUAL_FIRST + band] = EqLayout.MANUAL_Q
    }
    return EqCurve.of(gains, qs)
}

/**
 * The tone pad, as a curve. Slots the manual tab owns stay flat.
 *
 * [x] tilts: left is warm (low shelf up, high shelf down), right is bright, and
 * the two move by the same amount in opposite directions so the middle of the
 * spectrum — where most of a mix lives — keeps its level whichever way the puck
 * goes. [y] is the contour: down scoops the mids into a V, up pushes them
 * forward.
 *
 * [focused] narrows all three. Apple describes the two settings as wider ranges
 * with a richer sound against specific ranges with less distraction, which is a
 * description of bandwidth and nothing else, so that is the only thing it
 * changes.
 */
fun toneCurve(
    x: Int,
    y: Int,
    focused: Boolean,
    bassHz: Float = EqLayout.TONE_TILT_HZ[0],
    trebleHz: Float = EqLayout.TONE_TILT_HZ[1],
    bassQ: Float = 0.5f,
    trebleQ: Float = 0.5f,
): EqCurve {
    val gains = FloatArray(EqLayout.SLOTS)
    val qs = FloatArray(EqLayout.SLOTS) { 0.707f }
    val steps = EqLayout.TONE_STEPS
    val tilt = x.coerceIn(-steps, steps) * EqLayout.TONE_DB_PER_STEP
    val contour = y.coerceIn(-steps, steps) * EqLayout.TONE_DB_PER_STEP

    gains[EqLayout.TONE_LOW] = -tilt
    gains[EqLayout.TONE_HIGH] = tilt
    gains[EqLayout.TONE_MID] = contour

    // The tone knobs' frequency/Q (reference peq_equ_tone.xml _bassFreq/_bassQ/
    // _trebleFreq/_trebleQ) retune the tilt pair's corners in place.
    EqLayout.slots[EqLayout.TONE_LOW].frequencyHz = bassHz.coerceIn(20f, 2_000f)
    EqLayout.slots[EqLayout.TONE_HIGH].frequencyHz = trebleHz.coerceIn(500f, 20_000f)
    val shelfQ = if (focused) 0.9f else bassQ.coerceIn(0.05f, 8f)
    val highShelfQ = if (focused) 0.9f else trebleQ.coerceIn(0.05f, 8f)
    val bellQ = if (focused) 2.2f else 0.7f
    qs[EqLayout.TONE_LOW] = shelfQ
    qs[EqLayout.TONE_HIGH] = highShelfQ
    qs[EqLayout.TONE_MID] = bellQ
    return EqCurve.of(gains, qs)
}

/**
 * How far down the curve has to be pulled to stop it clipping.
 *
 * Taking the largest single band gain would under-read it: the bands overlap,
 * so two neighbours at +6 dB are worth more than +6 dB between them. This walks
 * the summed response instead — magnitudes multiply through a cascade, so
 * decibels add — and takes its actual peak. Runs on whoever changed the
 * setting, never on the audio thread.
 */
private fun preampFor(gainsDb: FloatArray, qs: FloatArray): Float {
    var peak = 0f
    for (point in 0 until RESPONSE_POINTS) {
        val hz = responseFrequency(point)
        var sum = 0f
        for (slot in 0 until EqLayout.SLOTS) {
            sum += sectionGainDb(EqLayout.slots[slot], gainsDb[slot], qs[slot], hz)
        }
        if (sum > peak) peak = sum
    }
    return -peak
}

/** Log-spaced across the audible band: 20 Hz to 20 kHz. */
private fun responseFrequency(point: Int): Float {
    val fraction = point.toDouble() / (RESPONSE_POINTS - 1)
    return (20.0 * (1_000.0).pow(fraction)).toFloat()
}

/**
 * One section's contribution at [hz], in decibels.
 *
 * The analog prototypes these are the magnitudes of are exactly what
 * [EqualizerProcessor]'s state-variable mixes realise — the bilinear transform
 * only bends the frequency axis near Nyquist, which is nowhere near where a
 * peak worth attenuating for lands.
 */
internal fun sectionGainDb(slot: FilterSlot, gainDb: Float, q: Float, hz: Float): Float {
    if (abs(gainDb) < 0.01f) return 0f
    val a = 10.0.pow(gainDb / 40.0)
    val a2 = a * a
    val x = (hz / slot.frequencyHz).toDouble()
    val x2 = x * x
    val qq = (q * q).toDouble()
    val magnitude = when (slot.kind) {
        FilterKind.BELL -> {
            val flat = (1 - x2) * (1 - x2)
            sqrt((flat + x2 * a2 / qq) / (flat + x2 / (a2 * qq)))
        }
        FilterKind.LOW_SHELF -> {
            val common = x2 * a / qq
            a * sqrt(((a - x2) * (a - x2) + common) / ((1 - a * x2) * (1 - a * x2) + common))
        }
        FilterKind.HIGH_SHELF -> {
            val common = x2 * a / qq
            a * sqrt(((1 - a * x2) * (1 - a * x2) + common) / ((a - x2) * (a - x2) + common))
        }
    }
    return (20.0 * log10(magnitude)).toFloat()
}

/** Enough to find the peak of a curve this smooth without wasting time on it. */
private const val RESPONSE_POINTS = 96
