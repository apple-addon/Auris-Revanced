package com.music.bitchord.playback.dsd

/** ALSA-compatible native DSD word layouts used by USB raw-data alternate settings. */
enum class NativeDsdPacking(val slotBytes: Int, val littleEndian: Boolean) {
    DSD_U32_BE(slotBytes = 4, littleEndian = false),
    DSD_U32_LE(slotBytes = 4, littleEndian = true),
    DSD_U16_LE(slotBytes = 2, littleEndian = true),
    ;

    val bitsPerSlot: Int get() = slotBytes * 8

    fun matchesSlot(subslotBytes: Int, bitResolution: Int): Boolean =
        subslotBytes == slotBytes && bitResolution == bitsPerSlot

    fun frameBytes(channels: Int): Int {
        require(channels in 1..255) { "DSD channel count must be in 1..255." }
        return slotBytes * channels
    }
}

/**
 * Packs native DSD without changing any DSD bit.
 *
 * The canonical form is chronological, byte-interleaved DSD: one byte for
 * channel 0, one for channel 1, and so on, then the next byte time. Packing
 * gathers 16 or 32 bits per channel and emits one interleaved word per
 * channel. Little-endian words reverse bytes within each channel's word;
 * endianness never reverses the bits inside a byte.
 */
object NativeDsdPacker {
    @JvmOverloads
    fun pack(
        canonicalDsd: ByteArray,
        channels: Int,
        packing: NativeDsdPacking,
        offset: Int = 0,
        length: Int = canonicalDsd.size - offset,
    ): ByteArray = convert(canonicalDsd, channels, packing, offset, length, pack = true)

    @JvmOverloads
    fun unpack(
        packedDsd: ByteArray,
        channels: Int,
        packing: NativeDsdPacking,
        offset: Int = 0,
        length: Int = packedDsd.size - offset,
    ): ByteArray = convert(packedDsd, channels, packing, offset, length, pack = false)

    private fun convert(
        input: ByteArray,
        channels: Int,
        packing: NativeDsdPacking,
        offset: Int,
        length: Int,
        pack: Boolean,
    ): ByteArray {
        checkedRange(input.size, offset, length)
        val frameBytes = packing.frameBytes(channels)
        require(length % frameBytes == 0) {
            "DSD block must contain complete ${packing.name} multichannel frames."
        }
        val output = ByteArray(length)
        val slotBytes = packing.slotBytes
        val nativeFrames = length / frameBytes
        for (frame in 0 until nativeFrames) {
            for (channel in 0 until channels) {
                val wordBase = offset + (frame * channels + channel) * slotBytes
                for (oldestByte in 0 until slotBytes) {
                    val canonicalIndex = offset + (frame * slotBytes + oldestByte) * channels + channel
                    val byteInWord = if (packing.littleEndian) slotBytes - 1 - oldestByte else oldestByte
                    val wordIndex = wordBase + byteInWord
                    if (pack) output[wordIndex - offset] = input[canonicalIndex]
                    else output[canonicalIndex - offset] = input[wordIndex]
                }
            }
        }
        return output
    }
}

private fun checkedRange(size: Int, offset: Int, length: Int) {
    require(offset >= 0 && length >= 0 && offset <= size - length) {
        "Invalid byte range: offset=$offset length=$length size=$size."
    }
}
