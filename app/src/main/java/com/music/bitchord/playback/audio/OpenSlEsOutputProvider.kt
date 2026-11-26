package com.music.bitchord.playback.audio

import java.nio.ByteBuffer

/**
 * Real OpenSL ES Float32 buffer-queue output.
 *
 * The native side creates its own engine, output mix and audio player using
 * `SL_ANDROID_DATAFORMAT_PCM_EX` with 32-bit float representation. Four fixed
 * buffers are preallocated. Playback-time short reads are padded with silence
 * and counted as underruns; the position clock advances only when application audio buffers
 * complete, never for that silence padding.
 *
 * [write] is non-blocking and uses the same direct native-order ByteBuffer
 * contract as [AAudioOutputProvider]. Release is idempotent and terminal.
 */
class OpenSlEsOutputProvider : NativeFloatAudioOutput {
    @Volatile private var nativeHandle = 0L
    @Volatile private var lastTelemetry = NativeAudioOutputTelemetry.closed(NativeAudioBackend.OPENSL_ES)
    private var activeConfig: NativeAudioOutputConfig? = null
    private var released = false

    init {
        NativeAudioOutputLibrary.load()
    }

    @get:Synchronized
    override val isOpen: Boolean
        get() = nativeHandle != 0L && nativeIsOpen(nativeHandle)

    @get:Synchronized
    override val isPlaying: Boolean
        get() = nativeHandle != 0L && nativeIsPlaying(nativeHandle)

    @JvmOverloads
    fun open(
        sampleRate: Int,
        channelCount: Int,
        ringCapacityFrames: Int = defaultNativeRingCapacityFrames(sampleRate),
        bufferSizeFrames: Int = 0,
        deviceId: Int = 0,
    ): Boolean = open(
        NativeAudioOutputConfig(
            sampleRate = sampleRate,
            channelCount = channelCount,
            ringCapacityFrames = ringCapacityFrames,
            bufferSizeFrames = bufferSizeFrames,
            deviceId = deviceId,
        ),
    )

    @Synchronized
    override fun open(config: NativeAudioOutputConfig): Boolean {
        check(!released) { "OpenSlEsOutputProvider has been released" }
        destroyHandle()
        activeConfig = config
        lastTelemetry = NativeAudioOutputTelemetry.closed(NativeAudioBackend.OPENSL_ES).copy(
            sampleRate = config.sampleRate,
            channelCount = config.channelCount,
        )
        nativeHandle = nativeOpen(
            config.sampleRate,
            config.channelCount,
            config.ringCapacityFrames,
            config.bufferSizeFrames,
            config.deviceId,
        )
        if (nativeHandle == 0L) return false
        lastTelemetry = snapshot(nativeHandle)
        return lastTelemetry.isOpen
    }

    @Synchronized
    override fun start(): Boolean = nativeHandle != 0L && nativeStart(nativeHandle)

    @Synchronized
    override fun pause(): Boolean = nativeHandle != 0L && nativePause(nativeHandle)

    @Synchronized
    override fun flush(): Boolean = nativeHandle != 0L && nativeFlush(nativeHandle)

    @Synchronized
    override fun write(buffer: ByteBuffer): Int {
        val handle = nativeHandle
        val config = activeConfig
        check(handle != 0L && config != null) { "OpenSL ES output is not initialized" }
        directFloatFrameCount(buffer, config.frameBytes)
        val position = buffer.position()
        val byteCount = buffer.remaining()
        val accepted = nativeWrite(handle, buffer, position, byteCount)
        check(accepted >= 0 && accepted <= byteCount / config.frameBytes) {
            "Native OpenSL ES writer returned an invalid frame count: $accepted"
        }
        buffer.position(position + accepted * config.frameBytes)
        return accepted
    }

    @Synchronized
    override fun positionFrames(): Long =
        if (nativeHandle == 0L) -1L else nativePositionFrames(nativeHandle)

    @Synchronized
    override fun telemetry(): NativeAudioOutputTelemetry {
        if (nativeHandle != 0L) lastTelemetry = snapshot(nativeHandle)
        return lastTelemetry
    }

    @Synchronized
    override fun release() {
        destroyHandle()
        released = true
        activeConfig = null
        lastTelemetry = lastTelemetry.copy(
            isOpen = false,
            isPlaying = false,
            positionFrames = -1,
            clock = NativeAudioClock.NONE,
            bufferedFrames = 0,
        )
    }

    override fun close() = release()

    private fun snapshot(handle: Long): NativeAudioOutputTelemetry {
        val values = checkNotNull(nativeTelemetry(handle)) { "Could not allocate OpenSL ES telemetry" }
        return decodeNativeAudioTelemetry(
            NativeAudioBackend.OPENSL_ES,
            values,
            nativeLastError(handle),
        )
    }

    private fun destroyHandle() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeOpen(
        sampleRate: Int,
        channelCount: Int,
        ringCapacityFrames: Int,
        bufferSizeFrames: Int,
        deviceId: Int,
    ): Long

    private external fun nativeRelease(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativePause(handle: Long): Boolean
    private external fun nativeFlush(handle: Long): Boolean
    private external fun nativeWrite(
        handle: Long,
        buffer: ByteBuffer,
        position: Int,
        byteCount: Int,
    ): Int
    private external fun nativePositionFrames(handle: Long): Long
    private external fun nativeIsOpen(handle: Long): Boolean
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeTelemetry(handle: Long): LongArray?
    private external fun nativeLastError(handle: Long): String?
}
