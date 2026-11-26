package com.music.bitchord.playback.output

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class NativeAudioOutputTest {
    private val spec = NativeOutputSpec(sampleRateHz = 48_000, channelCount = 2)

    @Test
    fun `direct partial write advances only consumed whole frames`() {
        val backend = FakeBackend().apply { writeResults += 1 }
        val output = output(backend)
        val input = ByteBuffer.allocateDirect(4 * spec.frameBytes)
        input.position(spec.frameBytes)

        assertFalse(output.write(input, 2 * spec.frameBytes, 0))
        assertEquals(2 * spec.frameBytes, input.position())
        assertFalse(output.isStalled)
        assertEquals(spec.frameBytes, backend.writes.single().offsetBytes)
        assertEquals(3 * spec.frameBytes, backend.writes.single().byteCount)
    }

    @Test
    fun `heap write copies packet without changing caller position early`() {
        val backend = FakeBackend().apply { writeResults += 2 }
        val output = output(backend)
        val input = ByteBuffer.allocate(6 * spec.frameBytes)
        for (i in 0 until input.capacity()) input.put(i, i.toByte())
        input.position(spec.frameBytes)

        assertFalse(output.write(input, 3 * spec.frameBytes, 0))
        assertEquals(3 * spec.frameBytes, input.position())
        val write = backend.writes.single()
        assertTrue(write.direct)
        assertEquals(0, write.offsetBytes)
        assertEquals(5 * spec.frameBytes, write.byteCount)
        assertArrayEquals(
            ByteArray(5 * spec.frameBytes) { (it + spec.frameBytes).toByte() },
            write.bytes,
        )
    }

    @Test
    fun `zero write succeeds without native call`() {
        val backend = FakeBackend()
        val output = output(backend)
        assertTrue(output.write(ByteBuffer.allocateDirect(0), 0, 0))
        assertTrue(backend.writes.isEmpty())
        assertFalse(output.isStalled)
    }

    @Test
    fun `full output stalls only after playback stops making progress`() {
        var now = 1_000_000_000L
        val backend = FakeBackend().apply { writeResults += 0 }
        val output = NativeAudioOutput.createForTesting(
            NativeOutputDriver.AAUDIO,
            spec,
            backend,
            nanoTime = { now },
        )
        output.play()
        assertFalse(output.write(ByteBuffer.allocateDirect(spec.frameBytes), 0, 0))
        assertFalse(output.isStalled)
        now += 199_000_000L
        assertFalse(output.isStalled)
        now += 1_000_000L
        assertTrue(output.isStalled)
    }

    @Test
    fun `PCM ignores Media3 encoded access unit count`() {
        val backend = FakeBackend()
        val output = output(backend)
        val input = ByteBuffer.allocateDirect(spec.frameBytes)
        assertTrue(output.write(input, bytes = 0, presentationTimeUs = 0))
        assertEquals(spec.frameBytes, input.position())
        assertEquals(spec.frameBytes, backend.writes.single().byteCount)
    }

    @Test
    fun `partial PCM frame is rejected before native write`() {
        val backend = FakeBackend()
        val output = output(backend)
        val error = expectWriteError {
            output.write(ByteBuffer.allocateDirect(spec.frameBytes + 1), spec.frameBytes + 1, 0)
        }
        assertEquals(NativeFailure.INVALID_ARGUMENT, error.errorCode)
        assertFalse(error.isRecoverable)
        assertTrue(backend.writes.isEmpty())
    }

    @Test
    fun `disconnect is a recoverable write failure and consumes nothing`() {
        val backend = FakeBackend().apply { writeResults += -899 }
        val output = output(backend)
        val input = ByteBuffer.allocateDirect(spec.frameBytes)
        val error = expectWriteError { output.write(input, spec.frameBytes, 0) }
        assertEquals(-899, error.errorCode)
        assertTrue(error.isRecoverable)
        assertEquals(0, input.position())
        assertFalse(output.isStalled)
    }

    @Test
    fun `release is idempotent and later writes fail closed`() {
        val backend = FakeBackend()
        val output = output(backend)
        val listener = RecordingListener()
        output.addListener(listener)

        output.release()
        output.release()
        assertEquals(1, backend.releaseCount)
        assertEquals(1, listener.released)
        assertTrue(output.telemetry().released)
        val error = expectWriteError {
            output.write(ByteBuffer.allocateDirect(spec.frameBytes), spec.frameBytes, 0)
        }
        assertEquals(NativeFailure.RELEASED, error.errorCode)
        assertFalse(error.isRecoverable)
    }

    @Test
    fun `xrun edges notify underrun listener once`() {
        val backend = FakeBackend()
        val output = output(backend)
        val listener = RecordingListener()
        output.addListener(listener)
        val input = ByteBuffer.allocateDirect(spec.frameBytes)

        backend.xruns = 2
        assertTrue(output.write(input, spec.frameBytes, 0))
        input.clear()
        assertTrue(output.write(input, spec.frameBytes, 0))
        assertEquals(1, listener.underruns)
        backend.xruns = 3
        input.clear()
        assertTrue(output.write(input, spec.frameBytes, 0))
        assertEquals(2, listener.underruns)
    }

    @Test
    fun `telemetry preserves unknowns and reports real failure`() {
        val backend = FakeBackend().apply {
            snapshot = RawNativeSnapshot(
                sampleRateHz = 48_000,
                channelCount = 2,
                bitsPerSample = 32,
                sharing = 2,
                failure = "route disconnected",
            )
        }
        val telemetry = output(backend).telemetry()
        assertEquals("AAudio", telemetry.driverName)
        assertEquals(NativeOutputSharing.EXCLUSIVE, telemetry.sharing)
        assertEquals(48_000, telemetry.sampleRateHz)
        assertEquals(32, telemetry.bitsPerSample)
        assertNull(telemetry.bufferSizeFrames)
        assertNull(telemetry.xruns)
        assertNull(telemetry.positionUs)
        assertEquals("route disconnected", telemetry.failure)
    }

    @Test
    fun `flush and playback parameters follow Media3 contract`() {
        val backend = FakeBackend().apply {
            snapshot = RawNativeSnapshot(positionUs = 125_000, bufferSizeFrames = 480)
        }
        val output = output(backend)
        assertEquals(125_000, output.getPositionUs())
        assertEquals(480, output.getBufferSizeInFrames())
        val parameters = PlaybackParameters(1.25f, 0.9f)
        output.setPlaybackParameters(parameters)
        assertEquals(parameters, output.playbackParameters)

        output.flush()
        assertEquals(1, backend.flushCount)
        assertEquals(0, output.getPositionUs())
        assertFalse(output.isStalled)
    }

    @Test
    fun `unavailable native facts do not echo requested values`() {
        val backend = FakeBackend().apply { snapshot = RawNativeSnapshot() }
        val output = NativeAudioOutput.createForTesting(
            NativeOutputDriver.OPENSL_ES,
            NativeOutputSpec(48_000, 2, audioSessionId = 37, requestedBufferFrames = 1_000),
            backend,
        )
        assertEquals(0, output.audioSessionId)
        assertEquals(0, output.bufferSizeInFrames)
        assertNull(output.telemetry().audioSessionId)
        assertNull(output.telemetry().bufferSizeFrames)
    }

    @Test
    fun `native operation failure is not swallowed`() {
        val backend = FakeBackend().apply {
            playResult = -895
            snapshot = RawNativeSnapshot(failure = "invalid stream state")
        }
        val output = output(backend)
        try {
            output.play()
            fail("play must fail")
        } catch (expected: NativeOutputException) {
            assertTrue(expected.message.orEmpty().contains("-895"))
            assertTrue(expected.message.orEmpty().contains("invalid stream state"))
        }
    }

    private fun output(backend: FakeBackend): NativeAudioOutput =
        NativeAudioOutput.createForTesting(NativeOutputDriver.AAUDIO, spec, backend)

    private fun expectWriteError(block: () -> Unit): AudioOutput.WriteException {
        try {
            block()
            fail("Expected WriteException")
        } catch (error: AudioOutput.WriteException) {
            return error
        }
        throw AssertionError("Unreachable")
    }
}

private data class CapturedWrite(
    val offsetBytes: Int,
    val byteCount: Int,
    val direct: Boolean,
    val bytes: ByteArray,
)

private class FakeBackend : NativeOutputBackend {
    val writes = mutableListOf<CapturedWrite>()
    val writeResults = ArrayDeque<Int>()
    var snapshot = RawNativeSnapshot(
        sampleRateHz = 48_000,
        channelCount = 2,
        bitsPerSample = 32,
        bufferSizeFrames = 480,
        bufferCapacityFrames = 960,
        outputFrames = 0,
        positionUs = 0,
        sharing = 1,
    )
    var xruns = 0L
    var releaseCount = 0
    var flushCount = 0
    var playResult = 0

    override fun openAAudio(spec: NativeOutputSpec, requestExclusive: Boolean): Long = 1
    override fun openOpenSlEs(spec: NativeOutputSpec): Long = 1
    override fun play(handle: Long): Int = playResult
    override fun pause(handle: Long): Int = 0

    override fun write(handle: Long, buffer: ByteBuffer, offsetBytes: Int, byteCount: Int): Int {
        val copy = ByteArray(byteCount)
        val source = buffer.duplicate().apply { position(offsetBytes) }
        source.get(copy)
        writes += CapturedWrite(offsetBytes, byteCount, buffer.isDirect, copy)
        return writeResults.removeFirstOrNull() ?: (byteCount / 8)
    }

    override fun flush(handle: Long): Int {
        ++flushCount
        snapshot = snapshot.copy(positionUs = 0, outputFrames = 0)
        return 0
    }

    override fun stop(handle: Long): Int = 0
    override fun setVolume(handle: Long, volume: Float): Int = 0
    override fun xrunCount(handle: Long): Long = xruns
    override fun snapshot(handle: Long): RawNativeSnapshot = snapshot
    override fun release(handle: Long) {
        ++releaseCount
    }
}

private class RecordingListener : AudioOutput.Listener {
    var underruns = 0
    var released = 0
    override fun onPositionAdvancing(playoutTimeUs: Long) = Unit
    override fun onOffloadDataRequest() = Unit
    override fun onOffloadPresentationEnded() = Unit
    override fun onUnderrun() {
        ++underruns
    }
    override fun onReleased() {
        ++released
    }
}
