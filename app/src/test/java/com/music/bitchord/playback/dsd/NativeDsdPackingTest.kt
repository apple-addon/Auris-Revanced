package com.music.bitchord.playback.dsd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDsdPackingTest {
    // Left: 00 11 22 33 44 55 66 77; right: 88 99 aa bb cc dd ee ff.
    private val canonical = bytes(
        0x00, 0x88, 0x11, 0x99, 0x22, 0xaa, 0x33, 0xbb,
        0x44, 0xcc, 0x55, 0xdd, 0x66, 0xee, 0x77, 0xff,
    )
    private val u32Be = bytes(
        0x00, 0x11, 0x22, 0x33, 0x88, 0x99, 0xaa, 0xbb,
        0x44, 0x55, 0x66, 0x77, 0xcc, 0xdd, 0xee, 0xff,
    )
    private val u32Le = bytes(
        0x33, 0x22, 0x11, 0x00, 0xbb, 0xaa, 0x99, 0x88,
        0x77, 0x66, 0x55, 0x44, 0xff, 0xee, 0xdd, 0xcc,
    )
    private val u16Le = bytes(
        0x11, 0x00, 0x99, 0x88, 0x33, 0x22, 0xbb, 0xaa,
        0x55, 0x44, 0xdd, 0xcc, 0x77, 0x66, 0xff, 0xee,
    )

    @Test
    fun stereoGoldenVectorsMatchAlsaWordLayouts() {
        assertArrayEquals(u32Be, NativeDsdPacker.pack(canonical, 2, NativeDsdPacking.DSD_U32_BE))
        assertArrayEquals(u32Le, NativeDsdPacker.pack(canonical, 2, NativeDsdPacking.DSD_U32_LE))
        assertArrayEquals(u16Le, NativeDsdPacker.pack(canonical, 2, NativeDsdPacking.DSD_U16_LE))
    }

    @Test
    fun unpackingRestoresChronologicalInterleaving() {
        for ((packing, packed) in listOf(
            NativeDsdPacking.DSD_U32_BE to u32Be,
            NativeDsdPacking.DSD_U32_LE to u32Le,
            NativeDsdPacking.DSD_U16_LE to u16Le,
        )) {
            assertArrayEquals(packing.name, canonical, NativeDsdPacker.unpack(packed, 2, packing))
        }
    }

    @Test
    fun roundTripsAllPackingsAndSeveralChannelCounts() {
        for (channels in 1..4) for (packing in NativeDsdPacking.entries) {
            val input = ByteArray(packing.frameBytes(channels) * 3) { (it * 37 + channels).toByte() }
            val packed = NativeDsdPacker.pack(input, channels, packing)
            assertArrayEquals("channels=$channels packing=$packing", input, NativeDsdPacker.unpack(packed, channels, packing))
        }
    }

    @Test
    fun packingHonorsOffsets() {
        val padded = bytes(9, 8, 7) + canonical + bytes(6, 5)
        val packed = NativeDsdPacker.pack(padded, 2, NativeDsdPacking.DSD_U32_LE, 3, canonical.size)
        assertArrayEquals(u32Le, packed)
        val packedPadding = bytes(1, 2) + packed + bytes(3)
        assertArrayEquals(canonical, NativeDsdPacker.unpack(packedPadding, 2, NativeDsdPacking.DSD_U32_LE, 2, packed.size))
    }

    @Test
    fun slotMatchingDoesNotGuessEndianness() {
        assertTrue(NativeDsdPacking.DSD_U32_BE.matchesSlot(4, 32))
        assertTrue(NativeDsdPacking.DSD_U32_LE.matchesSlot(4, 32))
        assertTrue(NativeDsdPacking.DSD_U16_LE.matchesSlot(2, 16))
        assertFalse(NativeDsdPacking.DSD_U16_LE.matchesSlot(4, 32))
        assertFalse(NativeDsdPacking.DSD_U32_LE.matchesSlot(4, 24))
        assertEquals(8, NativeDsdPacking.DSD_U32_BE.frameBytes(2))
        assertEquals(4, NativeDsdPacking.DSD_U16_LE.frameBytes(2))
    }

    @Test
    fun malformedBlocksRangesAndChannelCountsAreRejected() {
        for (packing in NativeDsdPacking.entries) {
            val invalid = ByteArray(packing.frameBytes(2) - 1)
            assertThrows(IllegalArgumentException::class.java) { NativeDsdPacker.pack(invalid, 2, packing) }
            assertThrows(IllegalArgumentException::class.java) { NativeDsdPacker.unpack(invalid, 2, packing) }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeDsdPacker.pack(ByteArray(4), 0, NativeDsdPacking.DSD_U32_BE) }
        assertThrows(IllegalArgumentException::class.java) { NativeDsdPacker.pack(ByteArray(4), 1, NativeDsdPacking.DSD_U32_BE, 1, 4) }
        assertThrows(IllegalArgumentException::class.java) { NativeDsdPacker.unpack(ByteArray(4), 1, NativeDsdPacking.DSD_U32_LE, -1, 4) }
        assertThrows(IllegalArgumentException::class.java) { NativeDsdPacking.DSD_U16_LE.frameBytes(256) }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
