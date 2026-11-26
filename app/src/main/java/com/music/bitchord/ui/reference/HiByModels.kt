package com.music.bitchord.ui.reference

import kotlin.math.exp
import kotlin.math.ln

/** HiBy MSEB values deliberately retain the native -100…+100 range on every axis. */
data class MsebState(
    val enabled: Boolean = true,
    val presetName: String = "Custom",
    val preampDb: Float = 0f,
    /** HiBy's wire-level range-model value. Kept even though it is not a sound-slider value. */
    val range: Int = 0,
    val values: List<Float> = List(MsebControl.entries.size) { 0f },
)

/** Native order: master_temp, bass0, bass1, thickness, vocal, female, male_vocal, female_vocal, instruments, air. */
enum class MsebControl(
    val label: String,
    val negativeEndpoint: String,
    val positiveEndpoint: String,
) {
    OVERALL_TEMPERATURE("Overall temperature", "Cool/Bright", "Warm/Dark"),
    BASS_EXTENSION("Bass extension", "Light", "Deep"),
    BASS_TEXTURE("Bass texture", "Fast", "Thumpy"),
    NOTE_THICKNESS("Note thickness", "Crisp", "Thick"),
    VOICE("Voice", "Recessed/Crisp", "Forward/Radio Edit"),
    FEMALE_OVERTONES("Female overtones", "Detoxed", "Intoxicating"),
    SIBILANCE_LF("Sibilance LF", "Soft", "Crisp"),
    SIBILANCE_HF("Sibilance HF", "Soft", "Crisp"),
    IMPULSE_RESPONSE("Impulse response", "Slow/Musical", "Fast/Hard"),
    AIR("Air", "Soft", "Crisp"),
}

enum class HiByPresetAction {
    SAVE,
    IMPORT,
    SHARE,
}

data class PeqState(
    val enabled: Boolean = true,
    val presetName: String = "Custom",
    val preampDb: Float = 0f,
    val bands: List<PeqBand> = PeqDefaults.bands,
)

data class PeqBand(
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 0.2f,
    val type: PeqFilterType = PeqFilterType.PEAK,
    val enabled: Boolean = true,
)

enum class PeqFilterType(val label: String) {
    LOW_SHELF("Low Shelf"),
    PEAK("Peaking"),
    HIGH_SHELF("High Shelf"),
    LOW_PASS("Low Pass"),
    HIGH_PASS("High Pass"),
    NOTCH("Notch"),
    BAND_PASS("Band Pass"),
}

object PeqDefaults {
    const val BAND_COUNT = 10
    const val MIN_FREQUENCY_HZ = 20f
    const val MAX_FREQUENCY_HZ = 20_000f
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val MIN_Q = 0.2f
    const val MAX_Q = 30f

    val frequenciesHz: List<Float> = listOf(
        31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
    )

    val bands: List<PeqBand> = frequenciesHz.map { PeqBand(it) }
}

object MsebReducer {
    const val MIN_VALUE = -100f
    const val MAX_VALUE = 100f
    const val MIN_PREAMP_DB = -45f
    const val MAX_PREAMP_DB = 45f

    fun setValue(state: MsebState, index: Int, value: Float): MsebState {
        if (index !in MsebControl.entries.indices) return state.normalized()
        val current = state.normalized()
        return current.copy(
            values = current.values.mapIndexed { bandIndex, oldValue ->
                if (bandIndex == index) value.finiteOr(0f).coerceIn(MIN_VALUE, MAX_VALUE) else oldValue
            },
        )
    }

    fun nudge(state: MsebState, index: Int, delta: Float): MsebState {
        val current = state.normalized()
        return setValue(current, index, current.values.getOrElse(index) { 0f } + delta)
    }

    fun setPreamp(state: MsebState, value: Float): MsebState = state.normalized().copy(
        preampDb = value.finiteOr(0f).coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB),
    )

    fun reset(state: MsebState): MsebState = state.normalized().copy(
        preampDb = 0f,
        values = List(MsebControl.entries.size) { 0f },
    )
}

object PeqReducer {
    fun updateBand(state: PeqState, index: Int, update: (PeqBand) -> PeqBand): PeqState {
        if (index !in 0 until PeqDefaults.BAND_COUNT) return state.normalized()
        val current = state.normalized()
        return current.copy(
            bands = current.bands.mapIndexed { bandIndex, band ->
                if (bandIndex == index) update(band).normalized() else band
            },
        )
    }

    fun setPreamp(state: PeqState, value: Float): PeqState = state.normalized().copy(
        preampDb = value.finiteOr(0f).coerceIn(PeqDefaults.MIN_GAIN_DB, PeqDefaults.MAX_GAIN_DB),
    )

    /** Maps a graph drag to a logarithmic frequency and a linear -12…+12 dB gain. */
    fun graphDrag(state: PeqState, index: Int, xFraction: Float, yFraction: Float): PeqState =
        updateBand(state, index) { band ->
            band.copy(
                frequencyHz = peqFractionToFrequency(xFraction),
                gainDb = peqFractionToGain(yFraction),
            )
        }

    fun reset(state: PeqState): PeqState = state.normalized().copy(
        preampDb = 0f,
        bands = PeqDefaults.bands,
    )
}

fun MsebState.normalized(): MsebState = copy(
    preampDb = preampDb.finiteOr(0f).coerceIn(MsebReducer.MIN_PREAMP_DB, MsebReducer.MAX_PREAMP_DB),
    values = List(MsebControl.entries.size) { index ->
        values.getOrElse(index) { 0f }.finiteOr(0f).coerceIn(MsebReducer.MIN_VALUE, MsebReducer.MAX_VALUE)
    },
)

fun PeqState.normalized(): PeqState = copy(
    preampDb = preampDb.finiteOr(0f).coerceIn(PeqDefaults.MIN_GAIN_DB, PeqDefaults.MAX_GAIN_DB),
    bands = List(PeqDefaults.BAND_COUNT) { index ->
        (bands.getOrNull(index) ?: PeqDefaults.bands[index]).normalized()
    },
)

fun PeqBand.normalized(): PeqBand = copy(
    frequencyHz = frequencyHz.finiteOr(1_000f).coerceIn(
        PeqDefaults.MIN_FREQUENCY_HZ,
        PeqDefaults.MAX_FREQUENCY_HZ,
    ),
    gainDb = gainDb.finiteOr(0f).coerceIn(PeqDefaults.MIN_GAIN_DB, PeqDefaults.MAX_GAIN_DB),
    q = q.finiteOr(0.2f).coerceIn(PeqDefaults.MIN_Q, PeqDefaults.MAX_Q),
)

fun peqFrequencyToFraction(frequencyHz: Float): Float {
    val frequency = frequencyHz.finiteOr(PeqDefaults.MIN_FREQUENCY_HZ).coerceIn(
        PeqDefaults.MIN_FREQUENCY_HZ,
        PeqDefaults.MAX_FREQUENCY_HZ,
    )
    return ((ln(frequency) - ln(PeqDefaults.MIN_FREQUENCY_HZ)) /
        (ln(PeqDefaults.MAX_FREQUENCY_HZ) - ln(PeqDefaults.MIN_FREQUENCY_HZ))).coerceIn(0f, 1f)
}

fun peqFractionToFrequency(fraction: Float): Float {
    val fraction = fraction.finiteOr(0f).coerceIn(0f, 1f)
    if (fraction == 0f) return PeqDefaults.MIN_FREQUENCY_HZ
    if (fraction == 1f) return PeqDefaults.MAX_FREQUENCY_HZ
    return exp(
        ln(PeqDefaults.MIN_FREQUENCY_HZ) + fraction *
            (ln(PeqDefaults.MAX_FREQUENCY_HZ) - ln(PeqDefaults.MIN_FREQUENCY_HZ)),
    ).coerceIn(PeqDefaults.MIN_FREQUENCY_HZ, PeqDefaults.MAX_FREQUENCY_HZ)
}

fun peqGainToFraction(gainDb: Float): Float = 1f - (
    (gainDb.finiteOr(0f).coerceIn(PeqDefaults.MIN_GAIN_DB, PeqDefaults.MAX_GAIN_DB) - PeqDefaults.MIN_GAIN_DB) /
        (PeqDefaults.MAX_GAIN_DB - PeqDefaults.MIN_GAIN_DB)
    )

fun peqFractionToGain(fraction: Float): Float {
    val y = fraction.finiteOr(0.5f).coerceIn(0f, 1f)
    return PeqDefaults.MAX_GAIN_DB - y * (PeqDefaults.MAX_GAIN_DB - PeqDefaults.MIN_GAIN_DB)
}
