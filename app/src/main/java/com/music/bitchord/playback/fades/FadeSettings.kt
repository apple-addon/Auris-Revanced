package com.music.bitchord.playback.fades

/** Preload choices exposed by the fade/gapless settings screen. */
enum class GaplessPreloadPreset(val durationMs: Long) {
    NONE(0L),
    NORMAL(2_500L),
    MORE(5_000L),
    ;

    companion object {
        /** Returns null for a custom duration rather than snapping it to a preset. */
        fun matching(durationMs: Long): GaplessPreloadPreset? =
            entries.firstOrNull { it.durationMs == durationMs }
    }
}

/**
 * Listener-owned fade and gapless durations. Zero disables the corresponding delay or ramp.
 *
 * This type has no Android dependency so transition and rendering code can use it in JVM tests.
 */
data class FadeSettings(
    val preloadGaplessTracksMs: Long = DEFAULT_PRELOAD_GAPLESS_TRACKS_MS,
    val silenceBetweenTracksMs: Long = DEFAULT_SILENCE_BETWEEN_TRACKS_MS,
    val crossfadeLengthMs: Long = DEFAULT_CROSSFADE_LENGTH_MS,
    val shortManualCrossfadeMs: Long = DEFAULT_SHORT_MANUAL_CROSSFADE_MS,
    val playPauseStopFadeMs: Long = DEFAULT_PLAY_PAUSE_STOP_FADE_MS,
    val seekFadeMs: Long = DEFAULT_SEEK_FADE_MS,
    /**
     * Reference `fade_play_pause` switch (default true): whether play/pause/stop
     * ramp over [playPauseStopFadeMs]. Off means an immediate gain change with
     * the same one-shot commands; the length itself is never rewritten.
     */
    val fadePlayPause: Boolean = DEFAULT_FADE_PLAY_PAUSE,
    /**
     * Reference `fade_seek` switch (default true): whether a seek on a playing
     * track fades out/in over [seekFadeMs]. Off means the seek lands at once.
     */
    val fadeSeek: Boolean = DEFAULT_FADE_SEEK,
    /**
     * Reference `crossfade_auto_advance` switch: whether the queue advancing on
     * its own crossfades over [crossfadeLengthMs]. Off keeps the length stored
     * but arms no auto-advance blend.
     */
    val crossfadeAutoAdvance: Boolean = DEFAULT_CROSSFADE_AUTO_ADVANCE,
    /**
     * Reference `fade_manual_advance` switch: whether a manual track change uses
     * [shortManualCrossfadeMs]. Off keeps the length stored but plans none.
     */
    val fadeManualAdvance: Boolean = DEFAULT_FADE_MANUAL_ADVANCE,
) {
    /** Treats malformed or imported negative durations as disabled. */
    fun normalized(): FadeSettings = copy(
        preloadGaplessTracksMs = preloadGaplessTracksMs.coerceAtLeast(0L),
        silenceBetweenTracksMs = silenceBetweenTracksMs.coerceAtLeast(0L),
        crossfadeLengthMs = crossfadeLengthMs.coerceAtLeast(0L),
        shortManualCrossfadeMs = shortManualCrossfadeMs.coerceAtLeast(0L),
        playPauseStopFadeMs = playPauseStopFadeMs.coerceAtLeast(0L),
        seekFadeMs = seekFadeMs.coerceAtLeast(0L),
    )

    companion object {
        const val DEFAULT_PRELOAD_GAPLESS_TRACKS_MS = 0L
        const val DEFAULT_SILENCE_BETWEEN_TRACKS_MS = 0L
        const val DEFAULT_CROSSFADE_LENGTH_MS = 5_000L
        const val DEFAULT_SHORT_MANUAL_CROSSFADE_MS = 400L
        const val DEFAULT_PLAY_PAUSE_STOP_FADE_MS = 400L
        const val DEFAULT_SEEK_FADE_MS = 100L
        const val DEFAULT_FADE_PLAY_PAUSE = true
        const val DEFAULT_FADE_SEEK = true
        const val DEFAULT_CROSSFADE_AUTO_ADVANCE = true
        const val DEFAULT_FADE_MANUAL_ADVANCE = true

        val DEFAULTS = FadeSettings()
    }
}
