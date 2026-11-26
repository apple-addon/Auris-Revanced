package com.music.bitchord.playback.dsd.raw

import com.music.bitchord.playback.dsd.DstFrameCrc
import com.music.bitchord.playback.dsd.NativeDsdPacker
import com.music.bitchord.playback.dsd.NativeDsdPacking
import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DffRawDsdReaderTest {
    @Test
    fun goldenDffPacketsPreserveChronologicalBits() {
        val canonical = testBytes(
            0x00, 0x88, 0x11, 0x99, 0x22, 0xaa, 0x33, 0xbb,
            0x44, 0xcc, 0x55, 0xdd, 0x66, 0xee, 0x77, 0xff,
        )
        val reader = RawDsdReaders.openDff(ByteArrayRawDsdInput(makePlainDff(canonical)))
        val packet = reader.readPacket()!!
        assertEquals(64, packet.sampleCount)
        assertEquals(22, packet.durationUs)
        assertArrayEquals(testBytes(
            0x00, 0x11, 0x22, 0x33, 0x88, 0x99, 0xaa, 0xbb,
            0x44, 0x55, 0x66, 0x77, 0xcc, 0xdd, 0xee, 0xff,
        ), packet.data)
        assertNull(reader.readPacket())
    }

    @Test
    fun oddChunksAndPartialFinalWordAreHandledExactly() {
        val canonical = testBytes(0x00, 0x88, 0x11, 0x99, 0x22, 0xaa, 0x33, 0xbb, 0x44, 0xcc)
        val image = makePlainDff(canonical, oddMetadataChunk = true)
        val reader = RawDsdReaders.openDff(ByteArrayRawDsdInput(image), RawDsdPacking.DSD_U32_LE)
        val packet = reader.readPacket()!!
        assertEquals(40, packet.sampleCount)
        assertEquals(24, reader.format.finalPaddingSamples)
        assertArrayEquals(testBytes(
            0x33, 0x22, 0x11, 0x00, 0xbb, 0xaa, 0x99, 0x88,
            0, 0, 0, 0x44, 0, 0, 0, 0xcc,
        ), packet.data)
        assertNull(reader.readPacket())
    }

    @Test
    fun plainDffSeekUsesWholeWordsAndExactEnd() {
        val samples = 32_768L + 16
        val canonical = ByteArray((samples / 8 * 2).toInt()) { (it * 43 + 7).toByte() }
        val reader = RawDsdReaders.openDff(ByteArrayRawDsdInput(makePlainDff(canonical)))
        val second = reader.readPacket()!!.let { first ->
            assertEquals(32_768, first.sampleCount)
            reader.readPacket()!!
        }
        assertEquals(16, second.sampleCount)
        assertEquals(8, second.data.size)
        val point = reader.seekToSample(32_770)
        assertEquals(32_768, point.samplePosition)
        assertEquals(2, point.skipSamples)
        assertArrayEquals(second.data, reader.readPacket()!!.data)
        reader.seekToTime(reader.format.durationUs)
        assertNull(reader.readPacket())
    }

    @Test
    fun dstFramesAreDecodedPackedCheckedAndSeekable() {
        val decoded = List(2) { frame -> ByteArray(4704 * 2) { (frame * 83 + it * 19).toByte() } }
        var closed = false
        val factory = RawDstDecoderFactory { channels ->
            assertEquals(2, channels)
            object : RawDstDecoder {
                override fun decode(encoded: ByteArray, output: ByteArray) {
                    assertEquals(2, encoded.size)
                    val frame = encoded[0].toInt()
                    require(frame in decoded.indices)
                    require(encoded[1].toInt() == frame + 10)
                    decoded[frame].copyInto(output)
                }

                override fun close() {
                    closed = true
                }
            }
        }
        val encoded = listOf(testBytes(0, 10), testBytes(1, 11))
        val image = makeDstDff(encoded, crcs = decoded.map(DstFrameCrc::calculate))
        val reader = RawDsdReaders.openDff(ByteArrayRawDsdInput(image), dstDecoderFactory = factory)
        val first = reader.readPacket()!!
        val second = reader.readPacket()!!
        assertArrayEquals(NativeDsdPacker.pack(decoded[0], 2, NativeDsdPacking.DSD_U32_BE), first.data)
        assertEquals(0, first.timeUs)
        assertEquals(13_333, second.timeUs)
        assertEquals(26_666, second.endTimeUs)
        assertEquals(37_632, second.firstSample)
        assertNull(reader.readPacket())

        val seek = reader.seekToTime(14_000)
        assertEquals(37_632, seek.samplePosition)
        assertEquals(1_881, seek.skipSamples)
        assertEquals(second.timeUs, seek.timeUs)
        assertArrayEquals(second.data, reader.readPacket()!!.data)
        reader.close()
        assertTrue(closed)
    }

    @Test
    fun malformedTruncatedAndMisalignedDffAreRejected() {
        assertThrows(EOFException::class.java) { RawDsdReaders.open(ByteArrayRawDsdInput("FRM8".toByteArray())) }
        val valid = makePlainDff(ByteArray(16))
        val badFirstChunk = valid.copyOf().also { it.putAscii(16, "NOPE") }
        assertThrows(IllegalArgumentException::class.java) { RawDsdReaders.open(ByteArrayRawDsdInput(badFirstChunk)) }
        assertThrows(IllegalArgumentException::class.java) {
            RawDsdReaders.open(ByteArrayRawDsdInput(valid.copyOf(valid.size - 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawDsdReaders.open(ByteArrayRawDsdInput(makePlainDff(ByteArray(15))))
        }
        val truncated = valid.copyOf(valid.size - 20)
        val unknownLength = object : RawDsdInput {
            private val delegate = ByteArrayRawDsdInput(truncated)
            override val length: Long get() = RawDsdInput.UNKNOWN_LENGTH
            override fun readFully(position: Long, destination: ByteArray, offset: Int, length: Int) =
                delegate.readFully(position, destination, offset, length)
        }
        assertThrows(EOFException::class.java) { RawDsdReaders.open(unknownLength) }
    }

    @Test
    fun dstChecksumFailureDoesNotEmitPacket() {
        val decoded = ByteArray(9408) { (it * 7).toByte() }
        val factory = RawDstDecoderFactory {
            object : RawDstDecoder {
                override fun decode(encoded: ByteArray, output: ByteArray) = decoded.copyInto(output).let { }
                override fun close() = Unit
            }
        }
        val reader = RawDsdReaders.openDff(
            ByteArrayRawDsdInput(makeDstDff(listOf(testBytes(1, 2)), crcs = listOf(DstFrameCrc.calculate(decoded) + 1))),
            dstDecoderFactory = factory,
        )
        assertThrows(IllegalArgumentException::class.java) { reader.readPacket() }
    }
}
