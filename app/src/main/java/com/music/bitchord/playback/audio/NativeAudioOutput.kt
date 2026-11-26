package com.music.bitchord.playback.audio

import android.media.AudioDeviceInfo
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

/** Media3 adapter for a real AAudio or OpenSL ES native stream. */
@UnstableApi
class NativeAudioOutput(
    private val stream: NativeFloatAudioOutput,
    private val config: AudioOutputProvider.OutputConfig,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var released = false
    private var lastUnderruns = 0L

    override fun play() {
        if (!released && stream.start()) {
            listeners.forEach { it.onPositionAdvancing(SystemClock.elapsedRealtime()) }
        }
    }

    override fun pause() {
        if (!released) stream.pause()
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (released) return false
        val accepted = stream.write(buffer)
        val telemetry = stream.telemetry()
        if (telemetry.underrunCount != lastUnderruns) {
            lastUnderruns = telemetry.underrunCount
            listeners.forEach { it.onUnderrun() }
        }
        return accepted >= 0 && !buffer.hasRemaining()
    }

    override fun flush() {
        if (!released) stream.flush()
    }

    override fun stop() {
        if (!released) {
            stream.pause()
            stream.flush()
        }
    }

    override fun release() {
        if (released) return
        released = true
        stream.release()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) = Unit // Gain remains upstream in PrecisionAudioSink.
    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = config.audioSessionId
    override fun getSampleRate(): Int = config.sampleRate
    override fun getBufferSizeInFrames(): Long = stream.telemetry().bufferCapacityFrames
    override fun getPositionUs(): Long {
        val frames = stream.positionFrames()
        return if (frames < 0L || config.sampleRate <= 0) C.TIME_UNSET else frames * 1_000_000L / config.sampleRate
    }
    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters
    override fun isStalled(): Boolean = stream.telemetry().underrunCount > 0L

    override fun addListener(listener: AudioOutput.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners -= listener
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
}
