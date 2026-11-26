package com.music.bitchord.playback.fades

/** Switches used by [AudioFocusPolicy]; Android focus constants deliberately stay at the caller. */
data class AudioFocusSettings(
    val pauseInCall: Boolean = true,
    val notificationDuck: Boolean = true,
    val resumeOnStart: Boolean = false,
    val resumeOnReopen: Boolean = false,
    val waitForStorage: Boolean = true,
    val noDuckOverride: Boolean = false,
    /**
     * Folded in from a per-device `no_af` ("Ignore Audio Focus") profile flag at
     * runtime; never a stored global choice on its own. When set, the route
     * ignores duckable notifications, calls and permanent focus loss alike —
     * only storage gating still blocks playback.
     */
    val ignoreAudioFocus: Boolean = false,
)

/** Listener intent, transient interruptions, and the resulting actual playback state. */
data class AudioFocusState(
    val playbackRequested: Boolean = false,
    val isPlaying: Boolean = false,
    val outputGain: Float = 1f,
    val callActive: Boolean = false,
    val notificationActive: Boolean = false,
    val notificationCanDuck: Boolean = true,
    val permanentFocusLost: Boolean = false,
    val storageReady: Boolean = true,
    val waitingForStorage: Boolean = false,
) {
    val isDucked: Boolean
        get() = isPlaying && outputGain < 1f
}

sealed interface AudioFocusEvent {
    data object UserPlay : AudioFocusEvent
    data object UserPause : AudioFocusEvent
    data object ServiceStarted : AudioFocusEvent
    data object AppReopened : AudioFocusEvent
    data class CallChanged(val active: Boolean) : AudioFocusEvent
    data class NotificationChanged(
        val active: Boolean,
        val canDuck: Boolean = true,
    ) : AudioFocusEvent

    data object PermanentFocusLost : AudioFocusEvent
    data class StorageChanged(val ready: Boolean) : AudioFocusEvent

    /** Re-evaluates gains and blockers after the caller supplies changed [AudioFocusSettings]. */
    data object SettingsChanged : AudioFocusEvent
}

enum class AudioFocusCommand {
    NONE,
    PLAY,
    PAUSE,
}

data class AudioFocusTransition(
    val state: AudioFocusState,
    val command: AudioFocusCommand,
    val gainChanged: Boolean,
)

/**
 * Pure reducer for calls, notification focus, lifecycle resume, and removable-storage startup.
 *
 * Automatic interruptions preserve [AudioFocusState.playbackRequested], so they can resume only
 * playback they interrupted. [AudioFocusEvent.UserPause] clears that intent and therefore cancels
 * every pending resume. Permanent focus loss also clears it. [AudioFocusState.outputGain] should be
 * applied through [ClickFreeGain], including when [AudioFocusTransition.command] is [AudioFocusCommand.NONE].
 */
object AudioFocusPolicy {
    const val NOTIFICATION_DUCK_GAIN = 0.25f

    fun reduce(
        state: AudioFocusState,
        event: AudioFocusEvent,
        settings: AudioFocusSettings = AudioFocusSettings(),
    ): AudioFocusTransition {
        val changed = when (event) {
            AudioFocusEvent.UserPlay -> state.copy(
                playbackRequested = true,
                permanentFocusLost = false,
            )

            AudioFocusEvent.UserPause -> state.copy(playbackRequested = false)
            // A permanent loss blocks even the lifecycle resumes until the user
            // plays again (UserPlay clears it); a route with ignoreAudioFocus
            // (no_af) is not interrupted at all.
            AudioFocusEvent.ServiceStarted -> if (settings.resumeOnStart && !state.permanentFocusLost) {
                state.copy(playbackRequested = true)
            } else {
                state
            }

            AudioFocusEvent.AppReopened -> if (settings.resumeOnReopen && !state.permanentFocusLost) {
                state.copy(playbackRequested = true)
            } else {
                state
            }

            is AudioFocusEvent.CallChanged -> state.copy(callActive = event.active)
            is AudioFocusEvent.NotificationChanged -> state.copy(
                notificationActive = event.active,
                notificationCanDuck = event.canDuck,
            )

            AudioFocusEvent.PermanentFocusLost -> if (settings.ignoreAudioFocus) {
                // no_af route: the loss is recorded but neither pauses nor
                // cancels the resume intent.
                state.copy(permanentFocusLost = true)
            } else {
                state.copy(
                    playbackRequested = false,
                    permanentFocusLost = true,
                )
            }

            is AudioFocusEvent.StorageChanged -> state.copy(storageReady = event.ready)
            AudioFocusEvent.SettingsChanged -> state
        }
        val next = resolve(changed, settings)
        val command = when {
            !state.isPlaying && next.isPlaying -> AudioFocusCommand.PLAY
            state.isPlaying && !next.isPlaying -> AudioFocusCommand.PAUSE
            else -> AudioFocusCommand.NONE
        }
        return AudioFocusTransition(
            state = next,
            command = command,
            gainChanged = state.outputGain != next.outputGain,
        )
    }

    private fun resolve(
        state: AudioFocusState,
        settings: AudioFocusSettings,
    ): AudioFocusState {
        // ignoreAudioFocus (per-device no_af) suppresses every focus-driven
        // block and the duck; storage readiness is not audio focus and stays.
        val focusIgnored = settings.ignoreAudioFocus
        val notificationDucked = state.notificationActive &&
            state.notificationCanDuck &&
            settings.notificationDuck &&
            !settings.noDuckOverride &&
            !focusIgnored
        val notificationPaused = !focusIgnored && state.notificationActive &&
            (!state.notificationCanDuck || (!settings.notificationDuck && !settings.noDuckOverride))
        val storageBlocked = settings.waitForStorage && !state.storageReady
        val blocked = (!focusIgnored && state.permanentFocusLost) ||
            (!focusIgnored && settings.pauseInCall && state.callActive) ||
            notificationPaused ||
            storageBlocked
        val playing = state.playbackRequested && !blocked
        return state.copy(
            isPlaying = playing,
            outputGain = if (playing && notificationDucked) NOTIFICATION_DUCK_GAIN else 1f,
            waitingForStorage = state.playbackRequested && storageBlocked,
        )
    }
}
