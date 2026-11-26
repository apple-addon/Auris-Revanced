package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.playback.fades.AudioFocusCommand
import com.music.bitchord.playback.fades.AudioFocusEvent
import com.music.bitchord.playback.fades.AudioFocusPolicy
import com.music.bitchord.playback.fades.AudioFocusSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusStateMachineTest {
    @Test
    fun callsPauseAndResumeOnlyRequestedPlayback() {
        val machine = AudioFocusStateMachine()
        machine.dispatch(AudioFocusEvent.UserPlay)
        val call = machine.dispatch(AudioFocusEvent.CallChanged(true))
        assertEquals(AudioFocusCommand.PAUSE, call.command)
        assertFalse(machine.state.isPlaying)

        machine.dispatch(AudioFocusEvent.CallChanged(false))
        assertTrue(machine.state.isPlaying)

        val secondCall = machine.dispatch(AudioFocusEvent.CallChanged(true)).state
        assertTrue(secondCall.playbackRequested)
        machine.dispatch(AudioFocusEvent.UserPause)
        val ended = machine.dispatch(AudioFocusEvent.CallChanged(false))
        assertEquals(AudioFocusCommand.NONE, ended.command)
        assertFalse(machine.state.isPlaying)
    }

    @Test
    fun outputNoDuckIsAnEffectiveConflictThatPreservesStoredSettings() {
        val stored = AudioFocusSettings(notificationDuck = true, noDuckOverride = false)
        val machine = AudioFocusStateMachine(stored)
        machine.dispatch(AudioFocusEvent.UserPlay)
        val transition = machine.updateSettings(stored, outputNoDuck = true)
        assertNotNull(transition)
        assertEquals(true, machine.settings.noDuckOverride)

        val full = machine.dispatch(AudioFocusEvent.NotificationChanged(active = true))
        assertEquals(AudioFocusCommand.NONE, full.command)
        assertEquals(1f, full.state.outputGain, 0f)
        assertFalse(full.state.isDucked)

        machine.updateSettings(stored, outputNoDuck = false)
        val ducked = machine.dispatch(AudioFocusEvent.NotificationChanged(active = false))
            .let { machine.dispatch(AudioFocusEvent.NotificationChanged(active = true)) }
        assertEquals(AudioFocusPolicy.NOTIFICATION_DUCK_GAIN, ducked.state.outputGain, 0f)
        assertTrue(ducked.state.isDucked)
    }

    @Test
    fun noDuckCannotIgnoreNonDuckableFocusLoss() {
        val machine = AudioFocusStateMachine(outputNoDuck = true)
        machine.dispatch(AudioFocusEvent.UserPlay)
        val paused = machine.dispatch(AudioFocusEvent.NotificationChanged(active = true, canDuck = false))
        assertEquals(AudioFocusCommand.PAUSE, paused.command)
        assertFalse(paused.state.isPlaying)
    }

    @Test
    fun resumeOnStartWaitsForStorageAndUserPauseCancelsDeferredResume() {
        val settings = AudioFocusSettings(resumeOnStart = true, waitForStorage = true)
        val machine = AudioFocusStateMachine(settings)
        machine.dispatch(AudioFocusEvent.StorageChanged(ready = false))
        val waiting = machine.dispatch(AudioFocusEvent.ServiceStarted)
        assertEquals(AudioFocusCommand.NONE, waiting.command)
        assertTrue(waiting.state.waitingForStorage)

        machine.dispatch(AudioFocusEvent.UserPause)
        val mounted = machine.dispatch(AudioFocusEvent.StorageChanged(ready = true))
        assertEquals(AudioFocusCommand.NONE, mounted.command)
        assertFalse(mounted.state.isPlaying)
        assertFalse(mounted.state.waitingForStorage)

        machine.updateSettings(settings, outputNoDuck = false)
        machine.dispatch(AudioFocusEvent.UserPlay)
        machine.dispatch(AudioFocusEvent.UserPause)
        val resumedThenPaused = machine.dispatch(AudioFocusEvent.UserPlay)
        assertEquals(AudioFocusCommand.PLAY, resumedThenPaused.command)
    }

    @Test
    fun permanentFocusLossClearsResumeIntentUntilTransientInterruptionsEnd() {
        val machine = AudioFocusStateMachine(AudioFocusSettings())
        machine.dispatch(AudioFocusEvent.UserPlay)
        val lost = machine.dispatch(AudioFocusEvent.PermanentFocusLost)
        assertEquals(AudioFocusCommand.PAUSE, lost.command)
        assertTrue(lost.state.permanentFocusLost)

        machine.dispatch(AudioFocusEvent.NotificationChanged(active = true))
        val ended = machine.dispatch(AudioFocusEvent.NotificationChanged(active = false))
        assertEquals(AudioFocusCommand.NONE, ended.command)
        assertFalse(machine.state.isPlaying)
    }

    @Test
    fun unchangedEffectiveSettingsDoNotEmitACallbackTransition() {
        val settings = AudioFocusSettings()
        val machine = AudioFocusStateMachine(settings)
        assertNull(machine.updateSettings(settings.copy(), outputNoDuck = false))
        assertNotNull(machine.updateSettings(settings.copy(noDuckOverride = true), outputNoDuck = true))
    }

    /**
     * The service dispatches this exact sequence for a duckable notification
     * (AudioManager AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK). The duck must lower
     * the gain *without* a pause command, and the restore must raise it again
     * without a play command — the controller delivers the gain before any
     * command, and here there is no command at all.
     */
    @Test
    fun notificationDuckChangesGainWithoutPlayOrPauseCommands() {
        val machine = AudioFocusStateMachine(AudioFocusSettings(notificationDuck = true))
        val play = machine.dispatch(AudioFocusEvent.UserPlay)
        assertEquals(AudioFocusCommand.PLAY, play.command)
        assertFalse(play.gainChanged)

        val duck = machine.dispatch(AudioFocusEvent.NotificationChanged(active = true, canDuck = true))
        assertEquals(AudioFocusCommand.NONE, duck.command)
        assertTrue(duck.gainChanged)
        assertEquals(AudioFocusPolicy.NOTIFICATION_DUCK_GAIN, duck.state.outputGain, 0f)
        assertTrue(duck.state.isDucked)

        val restore = machine.dispatch(AudioFocusEvent.NotificationChanged(active = false))
        assertEquals(AudioFocusCommand.NONE, restore.command)
        assertTrue(restore.gainChanged)
        assertEquals(1f, restore.state.outputGain, 0f)
        assertTrue(restore.state.isPlaying)
    }

    /** The full service-side lifecycle: start, reopen, call, permanent loss, storage. */
    @Test
    fun serviceLifecycleEventsDriveResumeOnlyWhenRequested() {
        val machine = AudioFocusStateMachine(
            AudioFocusSettings(resumeOnStart = true, resumeOnReopen = true, waitForStorage = true),
        )
        val unmounted = machine.dispatch(AudioFocusEvent.StorageChanged(ready = false))
        assertEquals(AudioFocusCommand.NONE, unmounted.command)

        val started = machine.dispatch(AudioFocusEvent.ServiceStarted)
        assertEquals(AudioFocusCommand.NONE, started.command)
        assertTrue(started.state.waitingForStorage)

        val mounted = machine.dispatch(AudioFocusEvent.StorageChanged(ready = true))
        assertEquals(AudioFocusCommand.PLAY, mounted.command)

        machine.dispatch(AudioFocusEvent.UserPause)
        val reopened = machine.dispatch(AudioFocusEvent.AppReopened)
        assertEquals(AudioFocusCommand.PLAY, reopened.command)
        assertTrue(reopened.state.isPlaying)

        val call = machine.dispatch(AudioFocusEvent.CallChanged(active = true))
        assertEquals(AudioFocusCommand.PAUSE, call.command)
        val ended = machine.dispatch(AudioFocusEvent.CallChanged(active = false))
        assertEquals(AudioFocusCommand.PLAY, ended.command)

        val lost = machine.dispatch(AudioFocusEvent.PermanentFocusLost)
        assertEquals(AudioFocusCommand.PAUSE, lost.command)
        assertTrue(lost.state.permanentFocusLost)
        // Permanent loss clears the resume intent, so a later reopen stays down.
        val after = machine.dispatch(AudioFocusEvent.AppReopened)
        assertEquals(AudioFocusCommand.NONE, after.command)
        assertFalse(after.state.isPlaying)
    }

    /** With both resume switches off, service start and reopen change nothing. */
    @Test
    fun serviceStartAndReopenDoNothingWithoutResumeSwitches() {
        val machine = AudioFocusStateMachine(AudioFocusSettings())
        assertEquals(AudioFocusCommand.NONE, machine.dispatch(AudioFocusEvent.ServiceStarted).command)
        assertEquals(AudioFocusCommand.NONE, machine.dispatch(AudioFocusEvent.AppReopened).command)
        machine.dispatch(AudioFocusEvent.UserPlay)
        machine.dispatch(AudioFocusEvent.UserPause)
        assertEquals(AudioFocusCommand.NONE, machine.dispatch(AudioFocusEvent.AppReopened).command)
        assertFalse(machine.state.isPlaying)
    }

    /** A per-device no_af flag folds into the effective policy without touching stored settings. */
    @Test
    fun outputIgnoreAudioFocusFoldsNoAfIntoEffectivePolicy() {
        val stored = AudioFocusSettings()
        val machine = AudioFocusStateMachine(stored, outputIgnoreAudioFocus = true)
        assertTrue(machine.settings.ignoreAudioFocus)
        assertTrue(machine.settings.noDuckOverride)

        machine.dispatch(AudioFocusEvent.UserPlay)
        val duck = machine.dispatch(AudioFocusEvent.NotificationChanged(active = true, canDuck = true))
        assertEquals(AudioFocusCommand.NONE, duck.command)
        assertEquals(1f, duck.state.outputGain, 0f)

        val call = machine.dispatch(AudioFocusEvent.CallChanged(active = true))
        assertEquals(AudioFocusCommand.NONE, call.command)
        assertTrue(call.state.isPlaying)

        val lost = machine.dispatch(AudioFocusEvent.PermanentFocusLost)
        assertEquals(AudioFocusCommand.NONE, lost.command)
        assertTrue(lost.state.isPlaying)

        // Lifting the flag restores the stored policy exactly. The recorded
        // permanent loss still blocks until the user plays again, and the call
        // from above is still active, so end both before expecting a duck.
        assertNotNull(machine.updateSettings(stored, outputNoDuck = false, outputIgnoreAudioFocus = false))
        assertFalse(machine.settings.ignoreAudioFocus)
        machine.dispatch(AudioFocusEvent.CallChanged(active = false))
        machine.dispatch(AudioFocusEvent.UserPlay)
        val ducked = machine.dispatch(AudioFocusEvent.NotificationChanged(active = true, canDuck = true))
        assertEquals(AudioFocusPolicy.NOTIFICATION_DUCK_GAIN, ducked.state.outputGain, 0f)
    }
}
