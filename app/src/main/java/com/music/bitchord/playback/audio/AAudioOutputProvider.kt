package com.music.bitchord.playback.audio

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native backends implementing the same pull-driven Float32 PCM lifecycle. */
enum class NativeAudioBackend {
    AAUDIO,
    OPENSL_ES,
}

/** Actual negotiated sharing mode, never merely the requested mode. */
enum class NativeAudioSharingMode {
    NOT_APPLICABLE,
    EXCLUSIVE,
    SHARED,
    UNKNOWN,
}

/** The clock responsible for the reported presentation position. */
enum class NativeAudioClock {
    NONE,
    AAUDIO_TIMESTAMP,
    AAUDIO_FRAMES_READ,
    OPENSL_QUEUE_COMPLETION,
    UNKNOWN,
}

/**
 * Configuration for one native interleaved Float32 PCM stream.
 *
 * [ringCapacityFrames] is rounded up to a power of two natively. A zero
 * [bufferSizeFrames] selects the backend default. [deviceId] uses AAudio's
 * `AAUDIO_UNSPECIFIED` value of zero and is ignored by OpenSL ES.
 */
data class NativeAudioOutputConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val ringCapacityFrames: Int = defaultNativeRingCapacityFrames(sampleRate),
    val bufferSizeFrames: Int = 0,
    val deviceId: Int = 0,
) {
    init {
        require(sampleRate in MIN_NATIVE_SAMPLE_RATE..MAX_NATIVE_SAMPLE_RATE) {
            "sampleRate must be in $MIN_NATIVE_SAMPLE_RATE..$MAX_NATIVE_SAMPLE_RATE"
        }
        require(channelCount in 1..MAX_NATIVE_CHANNELS) {
            "channelCount must be in 1..$MAX_NATIVE_CHANNELS"
        }
        require(ringCapacityFrames in 1..MAX_NATIVE_RING_FRAMES) {
            "ringCapacityFrames must be in 1..$MAX_NATIVE_RING_FRAMES"
        }
        require(bufferSizeFrames in 0..ringCapacityFrames) {
            "bufferSizeFrames must be zero or no greater than the ring capacity"
        }
        require(deviceId >= 0) { "deviceId must be non-negative" }
    }

    val frameBytes: Int get() = channelCount * Float.SIZE_BYTES
}

/** A point-in-time snapshot; all counters are since open unless noted otherwise. */
data class NativeAudioOutputTelemetry(
    val backend: NativeAudioBackend,
    val isOpen: Boolean,
    val isPlaying: Boolean,
    val sampleRate: Int,
    val channelCount: Int,
    val ringCapacityFrames: Long,
    val bufferCapacityFrames: Long,
    val framesPerBuffer: Long,
    val sharingMode: NativeAudioSharingMode,
    val lowLatency: Boolean,
    /** Last presentation estimate, or -1 when no clock has produced a position. */
    val positionFrames: Long,
    val clock: NativeAudioClock,
    /** Application audio frames accepted by the native ring since open/flush. */
    val framesWritten: Long,
    /** Ring starvation events; distinct from hardware xruns for AAudio. */
    val underrunCount: Long,
    /** Hardware xruns reported by AAudio; always zero for OpenSL ES. */
    val xrunCount: Long,
    val recoveryCount: Long,
    val disconnectCount: Long,
    /** Application audio still in the ring and, for OpenSL ES, its queue. */
    val bufferedFrames: Long,
    /** Raw AAudio stream state or OpenSL play state for diagnostics. */
    val nativeState: Int,
    val lastErrorCode: Int,
    /** Last native error, including an error from which playback recovered. */
    val lastError: String?,
) {
    companion object {
        internal fun closed(backend: NativeAudioBackend) = NativeAudioOutputTelemetry(
            backend = backend,
            isOpen = false,
            isPlaying = false,
            sampleRate = 0,
            channelCount = 0,
            ringCapacityFrames = 0,
            bufferCapacityFrames = 0,
            framesPerBuffer = 0,
            sharingMode = NativeAudioSharingMode.NOT_APPLICABLE,
            lowLatency = false,
            positionFrames = -1,
            clock = NativeAudioClock.NONE,
            framesWritten = 0,
            underrunCount = 0,
            xrunCount = 0,
            recoveryCount = 0,
            disconnectCount = 0,
            bufferedFrames = 0,
            nativeState = 0,
            lastErrorCode = 0,
            lastError = null,
        )
    }
}

/** Lifecycle expected by a later Media3 AudioOutput adapter. */
interface NativeFloatAudioOutput : Closeable {
    val isOpen: Boolean
    val isPlaying: Boolean

    /** Opens a new stream, replacing any previous stream. False means inspect [telemetry]. */
    fun open(config: NativeAudioOutputConfig): Boolean

    fun start(): Boolean
    fun pause(): Boolean

    /** Discards queued application audio and resets the presentation position to zero. */
    fun flush(): Boolean

    /**
     * Copies up to [ByteBuffer.remaining] bytes into the native ring without blocking.
     * Returns accepted frames and advances the buffer position by exactly that many frames.
     */
    fun write(buffer: ByteBuffer): Int

    /** Returns the current presentation frame, or -1 if no position is available. */
    fun positionFrames(): Long

    fun telemetry(): NativeAudioOutputTelemetry

    /** Idempotent. A released provider cannot be reopened. */
    fun release()

    override fun close()
}

internal const val MIN_NATIVE_SAMPLE_RATE = 4_000
internal const val MAX_NATIVE_SAMPLE_RATE = 768_000
internal const val MAX_NATIVE_CHANNELS = 8
internal const val MAX_NATIVE_RING_FRAMES = 1 shl 22
internal const val NATIVE_TELEMETRY_VALUE_COUNT = 19

internal fun defaultNativeRingCapacityFrames(sampleRate: Int): Int =
    (sampleRate / 2).coerceAtLeast(4_096)

internal fun directFloatFrameCount(buffer: ByteBuffer, frameBytes: Int): Int {
    require(buffer.isDirect) { "Native audio input must be a direct ByteBuffer" }
    require(buffer.order() == ByteOrder.nativeOrder()) {
        "Native audio input must use ${ByteOrder.nativeOrder()} byte order"
    }
    require(frameBytes > 0) { "frameBytes must be positive" }
    require(buffer.remaining() % frameBytes == 0) {
        "Native audio input must contain complete interleaved Float32 frames"
    }
    return buffer.remaining() / frameBytes
}

internal fun decodeNativeAudioTelemetry(
    backend: NativeAudioBackend,
    values: LongArray,
    lastError: String?,
): NativeAudioOutputTelemetry {
    require(values.size == NATIVE_TELEMETRY_VALUE_COUNT) {
        "Expected $NATIVE_TELEMETRY_VALUE_COUNT native telemetry values"
    }
    val sharingMode = when (values[7].toInt()) {
        -1 -> NativeAudioSharingMode.NOT_APPLICABLE
        0 -> NativeAudioSharingMode.EXCLUSIVE
        1 -> NativeAudioSharingMode.SHARED
        else -> NativeAudioSharingMode.UNKNOWN
    }
    val clock = when (values[10].toInt()) {
        0 -> NativeAudioClock.NONE
        1 -> NativeAudioClock.AAUDIO_TIMESTAMP
        2 -> NativeAudioClock.AAUDIO_FRAMES_READ
        3 -> NativeAudioClock.OPENSL_QUEUE_COMPLETION
        else -> NativeAudioClock.UNKNOWN
    }
    return NativeAudioOutputTelemetry(
        backend = backend,
        isOpen = values[0] != 0L,
        isPlaying = values[1] != 0L,
        sampleRate = values[2].toInt(),
        channelCount = values[3].toInt(),
        ringCapacityFrames = values[4],
        bufferCapacityFrames = values[5],
        framesPerBuffer = values[6],
        sharingMode = sharingMode,
        lowLatency = values[8] != 0L,
        positionFrames = values[9],
        clock = clock,
        framesWritten = values[11],
        underrunCount = values[12],
        xrunCount = values[13],
        recoveryCount = values[14],
        disconnectCount = values[15],
        bufferedFrames = values[16],
        nativeState = values[17].toInt(),
        lastErrorCode = values[18].toInt(),
        lastError = lastError,
    )
}

internal object NativeAudioOutputLibrary {
    private val loading = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        System.loadLibrary("bitchord_audio_outputs")
        true
    }

    fun load(): Boolean = loading.value
}

/**
 * Real AAudio Float32 output with exclusive/low-latency negotiation, shared-mode
 * fallback, deferred error/disconnect/xrun recovery, and timestamp-based position.
 *
 * All public operations are serialized for native handle safety. The native audio
 * callback does not take this monitor or any native lock. [write] is non-blocking;
 * zero means the preallocated ring is temporarily full or the stream is recovering.
 */
class AAudioOutputProvider : NativeFloatAudioOutput {
    @Volatile private var nativeHandle = 0L
    @Volatile private var lastTelemetry = NativeAudioOutputTelemetry.closed(NativeAudioBackend.AAUDIO)
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
        check(!released) { "AAudioOutputProvider has been released" }
        destroyHandle()
        activeConfig = config
        lastTelemetry = NativeAudioOutputTelemetry.closed(NativeAudioBackend.AAUDIO).copy(
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
        check(handle != 0L && config != null) { "AAudio output is not initialized" }
        directFloatFrameCount(buffer, config.frameBytes)
        val position = buffer.position()
        val byteCount = buffer.remaining()
        val accepted = nativeWrite(handle, buffer, position, byteCount)
        check(accepted >= 0 && accepted <= byteCount / config.frameBytes) {
            "Native AAudio writer returned an invalid frame count: $accepted"
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
        val values = checkNotNull(nativeTelemetry(handle)) { "Could not allocate AAudio telemetry" }
        return decodeNativeAudioTelemetry(
            NativeAudioBackend.AAUDIO,
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
