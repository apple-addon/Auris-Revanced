package com.music.bitchord.playback.audio

/**
 * One parametric band: where it sits, how wide it is, and how hard it works.
 *
 * The ranges mirror the reference surface the UI presents: 20 Hz–20 kHz,
 * Q 0.1–10, gain −15…+15 dB. Values are clamped again inside the processor,
 * because a stored or imported preset is not trusted input.
 */
data class PeqBand(
    val frequencyHz: Float,
    val q: Float,
    val gainDb: Float,
    val kind: BiquadKind = BiquadKind.PEAKING,
    val enabled: Boolean = true,
)

/**
 * Ten-band clean-room parametric equaliser used by the HiBy-style mode.
 *
 * This is deliberately separate from BitChord's existing graphic equaliser.
 * That one has a fixed layout with a tone pad; this one exposes frequency, Q
 * and gain per band. Both use standard filter maths, not proprietary Poweramp
 * or HiBy coefficients. No reference-app parity is claimed without sweeps.
 *
 * The default frequencies are the familiar ten-band graphic centres
 * (31…16 kHz) so the processor is useful the moment it is switched on, but
 * every band can be retuned.
 */
class ParametricEqProcessor : FloatAudioProcessor {

    private val bank = BiquadBank(BAND_COUNT)

    val isEnabled: Boolean
        get() = bank.isEnabled

    /**
     * Publishes a band set. Called from the settings thread.
     *
     * Bands beyond [BAND_COUNT] are ignored rather than throwing: importers are
     * allowed to hand over more, and the audio thread must never see a partial
     * update.
     */
    fun setTuning(enabled: Boolean, bands: List<PeqBand>, preampDb: Float = 0f) {
        val specs = ArrayList<BiquadSpec>(BAND_COUNT)
        for (index in 0 until BAND_COUNT) {
            val band = bands.getOrNull(index) ?: DEFAULT_BANDS[index]
            if (!band.enabled || band.gainDb == 0f) continue
            specs += BiquadSpec(
                frequencyHz = band.frequencyHz.coerceIn(MIN_HZ, MAX_HZ),
                q = band.q.coerceIn(MIN_Q, MAX_Q),
                gainDb = band.gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
                kind = band.kind,
            )
        }
        // Preamp-only tuning is still active; an empty band list must not
        // silently disable it.
        bank.setTarget(enabled, specs, preampDb)
    }

    override fun configure(sampleRate: Int, channelCount: Int) = bank.configure(sampleRate, channelCount)

    override fun process(block: AudioBlock) = bank.process(block)

    @Suppress("DEPRECATION")
    override fun flush() = bank.flush()

    override fun reset() = bank.reset()

    companion object {
        const val BAND_COUNT = 10

        const val MIN_HZ = 20f
        const val MAX_HZ = 20000f
        const val MIN_Q = 0.1f
        const val MAX_Q = 10f
        const val MAX_GAIN_DB = 15f

        /** HiBy portable defaults: ten centres, Q 0.20, all bands off. */
        val DEFAULT_BANDS: List<PeqBand> = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
            .map { PeqBand(it, 0.20f, 0f, enabled = false) }
    }
}
