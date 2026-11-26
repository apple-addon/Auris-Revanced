package com.music.bitchord.playback.reference.paws

import java.nio.ByteBuffer

/** PCM accepted by the host-owned WAV pipe. No resampling is done by this wrapper. */
enum class PawsInputEncoding(val wavFormatTag: Int, val bytesPerSample: Int) {
    PCM_16BIT(1, 2),
    PCM_24BIT_PACKED(1, 3),
    PCM_32BIT(1, 4),
    PCM_FLOAT(3, 4),
}

data class PawsInputFormat(
    val sampleRate: Int,
    val channels: Int,
    val encoding: PawsInputEncoding,
    val pipeCapacityBytes: Int = DEFAULT_PIPE_CAPACITY,
) {
    init {
        require(sampleRate in 8_000..768_000) { "Invalid PAWS input sampleRate=$sampleRate" }
        require(channels in 1..2) { "The verified CC tap route supports mono/stereo input only" }
        require(pipeCapacityBytes in MIN_PIPE_CAPACITY..MAX_PIPE_CAPACITY) {
            "pipeCapacityBytes must be $MIN_PIPE_CAPACITY..$MAX_PIPE_CAPACITY"
        }
    }

    val frameBytes: Int = channels * encoding.bytesPerSample

    companion object {
        const val DEFAULT_PIPE_CAPACITY = 256 * 1024
        const val MIN_PIPE_CAPACITY = 16 * 1024
        const val MAX_PIPE_CAPACITY = 4 * 1024 * 1024
    }
}

/** Native PAWS sample-format numbers, as used by PipelineInfoBlob and Cy0.p(). */
enum class PawsOutputEncoding(val nativeValue: Int, val bytesPerSample: Int) {
    PCM_16BIT(1, 2),
    PCM_32BIT(2, 4),
    PCM_FLOAT(3, 4),
    PCM_FLOAT64(4, 8),
    PCM_24BIT_PACKED(20, 3),
    PCM_8BIT(30, 1),
    UNKNOWN(Int.MIN_VALUE, 0),
    ;

    companion object {
        @JvmStatic
        fun fromNative(value: Int): PawsOutputEncoding =
            entries.firstOrNull { it.nativeValue == value } ?: UNKNOWN
    }
}

data class PawsPluginInfo(
    val id: Int,
    val flags: Int,
    val uniqueName: String,
    val displayName: String,
)

data class PawsOutputInfo(
    val sampleRate: Int,
    val channels: Int,
    val encoding: PawsOutputEncoding,
    val pipelineFlags: Long,
    val pipelineState: Int,
) {
    val frameBytes: Int = channels * encoding.bytesPerSample
    val isPcm: Boolean = encoding != PawsOutputEncoding.UNKNOWN && frameBytes > 0
}

/**
 * Mutable result reused by the caller. process() allocates nothing and creates no result objects.
 */
class PawsProcessResult {
    @JvmField var consumedInputBytes: Int = 0
    @JvmField var producedOutputBytes: Int = 0
    @JvmField var endOfInputAccepted: Boolean = false
    @JvmField var endOfNativeStream: Boolean = false
    @JvmField var outputFormatChanged: Boolean = false
    @JvmField var error: Int = 0

    fun reset() {
        consumedInputBytes = 0
        producedOutputBytes = 0
        endOfInputAccepted = false
        endOfNativeStream = false
        outputFormatChanged = false
        error = 0
    }
}

/** Counters are evidence, not a claim made merely because the native library loaded. */
data class PawsEvidence(
    val inputBytesWrittenToPipe: Long,
    val processedBytesReadFromCc: Long,
    val outputBytesDelivered: Long,
    val nativePcmTapVerified: Boolean,
    val outputInfo: PawsOutputInfo,
)

internal object PawsBufferPositions {
    fun commit(input: ByteBuffer?, inputPosition: Int, output: ByteBuffer, outputPosition: Int, result: PawsProcessResult) {
        input?.position(inputPosition + result.consumedInputBytes)
        output.position(outputPosition + result.producedOutputBytes)
    }
}

internal object PawsPluginManifest {
    /** Native emits one record per line: id<TAB>flags<TAB>uniqueName<TAB>displayName. */
    fun parse(manifest: String): List<PawsPluginInfo> = manifest.lineSequence()
        .filter { it.isNotEmpty() }
        .map { line ->
            val fields = line.split('\t')
            require(fields.size == 4) { "Malformed PAWS plugin record" }
            PawsPluginInfo(
                id = fields[0].toInt(),
                flags = fields[1].toInt(),
                uniqueName = fields[2],
                displayName = fields[3],
            )
        }
        .toList()
}
