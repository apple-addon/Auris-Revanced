package com.music.bitchord.ui.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerampEqReducerTest {
    @Test
    fun defaultsHaveTenIndependentBands() {
        val state = PowerampEqState()
        assertEquals(10, state.bands.size)
        assertFloat(31f, state.bands.first().frequencyHz)
        assertFloat(16_000f, state.bands.last().frequencyHz)
    }

    @Test
    fun bandUpdateIsAtomicAndDoesNotMutateOldSnapshot() {
        val original = PowerampEqState(preampDb = -3f, tempo = 1.25f)
        val changed = PowerampEqReducer.updateBand(original, 4) {
            it.copy(gainDb = 99f, frequencyHz = 1f, q = Float.NaN)
        }

        assertNotSame(original, changed)
        assertFloat(0f, original.bands[4].gainDb)
        assertFloat(12f, changed.bands[4].gainDb)
        assertFloat(20f, changed.bands[4].frequencyHz)
        assertFloat(1f, changed.bands[4].q)
        assertFloat(-3f, changed.preampDb)
        assertFloat(1.25f, changed.tempo)
        assertEquals(original.bands[3], changed.bands[3])
    }

    @Test
    fun playbackUpdatesRetainTheirPublishedRanges() {
        val state = PowerampEqState()
        assertFloat(-1f, PowerampEqReducer.setBalance(state, -5f).balance)
        assertFloat(1f, PowerampEqReducer.setStereoWidth(state, 5f).stereoWidth)
        assertFloat(0.5f, PowerampEqReducer.setTempo(state, 0.1f).tempo)
        assertFloat(2f, PowerampEqReducer.setTempo(state, 8f).tempo)
        assertFloat(1f, PowerampEqReducer.setVolume(state, Float.POSITIVE_INFINITY).volume)
    }

    @Test
    fun reverbDefaultsAreExactlySixZeroKnobsAndZeroMix() {
        val reverb = PowerampReverbState()
        assertFalse(reverb.enabled)
        assertTrue(reverbValues(reverb).all { it == 0f })
        assertEquals(7, reverbValues(reverb).size)
    }

    @Test
    fun everyReverbControlUsesAnImmutableAtomicSnapshot() {
        val original = PowerampEqState()
        val changed = PowerampEqReducer.updateReverb(original) {
            it.copy(
                enabled = true,
                damp = 0.1f,
                filter = 0.2f,
                fade = 0.3f,
                preDelay = 0.4f,
                preDelayMix = 0.5f,
                size = 0.6f,
                mix = 0.7f,
            )
        }
        assertNotSame(original, changed)
        assertEquals(PowerampReverbState(), original.reverb)
        assertEquals(
            listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f),
            reverbValues(changed.reverb),
        )
    }

    @Test
    fun reverbResetKeepsEnableChoiceAndZerosAllSixControlsAndMix() {
        val changed = PowerampEqState(
            reverb = PowerampReverbState(
                enabled = true,
                damp = 1f,
                filter = 1f,
                fade = 1f,
                preDelay = 1f,
                preDelayMix = 1f,
                size = 1f,
                mix = 1f,
            ),
        )
        val reset = PowerampEqReducer.resetReverb(changed)
        assertTrue(reset.reverb.enabled)
        assertEquals(PowerampReverbState(enabled = true), reset.reverb)
        assertTrue(reverbValues(reset.reverb).all { it == 0f })
        assertFalse(PowerampEqReducer.resetReverb(PowerampEqState()).reverb.enabled)
    }

    @Test
    fun malformedBandListIsFilledWithoutDroppingTheUiContract() {
        val state = PowerampEqState(bands = listOf(PowerampEqBand(100f, gainDb = 2f)))
        val normalized = state.normalized()
        assertEquals(10, normalized.bands.size)
        assertFloat(2f, normalized.bands[0].gainDb)
        assertFloat(62f, normalized.bands[1].frequencyHz)
    }

    private fun reverbValues(reverb: PowerampReverbState): List<Float> = listOf(
        reverb.damp,
        reverb.filter,
        reverb.fade,
        reverb.preDelay,
        reverb.preDelayMix,
        reverb.size,
        reverb.mix,
    )

    private fun assertFloat(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0001f)
}
