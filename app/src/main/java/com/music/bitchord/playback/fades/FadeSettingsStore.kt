package com.music.bitchord.playback.fades

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Atomic, persistent fade settings with one immediately available [StateFlow].
 *
 * Multiple stores can share the same preference file: the listener keeps each flow in sync,
 * including changes made by backup restore. Call [close] when an owner is destroyed.
 */
class FadeSettingsStore(
    private val preferences: SharedPreferences,
) : AutoCloseable {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<FadeSettings> = mutableState.asStateFlow()

    /** Alias useful at call sites that prefer the domain name over the store terminology. */
    val settings: StateFlow<FadeSettings>
        get() = state

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in KEYS) reload()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    @Synchronized
    fun reload() {
        mutableState.value = read()
    }

    /** Writes all ten values in one preference transaction and then publishes the same snapshot. */
    @Synchronized
    fun update(settings: FadeSettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putLong(KEY_PRELOAD, normalized.preloadGaplessTracksMs)
            .putLong(KEY_SILENCE, normalized.silenceBetweenTracksMs)
            .putLong(KEY_CROSSFADE, normalized.crossfadeLengthMs)
            .putLong(KEY_SHORT_MANUAL, normalized.shortManualCrossfadeMs)
            .putLong(KEY_TRANSPORT, normalized.playPauseStopFadeMs)
            .putLong(KEY_SEEK, normalized.seekFadeMs)
            .putBoolean(KEY_FADE_PLAY_PAUSE, normalized.fadePlayPause)
            .putBoolean(KEY_FADE_SEEK, normalized.fadeSeek)
            .putBoolean(KEY_CROSSFADE_AUTO_ADVANCE, normalized.crossfadeAutoAdvance)
            .putBoolean(KEY_FADE_MANUAL_ADVANCE, normalized.fadeManualAdvance)
            .apply()
        mutableState.value = normalized
    }

    fun setPreloadGaplessTracks(preset: GaplessPreloadPreset) =
        setPreloadGaplessTracksMs(preset.durationMs)

    @Synchronized
    fun setPreloadGaplessTracksMs(durationMs: Long) =
        update(state.value.copy(preloadGaplessTracksMs = durationMs))

    @Synchronized
    fun setSilenceBetweenTracksMs(durationMs: Long) =
        update(state.value.copy(silenceBetweenTracksMs = durationMs))

    @Synchronized
    fun setCrossfadeLengthMs(durationMs: Long) =
        update(state.value.copy(crossfadeLengthMs = durationMs))

    @Synchronized
    fun setShortManualCrossfadeMs(durationMs: Long) =
        update(state.value.copy(shortManualCrossfadeMs = durationMs))

    @Synchronized
    fun setPlayPauseStopFadeMs(durationMs: Long) =
        update(state.value.copy(playPauseStopFadeMs = durationMs))

    @Synchronized
    fun setSeekFadeMs(durationMs: Long) =
        update(state.value.copy(seekFadeMs = durationMs))

    @Synchronized
    fun setFadePlayPause(enabled: Boolean) =
        update(state.value.copy(fadePlayPause = enabled))

    @Synchronized
    fun setFadeSeek(enabled: Boolean) =
        update(state.value.copy(fadeSeek = enabled))

    @Synchronized
    fun setCrossfadeAutoAdvance(enabled: Boolean) =
        update(state.value.copy(crossfadeAutoAdvance = enabled))

    @Synchronized
    fun setFadeManualAdvance(enabled: Boolean) =
        update(state.value.copy(fadeManualAdvance = enabled))

    /** Removes only this component's keys; unrelated app preferences are left untouched. */
    @Synchronized
    fun restoreDefaults() {
        val editor = preferences.edit()
        KEYS.forEach(editor::remove)
        editor.apply()
        mutableState.value = FadeSettings.DEFAULTS
    }

    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun read(): FadeSettings = FadeSettings(
        preloadGaplessTracksMs = readLong(KEY_PRELOAD, FadeSettings.DEFAULT_PRELOAD_GAPLESS_TRACKS_MS),
        silenceBetweenTracksMs = readLong(KEY_SILENCE, FadeSettings.DEFAULT_SILENCE_BETWEEN_TRACKS_MS),
        crossfadeLengthMs = readLong(KEY_CROSSFADE, FadeSettings.DEFAULT_CROSSFADE_LENGTH_MS),
        shortManualCrossfadeMs = readLong(KEY_SHORT_MANUAL, FadeSettings.DEFAULT_SHORT_MANUAL_CROSSFADE_MS),
        playPauseStopFadeMs = readLong(KEY_TRANSPORT, FadeSettings.DEFAULT_PLAY_PAUSE_STOP_FADE_MS),
        seekFadeMs = readLong(KEY_SEEK, FadeSettings.DEFAULT_SEEK_FADE_MS),
        fadePlayPause = readBoolean(KEY_FADE_PLAY_PAUSE, FadeSettings.DEFAULT_FADE_PLAY_PAUSE),
        fadeSeek = readBoolean(KEY_FADE_SEEK, FadeSettings.DEFAULT_FADE_SEEK),
        crossfadeAutoAdvance = readBoolean(KEY_CROSSFADE_AUTO_ADVANCE, FadeSettings.DEFAULT_CROSSFADE_AUTO_ADVANCE),
        fadeManualAdvance = readBoolean(KEY_FADE_MANUAL_ADVANCE, FadeSettings.DEFAULT_FADE_MANUAL_ADVANCE),
    ).normalized()

    private fun readLong(key: String, default: Long): Long =
        runCatching { preferences.getLong(key, default) }.getOrDefault(default)

    private fun readBoolean(key: String, default: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, default) }.getOrDefault(default)

    companion object {
        const val FILE_NAME = "bitchord_playback_fades"

        private const val KEY_PRELOAD = "preload_gapless_tracks_ms"
        private const val KEY_SILENCE = "silence_between_tracks_ms"
        private const val KEY_CROSSFADE = "crossfade_length_ms"
        private const val KEY_SHORT_MANUAL = "short_manual_crossfade_ms"
        private const val KEY_TRANSPORT = "play_pause_stop_fade_ms"
        private const val KEY_SEEK = "seek_fade_ms"
        // Reference fade.xml key names; absent keys fall back to the defaults,
        // so installs written before these switches existed keep their behavior.
        private const val KEY_FADE_PLAY_PAUSE = "fade_play_pause"
        private const val KEY_FADE_SEEK = "fade_seek"
        private const val KEY_CROSSFADE_AUTO_ADVANCE = "crossfade_auto_advance"
        private const val KEY_FADE_MANUAL_ADVANCE = "fade_manual_advance"

        private val KEYS = setOf(
            KEY_PRELOAD,
            KEY_SILENCE,
            KEY_CROSSFADE,
            KEY_SHORT_MANUAL,
            KEY_TRANSPORT,
            KEY_SEEK,
            KEY_FADE_PLAY_PAUSE,
            KEY_FADE_SEEK,
            KEY_CROSSFADE_AUTO_ADVANCE,
            KEY_FADE_MANUAL_ADVANCE,
        )
    }
}
