package com.music.bitchord.ui.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PeqReducerTest {
    @Test
    fun logarithmicAxisUsesFrequencyRatiosNotLinearPixels() {
        assertFloat(0f, peqFrequencyToFraction(20f))
        assertFloat(1f, peqFrequencyToFraction(20_000f))
        assertFloat(0.5f, peqFrequencyToFraction(sqrt(20f * 20_000f)))
        assertFloat(20f, peqFractionToFrequency(0f))
        assertFloat(20_000f, peqFractionToFrequency(1f))
        assertEquals(sqrt(20f * 20_000f), peqFractionToFrequency(0.5f), 0.01f)
        assertTrue(peqFractionToFrequency(0.5f) < 1_000f)
    }

    @Test
    fun gainAxisIsExactlyMinusTwelveToPlusTwelve() {
        assertFloat(0f, peqGainToFraction(12f))
        assertFloat(0.5f, peqGainToFraction(0f))
        assertFloat(1f, peqGainToFraction(-12f))
        assertFloat(12f, peqFractionToGain(0f))
        assertFloat(-12f, peqFractionToGain(1f))
    }

    @Test
    fun qAndTypeChoicesExactlyMatchRunbook() {
        assertFloat(0.2f, PeqDefaults.MIN_Q)
        assertFloat(0.2f, PeqBand(1_000f).q)
        assertFloat(0.2f, PeqBand(1_000f, q = 0f).normalized().q)
        assertFloat(0.2f, PeqBand(1_000f, q = Float.NaN).normalized().q)
        assertEquals(
            listOf("Low Shelf", "Peaking", "High Shelf", "Low Pass", "High Pass", "Notch", "Band Pass"),
            PeqFilterType.entries.map { it.label },
        )
        assertEquals(7, PeqFilterType.entries.size)
    }

    @Test
    fun graphDragChangesOnlyChosenBandAndClampsToGraph() {
        val original = PeqState(preampDb = -2f)
        val changed = PeqReducer.graphDrag(original, 2, 2f, -1f)
        assertFloat(20_000f, changed.bands[2].frequencyHz)
        assertFloat(12f, changed.bands[2].gainDb)
        assertEquals(original.bands[1], changed.bands[1])
        assertFloat(0f, original.bands[2].gainDb)
        assertFloat(-2f, changed.preampDb)
    }

    @Test
    fun completeBandEditorRetainsTypeAndEnableState() {
        val changed = PeqReducer.updateBand(PeqState(), 0) {
            it.copy(type = PeqFilterType.LOW_SHELF, enabled = false, q = 50f)
        }
        assertEquals(PeqFilterType.LOW_SHELF, changed.bands[0].type)
        assertFalse(changed.bands[0].enabled)
        assertFloat(30f, changed.bands[0].q)
    }

    @Test
    fun peqResetRestoresTenBandsAndKeepsEnableAndPresetMetadata() {
        val reset = PeqReducer.reset(
            PeqState(
                enabled = false,
                presetName = "Imported",
                preampDb = 10f,
                bands = List(10) { PeqBand(100f + it, gainDb = 5f) },
            ),
        )
        assertFalse(reset.enabled)
        assertEquals("Imported", reset.presetName)
        assertFloat(0f, reset.preampDb)
        assertEquals(PeqDefaults.bands, reset.bands)
        assertTrue(reset.bands.all { it.q == 0.2f && it.type == PeqFilterType.PEAK })
    }

    private fun assertFloat(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0001f)
}
