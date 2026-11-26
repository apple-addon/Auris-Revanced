package com.music.bitchord.playback.dsd.raw

import java.io.ByteArrayOutputStream

internal fun testBytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun makeDsf(
    sampleCount: Long,
    channelData: List<ByteArray>,
    bits: Int = 8,
    metadata: ByteArray = ByteArray(0),
): ByteArray {
    require(channelData.isNotEmpty() && channelData.all { it.size == (sampleCount + 7).toInt() / 8 })
    val channels = channelData.size
    val bytesPerChannel = (sampleCount + 7) / 8
    val blocks = (bytesPerChannel + 4095) / 4096
    val dataBytes = blocks * 4096 * channels
    val fileSize = 28L + 52L + 12L + dataBytes + metadata.size
    val out = ByteArrayOutputStream(fileSize.toInt())
    out.write("DSD ".toByteArray())
    out.write(leLong(28))
    out.write(leLong(fileSize))
    out.write(leLong(if (metadata.isEmpty()) 0 else fileSize - metadata.size))
    out.write("fmt ".toByteArray())
    out.write(leLong(52))
    out.write(leInt(1))
    out.write(leInt(0))
    out.write(leInt(when (channels) {
        1 -> 1
        2 -> 2
        3 -> 3
        4 -> 4
        5 -> 6
        6 -> 7
        else -> error("bad test channel count")
    }))
    out.write(leInt(channels))
    out.write(leInt(RawDsdConstants.DSD64_RATE))
    out.write(leInt(bits))
    out.write(leLong(sampleCount))
    out.write(leInt(4096))
    out.write(leInt(0))
    out.write("data".toByteArray())
    out.write(leLong(12 + dataBytes))
    for (block in 0 until blocks.toInt()) for (channel in 0 until channels) {
        val padded = ByteArray(4096)
        val sourceOffset = block * 4096
        val count = minOf(4096, channelData[channel].size - sourceOffset).coerceAtLeast(0)
        if (count > 0) channelData[channel].copyInto(padded, 0, sourceOffset, sourceOffset + count)
        out.write(padded)
    }
    out.write(metadata)
    return out.toByteArray().also { require(it.size.toLong() == fileSize) }
}

internal fun makePlainDff(canonical: ByteArray, channels: Int = 2, oddMetadataChunk: Boolean = false): ByteArray {
    val chunks = ArrayList<ByteArray>()
    chunks += dffChunk("FVER", testBytes(0x01, 0x05, 0x00, 0x00))
    chunks += dffChunk("PROP", dffProperties(channels, false))
    if (oddMetadataChunk) chunks += dffChunk("DIIN", testBytes(1, 2, 3))
    chunks += dffChunk("DSD ", canonical)
    return dffForm(chunks)
}

internal fun makeDstDff(encodedFrames: List<ByteArray>, channels: Int = 2, crcs: List<Int>? = null): ByteArray {
    require(encodedFrames.isNotEmpty() && (crcs == null || crcs.size == encodedFrames.size))
    val dst = ByteArrayOutputStream()
    dst.write(dffChunk("FRTE", beInt(encodedFrames.size) + testBytes(0, 75)))
    for (index in encodedFrames.indices) {
        dst.write(dffChunk("DSTF", encodedFrames[index]))
        if (crcs != null) dst.write(dffChunk("DSTC", beInt(crcs[index])))
    }
    return dffForm(listOf(
        dffChunk("FVER", testBytes(0x01, 0x05, 0x00, 0x00)),
        dffChunk("PROP", dffProperties(channels, true)),
        dffChunk("DST ", dst.toByteArray()),
    ))
}

private fun dffProperties(channels: Int, dst: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("SND ".toByteArray())
    out.write(dffChunk("FS  ", beInt(RawDsdConstants.DSD64_RATE)))
    val channelList = ByteArrayOutputStream()
    channelList.write(testBytes(channels shr 8, channels and 0xff))
    if (channels == 2) {
        channelList.write("SLFT".toByteArray())
        channelList.write("SRGT".toByteArray())
    } else {
        for (channel in 0 until channels) channelList.write("C%03d".format(channel).toByteArray())
    }
    out.write(dffChunk("CHNL", channelList.toByteArray()))
    val compression = if (dst) "DST " else "DSD "
    out.write(dffChunk("CMPR", compression.toByteArray() + testBytes(3) + "raw".toByteArray()))
    return out.toByteArray()
}

private fun dffForm(chunks: List<ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("FRM8".toByteArray())
    out.write(beLong(4L + chunks.sumOf { it.size }))
    out.write("DSD ".toByteArray())
    chunks.forEach(out::write)
    return out.toByteArray()
}

private fun dffChunk(id: String, payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(id.toByteArray())
    out.write(beLong(payload.size.toLong()))
    out.write(payload)
    if (payload.size % 2 != 0) out.write(0)
    return out.toByteArray()
}

internal data class SyntheticSacd(
    val image: ByteArray,
    val frames: List<ByteArray>,
    val trackStartLsns: List<Long>,
)

internal fun makeSyntheticSacd(): SyntheticSacd {
    val sectors = 549 + 28
    val image = ByteArray(sectors * 2048)
    image.putAscii(510 * 2048, "SACDMTOC")
    image[510 * 2048 + 8] = 1
    image[510 * 2048 + 9] = 20
    image.putBe32(510 * 2048 + 0x40, 544)

    val area = 544 * 2048
    image.putAscii(area, "TWOCHTOC")
    image[area + 8] = 1
    image[area + 9] = 20
    image.putBe16(area + 0x0a, 5)
    image[area + 0x14] = 4
    image[area + 0x15] = SacdFrameFormat.DSD_3_IN_14.code.toByte()
    image[area + 0x20] = 2
    image.putTimecode(area + 0x40, 6)
    image[area + 0x45] = 2
    image.putBe32(area + 0x48, 549)
    image.putBe32(area + 0x4c, 576)

    val offsets = 545 * 2048
    image.putAscii(offsets, "SACDTRL1")
    image.putBe32(offsets + 8, 549)
    image.putBe32(offsets + 12, 563)
    image.putBe32(offsets + 8 + 4 * 255, 14)
    image.putBe32(offsets + 8 + 4 * 255 + 4, 14)
    val times = 546 * 2048
    image.putAscii(times, "SACDTRL2")
    image.putTimecode(times + 8, 0)
    image.putTimecode(times + 12, 3)
    image.putTimecode(times + 8 + 4 * 255, 3)
    image.putTimecode(times + 8 + 4 * 255 + 4, 3)

    val frames = List(6) { frame ->
        ByteArray(4704 * 2) { index -> (frame * 41 + index * 17 + index / 23).toByte() }
    }
    for (group in 0 until 2) {
        val audio = muxThreeDsdFrames(frames.subList(group * 3, group * 3 + 3), group * 3)
        require(audio.size == 14)
        for (index in audio.indices) audio[index].copyInto(image, (549 + group * 14 + index) * 2048)
    }
    return SyntheticSacd(image, frames, listOf(549, 563))
}

/** Three stereo DSD frames carry exactly 28,224 bytes, spread over fourteen sectors. */
private fun muxThreeDsdFrames(frames: List<ByteArray>, firstFrame: Int): List<ByteArray> {
    require(frames.size == 3 && frames.all { it.size == 9408 })
    val sectors = ArrayList<ByteArray>(14)
    var frameIndex = 0
    var frameOffset = 0
    for (sectorIndex in 0 until 14) {
        var remaining = 2016
        val packets = ArrayList<Triple<Boolean, Int, ByteArray>>()
        while (remaining > 0) {
            require(frameIndex < 3) { "Too much SACD test audio." }
            val frame = frames[frameIndex]
            val count = minOf(remaining, frame.size - frameOffset)
            packets += Triple(frameOffset == 0, firstFrame + frameIndex, frame.copyOfRange(frameOffset, frameOffset + count))
            frameOffset += count
            remaining -= count
            if (frameOffset == frame.size) {
                frameIndex++
                frameOffset = 0
            }
        }
        val starts = packets.count { it.first }
        val sector = ByteArray(2048)
        sector[0] = ((packets.size shl 5) or (starts shl 2)).toByte()
        var packetInfo = 1
        var frameInfo = 1 + 2 * packets.size
        for ((start, time, data) in packets) {
            val info = (if (start) 0x8000 else 0) or (2 shl 11) or data.size
            sector.putBe16(packetInfo, info)
            packetInfo += 2
            if (start) {
                sector.putTimecode(frameInfo, time)
                frameInfo += 3
            }
        }
        for (packet in packets) {
            packet.third.copyInto(sector, frameInfo)
            frameInfo += packet.third.size
        }
        require(frameInfo <= 2048)
        sectors += sector
    }
    require(frameIndex == 3 && frameOffset == 0)
    return sectors
}

internal fun toRaw2064(userDataImage: ByteArray): ByteArray {
    require(userDataImage.size % 2048 == 0)
    val sectors = userDataImage.size / 2048
    val raw = ByteArray(sectors * 2064)
    for (sector in 0 until sectors) {
        userDataImage.copyInto(raw, sector * 2064 + 12, sector * 2048, (sector + 1) * 2048)
    }
    return raw
}

internal fun ByteArray.putTimecode(offset: Int, totalFrames: Int) {
    this[offset] = (totalFrames / 4500).toByte()
    this[offset + 1] = (totalFrames / 75 % 60).toByte()
    this[offset + 2] = (totalFrames % 75).toByte()
}

internal fun ByteArray.putAscii(offset: Int, value: String) {
    value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
}

internal fun ByteArray.putBe16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.putBe32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun beInt(value: Int): ByteArray = testBytes(value ushr 24, value ushr 16, value ushr 8, value)
private fun beLong(value: Long): ByteArray = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }
private fun leInt(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }
private fun leLong(value: Long): ByteArray = ByteArray(8) { (value ushr (8 * it)).toByte() }
