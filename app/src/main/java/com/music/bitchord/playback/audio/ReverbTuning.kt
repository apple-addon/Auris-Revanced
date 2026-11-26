package com.music.bitchord.playback.audio

/**
 * Immutable controls for JamesDSP's custom reverb network.
 *
 * A disabled tuning lets [JamesDspConfig.reverbPreset] retain its usual
 * behaviour. Every continuous control is normalised to 0..1 by [sanitized]
 * before it crosses the settings/audio-thread boundary.
 */
data class ReverbTuning(
    val enabled: Boolean = false,
    val damp: Float = 0f,
    val filter: Float = 0f,
    val fade: Float = 0f,
    val preDelay: Float = 0f,
    val preDelayMix: Float = 0f,
    val size: Float = 0f,
    val mix: Float = 0f,
) {
    fun sanitized(): ReverbTuning = copy(
        damp = damp.normalized(),
        filter = filter.normalized(),
        fade = fade.normalized(),
        preDelay = preDelay.normalized(),
        preDelayMix = preDelayMix.normalized(),
        size = size.normalized(),
        mix = mix.normalized(),
    )

    private companion object {
        fun Float.normalized(): Float =
            if (isFinite()) coerceIn(0f, 1f) else 0f
    }
}
