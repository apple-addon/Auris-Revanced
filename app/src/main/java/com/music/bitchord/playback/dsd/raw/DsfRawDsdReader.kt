package com.music.bitchord.playback.dsd.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DSF chunk parser and block reader. No PCM filtering or decimation is performed. */
internal class DsfRawDsdReader private constructor(
    private val input: RawDsdInput,
    override val format: RawDsdFormat,
    private val dataOffset: Long,
    private val dataEnd: Long,
    private val leastSignificantBitFirst: Boolean,
) : RawDsdReaderBase() {
    override val maxPacketBytes: Int = DSF_BLOCK_BYTES * format.channels
    override var nextSamplePosition: Long = 0L
        private set

    override fun locate(samplePosition: Long): RawDsdSeekPoint {
        if (samplePosition == format.sampleCount) {
            return RawDsdSeekPoint(samplePosition, format.durationUs, dataEnd, 0)
        }
        val block = samplePosition / SAMPLES_PER_BLOCK
        val aligned = block * SAMPLES_PER_BLOCK
        return RawDsdSeekPoint(
            aligned,
            format.timestampUs(aligned),
            dataOffset + block * maxPacketBytes,
            (samplePosition - aligned).toInt(),
        )
    }

    override fun moveTo(point: RawDsdSeekPoint) {
        nextSamplePosition = point.samplePosition
    }

    override fun readPacket(): RawDsdPacket? {
        val first = nextSamplePosition
        if (first == format.sampleCount) return null
        check(first in 0 until format.sampleCount && first % SAMPLES_PER_BLOCK == 0L) {
            "DSF reader is not on a block boundary."
        }
        val count = minOf(SAMPLES_PER_BLOCK, format.sampleCount - first).toInt()
        val validBytes = (count + 7) / 8
        val blockIndex = first / SAMPLES_PER_BLOCK
        val position = dataOffset + blockIndex * maxPacketBytes
        val source = input.readBytes(position, maxPacketBytes)
        val canonical = ByteArray(validBytes * format.channels)
        for (byte in 0 until validBytes) for (channel in 0 until format.channels) {
            val value = source[channel * DSF_BLOCK_BYTES + byte].toInt() and 0xff
            canonical[byte * format.channels + channel] = if (leastSignificantBitFirst) {
                (Integer.reverse(value) ushr 24).toByte()
            } else {
                value.toByte()
            }
        }
        nextSamplePosition = first + count
        return RawDsdPacketizer.packet(format, canonical, first, count)
    }

    companion object {
        private const val DSF_BLOCK_BYTES = 4_096
        private const val SAMPLES_PER_BLOCK = DSF_BLOCK_BYTES * 8L

        fun open(input: RawDsdInput, packing: RawDsdPacking): DsfRawDsdReader {
            val header = ByteBuffer.wrap(input.readBytes(0, 28)).order(ByteOrder.LITTLE_ENDIAN)
            require(ascii(header, 0) == "DSD " && header.getLong(4) == 28L) { "Invalid DSF header chunk." }
            val fileEnd = header.getLong(12)
            val metadataOffset = header.getLong(20)
            verifyDeclaredLength(input, fileEnd)
            require(fileEnd >= 80 && metadataOffset >= 0) { "Invalid DSF file or metadata range." }

            val fmtPosition = 28L
            val fmtHeader = ByteBuffer.wrap(input.readBytes(fmtPosition, 12)).order(ByteOrder.LITTLE_ENDIAN)
            require(ascii(fmtHeader, 0) == "fmt ") { "DSF is missing its fmt chunk." }
            val fmtSize = fmtHeader.getLong(4)
            require(fmtSize in 52..65_536) { "Invalid DSF fmt chunk size." }
            val dataHeaderPosition = checkedEnd(fmtPosition, fmtSize, fileEnd)
            val body = ByteBuffer.wrap(input.readBytes(fmtPosition + 12, fmtSize.toInt() - 12))
                .order(ByteOrder.LITTLE_ENDIAN)
            require(body.remaining() >= 40) { "Truncated DSF format description." }
            require(body.int == 1 && body.int == 0) { "Unsupported DSF format version or encoding." }
            val channelType = body.int
            val channels = body.int
            val expectedChannels = when (channelType) {
                1 -> 1
                2 -> 2
                3 -> 3
                4, 5 -> 4
                6 -> 5
                7 -> 6
                else -> 0
            }
            require(channels in 1..6 && channels == expectedChannels) { "Invalid DSF channel layout." }
            val rate = body.int
            val bits = body.int
            require(bits == 1 || bits == 8) { "Invalid DSF bit order declaration." }
            val samples = body.long
            val blockBytes = body.int
            require(blockBytes == DSF_BLOCK_BYTES && body.int == 0) { "Unsupported DSF block layout." }

            val dataHeader = ByteBuffer.wrap(input.readBytes(dataHeaderPosition, 12)).order(ByteOrder.LITTLE_ENDIAN)
            require(ascii(dataHeader, 0) == "data") { "DSF is missing its data chunk." }
            val dataSize = dataHeader.getLong(4)
            require(dataSize >= 12) { "Invalid DSF data chunk size." }
            val dataOffset = dataHeaderPosition + 12
            val dataBytes = dataSize - 12
            val dataEnd = checkedEnd(dataOffset, dataBytes, fileEnd)
            require(if (metadataOffset == 0L) dataEnd == fileEnd else metadataOffset == dataEnd && dataEnd < fileEnd) {
                "Invalid DSF metadata offset or trailing data."
            }
            val format = RawDsdFormat(RawDsdContainer.DSF, rate, channels, samples, packing)
            val bytesPerChannel = (samples + 7) / 8
            val expectedBytes = ((bytesPerChannel + DSF_BLOCK_BYTES - 1) / DSF_BLOCK_BYTES) *
                DSF_BLOCK_BYTES.toLong() * channels
            require(dataBytes == expectedBytes) { "DSF sample count does not match its padded channel blocks." }
            return DsfRawDsdReader(input, format, dataOffset, dataEnd, bits == 1)
        }

        private fun ascii(buffer: ByteBuffer, offset: Int): String {
            val bytes = ByteArray(4)
            for (index in bytes.indices) bytes[index] = buffer.get(offset + index)
            return String(bytes, Charsets.US_ASCII)
        }
    }
}
