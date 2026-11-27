package com.music.bitchord.ui.reference

/** Immutable UI/persistence snapshot for the Poweramp-style equaliser hub. */
data class PowerampEqState(
    val equEnabled: Boolean = true,
    val toneEnabled: Boolean = true,
    val limiterEnabled: Boolean = false,
    val parametricMode: Boolean = false,
    val preampDb: Float = 0f,
    val bands: List<PowerampEqBand> = PowerampEqDefaults.bands,
    /** Native PAWS tone gains: 0 = neutral, 1 = +100%, matching dsp_bass/treble_gain. */
    val bassGain: Float = 0f,
    val trebleGain: Float = 0f,
    val balance: Float = 0f,
    val stereoWidth: Float = 0f,
    val tempo: Float = 1f,
    val monoEnabled: Boolean = false,
    val volume: Float = 1f,
    val reverb: PowerampReverbState = PowerampReverbState(),
)

data class PowerampEqBand(
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
)

data class PowerampReverbState(
    val enabled: Boolean = false,
    val damp: Float = 0f,
    val filter: Float = 0f,
    val fade: Float = 0f,
    val preDelay: Float = 0f,
    val preDelayMix: Float = 0f,
    val size: Float = 0f,
    val mix: Float = 0f,
)

enum class PowerampEqPage(val label: String) {
    EQUALIZER("Equalizer"),
    PLAYBACK("Balance & tempo"),
    REVERB("Reverb"),
}

enum class PowerampPresetFilter(val label: String) {
    USER("User"),
    BUILT_IN("Built-in"),
    AUTO_EQ("AutoEq"),
    GRAPHIC("Graphic"),
    PARAMETRIC("Parametric"),
    ASSIGNED("Assigned"),
    DEVICE("Device"),
    RESET("Reset"),
}

/** All transient hub state is hoisted too, so recreation never resets a page or search. */
data class PowerampEqUiState(
    val page: PowerampEqPage = PowerampEqPage.EQUALIZER,
    val selectedBand: Int = 0,
    val presetsVisible: Boolean = false,
    val presetQuery: String = "",
    val presetFilter: PowerampPresetFilter = PowerampPresetFilter.USER,
    val contextPresetId: String? = null,
)

object PowerampEqDefaults {
    const val BAND_COUNT = 10
    const val MIN_DB = -12f
    const val MAX_DB = 12f
    const val MIN_FREQUENCY_HZ = 20f
    const val MAX_FREQUENCY_HZ = 20_000f
    const val MIN_Q = 0.3f
    const val MAX_Q = 30f

    val frequenciesHz: List<Float> = listOf(
        31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f,
    )

    val bands: List<PowerampEqBand> = frequenciesHz.map { PowerampEqBand(it) }

    val roomPreset = PowerampReverbState(
        enabled = true,
        damp = 0.55f,
        filter = 0.35f,
        fade = 0.2f,
        preDelay = 0.1f,
        preDelayMix = 0.45f,
        size = 0.28f,
        mix = 0.2f,
    )

    val hallPreset = PowerampReverbState(
        enabled = true,
        damp = 0.3f,
        filter = 0.6f,
        fade = 0.8f,
        preDelay = 0.25f,
        preDelayMix = 0.35f,
        size = 0.85f,
        mix = 0.35f,
    )
}

fun PowerampEqState.normalized(): PowerampEqState {
    val source = bands.take(PowerampEqDefaults.BAND_COUNT)
    val normalizedBands = List(PowerampEqDefaults.BAND_COUNT) { index ->
        (source.getOrNull(index) ?: PowerampEqDefaults.bands[index]).normalized()
    }
    return copy(
        bands = normalizedBands,
        preampDb = preampDb.finiteOr(0f).coerceIn(-24f, 24f),
        bassGain = bassGain.finiteOr(0f).coerceIn(0f, 1f),
        trebleGain = trebleGain.finiteOr(0f).coerceIn(0f, 1f),
        balance = balance.finiteOr(0f).coerceIn(-1f, 1f),
        stereoWidth = stereoWidth.finiteOr(0f).coerceIn(0f, 1f),
        tempo = tempo.finiteOr(1f).coerceIn(0.5f, 3f),
        volume = volume.finiteOr(1f).coerceIn(0f, 1f),
        reverb = reverb.normalized(),
    )
}

fun PowerampEqBand.normalized(): PowerampEqBand = copy(
    frequencyHz = frequencyHz.finiteOr(1_000f).coerceIn(
        PowerampEqDefaults.MIN_FREQUENCY_HZ,
        PowerampEqDefaults.MAX_FREQUENCY_HZ,
    ),
    gainDb = gainDb.finiteOr(0f).coerceIn(PowerampEqDefaults.MIN_DB, PowerampEqDefaults.MAX_DB),
    q = q.finiteOr(1f).coerceIn(PowerampEqDefaults.MIN_Q, PowerampEqDefaults.MAX_Q),
)

fun PowerampReverbState.normalized(): PowerampReverbState = copy(
    damp = damp.unitOr(0f),
    filter = filter.unitOr(0f),
    fade = fade.unitOr(0f),
    preDelay = preDelay.unitOr(0f),
    preDelayMix = preDelayMix.unitOr(0f),
    size = size.unitOr(0f),
    mix = mix.unitOr(0f),
)

fun PowerampEqUiState.normalized(): PowerampEqUiState = copy(
    selectedBand = selectedBand.coerceIn(0, PowerampEqDefaults.BAND_COUNT - 1),
)

fun PowerampEqUiState.resetPresetFilters(): PowerampEqUiState = copy(
    presetQuery = "",
    presetFilter = PowerampPresetFilter.USER,
    contextPresetId = null,
)

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

internal fun Float.unitOr(fallback: Float): Float = finiteOr(fallback).coerceIn(0f, 1f)
