package com.music.bitchord.playback.dsd

import kotlin.math.abs

/** Common unsigned little-endian asynchronous feedback representations. */
enum class UsbFeedbackFormat(
    val packetBytes: Int,
    val integerBits: Int,
    val fractionalBits: Int,
    val defaultUsbFramesPerSecond: Int,
) {
    Q10_14(packetBytes = 3, integerBits = 10, fractionalBits = 14, defaultUsbFramesPerSecond = 1_000),
    Q12_16(packetBytes = 4, integerBits = 12, fractionalBits = 16, defaultUsbFramesPerSecond = 8_000),
}

data class UsbFeedbackValue(
    val format: UsbFeedbackFormat,
    val rawValue: Long,
    val usbFramesPerSecond: Int,
) {
    val samplesPerUsbFrame: Double get() = rawValue.toDouble() / (1L shl format.fractionalBits).toDouble()
    val sampleRateHz: Double get() = samplesPerUsbFrame * usbFramesPerSecond
}

/** Decodes complete feedback packets; packet sizes and reserved bits are strict. */
object UsbAudioFeedback {
    @JvmOverloads
    fun decode(
        packet: ByteArray,
        format: UsbFeedbackFormat,
        usbFramesPerSecond: Int = format.defaultUsbFramesPerSecond,
    ): UsbFeedbackValue {
        require(packet.size == format.packetBytes) {
            "${format.name} feedback must contain exactly ${format.packetBytes} bytes."
        }
        require(usbFramesPerSecond > 0) { "USB frame frequency must be positive." }
        var raw = 0L
        for (index in packet.indices) raw = raw or ((packet[index].toInt() and 0xff).toLong() shl (index * 8))
        if (format == UsbFeedbackFormat.Q12_16) {
            require(raw and 0xf0000000L == 0L) { "Q12.16 feedback reserved bits 28..31 must be zero." }
        }
        require(raw > 0L) { "Asynchronous feedback rate must be non-zero." }
        val integerLimit = 1L shl format.integerBits
        require(raw ushr format.fractionalBits < integerLimit) { "Feedback integer part exceeds ${format.name}." }
        return UsbFeedbackValue(format, raw, usbFramesPerSecond)
    }
}

/** Pure decoding for UAC2 CUR sample-rate readback (four-byte little endian). */
object Uac2ClockReadback {
    fun decodeSampleRateHz(packet: ByteArray): Int {
        require(packet.size == 4) { "UAC2 sample-rate readback must contain exactly four bytes." }
        var rate = 0L
        for (index in 0 until 4) rate = rate or ((packet[index].toInt() and 0xff).toLong() shl (index * 8))
        require(rate in 1..Int.MAX_VALUE.toLong()) { "UAC2 sample-rate readback is out of range." }
        return rate.toInt()
    }
}

enum class UsbClockHealth { INACTIVE, WAITING_FOR_FEEDBACK, HEALTHY, STALE, RATE_MISMATCH }

/**
 * Deterministic clock watchdog driven by caller-supplied monotonic timestamps.
 * It owns no thread and performs no USB or Android calls. Invalid feedback
 * never refreshes the last-good timestamp, so an absent or rejected stream
 * becomes stale after [feedbackTimeoutMs].
 */
class UsbClockWatchdog @JvmOverloads constructor(
    val nominalRateHz: Int,
    val feedbackTimeoutMs: Long = 250L,
    val feedbackTolerancePpm: Double = 5_000.0,
    val readbackTolerancePpm: Double = 100.0,
) {
    private var active = false
    private var startedAtMs = 0L
    private var observedAtMs = 0L
    private var feedbackMismatch = false
    private var readbackMismatch = false

    var lastValidFeedbackAtMs: Long? = null
        private set
    var latestFeedbackRateHz: Double? = null
        private set
    var latestReadbackRateHz: Int? = null
        private set

    init {
        require(nominalRateHz > 0) { "Nominal sample rate must be positive." }
        require(feedbackTimeoutMs > 0) { "Feedback timeout must be positive." }
        require(feedbackTolerancePpm.isFinite() && feedbackTolerancePpm >= 0.0) {
            "Feedback tolerance must be finite and non-negative."
        }
        require(readbackTolerancePpm.isFinite() && readbackTolerancePpm >= 0.0) {
            "Readback tolerance must be finite and non-negative."
        }
    }

    fun start(nowMs: Long) {
        require(nowMs >= 0) { "Monotonic timestamp cannot be negative." }
        active = true
        startedAtMs = nowMs
        observedAtMs = nowMs
        lastValidFeedbackAtMs = null
        latestFeedbackRateHz = null
        latestReadbackRateHz = null
        feedbackMismatch = false
        readbackMismatch = false
    }

    fun stop() {
        active = false
        lastValidFeedbackAtMs = null
        latestFeedbackRateHz = null
        latestReadbackRateHz = null
        feedbackMismatch = false
        readbackMismatch = false
    }

    @JvmOverloads
    fun recordFeedback(
        packet: ByteArray,
        format: UsbFeedbackFormat,
        nowMs: Long,
        usbFramesPerSecond: Int = format.defaultUsbFramesPerSecond,
    ): UsbClockHealth {
        checkEvent(nowMs)
        val feedback = UsbAudioFeedback.decode(packet, format, usbFramesPerSecond)
        latestFeedbackRateHz = feedback.sampleRateHz
        feedbackMismatch = deviationPpm(feedback.sampleRateHz) > feedbackTolerancePpm
        if (!feedbackMismatch) lastValidFeedbackAtMs = nowMs
        return status(nowMs)
    }

    fun recordClockReadback(packet: ByteArray, nowMs: Long): UsbClockHealth {
        checkEvent(nowMs)
        val rate = Uac2ClockReadback.decodeSampleRateHz(packet)
        latestReadbackRateHz = rate
        readbackMismatch = deviationPpm(rate.toDouble()) > readbackTolerancePpm
        return status(nowMs)
    }

    fun status(nowMs: Long): UsbClockHealth {
        require(nowMs >= 0) { "Monotonic timestamp cannot be negative." }
        if (!active) return UsbClockHealth.INACTIVE
        require(nowMs >= observedAtMs) { "Monotonic timestamp moved backwards." }
        observedAtMs = nowMs
        if (feedbackMismatch || readbackMismatch) return UsbClockHealth.RATE_MISMATCH
        val lastValid = lastValidFeedbackAtMs
        if (lastValid == null) {
            return if (nowMs - startedAtMs > feedbackTimeoutMs) UsbClockHealth.STALE
            else UsbClockHealth.WAITING_FOR_FEEDBACK
        }
        return if (nowMs - lastValid > feedbackTimeoutMs) UsbClockHealth.STALE else UsbClockHealth.HEALTHY
    }

    private fun checkEvent(nowMs: Long) {
        require(active) { "Clock watchdog is not running." }
        require(nowMs >= observedAtMs) { "Monotonic timestamp moved backwards." }
        observedAtMs = nowMs
    }

    private fun deviationPpm(actualRateHz: Double): Double =
        abs((actualRateHz - nominalRateHz) / nominalRateHz * 1_000_000.0)
}
