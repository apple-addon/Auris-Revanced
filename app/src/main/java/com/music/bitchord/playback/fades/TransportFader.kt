package com.music.bitchord.playback.fades

/** Observable transport phase. The caller owns the actual player. */
enum class TransportFadeState {
    STOPPED,
    PLAYING,
    PAUSED,
    FADING_IN,
    FADING_OUT,
    SEEK_FADE_OUT,
    SEEK_FADE_IN,
}

/** A one-shot command emitted by an event or clock advance. */
enum class FadeAction {
    NONE,
    START_PLAYBACK,
    PAUSE_PLAYBACK,
    STOP_PLAYBACK,
    SEEK,
}

data class FadeUpdate(
    val state: TransportFadeState,
    val gain: Float,
    val action: FadeAction = FadeAction.NONE,
)

/**
 * Pure transport state machine for play/pause/stop and seek fades.
 *
 * All timing comes from a caller-supplied monotonic millisecond clock. A new command starts from
 * the currently published gain, so interrupting a ramp never creates a gain discontinuity. Actions
 * are returned exactly once; subsequent [snapshot] or [advanceTo] calls return [FadeAction.NONE].
 * Feed [FadeUpdate.gain] to [ClickFreeGain] to smooth control-interval steps into audio samples.
 */
class TransportFader(settings: FadeSettings = FadeSettings.DEFAULTS) {
    private var transportDurationMs = settings.playPauseStopFadeMs.coerceAtLeast(0L)
    private var seekDurationMs = settings.seekFadeMs.coerceAtLeast(0L)
    private var transportFadeEnabled = settings.fadePlayPause
    private var seekFadeEnabled = settings.fadeSeek

    var state: TransportFadeState = TransportFadeState.STOPPED
        private set

    var gain: Float = 0f
        private set

    private var startedAtMs = 0L
    private var startGain = 0f
    private var activeDurationMs = 0L
    private var completion: Completion? = null

    private enum class Completion {
        PAUSE,
        STOP,
        SEEK,
    }

    /** Changes the next ramp's duration without moving a ramp already in flight. */
    fun updateSettings(settings: FadeSettings) {
        transportDurationMs = settings.playPauseStopFadeMs.coerceAtLeast(0L)
        seekDurationMs = settings.seekFadeMs.coerceAtLeast(0L)
        transportFadeEnabled = settings.fadePlayPause
        seekFadeEnabled = settings.fadeSeek
    }

    /** The fade lengths with their switches applied; zero means immediate. */
    private val transportDuration: Long get() = if (transportFadeEnabled) transportDurationMs else 0L
    private val seekDuration: Long get() = if (seekFadeEnabled) seekDurationMs else 0L

    fun snapshot(): FadeUpdate = FadeUpdate(state, gain)

    fun play(nowMs: Long): FadeUpdate {
        if (state == TransportFadeState.PLAYING ||
            state == TransportFadeState.FADING_IN ||
            state == TransportFadeState.SEEK_FADE_IN
        ) {
            return snapshot()
        }
        completion = null
        val duration = proportionalDuration(transportDuration, 1f - gain)
        if (duration == 0L) {
            gain = 1f
            state = TransportFadeState.PLAYING
        } else {
            begin(TransportFadeState.FADING_IN, nowMs, duration, completion = null)
        }
        return FadeUpdate(state, gain, FadeAction.START_PLAYBACK)
    }

    fun pause(nowMs: Long): FadeUpdate {
        if (state == TransportFadeState.STOPPED || state == TransportFadeState.PAUSED) return snapshot()
        if (state == TransportFadeState.FADING_OUT && completion == Completion.PAUSE) return snapshot()
        if (gain == 0f || transportDuration == 0L) {
            gain = 0f
            state = TransportFadeState.PAUSED
            completion = null
            return FadeUpdate(state, gain, FadeAction.PAUSE_PLAYBACK)
        }
        begin(
            TransportFadeState.FADING_OUT,
            nowMs,
            proportionalDuration(transportDuration, gain),
            Completion.PAUSE,
        )
        return snapshot()
    }

    fun stop(nowMs: Long): FadeUpdate {
        if (state == TransportFadeState.STOPPED) return snapshot()
        if (state == TransportFadeState.FADING_OUT && completion == Completion.STOP) return snapshot()
        if (gain == 0f || transportDuration == 0L) {
            gain = 0f
            state = TransportFadeState.STOPPED
            completion = null
            return FadeUpdate(state, gain, FadeAction.STOP_PLAYBACK)
        }
        begin(
            TransportFadeState.FADING_OUT,
            nowMs,
            proportionalDuration(transportDuration, gain),
            Completion.STOP,
        )
        return snapshot()
    }

    /**
     * Playing seeks fade out, emit [FadeAction.SEEK], then fade back in. Paused/stopped seeks are
     * immediate and remain paused/stopped. The caller performs its seek when the action arrives.
     */
    fun seek(nowMs: Long): FadeUpdate {
        if (state == TransportFadeState.STOPPED || state == TransportFadeState.PAUSED) {
            return FadeUpdate(state, gain, FadeAction.SEEK)
        }
        if (state == TransportFadeState.SEEK_FADE_OUT) return snapshot()
        if (seekDuration == 0L) {
            completion = null
            gain = 1f
            state = TransportFadeState.PLAYING
            return FadeUpdate(state, gain, FadeAction.SEEK)
        }
        if (gain == 0f) {
            beginSeekFadeIn(nowMs)
            return FadeUpdate(state, gain, FadeAction.SEEK)
        }
        begin(
            TransportFadeState.SEEK_FADE_OUT,
            nowMs,
            proportionalDuration(seekDuration, gain),
            Completion.SEEK,
        )
        return snapshot()
    }

    fun advanceTo(nowMs: Long): FadeUpdate = when (state) {
        TransportFadeState.FADING_IN -> {
            if (sampleRamp(nowMs, targetGain = 1f)) {
                gain = 1f
                state = TransportFadeState.PLAYING
                completion = null
            }
            snapshot()
        }

        TransportFadeState.FADING_OUT,
        TransportFadeState.SEEK_FADE_OUT,
        -> {
            if (sampleRamp(nowMs, targetGain = 0f)) completeFadeOut(nowMs) else snapshot()
        }

        TransportFadeState.SEEK_FADE_IN -> {
            if (sampleRamp(nowMs, targetGain = 1f)) {
                gain = 1f
                state = TransportFadeState.PLAYING
            }
            snapshot()
        }

        TransportFadeState.STOPPED,
        TransportFadeState.PLAYING,
        TransportFadeState.PAUSED,
        -> snapshot()
    }

    fun reset() {
        state = TransportFadeState.STOPPED
        gain = 0f
        completion = null
        activeDurationMs = 0L
    }

    private fun begin(
        newState: TransportFadeState,
        nowMs: Long,
        durationMs: Long,
        completion: Completion?,
    ) {
        state = newState
        startedAtMs = nowMs
        startGain = gain
        activeDurationMs = durationMs
        this.completion = completion
    }

    /** Returns true only when the active ramp reaches its endpoint. */
    private fun sampleRamp(nowMs: Long, targetGain: Float): Boolean {
        val elapsed = if (nowMs <= startedAtMs) 0L else nowMs - startedAtMs
        val progress = if (activeDurationMs <= 0L) {
            1f
        } else {
            (elapsed.toDouble() / activeDurationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
        gain = FadeGain.at(progress, startGain, targetGain)
        if (progress == 1f) gain = targetGain
        return progress == 1f
    }

    private fun completeFadeOut(nowMs: Long): FadeUpdate {
        gain = 0f
        return when (completion) {
            Completion.PAUSE -> {
                state = TransportFadeState.PAUSED
                completion = null
                FadeUpdate(state, gain, FadeAction.PAUSE_PLAYBACK)
            }

            Completion.STOP -> {
                state = TransportFadeState.STOPPED
                completion = null
                FadeUpdate(state, gain, FadeAction.STOP_PLAYBACK)
            }

            Completion.SEEK -> {
                completion = null
                beginSeekFadeIn(nowMs)
                FadeUpdate(state, gain, FadeAction.SEEK)
            }

            null -> {
                state = TransportFadeState.PAUSED
                snapshot()
            }
        }
    }

    private fun beginSeekFadeIn(nowMs: Long) {
        gain = 0f
        if (seekDuration == 0L) {
            gain = 1f
            state = TransportFadeState.PLAYING
        } else {
            begin(TransportFadeState.SEEK_FADE_IN, nowMs, seekDuration, completion = null)
        }
    }

    private fun proportionalDuration(baseMs: Long, gainDistance: Float): Long {
        if (baseMs <= 0L || gainDistance <= 0f) return 0L
        return (baseMs.toDouble() * gainDistance.coerceIn(0f, 1f))
            .toLong()
            .coerceAtLeast(1L)
    }
}
