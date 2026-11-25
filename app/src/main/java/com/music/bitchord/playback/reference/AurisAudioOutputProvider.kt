package com.music.bitchord.playback.reference

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ReferenceEngineMode
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Media3 1.11 output-provider bridge; stock AudioTrack remains the fallback. */
@OptIn(UnstableApi::class)
class AurisAudioOutputProvider(
    context: Context,
    private val isAudible: () -> Boolean = { true },
) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context.applicationContext).build(),
) {
    override fun getAudioOutput(outputConfig: AudioOutputProvider.OutputConfig): AudioOutput {
        val hibyRequested = AppSettings.qualityOptions.value &&
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
                return HibyAudioOutput(outputConfig, owner)
            }
        }
        return super.getAudioOutput(outputConfig)
    }

    override fun release() {
        HibyNativeTrackWriter.close(System.identityHashCode(this))
        super.release()
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
