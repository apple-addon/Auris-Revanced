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

import java.util.Locale
import kotlin.math.abs

/** Source/decoder facts shown in the header block of the analysis. */
data class SourceInfo(
    val durationMs: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val bitDepth: Int,
    val isFloat: Boolean,
    val decodedFormat: String,
    val bitrateKbps: Int?,
    val mimeType: String?,
    val declaredLossless: Boolean,
) {
    /** "Padded: N-bit content in M-bit" when the container declares more depth than the codec used. */
    fun paddedLabel(declaredDepth: Int?): String? =
        if (declaredDepth != null && !isFloat && declaredDepth > bitDepth) {
            "Padded: $bitDepth-bit content in $declaredDepth-bit"
        } else null
}

/** Per-channel peak / RMS / crest / DR / DC offset. */
data class ChannelStats(
    val peakDbfs: Double,
    val rmsDbfs: Double,
    val crestDb: Double,
    val dr: Int,
    val dcOffsetPercent: Double,
)

data class ClippingReport(
    val events: Int,
    val samples: Long,
    val firstAtMs: Long?,
    val longestSamples: Long,
    val longestAtMs: Long?,
) {
    val any: Boolean get() = events > 0
}

data class Gap(val startMs: Long, val durationMs: Long)

data class SilenceReport(
    val leadingMs: Long,
    val trailingMs: Long,
    val gapCount: Int,
    val gaps: List<Gap>,
    val longestGapMs: Long,
    val longestGapAtMs: Long,
)

enum class Band { SUBSONIC, BASS, MID, TREBLE, ULTRASONIC }

enum class SourceVerdict { FULL_BAND, LOSSLESS, LOSSY, UPSAMPLED }

/**
 * Log-spaced spectrogram: [columns] time slices x [rows] frequency bins,
 * values in dB clamped to [[SPEC_DB_MIN], 0]. One array per channel
 * ([channelSpectrograms]); the "All" view averages them.
 */
data class Spectrogram(
    val rows: Int,
    val columns: Int,
    val minHz: Double,
    val maxHz: Double,
    val channelSpectrograms: List<FloatArray>,
) {
    /** Flattened [rows*columns] dB values for [channel], averaged when null. */
    fun channelDb(channel: Int?): FloatArray {
        val out = FloatArray(rows * columns)
        if (channel != null && channel in channelSpectrograms.indices) return channelSpectrograms[channel]
        val n = channelSpectrograms.size.coerceAtLeast(1)
        for (i in out.indices) {
            var acc = 0f
            for (c in channelSpectrograms.indices) acc += channelSpectrograms[c][i]
            out[i] = acc / n
        }
        return out
    }

    companion object {
        const val SPEC_DB_MIN = -120f
    }
}

data class GainReport(
    val tagGainDb: Double?,
    val suggestedGainDb: Double,
    val headroomDb: Double,
    val afterTagDb: Double?,
    val afterSuggestedDb: Double,
)

/** The complete, immutable outcome of one [AudioAnalyzer] pass. */
data class AudioAnalysisResult(
    val source: SourceInfo,
    val spectrogram: Spectrogram,
    val perChannel: List<ChannelStats>,
    val peakDbfs: Double,
    val peakAtMs: Long,
    val rmsDbfs: Double,
    val truePeakDbtp: Double,
    val truePeakAtMs: Long,
    val dynamicRange: Int,
    val dcOffsetPercent: Double,
    val clipping: ClippingReport,
    val silence: SilenceReport,
    val spectralCutoffHz: Double,
    val nyquistHz: Double,
    val bandEnergiesDb: Map<Band, Double>,
    val correlation: Double,
    val sideMidDb: Double,
    val identicalChannels: Boolean,
    val reversedPolarity: Boolean,
    val loudnessLufs: Double,
    val lraLu: Double,
    val maxMomentaryLufs: Double,
    val maxMomentaryAtMs: Long,
    val maxShortTermLufs: Double,
    val maxShortTermAtMs: Long,
    val plrDb: Double,
    val headroomDb: Double,
    val verdict: SourceVerdict,
    val cutoffLabel: String,
    val verdictLabel: String,
    val gain: GainReport,
    val framesAnalyzed: Long,
) {
    val durationMs: Long get() = source.durationMs
    val sampleRateHz: Int get() = source.sampleRateHz
}

// ── Formatting (labels mirror the reference analysis strings) ──────────────

private fun fmt(value: Double, digits: Int = 2): String =
    if (value.isFinite()) "%.${digits}f".format(Locale.ROOT, value) else "—"

fun fmtDbfs(value: Double): String = "${fmt(value)} dBFS"
fun fmtDbtp(value: Double): String = "${fmt(value)} dBTP"
fun fmtLufs(value: Double): String = "${fmt(value)} LUFS"
fun fmtLu(value: Double): String = "${fmt(value)} LU"
fun fmtDb(value: Double): String = "${fmt(value)} dB"
fun fmtKhz(hz: Double): String =
    if (hz >= 1000.0) "${fmt(hz / 1000.0, 1).removeSuffix(".0")} kHz" else "${hz.toInt()} Hz"
fun fmtSeconds(ms: Long): String = "${fmt(ms / 1000.0, 1)} s"
fun fmtAt(ms: Long): String = "at ${fmtTimecode(ms)}"

/** 83_400 ms -> "1:23.4" */
fun fmtTimecode(ms: Long): String {
    val m = ms / 60_000
    val s = (ms % 60_000) / 1_000
    val d = (ms % 1_000) / 100
    return "$m:%02d.$d".format(Locale.ROOT, s, d)
}

/** "DR8" style label. */
fun fmtDr(dr: Int): String = "DR$dr"

fun dcPercentLabel(percent: Double): String {
    if (!percent.isFinite()) return "—"
    val text = "%.4f".format(Locale.ROOT, percent).trimEnd('0').trimEnd('.')
    return "$text%"
}

fun absMax(a: Double, b: Double): Double = if (abs(a) >= abs(b)) a else b
