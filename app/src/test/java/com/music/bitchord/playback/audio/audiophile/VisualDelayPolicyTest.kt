package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.ui.screens.audiophile.AudioOutputRoute
import com.music.bitchord.ui.screens.audiophile.AudioOutputTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class VisualDelayPolicyTest {
    @Test
    fun positiveDelayHoldsVisualsBackAndClampsAtZeroAndDuration() {
        val target = AudioOutputTarget.route(AudioOutputRoute.WIRED)
        val telemetry = VisualDelayTelemetry(
            target = target,
            configuredDelayMs = 120,
            bufferSizeFrames = 4800,
            outputSampleRateHz = 48_000,
        )
        assertEquals(100.0, telemetry.bufferDelayMs!!, 0.0001)
        assertEquals(880L, telemetry.visualPositionMs(1000L))
        assertEquals(0L, telemetry.visualPositionMs(50L))
        assertEquals(10_000L, telemetry.visualPositionMs(20_000L, 10_000L))
    }

    @Test
    fun negativeDelayAdvancesVisualsWithoutTouchingAudioSemantics() {
        assertEquals(1_120L, VisualDelayPolicy.visualPositionMs(1_000L, configuredDelayMs = -120))
        assertEquals(0L, VisualDelayPolicy.visualPositionMs(-1L, configuredDelayMs = 0))
    }
}
