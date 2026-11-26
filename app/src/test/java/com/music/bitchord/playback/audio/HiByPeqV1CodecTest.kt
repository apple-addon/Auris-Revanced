package com.music.bitchord.playback.audio

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HiByPeqV1CodecTest {
    @Test
    fun `golden byte layout uses big endian IEEE 754 floats`() {
        val expectedHeader = bytes(
            0x01, 0x41,
            0x02, 0x42, 0x43,
            0x00,
            0xbf, 0xa0, 0x00, 0x00,
        )
        val expectedBand = bytes(
            0x02,
            0x44, 0x7a, 0x00, 0x00,
            0x01,
            0xc0, 0x60, 0x00, 0x00,
            0x3f, 0x80, 0x00, 0x00,
        )
        val expected = expectedHeader + ByteArray(10 * expectedBand.size) {
            expectedBand[it % expectedBand.size]
        }
        val data = goldenData()

        val encoded = HiByPeqV1Codec.encodeToBytes(data)

        assertEquals(150, encoded.size)
        assertArrayEquals(expected, encoded)
        assertEquals(data, HiByPeqV1Codec.decodeFromBytes(expected))
        assertEquals(
            HiByPeqV1Codec.URI_PREFIX + Base64.getEncoder().encodeToString(expected),
            HiByPeqV1Codec.encode(data),
        )
    }

    @Test
    fun `roundtrip preserves UTF-8 text unsigned bytes and all bands`() {
        val original = HiByPeqData(
            name = "Café 🎧",
            device = "HiBy R6",
            description = "十段 / shared",
            preamp = -4.25f,
            bands = List(10) { index ->
                HiByPeqBand(
                    filter = 255 - index,
                    frequency = (index + 1) * 100.25f,
                    on = 255 - index * 2,
                    gain = index - 5.5f,
                    q = index + 0.75f,
                )
            },
        )

        val uri = HiByPeqV1Codec.encode(original)

        assertEquals(original, HiByPeqV1Codec.decode(uri))
        assertEquals(uri, HiByPeqV1Codec.encode(HiByPeqV1Codec.decode(uri)))
    }

    @Test
    fun `every truncated binary payload is rejected`() {
        val encoded = HiByPeqV1Codec.encodeToBytes(goldenData())

        for (length in encoded.indices) {
            assertThrows("Accepted truncation at $length", IllegalArgumentException::class.java) {
                HiByPeqV1Codec.decodeFromBytes(encoded.copyOf(length))
            }
        }
    }

    @Test
    fun `trailing binary data is rejected`() {
        val encoded = HiByPeqV1Codec.encodeToBytes(goldenData())

        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.decodeFromBytes(encoded + 0.toByte())
        }
        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.decodeFromBytes(encoded + encoded.copyOfRange(10, 24))
        }
    }

    @Test
    fun `malformed URIs and Base64 are rejected`() {
        val validPayload = Base64.getEncoder().encodeToString(
            HiByPeqV1Codec.encodeToBytes(goldenData()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.decode("hiby://plg_peq/v2/$validPayload")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.decode(HiByPeqV1Codec.URI_PREFIX)
        }
        listOf("@@@", "A", "AA=A", "AA\nAA").forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                HiByPeqV1Codec.decode(HiByPeqV1Codec.URI_PREFIX + payload)
            }
        }
    }

    @Test
    fun `non-finite values are rejected in models and binary input`() {
        assertThrows(IllegalArgumentException::class.java) {
            goldenData().copy(preamp = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            goldenBand().copy(frequency = Float.POSITIVE_INFINITY)
        }

        val invalidValues = listOf(
            6 to 0x7fc00000,
            11 to 0x7f800000,
            16 to 0xff800000.toInt(),
            20 to 0x7fc00000,
        )
        invalidValues.forEach { (offset, bits) ->
            val malformed = HiByPeqV1Codec.encodeToBytes(goldenData())
            malformed.writeBigEndianInt(offset, bits)
            assertThrows(IllegalArgumentException::class.java) {
                HiByPeqV1Codec.decodeFromBytes(malformed)
            }
        }
    }

    @Test
    fun `text byte lengths unsigned bytes and band count are validated`() {
        val data = goldenData()
        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.encodeToBytes(data.copy(name = "x".repeat(256)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.encodeToBytes(data.copy(device = "é".repeat(128)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HiByPeqV1Codec.encodeToBytes(data.copy(description = "x".repeat(256)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            data.copy(bands = data.bands.drop(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            data.copy(bands = data.bands + data.bands.first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            goldenBand().copy(filter = 256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            goldenBand().copy(on = -1)
        }
    }

    @Test
    fun `golden share vector A decodes field by field and re-encodes byte-identically`() {
        val uri = "hiby://plg_peq/v1/AUECQkMAv6AAAAJEegAAAcBgAAA/gAAAAkR6AAABwGAAAD+AAAACRHoAAAHAYAAAP4AAAAJEegAAAcBgAAA/gAAAAkR6AAABwGAAAD+AAAACRHoAAAHAYAAAP4AAAAJEegAAAcBgAAA/gAAAAkR6AAABwGAAAD+AAAACRHoAAAHAYAAAP4AAAAJEegAAAcBgAAA/gAAA"

        val data = HiByPeqV1Codec.decode(uri)

        assertEquals(goldenData(), data)
        assertEquals("A", data.name)
        assertEquals("BC", data.device)
        assertEquals("", data.description)
        assertEquals(-1.25f, data.preamp, 0.0f)
        assertEquals(10, data.bands.size)
        data.bands.forEach { band ->
            assertEquals(2, band.filter)
            assertEquals(1000.0f, band.frequency, 0.0f)
            assertEquals(1, band.on)
            assertEquals(-3.5f, band.gain, 0.0f)
            assertEquals(1.0f, band.q, 0.0f)
        }
        assertEquals(uri, HiByPeqV1Codec.encode(data))
    }

    @Test
    fun `golden share vector B with varied bands roundtrips through the URI`() {
        val uri = "hiby://plg_peq/v1/CkNhZsOpIPCfjqcHSGlCeSBSNg/ljYHmrrUgLyBzaGFyZWTAiAAA/0LIgAD/wLAAAD9AAAD+Q0iAAP3AkAAAP+AAAP1DlmAA+8BgAABAMAAA/EPIgAD5wCAAAEBwAAD7Q/qgAPe/wAAAQJgAAPpEFmAA9b8AAABAuAAA+UQvcADzPwAAAEDYAAD4REiAAPE/wAAAQPgAAPdEYZAA70AgAABBDAAA9kR6oADtQGAAAEEcAAA="
        val expected = HiByPeqData(
            name = "Café 🎧",
            device = "HiBy R6",
            description = "十段 / shared",
            preamp = -4.25f,
            bands = List(10) { index ->
                HiByPeqBand(
                    filter = 255 - index,
                    frequency = (index + 1) * 100.25f,
                    on = 255 - index * 2,
                    gain = index - 5.5f,
                    q = index + 0.75f,
                )
            },
        )

        val decoded = HiByPeqV1Codec.decode(uri)

        assertEquals(expected, decoded)
        assertEquals(uri, HiByPeqV1Codec.encode(decoded))
    }

    @Test
    fun `negative zero preamp keeps its exact bit pattern`() {
        val data = goldenData().copy(preamp = -0.0f)

        val encoded = HiByPeqV1Codec.encodeToBytes(data)

        // name(1+1) + device(1+2) + description(1+0) -> preamp starts at offset 6
        assertEquals(0x80.toByte(), encoded[6])
        assertEquals(0x00.toByte(), encoded[7])
        val decoded = HiByPeqV1Codec.decodeFromBytes(encoded)
        assertEquals(0x80000000.toInt(), java.lang.Float.floatToRawIntBits(decoded.preamp))
        assertArrayEquals(encoded, HiByPeqV1Codec.encodeToBytes(decoded))
    }

    @Test
    fun `255-byte UTF-8 text fields are accepted and roundtrip`() {
        val data = goldenData().copy(
            name = "x".repeat(255),
            device = "é".repeat(127) + "x",
            description = "🎧".repeat(63) + "xxx",
        )

        val decoded = HiByPeqV1Codec.decode(HiByPeqV1Codec.encode(data))

        assertEquals(data, decoded)
    }

    private fun goldenData() = HiByPeqData(
        name = "A",
        device = "BC",
        description = "",
        preamp = -1.25f,
        bands = List(10) { goldenBand() },
    )

    private fun goldenBand() = HiByPeqBand(
        filter = 2,
        frequency = 1000.0f,
        on = 1,
        gain = -3.5f,
        q = 1.0f,
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.writeBigEndianInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
