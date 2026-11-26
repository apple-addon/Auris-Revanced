package com.music.bitchord.playback.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainTagParserTest {
    @Test
    fun parsesCommonReplayGainFamily() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "replaygain_track_gain" to " -7.25 dB ",
                "REPLAYGAIN_TRACK_PEAK" to "1.05",
                "ReplayGain-Album-Gain" to "+4.5",
                "replaygain album peak" to "0.98",
            ),
        )

        assertEquals(-7.25, tags.trackGainDb!!, 0.0)
        assertEquals(1.05, tags.trackPeak!!, 0.0)
        assertEquals(4.5, tags.albumGainDb!!, 0.0)
        assertEquals(0.98, tags.albumPeak!!, 0.0)
    }

    @Test
    fun parsesOpusR128GainAsSignedQ8() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "R128_TRACK_GAIN" to "-768",
                "R128_ALBUM_GAIN" to "256",
            ),
        )

        assertEquals(-3.0, tags.trackGainDb!!, 0.0)
        assertEquals(1.0, tags.albumGainDb!!, 0.0)
    }

    @Test
    fun acceptsAlreadyConvertedR128GainAndExplicitUnits() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "R128_TRACK_GAIN" to "-3.25",
                "R128_ALBUM_GAIN" to "+200 dB",
                "R128_TRACK_PEAK" to "-6 dB",
            ),
        )

        assertEquals(-3.25, tags.trackGainDb!!, 0.0)
        // Explicit dB bypasses Q8 even when the number happens to be an integer.
        assertEquals(200.0, tags.albumGainDb!!, 0.0)
        assertEquals(0.5011872336, tags.trackPeak!!, 1e-9)
    }

    @Test
    fun parsesId3AndMp4PrefixedNames() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "TXXX:REPLAYGAIN_TRACK_GAIN" to "-2 dB",
                "----:com.apple.iTunes:replaygain_album_gain" to "-4 dB",
                "----:com.apple.iTunes:replaygain_track_peak" to "0.75",
            ),
        )

        assertEquals(-2.0, tags.trackGainDb!!, 0.0)
        assertEquals(-4.0, tags.albumGainDb!!, 0.0)
        assertEquals(0.75, tags.trackPeak!!, 0.0)
    }

    @Test
    fun replayGainWinsAndR128FillsMissingFields() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "R128_TRACK_GAIN" to "-256",
                "REPLAYGAIN_TRACK_GAIN" to "-8 dB",
                "R128_ALBUM_GAIN" to "-512",
                "R128_TRACK_PEAK" to "0.8",
            ),
        )

        assertEquals(-8.0, tags.trackGainDb!!, 0.0)
        assertEquals(-2.0, tags.albumGainDb!!, 0.0)
        assertEquals(0.8, tags.trackPeak!!, 0.0)
    }

    @Test
    fun invalidDuplicateDoesNotHideLaterValidValue() {
        val tags = ReplayGainTagParser.parse(
            listOf(
                "REPLAYGAIN_TRACK_GAIN" to "not-a-number",
                "replaygain_track_gain" to "-5 dB",
                "R128_ALBUM_GAIN" to "999999999999999999999",
                "r128_album_gain" to "-256",
            ),
        )

        assertEquals(-5.0, tags.trackGainDb!!, 0.0)
        assertEquals(-1.0, tags.albumGainDb!!, 0.0)
    }

    @Test
    fun invalidReplayGainFallsBackToValidR128() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "NaN",
                "R128_TRACK_GAIN" to "-1280",
            ),
        )

        assertEquals(-5.0, tags.trackGainDb!!, 0.0)
    }

    @Test
    fun rejectsMalformedNonFiniteAndUnsafeValues() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "1e3",
                "REPLAYGAIN_ALBUM_GAIN" to "Infinity",
                "REPLAYGAIN_TRACK_PEAK" to "-0.5",
                "REPLAYGAIN_ALBUM_PEAK" to "NaN",
                "R128_TRACK_GAIN" to "32768",
                "R128_ALBUM_GAIN" to "-32769",
            ),
        )

        assertEquals(ReplayGainTags(), tags)
    }

    @Test
    fun nulPaddingAndBareNumbersAreAccepted() {
        val tags = ReplayGainTagParser.parse(
            mapOf(
                "REPLAYGAIN_TRACK_GAIN\u0000" to "-1.5\u0000",
                "R128_ALBUM_GAIN" to "+0",
                "REPLAYGAIN_TRACK_PEAK" to "0",
            ),
        )

        assertEquals(-1.5, tags.trackGainDb!!, 0.0)
        assertEquals(0.0, tags.albumGainDb!!, 0.0)
        assertEquals(0.0, tags.trackPeak!!, 0.0)
    }
}

class ReplayGainPolicyTest {
    private val tags = ReplayGainTags(
        trackGainDb = -8.0,
        trackPeak = 0.8,
        albumGainDb = -4.0,
        albumPeak = 0.6,
    )

    @Test
    fun trackAndAlbumSelectTheirOwnValues() {
        val track = ReplayGainPolicy.select(tags, ReplayGainSource.TRACK)
        val album = ReplayGainPolicy.select(tags, ReplayGainSource.ALBUM)

        assertEquals(ReplayGainTagSource.TRACK, track.tagSource)
        assertEquals(-8.0, track.gainDb!!, 0.0)
        assertEquals(0.8, track.peak!!, 0.0)
        assertEquals(ReplayGainTagSource.ALBUM, album.tagSource)
        assertEquals(-4.0, album.gainDb!!, 0.0)
        assertEquals(0.6, album.peak!!, 0.0)
    }

    @Test
    fun smartUsesAlbumOnlyForContinuousAlbumPlayback() {
        val album = ReplayGainPolicy.select(tags, ReplayGainSource.SMART, albumPlayback = true)
        val shuffled = ReplayGainPolicy.select(tags, ReplayGainSource.SMART, albumPlayback = false)

        assertEquals(ReplayGainTagSource.ALBUM, album.tagSource)
        assertEquals(ReplayGainTagSource.TRACK, shuffled.tagSource)
    }

    @Test
    fun missingPreferredGainFallsBackAndIsReported() {
        val trackOnly = ReplayGainTags(trackGainDb = -7.0, trackPeak = 0.9)
        val selection = ReplayGainPolicy.select(trackOnly, ReplayGainSource.ALBUM)

        assertEquals(ReplayGainTagSource.TRACK, selection.tagSource)
        assertTrue(selection.usedFallback)
        assertEquals(-7.0, selection.gainDb!!, 0.0)
        assertEquals(0.9, selection.peak!!, 0.0)
    }

    @Test
    fun missingGainAndPeakReturnNoneWithoutInventingTags() {
        val selection = ReplayGainPolicy.select(ReplayGainTags(), ReplayGainSource.SMART, true)

        assertEquals(ReplayGainTagSource.NONE, selection.tagSource)
        assertNull(selection.gainDb)
        assertNull(selection.peak)
        assertFalse(selection.usedFallback)
    }

    @Test
    fun sampleGainUsesTwentyLogScaling() {
        val sample = ReplayGainPolicy.sampleGain(
            ReplayGainTags(trackGainDb = -6.0),
            ReplayGainSettings(preventClipping = false),
        )

        assertEquals(0.5011872336, sample.linearGain, 1e-9)
        assertEquals(-6.0, sample.appliedGainDb, 1e-9)
        assertEquals(sample.linearGain.toFloat(), sample.sampleGain)
    }

    @Test
    fun taggedPreampAddsToSelectedTag() {
        val sample = ReplayGainPolicy.sampleGain(
            tags,
            ReplayGainSettings(preampDb = 2.0, noTagPreampDb = -20.0),
        )

        assertEquals(-8.0, sample.tagGainDb!!, 0.0)
        assertEquals(2.0, sample.preampDb, 0.0)
        assertEquals(ReplayGainPolicy.dbToLinear(-6.0), sample.unclippedLinearGain, 1e-12)
    }

    @Test
    fun noTagPreampDoesNotLeakIntoTaggedTracks() {
        val tagged = ReplayGainPolicy.sampleGain(tags, ReplayGainSettings(noTagPreampDb = 12.0))
        val untagged = ReplayGainPolicy.sampleGain(
            ReplayGainTags(),
            ReplayGainSettings(preampDb = 20.0, noTagPreampDb = -3.0),
        )

        assertEquals(0.0, tagged.preampDb, 0.0)
        assertEquals(0.0, tagged.appliedGainDb + 8.0, 1e-9)
        assertEquals(-3.0, untagged.appliedGainDb, 1e-9)
        assertEquals(ReplayGainTagSource.NONE, untagged.tagSource)
    }

    @Test
    fun peakPreventionCapsBoostAtFullScale() {
        val sample = ReplayGainPolicy.sampleGain(
            ReplayGainTags(trackGainDb = 6.0, trackPeak = 0.8),
            ReplayGainSettings(),
        )

        assertEquals(1.25, sample.linearGain, 1e-12)
        assertEquals(1.0, sample.linearGain * 0.8, 1e-12)
        assertTrue(sample.clippingPrevented)
        assertTrue(sample.unclippedLinearGain > sample.linearGain)
    }

    @Test
    fun customCeilingIsHonored() {
        val sample = ReplayGainPolicy.sampleGain(
            ReplayGainTags(trackGainDb = 6.0, trackPeak = 0.8),
            ReplayGainSettings(ceiling = 0.9),
        )

        assertEquals(0.9, sample.linearGain * 0.8, 1e-12)
        assertEquals(1.125, sample.linearGain, 1e-12)
    }

    @Test
    fun disablingPeakPreventionLeavesRequestedBoostUntouched() {
        val sample = ReplayGainPolicy.sampleGain(
            ReplayGainTags(trackGainDb = 6.0, trackPeak = 0.8),
            ReplayGainSettings(preventClipping = false),
        )

        assertEquals(ReplayGainPolicy.dbToLinear(6.0), sample.linearGain, 1e-12)
        assertFalse(sample.clippingPrevented)
    }

    @Test
    fun attenuationNeedingNoLimitIsNotChangedByPeak() {
        val sample = ReplayGainPolicy.sampleGain(tags)

        assertEquals(ReplayGainPolicy.dbToLinear(-8.0), sample.linearGain, 1e-12)
        assertFalse(sample.clippingPrevented)
    }

    @Test
    fun peakOnlyTagCanProtectNoTagPreamp() {
        val sample = ReplayGainPolicy.sampleGain(
            ReplayGainTags(trackPeak = 0.5),
            ReplayGainSettings(noTagPreampDb = 12.0),
        )

        assertEquals(2.0, sample.linearGain, 1e-12)
        assertTrue(sample.clippingPrevented)
    }

    @Test
    fun zeroPeakDoesNotDivideByZero() {
        val sample = ReplayGainPolicy.sampleGain(
            ReplayGainTags(trackGainDb = 3.0, trackPeak = 0.0),
        )

        assertEquals(ReplayGainPolicy.dbToLinear(3.0), sample.linearGain, 1e-12)
        assertFalse(sample.clippingPrevented)
    }

    @Test
    fun disabledReplayGainIsExactlyUnity() {
        val sample = ReplayGainPolicy.sampleGain(
            tags,
            ReplayGainSettings(enabled = false, preampDb = 20.0, noTagPreampDb = 20.0),
        )

        assertEquals(1.0, sample.linearGain, 0.0)
        assertEquals(0.0, sample.appliedGainDb, 0.0)
        assertNull(sample.tagGainDb)
        assertFalse(sample.clippingPrevented)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPreampIsRejectedBeforeGainStage() {
        ReplayGainSettings(preampDb = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCeilingIsRejectedBeforeGainStage() {
        ReplayGainSettings(ceiling = 0.0)
    }
}
