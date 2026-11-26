package com.music.bitchord.playback.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    private fun playing() = AudioFocusState(playWhenReady = true, focus = FocusOwnership.GRANTED)

    private fun focus(change: AudioFocusChange) = AudioFocusEvent.FocusChanged(change)

    @Test
    fun userPlayRequestsFocusBeforeStarting() {
        val request = AudioFocusPolicy.reduce(AudioFocusState(), AudioFocusEvent.UserPlay)
        val grant = AudioFocusPolicy.reduce(request.state, focus(AudioFocusChange.GAIN))

        assertEquals(listOf(AudioFocusCommand.REQUEST_FOCUS), request.commands)
        assertFalse(request.state.shouldPlay)
        assertEquals(FocusOwnership.NONE, request.state.focus)
        assertEquals(listOf(AudioFocusCommand.PLAY), grant.commands)
        assertTrue(grant.state.shouldPlay)
    }

    @Test
    fun delayedFocusWaitsWithoutPlayingOrRequestingAgain() {
        val requested = AudioFocusPolicy.reduce(AudioFocusState(), AudioFocusEvent.UserPlay).state
        val delayed = AudioFocusPolicy.reduce(requested, focus(AudioFocusChange.REQUEST_DELAYED))
        val repeatedPlay = AudioFocusPolicy.reduce(delayed.state, AudioFocusEvent.UserPlay)
        val grant = AudioFocusPolicy.reduce(delayed.state, focus(AudioFocusChange.GAIN))

        assertTrue(delayed.commands.isEmpty())
        assertEquals(FocusOwnership.WAITING, delayed.state.focus)
        assertFalse(delayed.state.shouldPlay)
        assertTrue(repeatedPlay.commands.isEmpty())
        assertEquals(listOf(AudioFocusCommand.PLAY), grant.commands)
    }

    @Test
    fun failedFocusDoesNotStartPlayback() {
        val state = AudioFocusState(playWhenReady = true, focus = FocusOwnership.WAITING)
        val failed = AudioFocusPolicy.reduce(state, focus(AudioFocusChange.REQUEST_FAILED))

        assertTrue(failed.commands.isEmpty())
        assertFalse(failed.state.shouldPlay)
        assertEquals(FocusOwnership.NONE, failed.state.focus)
        assertTrue(failed.state.playWhenReady)
    }

    @Test
    fun userPauseIsStickyAcrossLaterFocusGain() {
        val paused = AudioFocusPolicy.reduce(playing(), AudioFocusEvent.UserPause)
        val laterGain = AudioFocusPolicy.reduce(paused.state, focus(AudioFocusChange.GAIN))

        assertEquals(listOf(AudioFocusCommand.PAUSE, AudioFocusCommand.ABANDON_FOCUS), paused.commands)
        assertFalse(paused.state.playWhenReady)
        assertTrue(laterGain.commands.isEmpty())
        assertFalse(laterGain.state.shouldPlay)
    }

    @Test
    fun transientLossPausesAndGainResumesOriginalIntent() {
        val lost = AudioFocusPolicy.reduce(playing(), focus(AudioFocusChange.LOSS_TRANSIENT))
        val gained = AudioFocusPolicy.reduce(lost.state, focus(AudioFocusChange.GAIN))

        assertEquals(listOf(AudioFocusCommand.PAUSE), lost.commands)
        assertTrue(lost.state.playWhenReady)
        assertTrue(lost.state.transientLoss)
        assertFalse(lost.state.shouldPlay)
        assertEquals(listOf(AudioFocusCommand.PLAY), gained.commands)
        assertFalse(gained.state.transientLoss)
    }

    @Test
    fun userPauseDuringTransientLossCancelsAutomaticResume() {
        val lost = AudioFocusPolicy.reduce(playing(), focus(AudioFocusChange.LOSS_TRANSIENT)).state
        val paused = AudioFocusPolicy.reduce(lost, AudioFocusEvent.UserPause)
        val gained = AudioFocusPolicy.reduce(paused.state, focus(AudioFocusChange.GAIN))

        assertFalse(paused.state.playWhenReady)
        assertTrue(gained.commands.isEmpty())
        assertFalse(gained.state.shouldPlay)
    }

    @Test
    fun permanentLossRequiresNewUserPlay() {
        val lost = AudioFocusPolicy.reduce(playing(), focus(AudioFocusChange.LOSS))
        val staleGain = AudioFocusPolicy.reduce(lost.state, focus(AudioFocusChange.GAIN))
        val userPlay = AudioFocusPolicy.reduce(staleGain.state, AudioFocusEvent.UserPlay)

        assertEquals(listOf(AudioFocusCommand.PAUSE), lost.commands)
        assertFalse(lost.state.playWhenReady)
        assertTrue(staleGain.commands.isEmpty())
        assertEquals(listOf(AudioFocusCommand.PLAY), userPlay.commands)
    }

    @Test
    fun callPausesReleasesFocusThenRequestsBeforeResume() {
        val call = AudioFocusPolicy.reduce(playing(), AudioFocusEvent.CallStarted)
        val ended = AudioFocusPolicy.reduce(call.state, AudioFocusEvent.CallEnded)
        val gained = AudioFocusPolicy.reduce(ended.state, focus(AudioFocusChange.GAIN))

        assertEquals(listOf(AudioFocusCommand.PAUSE, AudioFocusCommand.ABANDON_FOCUS), call.commands)
        assertTrue(call.state.callActive)
        assertFalse(call.state.isDucked)
        assertEquals(listOf(AudioFocusCommand.REQUEST_FOCUS), ended.commands)
        assertFalse(ended.state.shouldPlay)
        assertEquals(listOf(AudioFocusCommand.PLAY), gained.commands)
    }

    @Test
    fun callNeverResumesPlayerThatWasAlreadyPaused() {
        val paused = AudioFocusState(playWhenReady = false, focus = FocusOwnership.GRANTED)
        val call = AudioFocusPolicy.reduce(paused, AudioFocusEvent.CallStarted).state
        val ended = AudioFocusPolicy.reduce(call, AudioFocusEvent.CallEnded)

        assertTrue(ended.commands.isEmpty())
        assertFalse(ended.state.playWhenReady)
        assertFalse(ended.state.shouldPlay)
    }

    @Test
    fun focusGainDuringCallDoesNotStartUntilCallEnds() {
        val call = AudioFocusPolicy.reduce(playing(), AudioFocusEvent.CallStarted).state
        val gainedDuringCall = AudioFocusPolicy.reduce(call, focus(AudioFocusChange.GAIN))
        val ended = AudioFocusPolicy.reduce(gainedDuringCall.state, AudioFocusEvent.CallEnded)

        assertTrue(gainedDuringCall.commands.isEmpty())
        assertFalse(gainedDuringCall.state.shouldPlay)
        assertEquals(listOf(AudioFocusCommand.PLAY), ended.commands)
    }

    @Test
    fun transientDuckLowersGainWithoutPausing() {
        val ducked = AudioFocusPolicy.reduce(playing(), focus(AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK))
        val gained = AudioFocusPolicy.reduce(ducked.state, focus(AudioFocusChange.GAIN))

        assertEquals(listOf(AudioFocusCommand.DUCK), ducked.commands)
        assertTrue(ducked.state.shouldPlay)
        assertEquals(0.2, ducked.state.outputGain(), 0.0)
        assertEquals(listOf(AudioFocusCommand.UNDUCK), gained.commands)
        assertEquals(1.0, gained.state.outputGain(), 0.0)
    }

    @Test
    fun notificationAndNavigationDucksAreReferenceCountedByReason() {
        val notification = AudioFocusPolicy.reduce(
            playing(),
            AudioFocusEvent.DuckStarted(DuckReason.NOTIFICATION),
        )
        val both = AudioFocusPolicy.reduce(
            notification.state,
            AudioFocusEvent.DuckStarted(DuckReason.NAVIGATION),
        )
        val navigationOnly = AudioFocusPolicy.reduce(
            both.state,
            AudioFocusEvent.DuckEnded(DuckReason.NOTIFICATION),
        )
        val clear = AudioFocusPolicy.reduce(
            navigationOnly.state,
            AudioFocusEvent.DuckEnded(DuckReason.NAVIGATION),
        )

        assertEquals(listOf(AudioFocusCommand.DUCK), notification.commands)
        assertTrue(both.commands.isEmpty())
        assertEquals(setOf(DuckReason.NOTIFICATION, DuckReason.NAVIGATION), both.state.duckReasons)
        assertTrue(navigationOnly.commands.isEmpty())
        assertTrue(navigationOnly.state.isDucked)
        assertEquals(listOf(AudioFocusCommand.UNDUCK), clear.commands)
        assertEquals(1.0, clear.state.outputGain(), 0.0)
    }

    @Test
    fun focusGainDoesNotClearIndependentNotificationDuck() {
        val ducked = AudioFocusPolicy.reduce(
            playing(),
            AudioFocusEvent.DuckStarted(DuckReason.NOTIFICATION),
        ).state
        val focusDuck = AudioFocusPolicy.reduce(
            ducked,
            focus(AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK),
        ).state
        val gained = AudioFocusPolicy.reduce(focusDuck, focus(AudioFocusChange.GAIN))

        assertTrue(gained.commands.isEmpty())
        assertEquals(setOf(DuckReason.NOTIFICATION), gained.state.duckReasons)
        assertEquals(0.2, gained.state.outputGain(), 0.0)
    }

    @Test
    fun endingDuckDuringTransientLossDoesNotResumeEarly() {
        val ducked = AudioFocusPolicy.reduce(
            playing(),
            AudioFocusEvent.DuckStarted(DuckReason.NAVIGATION),
        ).state
        val lost = AudioFocusPolicy.reduce(ducked, focus(AudioFocusChange.LOSS_TRANSIENT)).state
        val duckEnded = AudioFocusPolicy.reduce(lost, AudioFocusEvent.DuckEnded(DuckReason.NAVIGATION))
        val gained = AudioFocusPolicy.reduce(duckEnded.state, focus(AudioFocusChange.GAIN))

        assertEquals(listOf(AudioFocusCommand.UNDUCK), duckEnded.commands)
        assertFalse(duckEnded.state.shouldPlay)
        assertEquals(listOf(AudioFocusCommand.PLAY), gained.commands)
    }

    @Test
    fun playbackCompletionClearsIntentAndReleasesFocus() {
        val completed = AudioFocusPolicy.reduce(playing(), AudioFocusEvent.PlaybackCompleted)

        assertEquals(listOf(AudioFocusCommand.PAUSE, AudioFocusCommand.ABANDON_FOCUS), completed.commands)
        assertFalse(completed.state.playWhenReady)
        assertFalse(completed.state.shouldPlay)
    }

    @Test
    fun duckGainCanBeChosenAndIsValidated() {
        val state = playing().copy(duckReasons = setOf(DuckReason.NAVIGATION))

        assertEquals(0.35, state.outputGain(0.35), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDuckGainIsRejected() {
        playing().outputGain(Double.NaN)
    }
}

class StartupResumePolicyTest {
    private val enabled = StartupResumeSettings(resumeOnColdStart = true, resumeOnReopen = true)

    private fun request(
        launch: AppLaunch = AppLaunch.COLD_START,
        storage: StorageReadiness = StorageReadiness.READY,
        focusGranted: Boolean = true,
        wasPlaying: Boolean = true,
        restorable: Boolean = true,
    ) = StartupResumeRequest(launch, wasPlaying, restorable, storage, focusGranted)

    @Test
    fun resumeIsOptInByDefault() {
        val decision = StartupResumePolicy.decide(request(), StartupResumeSettings())

        assertEquals(StartupResumeAction.DO_NOT_RESUME, decision.action)
        assertFalse(decision.restoreSnapshot)
    }

    @Test
    fun coldStartWaitsForStorageThenFocusThenResumesSnapshot() {
        val waiting = StartupResumePolicy.decide(
            request(storage = StorageReadiness.WAITING),
            enabled,
        )
        val needsFocus = StartupResumePolicy.decide(request(focusGranted = false), enabled)
        val ready = StartupResumePolicy.decide(request(), enabled)

        assertEquals(StartupResumeAction.WAIT_FOR_STORAGE, waiting.action)
        assertTrue(waiting.restoreSnapshot)
        assertEquals(StartupResumeAction.REQUEST_FOCUS, needsFocus.action)
        assertTrue(needsFocus.restoreSnapshot)
        assertEquals(StartupResumeAction.RESUME, ready.action)
        assertTrue(ready.restoreSnapshot)
    }

    @Test
    fun reopenUsesLivePlayerStateInsteadOfRestoringSnapshotAgain() {
        val decision = StartupResumePolicy.decide(request(launch = AppLaunch.REOPEN), enabled)

        assertEquals(StartupResumeAction.RESUME, decision.action)
        assertFalse(decision.restoreSnapshot)
    }

    @Test
    fun storageUnavailableDoesNotRequestFocusOrStart() {
        val decision = StartupResumePolicy.decide(
            request(storage = StorageReadiness.UNAVAILABLE, focusGranted = false),
            enabled,
        )

        assertEquals(StartupResumeAction.STORAGE_UNAVAILABLE, decision.action)
    }

    @Test
    fun pausedOrEmptyStateNeverResumes() {
        val paused = StartupResumePolicy.decide(request(wasPlaying = false), enabled)
        val empty = StartupResumePolicy.decide(request(restorable = false), enabled)

        assertEquals(StartupResumeAction.DO_NOT_RESUME, paused.action)
        assertEquals(StartupResumeAction.DO_NOT_RESUME, empty.action)
        assertFalse(empty.restoreSnapshot)
    }

    @Test
    fun coldStartAndReopenSettingsAreIndependent() {
        val settings = StartupResumeSettings(resumeOnColdStart = false, resumeOnReopen = true)

        assertEquals(
            StartupResumeAction.DO_NOT_RESUME,
            StartupResumePolicy.decide(request(), settings).action,
        )
        assertEquals(
            StartupResumeAction.RESUME,
            StartupResumePolicy.decide(request(launch = AppLaunch.REOPEN), settings).action,
        )
    }
}
