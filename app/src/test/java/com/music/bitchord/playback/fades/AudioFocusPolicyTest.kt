package com.music.bitchord.playback.fades

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    @Test
    fun callPausesAndResumesOnlyPlaybackItInterrupted() {
        var state = reduce(AudioFocusState(), AudioFocusEvent.UserPlay).state
        val call = reduce(state, AudioFocusEvent.CallChanged(active = true))
        assertEquals(AudioFocusCommand.PAUSE, call.command)
        assertFalse(call.state.isPlaying)

        state = reduce(call.state, AudioFocusEvent.CallChanged(active = false)).state
        assertTrue(state.isPlaying)

        val secondCall = reduce(state, AudioFocusEvent.CallChanged(active = true)).state
        val userPause = reduce(secondCall, AudioFocusEvent.UserPause)
        assertFalse(userPause.state.playbackRequested)
        val callEnd = reduce(userPause.state, AudioFocusEvent.CallChanged(active = false))
        assertEquals(AudioFocusCommand.NONE, callEnd.command)
        assertFalse(callEnd.state.isPlaying)
    }

    @Test
    fun pauseInCallCanBeDisabled() {
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay).state
        val result = reduce(
            playing,
            AudioFocusEvent.CallChanged(active = true),
            AudioFocusSettings(pauseInCall = false),
        )
        assertEquals(AudioFocusCommand.NONE, result.command)
        assertTrue(result.state.isPlaying)
    }

    @Test
    fun notificationDucksAndRestoresGainWithoutPausing() {
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay).state
        val duck = reduce(playing, AudioFocusEvent.NotificationChanged(active = true))
        assertEquals(AudioFocusCommand.NONE, duck.command)
        assertTrue(duck.gainChanged)
        assertTrue(duck.state.isDucked)
        assertEquals(AudioFocusPolicy.NOTIFICATION_DUCK_GAIN, duck.state.outputGain, 0f)

        val restore = reduce(duck.state, AudioFocusEvent.NotificationChanged(active = false))
        assertEquals(AudioFocusCommand.NONE, restore.command)
        assertTrue(restore.gainChanged)
        assertEquals(1f, restore.state.outputGain, 0f)
    }

    @Test
    fun noDuckOverrideKeepsFullGainButCannotIgnoreNonDuckableLoss() {
        val settings = AudioFocusSettings(noDuckOverride = true)
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay, settings).state
        val full = reduce(
            playing,
            AudioFocusEvent.NotificationChanged(active = true, canDuck = true),
            settings,
        )
        assertTrue(full.state.isPlaying)
        assertEquals(1f, full.state.outputGain, 0f)

        val paused = reduce(
            full.state,
            AudioFocusEvent.NotificationChanged(active = true, canDuck = false),
            settings,
        )
        assertEquals(AudioFocusCommand.PAUSE, paused.command)
        assertFalse(paused.state.isPlaying)
    }

    @Test
    fun disablingDuckPausesForDuckableNotificationAndResumesAfterward() {
        val settings = AudioFocusSettings(notificationDuck = false)
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay, settings).state
        val paused = reduce(
            playing,
            AudioFocusEvent.NotificationChanged(active = true),
            settings,
        )
        assertEquals(AudioFocusCommand.PAUSE, paused.command)
        val resumed = reduce(
            paused.state,
            AudioFocusEvent.NotificationChanged(active = false),
            settings,
        )
        assertEquals(AudioFocusCommand.PLAY, resumed.command)
    }

    @Test
    fun permanentLossDoesNotResumeFromLaterTransientEvents() {
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay).state
        val lost = reduce(playing, AudioFocusEvent.PermanentFocusLost)
        assertEquals(AudioFocusCommand.PAUSE, lost.command)
        assertFalse(lost.state.playbackRequested)

        val notification = reduce(
            lost.state,
            AudioFocusEvent.NotificationChanged(active = true),
        )
        val ended = reduce(
            notification.state,
            AudioFocusEvent.NotificationChanged(active = false),
        )
        assertEquals(AudioFocusCommand.NONE, ended.command)
        assertFalse(ended.state.isPlaying)
    }

    @Test
    fun lifecycleResumeIsOptInAndStartAndReopenAreIndependent() {
        assertEquals(
            AudioFocusCommand.NONE,
            reduce(AudioFocusState(), AudioFocusEvent.ServiceStarted).command,
        )
        assertEquals(
            AudioFocusCommand.NONE,
            reduce(AudioFocusState(), AudioFocusEvent.AppReopened).command,
        )
        assertEquals(
            AudioFocusCommand.PLAY,
            reduce(
                AudioFocusState(),
                AudioFocusEvent.ServiceStarted,
                AudioFocusSettings(resumeOnStart = true),
            ).command,
        )
        assertEquals(
            AudioFocusCommand.PLAY,
            reduce(
                AudioFocusState(),
                AudioFocusEvent.AppReopened,
                AudioFocusSettings(resumeOnReopen = true),
            ).command,
        )
    }

    @Test
    fun waitForStorageDefersStartupResumeUntilMounted() {
        val settings = AudioFocusSettings(resumeOnStart = true, waitForStorage = true)
        val waiting = reduce(
            AudioFocusState(storageReady = false),
            AudioFocusEvent.ServiceStarted,
            settings,
        )
        assertEquals(AudioFocusCommand.NONE, waiting.command)
        assertTrue(waiting.state.waitingForStorage)
        assertFalse(waiting.state.isPlaying)

        val mounted = reduce(waiting.state, AudioFocusEvent.StorageChanged(ready = true), settings)
        assertEquals(AudioFocusCommand.PLAY, mounted.command)
        assertFalse(mounted.state.waitingForStorage)
    }

    @Test
    fun disabledStorageGateStartsImmediatelyAndUserPauseCatchesDeferredResume() {
        val noGate = AudioFocusSettings(resumeOnStart = true, waitForStorage = false)
        assertEquals(
            AudioFocusCommand.PLAY,
            reduce(
                AudioFocusState(storageReady = false),
                AudioFocusEvent.ServiceStarted,
                noGate,
            ).command,
        )

        val settings = AudioFocusSettings(resumeOnStart = true)
        val waiting = reduce(
            AudioFocusState(storageReady = false),
            AudioFocusEvent.ServiceStarted,
            settings,
        ).state
        val cancelled = reduce(waiting, AudioFocusEvent.UserPause, settings).state
        val mounted = reduce(cancelled, AudioFocusEvent.StorageChanged(ready = true), settings)
        assertEquals(AudioFocusCommand.NONE, mounted.command)
        assertFalse(mounted.state.isPlaying)
    }

    @Test
    fun callTakesPriorityOverDuckAndReturnsToDuckedPlayback() {
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay).state
        val ducked = reduce(playing, AudioFocusEvent.NotificationChanged(active = true)).state
        val call = reduce(ducked, AudioFocusEvent.CallChanged(active = true))
        assertEquals(AudioFocusCommand.PAUSE, call.command)
        assertEquals(1f, call.state.outputGain, 0f)

        val callEnd = reduce(call.state, AudioFocusEvent.CallChanged(active = false))
        assertEquals(AudioFocusCommand.PLAY, callEnd.command)
        assertTrue(callEnd.state.isDucked)
        assertEquals(AudioFocusPolicy.NOTIFICATION_DUCK_GAIN, callEnd.state.outputGain, 0f)
    }

    @Test
    fun ignoreAudioFocusSkipsDuckCallAndPermanentLossButNotStorage() {
        val settings = AudioFocusSettings(ignoreAudioFocus = true)
        val playing = reduce(AudioFocusState(), AudioFocusEvent.UserPlay, settings).state

        // Duckable notification: no duck, no pause.
        val duck = reduce(playing, AudioFocusEvent.NotificationChanged(active = true), settings)
        assertEquals(AudioFocusCommand.NONE, duck.command)
        assertEquals(1f, duck.state.outputGain, 0f)
        assertTrue(duck.state.isPlaying)

        // Non-duckable notification and calls: still ignored.
        val hard = reduce(duck.state, AudioFocusEvent.NotificationChanged(active = true, canDuck = false), settings)
        assertEquals(AudioFocusCommand.NONE, hard.command)
        val call = reduce(hard.state, AudioFocusEvent.CallChanged(active = true), settings)
        assertEquals(AudioFocusCommand.NONE, call.command)
        assertTrue(call.state.isPlaying)

        // Permanent loss: ignored too.
        val lost = reduce(call.state, AudioFocusEvent.PermanentFocusLost, settings)
        assertEquals(AudioFocusCommand.NONE, lost.command)
        assertTrue(lost.state.isPlaying)

        // Storage gating is not audio focus and still blocks.
        val blocked = reduce(
            lost.state,
            AudioFocusEvent.StorageChanged(ready = false),
            AudioFocusSettings(ignoreAudioFocus = true, waitForStorage = true),
        )
        assertEquals(AudioFocusCommand.PAUSE, blocked.command)
        assertFalse(blocked.state.isPlaying)
    }

    private fun reduce(
        state: AudioFocusState,
        event: AudioFocusEvent,
        settings: AudioFocusSettings = AudioFocusSettings(),
    ): AudioFocusTransition = AudioFocusPolicy.reduce(state, event, settings)
}
