package com.music.bitchord.playback.decoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SacdTrackReaderTest {
    @Test
    fun indexesEveryFrameAndSeeksToItsActualSector() {
        val image = SacdTestImage.create()
        val source = image.source()
        val area = SacdIsoParser.parse(source).stereoArea!!
        val track = area.tracks[0]
        val index = SacdTrackIndex.build(source, area, track)
        assertTrue(index.isComplete)
        assertEquals(2, index.indexedFrameCount)

        val seek = index.seek(13_334)
        assertEquals(1, seek.point.relativeFrame)
        assertEquals(track.sourceStartLsn + 5, seek.sourceLsn)
        assertEquals(13_333L, seek.point.timeUs)

        val reader = SacdTrackReader(source, area, track)
        reader.seek(seek)
        val frame = requireNotNull(reader.readFrame())
        assertEquals(1, frame.sourceTimecodeFrame)
        assertEquals(SACD_SAMPLES_PER_FRAME, frame.firstSample)
        assertEquals(13_333L, frame.timeUs)
        assertArrayEquals(image.sourceFrames[1L], frame.bytes)
        assertNull(reader.readFrame())
        reader.close()
    }

    @Test
    fun sequentialReaderReturnsUnchangedInterleavedDsd() {
        val image = SacdTestImage.create()
        val source = image.source()
        val area = SacdIsoParser.parse(source).stereoArea!!
        val reader = SacdTrackReader(source, area, area.tracks[0])
        val first = requireNotNull(reader.readFrame())
        val second = requireNotNull(reader.readFrame())
        assertArrayEquals(image.sourceFrames[0L], first.bytes)
        assertArrayEquals(image.sourceFrames[1L], second.bytes)
        assertEquals(SACD_BYTES_PER_CHANNEL_FRAME * 2, first.bytes.size)
        assertEquals(0L, first.timeUs)
        assertEquals(13_333L, second.timeUs)
        assertNull(reader.readFrame())
        reader.close()
    }

    @Test
    fun dstFramesAreDecodedOnlyToRawDsd() {
        val image = SacdTestImage.create(format = SacdFrameFormat.DST, durations = intArrayOf(2))
        val source = image.source()
        val area = SacdIsoParser.parse(source).stereoArea!!
        assertEquals(SacdFrameFormat.DST, area.frameFormat)
        val decodedInputs = ArrayList<ByteArray>()
        val reader = SacdTrackReader(source, area, area.tracks[0]) { channels ->
            assertEquals(2, channels)
            object : DstFrameDecoder {
                override fun decode(frame: ByteArray): ByteArray {
                    decodedInputs += frame.copyOf()
                    val timecode = if (decodedInputs.size == 1) 0L else 1L
                    return SacdTestImage.expectedRaw(timecode, frame)
                }

                override fun close() = Unit
            }
        }
        val first = requireNotNull(reader.readFrame())
        val second = requireNotNull(reader.readFrame())
        assertArrayEquals(SacdTestImage.expectedRaw(0, image.sourceFrames.getValue(0)), first.bytes)
        assertArrayEquals(SacdTestImage.expectedRaw(1, image.sourceFrames.getValue(1)), second.bytes)
        assertEquals(2, decodedInputs.size)
        assertArrayEquals(image.sourceFrames[0L], decodedInputs[0])
        assertArrayEquals(image.sourceFrames[1L], decodedInputs[1])
        assertNull(reader.readFrame())
        reader.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun dstReaderDoesNotSilentlySubstitutePcmOrAnotherDecoder() {
        val image = SacdTestImage.create(format = SacdFrameFormat.DST, durations = intArrayOf(1))
        val source = image.source()
        val area = SacdIsoParser.parse(source).stereoArea!!
        SacdTrackReader(source, area, area.tracks[0])
    }

    @Test(expected = SacdFormatException::class)
    fun readerFailsInsteadOfPretendingTruncatedTrackEndedCleanly() {
        val image = SacdTestImage.create(durations = intArrayOf(1))
        val source = image.source()
        val area = SacdIsoParser.parse(source).stereoArea!!
        val track = area.tracks[0]
        // Remove the frame-start bit from the only frame's first packet.
        image.bytes[track.sourceStartLsn.toInt() * SACD_SECTOR_BYTES + 1] = 0x10
        SacdTrackReader(source, area, track).readFrame()
    }
}
