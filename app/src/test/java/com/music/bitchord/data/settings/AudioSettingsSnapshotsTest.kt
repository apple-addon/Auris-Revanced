package com.music.bitchord.data.settings

import com.music.bitchord.ui.audiophile.AudioOutputRouteId
import com.music.bitchord.ui.audiophile.AudioOutputRouteState
import com.music.bitchord.ui.audiophile.AudiophileAudioState
import com.music.bitchord.ui.audiophile.AutoAdvanceFading
import com.music.bitchord.ui.audiophile.ReplayGainSource
import com.music.bitchord.ui.reference.MsebState
import com.music.bitchord.ui.reference.PeqState
import com.music.bitchord.ui.reference.PowerampEqState
import com.music.bitchord.ui.reference.normalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsSnapshotsTest {
    @Test
    fun `reference editor snapshots round trip without aliasing bands`() {
        val poweramp = PowerampEqState(
            parametricMode = true,
            preampDb = 2.5f,
            bands = PowerampEqState().bands.mapIndexed { index, band ->
                band.copy(gainDb = index - 4f)
            },
        )
        val decodedPoweramp = decodePowerampEqSnapshot(encodePowerampEqSnapshot(poweramp))
        assertEquals(poweramp.normalized(), decodedPoweramp)

        val mseb = MsebState(enabled = false, presetName = "Night", range = 3, values = List(10) { it - 5f })
        assertEquals(mseb.normalized(), decodeMsebSnapshot(encodeMsebSnapshot(mseb)))

        val peq = PeqState(enabled = false, presetName = "Desk", preampDb = -1f)
        assertEquals(peq.normalized(), decodePeqSnapshot(encodePeqSnapshot(peq)))
    }

    @Test
    fun `v1 tone decibels migrate to native gains`() {
        val decoded = decodePowerampEqSnapshot(
            "{\"v\":1,\"bass\":6.0,\"treble\":-3.0,\"width\":-1.0,\"tempo\":2.5}",
        )
        assertEquals(0.5f, decoded.bassGain, 0.0001f)
        assertEquals(0f, decoded.trebleGain, 0.0001f)
        assertEquals(0f, decoded.stereoWidth, 0.0001f)
        assertEquals(2.5f, decoded.tempo, 0.0001f)
    }

    @Test
    fun `audiophile snapshot retains policies and route configuration but not runtime status`() {
        val state = AudiophileAudioState(
            qualityOptionsEnabled = true,
            crossfadeFadeGapless = AudiophileAudioState().crossfadeFadeGapless.copy(
                autoAdvanceFading = AutoAdvanceFading.CROSSFADE,
                crossfadeLengthMs = 7_500,
            ),
            replayGain = AudiophileAudioState().replayGain.copy(source = ReplayGainSource.SMART),
            outputs = AudiophileAudioState().outputs.map { route ->
                if (route.id == AudioOutputRouteId.AAUDIO) {
                    route.copy(enabled = true)
                } else {
                    route
                }
            },
        )
        val decoded = decodeAudiophileSnapshot(encodeAudiophileSnapshot(state))
        assertTrue(decoded.qualityOptionsEnabled)
        assertEquals(7_500, decoded.crossfadeFadeGapless.crossfadeLengthMs)
        assertEquals(ReplayGainSource.SMART, decoded.replayGain.source)
        assertTrue(decoded.outputs.first { it.id == AudioOutputRouteId.AAUDIO }.enabled)
        assertTrue(decoded.outputs.all { it.actualIsRuntimeOnly })
    }

    @Test
    fun ` malformed snapshots fail to neutral defaults`() {
        assertEquals(PowerampEqState(), decodePowerampEqSnapshot("not-json"))
        assertEquals(MsebState(), decodeMsebSnapshot("{\"v\":99}"))
        assertEquals(PeqState(), decodePeqSnapshot("[]"))
        assertFalse(decodeAudiophileSnapshot(null).qualityOptionsEnabled)
    }

    private val AudioOutputRouteState.actualIsRuntimeOnly: Boolean
        get() = status.name == "UNKNOWN" || status.name == "AVAILABLE"
}
