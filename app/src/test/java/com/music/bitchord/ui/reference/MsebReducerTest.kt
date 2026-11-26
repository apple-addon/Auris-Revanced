package com.music.bitchord.ui.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MsebReducerTest {
    @Test
    fun allTenHibyAxesAndEndpointsExactlyMatchRunbook() {
        assertEquals(
            listOf(
                "Overall temperature", "Bass extension", "Bass texture", "Note thickness", "Voice",
                "Female overtones", "Sibilance LF", "Sibilance HF", "Impulse response", "Air",
            ),
            MsebControl.entries.map { it.label },
        )
        assertEquals(
            listOf(
                "Cool/Bright" to "Warm/Dark",
                "Light" to "Deep",
                "Fast" to "Thumpy",
                "Crisp" to "Thick",
                "Recessed/Crisp" to "Forward/Radio Edit",
                "Detoxed" to "Intoxicating",
                "Soft" to "Crisp",
                "Soft" to "Crisp",
                "Slow/Musical" to "Fast/Hard",
                "Soft" to "Crisp",
            ),
            MsebControl.entries.map { it.negativeEndpoint to it.positiveEndpoint },
        )
        assertEquals(10, MsebState().values.size)
    }

    @Test
    fun changingOneAxisPreservesPreampWireRangeAndEveryOtherAxis() {
        val original = MsebState(
            preampDb = -4.5f,
            range = 3,
            values = List(10) { it.toFloat() },
        )
        val changed = MsebReducer.setValue(original, 3, 500f)
        assertFloat(-4.5f, changed.preampDb)
        assertEquals(3, changed.range)
        assertFloat(100f, changed.values[3])
        assertFloat(2f, changed.values[2])
        assertFloat(4f, changed.values[4])
        assertFloat(3f, original.values[3])
    }

    @Test
    fun plusMinusNudgesStopAtTheOriginalRangeEdges() {
        val low = MsebState(values = List(10) { -100f })
        val high = MsebState(values = List(10) { 99.5f })
        assertFloat(-100f, MsebReducer.nudge(low, 0, -1f).values[0])
        assertFloat(100f, MsebReducer.nudge(high, 9, 1f).values[9])
        assertFloat(-100f, MsebReducer.MIN_VALUE)
        assertFloat(100f, MsebReducer.MAX_VALUE)
    }

    @Test
    fun preampUsesNativeMinusFortyFiveToPlusFortyFiveNotPeqRange() {
        val state = MsebState(range = 9)
        assertFloat(-45f, MsebReducer.setPreamp(state, -100f).preampDb)
        assertFloat(45f, MsebReducer.setPreamp(state, 100f).preampDb)
        assertFloat(30f, MsebReducer.setPreamp(state, 30f).preampDb)
        assertFloat(45f, MsebState(preampDb = 90f).normalized().preampDb)
        assertEquals(9, MsebReducer.setPreamp(state, 30f).range)
    }

    @Test
    fun resetZerosSoundWithoutTogglingProcessorOrChoosingAnotherPreset() {
        val reset = MsebReducer.reset(
            MsebState(
                enabled = false,
                presetName = "Mine",
                preampDb = 40f,
                range = 7,
                values = List(10) { 80f },
            ),
        )
        assertFalse(reset.enabled)
        assertEquals("Mine", reset.presetName)
        assertEquals(7, reset.range)
        assertFloat(0f, reset.preampDb)
        assertTrue(reset.values.all { it == 0f })
    }

    @Test
    fun normalizationDoesNotNarrowAxisRange() {
        val state = MsebState(values = listOf(-1000f, 1000f)).normalized()
        assertEquals(10, state.values.size)
        assertFloat(-100f, state.values[0])
        assertFloat(100f, state.values[1])
    }

    private fun assertFloat(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0001f)
}
