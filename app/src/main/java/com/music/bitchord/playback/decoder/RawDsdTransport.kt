package com.music.bitchord.playback.decoder

import java.nio.ByteOrder

object DsdTransport {
    /** DoP carries 16 DSD bits in each 24-bit carrier sample. */
    fun dopCarrierRate(dsdBitRate: Int): Int {
        require(dsdBitRate > 0 && dsdBitRate % 16 == 0) { "Invalid DSD bit rate." }
        return dsdBitRate / 16
    }

    /** UAC2 raw DSD carries 32 DSD bits in each 32-bit slot. */
    fun rawUac2CarrierRate(dsdBitRate: Int): Int {
        require(dsdBitRate > 0 && dsdBitRate % 32 == 0) { "Invalid DSD bit rate." }
        return dsdBitRate / 32
    }
}

/**
 * DoP 1.1 packer for interleaved, MSB-first DSD bytes. The oldest byte occupies
 * bits 15..8 of each 24-bit word and the marker occupies bits 23..16. Marker
 * phase continues across calls so arbitrary transport chunking is lossless.
 */
class DopPacker(private val channels: Int) {
    private var marker = MARKERS[0]

    init {
        require(channels in 1..8)
    }

    val nextMarker: Int get() = marker.toInt() and 255

    fun reset(firstMarker: Int = 0x05) {
        require(firstMarker == 0x05 || firstMarker == 0xFA)
        marker = firstMarker.toByte()
    }

    fun pack(
        input: ByteArray,
        offset: Int = 0,
        size: Int = input.size - offset,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): ByteArray {
        require(offset >= 0 && size >= 0 && offset <= input.size - size)
        val bytesPerFrame = channels * 2
        require(size % bytesPerFrame == 0) { "DoP input ends in a partial 16-bit channel sample." }
        val output = ByteArray(size / 2 * 3)
        var source = offset
        var destination = 0
        while (source < offset + size) {
            val currentMarker = marker
            repeat(channels) {
                // Two interleaved PCM byte slots precede this channel's two DSD bytes.
                val oldest = input[source]
                val newest = input[source + channels]
                if (byteOrder == ByteOrder.BIG_ENDIAN) {
                    output[destination++] = currentMarker
                    output[destination++] = oldest
                    output[destination++] = newest
                } else {
                    output[destination++] = newest
                    output[destination++] = oldest
                    output[destination++] = currentMarker
                }
                source++
            }
            source += channels
            marker = if (currentMarker == MARKERS[0]) MARKERS[1] else MARKERS[0]
        }
        return output
    }

    private companion object {
        val MARKERS = byteArrayOf(0x05, 0xFA.toByte())
    }
}

/**
 * Native UAC2 RAW/DSD 32-bit-slot packer. Four oldest-to-newest DSD bytes form
 * one 32-bit word; BIG_ENDIAN produces DSD_U32_BE and LITTLE_ENDIAN DSD_U32_LE.
 * There are no markers, padding bytes, volume operations, or PCM conversion.
 */
class RawDsdUac2Packer(private val channels: Int) {
    init {
        require(channels in 1..8)
    }

    fun pack(
        input: ByteArray,
        offset: Int = 0,
        size: Int = input.size - offset,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): ByteArray {
        require(offset >= 0 && size >= 0 && offset <= input.size - size)
        require(size % (channels * 4) == 0) { "Raw DSD input ends in a partial 32-bit channel slot." }
        val output = ByteArray(size)
        var source = offset
        var destination = 0
        while (source < offset + size) {
            repeat(channels) {
                // Gather four interleaved bytes into this channel's contiguous slot.
                val oldest = input[source]
                val second = input[source + channels]
                val third = input[source + 2 * channels]
                val newest = input[source + 3 * channels]
                if (byteOrder == ByteOrder.BIG_ENDIAN) {
                    output[destination] = oldest
                    output[destination + 1] = second
                    output[destination + 2] = third
                    output[destination + 3] = newest
                } else {
                    output[destination] = newest
                    output[destination + 1] = third
                    output[destination + 2] = second
                    output[destination + 3] = oldest
                }
                source++
                destination += 4
            }
            source += 3 * channels
        }
        return output
    }
}
