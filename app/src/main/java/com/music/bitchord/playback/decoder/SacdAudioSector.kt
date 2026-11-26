package com.music.bitchord.playback.decoder

import java.io.ByteArrayOutputStream

/** One compressed DST or uncompressed DSD audio frame, still in its source encoding. */
class SacdSourceFrame(
    val timecodeFrame: Long,
    val bytes: ByteArray,
    val dstEncoded: Boolean,
)

internal data class SacdFrameStart(
    val timecodeFrame: Long,
    val sectorCount: Int,
    val channelCount: Int,
)

internal data class SacdPacket(
    val frameStart: Boolean,
    val audio: Boolean,
    val payloadOffset: Int,
    val payloadSize: Int,
)

internal data class ParsedSacdSector(
    val dstEncoded: Boolean,
    val packets: List<SacdPacket>,
    val frameStarts: Map<Int, SacdFrameStart>,
)

/** Parses the packet and frame-info headers in one 2048-byte ScarletBook audio sector. */
internal fun parseSacdSector(sector: ByteArray, expectDst: Boolean): ParsedSacdSector {
    if (sector.size != SACD_SECTOR_BYTES) throw SacdFormatException("Incomplete SACD audio sector.")
    val header = sector[0].toInt() and 255
    val dst = header and 1 != 0
    if (dst != expectDst) throw SacdFormatException("Audio sector encoding differs from its area TOC.")
    val packetCount = header ushr 5
    val frameInfoCount = header ushr 2 and 7
    val frameInfoSize = if (dst) 4 else 3
    var cursor = 1 + packetCount * 2
    val frameInfoOffset = cursor
    cursor += frameInfoCount * frameInfoSize
    if (cursor > sector.size) throw SacdFormatException("SACD audio sector headers exceed the sector.")

    val packets = ArrayList<SacdPacket>(packetCount)
    val starts = HashMap<Int, SacdFrameStart>()
    var frameInfoIndex = 0
    for (packetIndex in 0 until packetCount) {
        val info = 1 + packetIndex * 2
        val first = sector[info].toInt() and 255
        val frameStart = first and 0x80 != 0
        val dataType = first ushr 3 and 7
        val payloadSize = (first and 7 shl 8) or (sector[info + 1].toInt() and 255)
        val audio = dataType == 2
        if (cursor + payloadSize > sector.size) throw SacdFormatException("SACD packet exceeds its sector.")
        if (frameStart && audio) {
            if (frameInfoIndex >= frameInfoCount) throw SacdFormatException("Missing SACD audio frame info.")
            val offset = frameInfoOffset + frameInfoIndex * frameInfoSize
            val timecode = sectorTimecode(sector, offset)
            var channels = 2
            var sectors = 1
            if (dst) {
                val flags = sector[offset + 3].toInt() and 255
                val channelBit3 = flags and 1
                val channelBit2 = flags ushr 1 and 1
                channels = if (channelBit2 == 1 && channelBit3 == 0) 6 else
                    if (channelBit2 == 0 && channelBit3 == 1) 5 else 2
                sectors = flags ushr 2 and 31
                if (sectors == 0) throw SacdFormatException("DST frame has a zero sector count.")
            }
            starts[packetIndex] = SacdFrameStart(timecode, sectors, channels)
            frameInfoIndex++
        } else if (frameStart) {
            throw SacdFormatException("A non-audio SACD packet is marked as a frame start.")
        }
        packets += SacdPacket(frameStart, audio, cursor, payloadSize)
        cursor += payloadSize
    }
    if (frameInfoIndex != frameInfoCount) throw SacdFormatException("Unused SACD audio frame info.")
    return ParsedSacdSector(dst, packets, starts)
}

/**
 * Reassembles audio packets across sectors. Starting at an indexed frame's sector
 * is safe: continuation packets before that frame are ignored, never guessed at.
 */
internal class SacdSectorFrameExtractor(
    private val channels: Int,
    private val dstEncoded: Boolean,
) {
    private class Pending(
        val timecodeFrame: Long,
        var remainingSectors: Int,
        val bytes: ByteArrayOutputStream,
    )

    private var pending: Pending? = null

    fun decode(sector: ByteArray): List<SacdSourceFrame> {
        val parsed = parseSacdSector(sector, dstEncoded)
        val frames = ArrayList<SacdSourceFrame>(2)
        for (packetIndex in parsed.packets.indices) {
            val packet = parsed.packets[packetIndex]
            val start = parsed.frameStarts[packetIndex]
            if (start != null) {
                pending?.let { frames += finishFrame(it) }
                if (dstEncoded && start.channelCount != channels) {
                    throw SacdFormatException("Audio frame channel count differs from its area TOC.")
                }
                val capacity = if (dstEncoded) 4_096 else SACD_BYTES_PER_CHANNEL_FRAME * channels
                pending = Pending(start.timecodeFrame, start.sectorCount, ByteArrayOutputStream(capacity))
            }
            if (!packet.audio) continue
            val current = pending ?: continue
            if (current.remainingSectors <= 0) throw SacdFormatException("DST frame exceeds its sector count.")
            val maximum = if (dstEncoded) MAX_DST_FRAME_BYTES else SACD_BYTES_PER_CHANNEL_FRAME * channels
            if (current.bytes.size() > maximum - packet.payloadSize) {
                throw SacdFormatException("SACD audio frame exceeds its permitted size.")
            }
            current.bytes.write(sector, packet.payloadOffset, packet.payloadSize)
            if (dstEncoded) current.remainingSectors--
        }
        return frames
    }

    fun finish(): List<SacdSourceFrame> {
        val current = pending ?: return emptyList()
        pending = null
        return listOf(finishFrame(current))
    }

    private fun finishFrame(frame: Pending): SacdSourceFrame {
        val bytes = frame.bytes.toByteArray()
        val complete = if (dstEncoded) frame.remainingSectors == 0 && bytes.isNotEmpty() else
            bytes.size == SACD_BYTES_PER_CHANNEL_FRAME * channels
        if (!complete) throw SacdFormatException("Incomplete SACD audio frame at timecode ${frame.timecodeFrame}.")
        pending = null
        return SacdSourceFrame(frame.timecodeFrame, bytes, dstEncoded)
    }

    private companion object {
        const val MAX_DST_FRAME_BYTES = 65_536
    }
}

private fun sectorTimecode(sector: ByteArray, offset: Int): Long {
    val minutes = sector[offset].toInt() and 255
    val seconds = sector[offset + 1].toInt() and 255
    val frames = sector[offset + 2].toInt() and 255
    if (seconds >= 60 || frames >= SACD_FRAME_RATE) throw SacdFormatException("Invalid audio frame timecode.")
    return minutes * 4_500L + seconds * 75L + frames
}
