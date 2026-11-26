package com.music.bitchord.playback.audio

/**
 * Which reference engine's controls are allowed to shape the sound.
 *
 * The modes exist to stop two equalisers being stacked while the UI claims one
 * app's sound. They are deliberately honest about what they are:
 *
 * - [POWERAMP] runs BitChord's tone/equaliser and JamesDSP stages — the
 *   Poweramp-shaped surface.
 * - [HIBY] runs the MSEB and parametric banks and leaves the Poweramp-shaped
 *   stages bypassed.
 * - [HYBRID] runs HiBy's shaping first and the Poweramp-shaped stages after it.
 *   It is a new sound, not either reference, and the UI says so.
 * - [BIT_PERFECT] runs none of them. The sink's existing bit-perfect switch
 *   performs the actual bypass; this mode keeps the settings from being
 *   applied at all, so a later toggle cannot leak a curve into the path.
 *
 * None of these claims bit-identical output. The proprietary engines are not
 * redistributable, so the DSP is clean-room. L2/L3 parity is not claimed until
 * measured against each reference; telemetry and labels must say "style".
 */
enum class ReferenceMode(val stored: String, val label: String) {
    POWERAMP("poweramp", "Poweramp style"),
    HIBY("hiby", "HiBy style"),
    HYBRID("hybrid", "Hybrid"),
    BIT_PERFECT("bit_perfect", "Bit-Perfect");

    /** BitChord's own equaliser, tone pad, JamesDSP and loudness shaping. */
    val runsPowerampStages: Boolean
        get() = this == POWERAMP || this == HYBRID

    /** MSEB and the parametric bank. */
    val runsHibyStages: Boolean
        get() = this == HIBY || this == HYBRID

    /**
     * Whether any custom DSP may run. Bit-Perfect is the only mode that says
     * no; the sink still owns the final, exact bypass.
     */
    val runsCustomDsp: Boolean
        get() = this != BIT_PERFECT

    companion object {
        fun fromStored(value: String?): ReferenceMode =
            entries.firstOrNull { it.stored == value } ?: POWERAMP
    }
}
