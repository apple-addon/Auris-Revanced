package com.music.bitchord.playback.dsd.raw

import java.io.EOFException

/** Scarlet Book sector layouts accepted by the parser (user data and raw 2064-byte sectors). */
data class SacdSectorLayout(val sectorSize: Int, val dataOffset: Int) {
    init {
        require((sectorSize == 2_048 && dataOffset == 0) || (sectorSize == 2_064 && dataOffset == 12)) {
            "Unsupported SACD sector layout."
        }
    }

    fun dataPosition(lsn: Long): Long {
        require(lsn >= 0 && lsn <= (Long.MAX_VALUE - dataOffset) / sectorSize) { "Invalid SACD LSN." }
        return lsn * sectorSize + dataOffset
    }
}

enum class SacdAreaKind(val signature: String) {
    STEREO("TWOCHTOC"),
    MULTI_CHANNEL("MULCHTOC"),
}

enum class SacdFrameFormat(val code: Int, val dst: Boolean, val sectorsPerThreeFrames: Int) {
    DST(0, true, 0),
    DSD_3_IN_14(2, false, 14),
    DSD_3_IN_16(3, false, 16),
    ;

    companion object {
        fun fromCode(code: Int): SacdFrameFormat = entries.firstOrNull { it.code == code }
            ?: throw UnsupportedOperationException("Unsupported SACD frame format: $code.")
    }
}

data class SacdTrack(
    val number: Int,
    val area: SacdAreaKind,
    val startLsn: Long,
    val lengthSectors: Long,
    val byteOffset: Long,
    val startFrame: Int,
    val durationFrames: Int,
    val startTimeUs: Long,
    val channels: Int,
    val bitRate: Int,
) {
    init {
        require(number in 1..255 && startLsn >= 0 && lengthSectors > 0 && byteOffset >= 0) {
            "Invalid SACD track sector range."
        }
        require(startFrame >= 0 && durationFrames > 0 && startTimeUs >= 0 && channels in 1..6) {
            "Invalid SACD track time or channel metadata."
        }
        require(bitRate == RawDsdConstants.DSD64_RATE) { "SACD uses DSD64." }
    }

    val endFrameExclusive: Int get() = startFrame + durationFrames
    val endLsnExclusive: Long get() = startLsn + lengthSectors
    val durationUs: Long get() = durationFrames.toLong() * 1_000_000L / RawDsdConstants.SACD_FRAME_RATE
    val sampleCount: Long get() = durationFrames.toLong() * RawDsdConstants.SACD_FRAME_SAMPLES
}

data class SacdArea(
    val kind: SacdAreaKind,
    val tocLsn: Long,
    val tocSectors: Int,
    val frameFormat: SacdFrameFormat,
    val channels: Int,
    val totalFrames: Int,
    val firstFrame: Int,
    val trackAreaStartLsn: Long,
    val trackAreaEndLsn: Long,
    val tracks: List<SacdTrack>,
) {
    init {
        require(tracks.isNotEmpty() && totalFrames > 0 && firstFrame >= 0) { "Empty SACD audio area." }
        require(trackAreaStartLsn >= 0 && trackAreaEndLsn >= trackAreaStartLsn) { "Invalid SACD track area." }
    }

    fun track(number: Int): SacdTrack = tracks.firstOrNull { it.number == number }
        ?: throw IllegalArgumentException("SACD track $number does not exist in ${kind.name}.")
}

data class SacdIsoDisc(val layout: SacdSectorLayout, val areas: List<SacdArea>) {
    init {
        require(areas.isNotEmpty() && areas.map { it.kind }.toSet().size == areas.size) {
            "SACD image has no audio areas or has duplicate areas."
        }
    }

    fun area(kind: SacdAreaKind): SacdArea = areas.firstOrNull { it.kind == kind }
        ?: throw IllegalArgumentException("SACD image has no ${kind.name} area.")
}

/** Master_TOC_0, Area_TOC, SACDTRL1 and SACDTRL2 parser. */
object SacdIsoParser {
    const val MASTER_TOC_LSN = 510L
    const val SECTOR_BYTES = 2_048

    fun parse(input: RawDsdInput): SacdIsoDisc {
        val userLayout = SacdSectorLayout(2_048, 0)
        var master = readSector(input, userLayout, MASTER_TOC_LSN)
        var layout = userLayout
        if (!master.hasSignature("SACDMTOC")) {
            layout = SacdSectorLayout(2_064, 12)
            master = readSector(input, layout, MASTER_TOC_LSN)
            require(master.hasSignature("SACDMTOC")) { "SACD master TOC was not found at LSN 510." }
        }
        require(master.u8(8) == 1) { "Unsupported SACD master TOC version." }

        val references = SacdAreaKind.entries.mapNotNull { kind ->
            val address = if (kind == SacdAreaKind.STEREO) 0x40 else 0x48
            master.u32(address).takeIf { it != 0L }?.let { kind to it }
        }.sortedBy { it.second }
        require(references.isNotEmpty()) { "SACD master TOC contains no audio area." }
        require(references.map { it.second }.toSet().size == references.size) { "Duplicate SACD area TOC address." }

        val areas = references.map { (kind, tocLsn) -> readArea(input, layout, kind, tocLsn) }
        return SacdIsoDisc(layout, areas.sortedBy { it.kind.ordinal })
    }

    private fun readArea(input: RawDsdInput, layout: SacdSectorLayout, kind: SacdAreaKind, tocLsn: Long): SacdArea {
        val toc = readSector(input, layout, tocLsn)
        require(toc.hasSignature(kind.signature)) { "Incorrect ${kind.name} area TOC signature." }
        require(toc.u8(8) == 1) { "Unsupported ${kind.name} area TOC version." }
        val tocSectors = toc.u16(0x0a)
        require(tocSectors in 5..96) { "Invalid SACD area TOC length." }
        require(toc.u8(0x14) == 4) { "SACD sampling frequency is not DSD64." }
        val frameFormat = SacdFrameFormat.fromCode(toc.u8(0x15) and 0x0f)
        val channels = toc.u8(0x20)
        require(if (frameFormat.dst) channels in intArrayOf(2, 5, 6) else channels == 2) {
            "Unsupported SACD channel count $channels for $frameFormat."
        }
        val totalFrames = timecode(toc, 0x40)
        require(totalFrames > 0) { "Empty SACD area play time." }
        val trackCount = toc.u8(0x45)
        require(trackCount in 1..255) { "Invalid SACD track count." }
        val areaStart = toc.u32(0x48)
        val areaEnd = toc.u32(0x4c)
        require(areaEnd >= areaStart && tocLsn + tocSectors <= areaStart) { "Invalid SACD track area range." }

        val offsets = readSector(input, layout, tocLsn + 1)
        val times = readSector(input, layout, tocLsn + 2)
        require(offsets.hasSignature("SACDTRL1")) { "SACD track offset list is missing." }
        require(times.hasSignature("SACDTRL2")) { "SACD track time list is missing." }
        val firstFrame = timecode(times, 8)
        require(firstFrame < totalFrames) { "SACD first track starts outside the area play time." }

        val tracks = ArrayList<SacdTrack>(trackCount)
        var previousTrack: SacdTrack? = null
        for (index in 0 until trackCount) {
            val startLsn = offsets.u32(8 + 4 * index)
            val lengthSectors = offsets.u32(8 + 4 * 255 + 4 * index)
            val startFrame = timecode(times, 8 + 4 * index)
            val durationFrames = timecode(times, 8 + 4 * 255 + 4 * index)
            require(startFrame >= firstFrame && durationFrames > 0 && startFrame + durationFrames <= totalFrames) {
                "Invalid SACD track time range."
            }
            require(lengthSectors > 0 && startLsn >= areaStart && startLsn <= areaEnd &&
                lengthSectors - 1 <= areaEnd - startLsn) {
                "SACD track sectors exceed the track area."
            }
            previousTrack?.let { previous ->
                require(startFrame >= previous.endFrameExclusive) { "SACD track times overlap or run backwards." }
                require(startLsn >= previous.endLsnExclusive) { "SACD track sectors overlap or run backwards." }
            }
            val position = layout.dataPosition(startLsn)
            val physicalPosition = position - layout.dataOffset
            require(input.length == RawDsdInput.UNKNOWN_LENGTH ||
                position <= input.length - SECTOR_BYTES &&
                lengthSectors <= (input.length - physicalPosition) / layout.sectorSize) {
                "SACD track is truncated."
            }
            tracks += SacdTrack(
                number = index + 1,
                area = kind,
                startLsn = startLsn,
                lengthSectors = lengthSectors,
                byteOffset = position,
                startFrame = startFrame,
                durationFrames = durationFrames,
                startTimeUs = (startFrame - firstFrame).toLong() * 1_000_000L / RawDsdConstants.SACD_FRAME_RATE,
                channels = channels,
                bitRate = RawDsdConstants.DSD64_RATE,
            )
            previousTrack = tracks.last()
        }
        return SacdArea(
            kind,
            tocLsn,
            tocSectors,
            frameFormat,
            channels,
            totalFrames,
            firstFrame,
            areaStart,
            areaEnd,
            tracks,
        )
    }

    private fun readSector(input: RawDsdInput, layout: SacdSectorLayout, lsn: Long): ByteArray {
        val position = layout.dataPosition(lsn)
        if (input.length != RawDsdInput.UNKNOWN_LENGTH && position > input.length - SECTOR_BYTES) {
            throw EOFException("Truncated SACD sector $lsn.")
        }
        return input.readBytes(position, SECTOR_BYTES)
    }

    private fun timecode(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 3) { "Truncated SACD timecode." }
        val minutes = bytes.u8(offset)
        val seconds = bytes.u8(offset + 1)
        val frames = bytes.u8(offset + 2)
        require(seconds < 60 && frames < RawDsdConstants.SACD_FRAME_RATE) { "Invalid SACD timecode." }
        return (minutes * 60 + seconds) * RawDsdConstants.SACD_FRAME_RATE + frames
    }

    private fun ByteArray.hasSignature(signature: String): Boolean {
        val expected = signature.toByteArray(Charsets.US_ASCII)
        return size >= expected.size && expected.indices.all { this[it] == expected[it] }
    }
}

internal fun ByteArray.u8(offset: Int): Int {
    require(offset >= 0 && offset < size) { "Read outside SACD structure." }
    return this[offset].toInt() and 0xff
}

internal fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

internal fun ByteArray.u32(offset: Int): Long =
    (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
        (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()
