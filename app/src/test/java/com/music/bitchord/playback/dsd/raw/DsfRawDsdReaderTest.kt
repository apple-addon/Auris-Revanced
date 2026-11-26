package com.music.bitchord.playback.dsd.raw

import java.io.EOFException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DsfRawDsdReaderTest {
    @Test
    fun goldenStereoPacketsMatchDsdU32Endianness() {
        val left = testBytes(0x01, 0x23, 0x45, 0x67, 0x89)
        val right = testBytes(0x11, 0x22, 0x33, 0x44, 0x55)
        val image = makeDsf(40, listOf(left, right))
        val bigEndian = RawDsdReaders.openDsf(ByteArrayRawDsdInput(image), RawDsdPacking.DSD_U32_BE)
        val packet = bigEndian.readPacket()!!
        assertEquals(0, packet.timeUs)
        assertEquals(14, packet.durationUs)
        assertEquals(0, packet.firstSample)
        assertEquals(40, packet.sampleCount)
        assertArrayEquals(testBytes(
            0x01, 0x23, 0x45, 0x67, 0x11, 0x22, 0x33, 0x44,
            0x89, 0, 0, 0, 0x55, 0, 0, 0,
        ), packet.data)
        assertNull(bigEndian.readPacket())

        val littleEndian = RawDsdReaders.openDsf(ByteArrayRawDsdInput(image), RawDsdPacking.DSD_U32_LE)
        assertArrayEquals(testBytes(
            0x67, 0x45, 0x23, 0x01, 0x44, 0x33, 0x22, 0x11,
            0, 0, 0, 0x89, 0, 0, 0, 0x55,
        ), littleEndian.readPacket()!!.data)
    }

    @Test
    fun lsbFirstDsfIsNormalizedAndGaplessTailIsMasked() {
        val left = testBytes(0x01, 0x80, 0x03, 0x00, 0xff)
        val image = makeDsf(35, listOf(left, ByteArray(5)), bits = 1)
        val reader = RawDsdReaders.openDsf(ByteArrayRawDsdInput(image))
        assertEquals(29, reader.format.finalPaddingSamples)
        val packet = reader.readPacket()!!
        assertEquals(35, packet.sampleCount)
        assertEquals(12, packet.durationUs)
        assertArrayEquals(testBytes(
            0x80, 0x01, 0xc0, 0x00, 0, 0, 0, 0,
            0xe0, 0, 0, 0, 0, 0, 0, 0,
        ), packet.data)
        assertNull(reader.readPacket())
    }

    @Test
    fun blockSeekAndTimestampsDoNotAccumulateRoundingError() {
        val samples = 32_768L + 35
        val channels = List(2) { channel -> ByteArray(4_101) { (it * 29 + channel * 71).toByte() } }
        val reader = RawDsdReaders.openDsf(ByteArrayRawDsdInput(makeDsf(samples, channels)))
        val first = reader.readPacket()!!
        val second = reader.readPacket()!!
        assertEquals(32_768, second.firstSample)
        assertEquals(reader.format.timestampUs(32_768), second.timeUs)
        assertEquals(reader.format.timestampUs(32_803) - second.timeUs, second.durationUs)
        assertEquals(35, second.sampleCount)
        assertNull(reader.readPacket())

        val seek = reader.seekToSample(32_770)
        assertEquals(32_768, seek.samplePosition)
        assertEquals(2, seek.skipSamples)
        assertEquals(second.timeUs, seek.timeUs)
        assertArrayEquals(second.data, reader.readPacket()!!.data)
        val end = reader.seekToTime(reader.format.durationUs)
        assertEquals(samples, end.samplePosition)
        assertEquals(0, end.skipSamples)
        assertNull(reader.readPacket())
        assertEquals(32_768, first.sampleCount)
    }

    @Test
    fun trailingId3StorageDoesNotBecomeAudio() {
        val metadata = testBytes('I'.code, 'D'.code, '3'.code, 4, 0, 0, 0, 0, 0, 0)
        val reader = RawDsdReaders.open(ByteArrayRawDsdInput(makeDsf(
            40,
            listOf(ByteArray(5) { it.toByte() }, ByteArray(5) { (it + 9).toByte() }),
            metadata = metadata,
        )))
        assertEquals(40, reader.format.sampleCount)
        assertEquals(1, reader.readPackets().size)
    }

    @Test
    fun malformedAndTruncatedDsfAreRejected() {
        assertThrows(EOFException::class.java) { RawDsdReaders.open(ByteArrayRawDsdInput(testBytes(1, 2, 3))) }
        assertThrows(EOFException::class.java) {
            RawDsdReaders.open(ByteArrayRawDsdInput("DSD ".toByteArray()))
        }
        val valid = makeDsf(40, listOf(ByteArray(5), ByteArray(5)))
        val wrongFileSize = valid.copyOf().also { it[12] = (it[12] + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { RawDsdReaders.open(ByteArrayRawDsdInput(wrongFileSize)) }
        val wrongBlockSize = valid.copyOf().also { for (index in 72..75) it[index] = 0 }
        assertThrows(IllegalArgumentException::class.java) { RawDsdReaders.open(ByteArrayRawDsdInput(wrongBlockSize)) }
        val wrongDataSize = valid.copyOf().also { it[84] = (it[84] - 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { RawDsdReaders.open(ByteArrayRawDsdInput(wrongDataSize)) }
        assertThrows(IllegalArgumentException::class.java) {
            RawDsdReaders.open(ByteArrayRawDsdInput(valid.copyOf(valid.size - 1)))
        }
    }

    private fun RawDsdPacketReader.readPackets(): List<RawDsdPacket> = buildList {
        while (true) add(readPacket() ?: break)
    }
}
