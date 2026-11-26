package com.music.bitchord.playback.dsd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DopCodecTest {
    private val stereoDsd = bytes(0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0)
    private val stereoWords = intArrayOf(0x051234, 0x055678, 0xfa9abc, 0xfadef0)
    private val stereoWire = bytes(0x34, 0x12, 0x05, 0x78, 0x56, 0x05, 0xbc, 0x9a, 0xfa, 0xf0, 0xde, 0xfa)

    @Test
    fun stereoGoldenWordsAndLittleEndianWireBytes() {
        assertArrayEquals(stereoWords, DopEncoder(2).encodeWords(stereoDsd))
        assertArrayEquals(stereoWire, DopEncoder(2).encode(stereoDsd))
        assertArrayEquals(stereoDsd, DopDecoder(2).decodeWords(stereoWords))
        assertArrayEquals(stereoDsd, DopDecoder(2).decode(stereoWire))
    }

    @Test
    fun monoGoldenVectorUsesOldestByteAboveNewerByte() {
        val dsd = bytes(0x80, 0x01, 0x7f, 0xfe)
        assertArrayEquals(intArrayOf(0x058001, 0xfa7ffe), DopEncoder(1).encodeWords(dsd))
        assertArrayEquals(bytes(0x01, 0x80, 0x05, 0xfe, 0x7f, 0xfa), DopEncoder(1).encode(dsd))
    }

    @Test
    fun markerPhaseSurvivesTransportBlocks() {
        val encoder = DopEncoder(2)
        val first = stereoDsd.copyOfRange(0, 4)
        val second = stereoDsd.copyOfRange(4, 8)
        assertArrayEquals(intArrayOf(0x051234, 0x055678), encoder.encodeWords(first))
        assertEquals(Dop.MARKER_SECOND, encoder.nextMarker)
        assertArrayEquals(intArrayOf(0xfa9abc, 0xfadef0), encoder.encodeWords(second))
        assertEquals(Dop.MARKER_FIRST, encoder.nextMarker)
    }

    @Test
    fun seekRestartsEncoderAndDecoderAt05() {
        val encoder = DopEncoder(1)
        encoder.encodeWords(bytes(1, 2))
        assertEquals(Dop.MARKER_SECOND, encoder.nextMarker)
        encoder.seek()
        assertArrayEquals(intArrayOf(0x050102), encoder.encodeWords(bytes(1, 2)))

        val decoder = DopDecoder(1)
        assertArrayEquals(bytes(1, 2), decoder.decodeWords(intArrayOf(0x050102)))
        decoder.reset()
        assertArrayEquals(bytes(3, 4), decoder.decodeWords(intArrayOf(0x050304)))
    }

    @Test
    fun offsetsDoNotIncludePrefixOrSuffix() {
        val padded = bytes(0xaa, 0xbb) + stereoDsd + bytes(0xcc)
        assertArrayEquals(stereoWire, DopEncoder(2).encode(padded, 2, stereoDsd.size))
        val wire = bytes(0x11) + stereoWire + bytes(0x22, 0x33)
        assertArrayEquals(stereoDsd, DopDecoder(2).decode(wire, 1, stereoWire.size))
    }

    @Test
    fun decoderRejectsWrongInitialAndRepeatedMarkers() {
        assertThrows(DopFormatException::class.java) {
            DopDecoder(1).decodeWords(intArrayOf(0xfa1234))
        }
        assertThrows(DopFormatException::class.java) {
            DopDecoder(1).decodeWords(intArrayOf(0x051234, 0x055678))
        }
    }

    @Test
    fun everyChannelMustHaveTheSameFrameMarker() {
        assertThrows(DopFormatException::class.java) {
            DopDecoder(2).decodeWords(intArrayOf(0x051234, 0xfa5678))
        }
    }

    @Test
    fun malformedSizesAndWordsAreRejected() {
        assertThrows(DopFormatException::class.java) { DopEncoder(2).encode(bytes(1, 2)) }
        assertThrows(DopFormatException::class.java) { DopDecoder(2).decodeWords(intArrayOf(0x051234)) }
        assertThrows(DopFormatException::class.java) { DopDecoder(2).decode(bytes(1, 2, 3)) }
        assertThrows(DopFormatException::class.java) { DopDecoder(1).decodeWords(intArrayOf(0x01051234)) }
        assertThrows(IllegalArgumentException::class.java) { DopEncoder(1).encode(bytes(1, 2), -1, 2) }
        assertThrows(IllegalArgumentException::class.java) { DopDecoder(1).decodeWords(IntArray(2), 1, 2) }
    }

    @Test
    fun malformedPacketDoesNotAdvanceDecoderPhase() {
        val decoder = DopDecoder(1)
        assertArrayEquals(bytes(0x12, 0x34), decoder.decodeWords(intArrayOf(0x051234)))
        assertThrows(DopFormatException::class.java) { decoder.decodeWords(intArrayOf(0x055678)) }
        assertEquals(Dop.MARKER_SECOND, decoder.nextMarker)
        assertArrayEquals(bytes(0x56, 0x78), decoder.decodeWords(intArrayOf(0xfa5678)))
    }

    @Test
    fun emptyBlocksAreNoOpsAndChannelCountsAreChecked() {
        val encoder = DopEncoder(2)
        assertArrayEquals(ByteArray(0), encoder.encode(ByteArray(0)))
        assertEquals(Dop.MARKER_FIRST, encoder.nextMarker)
        assertArrayEquals(ByteArray(0), DopDecoder(2).decode(ByteArray(0)))
        assertThrows(IllegalArgumentException::class.java) { DopEncoder(0) }
        assertThrows(IllegalArgumentException::class.java) { DopDecoder(256) }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
