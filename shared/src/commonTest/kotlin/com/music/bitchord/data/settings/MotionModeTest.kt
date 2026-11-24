package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionModeTest {
    @Test
    fun userChoiceIsKeptWithoutSystemConstraints() {
        MotionMode.entries.forEach { mode ->
            assertEquals(mode, resolveMotionMode(mode, false, false))
        }
    }

    @Test
    fun batterySaverCapsOnlyFullAtReduced() {
        assertEquals(MotionMode.REDUCED, resolveMotionMode(MotionMode.FULL, false, true))
        assertEquals(MotionMode.REDUCED, resolveMotionMode(MotionMode.REDUCED, false, true))
        assertEquals(MotionMode.OFF, resolveMotionMode(MotionMode.OFF, false, true))
    }

    @Test
    fun systemReducedMotionAlwaysWinsWithoutChangingRequestedMode() {
        MotionMode.entries.forEach { requested ->
            assertEquals(MotionMode.OFF, resolveMotionMode(requested, true, false))
            assertEquals(MotionMode.OFF, resolveMotionMode(requested, true, true))
        }
    }
}
