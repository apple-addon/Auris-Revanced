package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HiByMsebV1CodecTest {
    @Test
    fun `JSON has exact keys and HiBy wire order`() {
        val expectedJson =
            """{"datalist":[0,10,20,30,40,50,60,70,80,90],"range":6,"result":[10,9,8,7,6,5,4,3,2,1]}"""

        val encodedJson = HiByMsebV1Codec.encodeToJson(sampleData())

        assertEquals(expectedJson, encodedJson)
        assertEquals(HiByMsebV1Codec.URI_PREFIX + expectedJson, HiByMsebV1Codec.encode(sampleData()))
    }

    @Test
    fun `MSEB URI and JSON roundtrip`() {
        val original = sampleData()

        assertEquals(original, HiByMsebV1Codec.decode(HiByMsebV1Codec.encode(original)))
        assertEquals(original, HiByMsebV1Codec.decodeFromJson(HiByMsebV1Codec.encodeToJson(original)))
    }

    @Test
    fun `both MSEB lists require exactly ten dimensions`() {
        val data = sampleData()

        assertThrows(IllegalArgumentException::class.java) {
            data.copy(datalist = data.datalist.drop(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            data.copy(result = data.result + 11)
        }
        assertInvalidJson(
            """{"datalist":[0,10,20,30,40,50,60,70,80],"range":6,"result":[10,9,8,7,6,5,4,3,2,1]}""",
        )
        assertInvalidJson(
            """{"datalist":[0,10,20,30,40,50,60,70,80,90],"range":6,"result":[10,9,8,7,6,5,4,3,2,1,0]}""",
        )
    }

    @Test
    fun `MSEB rejects malformed missing unknown and trailing JSON data`() {
        assertInvalidJson("""{"datalist":""")
        assertInvalidJson("""[]""")
        assertInvalidJson(
            """{"datalist":[0,10,20,30,40,50,60,70,80,90],"result":[10,9,8,7,6,5,4,3,2,1]}""",
        )
        assertInvalidJson(
            """{"datalist":[0,10,20,30,40,50,60,70,80,90],"range":6,"result":[10,9,8,7,6,5,4,3,2,1],"extra":0}""",
        )
        assertInvalidJson(HiByMsebV1Codec.encodeToJson(sampleData()) + " trailing")
    }

    @Test
    fun `MSEB rejects wrong or empty URI payload`() {
        val json = HiByMsebV1Codec.encodeToJson(sampleData())

        assertThrows(IllegalArgumentException::class.java) {
            HiByMsebV1Codec.decode("hiby://plg_mseb/v2/$json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HiByMsebV1Codec.decode(HiByMsebV1Codec.URI_PREFIX)
        }
    }

    @Test
    fun `golden share URI decodes to the exact data and re-encodes identically`() {
        val uri = HiByMsebV1Codec.URI_PREFIX +
            """{"datalist":[-100,-50,-25,0,25,50,75,100,12,-12],"range":100,"result":[-100,-50,-25,0,25,50,75,100,12,-12]}"""

        val data = HiByMsebV1Codec.decode(uri)

        assertEquals(listOf(-100, -50, -25, 0, 25, 50, 75, 100, 12, -12), data.datalist)
        assertEquals(100, data.range)
        assertEquals(data.datalist, data.result)
        assertEquals(uri, HiByMsebV1Codec.encode(data))
    }

    @Test
    fun `range is preserved verbatim as metadata and never normalized by the codec`() {
        for (range in listOf(6, 20, 25, 40, 50, 100)) {
            val data = sampleData().copy(range = range)
            assertEquals(range, HiByMsebV1Codec.decode(HiByMsebV1Codec.encode(data)).range)
            assertEquals(range, HiByMsebV1Codec.decodeFromJson(HiByMsebV1Codec.encodeToJson(data)).range)
        }
    }

    @Test
    fun `datalist and result are independent lists and both survive the roundtrip`() {
        val data = sampleData().copy(datalist = listOf(-100, 100, 0, 0, 0, 0, 0, 0, 0, 0))

        val decoded = HiByMsebV1Codec.decode(HiByMsebV1Codec.encode(data))

        assertEquals(data.datalist, decoded.datalist)
        assertEquals(data.result, decoded.result)
    }

    @Test
    fun `decode accepts any key order but encode always uses the HiBy wire order`() {
        val reordered =
            """{"result":[10,9,8,7,6,5,4,3,2,1],"range":6,"datalist":[0,10,20,30,40,50,60,70,80,90]}"""

        assertEquals(sampleData(), HiByMsebV1Codec.decodeFromJson(reordered))
        assertEquals(
            """{"datalist":[0,10,20,30,40,50,60,70,80,90],"range":6,"result":[10,9,8,7,6,5,4,3,2,1]}""",
            HiByMsebV1Codec.encodeToJson(HiByMsebV1Codec.decodeFromJson(reordered)),
        )
    }

    @Test
    fun `non-integer JSON numbers are rejected`() {
        assertInvalidJson(
            """{"datalist":[0,10,20,30,40,50,60,70,80,90],"range":6.5,"result":[10,9,8,7,6,5,4,3,2,1]}""",
        )
        assertInvalidJson(
            """{"datalist":[0.5,10,20,30,40,50,60,70,80,90],"range":6,"result":[10,9,8,7,6,5,4,3,2,1]}""",
        )
    }

    @Test
    fun `extreme slider values -100 and 100 roundtrip exactly`() {
        val data = HiByMsebData(
            datalist = List(10) { if (it % 2 == 0) -100 else 100 },
            range = 100,
            result = List(10) { if (it % 2 == 0) 100 else -100 },
        )

        assertEquals(data, HiByMsebV1Codec.decode(HiByMsebV1Codec.encode(data)))
    }

    private fun assertInvalidJson(content: String) {
        assertThrows(IllegalArgumentException::class.java) {
            HiByMsebV1Codec.decodeFromJson(content)
        }
    }

    private fun sampleData() = HiByMsebData(
        datalist = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90),
        range = 6,
        result = listOf(10, 9, 8, 7, 6, 5, 4, 3, 2, 1),
    )
}
