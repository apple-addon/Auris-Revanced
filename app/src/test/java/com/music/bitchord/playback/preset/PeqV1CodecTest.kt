package com.music.bitchord.playback.preset

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PeqV1CodecTest {
    @Test
    fun `golden binary and URI match smali layout`() {
        val bytes = hex(GOLDEN_HEX)
        assertEquals(151, bytes.size)
        assertArrayEquals(bytes, PeqV1Codec.toBytes(goldenPreset()))
        assertEquals(GOLDEN_URI, PeqV1Codec.generateContent(goldenPreset()))
        val decoded = PeqV1Codec.decode(GOLDEN_URI)
        assertEquals(narrowedGolden(), decoded)
        assertArrayEquals(bytes, PeqV1Codec.toBytes(decoded))
        assertEquals("Né", decoded.name)
        assertEquals("D", decoded.device)
        assertEquals("", decoded.description)
        assertEquals(10, decoded.bands.size)
        assertEquals(-1.25, decoded.preampDb, 0.0)
        assertEquals(20.0, decoded.bands[0].frequencyHz, 0.0)
        assertEquals(0.1f.toDouble(), decoded.bands[0].q, 0.0)
        assertTrue(decoded.bands[0].enabled)
        assertFalse(decoded.bands[1].enabled)
    }

    @Test
    fun `signed filter and on bytes round trip without reinterpretation`() {
        val preset = goldenPreset().copy(
            bands = goldenPreset().bands.mapIndexed { index, band ->
                if (index == 0) band.copy(type = -1, on = -1) else band
            },
        )
        val decoded = PeqV1Codec.decode(PeqV1Codec.generateContent(preset))
        assertEquals(-1, decoded.bands[0].filter)
        assertEquals(-1, decoded.bands[0].on)
        assertTrue(decoded.bands[0].enabled)
        assertArrayEquals(PeqV1Codec.toBytes(preset), PeqV1Codec.toBytes(decoded))
    }

    @Test
    fun `negative zero and float narrowing are byte stable`() {
        val preset = goldenPreset().copy(
            preampDb = -0.0,
            bands = goldenPreset().bands.map { it.copy(gainDb = -0.0, q = 0.123456789123) },
        )
        val bytes = PeqV1Codec.toBytes(preset)
        val decoded = PeqV1Codec.decodeBytes(bytes)
        assertTrue(java.lang.Double.doubleToRawLongBits(decoded.preampDb) < 0)
        assertEquals(0.123456789123f.toDouble(), decoded.bands[0].q, 0.0)
        assertArrayEquals(bytes, PeqV1Codec.toBytes(decoded))
    }

    @Test
    fun `construction enforces ten bands and finite float values`() {
        malformed { goldenPreset().copy(bands = goldenPreset().bands.drop(1)) }
        malformed { goldenPreset().copy(preampDb = Double.NaN) }
        malformed { goldenPreset().copy(preampDb = Double.MAX_VALUE) }
        malformed { PeqBand(0, Double.POSITIVE_INFINITY, true, 0.0, 1.0) }
        malformed { PeqBand(0, 100.0, true, Double.NaN, 1.0) }
        malformed { PeqBand(0, 100.0, true, 0.0, Double.NEGATIVE_INFINITY) }
        malformed { PeqBand(128, 100.0, true, 0.0, 1.0) }
        malformed { PeqBand(0, 100.0, 128, 0.0, 1.0) }
    }

    @Test
    fun `text uses UTF-8 lengths and rejects overlong or malformed strings`() {
        malformed { PeqV1Codec.toBytes(goldenPreset().copy(name = "é".repeat(128))) }
        malformed { PeqV1Codec.toBytes(goldenPreset().copy(device = "x".repeat(256))) }
        malformed { PeqV1Codec.toBytes(goldenPreset().copy(description = "\uD800")) }
        val invalidUtf8 = hex(GOLDEN_HEX).also { it[1] = 0xFF.toByte() }
        malformed { PeqV1Codec.decodeBytes(invalidUtf8) }
    }

    @Test
    fun `wrong prefixes malformed Base64 and noncanonical encodings fail closed`() {
        malformed { PeqV1Codec.decode("") }
        malformed { PeqV1Codec.decode("hiby://plg_mseb/v1/{}") }
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX) }
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX + "***=") }
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX + "AAAAA") }
        val payload = GOLDEN_URI.substring(PeqV1Codec.PREFIX.length)
        val nonCanonical = payload.dropLast(3) + "B=="
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX + nonCanonical) }
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX + "A".repeat(2000)) }
        assertTrue(PeqV1Codec.canDecode(GOLDEN_URI))
        assertFalse(PeqV1Codec.canDecode(GOLDEN_URI.uppercase()))
    }

    @Test
    fun `truncated trailing and nonfinite payloads fail closed`() {
        val bytes = hex(GOLDEN_HEX)
        malformed { PeqV1Codec.decodeBytes(bytes.copyOf(bytes.size - 1)) }
        malformed { PeqV1Codec.decodeBytes(bytes + 0) }
        malformed { PeqV1Codec.decodeBytes(ByteArray(0)) }
        malformed { PeqV1Codec.decodeBytes(ByteArray(1000)) }
        val nan = bytes.copyOf().also {
            // Three length-prefixed strings occupy seven bytes in the golden vector.
            it[7] = 0x7F
            it[8] = 0xC0.toByte()
            it[9] = 0
            it[10] = 0
        }
        malformed { PeqV1Codec.decodeBytes(nan) }
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX + base64(bytes.copyOf(bytes.size - 14))) }
        malformed { PeqV1Codec.decode(PeqV1Codec.PREFIX + base64(bytes + ByteArray(14))) }
    }

    private fun goldenPreset(): PeqPreset {
        val types = listOf(0, 1, 2, 3, 4, 5, 0, 1, 2, 3)
        val frequencies = listOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 1000.0, 5000.0, 20000.0)
        val enabled = listOf(true, false, true, false, true, false, true, false, true, false)
        val gains = listOf(-12.0, -1.5, 0.0, 0.5, 1.25, 2.5, 3.75, -0.25, 6.0, -6.0)
        val qs = listOf(0.1, 0.25, 0.5, 0.7071067811865476, 1.0, 1.4142135623730951, 2.0, 4.0, 8.0, 12.0)
        return PeqPreset(
            name = "Né",
            device = "D",
            description = "",
            preampDb = -1.25,
            bands = List(10) { PeqBand(types[it], frequencies[it], enabled[it], gains[it], qs[it]) },
        )
    }

    private fun narrowedGolden(): PeqPreset {
        val source = goldenPreset()
        return source.copy(
            preampDb = source.preampDb.toFloat().toDouble(),
            bands = source.bands.map {
                it.copy(
                    frequencyHz = it.frequencyHz.toFloat().toDouble(),
                    gainDb = it.gainDb.toFloat().toDouble(),
                    q = it.q.toFloat().toDouble(),
                )
            },
        )
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun malformed(block: () -> Unit) {
        try {
            block()
            fail("Expected PresetCodecException")
        } catch (_: PresetCodecException) {
        }
    }

    companion object {
        private const val GOLDEN_HEX =
            "034ec3a9014400bfa000000041a0000001c14000003dcccccd0141c8000000bfc000003e8000000241fc000001000000" +
                "003f0000000342200000003f0000003f3504f30442480000013fa000003f80000005427c000000402000003fb504f300" +
                "42a0000001407000004000000001447a000000be8000004080000002459c40000140c000004100000003469c400000c0" +
                "c0000041400000"
        private const val GOLDEN_URI = PeqV1Codec.PREFIX +
            "A07DqQFEAL+gAAAAQaAAAAHBQAAAPczMzQFByAAAAL/AAAA+gAAAAkH8AAABAAAAAD8AAAADQiAAAAA/AAAAPzUE8wRCSAAAAT+gAAA/gAAABUJ8AAAAQCAAAD+1BPMAQqAAAAFAcAAAQAAAAAFEegAAAL6AAABAgAAAAkWcQAABQMAAAEEAAAADRpxAAADAwAAAQUAAAA=="
    }
}
