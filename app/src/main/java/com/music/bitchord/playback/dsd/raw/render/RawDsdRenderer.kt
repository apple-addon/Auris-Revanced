package com.music.bitchord.playback.dsd.raw.render

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.RendererCapabilities
import com.music.bitchord.playback.dsd.DopEncoder
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.DsdTransportPlan
import com.music.bitchord.playback.dsd.NativeDsdPacker
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.raw.RawDsdConstants
import com.music.bitchord.playback.dsd.raw.RawDsdPacking

/**
 * Media3 renderer for the custom raw-DSD access units emitted by the raw extractors. There is no
 * decoder, PCM output format, audio processor, or audio sink in this path: U32 input words are only
 * rearranged with [NativeDsdPacker], or encoded as DoP 1.1 with [DopEncoder].
 */
@UnstableApi
class RawDsdRenderer(
    private val transport: RawDsdTransport,
    private val transportPlan: DsdTransportPlan,
) : BaseRenderer(C.TRACK_TYPE_AUDIO), MediaClock {
    private val inputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    private var inputFormat: Format? = null
    private var inputPacking: NativeDsdPacking? = null
    private var outputConfiguration: RawDsdTransportConfiguration? = null
    private var dopEncoder: DopEncoder? = null
    private var pendingPacket: PendingPacket? = null
    private var sourceEnded = false
    private var transportFinished = false
    private var currentPositionUs = 0L
    private var allowPositionDiscontinuity = true
    private var released = false

    override fun getName(): String = "RawDsdRenderer"

    override fun getMediaClock(): MediaClock = this

    override fun supportsFormat(format: Format): Int {
        if (format.sampleMimeType != RawDsdConstants.MIME_DSD) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        val supported = try {
            val configuration = createOutputConfiguration(format)
            inputPackingFor(format) != null && transport.capabilities.supports(configuration)
        } catch (_: IllegalArgumentException) {
            false
        }
        return RendererCapabilities.create(
            if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE,
        )
    }

    override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        while (true) {
            if (pendingPacket != null) {
                writePendingPacket()
                if (pendingPacket != null) return
            }
            if (sourceEnded) {
                finishTransport()
                return
            }

            inputBuffer.clear()
            val sourceFormatHolder = getFormatHolder()
            when (val result = readSource(sourceFormatHolder, inputBuffer, 0)) {
                C.RESULT_FORMAT_READ -> configureInput(requireNotNull(sourceFormatHolder.format))
                C.RESULT_BUFFER_READ -> {
                    if (inputBuffer.isEndOfStream) {
                        sourceEnded = true
                        finishTransport()
                        return
                    }
                    check(!inputBuffer.isEncrypted) { "Encrypted raw DSD is unsupported." }
                    inputBuffer.flip()
                    pendingPacket = encodeInputBuffer(inputBuffer)
                    inputBuffer.clear()
                    writePendingPacket()
                    if (pendingPacket != null) return
                }
                C.RESULT_NOTHING_READ -> return
                else -> throw createRendererException(
                    IllegalStateException("Unexpected sample read result: $result."),
                    inputFormat,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                )
            }
        }
    }

    override fun isEnded(): Boolean = sourceEnded && pendingPacket == null && transportFinished

    override fun isReady(): Boolean = !isEnded && (pendingPacket != null || isSourceReady)

    override fun onPositionReset(
        positionUs: Long,
        joining: Boolean,
        sampleStreamIsResetToKeyFrame: Boolean,
    ) {
        resetPipeline(positionUs, flushTransport = true)
    }

    override fun onDisabled() {
        resetPipeline(currentPositionUs, flushTransport = true)
        clearInputConfiguration()
    }

    override fun onReset() {
        resetPipeline(0L, flushTransport = true)
        clearInputConfiguration()
    }

    override fun onRelease() {
        resetPipeline(currentPositionUs, flushTransport = false)
        clearInputConfiguration()
        if (!released) {
            released = true
            transport.release()
        }
    }

    override fun getPositionUs(): Long {
        if (outputConfiguration == null) return currentPositionUs
        val transportPositionUs = transport.positionUs()
        if (transportPositionUs != C.TIME_UNSET) {
            currentPositionUs = if (allowPositionDiscontinuity) {
                transportPositionUs
            } else {
                maxOf(currentPositionUs, transportPositionUs)
            }
            allowPositionDiscontinuity = false
        }
        return currentPositionUs
    }

    /** Raw DSD has no PCM time-stretch/pitch path, so the clock remains at real-time playback. */
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    private fun configureInput(format: Format) {
        if (format == inputFormat) return
        check(pendingPacket == null) { "A format changed while a raw DSD packet was pending." }
        val newConfiguration = try {
            createOutputConfiguration(format)
        } catch (failure: IllegalArgumentException) {
            throw createRendererException(
                failure,
                format,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            )
        }
        val newInputPacking = inputPackingFor(format) ?: throw createRendererException(
            IllegalArgumentException("Unsupported raw DSD input codec: ${format.codecs}."),
            format,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
        if (!transport.capabilities.supports(newConfiguration)) {
            throw createRendererException(
                IllegalArgumentException("The selected raw DSD transport rejected the stream format."),
                format,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            )
        }

        if (outputConfiguration != null) transport.flush()
        try {
            transport.configure(newConfiguration)
        } catch (failure: Exception) {
            throw createRendererException(
                failure,
                format,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            )
        }
        inputFormat = format
        inputPacking = newInputPacking
        outputConfiguration = newConfiguration
        dopEncoder = if (newConfiguration.kind == DsdTransportKind.DOP) {
            DopEncoder(newConfiguration.channelCount)
        } else {
            null
        }
        sourceEnded = false
        transportFinished = false
        allowPositionDiscontinuity = true
    }

    private fun encodeInputBuffer(buffer: DecoderInputBuffer): PendingPacket {
        val format = requireNotNull(inputFormat)
        val packing = requireNotNull(inputPacking)
        val configuration = requireNotNull(outputConfiguration)
        try {
            val source = requireNotNull(buffer.data).duplicate()
            val packedInput = ByteArray(source.remaining())
            source.get(packedInput)
            val inputFrameBytes = packing.frameBytes(configuration.channelCount)
            require(packedInput.isNotEmpty() && packedInput.size % inputFrameBytes == 0) {
                "Raw DSD sample does not contain complete multichannel U32 words."
            }
            val paddedSamples = packedInput.size / inputFrameBytes * packing.bitsPerSlot
            val validSamples = if (buffer.isLastSample) {
                paddedSamples - configuration.finalPaddingSamples
            } else {
                paddedSamples
            }
            require(validSamples in 1..paddedSamples) { "Invalid final raw DSD sample count." }

            val canonical = NativeDsdPacker.unpack(packedInput, configuration.channelCount, packing)
            val encoded = when (configuration.kind) {
                DsdTransportKind.NATIVE -> NativeDsdPacker.pack(
                    canonical,
                    configuration.channelCount,
                    requireNotNull(configuration.nativePacking),
                )
                DsdTransportKind.DOP -> {
                    val dopInputSamples = (validSamples + DOP_INPUT_SAMPLES_PER_FRAME - 1) /
                        DOP_INPUT_SAMPLES_PER_FRAME * DOP_INPUT_SAMPLES_PER_FRAME
                    val dopInputBytes = dopInputSamples / 8 * configuration.channelCount
                    requireNotNull(dopEncoder).encode(canonical, 0, dopInputBytes)
                }
                DsdTransportKind.UNSUPPORTED -> throw IllegalArgumentException("No raw DSD transport is available.")
            }
            return PendingPacket(encoded, buffer.timeUs, validSamples)
        } catch (failure: Exception) {
            if (failure is androidx.media3.exoplayer.ExoPlaybackException) throw failure
            throw createRendererException(
                failure,
                format,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )
        }
    }

    private fun finishTransport() {
        if (transportFinished) return
        transportFinished = true
        try {
            transport.finish()
        } catch (failure: Exception) {
            throw createRendererException(
                failure,
                inputFormat,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
        }
    }

    private fun writePendingPacket() {
        val packet = pendingPacket ?: return
        val remaining = packet.bytes.size - packet.offset
        val written = try {
            transport.writeOpaque(
                packet.bytes,
                packet.offset,
                remaining,
                packet.presentationTimeUs,
                packet.validDsdSamples,
            )
        } catch (failure: Exception) {
            throw createRendererException(
                failure,
                inputFormat,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
        }
        if (written !in 0..remaining) {
            throw createRendererException(
                IllegalStateException("Raw DSD transport accepted $written of $remaining bytes."),
                inputFormat,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
        }
        if (written == 0) return

        packet.offset += written
        if (packet.offset == packet.bytes.size) {
            currentPositionUs = maxOf(currentPositionUs, packet.presentationTimeUs)
            pendingPacket = null
        }
    }

    private fun resetPipeline(positionUs: Long, flushTransport: Boolean) {
        pendingPacket = null
        sourceEnded = false
        transportFinished = false
        inputBuffer.clear()
        dopEncoder?.seek()
        currentPositionUs = positionUs
        allowPositionDiscontinuity = true
        if (flushTransport) transport.flush()
    }

    private fun clearInputConfiguration() {
        inputFormat = null
        inputPacking = null
        outputConfiguration = null
        dopEncoder = null
    }

    private fun createOutputConfiguration(format: Format): RawDsdTransportConfiguration {
        require(format.sampleRate in SUPPORTED_DSD_RATES) { "Unsupported DSD bit rate: ${format.sampleRate}." }
        require(format.channelCount > 0) { "Raw DSD channel count is missing." }
        require(format.encoderDelay == 0) { "Raw DSD encoder delay is unsupported." }
        require(format.encoderPadding in 0 until 32) { "Invalid raw DSD final padding." }
        return RawDsdTransportConfiguration(
            dsdBitRate = format.sampleRate,
            channelCount = format.channelCount,
            kind = transportPlan.kind,
            nativePacking = transportPlan.packing,
            rawAlternateSetting = transportPlan.rawAlternateSetting,
            finalPaddingSamples = format.encoderPadding,
        )
    }

    private class PendingPacket(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val validDsdSamples: Int,
    ) {
        var offset: Int = 0
    }

    private companion object {
        const val DOP_INPUT_SAMPLES_PER_FRAME = 16
        val SUPPORTED_DSD_RATES = setOf(2_822_400, 5_644_800, 11_289_600, 22_579_200, 45_158_400)

        fun inputPackingFor(format: Format): NativeDsdPacking? = when (format.codecs) {
            RawDsdPacking.DSD_U32_BE.codec -> NativeDsdPacking.DSD_U32_BE
            RawDsdPacking.DSD_U32_LE.codec -> NativeDsdPacking.DSD_U32_LE
            else -> null
        }
    }
}
