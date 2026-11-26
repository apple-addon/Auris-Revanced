package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.playback.fades.AudioFocusEvent
import com.music.bitchord.playback.fades.AudioFocusPolicy
import com.music.bitchord.playback.fades.AudioFocusSettings
import com.music.bitchord.playback.fades.AudioFocusState
import com.music.bitchord.playback.fades.AudioFocusTransition

/**
 * Thread-safe, service-agnostic owner of the focus reducer's state.
 *
 * The caller translates Android focus, telephony, lifecycle and storage events into
 * [AudioFocusEvent]. It remains responsible for requesting/abandoning Android audio focus and for
 * executing the returned play/pause commands; this class deliberately has no Android or player
 * dependency. Per-output No Duck and Perfect BP are folded into the effective settings without
 * overwriting the persisted choices.
 */
class AudioFocusStateMachine(
    focusSettings: AudioFocusSettings = AudioFocusSettings(),
    outputNoDuck: Boolean = false,
    outputIgnoreAudioFocus: Boolean = false,
) {
    var settings: AudioFocusSettings = effectiveSettings(focusSettings, outputNoDuck, outputIgnoreAudioFocus)
        private set

    var state: AudioFocusState = AudioFocusPolicy.reduce(
        AudioFocusState(),
        AudioFocusEvent.SettingsChanged,
        settings,
    ).state
        private set

    @Synchronized
    fun dispatch(event: AudioFocusEvent): AudioFocusTransition {
        val transition = AudioFocusPolicy.reduce(state, event, settings)
        state = transition.state
        return transition
    }

    /** Returns null when the effective policy did not change, avoiding redundant service callbacks. */
    @Synchronized
    fun updateSettings(
        focusSettings: AudioFocusSettings,
        outputNoDuck: Boolean = false,
        outputIgnoreAudioFocus: Boolean = false,
    ): AudioFocusTransition? {
        val effective = effectiveSettings(focusSettings, outputNoDuck, outputIgnoreAudioFocus)
        if (effective == settings) return null
        settings = effective
        return dispatch(AudioFocusEvent.SettingsChanged)
    }

    private fun effectiveSettings(
        focusSettings: AudioFocusSettings,
        outputNoDuck: Boolean,
        outputIgnoreAudioFocus: Boolean,
    ): AudioFocusSettings {
        var effective = focusSettings
        if (outputNoDuck && !effective.noDuckOverride) {
            effective = effective.copy(noDuckOverride = true)
        }
        // Per-device no_af ("Ignore Audio Focus"): the route ignores duck and
        // permanent pause as well, without touching the persisted choices.
        if (outputIgnoreAudioFocus && !effective.ignoreAudioFocus) {
            effective = effective.copy(ignoreAudioFocus = true, noDuckOverride = true)
        }
        return effective
    }
}
