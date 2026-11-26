package com.music.bitchord.playback.preset

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * HiBy `PluginDataManager.MsebData`, including the inherited BaseData fields.
 *
 * `datalist` and `result` are both retained because both are present on the wire. The ten entries
 * use HiBy's native order: master_temp, bass0, bass1, thickness, vocal, female, male_vocal,
 * female_vocal, instruments, air. `range` is the MSEB range-model value, not a preamp.
 *
 * MSEB v1 has no preamp JSON field. Native MSEB preamp must be stored and applied separately;
 * this codec deliberately does not invent a non-interoperable `pre_amp` property.
 */
data class MsebPreset(
    val datalist: List<Int>,
    val range: Int,
    val result: List<Int>,
    val description: String? = null,
    val device: String? = null,
    val name: String? = null,
) {
    init {
        requireDimensions(datalist, "datalist")
        requireDimensions(result, "result")
    }

    val dimensions: List<Int> get() = datalist

    companion object {
        internal fun requireDimensions(values: List<Int>, field: String) {
            if (values.size != MsebV1Codec.DIMENSION_COUNT) {
                throw PresetCodecException(
                    "MSEB $field must contain exactly ${MsebV1Codec.DIMENSION_COUNT} entries",
                )
            }
        }
    }
}

/** Exact structural codec for `hiby://plg_mseb/v1/`. */
object MsebV1Codec {
    const val PREFIX = "hiby://plg_mseb/v1/"
    const val DIMENSION_COUNT = 10

    fun canDecode(content: String): Boolean = content.startsWith(PREFIX)

    /** Gson-compatible compact JSON, including its field order, null omission and HTML escaping. */
    fun toJson(preset: MsebPreset): String {
        MsebPreset.requireDimensions(preset.datalist, "datalist")
        MsebPreset.requireDimensions(preset.result, "result")
        return buildString {
            append('{')
            appendIntArray("datalist", preset.datalist)
            append(",\"range\":").append(preset.range)
            append(',')
            appendIntArray("result", preset.result)
            appendOptionalString("description", preset.description)
            appendOptionalString("device", preset.device)
            appendOptionalString("name", preset.name)
            append('}')
        }
    }

    fun toBytes(preset: MsebPreset): ByteArray = toJson(preset).toByteArray(Charsets.UTF_8)

    fun generateContent(preset: MsebPreset): String = PREFIX + toJson(preset)

    fun decode(content: String): MsebPreset {
        if (content.isEmpty()) throw PresetCodecException("MSEB content is empty")
        if (!canDecode(content)) throw PresetCodecException("MSEB content has the wrong prefix")
        return decodeJson(content.substring(PREFIX.length))
    }

    fun decodeBytes(bytes: ByteArray): MsebPreset =
        decodeJson(WireText.decode(bytes, field = "MSEB JSON"))

    /**
     * Strict JSON input, unlike Gson's legacy lenient fallback. Unknown object fields are ignored
     * just as they are by Gson; malformed or missing required fields fail closed.
     */
    fun decodeJson(json: String): MsebPreset {
        if (json.isBlank()) throw PresetCodecException("MSEB JSON is empty")
        val root = try {
            // Gson accepts a leading UTF-8 BOM.
            Json.parseToJsonElement(if (json.startsWith('\uFEFF')) json.substring(1) else json)
        } catch (error: SerializationException) {
            throw PresetCodecException("Invalid MSEB v1 JSON", error)
        } catch (error: IllegalArgumentException) {
            throw PresetCodecException("Invalid MSEB v1 JSON", error)
        }
        val obj = root as? JsonObject ?: throw PresetCodecException("MSEB JSON root must be an object")
        return MsebPreset(
            datalist = requiredIntArray(obj, "datalist"),
            range = requiredInt(obj, "range"),
            result = requiredIntArray(obj, "result"),
            description = optionalString(obj, "description"),
            device = optionalString(obj, "device"),
            name = optionalString(obj, "name"),
        )
    }

    private fun requiredIntArray(obj: JsonObject, field: String): List<Int> {
        val array = obj[field] as? JsonArray
            ?: throw PresetCodecException("MSEB $field must be an array")
        if (array.size != DIMENSION_COUNT) {
            throw PresetCodecException("MSEB $field must contain exactly $DIMENSION_COUNT entries")
        }
        return array.mapIndexed { index, element -> requiredInt(element, "$field[$index]") }
    }

    private fun requiredInt(obj: JsonObject, field: String): Int =
        requiredInt(obj[field] ?: throw PresetCodecException("MSEB $field is missing"), field)

    private fun requiredInt(element: JsonElement, field: String): Int {
        val number = element as? JsonPrimitive
            ?: throw PresetCodecException("MSEB $field must be an integer")
        if (number.isString || number is JsonNull || number.intOrNull == null) {
            throw PresetCodecException("MSEB $field must be a 32-bit integer")
        }
        return number.intOrNull!!
    }

    private fun optionalString(obj: JsonObject, field: String): String? {
        val element = obj[field] ?: return null
        if (element is JsonNull) return null
        val string = element as? JsonPrimitive
        if (string == null || !string.isString) {
            throw PresetCodecException("MSEB $field must be a string or null")
        }
        WireText.validate(string.content, field)
        return string.content
    }

    private fun StringBuilder.appendIntArray(field: String, values: List<Int>) {
        append('"').append(field).append("\":[")
        values.forEachIndexed { index, value ->
            if (index != 0) append(',')
            append(value)
        }
        append(']')
    }

    private fun StringBuilder.appendOptionalString(field: String, value: String?) {
        if (value == null) return
        WireText.validate(value, field)
        append(',')
        appendJsonString(field)
        append(':')
        appendJsonString(value)
    }

    /** Gson's default JsonWriter replacements, including HTML-safe and U+2028/U+2029 escapes. */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u000C' -> append("\\f")
                '<', '>', '&', '=', '\'', '\u2028', '\u2029' -> appendUnicodeEscape(ch)
                else -> if (ch <= '\u001F') appendUnicodeEscape(ch) else append(ch)
            }
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(ch: Char) {
        append("\\u")
        append(ch.code.toString(16).padStart(4, '0'))
    }
}
