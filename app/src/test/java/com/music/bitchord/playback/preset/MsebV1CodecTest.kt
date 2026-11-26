package com.music.bitchord.playback.preset

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MsebV1CodecTest {
    private val dimensions = listOf(-100, -75, -50, -25, 0, 25, 50, 75, 100, 5)
    private val result = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    private val preset = MsebPreset(
        datalist = dimensions,
        range = 3,
        result = result,
        description = "line\n&='",
        device = "D\\1",
        name = "A\"<耳>",
    )

    @Test
    fun `golden Gson JSON and URI match field order and escaping`() {
        val json = "{\"datalist\":[-100,-75,-50,-25,0,25,50,75,100,5]," +
            "\"range\":3,\"result\":[1,2,3,4,5,6,7,8,9,10]," +
            "\"description\":\"line\\n\\u0026\\u003d\\u0027\"," +
            "\"device\":\"D\\\\1\",\"name\":\"A\\\"\\u003c耳\\u003e\"}"
        assertEquals(json, MsebV1Codec.toJson(preset))
        assertArrayEquals(json.toByteArray(Charsets.UTF_8), MsebV1Codec.toBytes(preset))
        assertEquals(MsebV1Codec.PREFIX + json, MsebV1Codec.generateContent(preset))
        assertEquals(preset, MsebV1Codec.decode(MsebV1Codec.PREFIX + json))
        assertEquals(json, MsebV1Codec.toJson(MsebV1Codec.decodeBytes(json.toByteArray())))
    }

    @Test
    fun `null inherited fields are omitted like Gson`() {
        val value = MsebPreset(List(10) { 0 }, 0, List(10) { 0 })
        assertEquals(
            "{\"datalist\":[0,0,0,0,0,0,0,0,0,0],\"range\":0," +
                "\"result\":[0,0,0,0,0,0,0,0,0,0]}",
            MsebV1Codec.toJson(value),
        )
        assertNull(value.name)
        assertNull(value.device)
        assertNull(value.description)
    }

    @Test
    fun `all ten entries and independent result list survive`() {
        val decoded = MsebV1Codec.decode(MsebV1Codec.generateContent(preset))
        assertEquals(dimensions, decoded.dimensions)
        assertEquals(result, decoded.result)
        assertEquals(3, decoded.range)
        assertFalse(decoded.result === decoded.datalist)
    }

    @Test
    fun `decoder accepts BOM whitespace unknown fields and explicit null metadata`() {
        val decoded = MsebV1Codec.decodeJson(
            "\uFEFF { \"future\":true, \"datalist\":[0,1,2,3,4,5,6,7,8,9]," +
                "\"range\":-7,\"result\":[9,8,7,6,5,4,3,2,1,0]," +
                "\"name\":null,\"device\":null,\"description\":null } ",
        )
        assertEquals(-7, decoded.range)
        assertEquals((0..9).toList(), decoded.datalist)
        assertEquals((9 downTo 0).toList(), decoded.result)
        assertNull(decoded.name)
    }

    @Test
    fun `Gson special escapes include controls separators and HTML characters`() {
        val value = preset.copy(name = "<>&='/\b\t\u0001\u2028\u2029")
        assertEquals(
            "\"\\u003c\\u003e\\u0026\\u003d\\u0027/\\b\\t\\u0001\\u2028\\u2029\"",
            MsebV1Codec.toJson(value).substringAfter("\"name\":").removeSuffix("}"),
        )
    }

    @Test
    fun `prefix detection is exact`() {
        assertTrue(MsebV1Codec.canDecode(MsebV1Codec.PREFIX + "{}"))
        assertFalse(MsebV1Codec.canDecode(MsebV1Codec.PREFIX.uppercase() + "{}"))
        malformed { MsebV1Codec.decode("") }
        malformed { MsebV1Codec.decode("hiby://plg_peq/v1/{}") }
    }

    @Test
    fun `malformed JSON and required field types fail closed`() {
        malformed { MsebV1Codec.decodeJson(" ") }
        malformed { MsebV1Codec.decodeJson("{") }
        malformed { MsebV1Codec.decodeJson("[]") }
        malformed { MsebV1Codec.decodeJson("{}") }
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"range\":3", "\"range\":\"3\"")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"range\":3", "\"range\":3.5")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"range\":3", "\"range\":null")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"name\":\"A\"", "\"name\":7")) }
        malformed { MsebV1Codec.decodeBytes(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test
    fun `missing null short and long dimensions fail closed`() {
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"range\":3,", "")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"datalist\":[0,1,2,3,4,5,6,7,8,9]", "\"datalist\":null")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("[0,1,2,3,4,5,6,7,8,9]", "[0,1,2]")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("[9,8,7,6,5,4,3,2,1,0]", "[9,8,7,6,5,4,3,2,1,0,11]")) }
        malformed { MsebPreset(List(9) { 0 }, 0, List(10) { 0 }) }
        malformed { MsebPreset(List(10) { 0 }, 0, List(11) { 0 }) }
    }

    @Test
    fun `unpaired Unicode surrogates are rejected before encoding`() {
        malformed { MsebV1Codec.toJson(preset.copy(name = "\uD800")) }
        malformed { MsebV1Codec.toJson(preset.copy(device = "\uDC00")) }
        malformed { MsebV1Codec.decodeJson(validJson().replace("\"name\":\"A\"", "\"name\":\"\\uD800\"")) }
    }

    private fun validJson(): String =
        "{\"datalist\":[0,1,2,3,4,5,6,7,8,9],\"range\":3," +
            "\"result\":[9,8,7,6,5,4,3,2,1,0],\"name\":\"A\"}"

    private fun malformed(block: () -> Unit) {
        try {
            block()
            fail("Expected PresetCodecException")
        } catch (_: PresetCodecException) {
        }
    }
}
