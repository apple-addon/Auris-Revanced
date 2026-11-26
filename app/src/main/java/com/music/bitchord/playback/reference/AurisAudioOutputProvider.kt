package com.music.bitchord.playback.reference

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.drivers.AudioTrackHiResOutputProvider
import com.music.bitchord.playback.audio.drivers.OutputDriver
import com.music.bitchord.playback.audio.drivers.OutputDriverTelemetry
import com.music.bitchord.playback.output.NativeAudioOutput
import com.music.bitchord.playback.output.NativeOutputSharing
import com.music.bitchord.playback.output.NativeOutputTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Media3 1.11 output-provider bridge; stock AudioTrack remains the fallback. */
@OptIn(UnstableApi::class)
class AurisAudioOutputProvider(
    context: Context,
    private val isAudible: () -> Boolean = { true },
    activeDevice: () -> AudioDeviceInfo? = { null },
) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext).build(),
) {
    private val hiRes = AudioTrackHiResOutputProvider(context, activeDevice)
    private val nativeOutputs = mutableMapOf<Int, NativeAudioOutput>()

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        when (selectedDriver()) {
            OutputDriver.HI_RES -> hiRes.getFormatSupport(formatConfig)
            OutputDriver.AAUDIO, OutputDriver.OPENSL_ES -> {
                if (AppSettings.playbackSpeed.value != 1f) {
                    AudioOutputProvider.FormatSupport.UNSUPPORTED
                } else {
                    // Native PCM outputs have no offload/tunneling implementation.
                    super.getFormatSupport(formatConfig).buildUpon()
                        .setIsFormatSupportedForOffload(false)
                        .setIsGaplessSupportedForOffload(false)
                        .setIsSpeedChangeSupportedForOffload(false)
                        .build()
                }
            }
            else -> super.getFormatSupport(formatConfig)
        }

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig =
        when (selectedDriver()) {
            OutputDriver.HI_RES -> hiRes.getOutputConfig(formatConfig)
            OutputDriver.AAUDIO, OutputDriver.OPENSL_ES -> {
                val config = super.getOutputConfig(formatConfig)
                val problem = nativeConfigurationProblem(config)
                if (problem != null) {
                    throw AudioOutputProvider.ConfigurationException(problem)
                }
                config
            }
            else -> super.getOutputConfig(formatConfig)
        }

    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        when (selectedDriver()) {
            OutputDriver.HI_RES -> {
                return hiRes.getAudioOutput(outputConfig).also {
                    ReferenceEngineLoader.markDriver(
                        telemetry(
                            outputConfig,
                            "AudioTrack Hi-Res",
                            if (hiRes.lastDirect) "Direct requested" else "Shared / unverified",
                            if (hiRes.lastDirect) {
                                "Direct flag accepted by the builder; endpoint path not independently verified"
                            } else {
                                "HAL direct profile unavailable; shared fallback"
                            },
                        ),
                    )
                }
            }
            OutputDriver.AAUDIO, OutputDriver.OPENSL_ES -> {
                val driver = selectedDriver()
                nativeConfigurationProblem(outputConfig)?.let { problem ->
                    val failure = IllegalStateException(problem)
                    markNativeFailure(driver, outputConfig, problem)
                    throw AudioOutputProvider.InitializationException(failure)
                }
                try {
                    val output = when (driver) {
                        OutputDriver.AAUDIO -> NativeAudioOutput.openAAudio(outputConfig)
                        OutputDriver.OPENSL_ES -> NativeAudioOutput.openOpenSlEs(outputConfig)
                        else -> error("Not a native output driver")
                    }
                    val key = System.identityHashCode(output)
                    nativeOutputs[key] = output
                    nativeRegistrations[key] = NativeRegistration(output, isAudible)
                    output.addListener(object : AudioOutput.Listener {
                        override fun onUnderrun() = refreshNativeOutputTelemetry()
                        override fun onPositionAdvancing(presentationTimeUs: Long) = Unit
                        override fun onOffloadDataRequest() = Unit
                        override fun onOffloadPresentationEnded() = Unit
                        override fun onReleased() {
                            nativeRegistrations.remove(key)
                            refreshNativeOutputTelemetry()
                        }
                    })
                    publishNativeTelemetry(output.telemetry())
                    return output
                } catch (error: Exception) {
                    val detail = "INIT FAILED: ${driver.label}: ${error.message ?: error.javaClass.simpleName}"
                    markNativeFailure(driver, outputConfig, detail)
                    throw AudioOutputProvider.InitializationException(error)
                }
            }
            else -> Unit
        }

        val hibyRequested = AppSettings.qualityOptions.value &&
            selectedDriver() == OutputDriver.AUDIO_TRACK &&
            AppSettings.referenceEngineMode.value == ReferenceEngineMode.HIBY &&
            ReferenceEngineLoader.status.value.hiby.state == NativeLoadState.LOADED_ACTIVE &&
            isAudible() &&
            outputConfig.encoding == AudioFormat.ENCODING_PCM_16BIT &&
            !outputConfig.isOffload &&
            !outputConfig.isTunneling
        if (hibyRequested) {
            val owner = System.identityHashCode(this)
            val channels = Integer.bitCount(outputConfig.channelMask).coerceAtLeast(1)
            if (HibyNativeTrackWriter.configure(outputConfig.sampleRate, channels, owner)) {
                ReferenceEngineLoader.markDriver(telemetry(outputConfig, "HiBy Native", "Native"))
                return HibyAudioOutput(outputConfig, owner)
            }
        }
        return super.getAudioOutput(outputConfig).also {
            ReferenceEngineLoader.markDriver(telemetry(outputConfig, "AudioTrack", "Shared"))
        }
    }

    private fun selectedDriver(): OutputDriver = AppSettings.outputDriver.value

    private fun nativeConfigurationProblem(config: AudioOutputProvider.OutputConfig): String? = when {
        AppSettings.playbackSpeed.value != 1f ->
            "AAudio and OpenSL ES currently require 1.0x playback speed; choose AudioTrack for speed changes"
        config.encoding != AudioFormat.ENCODING_PCM_FLOAT ->
            "AAudio and OpenSL ES require the Media3 Float32 PCM path"
        config.isOffload || config.isTunneling ->
            "AAudio and OpenSL ES do not implement offload or tunneling"
        config.usePlaybackParameters ->
            "Native output playback parameters are unavailable; Media3 must apply them upstream"
        Integer.bitCount(config.channelMask) !in 1..32 ->
            "Native output channel mask is empty or exceeds 32 channels"
        else -> null
    }

    private fun markNativeFailure(
        driver: OutputDriver,
        config: AudioOutputProvider.OutputConfig,
        detail: String,
    ) {
        ReferenceEngineLoader.markDriver(
            telemetry(config, driver.label, driver.sharingLabel, detail),
        )
    }

    private fun telemetry(
        config: AudioOutputProvider.OutputConfig,
        driver: String,
        sharing: String,
        detail: String? = null,
    ): OutputDriverTelemetry {
        val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)
        val bits = when (config.encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        val bytesPerFrame = channels * when (config.encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            else -> 4
        }
        val frames = if (bytesPerFrame > 0) config.bufferSize / bytesPerFrame else 0
        return OutputDriverTelemetry(
            driver = driver,
            sharing = sharing,
            sampleRateHz = config.sampleRate,
            bitDepth = bits,
            bufferMs = if (config.sampleRate > 0) frames * 1000 / config.sampleRate else 0,
            xruns = null,
            detail = detail,
        )
    }

    override fun release() {
        nativeOutputs.values.toList().forEach { output -> runCatching { output.release() } }
        nativeOutputs.clear()
        HibyNativeTrackWriter.close(System.identityHashCode(this))
        hiRes.release()
        super.release()
    }

    private class NativeRegistration(
        val output: NativeAudioOutput,
        val isAudible: () -> Boolean,
    )

    companion object {
        private val nativeRegistrations = ConcurrentHashMap<Int, NativeRegistration>()
        private val mutableNativeTelemetry = MutableStateFlow<NativeOutputTelemetry?>(null)

        /** Live native endpoint facts; null means no native endpoint has reported them. */
        val nativeOutputTelemetry: StateFlow<NativeOutputTelemetry?> = mutableNativeTelemetry.asStateFlow()

        /** Polls the audible native stream without guessing any missing driver fact. */
        @JvmStatic
        fun refreshNativeOutputTelemetry() {
            val registration = nativeRegistrations.values.firstOrNull { registration ->
                runCatching { registration.isAudible() }.getOrDefault(false)
            } ?: nativeRegistrations.values.firstOrNull()
            if (registration == null) {
                mutableNativeTelemetry.value = null
                return
            }
            runCatching { publishNativeTelemetry(registration.output.telemetry()) }
        }

        private fun publishNativeTelemetry(value: NativeOutputTelemetry) {
            mutableNativeTelemetry.value = value
            ReferenceEngineLoader.markDriver(
                OutputDriverTelemetry(
                    driver = value.driverName,
                    sharing = when (value.sharing) {
                        NativeOutputSharing.SHARED -> "Shared"
                        NativeOutputSharing.EXCLUSIVE -> "Exclusive"
                        NativeOutputSharing.UNKNOWN -> "Not reported"
                    },
                    sampleRateHz = value.sampleRateHz ?: 0,
                    bitDepth = value.bitsPerSample ?: 0,
                    bufferMs = 0,
                    xruns = value.xruns,
                    detail = value.failure,
                ),
            )
        }
    }
}

/** HiBy native output with frame accounting in the emitted PCM16 clock domain. */
@OptIn(UnstableApi::class)
private class HibyAudioOutput(
    private val config: AudioOutputProvider.OutputConfig,
    private val owner: Int,
) : AudioOutput {
    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var stalled = false
    private var released = false

    override fun play() = HibyNativeTrackWriter.start(owner)

    override fun pause() {
        // Media3 also pauses for buffering. OtHiByTrackUtils must keep accepting
        // pre-roll; stop() is the transport-level endpoint operation.
    }

    override fun write(buffer: ByteBuffer, bytes: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val writable = bytes.coerceAtMost(buffer.remaining())
        val initialPosition = buffer.position()
        val slice = buffer.slice().apply { limit(writable) }
        // Keep the Media3 buffer's position in lockstep with native consumption.
        val result = HibyNativeTrackWriter.write(slice, owner)
        buffer.position(initialPosition + slice.position())
        stalled = result > 0
        if (result < 0) throw AudioOutput.WriteException(result, true)
        return result == 0 && !buffer.hasRemaining()
    }

    override fun flush() {
        val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)
        HibyNativeTrackWriter.configure(config.sampleRate, channels, owner)
        stalled = false
    }

    override fun stop() = HibyNativeTrackWriter.stop(owner)

    override fun release() {
        if (released) return
        released = true
        HibyNativeTrackWriter.close(owner)
        listeners.forEach { it.onReleased() }
    }

    override fun setVolume(volume: Float) {
        // HiBy USB volume lock and crossfade gain live upstream; never stack a
        // second software gain on the native writer.
    }

    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = config.audioSessionId
    override fun getSampleRate(): Int = config.sampleRate
    override fun getBufferSizeInFrames(): Long {
        val frameBytes = Integer.bitCount(config.channelMask).coerceAtLeast(1) * 2
        return (config.bufferSize.takeIf { it > 0 } ?: (config.sampleRate / 20)).toLong() / frameBytes
    }

    override fun getPositionUs(): Long = HibyNativeTrackWriter.positionUs()
    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters
    override fun isStalled(): Boolean = stalled

    override fun addListener(listener: AudioOutput.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners -= listener
    }

    override fun setPlaybackParameters(parameters: PlaybackParameters) {
        playbackParameters = parameters
    }

    override fun setOffloadDelayPadding(delayFrames: Int, paddingFrames: Int) = Unit
    override fun setOffloadEndOfStream() = stop()
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(device: AudioDeviceInfo?) = Unit

    override fun canReuseAudioOutput(
        outputConfig: AudioOutputProvider.OutputConfig,
        formatConfig: AudioOutputProvider.FormatConfig,
        otherOutputConfig: AudioOutputProvider.OutputConfig,
    ): Boolean = outputConfig.encoding == otherOutputConfig.encoding &&
        outputConfig.sampleRate == otherOutputConfig.sampleRate &&
        outputConfig.channelMask == otherOutputConfig.channelMask &&
        !otherOutputConfig.isOffload &&
        !otherOutputConfig.isTunneling
}
