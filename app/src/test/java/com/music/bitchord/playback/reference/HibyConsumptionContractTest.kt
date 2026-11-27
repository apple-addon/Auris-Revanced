package com.music.bitchord.playback.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HibyConsumptionContractTest {
    @Test
    fun `zero and errors never substitute written frames`() {
        assertEquals(0L, HibyConsumptionContract.monotonicFrames(0, 0, 48_000))
        assertNull(HibyConsumptionContract.monotonicFrames(-1008, 0, 48_000))
    }

    @Test
    fun `consumption is monotonic and bounded by complete writes`() {
        assertEquals(
            120L,
            HibyConsumptionContract.monotonicFrames(
                nativeResult = 120,
                previousConsumed = 100,
                framesWritten = 200,
            ),
        )
        assertNull(
            HibyConsumptionContract.monotonicFrames(
                nativeResult = 80,
                previousConsumed = 100,
                framesWritten = 200,
            ),
        )
        assertEquals(
            100L,
            HibyConsumptionContract.monotonicFrames(
                nativeResult = 100,
                previousConsumed = 100,
                framesWritten = 200,
            ),
        )
        assertNull(
            HibyConsumptionContract.monotonicFrames(
                nativeResult = 240,
                previousConsumed = 100,
                framesWritten = 200,
            ),
        )
    }

    @Test
    fun `unknown consumption cannot block end of stream`() {
        assertFalse(HibyConsumptionContract.hasPendingData(null, 48_000))
        assertTrue(HibyConsumptionContract.hasPendingData(100, 200))
        assertFalse(HibyConsumptionContract.hasPendingData(200, 200))
    }
}
