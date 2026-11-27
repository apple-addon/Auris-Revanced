package com.music.bitchord.playback.reference.paws

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PawsControlStateTest {
    @Test
    fun `reverb ids match original XML parameters rather than visual order`() {
        assertArrayEquals(intArrayOf(7, 2, 3, 4, 6, 1, 5), PAWS_REVERB_PARAMETER_IDS)
    }

    @Test
    fun `exact native control ranges are accepted`() {
        val controls = PawsControlState(
            bands = listOf(PawsEqBand(31, 2f, 0.7f)),
            preampLinear = 2f,
            bassGain = 1f,
            trebleGain = 1f,
            stereoWide = 1f,
            balance = -1f,
            tempo = 3f,
            reverbEnabled = true,
            reverbValues = FloatArray(7) { it / 7f },
        )
        assertEquals(2f, controls.preampLinear)
        assertEquals(3f, controls.tempo)
        assertEquals(1f, controls.stereoWide)
    }

    @Test
    fun `invalid native values fail before reflection`() {
        expectFailure("tempo") {
            PawsControlState(bands = listOf(PawsEqBand(31, 0f)), tempo = 4f)
        }
        expectFailure("tone") {
            PawsControlState(bands = listOf(PawsEqBand(31, 0f)), bassGain = -0.1f)
        }
        expectFailure("reverb") {
            PawsControlState(bands = listOf(PawsEqBand(31, 0f)), reverbValues = FloatArray(6))
        }
    }

    private fun expectFailure(label: String, body: () -> Unit) {
        try {
            body()
            fail("Expected $label validation failure")
        } catch (_: IllegalArgumentException) {
        }
    }
}
