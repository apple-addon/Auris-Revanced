package com.music.bitchord.playback.audio

/** Immutable settings needed for one complete native 94-float snapshot. */
data class NativeReferenceSettings(
    val qualityOptions: Boolean,
    val bitPerfect: Boolean,
    val mode: ReferenceMode,
    val msebEnabled: Boolean,
    val msebValues: FloatArray,
    val msebPreampDb: Float,
    val msebRange: Int,
    val peqEnabled: Boolean,
    val peqBands: List<PeqBand>,
    val peqPreampDb: Float,
    val powerampEqEnabled: Boolean,
    val powerampPreampDb: Float,
    val powerampGainsDb: FloatArray,
    val toneEnabled: Boolean,
    val bassDb: Float,
    val trebleDb: Float,
    val limiterEnabled: Boolean,
    val reverb: ReverbTuning,
    val dvcGainDb: Float,
    val dvcEnabled: Boolean = true,
    val headroomDb: Float = 0f,
    /**
     * Bounded positive compensation gain (dB) applied through the native
     * limiter/DVC path while DVC is disabled and `compensate_dvc_vol` is on.
     * Zero otherwise. The packer clamps it to [DVC_COMPENSATE_MAX_DB], keeps
     * the DVC gain stage on so the gain is actually applied, and forces the
     * limiter on with a ceiling lowered by the same amount, so the boost can
     * never clip silently.
     */
    val dvcCompensateDb: Float = 0f,
)

fun packNativeReferenceSettings(settings: NativeReferenceSettings): FloatArray {
    val out = FloatArray(AurisDspNative.PARAM_COUNT)
    if (!settings.qualityOptions || settings.bitPerfect || settings.mode == ReferenceMode.BIT_PERFECT) {
        out[0] = 0f
        return out
    }
    val mode = when (settings.mode) {
        ReferenceMode.POWERAMP -> 1
        ReferenceMode.HIBY -> 2
        ReferenceMode.HYBRID -> 3
        ReferenceMode.BIT_PERFECT -> 4
    }
    out[0] = mode.toFloat()
    val hiby = settings.mode.runsHibyStages
    val poweramp = settings.mode.runsPowerampStages
    out[1] = if (hiby && settings.msebEnabled) 1f else 0f
    out[2] = settings.msebPreampDb
    out[3] = settings.msebRange.toFloat()
    for (index in 0 until MsebProcessor.DIMENSIONS) {
        out[4 + index] = settings.msebValues.getOrElse(index) { 0f } / 100f
    }
    out[14] = if (hiby && settings.peqEnabled) 1f else 0f
    out[15] = settings.peqPreampDb
    for (index in 0 until ParametricEqProcessor.BAND_COUNT) {
        val band = settings.peqBands.getOrElse(index) { ParametricEqProcessor.DEFAULT_BANDS[index] }
        val base = 16 + index * 5
        out[base] = if (band.enabled) 1f else 0f
        out[base + 1] = when (band.kind) {
            BiquadKind.PEAKING -> 0f
            BiquadKind.LOW_SHELF -> 1f
            BiquadKind.HIGH_SHELF -> 2f
        }
        out[base + 2] = band.frequencyHz
        out[base + 3] = band.q
        out[base + 4] = band.gainDb
    }
    out[66] = if (poweramp && settings.powerampEqEnabled) 1f else 0f
    out[67] = settings.powerampPreampDb
    for (index in 0 until 10) out[68 + index] = settings.powerampGainsDb.getOrElse(index) { 0f }
    out[78] = if (poweramp && settings.toneEnabled) 1f else 0f
    out[79] = settings.bassDb
    out[80] = settings.trebleDb
    val compensateDb = if (poweramp) {
        settings.dvcCompensateDb.coerceIn(0f, DVC_COMPENSATE_MAX_DB)
    } else {
        0f
    }
    // Never clip silently: with a compensation boost the limiter is forced on
    // and its ceiling is lowered so ceiling + compensate <= -0.3 dB.
    out[81] = if (poweramp && (settings.limiterEnabled || compensateDb > 0f)) 1f else 0f
    out[82] = if (compensateDb > 0f) {
        (-compensateDb - 0.3f).coerceIn(-24f, 0f)
    } else {
        -1f
    }
    out[83] = 100f
    val reverb = settings.reverb.sanitized()
    out[84] = if (poweramp && reverb.enabled) 1f else 0f
    out[85] = reverb.damp
    out[86] = reverb.filter
    out[87] = reverb.fade
    out[88] = reverb.preDelay
    out[89] = reverb.preDelayMix
    out[90] = reverb.size
    out[91] = reverb.mix
    // The DVC gain stage also carries the compensation boost, so it stays on
    // while compensating even though DVC proper is off; the listener's volume
    // offset is only included when DVC is actually enabled, so the two never
    // stack.
    out[92] = if (poweramp && (settings.dvcEnabled || compensateDb > 0f)) 1f else 0f
    out[93] = (if (settings.dvcEnabled) settings.dvcGainDb else 0f) +
        settings.headroomDb.coerceIn(-12f, 0f) +
        compensateDb
    return out
}

/** Hard upper bound for the DVC compensation gain handed to the native path. */
const val DVC_COMPENSATE_MAX_DB: Float = 12f
