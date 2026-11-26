package com.music.bitchord.playback.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class HiByMsebData(
    val datalist: List<Int>,
    val range: Int,
    val result: List<Int>,
) {
    init {
        requireDimensions(datalist, result)
    }
}

/** Encoder and decoder for hiby://plg_mseb/v1/ shares. */
object HiByMsebV1Codec {
    const val URI_PREFIX = "hiby://plg_mseb/v1/"
    const val DIMENSION_COUNT = 10

    private val json = Json { ignoreUnknownKeys = false }
    private val expectedKeys = setOf("datalist", "range", "result")

    fun encode(data: HiByMsebData): String = URI_PREFIX + encodeToJson(data)

    fun decode(uri: String): HiByMsebData {
        require(uri.startsWith(URI_PREFIX)) { "Not a HiBy MSEB v1 URI" }
        val payload = uri.substring(URI_PREFIX.length)
        require(payload.isNotEmpty()) { "MSEB payload is empty" }
        return decodeFromJson(payload)
    }

    /** Property declaration order is the HiBy wire order: datalist, range, result. */
    fun encodeToJson(data: HiByMsebData): String {
        requireDimensions(data.datalist, data.result)
        return json.encodeToString(HiByMsebData.serializer(), data)
    }

    fun decodeFromJson(content: String): HiByMsebData {
        try {
            val element = json.parseToJsonElement(content)
            val objectElement = element as? JsonObject
                ?: throw IllegalArgumentException("MSEB JSON must be an object")
            require(objectElement.keys == expectedKeys) {
                "MSEB JSON must contain exactly datalist, range, and result"
            }
            return json.decodeFromJsonElement(HiByMsebData.serializer(), objectElement).also {
                requireDimensions(it.datalist, it.result)
            }
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Malformed MSEB JSON", error)
        }
    }
}

private fun requireDimensions(datalist: List<Int>, result: List<Int>) {
    require(datalist.size == HiByMsebV1Codec.DIMENSION_COUNT) {
        "MSEB datalist must contain exactly ${HiByMsebV1Codec.DIMENSION_COUNT} dimensions"
    }
    require(result.size == HiByMsebV1Codec.DIMENSION_COUNT) {
        "MSEB result must contain exactly ${HiByMsebV1Codec.DIMENSION_COUNT} dimensions"
    }
}
