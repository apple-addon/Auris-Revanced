package com.music.bitchord.playback.decoder

/** Raw interleaved MSB-first DSD bytes. No PCM representation is created here. */
class RawDsdFrame(
    val bytes: ByteArray,
    val channels: Int,
    val bitRate: Int,
    val firstSample: Long,
    val sampleCount: Long,
    val sourceTimecodeFrame: Long,
) {
    init {
        require(channels in 1..8 && bitRate > 0 && firstSample >= 0 && sampleCount > 0 && sampleCount % 8 == 0L)
        val expected = sampleCount / 8 * channels
        require(expected <= Int.MAX_VALUE && bytes.size == expected.toInt()) { "Raw DSD frame size is invalid." }
    }

    val timeUs: Long get() = firstSample * 1_000_000L / bitRate
    val durationUs: Long get() = sampleCount * 1_000_000L / bitRate
}

data class IndexedSacdSeekPoint(
    val point: SacdSeekPoint,
    /** Null only for the end-of-track position. */
    val sourceLsn: Long?,
)

/**
 * Exact audio-frame-to-sector index for one track. DST's variable bit rate makes
 * a sector calculated from elapsed time only an approximation; this index is not.
 */
class SacdTrackIndex internal constructor(
    private val track: SacdTrack,
    private val sectors: LongArray,
) {
    val indexedFrameCount: Int get() = sectors.count { it >= 0 }
    val isComplete: Boolean get() = indexedFrameCount == track.durationFrames

    fun seek(timeUs: Long): IndexedSacdSeekPoint {
        val point = track.seek(timeUs)
        if (point.endOfTrack) return IndexedSacdSeekPoint(point, null)
        val lsn = sectors[point.relativeFrame]
        if (lsn < 0) throw SacdFormatException("Track has no indexed audio frame ${point.relativeFrame}.")
        return IndexedSacdSeekPoint(point, lsn)
    }

    companion object {
        fun build(source: RandomAccessSource, area: SacdArea, track: SacdTrack): SacdTrackIndex {
            require(area.tracks.any { it === track }) { "Track does not belong to the supplied area." }
            val sectors = LongArray(track.durationFrames) { -1L }
            val endTimecode = track.startTimecodeFrame + track.durationFrames
            val sector = ByteArray(SACD_SECTOR_BYTES)
            for (lsn in track.sourceStartLsn..track.sourceEndLsn) {
                source.readFully(checkedSectorOffset(lsn), sector)
                val parsed = parseSacdSector(sector, area.frameFormat.dstEncoded)
                for ((_, start) in parsed.frameStarts) {
                    if (area.frameFormat.dstEncoded && start.channelCount != area.channelCount) {
                        throw SacdFormatException("Audio frame channel count differs from its area TOC.")
                    }
                    if (start.timecodeFrame < track.startTimecodeFrame || start.timecodeFrame >= endTimecode) continue
                    val relative = (start.timecodeFrame - track.startTimecodeFrame).toInt()
                    if (sectors[relative] >= 0) throw SacdFormatException("Duplicate SACD audio timecode ${start.timecodeFrame}.")
                    sectors[relative] = lsn
                }
            }
            return SacdTrackIndex(track, sectors)
        }
    }
}

/**
 * Streaming raw-DSD track reader. DST is decompressed losslessly to DSD bytes;
 * neither this class nor its decoder performs DSD-to-PCM decimation.
 */
class SacdTrackReader(
    private val source: RandomAccessSource,
    private val area: SacdArea,
    private val track: SacdTrack,
    private val dstDecoderFactory: ((channels: Int) -> DstFrameDecoder)? = null,
) : AutoCloseable {
    private var extractor = SacdSectorFrameExtractor(area.channelCount, area.frameFormat.dstEncoded)
    private var dstDecoder: DstFrameDecoder? = null
    private var nextLsn = track.sourceStartLsn
    private var nextRelativeFrame = 0
    private var sourceFinished = false
    private val ready = ArrayDeque<SacdSourceFrame>()

    init {
        require(area.tracks.any { it === track }) { "Track does not belong to the supplied area." }
        if (area.frameFormat.dstEncoded) {
            requireNotNull(dstDecoderFactory) { "DST playback requires a real DST frame decoder." }
            dstDecoder = dstDecoderFactory.invoke(area.channelCount)
        }
    }

    fun seekToStart() = reset(0, track.sourceStartLsn)

    fun seek(indexed: IndexedSacdSeekPoint) {
        val point = indexed.point
        require(point.relativeFrame in 0..track.durationFrames)
        require(point.absoluteTimecodeFrame == track.startTimecodeFrame + point.relativeFrame)
        require(point.endOfTrack == (point.relativeFrame == track.durationFrames))
        if (point.endOfTrack) {
            reset(track.durationFrames, track.sourceEndLsn)
            sourceFinished = true
        } else {
            val lsn = requireNotNull(indexed.sourceLsn)
            require(lsn in track.sourceStartLsn..track.sourceEndLsn)
            reset(point.relativeFrame, lsn)
        }
    }

    /** Returns the next 1/75-second raw DSD frame, or null only at the declared track end. */
    fun readFrame(): RawDsdFrame? {
        if (nextRelativeFrame == track.durationFrames) return null
        while (true) {
            val frame = ready.removeFirstOrNull()
            if (frame != null) {
                val accepted = acceptFrame(frame)
                if (accepted != null) return accepted
                continue
            }
            if (sourceFinished) {
                if (nextRelativeFrame != track.durationFrames) {
                    throw SacdFormatException("Track audio ended at frame $nextRelativeFrame of ${track.durationFrames}.")
                }
                return null
            }
            if (nextLsn > track.sourceEndLsn) {
                ready += extractor.finish()
                sourceFinished = true
                continue
            }
            val sector = source.readBytes(checkedSectorOffset(nextLsn++), SACD_SECTOR_BYTES)
            ready += extractor.decode(sector)
        }
    }

    private fun acceptFrame(frame: SacdSourceFrame): RawDsdFrame? {
        val expectedTimecode = track.startTimecodeFrame + nextRelativeFrame
        if (frame.timecodeFrame < expectedTimecode) return null
        if (frame.timecodeFrame != expectedTimecode) {
            throw SacdFormatException("Missing SACD audio frame at timecode $expectedTimecode.")
        }
        val raw = if (area.frameFormat.dstEncoded) {
            checkNotNull(dstDecoder).decode(frame.bytes)
        } else {
            frame.bytes
        }
        val expectedBytes = SACD_BYTES_PER_CHANNEL_FRAME * area.channelCount
        if (raw.size != expectedBytes) throw SacdFormatException("DST decoder returned ${raw.size} bytes, expected $expectedBytes.")
        val relative = nextRelativeFrame++
        return RawDsdFrame(
            bytes = raw,
            channels = area.channelCount,
            bitRate = SACD_SAMPLE_RATE,
            firstSample = relative * SACD_SAMPLES_PER_FRAME,
            sampleCount = SACD_SAMPLES_PER_FRAME,
            sourceTimecodeFrame = frame.timecodeFrame,
        )
    }

    private fun reset(relativeFrame: Int, lsn: Long) {
        ready.clear()
        extractor = SacdSectorFrameExtractor(area.channelCount, area.frameFormat.dstEncoded)
        nextRelativeFrame = relativeFrame
        nextLsn = lsn
        sourceFinished = false
        if (area.frameFormat.dstEncoded) {
            dstDecoder?.close()
            dstDecoder = checkNotNull(dstDecoderFactory).invoke(area.channelCount)
        }
    }

    override fun close() {
        dstDecoder?.close()
        dstDecoder = null
        ready.clear()
    }
}
