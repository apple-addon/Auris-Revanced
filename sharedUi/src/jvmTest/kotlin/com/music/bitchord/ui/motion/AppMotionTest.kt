package com.music.bitchord.ui.motion

import androidx.compose.animation.core.SnapSpec
import com.music.bitchord.data.settings.MotionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppMotionTest {
    @Test
    fun durationsAreSnappyAndReducedIsShorter() {
        assertEquals(240, AppMotion.duration(MotionMode.FULL))
        assertEquals(150, AppMotion.duration(MotionMode.REDUCED))
        assertEquals(0, AppMotion.duration(MotionMode.OFF))
    }

    @Test
    fun customDurationsStayInsideRequestedMotionBudget() {
        assertEquals(340, AppMotion.duration(MotionMode.FULL, fullMs = 340))
        assertEquals(120, AppMotion.duration(MotionMode.REDUCED, reducedMs = 120))
    }

    @Test
    fun offSnapsSpringsAndDisablesEveryMotionClass() {
        assertTrue(AppMotion.spring<Float>(MotionMode.OFF) is SnapSpec)
        assertFalse(MotionMode.OFF.transitionsEnabled)
        assertFalse(MotionMode.OFF.decorativeEnabled)
        assertTrue(MotionMode.REDUCED.transitionsEnabled)
        assertFalse(MotionMode.REDUCED.decorativeEnabled)
        assertTrue(MotionMode.FULL.decorativeEnabled)
    }
}
