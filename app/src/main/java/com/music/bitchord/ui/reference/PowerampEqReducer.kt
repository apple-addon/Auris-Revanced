package com.music.bitchord.ui.reference

/** Pure atomic updates. The caller persists the returned snapshot and forwards it to native audio. */
object PowerampEqReducer {
    fun updateBand(
        state: PowerampEqState,
        index: Int,
        update: (PowerampEqBand) -> PowerampEqBand,
    ): PowerampEqState {
        if (index !in 0 until PowerampEqDefaults.BAND_COUNT) return state.normalized()
        val current = state.normalized()
        return current.copy(
            bands = current.bands.mapIndexed { bandIndex, band ->
                if (bandIndex == index) update(band).normalized() else band
            },
        )
    }

    fun setPreamp(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(preampDb = value.finiteOr(0f).coerceIn(-24f, 24f))

    fun setBass(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(bassDb = value.db())

    fun setTreble(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(trebleDb = value.db())

    fun setBalance(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(balance = value.finiteOr(0f).coerceIn(-1f, 1f))

    fun setStereoWidth(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(stereoWidth = value.finiteOr(0f).coerceIn(-1f, 1f))

    fun setTempo(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(tempo = value.finiteOr(1f).coerceIn(0.5f, 2f))

    fun setVolume(state: PowerampEqState, value: Float): PowerampEqState =
        state.normalized().copy(volume = value.unitOr(1f))

    fun updateReverb(
        state: PowerampEqState,
        update: (PowerampReverbState) -> PowerampReverbState,
    ): PowerampEqState = state.normalized().let { current ->
        current.copy(reverb = update(current.reverb).normalized())
    }

    fun resetReverb(state: PowerampEqState): PowerampEqState = state.normalized().copy(
        reverb = PowerampReverbState(enabled = state.reverb.enabled),
    )

    private fun Float.db(): Float = finiteOr(0f).coerceIn(
        PowerampEqDefaults.MIN_DB,
        PowerampEqDefaults.MAX_DB,
    )
}
