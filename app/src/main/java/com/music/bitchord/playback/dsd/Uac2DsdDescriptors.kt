package com.music.bitchord.playback.dsd

/** Identifies an alternate setting by its USB interface and alternate numbers. */
data class Uac2AltSettingId(val interfaceNumber: Int, val alternateSetting: Int) {
    init {
        require(interfaceNumber in 0..255 && alternateSetting in 0..255) {
            "USB interface and alternate setting numbers must be unsigned bytes."
        }
    }
}

/** One UAC2 sampling-frequency range triplet (MIN, MAX, RES), all in Hz. */
data class Uac2SampleRateRange(val minimumHz: Int, val maximumHz: Int, val resolutionHz: Int) {
    init {
        require(minimumHz > 0 && maximumHz >= minimumHz && resolutionHz > 0) {
            "Invalid UAC2 sample-rate range."
        }
    }
}

data class Uac2DsdSlotFormat(val subslotBytes: Int, val bitResolution: Int) {
    init {
        require(subslotBytes > 0 && bitResolution > 0 && bitResolution <= subslotBytes * 8) {
            "Invalid UAC2 subslot format."
        }
    }
}

/** A Type-I UAC2 alternate setting advertising RAW_DATA (bmFormats bit 31). */
data class Uac2RawDsdAltSetting(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val channels: Int,
    val slotFormat: Uac2DsdSlotFormat,
    val bmFormats: Long,
    val sampleRateRanges: List<Uac2SampleRateRange> = emptyList(),
) {
    val id: Uac2AltSettingId get() = Uac2AltSettingId(interfaceNumber, alternateSetting)

    /** Discrete rates from the supplied clock ranges; empty when undisclosed or continuous only. */
    val sampleRatesHz: List<Int>

    init {
        require(channels in 1..255) { "Raw DSD must advertise at least one channel." }
        require(bmFormats and UAC2_FORMAT_TYPE_I_RAW_DATA != 0L) { "Not a UAC2 raw-data format." }
        require(
            (slotFormat.subslotBytes == 2 || slotFormat.subslotBytes == 4) &&
                slotFormat.bitResolution == slotFormat.subslotBytes * 8,
        ) { "Native DSD requires 16/16 or 32/32-bit slots." }
        sampleRatesHz = discreteRates(sampleRateRanges)
    }
}

/**
 * Parses UAC2 GET_RANGE sampling-frequency responses without performing I/O.
 * The payload is wNumSubranges followed by 12-byte little-endian triplets.
 */
object Uac2SampleRateRangeParser {
    fun parse(payload: ByteArray): List<Uac2SampleRateRange> {
        require(payload.size >= 2) { "Truncated UAC2 sample-rate range header." }
        val count = u16Le(payload, 0)
        require(count > 0) { "UAC2 sample-rate range response is empty." }
        val expectedSize = 2L + count.toLong() * 12L
        require(expectedSize == payload.size.toLong()) { "UAC2 sample-rate range length mismatch." }
        val ranges = ArrayList<Uac2SampleRateRange>(count)
        for (index in 0 until count) {
            val offset = 2 + index * 12
            ranges += Uac2SampleRateRange(
                minimumHz = positiveU32(payload, offset, "minimum"),
                maximumHz = positiveU32(payload, offset + 4, "maximum"),
                resolutionHz = positiveU32(payload, offset + 8, "resolution"),
            )
        }
        // Validate the expansion bound here as well as in each raw alt setting.
        discreteRates(ranges)
        return ranges
    }
}

/**
 * Walks raw USB descriptors and associates AS_GENERAL and FORMAT_TYPE records
 * with their own interface alternate setting. UAC2 rates normally arrive via
 * clock GET_RANGE requests, so callers can attach those pure parsing results
 * through [sampleRates]. Standard UAC2 descriptors never invent a rate list.
 */
object Uac2DsdDescriptorParser {
    @JvmOverloads
    fun parse(
        descriptors: ByteArray,
        sampleRates: Map<Uac2AltSettingId, List<Uac2SampleRateRange>> = emptyMap(),
    ): List<Uac2RawDsdAltSetting> {
        val result = ArrayList<Uac2RawDsdAltSetting>()
        val completedIds = HashSet<Uac2AltSettingId>()
        var current: PendingAlt? = null
        var offset = 0

        fun completeAlt() {
            val alt = current ?: return
            val formats = alt.formats
            if (formats == null || formats and UAC2_FORMAT_TYPE_I_RAW_DATA == 0L) return
            require(completedIds.add(alt.id)) { "Duplicate UAC2 alternate setting ${alt.id}." }
            require(alt.id.alternateSetting != 0 && alt.endpointCount > 0) {
                "Raw DSD alternate setting ${alt.id} has no streaming endpoint."
            }
            val channels = alt.channels ?: 0
            val slot = alt.slot
            require(slot != null) { "Raw DSD alternate setting ${alt.id} lacks its Type-I slot descriptor." }
            result += Uac2RawDsdAltSetting(
                interfaceNumber = alt.id.interfaceNumber,
                alternateSetting = alt.id.alternateSetting,
                channels = channels,
                slotFormat = slot,
                bmFormats = formats,
                sampleRateRanges = sampleRates[alt.id].orEmpty(),
            )
        }

        while (offset < descriptors.size) {
            require(descriptors.size - offset >= 2) { "Truncated USB descriptor header at $offset." }
            val length = descriptors[offset].toInt() and 0xff
            val type = descriptors[offset + 1].toInt() and 0xff
            require(length >= 2 && length <= descriptors.size - offset) {
                "Invalid USB descriptor length $length at $offset."
            }
            when {
                type == USB_DT_INTERFACE -> {
                    require(length >= 9) { "Truncated USB interface descriptor at $offset." }
                    completeAlt()
                    current = null
                    val interfaceClass = u8(descriptors, offset + 5)
                    val interfaceSubclass = u8(descriptors, offset + 6)
                    val protocol = u8(descriptors, offset + 7)
                    if (interfaceClass == USB_CLASS_AUDIO &&
                        interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING &&
                        protocol == UAC2_INTERFACE_PROTOCOL
                    ) {
                        current = PendingAlt(
                            id = Uac2AltSettingId(u8(descriptors, offset + 2), u8(descriptors, offset + 3)),
                            endpointCount = u8(descriptors, offset + 4),
                        )
                    }
                }

                current != null && type == USB_DT_CS_INTERFACE -> {
                    require(length >= 3) { "Truncated UAC2 class-specific interface descriptor at $offset." }
                    val alt = current
                    when (u8(descriptors, offset + 2)) {
                        UAC_AS_GENERAL -> {
                            require(length >= 15) { "Truncated UAC2 AS_GENERAL descriptor at $offset." }
                            require(!alt.sawGeneral) { "Duplicate AS_GENERAL for ${alt.id}." }
                            alt.sawGeneral = true
                            if (u8(descriptors, offset + 5) == UAC_FORMAT_TYPE_I) {
                                alt.formats = u32Le(descriptors, offset + 6)
                                alt.channels = u8(descriptors, offset + 14)
                            }
                        }

                        UAC_FORMAT_TYPE -> {
                            require(length >= 6) { "Truncated UAC2 FORMAT_TYPE descriptor at $offset." }
                            require(!alt.sawFormat) { "Duplicate FORMAT_TYPE for ${alt.id}." }
                            alt.sawFormat = true
                            if (u8(descriptors, offset + 3) == UAC_FORMAT_TYPE_I) {
                                alt.slot = Uac2DsdSlotFormat(
                                    subslotBytes = u8(descriptors, offset + 4),
                                    bitResolution = u8(descriptors, offset + 5),
                                )
                            }
                        }
                    }
                }
            }
            offset += length
        }
        completeAlt()
        return result
    }

    private class PendingAlt(val id: Uac2AltSettingId, val endpointCount: Int) {
        var sawGeneral = false
        var sawFormat = false
        var formats: Long? = null
        var channels: Int? = null
        var slot: Uac2DsdSlotFormat? = null
    }
}

const val UAC2_FORMAT_TYPE_I_RAW_DATA: Long = 0x80000000L

private const val USB_DT_INTERFACE = 0x04
private const val USB_DT_CS_INTERFACE = 0x24
private const val USB_CLASS_AUDIO = 0x01
private const val USB_SUBCLASS_AUDIOSTREAMING = 0x02
private const val UAC2_INTERFACE_PROTOCOL = 0x20
private const val UAC_AS_GENERAL = 0x01
private const val UAC_FORMAT_TYPE = 0x02
private const val UAC_FORMAT_TYPE_I = 0x01
private const val MAX_DISCRETE_RATES = 256

private fun discreteRates(ranges: List<Uac2SampleRateRange>): List<Int> {
    var expansionSize = 0L
    for (range in ranges) {
        if (range.resolutionHz > 1 || range.minimumHz == range.maximumHz) {
            expansionSize += (range.maximumHz - range.minimumHz).toLong() / range.resolutionHz + 1L
        }
    }
    require(expansionSize <= MAX_DISCRETE_RATES) { "Too many discrete UAC2 sample rates." }
    val rates = sortedSetOf<Int>()
    for (range in ranges) {
        if (range.resolutionHz == 1 && range.maximumHz > range.minimumHz) continue
        var rate = range.minimumHz
        while (rate <= range.maximumHz) {
            rates += rate
            val next = rate.toLong() + range.resolutionHz
            if (next > Int.MAX_VALUE) break
            rate = next.toInt()
        }
    }
    return rates.toList()
}

private fun positiveU32(bytes: ByteArray, offset: Int, field: String): Int {
    val value = u32Le(bytes, offset)
    require(value in 1..Int.MAX_VALUE.toLong()) { "UAC2 sample-rate $field is out of range." }
    return value.toInt()
}

private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

private fun u16Le(bytes: ByteArray, offset: Int): Int = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

private fun u32Le(bytes: ByteArray, offset: Int): Long =
    u8(bytes, offset).toLong() or
        (u8(bytes, offset + 1).toLong() shl 8) or
        (u8(bytes, offset + 2).toLong() shl 16) or
        (u8(bytes, offset + 3).toLong() shl 24)
