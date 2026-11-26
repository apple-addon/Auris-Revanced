package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Test

class RootPageMotionTest {
    @Test
    fun nestedBackUsesReverseDirection() {
        val motion = RootPageMotion()
        assertEquals(0, motion.advance("home"))
        assertEquals(1, motion.advance("album"))
        assertEquals(1, motion.advance("artist"))
        assertEquals(-1, motion.advance("album"))
        assertEquals(-1, motion.advance("home"))
    }

    @Test
    fun unchangedPageDoesNotMove() {
        val motion = RootPageMotion()
        motion.advance("home")
        assertEquals(0, motion.advance("home"))
        assertEquals(1, motion.advance("settings"))
        assertEquals(0, motion.advance("settings"))
    }

    @Test
    fun boundedHistoryCannotGrowWithoutLimit() {
        val motion = RootPageMotion(maxHistory = 2)
        repeat(100) { index ->
            motion.advance("page-$index")
            motion.advance("page-${index + 1}")
        }
        // Only the most recent pair is needed to choose the next direction.
        assertEquals(-1, motion.advance("page-99"))
    }
}
