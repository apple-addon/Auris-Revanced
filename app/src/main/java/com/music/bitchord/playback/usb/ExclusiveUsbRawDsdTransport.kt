package com.music.bitchord.playback.usb

import androidx.media3.common.C
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.DsdTransportPlan
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.raw.render.RawDsdTransport
import com.music.bitchord.playback.dsd.raw.render.RawDsdTransportCapabilities
import com.music.bitchord.playback.dsd.raw.render.RawDsdTransportConfiguration
import java.io.IOException

/**
 * [RawDsdTransport] over [ExclusiveUsbOutput]'s raw-byte queue.
 *
 * The same conservative [DsdTransportPlan] passed to the renderer must be passed here. In
 * particular, an UNSUPPORTED plan advertises no kinds and a native plan advertises only its verified
 * packing. This class never chooses DoP as a fallback for native DSD: a failed configuration throws
 * so the caller can select another renderer/output path without sending DSD bytes as PCM audio.
 *
 * End of stream is serialized through the raw writer into [com.decent.usbaudio.UsbAudioStream]'s
 * finish operation. It submits only retained source bytes; neither this adapter nor the driver adds
 * PCM/DSD padding to complete a nominal packet or URB.
 */
class ExclusiveUsbRawDsdTransport(
    private val output: RawDsdUsbOutput,
    private val plan: DsdTransportPlan,
) : RawDsdTransport {
    override val capabilities: RawDsdTransportCapabilities = when (plan.kind) {
        DsdTransportKind.DOP -> RawDsdTransportCapabilities(
            supportedKinds = setOf(DsdTransportKind.DOP),
            maxChannelCount = RawDsdWireFormats.MAX_RAW_CHANNELS,
        )
        DsdTransportKind.NATIVE -> {
            val packing = requireNotNull(plan.packing)
            val u32 = packing == NativeDsdPacking.DSD_U32_BE ||
                packing == NativeDsdPacking.DSD_U32_LE
            RawDsdTransportCapabilities(
                supportedKinds = if (u32) setOf(DsdTransportKind.NATIVE) else emptySet(),
                supportedNativePackings = if (u32) setOf(packing) else emptySet(),
                maxChannelCount = RawDsdWireFormats.MAX_RAW_CHANNELS,
            )
        }
        DsdTransportKind.UNSUPPORTED -> RawDsdTransportCapabilities(
            supportedKinds = emptySet(),
            maxChannelCount = RawDsdWireFormats.MAX_RAW_CHANNELS,
        )
    }

    private var configuration: RawDsdTransportConfiguration? = null
    private var wireFormat: RawDsdWireFormat? = null
    private var baseFrames = 0L
    private var startPresentationTimeUs: Long? = null
    private var finalEndPositionUs: Long? = null
    private var finishRequested = false
    private var released = false

    override fun configure(configuration: RawDsdTransportConfiguration) {
        check(!released) { "Raw-DSD transport is released." }
        require(capabilities.supports(configuration)) {
            "The selected USB transport plan does not support ${configuration.kind}."
        }
        require(configuration.kind == plan.kind &&
            configuration.nativePacking == plan.packing &&
            configuration.rawAlternateSetting == plan.rawAlternateSetting,
        ) { "Raw-DSD configuration does not match the selected USB transport plan." }
        val newWireFormat = RawDsdWireFormats.resolve(configuration)
        clearPacketAccounting()
        this.configuration = null
        wireFormat = null
        if (!output.configureRawDsd(configuration)) {
            throw IOException(
                output.lastFailureReason ?: "Exclusive USB rejected the raw-DSD configuration.",
            )
        }
        this.configuration = configuration
        wireFormat = newWireFormat
        baseFrames = output.rawDsdFramesWritten()
    }

    override fun writeOpaque(
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        validDsdSamples: Int,
    ): Int {
        check(!released) { "Raw-DSD transport is released." }
        check(!finishRequested) { "Raw-DSD transport is already finished." }
        val activeConfiguration = configuration ?: throw IOException("Raw-DSD output is not configured.")
        val wire = requireNotNull(wireFormat)
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid opaque-DSD byte range."
        }
        if (length == 0) return 0
        require(offset % wire.frameBytes == 0) { "An opaque continuation splits a USB frame." }
        val packetBytes = offset + length
        require(packetBytes % wire.frameBytes == 0) {
            "An opaque-DSD packet does not contain complete USB frames."
        }
        val paddedSamples = packetBytes.toLong() / wire.frameBytes * wire.dsdSamplesPerFrame
        require(validDsdSamples > 0 && validDsdSamples.toLong() <= paddedSamples) {
            "Valid DSD samples exceed the original packet capacity."
        }

        // A short accepted count is deliberate: the renderer retains the same packet and calls back
        // with its original timestamp and valid-sample count at the next offset.
        val count = minOf(length, MAX_WRITE_BYTES - MAX_WRITE_BYTES % wire.frameBytes)
        val newBaseFrames = if (startPresentationTimeUs == null) {
            output.rawDsdFramesWritten()
        } else {
            baseFrames
        }
        if (!output.writeRawDsd(data, offset, count)) return 0

        if (startPresentationTimeUs == null) {
            baseFrames = newBaseFrames
            startPresentationTimeUs = presentationTimeUs.coerceAtLeast(0L)
        }
        if (offset == 0) {
            finalEndPositionUs = if (validDsdSamples.toLong() < paddedSamples) {
                saturatedAdd(
                    presentationTimeUs.coerceAtLeast(0L),
                    samplesToUs(validDsdSamples.toLong(), activeConfiguration.dsdBitRate),
                )
            } else {
                null
            }
        }
        return count
    }

    override fun positionUs(): Long {
        val activeConfiguration = configuration ?: return C.TIME_UNSET
        val wire = wireFormat ?: return C.TIME_UNSET
        val startUs = startPresentationTimeUs ?: return C.TIME_UNSET
        val frames = (output.rawDsdFramesWritten() - baseFrames).coerceAtLeast(0L)
        val writtenDsdSamples = saturatedMultiply(frames, wire.dsdSamplesPerFrame.toLong())
        val position = saturatedAdd(
            startUs,
            samplesToUs(writtenDsdSamples, activeConfiguration.dsdBitRate),
        )
        // Native U32 must put the final slot's padding bits on the wire, but those bits are not media
        // time. DoP likewise can contain a final partial 16-sample group. Cap both at the valid end.
        return finalEndPositionUs?.let { minOf(position, it) } ?: position
    }

    override fun finish() {
        check(!released) { "Raw-DSD transport is released." }
        if (finishRequested || configuration == null) return
        finishRequested = true
        output.finishRawDsd()
    }

    override fun flush() {
        clearPacketAccounting()
        if (!released && configuration != null) output.flushRawDsd()
    }

    override fun release() {
        if (released) return
        released = true
        val hadConfiguration = configuration != null
        configuration = null
        wireFormat = null
        clearPacketAccounting()
        if (hadConfiguration) output.resetRawDsd()
    }

    private fun clearPacketAccounting() {
        baseFrames = 0L
        startPresentationTimeUs = null
        finalEndPositionUs = null
        finishRequested = false
    }

    private companion object {
        const val MAX_WRITE_BYTES = 64 * 1024

        fun samplesToUs(samples: Long, dsdBitRate: Int): Long {
            if (samples <= 0L) return 0L
            val seconds = samples / dsdBitRate
            val remaining = samples % dsdBitRate
            return saturatedAdd(
                saturatedMultiply(seconds, C.MICROS_PER_SECOND),
                remaining * C.MICROS_PER_SECOND / dsdBitRate,
            )
        }

        fun saturatedMultiply(value: Long, multiplier: Long): Long {
            if (value <= 0L || multiplier <= 0L) return 0L
            return if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier
        }

        fun saturatedAdd(first: Long, second: Long): Long =
            if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second
    }
}
