package com.music.bitchord.playback.policy

/** Android-free focus states; the host maps these to AudioManager/AudioFocusRequest. */
enum class FocusOwnership {
    NONE,
    WAITING,
    GRANTED,
}

enum class AudioFocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
    REQUEST_DELAYED,
    REQUEST_FAILED,
}

enum class DuckReason {
    AUDIO_FOCUS,
    NOTIFICATION,
    NAVIGATION,
}

sealed interface AudioFocusEvent {
    data object UserPlay : AudioFocusEvent
    data object UserPause : AudioFocusEvent
    data object PlaybackCompleted : AudioFocusEvent
    data object CallStarted : AudioFocusEvent
    data object CallEnded : AudioFocusEvent
    data class FocusChanged(val change: AudioFocusChange) : AudioFocusEvent
    data class DuckStarted(val reason: DuckReason) : AudioFocusEvent
    data class DuckEnded(val reason: DuckReason) : AudioFocusEvent
}

data class AudioFocusState(
    /** User intent. Focus-caused pauses preserve it; a user pause/permanent loss clears it. */
    val playWhenReady: Boolean = false,
    val focus: FocusOwnership = FocusOwnership.NONE,
    val transientLoss: Boolean = false,
    val callActive: Boolean = false,
    val duckReasons: Set<DuckReason> = emptySet(),
) {
    val shouldPlay: Boolean
        get() = playWhenReady && focus == FocusOwnership.GRANTED && !transientLoss && !callActive

    val isDucked: Boolean get() = duckReasons.isNotEmpty()

    @JvmOverloads
    fun outputGain(duckGain: Double = AudioFocusPolicy.DEFAULT_DUCK_GAIN): Double {
        require(duckGain.isFinite() && duckGain in 0.0..1.0) { "duckGain must be in 0..1" }
        return if (isDucked) duckGain else 1.0
    }
}

enum class AudioFocusCommand {
    PLAY,
    PAUSE,
    REQUEST_FOCUS,
    ABANDON_FOCUS,
    DUCK,
    UNDUCK,
}

data class AudioFocusTransition(
    val state: AudioFocusState,
    val commands: List<AudioFocusCommand>,
)

/**
 * Immutable reducer for phone calls, transient focus and overlapping duck owners.
 * It never resumes a user-paused player and never starts playback while focus is delayed.
 */
object AudioFocusPolicy {
    const val DEFAULT_DUCK_GAIN = 0.2

    @JvmStatic
    fun reduce(state: AudioFocusState, event: AudioFocusEvent): AudioFocusTransition {
        var requestFocus = false
        var abandonFocus = false
        val next = when (event) {
            AudioFocusEvent.UserPlay -> {
                val updated = state.copy(playWhenReady = true)
                requestFocus = updated.canRequestFocus
                updated
            }
            AudioFocusEvent.UserPause -> {
                abandonFocus = state.focus != FocusOwnership.NONE
                state.copy(playWhenReady = false, focus = FocusOwnership.NONE)
            }
            AudioFocusEvent.PlaybackCompleted -> {
                abandonFocus = state.focus != FocusOwnership.NONE
                state.copy(playWhenReady = false, focus = FocusOwnership.NONE)
            }
            AudioFocusEvent.CallStarted -> {
                // Calls always pause, never duck. Release our request even if the platform's
                // separate AUDIOFOCUS_LOSS callback arrives later.
                abandonFocus = state.focus != FocusOwnership.NONE
                state.copy(callActive = true, focus = FocusOwnership.NONE)
            }
            AudioFocusEvent.CallEnded -> {
                val updated = state.copy(callActive = false)
                requestFocus = updated.canRequestFocus
                updated
            }
            is AudioFocusEvent.FocusChanged -> when (event.change) {
                AudioFocusChange.GAIN -> state.copy(
                    focus = FocusOwnership.GRANTED,
                    transientLoss = false,
                    duckReasons = state.duckReasons - DuckReason.AUDIO_FOCUS,
                )
                AudioFocusChange.LOSS -> state.copy(
                    playWhenReady = false,
                    focus = FocusOwnership.NONE,
                    transientLoss = false,
                    duckReasons = state.duckReasons - DuckReason.AUDIO_FOCUS,
                )
                AudioFocusChange.LOSS_TRANSIENT -> state.copy(
                    focus = FocusOwnership.NONE,
                    transientLoss = true,
                )
                AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK -> state.copy(
                    focus = FocusOwnership.GRANTED,
                    transientLoss = false,
                    duckReasons = state.duckReasons + DuckReason.AUDIO_FOCUS,
                )
                AudioFocusChange.REQUEST_DELAYED -> state.copy(focus = FocusOwnership.WAITING)
                AudioFocusChange.REQUEST_FAILED -> state.copy(focus = FocusOwnership.NONE)
            }
            is AudioFocusEvent.DuckStarted -> state.copy(
                duckReasons = state.duckReasons + event.reason,
            )
            is AudioFocusEvent.DuckEnded -> state.copy(
                duckReasons = state.duckReasons - event.reason,
            )
        }

        val commands = mutableListOf<AudioFocusCommand>()
        if (state.shouldPlay != next.shouldPlay) {
            commands += if (next.shouldPlay) AudioFocusCommand.PLAY else AudioFocusCommand.PAUSE
        }
        if (state.isDucked != next.isDucked) {
            commands += if (next.isDucked) AudioFocusCommand.DUCK else AudioFocusCommand.UNDUCK
        }
        if (requestFocus) commands += AudioFocusCommand.REQUEST_FOCUS
        if (abandonFocus) commands += AudioFocusCommand.ABANDON_FOCUS
        return AudioFocusTransition(next, commands)
    }

    private val AudioFocusState.canRequestFocus: Boolean
        get() = playWhenReady && focus == FocusOwnership.NONE && !transientLoss && !callActive
}

enum class AppLaunch {
    COLD_START,
    REOPEN,
}

enum class StorageReadiness {
    WAITING,
    READY,
    UNAVAILABLE,
}

data class StartupResumeSettings(
    val resumeOnColdStart: Boolean = false,
    val resumeOnReopen: Boolean = false,
)

data class StartupResumeRequest(
    val launch: AppLaunch,
    val wasPlaying: Boolean,
    val hasRestorablePlayback: Boolean,
    val storage: StorageReadiness,
    val focusGranted: Boolean,
)

enum class StartupResumeAction {
    DO_NOT_RESUME,
    WAIT_FOR_STORAGE,
    STORAGE_UNAVAILABLE,
    REQUEST_FOCUS,
    RESUME,
}

data class StartupResumeDecision(
    val action: StartupResumeAction,
    /** Cold starts restore queue/position; a reopen uses the already restored player state. */
    val restoreSnapshot: Boolean,
)

/** Resume is opt-in, storage-gated and focus-gated. Waiting never drops saved intent. */
object StartupResumePolicy {
    @JvmStatic
    fun decide(
        request: StartupResumeRequest,
        settings: StartupResumeSettings,
    ): StartupResumeDecision {
        val enabled = when (request.launch) {
            AppLaunch.COLD_START -> settings.resumeOnColdStart
            AppLaunch.REOPEN -> settings.resumeOnReopen
        }
        val restore = request.launch == AppLaunch.COLD_START && request.hasRestorablePlayback
        val action = when {
            !enabled || !request.wasPlaying || !request.hasRestorablePlayback ->
                StartupResumeAction.DO_NOT_RESUME
            request.storage == StorageReadiness.WAITING -> StartupResumeAction.WAIT_FOR_STORAGE
            request.storage == StorageReadiness.UNAVAILABLE -> StartupResumeAction.STORAGE_UNAVAILABLE
            !request.focusGranted -> StartupResumeAction.REQUEST_FOCUS
            else -> StartupResumeAction.RESUME
        }
        return StartupResumeDecision(action, restoreSnapshot = restore && action != StartupResumeAction.DO_NOT_RESUME)
    }
}
