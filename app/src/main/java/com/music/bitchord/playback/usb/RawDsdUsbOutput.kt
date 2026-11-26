package com.music.bitchord.playback.usb

import androidx.media3.common.C
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.raw.render.RawDsdTransportConfiguration

/** Raw-DSD operations implemented by [ExclusiveUsbOutput], separated for headless transport tests. */
interface RawDsdUsbOutput {
    val lastFailureReason: String?

    /** Opens exactly the requested DoP or native-DSD wire format. */
    fun configureRawDsd(configuration: RawDsdTransportConfiguration): Boolean

    /**
     * Copies and queues one complete-frame opaque chunk. False means paused or full, never a short
     * write. An inactive/broken stream throws instead of looking like permanent backpressure.
     */
    fun writeRawDsd(data: ByteArray, offset: Int, length: Int): Boolean

    /** Driver frame counter used by the transport clock. */
    fun rawDsdFramesWritten(): Long

    /**
     * Submits actual native residual bytes after all earlier raw writes, without adding padding.
     * Returns after the writer has handed the residual to the kernel; in-flight URBs are not
     * discarded. Repeated calls in the same flush/configuration generation have no effect.
     */
    fun finishRawDsd()

    /** Discards queued raw output while retaining the configuration. */
    fun flushRawDsd()

    /** Closes the active raw stream without changing the user's exclusive-USB preference. */
    fun resetRawDsd()
}

/** The only two wire layouts accepted by the opaque-DSD path. */
internal data class RawDsdWireFormat(
    val pcmRateHz: Int,
    val bitDepth: Int,
    val rawEncoding: Int,
    val dsdSamplesPerFrame: Int,
    val frameBytes: Int,
)

internal object RawDsdWireFormats {
    fun resolve(configuration: RawDsdTransportConfiguration): RawDsdWireFormat {
        val channelCount = configuration.channelCount
        return when (configuration.kind) {
            DsdTransportKind.DOP -> {
                require(configuration.dsdBitRate % DOP_SAMPLES_PER_FRAME == 0) {
                    "The DSD bit rate is not divisible by the 16 DoP bits per PCM frame."
                }
                RawDsdWireFormat(
                    pcmRateHz = configuration.dsdBitRate / DOP_SAMPLES_PER_FRAME,
                    bitDepth = 24,
                    rawEncoding = C.ENCODING_PCM_24BIT,
                    dsdSamplesPerFrame = DOP_SAMPLES_PER_FRAME,
                    frameBytes = checkedFrameBytes(channelCount, 3),
                )
            }
            DsdTransportKind.NATIVE -> {
                require(
                    configuration.nativePacking == NativeDsdPacking.DSD_U32_BE ||
                        configuration.nativePacking == NativeDsdPacking.DSD_U32_LE,
                ) { "Exclusive USB native DSD supports only U32 output packing." }
                require(configuration.dsdBitRate % NATIVE_U32_SAMPLES_PER_FRAME == 0) {
                    "The DSD bit rate is not divisible by the 32 native-U32 bits per frame."
                }
                RawDsdWireFormat(
                    pcmRateHz = configuration.dsdBitRate / NATIVE_U32_SAMPLES_PER_FRAME,
                    bitDepth = 32,
                    rawEncoding = C.ENCODING_PCM_32BIT,
                    dsdSamplesPerFrame = NATIVE_U32_SAMPLES_PER_FRAME,
                    frameBytes = checkedFrameBytes(channelCount, 4),
                )
            }
            DsdTransportKind.UNSUPPORTED -> throw IllegalArgumentException(
                "No exclusive USB raw-DSD transport is available.",
            )
        }
    }

    private fun checkedFrameBytes(channels: Int, bytesPerChannel: Int): Int {
        require(channels in 1..MAX_RAW_CHANNELS) { "Unsupported raw-DSD channel count: $channels." }
        return channels * bytesPerChannel
    }

    private const val DOP_SAMPLES_PER_FRAME = 16
    private const val NATIVE_U32_SAMPLES_PER_FRAME = 32
    internal const val MAX_RAW_CHANNELS = 6
}
