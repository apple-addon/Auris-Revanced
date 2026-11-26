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

import java.io.Closeable

/**
 * Decoded-PCM description of one analysable stream.
 *
 * Everything the analysis engine needs to know about the samples is here;
 * the engine itself never touches a file, a codec or a network.
 */
class PcmFormat(
    val sampleRateHz: Int,
    val channels: Int,
    /** Decoded PCM bit depth (16/24/32 integer, or 32/64 float). */
    val bitsPerSample: Int,
    val isFloat: Boolean,
    /** Human label for the decoder's own output encoding, e.g. "PCM24" / "Float32". */
    val encodingLabel: String,
    /** Container/codec mime the stream was decoded from, when known. */
    val mimeType: String? = null,
    /** Encoded (container) bitrate in kbps, when the source can state one. */
    val bitrateKbps: Int? = null,
) {
    val isLosslessMime: Boolean
        get() = mimeType?.let {
            it.contains("flac", true) || it.contains("alac", true) ||
                it.contains("wav") || it.contains("pcm") || it.contains("dsd", true) ||
                it.contains("ape") || it.contains("wv") || it.contains("tta", true) ||
                it.contains("aiff", true)
        } == true
}

/**
 * A pull source of decoded PCM frames, interleaved float32 in [-1, 1].
 *
 * Implementations must be safe to drive from a single background thread and
 * must bound their own memory: [read] fills a caller-owned buffer and never
 * retains whole-track state. [AudioAnalyzer] relies on exactly that.
 */
interface PcmFrameSource : Closeable {
    val format: PcmFormat

    /** Media duration in ms, or -1 when the source cannot say. */
    val durationMs: Long

    /**
     * Reads up to [frames] frames into [buffer] (which must hold at least
     * `frames * format.channels` floats). Returns the number of frames read;
     * 0 means end of stream.
     */
    fun read(buffer: FloatArray, frames: Int): Int

    override fun close() {}
}

/** Raised when a source cannot be opened or decoded at all. */
class PcmSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
