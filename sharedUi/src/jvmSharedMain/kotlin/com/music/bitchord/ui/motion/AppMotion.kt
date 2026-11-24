package com.music.bitchord.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.MotionMode
import com.music.bitchord.ui.player.PlayerSettings

/** One motion vocabulary for navigation, controls, artwork, and theme changes. */
object AppMotion {
    const val QUICK_MS = 150
    const val STANDARD_MS = 240
    const val SLOW_MS = 340

    const val PRESS_SCALE = 0.975f
    const val PAGE_OFFSET_FRACTION = 0.035f

    val EaseOut: Easing = FastOutSlowInEasing

    fun duration(
        mode: MotionMode,
        fullMs: Int = STANDARD_MS,
        reducedMs: Int = QUICK_MS,
    ): Int = when (mode) {
        MotionMode.FULL -> fullMs
        MotionMode.REDUCED -> reducedMs
        MotionMode.OFF -> 0
    }

    /** Native, allocation-free tween selection. OFF still uses the framework driver. */
    fun <T> tween(
        mode: MotionMode,
        fullMs: Int = STANDARD_MS,
        reducedMs: Int = QUICK_MS,
        easing: Easing = EaseOut,
    ): FiniteAnimationSpec<T> = tween(durationMillis = duration(mode, fullMs, reducedMs), easing = easing)

    /** Gentle physics for direct manipulation and small control feedback. */
    fun <T> spring(
        mode: MotionMode,
        dampingRatio: Float = 0.86f,
        stiffness: Float = 420f,
    ): FiniteAnimationSpec<T> = if (mode == MotionMode.OFF) {
        snap()
    } else {
        spring(dampingRatio = dampingRatio, stiffness = stiffness)
    }

}

/** Reduced motion keeps simple transitions; decorative loops require FULL. */
val MotionMode.decorativeEnabled: Boolean get() = this == MotionMode.FULL
val MotionMode.transitionsEnabled: Boolean get() = this != MotionMode.OFF

/** Effective mode after the saved preference and platform constraints are combined. */
@Composable
fun rememberMotionMode(): MotionMode =
    PlayerSettings.motionMode.collectAsStateWithLifecycle().value

/** One app-wide answer, so each decorative instance pays no observer. */
val LocalDecorativeMotionEnabled = staticCompositionLocalOf { true }

/** True only while decorative motion is visible and allowed to schedule frames. */
@Composable
fun rememberDecorativeMotionEnabled(): Boolean = LocalDecorativeMotionEnabled.current

/** Physics defaults shared by small spring-driven surfaces. */
object MotionSprings {
    const val Damping = Spring.DampingRatioNoBouncy
    const val Stiffness = 420f
}
