package com.decent.usbaudio

/** One streaming alternate setting, including an optional async-feedback endpoint. */
data class UacStreamAltSetting(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val channels: Int,
    val bitResolution: Int,
    val subframeBytes: Int,
    val sampleRatesHz: List<Int>,
    val minSampleRateHz: Int,
    val maxSampleRateHz: Int,
    val rawDsd: Boolean,
    val endpointOut: Int,
    val maxPacketSize: Int,
    val dataInterval: Int,
    val feedbackEndpoint: Int?,
    val feedbackPacketSize: Int?,
    val feedbackInterval: Int?,
) {
    fun accepts(sampleRateHz: Int): Boolean = when {
        sampleRatesHz.isNotEmpty() -> sampleRateHz in sampleRatesHz
        minSampleRateHz > 0 && maxSampleRateHz >= minSampleRateHz ->
            sampleRateHz in minSampleRateHz..maxSampleRateHz
        else -> true // UAC1 descriptors may leave clock selection to the endpoint.
    }
}

/** Strict UAC1/UAC2 Type-I descriptor parser; malformed descriptors fail closed. */
object Uac2DsdDescriptors {
    private data class Pending(
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val protocol: Int,
        var channels: Int = 0,
        var bitResolution: Int = 0,
        var subframeBytes: Int = 0,
        var rawDsd: Boolean = false,
        var rates: List<Int> = emptyList(),
        var minRate: Int = 0,
        var maxRate: Int = 0,
        var endpointOut: Int = -1,
        var maxPacketSize: Int = 0,
        var dataInterval: Int = 1,
        var feedbackEndpoint: Int? = null,
        var feedbackPacketSize: Int? = null,
        var feedbackInterval: Int? = null,
    )

    fun parse(raw: ByteArray): List<UacStreamAltSetting> {
        val pending = mutableListOf<Pending>()
        var current: Pending? = null
        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw.u8(offset)
            if (length < 2 || offset + length > raw.size) break
            val type = raw.u8(offset + 1)
            when (type) {
                0x04 -> { // INTERFACE
                    if (length >= 9 && raw.u8(offset + 5) == 1 && raw.u8(offset + 6) == 2) {
                        current = Pending(
                            interfaceNumber = raw.u8(offset + 2),
                            alternateSetting = raw.u8(offset + 3),
                            protocol = raw.u8(offset + 7),
                        ).also(pending::add)
                    } else {
                        current = null
                    }
                }
                0x24 -> { // CS_INTERFACE
                    if (length >= 3 && raw.u8(offset + 2) == 2) {
                        parseFormatType(raw, offset, length, current)
                    }
                }
                0x05 -> { // ENDPOINT
                    val stream = current
                    if (stream != null && length >= 7 && (raw.u8(offset + 3) and 0x03) == 1) {
                        val address = raw.u8(offset + 2)
                        val maxPacket = raw.u16(offset + 4) and 0x07FF
                        val interval = raw.u8(offset + 6).coerceAtLeast(1)
                        if (address and 0x80 == 0) {
                            stream.endpointOut = address
                            stream.maxPacketSize = maxPacket
                            stream.dataInterval = interval
                        } else {
                            stream.feedbackEndpoint = address
                            stream.feedbackPacketSize = maxPacket
                            stream.feedbackInterval = interval
                        }
                    }
                }
            }
            offset += length
        }
        return pending.mapNotNull { stream ->
            if (stream.endpointOut < 0 || stream.channels <= 0 || stream.bitResolution <= 0 || stream.subframeBytes <= 0) {
                return@mapNotNull null
            }
            UacStreamAltSetting(
                interfaceNumber = stream.interfaceNumber,
                alternateSetting = stream.alternateSetting,
                channels = stream.channels,
                bitResolution = stream.bitResolution,
                subframeBytes = stream.subframeBytes,
                sampleRatesHz = stream.rates,
                minSampleRateHz = stream.minRate,
                maxSampleRateHz = stream.maxRate,
                rawDsd = stream.rawDsd,
                endpointOut = stream.endpointOut,
                maxPacketSize = stream.maxPacketSize,
                dataInterval = stream.dataInterval,
                feedbackEndpoint = stream.feedbackEndpoint,
                feedbackPacketSize = stream.feedbackPacketSize,
                feedbackInterval = stream.feedbackInterval,
            )
        }
    }

    private fun parseFormatType(raw: ByteArray, offset: Int, length: Int, stream: Pending?) {
        if (stream == null || raw.u8(offset + 3) != 1) return
        if (stream.protocol >= 0x20 && length >= 12) {
            // UAC2 Type-I: bmFormats is a little-endian DWORD. Bit 31 is
            // raw (non-PCM) data, used by native-DSD alternate settings.
            val formats = raw.u32(offset + 4)
            stream.rawDsd = formats and 0x80000000L != 0L
            stream.channels = raw.u8(offset + 8)
            stream.subframeBytes = raw.u8(offset + 9)
            stream.bitResolution = raw.u8(offset + 10)
            val frequencyType = raw.u8(offset + 11)
            parseRates(raw, offset + 12, offset + length, frequencyType, stream)
        } else if (stream.protocol < 0x20 && length >= 8) {
            stream.channels = raw.u8(offset + 4)
            stream.subframeBytes = raw.u8(offset + 5)
            stream.bitResolution = raw.u8(offset + 6)
            parseRates(raw, offset + 8, offset + length, raw.u8(offset + 7), stream)
        }
    }

    private fun parseRates(raw: ByteArray, start: Int, end: Int, frequencyType: Int, stream: Pending) {
        when (frequencyType) {
            0 -> if (start + 6 <= end) {
                stream.minRate = raw.u24(start)
                stream.maxRate = raw.u24(start + 3)
            }
            else -> {
                val count = ((end - start) / 3).coerceAtMost(frequencyType)
                stream.rates = (0 until count).map { raw.u24(start + it * 3) }.filter { it > 0 }
            }
        }
    }

    fun selectRawDsd(alts: List<UacStreamAltSetting>, sampleRateHz: Int, channels: Int): UacStreamAltSetting? =
        alts.firstOrNull { it.rawDsd && it.channels == channels && it.accepts(sampleRateHz) }

    fun selectDop(alts: List<UacStreamAltSetting>, sampleRateHz: Int, channels: Int): UacStreamAltSetting? =
        alts.firstOrNull { !it.rawDsd && it.bitResolution == 24 && it.channels == channels && it.accepts(sampleRateHz) }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
    private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
    private fun ByteArray.u24(index: Int): Int = u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)
    private fun ByteArray.u32(index: Int): Long =
        u8(index).toLong() or (u8(index + 1).toLong() shl 8) or
            (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)
}

/** Per-DAC transport quirks; unknown devices always use conservative defaults. */
data class DacQuirkProfile(
    val continuousFeedback: Boolean = false,
    val implicitFeedback: Boolean = false,
    val allowUnverifiedClock: Boolean = false,
    val keepAlive: Boolean = false,
)

object DacQuirks {
    private val profiles = mutableMapOf<Pair<Int, Int>, DacQuirkProfile>()

    fun forDevice(vendorId: Int, productId: Int): DacQuirkProfile =
        profiles[vendorId to productId] ?: DacQuirkProfile()

    fun install(vendorId: Int, productId: Int, profile: DacQuirkProfile) {
        profiles[vendorId to productId] = profile
    }

    fun clear() = profiles.clear()
}
