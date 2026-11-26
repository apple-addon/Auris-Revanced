package com.music.bitchord

import com.music.bitchord.data.settings.QualityAudioState
import com.music.bitchord.data.settings.QualityOptionsPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityOptionsPolicyTest {
    @Test
    fun `native DSP coexists with quality options without confirmation`() {
        val state = QualityAudioState(
            qualityOptions = true,
            configuredQualityDsp = true,
            nativeBitPerfect = false,
        )
        // Native EQ/JamesDSP state is intentionally absent from the conflict
        // policy: non-conflicting native stages remain enabled.
        assertTrue(QualityOptionsPolicy.needsBitPerfectConfirmation(state))
        assertFalse(QualityOptionsPolicy.needsQualityConfirmation(state))
    }

    @Test
    fun `inactive quality stages do not block native bit perfect`() {
        val state = QualityAudioState(
            qualityOptions = false,
            configuredQualityDsp = true,
            nativeBitPerfect = false,
        )
        assertFalse(QualityOptionsPolicy.needsBitPerfectConfirmation(state))
    }

    @Test
    fun `saved active DSP blocks quality enable while native bit perfect is on`() {
        val state = QualityAudioState(
            qualityOptions = false,
            configuredQualityDsp = true,
            nativeBitPerfect = true,
        )
        assertTrue(QualityOptionsPolicy.needsQualityConfirmation(state))
    }

    @Test
    fun `neutral quality configuration coexists with either native state`() {
        for (bitPerfect in listOf(false, true)) {
            val state = QualityAudioState(
                qualityOptions = bitPerfect.not(),
                configuredQualityDsp = false,
                nativeBitPerfect = bitPerfect,
            )
            assertFalse(QualityOptionsPolicy.needsBitPerfectConfirmation(state))
            assertFalse(QualityOptionsPolicy.needsQualityConfirmation(state))
        }
    }
}
