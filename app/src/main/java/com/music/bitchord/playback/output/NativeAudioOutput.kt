package com.music.bitchord.playback.output

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Media3 1.11-compatible PCM-float output backed by one native driver instance.
 * Construction opens the real stream; an open error throws and no output is
 * returned. Runtime driver errors fail subsequent writes instead of silently
 * switching to AudioTrack or another native driver.
 *
 * Dynamic device routing, offload/tunneling and aux-effect send control are not
 * implemented by these native APIs. Aux-effect attachment/send control is also
 * unsupported. Playback parameters are retained for the Media3 contract;
 * sample-rate conversion remains Media3's responsibility.
 */
@OptIn(UnstableApi::class)
class NativeAudioOutput internal constructor(
    val driver: NativeOutputDriver,
    private val spec: NativeOutputSpec,
    private val backend: NativeOutputBackend,
    private var nativeHandle: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : AudioOutput {
    private val listeners = CopyOnWriteArrayList<AudioOutput.Listener>()
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var released = false
    private var playing = false
    private var writePending = false
    private var lastWriteProgressNs = nanoTime()
    private var lastXrunCount = 0L
    private var lastPositionUs = 0L
    // Zero is Media3's unset ID; never return a requested ID that native did
    // not actually allocate/attach (notably OpenSL and AAudio on API 26/27).
    private var lastAudioSessionId = 0
    private var lastBufferSizeFrames = 0L
    private var heapStaging: ByteBuffer? = null
    private var lastTelemetry = RawNativeSnapshot(
        sampleRateHz = spec.sampleRateHz.toLong(),
        channelCount = spec.channelCount.toLong(),
        bitsPerSample = Float.SIZE_BITS.toLong(),
        bufferSizeFrames = NATIVE_VALUE_UNKNOWN,
        audioSessionId = NATIVE_VALUE_UNKNOWN,
    ).toTelemetry(driver, released = false)

    @Synchronized
    override fun play() {
        val handle = activeHandle()
        checkResult("Native output start", backend.play(handle))
        playing = true
        lastWriteProgressNs = nanoTime()
    }

    @Synchronized
    override fun pause() {
        val handle = activeHandle()
        checkResult("Native output pause", backend.pause(handle))
        playing = false
        writePending = false
    }

    @Synchronized
    @Throws(AudioOutput.WriteException::class)
    override fun write(buffer: ByteBuffer, bytes: Int, presentationTimeUs: Long): Boolean {
        val handle = activeHandleForWrite()
        // Media3's integer is an encoded access-unit count, not a PCM byte
        // count. Like AudioTrackAudioOutput, PCM consumes buffer.remaining().
        val byteCount = buffer.remaining()
        if (byteCount == 0) {
            writePending = false
            return true
        }
        if (byteCount % spec.frameBytes != 0) {
            throw AudioOutput.WriteException(NativeFailure.INVALID_ARGUMENT, false)
        }

        reportUnderruns(handle)
        val initialPosition = buffer.position()
        val consumedFrames = if (buffer.isDirect) {
            backend.write(handle, buffer, initialPosition, byteCount)
        } else {
            writeHeapBuffer(handle, buffer, initialPosition, byteCount)
        }
        if (consumedFrames < 0) {
            writePending = true
            throw AudioOutput.WriteException(
                consumedFrames,
                NativeFailure.isRecoverable(consumedFrames),
            )
        }
        val requestedFrames = byteCount / spec.frameBytes
        if (consumedFrames > requestedFrames) {
            throw AudioOutput.WriteException(NativeFailure.BACKEND_PROTOCOL, false)
        }
        val consumedBytes = consumedFrames * spec.frameBytes
        buffer.position(initialPosition + consumedBytes)
        if (consumedFrames > 0) lastWriteProgressNs = nanoTime()
        writePending = consumedFrames != requestedFrames
        return !writePending
    }

    private fun writeHeapBuffer(handle: Long, buffer: ByteBuffer, offset: Int, bytes: Int): Int {
        val staging = heapStaging ?: ByteBuffer.allocateDirect(HEAP_STAGING_BYTES).also {
            heapStaging = it
        }
        val chunkBytes = minOf(bytes, staging.capacity() / spec.frameBytes * spec.frameBytes)
        val source = buffer.duplicate().apply { limit(offset + chunkBytes) }
        staging.clear()
        staging.put(source)
        // Native consumes from the direct buffer's base address. The caller's
        // position advances only by the frame count that native actually took.
        return backend.write(handle, staging, 0, chunkBytes)
    }

    @Synchronized
    override fun flush() {
        val handle = nativeHandle.takeIf { !released && it != 0L } ?: return
        checkResult("Native output flush", backend.flush(handle))
        playing = false
        writePending = false
        lastPositionUs = 0L
    }

    @Synchronized
    override fun stop() {
        val handle = nativeHandle.takeIf { !released && it != 0L } ?: return
        checkResult("Native output stop", backend.stop(handle))
        playing = false
        writePending = false
    }

    @Synchronized
    override fun release() {
        if (released) return
        // Capture final facts before the handle becomes invalid. Release still
        // runs if telemetry allocation fails.
        try {
            refreshTelemetry()
        } finally {
            released = true
            val handle = nativeHandle
            nativeHandle = 0L
            try {
                if (handle != 0L) backend.release(handle)
            } finally {
                lastTelemetry = lastTelemetry.copy(released = true)
                listeners.forEach { it.onReleased() }
            }
        }
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        require(volume.isFinite() && volume in 0.0f..1.0f) { "Volume must be in 0..1" }
        val handle = activeHandle()
        checkResult("Native output volume", backend.setVolume(handle, volume))
    }

    override fun isOffloadedPlayback(): Boolean = false

    @Synchronized
    override fun getAudioSessionId(): Int {
        refreshSessionAndBufferFacts()
        return lastAudioSessionId
    }

    override fun getSampleRate(): Int = spec.sampleRateHz

    @Synchronized
    override fun getBufferSizeInFrames(): Long {
        refreshSessionAndBufferFacts()
        return lastBufferSizeFrames.coerceAtLeast(0L)
    }

    @Synchronized
    override fun getPositionUs(): Long {
        val handle = nativeHandle.takeIf { !released && it != 0L } ?: return lastPositionUs
        val snapshot = backend.snapshot(handle)
        snapshot.positionUs.takeIf { it >= 0 }?.let { lastPositionUs = it }
        lastTelemetry = snapshot.toTelemetry(driver, released = false)
        return lastPositionUs
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    @Synchronized
    override fun isStalled(): Boolean = !released && playing && writePending &&
        nanoTime() - lastWriteProgressNs >= STALL_AFTER_NS

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

    /** AAudio effects use the exposed session ID; aux send is not implemented. */
    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    /**
     * AAudio is routed at open time, not changed underneath a live native
     * stream. A matching/default device is already satisfied; a different live
     * route must be requested through the provider so a new stream is opened.
     * OpenSL exposes no post-open device selection at all.
     */
    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (device == null) return
        if (driver == NativeOutputDriver.AAUDIO &&
            (spec.audioDeviceId == 0 || device.id == spec.audioDeviceId)
        ) {
            return
        }
        throw UnsupportedOperationException(
            "${driver.displayName} cannot change physical output after it is opened",
        )
    }

    override fun canReuseAudioOutput(
        outputConfig: AudioOutputProvider.OutputConfig,
        formatConfig: AudioOutputProvider.FormatConfig,
        otherOutputConfig: AudioOutputProvider.OutputConfig,
    ): Boolean = !released &&
        otherOutputConfig.encoding == AudioFormat.ENCODING_PCM_FLOAT &&
        otherOutputConfig.sampleRate == spec.sampleRateHz &&
        Integer.bitCount(otherOutputConfig.channelMask) == spec.channelCount &&
        !otherOutputConfig.isOffload &&
        !otherOutputConfig.isTunneling &&
        !otherOutputConfig.usePlaybackParameters

    /** A fresh native snapshot; unknown facts remain null. */
    @Synchronized
    fun telemetry(): NativeOutputTelemetry {
        if (!released) refreshTelemetry()
        return lastTelemetry
    }

    private fun refreshTelemetry() {
        val handle = nativeHandle.takeIf { !released && it != 0L } ?: return
        val snapshot = backend.snapshot(handle)
        snapshot.positionUs.takeIf { it >= 0 }?.let { lastPositionUs = it }
        snapshot.audioSessionId.takeIf { it > 0 && it <= Int.MAX_VALUE }?.let {
            lastAudioSessionId = it.toInt()
        }
        snapshot.bufferSizeFrames.takeIf { it > 0 }?.let { lastBufferSizeFrames = it }
        lastTelemetry = snapshot.toTelemetry(driver, released = false)
    }

    private fun refreshSessionAndBufferFacts() {
        if (!released) refreshTelemetry()
    }

    private fun reportUnderruns(handle: Long) {
        val current = backend.xrunCount(handle)
        if (current < 0) return
        if (current > lastXrunCount) listeners.forEach { it.onUnderrun() }
        lastXrunCount = current
    }

    private fun activeHandle(): Long {
        check(!released && nativeHandle != 0L) { "Native output is released" }
        return nativeHandle
    }

    @Throws(AudioOutput.WriteException::class)
    private fun activeHandleForWrite(): Long {
        if (released || nativeHandle == 0L) {
            throw AudioOutput.WriteException(NativeFailure.RELEASED, false)
        }
        return nativeHandle
    }

    private fun checkResult(action: String, result: Int) {
        if (result >= 0) return
        val detail = runCatching { backend.snapshot(nativeHandle).failure }.getOrNull()
        throw NativeOutputException("$action failed ($result)${detail?.let { ": $it" }.orEmpty()}")
    }

    companion object {
        private const val HEAP_STAGING_BYTES = 64 * 1024
        private const val STALL_AFTER_NS = 200_000_000L

        /**
         * Requests exclusive + low-latency AAudio first. Only an open failure
         * retries as shared + low-latency; there is no fallback after streaming.
         */
        @JvmStatic
        @JvmOverloads
        fun openAAudio(
            spec: NativeOutputSpec,
            requestExclusive: Boolean = true,
            audioDeviceId: Int = spec.audioDeviceId,
        ): NativeAudioOutput {
            val routedSpec = spec.copy(audioDeviceId = audioDeviceId)
            return NativeAudioOutput(
                NativeOutputDriver.AAUDIO,
                routedSpec,
                NativeOutputBridge,
                NativeOutputBridge.openAAudio(routedSpec, requestExclusive),
            )
        }

        /** Opens the shared OpenSL ES float buffer-queue backend. */
        @JvmStatic
        fun openOpenSlEs(spec: NativeOutputSpec): NativeAudioOutput = NativeAudioOutput(
            NativeOutputDriver.OPENSL_ES,
            spec,
            NativeOutputBridge,
            NativeOutputBridge.openOpenSlEs(spec),
        )

        @JvmStatic
        @JvmOverloads
        fun openAAudio(
            config: AudioOutputProvider.OutputConfig,
            requestExclusive: Boolean = true,
            audioDeviceId: Int = 0,
        ): NativeAudioOutput = openAAudio(
            NativeOutputSpec.fromMedia3(config),
            requestExclusive,
            audioDeviceId,
        )

        @JvmStatic
        fun openOpenSlEs(config: AudioOutputProvider.OutputConfig): NativeAudioOutput =
            openOpenSlEs(NativeOutputSpec.fromMedia3(config))

        internal fun createForTesting(
            driver: NativeOutputDriver,
            spec: NativeOutputSpec,
            backend: NativeOutputBackend,
            handle: Long = 1L,
            nanoTime: () -> Long = System::nanoTime,
        ) = NativeAudioOutput(driver, spec, backend, handle, nanoTime)
    }
}
