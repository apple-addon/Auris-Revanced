package com.music.bitchord.playback.reference

import android.app.Application
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.drivers.OutputDriver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AurisAudioOutputProviderTest {
    private var previousQualityOptions = false
    private lateinit var previousMode: ReferenceEngineMode
    private lateinit var previousDriver: OutputDriver

    @Before
    fun saveSettings() {
        previousQualityOptions = AppSettings.qualityOptions.value
        previousMode = AppSettings.referenceEngineMode.value
        previousDriver = AppSettings.outputDriver.value
        AppSettings.qualityOptions.value = true
        AppSettings.outputDriver.value = OutputDriver.AUDIO_TRACK
    }

    @After
    fun restoreSettings() {
        AppSettings.qualityOptions.value = previousQualityOptions
        AppSettings.referenceEngineMode.value = previousMode
        AppSettings.outputDriver.value = previousDriver
    }

    @Test
    fun `HiBy and Hybrid negotiate PCM16 without offload or tunneling`() {
        for (mode in listOf(ReferenceEngineMode.HIBY, ReferenceEngineMode.HYBRID)) {
            AppSettings.referenceEngineMode.value = mode
            val provider = AurisAudioOutputProvider(RuntimeEnvironment.getApplication())
            try {
                val formatConfig = formatConfig(AudioFormat.ENCODING_PCM_16BIT)
                val support = provider.getFormatSupport(formatConfig)
                assertEquals("$mode", AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, support.supportLevel)
                assertFalse(support.isFormatSupportedForOffload)
                assertFalse(support.isGaplessSupportedForOffload)
                assertFalse(support.isSpeedChangeSupportedForOffload)

                val outputConfig = provider.getOutputConfig(formatConfig)
                assertEquals("$mode", AudioFormat.ENCODING_PCM_16BIT, outputConfig.encoding)
                assertFalse("$mode", outputConfig.isOffload)
                assertFalse("$mode", outputConfig.isTunneling)
                assertFalse("$mode", outputConfig.useOffloadGapless)
            } finally {
                provider.release()
            }
        }
    }

    @Test
    fun `HiBy and Hybrid reject non-PCM16 negotiation before output creation`() {
        for (mode in listOf(ReferenceEngineMode.HIBY, ReferenceEngineMode.HYBRID)) {
            AppSettings.referenceEngineMode.value = mode
            val provider = AurisAudioOutputProvider(RuntimeEnvironment.getApplication())
            try {
                val floatConfig = formatConfig(AudioFormat.ENCODING_PCM_FLOAT)
                assertEquals(
                    "$mode",
                    AudioOutputProvider.FORMAT_UNSUPPORTED,
                    provider.getFormatSupport(floatConfig).supportLevel,
                )
                try {
                    provider.getOutputConfig(floatConfig)
                    fail("$mode must reject float PCM during configuration")
                } catch (_: AudioOutputProvider.ConfigurationException) {
                    // Expected: getAudioOutput must never receive an advertised float config.
                }
            } finally {
                provider.release()
            }
        }
    }

    @Test
    fun `PCM write uses remaining bytes and preserves partial-write backpressure`() {
        var offeredBytes = -1
        val output = HibyAudioOutput(outputConfig(), owner = 7) { buffer, owner ->
            assertEquals(7, owner)
            offeredBytes = buffer.remaining()
            val consumed = buffer.remaining() / 2
            buffer.position(buffer.position() + consumed)
            buffer.remaining()
        }
        val buffer = ByteBuffer.allocateDirect(4096)
        buffer.position(256)

        assertFalse(output.write(buffer, 1, 0))
        assertEquals(3840, offeredBytes)
        assertEquals(256 + 1920, buffer.position())
        assertEquals(1920, buffer.remaining())
        assertTrue(output.isStalled)
    }

    @Test
    fun `heap-backed PCM is staged into direct storage before native write`() {
        var seen: ByteArray? = null
        var offeredDirect = false
        val output = HibyAudioOutput(outputConfig(), owner = 7) { buffer, _ ->
            offeredDirect = buffer.isDirect()
            val copy = ByteArray(buffer.remaining())
            buffer.duplicate().get(copy)
            seen = copy
            buffer.position(buffer.limit())
            0
        }
        val input = ByteBuffer.allocate(2048)
        for (index in 0 until input.capacity()) input.put(index, index.toByte())
        input.position(128)

        assertTrue(output.write(input, 1, 0))
        assertTrue(offeredDirect)
        assertArrayEquals(ByteArray(1920) { (it + 128).toByte() }, seen)
        assertEquals(input.limit(), input.position())
    }

    @Test
    fun `PCM write completes only when native consumed the whole buffer`() {
        val output = HibyAudioOutput(outputConfig(), owner = 7) { buffer, _ ->
            buffer.position(buffer.limit())
            0
        }
        val buffer = ByteBuffer.allocateDirect(4096)

        assertTrue(output.write(buffer, 1, 0))
        assertFalse(buffer.hasRemaining())
        assertFalse(output.isStalled)
    }

    private fun outputConfig(): AudioOutputProvider.OutputConfig =
        AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(4096)
            .build()

    private fun formatConfig(encoding: Int): AudioOutputProvider.FormatConfig {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(48_000)
            .setChannelCount(2)
            .build()
        return AudioOutputProvider.FormatConfig.Builder(format)
            .setEnableHighResolutionPcmOutput(true)
            .setEnableOffload(true)
            .setEnableTunneling(true)
            .setPreferredBufferSize(4096)
            .build()
    }
}
