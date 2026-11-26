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

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ITU-R BS.1770 / EBU R128 loudness over decoded PCM.
 *
 * Momentary blocks are 400 ms with 75% overlap (100 ms hop), short-term
 * blocks are 3 s with 1 s hop; both are computed with O(1)-per-hop sliding
 * power sums, so memory is one ring buffer per channel per window plus one
 * block-power list — never the track itself.
 *
 * Gating follows the standard: integrated loudness gates at -70 LUFS
 * absolute and -10 LU relative; LRA gates short-term values at -70 LUFS
 * absolute and -20 LU relative, and is the P95-P10 spread of what survives.
 */
class LoudnessMeter(sampleRateHz: Int, channels: Int) {
    private val sampleRate = sampleRateHz
    private val channels = channels.coerceAtLeast(1)
    private val kFilters = Array(channels) { KWeighting(sampleRateHz) }

    private val momentarySize = (sampleRate * MOMENTARY_S).toInt().coerceAtLeast(1)
    private val momentaryHop = (sampleRate * MOMENTARY_HOP_S).toInt().coerceAtLeast(1)
    private val shortSize = (sampleRate * SHORT_S).toInt().coerceAtLeast(1)
    private val shortHop = (sampleRate * SHORT_HOP_S).toInt().coerceAtLeast(1)

    // Ring buffers of K-weighted squared samples per channel, with sliding sums.
    private val momentaryRing = Array(channels) { DoubleArray(momentarySize) }
    private val shortRing = Array(channels) { DoubleArray(shortSize) }
    private val momentarySum = DoubleArray(channels)
    private val shortSum = DoubleArray(channels)
    private var momentaryPos = 0
    private var shortPos = 0
    private var frameIndex = 0L

    /** (startMs, blockPower) for every emitted momentary block. */
    private val momentaryBlocks = ArrayList<Pair<Long, Double>>()
    /** (startMs, blockPower) for every emitted short-term block. */
    private val shortBlocks = ArrayList<Pair<Long, Double>>()

    fun process(frame: FloatArray) {
        for (c in 0 until channels) {
            val z = kFilters[c].process(frame[c].toDouble())
            val z2 = z * z
            momentarySum[c] += z2 - momentaryRing[c][momentaryPos]
            momentaryRing[c][momentaryPos] = z2
            shortSum[c] += z2 - shortRing[c][shortPos]
            shortRing[c][shortPos] = z2
        }
        momentaryPos = (momentaryPos + 1) % momentarySize
        shortPos = (shortPos + 1) % shortSize
        frameIndex++
        // The rings hold the last blockSize samples, so once the stream is
        // longer than the window every hop emits a complete block whose
        // start is frameIndex - blockSize.
        if (frameIndex % momentaryHop == 0L && frameIndex >= momentarySize) {
            // BS.1770 sums the channel powers (G_i = 1); the mean square of the
            // block is the ring sum divided by its length.
            val power = (0 until channels).sumOf { momentarySum[it] } / momentarySize
            momentaryBlocks.add((frameIndex - momentarySize) * 1000L / sampleRate to power)
        }
        if (frameIndex % shortHop == 0L && frameIndex >= shortSize) {
            val power = (0 until channels).sumOf { shortSum[it] } / shortSize
            shortBlocks.add((frameIndex - shortSize) * 1000L / sampleRate to power)
        }
    }

    private fun blockLoudness(power: Double): Double = -0.691 + 10.0 * log10(power)

    fun result(): LoudnessResult {
        val momentary = momentaryBlocks.map { (ms, p) -> ms to blockLoudness(p) }.filter { it.second > ABSOLUTE_GATE_LUFS }
        val integrated: Double
        val maxMomentary: Double
        val maxMomentaryAtMs: Long
        if (momentary.isEmpty()) {
            integrated = Double.NEGATIVE_INFINITY
            maxMomentary = Double.NEGATIVE_INFINITY
            maxMomentaryAtMs = 0L
        } else {
            val ungatedMeanPower = momentary.filter { it.second > ABSOLUTE_GATE_LUFS }
                .map { 10.0.pow((it.second + 0.691) / 10.0) }.average()
            val relativeThreshold = -0.691 + 10.0 * log10(ungatedMeanPower) - RELATIVE_GATE_DB
            val gated = momentary.filter { it.second >= relativeThreshold }
            integrated = if (gated.isEmpty()) Double.NEGATIVE_INFINITY else {
                -0.691 + 10.0 * log10(gated.map { 10.0.pow((it.second + 0.691) / 10.0) }.average())
            }
            val maxM = momentary.maxBy { it.second }
            maxMomentary = maxM.second
            maxMomentaryAtMs = maxM.first.coerceAtLeast(0L)
        }

        val shorts = shortBlocks.map { (ms, p) -> ms to blockLoudness(p) }
        val maxShort: Double
        val maxShortAtMs: Long
        val lra: Double
        if (shorts.isEmpty()) {
            maxShort = Double.NEGATIVE_INFINITY
            maxShortAtMs = 0L
            lra = 0.0
        } else {
            val maxS = shorts.maxBy { it.second }
            maxShort = maxS.second
            maxShortAtMs = maxS.first.coerceAtLeast(0L)
            val absGated = shorts.filter { it.second > ABSOLUTE_GATE_LUFS }
            if (absGated.size >= 2) {
                val ungatedPower = absGated.map { 10.0.pow((it.second + 0.691) / 10.0) }.average()
                val relThresh = -0.691 + 10.0 * log10(ungatedPower) - LRA_RELATIVE_GATE_DB
                val gated = absGated.filter { it.second >= relThresh }.map { it.second }.sorted()
                lra = if (gated.size >= 2) percentile(gated, 0.95) - percentile(gated, 0.10) else 0.0
            } else {
                lra = 0.0
            }
        }
        return LoudnessResult(
            integratedLufs = integrated,
            lraLu = lra.coerceAtLeast(0.0),
            maxMomentaryLufs = maxMomentary,
            maxMomentaryAtMs = maxMomentaryAtMs,
            maxShortTermLufs = maxShort,
            maxShortTermAtMs = maxShortAtMs,
        )
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return Double.NEGATIVE_INFINITY
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    companion object {
        const val MOMENTARY_S = 0.4
        const val MOMENTARY_HOP_S = 0.1
        const val SHORT_S = 3.0
        const val SHORT_HOP_S = 1.0
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_DB = 10.0
        const val LRA_RELATIVE_GATE_DB = 20.0

        /** ReplayGain-style reference loudness the suggested gain aims at. */
        const val SUGGESTED_TARGET_LUFS = -18.0
    }
}

data class LoudnessResult(
    val integratedLufs: Double,
    val lraLu: Double,
    val maxMomentaryLufs: Double,
    val maxMomentaryAtMs: Long,
    val maxShortTermLufs: Double,
    val maxShortTermAtMs: Long,
)

/** DR (TT dynamic range): peak sample power against the loudest 20% of 3 s blocks. */
object DynamicRange {
    fun dr(blocks: List<Double>, peakPower: Double): Int {
        if (blocks.isEmpty() || peakPower <= 0.0) return 0
        val sorted = blocks.sortedDescending()
        val topCount = max(1, (sorted.size * 0.20).toInt())
        val topMean = sorted.take(topCount).average()
        if (topMean <= 0.0) return 0
        return (10.0 * log10(peakPower / topMean)).toInt().coerceIn(0, 99)
    }

    /** Stereo correlation in [-1, 1] from running sums. */
    fun correlation(sumL2: Double, sumR2: Double, sumLR: Double): Double {
        val denom = sqrt(sumL2 * sumR2)
        if (denom <= 0.0) return 0.0
        return (sumLR / denom).coerceIn(-1.0, 1.0)
    }

    /** Side/mid energy ratio in dB. */
    fun sideMidDb(sumMid2: Double, sumSide2: Double): Double {
        if (sumMid2 <= 0.0 || sumSide2 <= 1e-20) return Double.NEGATIVE_INFINITY
        return 10.0 * log10(sumSide2 / sumMid2)
    }
}
