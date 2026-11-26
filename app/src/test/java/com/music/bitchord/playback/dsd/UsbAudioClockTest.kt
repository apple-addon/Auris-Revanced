package com.music.bitchord.playback.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbAudioClockTest {
    @Test
    fun q10Point14Golden48KhzPacket() {
        val feedback = UsbAudioFeedback.decode(bytes(0x00, 0x00, 0x0c), UsbFeedbackFormat.Q10_14)
        assertEquals(0x0c0000L, feedback.rawValue)
        assertEquals(48.0, feedback.samplesPerUsbFrame, 0.0)
        assertEquals(48_000.0, feedback.sampleRateHz, 0.0)
    }

    @Test
    fun q10Point14RetainsFractionalRate() {
        val feedback = UsbAudioFeedback.decode(bytes(0x40, 0x06, 0x0b), UsbFeedbackFormat.Q10_14)
        assertEquals(44.09765625, feedback.samplesPerUsbFrame, 0.000000001)
        assertEquals(44_097.65625, feedback.sampleRateHz, 0.000001)
    }

    @Test
    fun q12Point16GoldenHighSpeed44KhzPacket() {
        val feedback = UsbAudioFeedback.decode(bytes(0x33, 0x83, 0x05, 0x00), UsbFeedbackFormat.Q12_16)
        assertEquals(0x00058333L, feedback.rawValue)
        assertEquals(5.5125, feedback.samplesPerUsbFrame, 0.00001)
        assertEquals(44_099.9755859375, feedback.sampleRateHz, 0.000000001)
    }

    @Test
    fun feedbackRejectsWrongSizeZeroAndReservedBits() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioFeedback.decode(bytes(0, 0), UsbFeedbackFormat.Q10_14)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioFeedback.decode(bytes(0, 0, 0), UsbFeedbackFormat.Q10_14)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioFeedback.decode(bytes(0, 0, 6, 0x10), UsbFeedbackFormat.Q12_16)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbAudioFeedback.decode(bytes(0, 0, 0x0c), UsbFeedbackFormat.Q10_14, 0)
        }
    }

    @Test
    fun clockReadbackIsUnsignedLittleEndianAndValidated() {
        assertEquals(176_400, Uac2ClockReadback.decodeSampleRateHz(bytes(0x10, 0xb1, 0x02, 0x00)))
        assertThrows(IllegalArgumentException::class.java) { Uac2ClockReadback.decodeSampleRateHz(bytes(0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Uac2ClockReadback.decodeSampleRateHz(bytes(0, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Uac2ClockReadback.decodeSampleRateHz(bytes(0, 0, 0, 0x80)) }
    }

    @Test
    fun watchdogWaitsBecomesStaleAndRecoversOnValidFeedback() {
        val watchdog = UsbClockWatchdog(48_000, feedbackTimeoutMs = 100)
        assertEquals(UsbClockHealth.INACTIVE, watchdog.status(0))
        watchdog.start(10)
        assertEquals(UsbClockHealth.WAITING_FOR_FEEDBACK, watchdog.status(110))
        assertEquals(UsbClockHealth.STALE, watchdog.status(111))

        watchdog.start(0)
        assertEquals(UsbClockHealth.HEALTHY, watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 0))
        assertEquals(0L, watchdog.lastValidFeedbackAtMs)
        assertEquals(UsbClockHealth.HEALTHY, watchdog.status(100))
        assertEquals(UsbClockHealth.STALE, watchdog.status(101))
        assertEquals(UsbClockHealth.HEALTHY, watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 150))
    }

    @Test
    fun implausibleFeedbackDoesNotRefreshLastGoodTimestamp() {
        val watchdog = UsbClockWatchdog(48_000, feedbackTimeoutMs = 100)
        watchdog.start(0)
        watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 0)
        assertEquals(
            UsbClockHealth.RATE_MISMATCH,
            watchdog.recordFeedback(bytes(0, 0, 0x18), UsbFeedbackFormat.Q10_14, 10),
        )
        assertEquals(0L, watchdog.lastValidFeedbackAtMs)
        assertEquals(96_000.0, watchdog.latestFeedbackRateHz!!, 0.0)
        assertEquals(UsbClockHealth.HEALTHY, watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 20))
        assertThrows(IllegalArgumentException::class.java) {
            watchdog.recordFeedback(bytes(0, 0, 0), UsbFeedbackFormat.Q10_14, 50)
        }
        assertEquals(20L, watchdog.lastValidFeedbackAtMs)
        assertEquals(UsbClockHealth.STALE, watchdog.status(121))
    }

    @Test
    fun readbackMismatchIsIndependentUntilAValidReadback() {
        val watchdog = UsbClockWatchdog(48_000, feedbackTimeoutMs = 100)
        watchdog.start(0)
        watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 0)
        assertEquals(UsbClockHealth.RATE_MISMATCH, watchdog.recordClockReadback(rateLe(96_000), 1))
        assertEquals(
            UsbClockHealth.RATE_MISMATCH,
            watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 2),
        )
        assertEquals(UsbClockHealth.HEALTHY, watchdog.recordClockReadback(rateLe(48_000), 3))
        assertEquals(48_000, watchdog.latestReadbackRateHz)
    }

    @Test
    fun highSpeedFeedbackUsesMicroframesByDefault() {
        val watchdog = UsbClockWatchdog(48_000)
        watchdog.start(0)
        val health = watchdog.recordFeedback(bytes(0, 0, 6, 0), UsbFeedbackFormat.Q12_16, 0)
        assertEquals(UsbClockHealth.HEALTHY, health)
        assertEquals(48_000.0, watchdog.latestFeedbackRateHz!!, 0.0)
    }

    @Test
    fun watchdogRejectsBackwardTimeAndCallsWhileStopped() {
        val watchdog = UsbClockWatchdog(48_000)
        assertThrows(IllegalArgumentException::class.java) {
            watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 0)
        }
        watchdog.start(10)
        watchdog.recordFeedback(fullSpeed48Khz, UsbFeedbackFormat.Q10_14, 20)
        assertThrows(IllegalArgumentException::class.java) { watchdog.status(19) }
        watchdog.stop()
        assertEquals(UsbClockHealth.INACTIVE, watchdog.status(20))
        assertThrows(IllegalArgumentException::class.java) { watchdog.start(-1) }
    }

    @Test
    fun watchdogConfigurationIsValidated() {
        assertThrows(IllegalArgumentException::class.java) { UsbClockWatchdog(0) }
        assertThrows(IllegalArgumentException::class.java) { UsbClockWatchdog(48_000, feedbackTimeoutMs = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            UsbClockWatchdog(48_000, feedbackTolerancePpm = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UsbClockWatchdog(48_000, readbackTolerancePpm = -1.0)
        }
    }

    private val fullSpeed48Khz = bytes(0, 0, 0x0c)

    private fun rateLe(rate: Int): ByteArray = ByteArray(4) { (rate ushr (it * 8)).toByte() }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
