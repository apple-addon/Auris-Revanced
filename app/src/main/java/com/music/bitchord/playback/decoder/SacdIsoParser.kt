package com.music.bitchord.playback.decoder

import java.io.IOException

const val SACD_SAMPLE_RATE: Int = 2_822_400
const val SACD_FRAME_RATE: Int = 75
const val SACD_SAMPLES_PER_FRAME: Long = 37_632L
const val SACD_BYTES_PER_CHANNEL_FRAME: Int = 4_704
internal const val SACD_SECTOR_BYTES: Int = 2_048

class SacdFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class SacdAreaKind { STEREO, MULTI_CHANNEL }

enum class SacdFrameFormat(val code: Int, val dstEncoded: Boolean) {
    DST(0, true),
    DSD_3_IN_14(2, false),
    DSD_3_IN_16(3, false),
}

/** A time position snapped down to the containing 1/75-second SACD audio frame. */
data class SacdSeekPoint(
    val relativeFrame: Int,
    val absoluteTimecodeFrame: Long,
    val timeUs: Long,
    val endOfTrack: Boolean,
)

data class SacdTrack(
    val index: Int,
    val startTimecodeFrame: Long,
    val durationFrames: Int,
    val sourceStartLsn: Long,
    /** Inclusive. It can include the next track's first sector to finish the last audio frame. */
    val sourceEndLsn: Long,
    val declaredStartLsn: Long,
    val declaredLengthLsn: Long,
) {
    init {
        require(index >= 0 && durationFrames > 0 && startTimecodeFrame >= 0)
        require(sourceStartLsn >= 0 && sourceEndLsn >= sourceStartLsn)
    }

    val durationUs: Long get() = durationFrames * 1_000_000L / SACD_FRAME_RATE
    val sampleCount: Long get() = durationFrames * SACD_SAMPLES_PER_FRAME

    fun seek(timeUs: Long): SacdSeekPoint {
        val frame = if (timeUs <= 0L) 0 else if (timeUs >= durationUs) durationFrames else
            (timeUs * SACD_FRAME_RATE / 1_000_000L).toInt().coerceAtMost(durationFrames)
        return SacdSeekPoint(
            relativeFrame = frame,
            absoluteTimecodeFrame = startTimecodeFrame + frame,
            timeUs = frame * 1_000_000L / SACD_FRAME_RATE,
            endOfTrack = frame == durationFrames,
        )
    }
}

data class SacdArea(
    val kind: SacdAreaKind,
    val tocStartLsn: Long,
    val tocSizeSectors: Int,
    val channelCount: Int,
    val frameFormat: SacdFrameFormat,
    val trackOffset: Int,
    val trackStartLsn: Long,
    val trackEndLsn: Long,
    val totalPlaytimeFrames: Long,
    val tracks: List<SacdTrack>,
)

data class SacdDisc(val areas: List<SacdArea>) {
    val stereoArea: SacdArea? get() = areas.firstOrNull { it.kind == SacdAreaKind.STEREO }
    val multiChannelArea: SacdArea? get() = areas.firstOrNull { it.kind == SacdAreaKind.MULTI_CHANNEL }
}

/**
 * ScarletBook master/area TOC and SACDTRL1/SACDTRL2 parser. All integers in these
 * structures are big-endian; timecodes are binary minute/second/75 Hz bytes.
 */
object SacdIsoParser {
    private const val MASTER_TOC_LSN = 510L

    fun parse(source: RandomAccessSource): SacdDisc {
        val masterOffset = checkedSectorOffset(MASTER_TOC_LSN)
        requireRange(source, masterOffset, SACD_SECTOR_BYTES, "Missing SACD master TOC.")
        val master = source.readBytes(masterOffset, SACD_SECTOR_BYTES)
        require(master.textAt(0, 8) == "SACDMTOC") { "Missing SACDMTOC signature." }
        checkVersion(master, "master")

        val areas = ArrayList<SacdArea>(2)
        parseMasterArea(master, source, areaNumber = 1)?.let(areas::add)
        parseMasterArea(master, source, areaNumber = 2)?.let(areas::add)
        require(areas.isNotEmpty()) { "The SACD image has no audio areas." }
        return SacdDisc(areas)
    }

    private fun parseMasterArea(master: ByteArray, source: RandomAccessSource, areaNumber: Int): SacdArea? {
        val startOffset = if (areaNumber == 1) 64 else 72
        val sizeOffset = if (areaNumber == 1) 84 else 86
        val primaryLsn = master.u32(startOffset)
        val backupLsn = master.u32(startOffset + 4)
        val tocLsn = if (primaryLsn != 0L) primaryLsn else backupLsn
        val sectors = master.u16(sizeOffset)
        if (tocLsn == 0L && sectors == 0) return null
        require(tocLsn > 0L && sectors in 2..96) { "Invalid audio area $areaNumber TOC range." }
        val tocBytes = checkedSectorBytes(sectors)
        val tocOffset = checkedSectorOffset(tocLsn)
        requireRange(source, tocOffset, tocBytes, "Truncated audio area TOC.")
        val toc = source.readBytes(tocOffset, tocBytes)
        return parseArea(toc, tocLsn, sectors, source.length, areaNumber)
    }

    private fun parseArea(
        toc: ByteArray,
        tocLsn: Long,
        declaredSectors: Int,
        sourceLength: Long,
        areaNumber: Int,
    ): SacdArea {
        val signature = toc.textAt(0, 8)
        val kind = when (signature) {
            "TWOCHTOC" -> SacdAreaKind.STEREO
            "MULCHTOC" -> SacdAreaKind.MULTI_CHANNEL
            else -> malformed("Invalid audio area $areaNumber signature.")
        }
        require((areaNumber == 1) == (kind == SacdAreaKind.STEREO)) { "Audio area is in the wrong master slot." }
        checkVersion(toc, "area")
        val tocSectors = toc.u16(10)
        require(tocSectors == declaredSectors) { "Area TOC size does not match the master TOC." }
        require(toc[20].toInt() and 255 == 4) { "Only DSD64 ScarletBook audio is supported." }
        val formatCode = toc[21].toInt() and 15
        val frameFormat = SacdFrameFormat.entries.firstOrNull { it.code == formatCode }
            ?: malformed("Unsupported ScarletBook frame format $formatCode.")
        val channels = toc[32].toInt() and 255
        require(if (kind == SacdAreaKind.STEREO) channels == 2 else channels in 3..6) {
            "Invalid channel count $channels for $signature."
        }
        val trackCount = toc[69].toInt() and 255
        require(trackCount in 1..255) { "Invalid SACD track count." }
        val trackStart = toc.u32(72)
        val trackEnd = toc.u32(76)
        require(trackStart > 0L && trackEnd >= trackStart) { "Invalid SACD track area." }
        val trackEndOffset = checkedSectorOffset(trackEnd + 1)
        require(trackEndOffset <= sourceLength) { "SACD track area exceeds the image." }

        var tracklistOffset = -1
        var timeListOffset = -1
        for (sector in 1 until declaredSectors) {
            val offset = sector * SACD_SECTOR_BYTES
            when (toc.textAt(offset, 8)) {
                "SACDTRL1" -> {
                    require(tracklistOffset < 0) { "Duplicate SACDTRL1 sector." }
                    tracklistOffset = offset
                }
                "SACDTRL2" -> {
                    require(timeListOffset < 0) { "Duplicate SACDTRL2 sector." }
                    timeListOffset = offset
                }
            }
        }
        require(tracklistOffset >= 0 && timeListOffset >= 0) { "Missing SACD track lists." }

        val starts = LongArray(trackCount)
        val lengths = LongArray(trackCount)
        for (index in 0 until trackCount) {
            starts[index] = toc.u32(tracklistOffset + 8 + index * 8)
            lengths[index] = toc.u32(tracklistOffset + 12 + index * 8)
            require(lengths[index] > 0L) { "Track ${index + 1} has no source sectors." }
        }
        val tracks = ArrayList<SacdTrack>(trackCount)
        var previousStartTime = -1L
        for (index in 0 until trackCount) {
            val timeEntry = timeListOffset + 8 + index * 4
            val durationEntry = timeListOffset + 8 + 255 * 4 + index * 4
            val startTime = timecodeFrames(toc, timeEntry, "track ${index + 1} start")
            val duration = timecodeFrames(toc, durationEntry, "track ${index + 1} duration")
            require(duration in 1L..Int.MAX_VALUE.toLong()) { "Track ${index + 1} has an invalid duration." }
            require(startTime >= previousStartTime) { "SACD track timecodes go backwards." }
            previousStartTime = startTime

            // The first entry can be zero on authored discs; the area header is authoritative.
            val sourceStart = if (index == 0) trackStart else starts[index]
            require(sourceStart in trackStart..trackEnd) { "Track ${index + 1} starts outside its area." }
            require(starts[index] == 0L || starts[index] in trackStart..trackEnd) {
                "Track ${index + 1} has an invalid declared start."
            }
            require(lengths[index] - 1 <= trackEnd - sourceStart) { "Track ${index + 1} exceeds its area." }
            val sourceEnd = if (index + 1 < trackCount) starts[index + 1] else trackEnd
            require(sourceEnd in sourceStart..trackEnd) { "Track ${index + 1} has an invalid source range." }
            tracks += SacdTrack(
                index = index,
                startTimecodeFrame = startTime,
                durationFrames = duration.toInt(),
                sourceStartLsn = sourceStart,
                sourceEndLsn = sourceEnd,
                declaredStartLsn = starts[index],
                declaredLengthLsn = lengths[index],
            )
        }
        return SacdArea(
            kind = kind,
            tocStartLsn = tocLsn,
            tocSizeSectors = declaredSectors,
            channelCount = channels,
            frameFormat = frameFormat,
            trackOffset = toc[68].toInt() and 255,
            trackStartLsn = trackStart,
            trackEndLsn = trackEnd,
            totalPlaytimeFrames = timecodeFrames(toc, 64, "area playtime"),
            tracks = tracks,
        )
    }

    private fun checkVersion(bytes: ByteArray, name: String) {
        val major = bytes[8].toInt() and 255
        val minor = bytes[9].toInt() and 255
        require(major == 1 && minor <= 20) { "Unsupported ScarletBook $name version $major.$minor." }
    }

    private fun timecodeFrames(bytes: ByteArray, offset: Int, name: String): Long {
        val minutes = bytes[offset].toInt() and 255
        val seconds = bytes[offset + 1].toInt() and 255
        val frames = bytes[offset + 2].toInt() and 255
        require(seconds < 60 && frames < SACD_FRAME_RATE) { "Invalid $name timecode." }
        return minutes * 4_500L + seconds * 75L + frames
    }

    private fun requireRange(source: RandomAccessSource, offset: Long, size: Int, message: String) {
        require(offset >= 0 && offset <= source.length && size.toLong() <= source.length - offset) { message }
    }

    private inline fun require(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw SacdFormatException(lazyMessage())
    }

    private fun malformed(message: String): Nothing = throw SacdFormatException(message)
}

internal fun checkedSectorOffset(lsn: Long): Long {
    if (lsn < 0 || lsn > Long.MAX_VALUE / SACD_SECTOR_BYTES) throw SacdFormatException("Invalid SACD sector number.")
    return lsn * SACD_SECTOR_BYTES
}

internal fun checkedSectorBytes(sectors: Int): Int {
    if (sectors < 0 || sectors > Int.MAX_VALUE / SACD_SECTOR_BYTES) throw SacdFormatException("Invalid SACD sector count.")
    return sectors * SACD_SECTOR_BYTES
}

internal fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 255 shl 8) or (this[offset + 1].toInt() and 255)

internal fun ByteArray.u32(offset: Int): Long =
    (this[offset].toLong() and 255 shl 24) or
        (this[offset + 1].toLong() and 255 shl 16) or
        (this[offset + 2].toLong() and 255 shl 8) or
        (this[offset + 3].toLong() and 255)

internal fun ByteArray.textAt(offset: Int, size: Int): String =
    String(this, offset, size, Charsets.US_ASCII)
