package com.music.bitchord.playback.preset

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PawsAutoEqBlobTest {
    @Test
    fun `graphic PaP maps two tone and 32 regular bands`() {
        val decoded = PawsAutoEqBlob.decode(blob(parametric = false))
        assertFalse(decoded.parametric)
        assertEquals(0.0f, decoded.preamp)
        assertEquals(34, decoded.bands.size)
        assertEquals(32, decoded.regularBands.size)
        assertEquals(PawsBandType.BASS, decoded.bands[0].type)
        assertEquals(90, decoded.bands[0].frequencyHz)
        assertFalse(decoded.bands[0].enabled)
        assertEquals(PawsBandType.TREBLE, decoded.bands[1].type)
        assertEquals(10_000, decoded.bands[1].frequencyHz)
        assertEquals(PawsBandType.GRAPHIC, decoded.regularBands.first().type)
        assertEquals(20, decoded.regularBands.first().frequencyHz)
        assertEquals(4.0f, decoded.regularBands.first().gain)
        assertTrue(decoded.regularBands.first().enabled)
        assertNull(decoded.regularBands.first().q)
        assertEquals(19_871, decoded.regularBands.last().frequencyHz)
        assertEquals(-3.5f, decoded.regularBands.last().gain)
    }

    @Test
    fun `parametric PaP maps ten filters Q color channels and preamp`() {
        val decoded = PawsAutoEqBlob.decode(blob(parametric = true))
        assertTrue(decoded.parametric)
        assertEquals(-2.5f, decoded.preamp)
        assertEquals(12, decoded.bands.size)
        assertEquals(10, decoded.regularBands.size)
        val first = decoded.regularBands.first()
        assertEquals(PawsBandType.LOW_SHELF, first.type)
        assertEquals(105, first.frequencyHz)
        assertEquals(2.6f, first.gain)
        assertEquals(0.7f, first.q)
        assertEquals(0, first.color)
        assertEquals(0, first.channels)
        val second = decoded.regularBands[1]
        assertEquals(PawsBandType.PEAKING, second.type)
        assertEquals(6_337, second.frequencyHz)
        assertEquals(5.3f, second.gain)
        assertEquals(3.61f, second.q)
        assertEquals(0xFF113399.toInt(), second.color)
        assertEquals(PawsBandType.HIGH_SHELF, decoded.regularBands[5].type)
    }

    @Test
    fun `all structural corruption fails instead of clamping like PAWS UI`() {
        val good = blob(true)
        val cases = mutableListOf<ByteArray>()
        cases += good.copyOf(2)
        cases += good.copyOf(good.size - 1)
        cases += good + 0
        cases += good.mutateInt(0, 0)
        cases += good.mutateInt(4, 0x0002000C)
        cases += good.mutateInt(4, 0x0000000C) // Says graphic but records carry Q.
        cases += good.mutateInt(8, Float.NaN.toRawBits())
        cases += good.mutateInt(8, 25.0f.toRawBits())
        cases += good.mutateInt(12, 0x00002000) // Tone records cannot carry Q here.
        cases += good.mutateInt(12, 0x00000006) // Invalid tone type.
        cases += good.mutateInt(36, 0x00007303) // Channels=3.
        cases += good.mutateInt(36, 0x00007002) // Graphic type in parametric preset.
        cases += good.mutateInt(36, 0x00006003) // Normal-band flag missing.
        cases += good.mutateInt(40, Float.POSITIVE_INFINITY.toRawBits())
        cases += good.mutateInt(40, 15.1f.toRawBits())
        cases += good.mutateInt(44, 0)
        cases += good.mutateInt(48, 0.01f.toRawBits())
        cases += good.copyOf(36 + 20 * 9 + 16) // Last color is truncated.
        cases.forEachIndexed { index, bytes ->
            try {
                PawsAutoEqBlob.decode(bytes)
                fail("Expected malformed PAWS case $index to fail")
            } catch (_: PresetCodecException) {
            }
        }
    }

    @Test
    fun `invalid band type lookup fails`() {
        try {
            PawsBandType.fromWire(6)
            fail("Expected PresetCodecException")
        } catch (_: PresetCodecException) {
        }
    }

    private fun blob(parametric: Boolean): ByteArray {
        val count = if (parametric) 10 else 32
        val bytes = ByteBuffer.allocate(12 + 2 * 12 + count * if (parametric) 20 else 12)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(byteArrayOf(0x50, 0x61, 0x50, 0x01))
        bytes.putInt((count + 2) or if (parametric) 0x10000 else 0)
        bytes.putFloat(if (parametric) -2.5f else 0.0f)
        putTone(bytes, type = 0, frequency = 90)
        putTone(bytes, type = 1, frequency = 10_000)
        if (!parametric) {
            val frequencies = listOf(
                20, 25, 31, 39, 49, 61, 76, 95, 119, 148, 185, 231, 289, 361, 451, 564,
                705, 880, 1100, 1374, 1717, 2145, 2680, 3348, 4182, 5225, 6528, 8156,
                10190, 12731, 15905, 19871,
            )
            frequencies.forEachIndexed { index, frequency ->
                bytes.putInt(0x5002)
                bytes.putFloat(if (index == 0) 4.0f else if (index == 31) -3.5f else 0.0f)
                bytes.putInt(frequency)
            }
        } else {
            val frequencies = listOf(105, 6337, 210, 4135, 1370, 10000, 776, 1180, 7029, 1163)
            val gains = listOf(2.6f, 5.3f, -1.0f, -1.4f, -1.0f, -4.7f, 0.6f, 0.3f, 1.6f, -0.7f)
            val qs = listOf(0.7f, 3.61f, 1.03f, 2.06f, 2.36f, 0.7f, 2.28f, 3.24f, 5.94f, 3.3f)
            frequencies.forEachIndexed { index, frequency ->
                val type = when (index) {
                    0 -> PawsBandType.LOW_SHELF
                    5 -> PawsBandType.HIGH_SHELF
                    else -> PawsBandType.PEAKING
                }
                bytes.putInt(0x7000 or type.wireValue)
                bytes.putFloat(gains[index])
                bytes.putInt(frequency)
                bytes.putFloat(qs[index])
                bytes.putInt(if (index == 1) 0xFF113399.toInt() else 0)
            }
        }
        assertFalse(bytes.hasRemaining())
        return bytes.array()
    }

    private fun putTone(bytes: ByteBuffer, type: Int, frequency: Int) {
        bytes.putInt(type)
        bytes.putFloat(0.0f)
        bytes.putInt(frequency)
    }

    private fun ByteArray.mutateInt(offset: Int, value: Int): ByteArray = copyOf().also {
        ByteBuffer.wrap(it, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

}
