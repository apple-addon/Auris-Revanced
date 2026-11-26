package com.music.bitchord.playback.dsd.raw

import com.music.bitchord.playback.dsd.NativeDsdPacker
import com.music.bitchord.playback.dsd.NativeDsdPacking
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SacdIsoReaderTest {
    @Test
    fun masterAndAreaTocsProducePerTrackRawMetadata() {
        val fixture = makeSyntheticSacd()
        val disc = SacdIsoParser.parse(ByteArrayRawDsdInput(fixture.image))
        assertEquals(SacdSectorLayout(2048, 0), disc.layout)
        assertEquals(1, disc.areas.size)
        val area = disc.area(SacdAreaKind.STEREO)
        assertEquals(SacdFrameFormat.DSD_3_IN_14, area.frameFormat)
        assertEquals(2, area.channels)
        assertEquals(6, area.totalFrames)
        assertEquals(2, area.tracks.size)

        val first = area.track(1)
        assertEquals(549L, first.startLsn)
        assertEquals(14L, first.lengthSectors)
        assertEquals(549L * 2048, first.byteOffset)
        assertEquals(0, first.startTimeUs)
        assertEquals(40_000, first.durationUs)
        assertEquals(112_896, first.sampleCount)
        assertEquals(2, first.channels)
        assertEquals(RawDsdConstants.DSD64_RATE, first.bitRate)
        val second = area.track(2)
        assertEquals(563L * 2048, second.byteOffset)
        assertEquals(40_000, second.startTimeUs)
        assertEquals(40_000, second.durationUs)
        assertEquals(3, second.startFrame)
        assertEquals(6, second.endFrameExclusive)
    }

    @Test
    fun selectedTrackPacketsAreGoldenAndNeverLeakAdjacentTrack() {
        val fixture = makeSyntheticSacd()
        val input = ByteArrayRawDsdInput(fixture.image)
        val disc = SacdIsoParser.parse(input)
        val reader = SacdIsoReader.open(disc, input, trackNumber = 2)
        val packets = reader.readPackets()
        assertEquals(3, packets.size)
        for (index in packets.indices) {
            val packet = packets[index]
            assertEquals(index * RawDsdConstants.SACD_FRAME_SAMPLES, packet.firstSample)
            assertEquals(RawDsdConstants.SACD_FRAME_SAMPLES.toInt(), packet.sampleCount)
            assertEquals(reader.format.timestampUs(packet.firstSample), packet.timeUs)
            assertArrayEquals(
                NativeDsdPacker.pack(fixture.frames[index + 3], 2, NativeDsdPacking.DSD_U32_BE),
                packet.data,
            )
        }
        assertEquals(40_000, reader.format.durationUs)
        assertEquals(26_666, packets[2].timeUs)
        assertEquals(13_334, packets[2].durationUs)
        assertNull(reader.readPacket())
    }

    @Test
    fun sacdSeekStartsAtExactFrameAndReportsPreroll() {
        val fixture = makeSyntheticSacd()
        val input = ByteArrayRawDsdInput(fixture.image)
        val reader = SacdIsoReader.open(input, trackNumber = 2)
        val point = reader.seekToTime(14_000)
        assertEquals(37_632, point.samplePosition)
        assertEquals(13_333, point.timeUs)
        assertEquals(1_881, point.skipSamples)
        assertEquals(563L * 2048, point.filePosition)
        assertArrayEquals(
            NativeDsdPacker.pack(fixture.frames[4], 2, NativeDsdPacking.DSD_U32_BE),
            reader.readPacket()!!.data,
        )
        val queried = reader.getSeekPoint(20_000)
        assertEquals(37_632, queried.samplePosition)
        assertEquals(13_333, queried.timeUs)
        assertEquals(75_264, reader.nextSamplePosition)
        reader.seekToTime(reader.format.durationUs)
        assertNull(reader.readPacket())
    }

    @Test
    fun raw2064SectorImagesUseTheSameTocAndAudio() {
        val fixture = makeSyntheticSacd()
        val raw = toRaw2064(fixture.image)
        val disc = SacdIsoParser.parse(ByteArrayRawDsdInput(raw))
        assertEquals(SacdSectorLayout(2064, 12), disc.layout)
        assertEquals(549L * 2064 + 12, disc.area(SacdAreaKind.STEREO).track(1).byteOffset)
        val reader = SacdIsoReader.open(disc, ByteArrayRawDsdInput(raw), trackNumber = 2)
        assertArrayEquals(
            NativeDsdPacker.pack(fixture.frames[3], 2, NativeDsdPacking.DSD_U32_BE),
            reader.readPacket()!!.data,
        )
    }

    @Test
    fun malformedAndTruncatedTocsAreRejected() {
        val fixture = makeSyntheticSacd()
        assertThrows(IllegalArgumentException::class.java) {
            SacdIsoParser.parse(ByteArrayRawDsdInput(fixture.image.copyOf().also { it[510 * 2048] = 'X'.code.toByte() }))
        }
        assertThrows(EOFException::class.java) {
            SacdIsoParser.parse(ByteArrayRawDsdInput(fixture.image.copyOf(545 * 2048)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SacdIsoParser.parse(ByteArrayRawDsdInput(fixture.image.copyOf().also { it[546 * 2048 + 9] = 60 }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SacdIsoParser.parse(ByteArrayRawDsdInput(fixture.image.copyOf().also {
                it.putBe32(545 * 2048 + 12, 550)
            }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SacdIsoParser.parse(ByteArrayRawDsdInput(fixture.image.copyOf().also { it.putAscii(545 * 2048, "NOTTRL1!") }))
        }
    }

    @Test
    fun malformedAudioSectorAndTimecodeGapAreRejected() {
        val fixture = makeSyntheticSacd()
        val badHeader = fixture.image.copyOf().also { it[549 * 2048] = 0 }
        val badHeaderReader = SacdIsoReader.open(ByteArrayRawDsdInput(badHeader))
        assertThrows(IllegalArgumentException::class.java) { badHeaderReader.readPacket() }

        val badTimecode = fixture.image.copyOf()
        val firstSector = 549 * 2048
        val packetCount = badTimecode[firstSector].toInt() ushr 5
        badTimecode[firstSector + 1 + 2 * packetCount + 2] = 1
        val badTimecodeReader = SacdIsoReader.open(ByteArrayRawDsdInput(badTimecode))
        assertThrows(IllegalArgumentException::class.java) { badTimecodeReader.readPacket() }
    }

    private fun RawDsdPacketReader.readPackets(): List<RawDsdPacket> = buildList {
        while (true) add(readPacket() ?: break)
    }
}
