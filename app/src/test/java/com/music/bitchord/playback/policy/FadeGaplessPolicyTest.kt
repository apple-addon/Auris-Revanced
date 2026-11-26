package com.music.bitchord.playback.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FadeGaplessPolicyTest {
    @Test
    fun exactTimingDefaults() {
        val fades = FadeDurations()
        val gapless = GaplessSettings()

        assertEquals(0L, PlaybackTimingDefaults.PRELOAD_GAPLESS_MS)
        assertEquals(2_500L, PlaybackTimingDefaults.PRELOAD_GAPLESS_NORMAL_MS)
        assertEquals(5_000L, PlaybackTimingDefaults.PRELOAD_GAPLESS_MORE_MS)
        assertEquals(5_000L, PlaybackTimingDefaults.SILENCE_BETWEEN_TRACKS_MAX_MS)
        assertEquals(0L, PlaybackTimingDefaults.SILENCE_BETWEEN_TRACKS_MS)
        assertEquals(5_000L, fades.crossfadeMs)
        assertEquals(400L, fades.manualTrackChangeMs)
        assertEquals(400L, fades.playPauseStopMs)
        assertEquals(100L, fades.seekMs)
        assertEquals(0L, gapless.preloadMs)
        assertEquals(0L, gapless.silenceBetweenTracksMs)
    }

    @Test
    fun gaplessPreloadPresets() {
        assertEquals(0L, GaplessPreloadPreset.NONE.preloadMs)
        assertEquals(2_500L, GaplessPreloadPreset.NORMAL.preloadMs)
        assertEquals(5_000L, GaplessPreloadPreset.MORE.preloadMs)
    }

    @Test
    fun eachTransportReasonUsesItsOwnDuration() {
        assertEquals(400L, FadePolicy.durationMs(FadeReason.MANUAL_TRACK_CHANGE))
        assertEquals(400L, FadePolicy.durationMs(FadeReason.PLAY_PAUSE_STOP))
        assertEquals(100L, FadePolicy.durationMs(FadeReason.SEEK))
    }

    @Test
    fun fadeInIsLinearAndClampsOutsideItsWindow() {
        val before = FadePolicy.sample(FadeReason.SEEK, FadeDirection.IN, -1)
        val start = FadePolicy.sample(FadeReason.SEEK, FadeDirection.IN, 0)
        val middle = FadePolicy.sample(FadeReason.SEEK, FadeDirection.IN, 50)
        val end = FadePolicy.sample(FadeReason.SEEK, FadeDirection.IN, 100)
        val after = FadePolicy.sample(FadeReason.SEEK, FadeDirection.IN, 500)

        assertEquals(FadePhase.NOT_STARTED, before.phase)
        assertEquals(0.0, before.gain, 0.0)
        assertEquals(101L, before.remainingMs)
        assertEquals(FadePhase.RUNNING, start.phase)
        assertEquals(0.0, start.progress, 0.0)
        assertEquals(0.5, middle.gain, 0.0)
        assertEquals(50L, middle.remainingMs)
        assertEquals(FadePhase.COMPLETE, end.phase)
        assertEquals(1.0, end.gain, 0.0)
        assertEquals(1.0, after.gain, 0.0)
        assertEquals(0L, after.remainingMs)
    }

    @Test
    fun fadeOutIsExactInverse() {
        val start = FadePolicy.sample(FadeReason.MANUAL_TRACK_CHANGE, FadeDirection.OUT, 0)
        val quarter = FadePolicy.sample(FadeReason.MANUAL_TRACK_CHANGE, FadeDirection.OUT, 100)
        val end = FadePolicy.sample(FadeReason.MANUAL_TRACK_CHANGE, FadeDirection.OUT, 400)

        assertEquals(1.0, start.gain, 0.0)
        assertEquals(0.75, quarter.gain, 0.0)
        assertEquals(0.0, end.gain, 0.0)
    }

    @Test
    fun zeroLengthFadeCompletesAtomically() {
        val fadeIn = FadePolicy.sample(0L, FadeDirection.IN, 0L)
        val fadeOut = FadePolicy.sample(0L, FadeDirection.OUT, 0L)

        assertEquals(FadePhase.COMPLETE, fadeIn.phase)
        assertEquals(1.0, fadeIn.gain, 0.0)
        assertEquals(FadePhase.COMPLETE, fadeOut.phase)
        assertEquals(0.0, fadeOut.gain, 0.0)
    }

    @Test
    fun crossfadeUsesDefaultFiveSecondsAndEqualPowerLaw() {
        val start = CrossfadePolicy.sample(0)
        val middle = CrossfadePolicy.sample(2_500)
        val end = CrossfadePolicy.sample(5_000)

        assertEquals(1.0, start.outgoingGain, 0.0)
        assertEquals(0.0, start.incomingGain, 0.0)
        assertEquals(sqrt(0.5), middle.outgoingGain, 1e-12)
        assertEquals(sqrt(0.5), middle.incomingGain, 1e-12)
        assertEquals(1.0, middle.powerSum, 1e-12)
        assertEquals(2_500L, middle.remainingMs)
        assertEquals(0.0, end.outgoingGain, 0.0)
        assertEquals(1.0, end.incomingGain, 0.0)
        assertEquals(FadePhase.COMPLETE, end.phase)
    }

    @Test
    fun zeroLengthCrossfadeSwitchesWithoutGhostGains() {
        val sample = CrossfadePolicy.sample(0L, 0L)

        assertEquals(0.0, sample.outgoingGain, 0.0)
        assertEquals(1.0, sample.incomingGain, 0.0)
        assertEquals(1.0, sample.powerSum, 0.0)
    }

    @Test
    fun morePresetStartsExactlyFiveSecondsBeforeKnownEnd() {
        val before = GaplessPolicy.preloadState(
            positionMs = 94_999,
            durationMs = 100_000,
            preloadMs = GaplessPreloadPreset.MORE.preloadMs,
        )
        val boundary = GaplessPolicy.preloadState(
            positionMs = 95_000,
            durationMs = 100_000,
            preloadMs = GaplessPreloadPreset.MORE.preloadMs,
        )
        val inside = GaplessPolicy.preloadState(
            positionMs = 97_000,
            durationMs = 100_000,
            preloadMs = GaplessPreloadPreset.MORE.preloadMs,
        )

        assertFalse(before.shouldPreload)
        assertEquals(5_001L, before.remainingMs!!)
        assertEquals(1L, before.untilPreloadMs!!)
        assertEquals(95_000L, before.preloadStartsAtMs!!)
        assertTrue(boundary.shouldPreload)
        assertEquals(5_000L, boundary.remainingMs!!)
        assertTrue(inside.shouldPreload)
        assertEquals(3_000L, inside.remainingMs!!)
    }

    @Test
    fun normalPresetStartsExactly2500MsBeforeKnownEnd() {
        val before = GaplessPolicy.preloadState(97_499, 100_000, GaplessPreloadPreset.NORMAL.preloadMs)
        val boundary = GaplessPolicy.preloadState(97_500, 100_000, GaplessPreloadPreset.NORMAL.preloadMs)

        assertFalse(before.shouldPreload)
        assertEquals(1L, before.untilPreloadMs!!)
        assertTrue(boundary.shouldPreload)
        assertEquals(2_500L, boundary.remainingMs!!)
    }

    @Test
    fun nonePresetNeverSchedulesPreload() {
        for (position in listOf(0L, 95_000L, 100_000L, 101_000L)) {
            val state = GaplessPolicy.preloadState(position, 100_000)

            assertFalse(state.shouldPreload)
            assertNull(state.preloadStartsAtMs)
            assertNull(state.untilPreloadMs)
        }
    }

    @Test
    fun shortTrackPreloadsFromPositionZero() {
        val state = GaplessPolicy.preloadState(
            positionMs = 0,
            durationMs = 3_000,
            preloadMs = GaplessPreloadPreset.MORE.preloadMs,
        )

        assertTrue(state.shouldPreload)
        assertEquals(0L, state.preloadStartsAtMs!!)
        assertEquals(3_000L, state.remainingMs!!)
    }

    @Test
    fun unknownDurationNeverArmsTimedPreload() {
        for (duration in listOf(-1L, 0L)) {
            val state = GaplessPolicy.preloadState(positionMs = 100, durationMs = duration)
            assertFalse(state.shouldPreload)
            assertNull(state.remainingMs)
            assertNull(state.preloadStartsAtMs)
            assertNull(state.untilPreloadMs)
        }
    }

    @Test
    fun preloadStateAtAndAfterEndHasZeroRemaining() {
        val atEnd = GaplessPolicy.preloadState(100_000, 100_000, GaplessPreloadPreset.MORE.preloadMs)
        val afterEnd = GaplessPolicy.preloadState(101_000, 100_000, GaplessPreloadPreset.MORE.preloadMs)

        assertTrue(atEnd.shouldPreload)
        assertEquals(0L, atEnd.remainingMs!!)
        assertEquals(0L, afterEnd.remainingMs!!)
    }

    @Test
    fun matchingFormatsAreNativelyGapless() {
        val format = StreamFormat(44_100, 2)
        val compatibility = GaplessPolicy.compatibility(format, format)
        val boundary = GaplessPolicy.boundary(format, format)

        assertTrue(compatibility.canUseSingleGaplessStream)
        assertTrue(compatibility.warnings.isEmpty())
        assertEquals(BoundaryMode.GAPLESS, boundary.mode)
        assertEquals(0L, boundary.preloadMs)
        assertEquals(0L, boundary.silenceAfterCurrentMs)
    }

    @Test
    fun rateMismatchProducesWarningAndSafeCrossfadeFallback() {
        val decision = GaplessPolicy.boundary(
            StreamFormat(44_100, 2),
            StreamFormat(48_000, 2),
            gaplessEnabled = true,
            crossfadeEnabled = true,
        )

        assertEquals(BoundaryMode.CROSSFADE, decision.mode)
        assertTrue(decision.gaplessRateMismatchWarning)
        assertEquals(setOf(GaplessWarning.SAMPLE_RATE_MISMATCH), decision.warnings)
        assertEquals(5_000L, decision.overlapMs)
    }

    @Test
    fun rateMismatchWithoutCrossfadeUsesPlainBoundaryButKeepsWarning() {
        val decision = GaplessPolicy.boundary(StreamFormat(96_000, 2), StreamFormat(48_000, 2))

        assertEquals(BoundaryMode.PLAIN, decision.mode)
        assertTrue(decision.gaplessRateMismatchWarning)
        assertEquals(0L, decision.overlapMs)
    }

    @Test
    fun channelMismatchIsNotHiddenByMatchingRates() {
        val compatibility = GaplessPolicy.compatibility(StreamFormat(48_000, 1), StreamFormat(48_000, 2))

        assertFalse(compatibility.canUseSingleGaplessStream)
        assertEquals(setOf(GaplessWarning.CHANNEL_COUNT_MISMATCH), compatibility.warnings)
    }

    @Test
    fun unknownFormatDoesNotFabricateMismatchValues() {
        val compatibility = GaplessPolicy.compatibility(StreamFormat(0, 0), StreamFormat(48_000, 2))

        assertFalse(compatibility.canUseSingleGaplessStream)
        assertEquals(setOf(GaplessWarning.UNKNOWN_FORMAT), compatibility.warnings)
        assertFalse(compatibility.requiresResampling)
    }

    @Test
    fun multipleFormatChangesReturnAllWarnings() {
        val compatibility = GaplessPolicy.compatibility(StreamFormat(44_100, 2), StreamFormat(48_000, 6))

        assertEquals(
            setOf(GaplessWarning.SAMPLE_RATE_MISMATCH, GaplessWarning.CHANNEL_COUNT_MISMATCH),
            compatibility.warnings,
        )
        assertTrue(compatibility.requiresResampling)
    }

    @Test
    fun configuredSilenceIsInsertedOnlyAtBoundary() {
        val decision = GaplessPolicy.boundary(
            StreamFormat(48_000, 2),
            StreamFormat(48_000, 2),
            settings = GaplessSettings(silenceBetweenTracksMs = 250),
        )

        assertEquals(BoundaryMode.SILENCE, decision.mode)
        assertEquals(250L, decision.silenceAfterCurrentMs)
        assertEquals(0L, decision.overlapMs)
    }

    @Test
    fun explicitCrossfadeWinsOverCompatibleGapless() {
        val decision = GaplessPolicy.boundary(
            StreamFormat(48_000, 2),
            StreamFormat(48_000, 2),
            gaplessEnabled = true,
            crossfadeEnabled = true,
        )

        assertEquals(BoundaryMode.CROSSFADE, decision.mode)
        assertEquals(5_000L, decision.overlapMs)
    }

    @Test
    fun crossfadeAndSilenceNeverStack() {
        val decision = GaplessPolicy.boundary(
            StreamFormat(44_100, 2),
            StreamFormat(48_000, 2),
            crossfadeEnabled = true,
            settings = GaplessSettings(silenceBetweenTracksMs = 500),
            crossfadeMs = 1_200,
        )

        assertEquals(BoundaryMode.CROSSFADE, decision.mode)
        assertEquals(0L, decision.silenceAfterCurrentMs)
        assertEquals(1_200L, decision.overlapMs)
    }

    @Test
    fun disabledGaplessDoesNotEmitGaplessWarnings() {
        val decision = GaplessPolicy.boundary(
            StreamFormat(44_100, 2),
            StreamFormat(48_000, 2),
            gaplessEnabled = false,
        )

        assertEquals(BoundaryMode.PLAIN, decision.mode)
        assertTrue(decision.warnings.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun silenceAboveFiveSecondsIsRejected() {
        GaplessSettings(silenceBetweenTracksMs = 5_001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeFadeDurationIsRejected() {
        FadeDurations(seekMs = -1)
    }
}
