package com.music.bitchord.playback.fades

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportFaderTest {
    @Test
    fun playUsesFourHundredMillisecondRampAndStartsOnlyOnce() {
        val fader = TransportFader()

        assertEquals(FadeAction.START_PLAYBACK, fader.play(0L).action)
        assertEquals(FadeAction.NONE, fader.play(0L).action)
        assertGain(0f, fader.advanceTo(0L).gain)
        assertGain(0.5f, fader.advanceTo(200L).gain)
        val done = fader.advanceTo(400L)
        assertEquals(TransportFadeState.PLAYING, done.state)
        assertEquals(FadeAction.NONE, done.action)
        assertGain(1f, done.gain)
    }

    @Test
    fun pauseAndStopFadeBeforeTheirOneShotCommands() {
        val paused = playingFader()
        paused.pause(1_000L)
        assertGain(0.5f, paused.advanceTo(1_200L).gain)
        assertEquals(FadeAction.NONE, paused.advanceTo(1_300L).action)
        val pauseDone = paused.advanceTo(1_400L)
        assertEquals(TransportFadeState.PAUSED, pauseDone.state)
        assertEquals(FadeAction.PAUSE_PLAYBACK, pauseDone.action)
        assertEquals(FadeAction.NONE, paused.advanceTo(1_500L).action)

        val stopped = playingFader()
        stopped.stop(2_000L)
        assertEquals(FadeAction.NONE, stopped.stop(2_100L).action)
        val stopDone = stopped.advanceTo(2_400L)
        assertEquals(TransportFadeState.STOPPED, stopDone.state)
        assertEquals(FadeAction.STOP_PLAYBACK, stopDone.action)
        assertGain(0f, stopDone.gain)
    }

    @Test
    fun seekFadesOutEmitsSeekThenFadesBackIn() {
        val fader = playingFader()
        fader.seek(1_000L)
        assertGain(0.5f, fader.advanceTo(1_050L).gain)

        val seekPoint = fader.advanceTo(1_100L)
        assertEquals(FadeAction.SEEK, seekPoint.action)
        assertEquals(TransportFadeState.SEEK_FADE_IN, seekPoint.state)
        assertGain(0f, seekPoint.gain)
        assertEquals(FadeAction.NONE, fader.advanceTo(1_100L).action)
        assertGain(0.5f, fader.advanceTo(1_150L).gain)
        assertEquals(TransportFadeState.PLAYING, fader.advanceTo(1_200L).state)
        assertGain(1f, fader.gain)
    }

    @Test
    fun reversingARampPreservesItsCurrentGain() {
        val fader = TransportFader()
        fader.play(0L)
        assertGain(0.5f, fader.advanceTo(200L).gain)

        val pauseStarted = fader.pause(200L)
        assertGain(0.5f, pauseStarted.gain)
        assertGain(0.25f, fader.advanceTo(300L).gain)

        val resumed = fader.play(300L)
        assertEquals(FadeAction.START_PLAYBACK, resumed.action)
        assertGain(0.25f, resumed.gain)
        assertGain(0.625f, fader.advanceTo(450L).gain)
        assertEquals(TransportFadeState.PLAYING, fader.advanceTo(600L).state)
        assertGain(1f, fader.gain)
    }

    @Test
    fun pausedSeekIsImmediateAndDoesNotStartPlayback() {
        val fader = TransportFader()
        val update = fader.seek(10L)
        assertEquals(FadeAction.SEEK, update.action)
        assertEquals(TransportFadeState.STOPPED, update.state)
        assertGain(0f, update.gain)
    }

    @Test
    fun zeroDurationsCompleteImmediately() {
        val fader = TransportFader(
            FadeSettings(playPauseStopFadeMs = 0L, seekFadeMs = 0L),
        )
        assertEquals(TransportFadeState.PLAYING, fader.play(0L).state)
        assertGain(1f, fader.gain)
        val seek = fader.seek(1L)
        assertEquals(FadeAction.SEEK, seek.action)
        assertEquals(TransportFadeState.PLAYING, seek.state)
        assertEquals(FadeAction.PAUSE_PLAYBACK, fader.pause(2L).action)
    }

    @Test
    fun fadePlayPauseSwitchOffMakesTransportImmediateButKeepsCommands() {
        val fader = TransportFader(FadeSettings(fadePlayPause = false))
        val play = fader.play(0L)
        assertEquals(FadeAction.START_PLAYBACK, play.action)
        assertEquals(TransportFadeState.PLAYING, play.state)
        assertGain(1f, play.gain)

        val pause = fader.pause(100L)
        assertEquals(FadeAction.PAUSE_PLAYBACK, pause.action)
        assertEquals(TransportFadeState.PAUSED, pause.state)
        assertGain(0f, pause.gain)

        val resume = fader.play(200L)
        assertEquals(FadeAction.START_PLAYBACK, resume.action)
        assertGain(1f, resume.gain)

        val stop = fader.stop(300L)
        assertEquals(FadeAction.STOP_PLAYBACK, stop.action)
        assertEquals(TransportFadeState.STOPPED, stop.state)
        assertGain(0f, stop.gain)
    }

    @Test
    fun fadeSeekSwitchOffLandsTheSeekAtOnce() {
        val fader = TransportFader(FadeSettings(fadeSeek = false))
        fader.play(0L)
        fader.advanceTo(400L)
        val seek = fader.seek(1_000L)
        assertEquals(FadeAction.SEEK, seek.action)
        assertEquals(TransportFadeState.PLAYING, seek.state)
        assertGain(1f, seek.gain)
        // No fade-out/in afterwards.
        assertEquals(FadeAction.NONE, fader.advanceTo(1_100L).action)
        assertGain(1f, fader.gain)
    }

    @Test
    fun updateSettingsAppliesSwitchesWithoutTouchingLengths() {
        val fader = playingFader()
        fader.updateSettings(FadeSettings(playPauseStopFadeMs = 400L, fadePlayPause = false))
        val pause = fader.pause(1_000L)
        assertEquals(FadeAction.PAUSE_PLAYBACK, pause.action)
        assertGain(0f, pause.gain)
        // Switch back on: the stored length ramps again.
        fader.updateSettings(FadeSettings(playPauseStopFadeMs = 400L, fadePlayPause = true))
        fader.play(2_000L)
        assertGain(0.5f, fader.advanceTo(2_200L).gain)
    }

    private fun playingFader(): TransportFader = TransportFader().apply {
        play(0L)
        advanceTo(400L)
    }

    private fun assertGain(expected: Float, actual: Float) =
        assertEquals(expected, actual, 0.0001f)
}
