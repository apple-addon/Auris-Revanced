package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeAudioOutputContractTest {

    @Test
    fun `config validates complete native float layout`() {
        val config = NativeAudioOutputConfig(sampleRate = 48_000, channelCount = 2)
        assertEquals(8, config.frameBytes)
        assertEquals(24_000, config.ringCapacityFrames)
        assertEquals(0, config.deviceId)
        assertEquals(4_096, defaultNativeRingCapacityFrames(4_000))

        assertThrows(IllegalArgumentException::class.java) {
            NativeAudioOutputConfig(sampleRate = 3_999, channelCount = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeAudioOutputConfig(sampleRate = 48_000, channelCount = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeAudioOutputConfig(
                sampleRate = 48_000,
                channelCount = 2,
                ringCapacityFrames = 64,
                bufferSizeFrames = 65,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeAudioOutputConfig(sampleRate = 48_000, channelCount = 2, deviceId = -1)
        }
    }

    @Test
    fun `direct buffer contract accepts native order without changing position`() {
        val buffer = ByteBuffer.allocateDirect(5 * 8)
            .order(ByteOrder.nativeOrder())
        buffer.position(8)
        buffer.limit(4 * 8)

        assertEquals(3, directFloatFrameCount(buffer, frameBytes = 8))
        assertEquals(8, buffer.position())
        assertEquals(4 * 8, buffer.limit())
        val empty = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        assertEquals(0, directFloatFrameCount(empty, frameBytes = 8))
    }

    @Test
    fun `direct buffer contract rejects heap wrong order and partial frames`() {
        assertThrows(IllegalArgumentException::class.java) {
            directFloatFrameCount(ByteBuffer.allocate(8), frameBytes = 8)
        }
        val oppositeOrder = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            ByteOrder.BIG_ENDIAN
        } else {
            ByteOrder.LITTLE_ENDIAN
        }
        assertThrows(IllegalArgumentException::class.java) {
            directFloatFrameCount(ByteBuffer.allocateDirect(8).order(oppositeOrder), frameBytes = 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            directFloatFrameCount(
                ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()),
                frameBytes = 8,
            )
        }
    }

    @Test
    fun `telemetry decoder reports negotiated mode clock and last error honestly`() {
        val values = LongArray(NATIVE_TELEMETRY_VALUE_COUNT)
        values[0] = 1
        values[1] = 1
        values[2] = 96_000
        values[3] = 2
        values[4] = 65_536
        values[5] = 1_024
        values[6] = 256
        values[7] = 1 // Actual shared mode, not the requested exclusive mode.
        values[8] = 1
        values[9] = 123_456
        values[10] = 1
        values[11] = 200_000
        values[12] = 3
        values[13] = 2
        values[14] = 1
        values[15] = 1
        values[16] = 512
        values[17] = 5
        values[18] = -899

        val telemetry = decodeNativeAudioTelemetry(
            NativeAudioBackend.AAUDIO,
            values,
            "Disconnected",
        )
        assertTrue(telemetry.isOpen)
        assertTrue(telemetry.isPlaying)
        assertEquals(NativeAudioSharingMode.SHARED, telemetry.sharingMode)
        assertEquals(NativeAudioClock.AAUDIO_TIMESTAMP, telemetry.clock)
        assertEquals(123_456L, telemetry.positionFrames)
        assertEquals(3L, telemetry.underrunCount)
        assertEquals(2L, telemetry.xrunCount)
        assertEquals(1L, telemetry.recoveryCount)
        assertEquals("Disconnected", telemetry.lastError)

        values[7] = 99
        values[10] = 99
        val unknown = decodeNativeAudioTelemetry(NativeAudioBackend.AAUDIO, values, null)
        assertEquals(NativeAudioSharingMode.UNKNOWN, unknown.sharingMode)
        assertEquals(NativeAudioClock.UNKNOWN, unknown.clock)
        assertNull(unknown.lastError)
    }

    @Test
    fun `opensl telemetry distinguishes queue clock from aaudio timestamps`() {
        val values = LongArray(NATIVE_TELEMETRY_VALUE_COUNT) { 0 }
        values[0] = 1
        values[7] = -1
        values[9] = 480
        values[10] = 3
        values[12] = 7

        val telemetry = decodeNativeAudioTelemetry(NativeAudioBackend.OPENSL_ES, values, null)
        assertFalse(telemetry.isPlaying)
        assertEquals(NativeAudioSharingMode.NOT_APPLICABLE, telemetry.sharingMode)
        assertEquals(NativeAudioClock.OPENSL_QUEUE_COMPLETION, telemetry.clock)
        assertEquals(480L, telemetry.positionFrames)
        assertEquals(7L, telemetry.underrunCount)
        assertEquals(0L, telemetry.xrunCount)
    }

    @Test
    fun `telemetry decoder rejects truncated native snapshots`() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeNativeAudioTelemetry(
                NativeAudioBackend.AAUDIO,
                LongArray(NATIVE_TELEMETRY_VALUE_COUNT - 1),
                null,
            )
        }
    }
}
