/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.audio.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded-PCM source for one media URI.
 *
 * Uses the platform's own `MediaExtractor` + `MediaCodec` stack — the same
 * decoders ExoPlayer drives — and converts their output to interleaved
 * float32. No third-party native library is loaded, linked or called.
 *
 * [open] decodes the first output buffer before returning, so the reported
 * [PcmFormat] is the decoder's real output (rate, channels and PCM encoding)
 * rather than the container's claim. Reads are chunked and bounded; nothing
 * larger than one codec output buffer is retained.
 */
class MediaPcmSource private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    override val format: PcmFormat,
    override val durationMs: Long,
    private var decodedEncoding: Int,
    initiallyPending: FloatArray,
) : PcmFrameSource {

    private val bufferInfo = MediaCodec.BufferInfo()
    private var pending: FloatArray = initiallyPending
    private var pendingOffset = 0
    private var inputDone = false
    private var outputDone = false
    private var released = false

    override fun read(buffer: FloatArray, frames: Int): Int {
        if (released) return 0
        if (frames <= 0) return 0
        val wanted = frames * format.channels
        var written = 0
        var stalls = 0

        while (written < wanted && !outputDone) {
            // Serve leftovers first.
            if (pendingOffset < pending.size) {
                val take = minOf(wanted - written, pending.size - pendingOffset)
                System.arraycopy(pending, pendingOffset, buffer, written, take)
                pendingOffset += take
                written += take
                if (pendingOffset >= pending.size) {
                    pending = FloatArray(0)
                    pendingOffset = 0
                }
                continue
            }

            feedInput()
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (error: IllegalStateException) {
                throw PcmSourceException(AnalysisLabels.ERR_READ, error)
            }
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    decodedEncoding = readPcmEncoding(codec.outputFormat)
                }
                outIndex >= 0 -> {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (bufferInfo.size > 0 && outBuffer != null) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        pending = convert(outBuffer, decodedEncoding)
                        pendingOffset = 0
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    stalls++
                    if (stalls > MAX_STALLS) {
                        // A decoder that neither produces nor ends: stop rather
                        // than spin forever; the caller gets what was decoded.
                        outputDone = true
                    }
                    if (inputDone && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }
        return written / format.channels
    }

    private fun feedInput() {
        if (inputDone) return
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return
        val input = codec.getInputBuffer(inputIndex) ?: return
        val size = try {
            extractor.readSampleData(input, 0)
        } catch (error: RuntimeException) {
            throw PcmSourceException(AnalysisLabels.ERR_READ, error)
        }
        if (size < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    override fun close() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val MAX_STALLS = 500

        /** Opens [uri] and decodes the first buffer so the format is the decoder's own. */
        fun open(context: Context, uri: Uri): MediaPcmSource {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var trackIndex = -1
                var trackFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        trackFormat = fmt
                        break
                    }
                }
                if (trackIndex < 0 || trackFormat == null) {
                    throw PcmSourceException(AnalysisLabels.ERR_UNSUPPORTED)
                }
                extractor.selectTrack(trackIndex)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                val codec = try {
                    MediaCodec.createDecoderByType(mime ?: "")
                } catch (error: Exception) {
                    throw PcmSourceException(AnalysisLabels.ERR_UNSUPPORTED, error)
                }

                // Ask for float output where the decoder honours it; fall back to
                // whatever it reports through INFO_OUTPUT_FORMAT_CHANGED.
                runCatching {
                    trackFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                }
                try {
                    codec.configure(trackFormat, null, null, 0)
                    codec.start()
                } catch (error: Exception) {
                    runCatching { codec.release() }
                    throw PcmSourceException(AnalysisLabels.ERR_CANT_OPEN, error)
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outputFormat: MediaFormat = trackFormat
                var pending = FloatArray(0)
                var encoding = readPcmEncoding(trackFormat)
                var inputDone = false

                // Pre-roll: decode until the first output buffer (or a bounded
                // number of rounds) so both format and first samples are real.
                var rounds = 0
                while (pending.isEmpty() && rounds < 256) {
                    rounds++
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val input = codec.getInputBuffer(inIndex)
                            val size = if (input != null) extractor.readSampleData(input, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = codec.outputFormat
                            encoding = readPcmEncoding(outputFormat)
                        }
                        outIndex >= 0 -> {
                            val out = codec.getOutputBuffer(outIndex)
                            if (bufferInfo.size > 0 && out != null) {
                                out.position(bufferInfo.offset)
                                out.limit(bufferInfo.offset + bufferInfo.size)
                                out.order(ByteOrder.LITTLE_ENDIAN)
                                pending = convert(out, encoding)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                    if (inputDone && outIndex < 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }

                val sampleRate = outputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
                val channels = outputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceAtLeast(1)
                val (bits, isFloat, label) = encodingDescription(encoding)
                val trackBitrate = trackFormat.intOr(MediaFormat.KEY_BIT_RATE, -1)
                val durationUs = trackFormat.longOr(MediaFormat.KEY_DURATION, -1L)
                val pcmFormat = PcmFormat(
                    sampleRateHz = sampleRate,
                    channels = channels,
                    bitsPerSample = bits,
                    isFloat = isFloat,
                    encodingLabel = label,
                    mimeType = mime,
                    bitrateKbps = if (trackBitrate > 0) trackBitrate / 1000 else null,
                )
                val durationMs = if (durationUs > 0) durationUs / 1000 else -1L
                return MediaPcmSource(
                    extractor = extractor,
                    codec = codec,
                    format = pcmFormat,
                    durationMs = durationMs,
                    decodedEncoding = encoding,
                    initiallyPending = pending,
                )
            } catch (error: PcmSourceException) {
                runCatching { extractor.release() }
                throw error
            } catch (error: Exception) {
                runCatching { extractor.release() }
                throw PcmSourceException(AnalysisLabels.ERR_CANT_OPEN, error)
            }
        }

        private fun convert(buffer: ByteBuffer, encoding: Int): FloatArray {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val out = FloatArray(buffer.remaining() / 4)
                    buffer.asFloatBuffer().get(out)
                    out
                }
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val out = FloatArray(buffer.remaining() / 3)
                    for (i in out.indices) {
                        val b0 = buffer.get().toInt() and 0xFF
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt()
                        out[i] = ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                    }
                    out
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    val out = FloatArray(buffer.remaining() / 4)
                    for (i in out.indices) out[i] = buffer.getInt() / 2_147_483_648f
                    out
                }
                else -> {
                    val out = FloatArray(buffer.remaining() / 2)
                    for (i in out.indices) out[i] = buffer.getShort() / 32_768f
                    out
                }
            }
        }

        private fun readPcmEncoding(format: MediaFormat): Int =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

        private fun encodingDescription(encoding: Int): Triple<Int, Boolean, String> = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> Triple(32, true, "Float32")
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> Triple(24, false, "PCM24")
            AudioFormat.ENCODING_PCM_32BIT -> Triple(32, false, "PCM32")
            AudioFormat.ENCODING_PCM_8BIT -> Triple(8, false, "PCM8")
            else -> Triple(16, false, "PCM16")
        }

        private fun MediaFormat.intOr(key: String, fallback: Int): Int =
            if (containsKey(key)) getInteger(key) else fallback

        private fun MediaFormat.longOr(key: String, fallback: Long): Long =
            if (containsKey(key)) getLong(key) else fallback
    }
}
