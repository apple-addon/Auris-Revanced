package com.music.bitchord.playback.output

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(UnstableApi::class)
class NativeOutputSpecTest {
    @Test
    fun `Media3 float configuration preserves packet geometry`() {
        val config = outputConfig(bufferSize = 8_000, sampleRate = 96_000)
        val spec = NativeOutputSpec.fromMedia3(config)
        assertEquals(96_000, spec.sampleRateHz)
        assertEquals(2, spec.channelCount)
        assertEquals(8, spec.frameBytes)
        assertEquals(1_000, spec.requestedBufferFrames)
        assertEquals(37, spec.audioSessionId)
    }

    @Test
    fun `compressed and offload configurations are rejected`() {
        expectInvalid {
            NativeOutputSpec.fromMedia3(outputConfig(encoding = AudioFormat.ENCODING_PCM_16BIT))
        }
        expectInvalid { NativeOutputSpec.fromMedia3(outputConfig(offload = true)) }
        expectInvalid { NativeOutputSpec.fromMedia3(outputConfig(tunneling = true)) }
        expectInvalid { NativeOutputSpec.fromMedia3(outputConfig(usePlaybackParameters = true)) }
    }

    @Test
    fun `reuse requires identical float stream geometry`() {
        val output = NativeAudioOutput.createForTesting(
            NativeOutputDriver.OPENSL_ES,
            NativeOutputSpec(48_000, 2),
            ReuseBackend,
        )
        val original = outputConfig()
        val format = AudioOutputProvider.FormatConfig.Builder(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build(),
        ).build()
        assertTrue(output.canReuseAudioOutput(original, format, outputConfig()))
        assertFalse(output.canReuseAudioOutput(
            original,
            format,
            outputConfig(encoding = AudioFormat.ENCODING_PCM_16BIT),
        ))
        assertFalse(output.canReuseAudioOutput(original, format, outputConfig(sampleRate = 44_100)))
        assertFalse(output.canReuseAudioOutput(original, format, outputConfig(offload = true)))
    }

    @Test
    fun `frame accounting handles exact packets and saturation`() {
        assertEquals(32, PcmFrameMath.framesToBytes(4, 8))
        assertEquals(4, PcmFrameMath.bytesToFrames(32, 8))
        assertEquals(1_000_000, PcmFrameMath.framesToUs(48_000, 48_000))
        assertEquals(20_833_333_333_333_333, PcmFrameMath.framesToUs(1_000_000_000_000_000, 48_000))
        assertEquals(Long.MAX_VALUE, PcmFrameMath.framesToUs(Long.MAX_VALUE, 1))
        expectInvalid { PcmFrameMath.bytesToFrames(7, 8) }
    }

    @Test
    fun `only transport loss is recoverable`() {
        assertTrue(NativeFailure.isRecoverable(-899))
        assertTrue(NativeFailure.isRecoverable(-20005))
        assertFalse(NativeFailure.isRecoverable(-12001))
        assertFalse(NativeFailure.isRecoverable(-885))
    }

    private fun outputConfig(
        encoding: Int = AudioFormat.ENCODING_PCM_FLOAT,
        sampleRate: Int = 48_000,
        bufferSize: Int = 3_840,
        offload: Boolean = false,
        tunneling: Boolean = false,
        usePlaybackParameters: Boolean = false,
    ): AudioOutputProvider.OutputConfig = AudioOutputProvider.OutputConfig.Builder()
        .setEncoding(encoding)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .setBufferSize(bufferSize)
        .setAudioSessionId(37)
        .setIsOffload(offload)
        .setIsTunneling(tunneling)
        .setUsePlaybackParameters(usePlaybackParameters)
        .build()

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid configuration")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

private object ReuseBackend : NativeOutputBackend {
    override fun openAAudio(spec: NativeOutputSpec, requestExclusive: Boolean): Long = 1
    override fun openOpenSlEs(spec: NativeOutputSpec): Long = 1
    override fun play(handle: Long): Int = 0
    override fun pause(handle: Long): Int = 0
    override fun write(handle: Long, buffer: java.nio.ByteBuffer, offsetBytes: Int, byteCount: Int): Int = 0
    override fun flush(handle: Long): Int = 0
    override fun stop(handle: Long): Int = 0
    override fun setVolume(handle: Long, volume: Float): Int = 0
    override fun xrunCount(handle: Long): Long = 0
    override fun snapshot(handle: Long): RawNativeSnapshot = RawNativeSnapshot()
    override fun release(handle: Long) = Unit
}
