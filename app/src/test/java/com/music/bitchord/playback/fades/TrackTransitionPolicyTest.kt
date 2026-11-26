package com.music.bitchord.playback.fades

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTransitionPolicyTest {
    private val settings = FadeSettings(
        preloadGaplessTracksMs = 2_500L,
        silenceBetweenTracksMs = 750L,
        crossfadeLengthMs = 5_000L,
        shortManualCrossfadeMs = 400L,
    )

    @Test
    fun automaticTransitionUsesGaplessPreloadAndLongCrossfade() {
        assertEquals(
            TrackTransitionPlan(preloadMs = 2_500L, silenceMs = 750L, crossfadeMs = 5_000L),
            TrackTransitionPolicy.plan(settings),
        )
    }

    @Test
    fun cueTransitionNeverGetsInsertedSilence() {
        val plan = TrackTransitionPolicy.plan(settings, cue = true)
        assertEquals(0L, plan.silenceMs)
        assertEquals(2_500L, plan.preloadMs)
        assertEquals(5_000L, plan.crossfadeMs)
    }

    @Test
    fun manualTransitionIsImmediateAndUsesShortCrossfade() {
        assertEquals(
            TrackTransitionPlan(preloadMs = 0L, silenceMs = 0L, crossfadeMs = 400L),
            TrackTransitionPolicy.plan(settings, TrackChangeReason.MANUAL),
        )
    }

    @Test
    fun advanceSwitchesGateCrossfadesWithoutRewritingLengths() {
        val off = settings.copy(crossfadeAutoAdvance = false, fadeManualAdvance = false)
        assertEquals(
            TrackTransitionPlan(preloadMs = 2_500L, silenceMs = 750L, crossfadeMs = 0L),
            TrackTransitionPolicy.plan(off),
        )
        assertEquals(
            TrackTransitionPlan(preloadMs = 0L, silenceMs = 0L, crossfadeMs = 0L),
            TrackTransitionPolicy.plan(off, TrackChangeReason.MANUAL),
        )
        // The stored lengths survive the switches being off.
        assertEquals(5_000L, off.crossfadeLengthMs)
        assertEquals(400L, off.shortManualCrossfadeMs)

        val autoOnly = settings.copy(crossfadeAutoAdvance = true, fadeManualAdvance = false)
        assertEquals(5_000L, TrackTransitionPolicy.plan(autoOnly).crossfadeMs)
        assertEquals(0L, TrackTransitionPolicy.plan(autoOnly, TrackChangeReason.MANUAL).crossfadeMs)
    }
}
