package com.music.bitchord.playback.decoder

import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RawDsdTransportTest {
    @Test
    fun dopPutsOldestBitsInLowWordAndAlternatesMarkers() {
        val input = byteArrayOf(
            0x12, 0x34, 0xAB.toByte(), 0xCD.toByte(),
            0x69, 0x96.toByte(), 0x0F, 0xF0.toByte(),
        )
        val littleEndian = DopPacker(2).pack(input)
        assertArrayEquals(
            byteArrayOf(
                0xAB.toByte(), 0x12, 0x05, 0xCD.toByte(), 0x34, 0x05,
                0x0F, 0x69, 0xFA.toByte(), 0xF0.toByte(), 0x96.toByte(), 0xFA.toByte(),
            ),
            littleEndian,
        )
        val bigEndian = DopPacker(2).pack(input, byteOrder = ByteOrder.BIG_ENDIAN)
        assertArrayEquals(
            byteArrayOf(
                0x05, 0x12, 0xAB.toByte(), 0x05, 0x34, 0xCD.toByte(),
                0xFA.toByte(), 0x69, 0x0F, 0xFA.toByte(), 0x96.toByte(), 0xF0.toByte(),
            ),
            bigEndian,
        )
    }

    @Test
    fun dopMarkerPhaseSurvivesChunkBoundariesAndReset() {
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7, 8)
        val packer = DopPacker(2)
        val combined = packer.pack(first) + packer.pack(second)
        assertArrayEquals(DopPacker(2).pack(first + second), combined)
        assertEquals(0x05, packer.nextMarker)
        packer.reset()
        assertArrayEquals(DopPacker(2).pack(first), packer.pack(first))
    }

    @Test
    fun uac2RawSlotsPreserveAll32DsdBits() {
        val input = byteArrayOf(0, 16, 1, 17, 2, 18, 3, 19)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 16, 17, 18, 19), RawDsdUac2Packer(2).pack(input))
        assertArrayEquals(
            byteArrayOf(3, 2, 1, 0, 19, 18, 17, 16),
            RawDsdUac2Packer(2).pack(input, byteOrder = ByteOrder.LITTLE_ENDIAN),
        )
    }

    @Test
    fun carrierRatesMatchDsdPayloadRates() {
        assertEquals(176_400, DsdTransport.dopCarrierRate(2_822_400))
        assertEquals(352_800, DsdTransport.dopCarrierRate(5_644_800))
        assertEquals(88_200, DsdTransport.rawUac2CarrierRate(2_822_400))
        assertEquals(176_400, DsdTransport.rawUac2CarrierRate(5_644_800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun dopRejectsPartialChannelPayload() {
        DopPacker(2).pack(ByteArray(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rawDsdRejectsPartialSlot() {
        RawDsdUac2Packer(2).pack(ByteArray(7))
    }
}
