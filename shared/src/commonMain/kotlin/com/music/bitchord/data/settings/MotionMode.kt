package com.music.bitchord.data.settings

/** User-selected and system-constrained motion levels shared by Android and desktop. */
enum class MotionMode {
    /** Full transitions and decorative motion. */
    FULL,
    /** Short transitions; decorative/repeating effects stay off. */
    REDUCED,
    /** No animation. State changes are drawn directly at their final value. */
    OFF,
}

/** Apply system constraints without overwriting the listener's saved preference. */
fun resolveMotionMode(
    requested: MotionMode,
    systemReducedMotion: Boolean,
    batterySaver: Boolean,
): MotionMode = when {
    systemReducedMotion -> MotionMode.OFF
    batterySaver && requested == MotionMode.FULL -> MotionMode.REDUCED
    else -> requested
}
