package com.music.bitchord.playback.preset

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A malformed or unsupported preset. No partially decoded value is returned. */
class PresetCodecException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object WireText {
    fun validate(value: String, field: String) {
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        throw PresetCodecException("$field contains an unpaired high surrogate")
                    }
                    index++
                }
                Character.isLowSurrogate(ch) ->
                    throw PresetCodecException("$field contains an unpaired low surrogate")
            }
            index++
        }
    }

    fun encode(value: String, field: String, maximumBytes: Int? = null): ByteArray {
        validate(value, field)
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (maximumBytes != null && bytes.size > maximumBytes) {
            throw PresetCodecException("$field is ${bytes.size} UTF-8 bytes; maximum is $maximumBytes")
        }
        return bytes
    }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size, field: String): String {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw PresetCodecException("$field is outside the input")
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (error: CharacterCodingException) {
            throw PresetCodecException("$field is not valid UTF-8", error)
        }
    }
}
