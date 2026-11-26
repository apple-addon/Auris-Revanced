package com.music.bitchord.playback.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SacdIsoParserTest {
    @Test
    fun parsesAreaAndTrackLists() {
        val disc = SacdIsoParser.parse(SacdTestImage.create().source())
        assertNull(disc.multiChannelArea)
        val area = requireNotNull(disc.stereoArea)
        assertEquals(SacdAreaKind.STEREO, area.kind)
        assertEquals(2, area.channelCount)
        assertEquals(SacdFrameFormat.DSD_3_IN_16, area.frameFormat)
        assertEquals(2, area.tracks.size)
        assertEquals(2, area.tracks[0].durationFrames)
        assertEquals(26_666L, area.tracks[0].durationUs)
        assertEquals(2 * SACD_SAMPLES_PER_FRAME, area.tracks[0].sampleCount)
        assertEquals(3, area.tracks[1].durationFrames)
        assertEquals(40_000L, area.tracks[1].durationUs)
        assertEquals(2L, area.tracks[1].startTimecodeFrame)
        assertEquals(area.tracks[1].sourceStartLsn, area.tracks[0].sourceEndLsn)
        assertEquals(area.trackEndLsn, area.tracks[1].sourceEndLsn)
    }

    @Test
    fun parsesNonIntegralSecondDurationFromTimecodeBytes() {
        val image = SacdTestImage.create(durations = intArrayOf(4_653, 75), withAudio = false)
        val track = SacdIsoParser.parse(image.source()).stereoArea!!.tracks[0]
        assertEquals(4_653, track.durationFrames)
        assertEquals(62_040_000L, track.durationUs)
        assertEquals(4_653 * SACD_SAMPLES_PER_FRAME, track.sampleCount)
    }

    @Test
    fun seekSnapsToExactAudioFrameAndUsesWholeTrackDuration() {
        val track = SacdIsoParser.parse(SacdTestImage.create().source()).stereoArea!!.tracks[0]
        val start = track.seek(-1)
        assertEquals(0, start.relativeFrame)
        assertEquals(0L, start.timeUs)
        assertFalse(start.endOfTrack)

        assertEquals(0, track.seek(13_333).relativeFrame)
        val second = track.seek(13_334)
        assertEquals(1, second.relativeFrame)
        assertEquals(1L, second.absoluteTimecodeFrame)
        assertEquals(13_333L, second.timeUs)
        assertFalse(second.endOfTrack)

        val end = track.seek(26_666)
        assertEquals(2, end.relativeFrame)
        assertEquals(26_666L, end.timeUs)
        assertTrue(end.endOfTrack)
        assertEquals(end, track.seek(Long.MAX_VALUE))
    }

    @Test
    fun parsesMultichannelMasterSlot() {
        val image = SacdTestImage.create(withAudio = false, durations = intArrayOf(1))
        val master = 510 * SACD_SECTOR_BYTES
        val area = 600 * SACD_SECTOR_BYTES
        image.bytes.putU32(master + 64, 0)
        image.bytes.putU16(master + 84, 0)
        image.bytes.putU32(master + 72, 600)
        image.bytes.putU16(master + 86, 3)
        image.bytes.putText(area, "MULCHTOC")
        image.bytes[area + 32] = 6

        val disc = SacdIsoParser.parse(image.source())
        assertNull(disc.stereoArea)
        assertEquals(SacdAreaKind.MULTI_CHANNEL, disc.multiChannelArea!!.kind)
        assertEquals(6, disc.multiChannelArea!!.channelCount)
    }

    @Test(expected = SacdFormatException::class)
    fun rejectsMissingMasterToc() {
        SacdIsoParser.parse(ByteArrayRandomAccessSource(ByteArray(1_024 * SACD_SECTOR_BYTES)))
    }

    @Test(expected = SacdFormatException::class)
    fun rejectsInvalidTrackTimecode() {
        val image = SacdTestImage.create()
        val durationFlags = 600 * SACD_SECTOR_BYTES + 2 * SACD_SECTOR_BYTES + 8 + 255 * 4 + 2
        image.bytes[durationFlags] = 75
        SacdIsoParser.parse(image.source())
    }

    @Test(expected = SacdFormatException::class)
    fun rejectsTrackRangeOutsideImage() {
        val image = SacdTestImage.create()
        val area = 600 * SACD_SECTOR_BYTES
        image.bytes.putU32(area + 76, 2_000)
        SacdIsoParser.parse(image.source())
    }
}
