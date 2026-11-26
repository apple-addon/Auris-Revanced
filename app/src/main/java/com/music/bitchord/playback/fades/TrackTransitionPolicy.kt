package com.music.bitchord.playback.fades

/** The queue operation for which a transition is being prepared. */
enum class TrackChangeReason {
    AUTOMATIC,
    MANUAL,
}

/**
 * Timing instructions only; decoding, queue mutation, and rendering remain with the caller.
 * [silenceMs] precedes the incoming item, while [crossfadeMs] describes audible overlap.
 */
data class TrackTransitionPlan(
    val preloadMs: Long,
    val silenceMs: Long,
    val crossfadeMs: Long,
)

/** Pure gapless/crossfade selection shared by service and settings previews. */
object TrackTransitionPolicy {
    fun plan(
        settings: FadeSettings,
        reason: TrackChangeReason = TrackChangeReason.AUTOMATIC,
        cue: Boolean = false,
    ): TrackTransitionPlan {
        val normalized = settings.normalized()
        return when (reason) {
            TrackChangeReason.AUTOMATIC -> TrackTransitionPlan(
                preloadMs = normalized.preloadGaplessTracksMs,
                // CUE entries are slices of one continuous file and already contain their own gaps.
                silenceMs = if (cue) 0L else normalized.silenceBetweenTracksMs,
                // crossfade_auto_advance gates the stored length, it does not rewrite it.
                crossfadeMs = if (normalized.crossfadeAutoAdvance) normalized.crossfadeLengthMs else 0L,
            )

            TrackChangeReason.MANUAL -> TrackTransitionPlan(
                preloadMs = 0L,
                silenceMs = 0L,
                // fade_manual_advance gates the stored short length the same way.
                crossfadeMs = if (normalized.fadeManualAdvance) normalized.shortManualCrossfadeMs else 0L,
            )
        }
    }
}
