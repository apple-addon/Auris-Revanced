package com.music.bitchord.playback.output

import java.nio.ByteBuffer

/** Internal seam for host tests; production always uses [NativeOutputBridge]. */
internal interface NativeOutputBackend {
    fun openAAudio(spec: NativeOutputSpec, requestExclusive: Boolean): Long
    fun openOpenSlEs(spec: NativeOutputSpec): Long
    fun play(handle: Long): Int
    fun pause(handle: Long): Int
    /** Returns consumed frames, or a negative driver error. */
    fun write(handle: Long, buffer: ByteBuffer, offsetBytes: Int, byteCount: Int): Int
    fun flush(handle: Long): Int
    fun stop(handle: Long): Int
    fun setVolume(handle: Long, volume: Float): Int
    fun xrunCount(handle: Long): Long
    fun snapshot(handle: Long): RawNativeSnapshot
    fun release(handle: Long)
}

/** JNI bridge. Library-load and open errors are returned, never hidden by a fallback. */
internal object NativeOutputBridge : NativeOutputBackend {
    private const val SNAPSHOT_VALUES = 10

    @Volatile
    private var loadAttempted = false
    private var loadFailure: String? = null

    @Synchronized
    private fun ensureLoaded() {
        if (loadAttempted) return
        loadAttempted = true
        try {
            System.loadLibrary("auris_native_outputs")
        } catch (error: UnsatisfiedLinkError) {
            loadFailure = error.toString()
        } catch (error: SecurityException) {
            loadFailure = error.toString()
        }
    }

    private fun requireLibrary() {
        ensureLoaded()
        loadFailure?.let { throw NativeOutputException("Native output library unavailable: $it") }
    }

    override fun openAAudio(spec: NativeOutputSpec, requestExclusive: Boolean): Long {
        requireLibrary()
        val handle = nativeCreateAAudio(
            spec.sampleRateHz,
            spec.channelCount,
            spec.audioSessionId,
            spec.requestedBufferFrames,
            requestExclusive,
        )
        return handle.takeIf { it != 0L } ?: throw NativeOutputException(openError("AAudio"))
    }

    override fun openOpenSlEs(spec: NativeOutputSpec): Long {
        requireLibrary()
        val handle = nativeCreateOpenSlEs(
            spec.sampleRateHz,
            spec.channelCount,
            spec.audioSessionId,
            spec.requestedBufferFrames,
        )
        return handle.takeIf { it != 0L } ?: throw NativeOutputException(openError("OpenSL ES"))
    }

    private fun openError(driver: String): String =
        nativeConsumeOpenError()?.takeIf { it.isNotBlank() } ?: "$driver open failed without an error detail"

    override fun play(handle: Long): Int = nativePlay(handle)
    override fun pause(handle: Long): Int = nativePause(handle)

    override fun write(handle: Long, buffer: ByteBuffer, offsetBytes: Int, byteCount: Int): Int =
        nativeWrite(handle, buffer, offsetBytes, byteCount)

    override fun flush(handle: Long): Int = nativeFlush(handle)
    override fun stop(handle: Long): Int = nativeStop(handle)
    override fun setVolume(handle: Long, volume: Float): Int = nativeSetVolume(handle, volume)
    override fun xrunCount(handle: Long): Long = nativeXrunCount(handle)

    override fun snapshot(handle: Long): RawNativeSnapshot {
        val values = LongArray(SNAPSHOT_VALUES) { NATIVE_VALUE_UNKNOWN }
        val failure = nativeSnapshot(handle, values)
        return RawNativeSnapshot(
            sampleRateHz = values[0],
            channelCount = values[1],
            bitsPerSample = values[2],
            bufferSizeFrames = values[3],
            bufferCapacityFrames = values[4],
            xruns = values[5],
            outputFrames = values[6],
            positionUs = values[7],
            sharing = values[8].toInt(),
            audioSessionId = values[9],
            failure = failure,
        )
    }

    override fun release(handle: Long) = nativeRelease(handle)

    private external fun nativeCreateAAudio(
        sampleRateHz: Int,
        channelCount: Int,
        audioSessionId: Int,
        requestedBufferFrames: Int,
        requestExclusive: Boolean,
    ): Long

    private external fun nativeCreateOpenSlEs(
        sampleRateHz: Int,
        channelCount: Int,
        audioSessionId: Int,
        requestedBufferFrames: Int,
    ): Long

    private external fun nativeConsumeOpenError(): String?
    private external fun nativePlay(handle: Long): Int
    private external fun nativePause(handle: Long): Int
    private external fun nativeWrite(
        handle: Long,
        buffer: ByteBuffer,
        offsetBytes: Int,
        byteCount: Int,
    ): Int
    private external fun nativeFlush(handle: Long): Int
    private external fun nativeStop(handle: Long): Int
    private external fun nativeSetVolume(handle: Long, volume: Float): Int
    private external fun nativeXrunCount(handle: Long): Long
    private external fun nativeSnapshot(handle: Long, values: LongArray): String?
    private external fun nativeRelease(handle: Long)
}

internal object NativeFailure {
    const val INVALID_ARGUMENT = -12001
    const val RELEASED = -12002
    const val BACKEND_PROTOCOL = -12003
    const val INVALID_BUFFER = -12004

    /** Only transport/loss errors are recoverable by constructing a new output. */
    fun isRecoverable(errorCode: Int): Boolean = when (errorCode) {
        -899, // AAUDIO_ERROR_DISCONNECTED
        -896, // AAUDIO_ERROR_INTERNAL
        -892, // AAUDIO_ERROR_INVALID_HANDLE
        -20005, // OpenSL SL_RESULT_RESOURCE_LOST
        -20006, // OpenSL SL_RESULT_IO_ERROR
        -20016, // OpenSL SL_RESULT_CONTROL_LOST
        -> true
        else -> false
    }
}
