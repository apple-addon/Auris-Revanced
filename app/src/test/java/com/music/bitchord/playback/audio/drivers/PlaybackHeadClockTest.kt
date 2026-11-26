package com.music.bitchord.playback.audio.drivers

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHeadClockTest {
    @Test
    fun `accumulates frames and wraps without negative time`() {
        val clock = PlaybackHeadClock()
        val startUs = clock.update(0xFFFFFF00L, 48_000)
        assertEquals((0xFFFFFF00L * 1_000_000L / 48_000), startUs)
        val nextUs = clock.update(0x000000FFL, 48_000)
        assertEquals(startUs + (0x1FFL * 1_000_000L / 48_000), nextUs)
    }

    @Test
    fun `reset starts a new output clock`() {
        val clock = PlaybackHeadClock()
        clock.update(4800, 48_000)
        clock.update(9600, 48_000)
        clock.reset()
        assertEquals(100_000L, clock.update(4800, 48_000))
    }

    @Test
    fun `new stream discontinuity does not add half a counter`() {
        val clock = PlaybackHeadClock()
        clock.update(1000, 48_000)
        assertEquals(100_000L, clock.update(4800, 48_000))
    }
}
