package com.music.bitchord.playback.dsd.raw.render

import androidx.media3.common.C
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.NativeDsdPacking

/** Fully resolved output parameters for one raw-DSD stream. */
data class RawDsdTransportConfiguration(
    val dsdBitRate: Int,
    val channelCount: Int,
    val kind: DsdTransportKind,
    val nativePacking: NativeDsdPacking?,
    val rawAlternateSetting: Int?,
    val finalPaddingSamples: Int,
) {
    init {
        require(dsdBitRate > 0 && channelCount > 0) { "Invalid raw DSD stream parameters." }
        require(finalPaddingSamples in 0 until 32) { "Final padding must be less than one U32 word." }
        when (kind) {
            DsdTransportKind.DOP -> require(nativePacking == null && rawAlternateSetting == null) {
                "DoP output cannot have native-only parameters."
            }
            DsdTransportKind.NATIVE -> require(
                (nativePacking == NativeDsdPacking.DSD_U32_BE ||
                    nativePacking == NativeDsdPacking.DSD_U32_LE) && rawAlternateSetting != null,
            ) { "Native raw-DSD output requires U32 packing and a raw alternate setting." }
            DsdTransportKind.UNSUPPORTED -> throw IllegalArgumentException("No raw DSD transport is available.")
        }
    }
}

/** Declared output support; a renderer never probes or changes hardware to discover this. */
data class RawDsdTransportCapabilities(
    val supportedKinds: Set<DsdTransportKind>,
    val supportedNativePackings: Set<NativeDsdPacking> = emptySet(),
    val maxChannelCount: Int = 6,
) {
    init {
        require(maxChannelCount > 0) { "Maximum channel count must be positive." }
    }

    fun supports(configuration: RawDsdTransportConfiguration): Boolean =
        configuration.channelCount <= maxChannelCount &&
            configuration.kind in supportedKinds &&
            (configuration.kind != DsdTransportKind.NATIVE ||
                configuration.nativePacking in supportedNativePackings)
}

/**
 * Byte-oriented DSD output. Implementations must not convert these bytes to PCM or pass them to an
 * audio sink. [configure], [flush], and [release] are called on the playback thread.
 */
interface RawDsdTransport {
    val capabilities: RawDsdTransportCapabilities

    fun configure(configuration: RawDsdTransportConfiguration)

    /**
     * Accepts between zero and [length] bytes and returns the exact count accepted. Zero means the
     * output is full; the renderer retains and retries the same packet. [offset] is relative to the
     * original encoded packet, so a nonzero value identifies a continuation after a partial write.
     *
     * [presentationTimeUs] remains the original packet timestamp on every continuation.
     * [validDsdSamples] is the valid one-bit sample count per channel in the whole original packet;
     * it can be smaller than the padded U32/DoP capacity in the final packet. Implementations must
     * use it to stop at the gapless stream end. The byte array must be copied if retained after
     * this call returns.
     */
    fun writeOpaque(
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        validDsdSamples: Int,
    ): Int

    /** Returns the played media position, or [C.TIME_UNSET] if it is not currently available. */
    fun positionUs(): Long

    /**
     * Finishes this stream after its final packet has been accepted. Implementations must preserve
     * byte identity, submit actual residual bytes without synthesized padding, and let earlier
     * queued writes complete. Repeated calls in the same configure/flush generation are no-ops.
     */
    fun finish()

    /** Discards queued output and restarts transport-side packet accounting at the next write. */
    fun flush()

    /** Permanently releases this transport. */
    fun release()
}
