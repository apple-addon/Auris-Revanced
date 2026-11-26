package com.music.bitchord.playback.output

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider

/** Configuration for one interleaved, little-endian PCM float output stream. */
data class NativeOutputSpec(
    val sampleRateHz: Int,
    val channelCount: Int,
    val audioSessionId: Int = 0,
    /** Zero lets the driver choose its buffer size. */
    val requestedBufferFrames: Int = 0,
) {
    init {
        require(sampleRateHz in 1..768_000) { "Sample rate must be in 1..768000 Hz" }
        require(channelCount in 1..32) { "Channel count must be in 1..32" }
        require(requestedBufferFrames >= 0) { "Buffer size cannot be negative" }
    }

    val frameBytes: Int get() = channelCount * Float.SIZE_BYTES

    companion object {
        /**
         * Converts Media3's configuration without silently transcoding. Offload,
         * tunneling, compressed PCM and non-float PCM are deliberately rejected.
         */
        @OptIn(UnstableApi::class)
        @JvmStatic
        fun fromMedia3(config: AudioOutputProvider.OutputConfig): NativeOutputSpec {
            require(config.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                "Native outputs accept ENCODING_PCM_FLOAT only"
            }
            require(!config.isOffload && !config.isTunneling) {
                "Native outputs do not implement offload or tunneling"
            }
            require(!config.usePlaybackParameters) {
                "Native outputs require Media3 to apply playback parameters upstream"
            }
            val channels = Integer.bitCount(config.channelMask)
            require(channels > 0) { "Output channel mask is empty" }
            val frameBytes = channels * Float.SIZE_BYTES
            return NativeOutputSpec(
                sampleRateHz = config.sampleRate,
                channelCount = channels,
                audioSessionId = config.audioSessionId,
                requestedBufferFrames = if (config.bufferSize > 0) {
                    config.bufferSize / frameBytes
                } else {
                    0
                },
            )
        }
    }
}

enum class NativeOutputDriver(val displayName: String) {
    AAUDIO("AAudio"),
    OPENSL_ES("OpenSL ES"),
}

enum class NativeOutputSharing {
    UNKNOWN,
    SHARED,
    EXCLUSIVE,
}

/**
 * Live driver facts. Null means that the driver cannot supply that fact; it
 * never means an assumed hardware capability or an assumed zero error count.
 */
data class NativeOutputTelemetry(
    val driverName: String,
    val sharing: NativeOutputSharing,
    /** Stream PCM rate, not a claim about the DAC or AudioFlinger mixer rate. */
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val bitsPerSample: Int?,
    val bufferSizeFrames: Int?,
    val bufferCapacityFrames: Int?,
    /** AAudio XRuns, or short OpenSL buffer-queue callbacks. */
    val xruns: Long?,
    /** Frames reported by the endpoint (AAudio) or submitted to its queue (OpenSL). */
    val outputFrames: Long?,
    val positionUs: Long?,
    val audioSessionId: Int?,
    val failure: String?,
    val released: Boolean,
)

class NativeOutputException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal const val NATIVE_VALUE_UNKNOWN = -1L

internal data class RawNativeSnapshot(
    val sampleRateHz: Long = NATIVE_VALUE_UNKNOWN,
    val channelCount: Long = NATIVE_VALUE_UNKNOWN,
    val bitsPerSample: Long = NATIVE_VALUE_UNKNOWN,
    val bufferSizeFrames: Long = NATIVE_VALUE_UNKNOWN,
    val bufferCapacityFrames: Long = NATIVE_VALUE_UNKNOWN,
    val xruns: Long = NATIVE_VALUE_UNKNOWN,
    val outputFrames: Long = NATIVE_VALUE_UNKNOWN,
    val positionUs: Long = NATIVE_VALUE_UNKNOWN,
    val sharing: Int = 0,
    val audioSessionId: Long = NATIVE_VALUE_UNKNOWN,
    val failure: String? = null,
) {
    fun toTelemetry(driver: NativeOutputDriver, released: Boolean) = NativeOutputTelemetry(
        driverName = driver.displayName,
        sharing = when (sharing) {
            1 -> NativeOutputSharing.SHARED
            2 -> NativeOutputSharing.EXCLUSIVE
            else -> NativeOutputSharing.UNKNOWN
        },
        sampleRateHz = sampleRateHz.positiveIntOrNull(),
        channelCount = channelCount.positiveIntOrNull(),
        bitsPerSample = bitsPerSample.positiveIntOrNull(),
        bufferSizeFrames = bufferSizeFrames.positiveIntOrNull(),
        bufferCapacityFrames = bufferCapacityFrames.positiveIntOrNull(),
        xruns = xruns.takeIf { it >= 0 },
        outputFrames = outputFrames.takeIf { it >= 0 },
        positionUs = positionUs.takeIf { it >= 0 },
        audioSessionId = audioSessionId.positiveIntOrNull(),
        failure = failure,
        released = released,
    )
}

private fun Long.positiveIntOrNull(): Int? =
    takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()

internal object PcmFrameMath {
    fun framesToBytes(frames: Int, frameBytes: Int): Int {
        require(frames >= 0 && frameBytes > 0)
        return Math.multiplyExact(frames, frameBytes)
    }

    fun bytesToFrames(byteCount: Int, frameBytes: Int): Int {
        require(byteCount >= 0 && frameBytes > 0)
        require(byteCount % frameBytes == 0) { "Partial PCM frame: $byteCount bytes" }
        return byteCount / frameBytes
    }

    fun framesToUs(frames: Long, sampleRateHz: Int): Long {
        require(frames >= 0 && sampleRateHz > 0)
        val seconds = frames / sampleRateHz
        val remainderUs = (frames % sampleRateHz) * 1_000_000L / sampleRateHz
        if (seconds > (Long.MAX_VALUE - remainderUs) / 1_000_000L) return Long.MAX_VALUE
        return seconds * 1_000_000L + remainderUs
    }
}
