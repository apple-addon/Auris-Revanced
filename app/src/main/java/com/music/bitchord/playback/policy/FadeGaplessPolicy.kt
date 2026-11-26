package com.music.bitchord.playback.policy

import kotlin.math.cos
import kotlin.math.sin

/** Timing constants in milliseconds; deliberately independent of settings storage. */
object PlaybackTimingDefaults {
    const val PRELOAD_GAPLESS_MS = 0L
    const val PRELOAD_GAPLESS_NORMAL_MS = 2_500L
    const val PRELOAD_GAPLESS_MORE_MS = 5_000L
    const val SILENCE_BETWEEN_TRACKS_MAX_MS = 5_000L
    const val SILENCE_BETWEEN_TRACKS_MS = 0L
    const val CROSSFADE_MS = 5_000L
    const val MANUAL_TRACK_CHANGE_FADE_MS = 400L
    const val PLAY_PAUSE_STOP_FADE_MS = 400L
    const val SEEK_FADE_MS = 100L
}

enum class GaplessPreloadPreset(val preloadMs: Long) {
    NONE(PlaybackTimingDefaults.PRELOAD_GAPLESS_MS),
    NORMAL(PlaybackTimingDefaults.PRELOAD_GAPLESS_NORMAL_MS),
    MORE(PlaybackTimingDefaults.PRELOAD_GAPLESS_MORE_MS),
}

data class FadeDurations(
    val crossfadeMs: Long = PlaybackTimingDefaults.CROSSFADE_MS,
    val manualTrackChangeMs: Long = PlaybackTimingDefaults.MANUAL_TRACK_CHANGE_FADE_MS,
    val playPauseStopMs: Long = PlaybackTimingDefaults.PLAY_PAUSE_STOP_FADE_MS,
    val seekMs: Long = PlaybackTimingDefaults.SEEK_FADE_MS,
) {
    init {
        require(crossfadeMs >= 0 && manualTrackChangeMs >= 0 && playPauseStopMs >= 0 && seekMs >= 0) {
            "Fade durations cannot be negative"
        }
    }
}

data class GaplessSettings(
    val preloadMs: Long = PlaybackTimingDefaults.PRELOAD_GAPLESS_MS,
    val silenceBetweenTracksMs: Long = PlaybackTimingDefaults.SILENCE_BETWEEN_TRACKS_MS,
) {
    init {
        require(preloadMs >= 0) { "preloadMs cannot be negative" }
        require(silenceBetweenTracksMs in 0..PlaybackTimingDefaults.SILENCE_BETWEEN_TRACKS_MAX_MS) {
            "silenceBetweenTracksMs must be between 0 and 5000 ms"
        }
    }
}

enum class FadeReason {
    MANUAL_TRACK_CHANGE,
    PLAY_PAUSE_STOP,
    SEEK,
}

enum class FadeDirection {
    IN,
    OUT,
}

enum class FadePhase {
    NOT_STARTED,
    RUNNING,
    COMPLETE,
}

data class FadeSample(
    val progress: Double,
    val gain: Double,
    val phase: FadePhase,
    val remainingMs: Long,
)

/** Linear transport fades. Crossfades use the equal-power law in [CrossfadePolicy]. */
object FadePolicy {
    @JvmStatic
    @JvmOverloads
    fun durationMs(reason: FadeReason, durations: FadeDurations = FadeDurations()): Long = when (reason) {
        FadeReason.MANUAL_TRACK_CHANGE -> durations.manualTrackChangeMs
        FadeReason.PLAY_PAUSE_STOP -> durations.playPauseStopMs
        FadeReason.SEEK -> durations.seekMs
    }

    @JvmStatic
    @JvmOverloads
    fun sample(
        reason: FadeReason,
        direction: FadeDirection,
        elapsedMs: Long,
        durations: FadeDurations = FadeDurations(),
    ): FadeSample = sample(durationMs(reason, durations), direction, elapsedMs)

    @JvmStatic
    fun sample(durationMs: Long, direction: FadeDirection, elapsedMs: Long): FadeSample {
        require(durationMs >= 0) { "durationMs cannot be negative" }
        val phase = phaseAt(elapsedMs, durationMs)
        val progress = progressAt(elapsedMs, durationMs)
        val gain = when (direction) {
            FadeDirection.IN -> progress
            FadeDirection.OUT -> 1.0 - progress
        }
        return FadeSample(
            progress = progress,
            gain = gain,
            phase = phase,
            remainingMs = (durationMs - elapsedMs).coerceAtLeast(0L),
        )
    }

    internal fun progressAt(elapsedMs: Long, durationMs: Long): Double = when {
        durationMs == 0L || elapsedMs >= durationMs -> 1.0
        elapsedMs <= 0L -> 0.0
        else -> elapsedMs.toDouble() / durationMs.toDouble()
    }

    internal fun phaseAt(elapsedMs: Long, durationMs: Long): FadePhase = when {
        durationMs == 0L || elapsedMs >= durationMs -> FadePhase.COMPLETE
        elapsedMs < 0L -> FadePhase.NOT_STARTED
        else -> FadePhase.RUNNING
    }
}

data class CrossfadeSample(
    val progress: Double,
    val outgoingGain: Double,
    val incomingGain: Double,
    val phase: FadePhase,
    val remainingMs: Long,
) {
    /** Equal-power crossfades preserve power for uncorrelated material. */
    val powerSum: Double get() = outgoingGain * outgoingGain + incomingGain * incomingGain
}

object CrossfadePolicy {
    @JvmStatic
    @JvmOverloads
    fun sample(
        elapsedMs: Long,
        durationMs: Long = PlaybackTimingDefaults.CROSSFADE_MS,
    ): CrossfadeSample {
        require(durationMs >= 0) { "durationMs cannot be negative" }
        val progress = FadePolicy.progressAt(elapsedMs, durationMs)
        val (outgoing, incoming) = when (progress) {
            0.0 -> 1.0 to 0.0
            1.0 -> 0.0 to 1.0
            else -> {
                val angle = progress * Math.PI / 2.0
                cos(angle) to sin(angle)
            }
        }
        return CrossfadeSample(
            progress = progress,
            outgoingGain = outgoing,
            incomingGain = incoming,
            phase = FadePolicy.phaseAt(elapsedMs, durationMs),
            remainingMs = (durationMs - elapsedMs).coerceAtLeast(0L),
        )
    }
}

data class PreloadState(
    /** Null for an unknown or non-positive duration. */
    val remainingMs: Long?,
    /** Null for an unknown duration or the NONE preset. */
    val preloadStartsAtMs: Long?,
    /** Null for an unknown duration or the NONE preset. */
    val untilPreloadMs: Long?,
    val shouldPreload: Boolean,
)

/** Format and boundary decisions only. Actual queue preparation remains with the player. */
object GaplessPolicy {
    @JvmStatic
    @JvmOverloads
    fun preloadState(
        positionMs: Long,
        durationMs: Long,
        preloadMs: Long = PlaybackTimingDefaults.PRELOAD_GAPLESS_MS,
    ): PreloadState {
        require(positionMs >= 0) { "positionMs cannot be negative" }
        require(preloadMs >= 0) { "preloadMs cannot be negative" }
        if (durationMs <= 0L) {
            return PreloadState(null, null, null, shouldPreload = false)
        }
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        if (preloadMs == 0L) {
            return PreloadState(remaining, null, null, shouldPreload = false)
        }
        val startsAt = (durationMs - preloadMs).coerceAtLeast(0L)
        return PreloadState(
            remainingMs = remaining,
            preloadStartsAtMs = startsAt,
            untilPreloadMs = (startsAt - positionMs).coerceAtLeast(0L),
            shouldPreload = positionMs >= startsAt,
        )
    }

    @JvmStatic
    fun compatibility(current: StreamFormat, next: StreamFormat): GaplessCompatibility {
        val warnings = linkedSetOf<GaplessWarning>()
        if (!current.isKnown || !next.isKnown) warnings += GaplessWarning.UNKNOWN_FORMAT
        if (current.sampleRateHz > 0 && next.sampleRateHz > 0 && current.sampleRateHz != next.sampleRateHz) {
            warnings += GaplessWarning.SAMPLE_RATE_MISMATCH
        }
        if (current.channelCount > 0 && next.channelCount > 0 && current.channelCount != next.channelCount) {
            warnings += GaplessWarning.CHANNEL_COUNT_MISMATCH
        }
        return GaplessCompatibility(
            canUseSingleGaplessStream = warnings.isEmpty(),
            warnings = warnings,
        )
    }

    /**
     * A rate/channel mismatch never silently claims native gapless. It falls back to a
     * crossfade or plain boundary and retains a warning for integration telemetry/UI.
     */
    @JvmStatic
    @JvmOverloads
    fun boundary(
        current: StreamFormat,
        next: StreamFormat,
        gaplessEnabled: Boolean = true,
        crossfadeEnabled: Boolean = false,
        settings: GaplessSettings = GaplessSettings(),
        crossfadeMs: Long = PlaybackTimingDefaults.CROSSFADE_MS,
    ): BoundaryDecision {
        require(crossfadeMs >= 0) { "crossfadeMs cannot be negative" }
        val compatibility = compatibility(current, next)
        val warnings = if (gaplessEnabled) compatibility.warnings else emptySet()
        val canGapless = gaplessEnabled && compatibility.canUseSingleGaplessStream
        val mode = when {
            // An explicit blend wins over a compatible gapless handoff.
            crossfadeEnabled -> BoundaryMode.CROSSFADE
            canGapless && settings.silenceBetweenTracksMs > 0 -> BoundaryMode.SILENCE
            canGapless -> BoundaryMode.GAPLESS
            settings.silenceBetweenTracksMs > 0 -> BoundaryMode.SILENCE
            else -> BoundaryMode.PLAIN
        }
        return BoundaryDecision(
            mode = mode,
            warnings = warnings,
            silenceAfterCurrentMs = if (mode == BoundaryMode.SILENCE) settings.silenceBetweenTracksMs else 0L,
            overlapMs = if (mode == BoundaryMode.CROSSFADE) crossfadeMs else 0L,
            preloadMs = settings.preloadMs,
        )
    }
}

/** Zero or negative fields mean that the decoder has not reported that property yet. */
data class StreamFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    val isKnown: Boolean get() = sampleRateHz > 0 && channelCount > 0
}

enum class GaplessWarning {
    UNKNOWN_FORMAT,
    SAMPLE_RATE_MISMATCH,
    CHANNEL_COUNT_MISMATCH,
}

data class GaplessCompatibility(
    val canUseSingleGaplessStream: Boolean,
    val warnings: Set<GaplessWarning>,
) {
    val requiresResampling: Boolean get() = GaplessWarning.SAMPLE_RATE_MISMATCH in warnings
}

enum class BoundaryMode {
    GAPLESS,
    CROSSFADE,
    SILENCE,
    PLAIN,
}

data class BoundaryDecision(
    val mode: BoundaryMode,
    val warnings: Set<GaplessWarning>,
    val silenceAfterCurrentMs: Long,
    val overlapMs: Long,
    /** Pass to GaplessPolicy.preloadState with the current position and duration. */
    val preloadMs: Long,
) {
    val gaplessRateMismatchWarning: Boolean
        get() = GaplessWarning.SAMPLE_RATE_MISMATCH in warnings
}
