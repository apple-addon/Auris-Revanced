package com.music.bitchord.playback.decoder

internal class SacdTestImage(
    val bytes: ByteArray,
    val sourceFrames: Map<Long, ByteArray>,
) {
    fun source(): RandomAccessSource = ByteArrayRandomAccessSource(bytes)

    companion object {
        private const val AREA_LSN = 600
        private const val TRACK_LSN = 610

        fun create(
            format: SacdFrameFormat = SacdFrameFormat.DSD_3_IN_16,
            durations: IntArray = intArrayOf(2, 3),
            withAudio: Boolean = true,
        ): SacdTestImage {
            require(durations.isNotEmpty() && durations.all { it > 0 })
            val image = ByteArray(1_024 * SACD_SECTOR_BYTES)
            val sourceFrames = LinkedHashMap<Long, ByteArray>()
            val starts = LongArray(durations.size)
            val lengths = LongArray(durations.size)
            val timeStarts = LongArray(durations.size)
            var lsn = TRACK_LSN
            var timecode = 0L
            for (track in durations.indices) {
                starts[track] = lsn.toLong()
                timeStarts[track] = timecode
                val trackStart = lsn
                repeat(if (withAudio) durations[track] else 1) { frameInTrack ->
                    if (withAudio) {
                        val bytes = if (format.dstEncoded) {
                            ByteArray(32) { (timecode * 11 + it).toByte() }
                        } else {
                            ByteArray(SACD_BYTES_PER_CHANNEL_FRAME * 2) { (timecode * 17 + it * 3).toByte() }
                        }
                        sourceFrames[timecode] = bytes
                        lsn = writeFrame(image, lsn, timecode, bytes, format.dstEncoded)
                    } else {
                        lsn++
                    }
                    if (withAudio) timecode++
                }
                lengths[track] = (lsn - trackStart).toLong()
                if (!withAudio) timecode += durations[track]
            }
            val trackEnd = lsn - 1
            writeMaster(image)
            writeArea(image, format, starts, lengths, timeStarts, durations, trackEnd)
            return SacdTestImage(image, sourceFrames)
        }

        fun expectedRaw(timecode: Long, compressed: ByteArray): ByteArray =
            ByteArray(SACD_BYTES_PER_CHANNEL_FRAME * 2) { (compressed[it % compressed.size].toInt() xor 0x5a).toByte() }

        private fun writeMaster(image: ByteArray) {
            val offset = 510 * SACD_SECTOR_BYTES
            image.putText(offset, "SACDMTOC")
            image[offset + 8] = 1
            image[offset + 9] = 20
            image.putU32(offset + 64, AREA_LSN.toLong())
            image.putU16(offset + 84, 3)
        }

        private fun writeArea(
            image: ByteArray,
            format: SacdFrameFormat,
            starts: LongArray,
            lengths: LongArray,
            timeStarts: LongArray,
            durations: IntArray,
            trackEnd: Int,
        ) {
            val offset = AREA_LSN * SACD_SECTOR_BYTES
            image.putText(offset, "TWOCHTOC")
            image[offset + 8] = 1
            image[offset + 9] = 20
            image.putU16(offset + 10, 3)
            image[offset + 20] = 4
            image[offset + 21] = format.code.toByte()
            image[offset + 32] = 2
            image[offset + 69] = durations.size.toByte()
            image.putU32(offset + 72, TRACK_LSN.toLong())
            image.putU32(offset + 76, trackEnd.toLong())
            putTimecode(image, offset + 64, timeStarts.last() + durations.last())
            val trl1 = offset + SACD_SECTOR_BYTES
            val trl2 = offset + 2 * SACD_SECTOR_BYTES
            image.putText(trl1, "SACDTRL1")
            image.putText(trl2, "SACDTRL2")
            for (index in durations.indices) {
                image.putU32(trl1 + 8 + index * 8, starts[index])
                image.putU32(trl1 + 12 + index * 8, lengths[index])
                putTimecode(image, trl2 + 8 + index * 4, timeStarts[index])
                putTimecode(image, trl2 + 8 + 255 * 4 + index * 4, durations[index].toLong())
            }
        }

        /** Returns the first free LSN after this frame. */
        private fun writeFrame(
            image: ByteArray,
            firstLsn: Int,
            timecode: Long,
            bytes: ByteArray,
            dst: Boolean,
        ): Int {
            var lsn = firstLsn
            var source = 0
            var first = true
            do {
                val count = if (dst) bytes.size else minOf(2_000, bytes.size - source)
                val sector = lsn * SACD_SECTOR_BYTES
                val frameInfoCount = if (first) 1 else 0
                image[sector] = (0x20 or (frameInfoCount shl 2) or if (dst) 1 else 0).toByte()
                image[sector + 1] = ((if (first) 0x80 else 0) or 0x10 or (count ushr 8 and 7)).toByte()
                image[sector + 2] = count.toByte()
                var payload = sector + 3
                if (first) {
                    putTimecode(image, payload, timecode)
                    payload += 3
                    if (dst) image[payload++] = (1 shl 2).toByte()
                }
                bytes.copyInto(image, payload, source, source + count)
                source += count
                first = false
                lsn++
            } while (source < bytes.size)
            return lsn
        }

        private fun putTimecode(bytes: ByteArray, offset: Int, frameCount: Long) {
            bytes[offset] = (frameCount / 4_500).toByte()
            bytes[offset + 1] = (frameCount / 75 % 60).toByte()
            bytes[offset + 2] = (frameCount % 75).toByte()
        }
    }
}

internal fun ByteArray.putText(offset: Int, text: String) {
    val bytes = text.toByteArray(Charsets.US_ASCII)
    bytes.copyInto(this, offset)
}

internal fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.putU32(offset: Int, value: Long) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
