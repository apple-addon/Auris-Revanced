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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** dB for a linear amplitude, guarding zero and negatives. */
fun amplitudeDb(linear: Double): Double =
    if (linear <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(linear)

/** dB for a linear power quantity, guarding zero. */
fun powerDb(power: Double): Double =
    if (power <= 0.0) Double.NEGATIVE_INFINITY else 10.0 * log10(power)

/** Hann window of length [n]. */
fun hannWindow(n: Int): DoubleArray {
    val w = DoubleArray(n)
    for (i in 0 until n) w[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
    return w
}

/** One double-precision biquad section (RBJ cookbook, normalised by a0). */
class Biquad(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
    private val b0 = b0
    private val b1 = b1
    private val b2 = b2
    private val a1 = a1
    private val a2 = a2
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}

/** RBJ-cookbook biquad designs, rate-independent via the bilinear transform. */
object BiquadDesign {
    /** RBJ high-shelf: the ITU-R BS.1770 K-weighting pre-filter shape. */
    fun highShelf(fs: Double, f0: Double, gainDb: Double, q: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val sqrtA = sqrt(a)
        val b0 = a * ((a + 1) + (a - 1) * cosW + 2.0 * sqrtA * alpha)
        val b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW)
        val b2 = a * ((a + 1) + (a - 1) * cosW - 2.0 * sqrtA * alpha)
        val a0 = (a + 1) - (a - 1) * cosW + 2.0 * sqrtA * alpha
        val a1 = 2.0 * ((a - 1) - (a + 1) * cosW)
        val a2 = (a + 1) - (a - 1) * cosW - 2.0 * sqrtA * alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** RBJ second-order high-pass: the BS.1770 RLB weighting. */
    fun highPass(fs: Double, f0: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * f0 / fs
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val b0 = (1.0 + cosW) / 2.0
        val b1 = -(1.0 + cosW)
        val b2 = (1.0 + cosW) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * ITU-R BS.1770 K-weighting: a high shelf at ~1.68 kHz (+4 dB) followed by
 * a high-pass at ~38 Hz, designed at whatever rate the stream runs at.
 */
class KWeighting(sampleRateHz: Int) {
    private val shelf = BiquadDesign.highShelf(
        fs = sampleRateHz.toDouble(),
        f0 = 1681.974450955533,
        gainDb = 3.99984385397,
        q = 0.7071752369554196,
    )
    private val rlb = BiquadDesign.highPass(
        fs = sampleRateHz.toDouble(),
        f0 = 38.13547087602444,
        q = 0.5003270373238773,
    )

    fun process(x: Double): Double = rlb.process(shelf.process(x))

    fun reset() {
        shelf.reset()
        rlb.reset()
    }
}

/**
 * 4x oversampling true-peak estimator: a windowed-sinc low-pass (cutoff at
 * 0.45 of the original Nyquist, so the passband survives) evaluated as a
 * polyphase filter, 8 taps per phase.
 *
 * Memory is O(taps); [process] returns the four interpolated values for one
 * input sample. The caller tracks the maximum.
 */
class TruePeakOversampler {
    private val phases: Array<DoubleArray>
    private val history = DoubleArray(TAPS)
    private var historyPos = 0

    init {
        // Windowed sinc at the 4x rate, cutoff 0.45 * fs (normalised 0.1125 of the 4x rate).
        val cutoff = 0.1125 // of the 4x sample rate
        val h = DoubleArray(TAPS)
        val m = (TAPS - 1) / 2.0
        for (i in 0 until TAPS) {
            val x = i - m
            val sinc = if (x == 0.0) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
            val win = 0.5 * (1.0 + cos(PI * x / m)) // Hann
            h[i] = sinc * win
        }
        // Each polyphase branch must have unity DC gain: the full filter's
        // gain is split across the four phases, so normalising only the whole
        // kernel would attenuate every interpolated sample by 1/PHASES.
        phases = Array(PHASES) { p ->
            val taps = DoubleArray(TAPS / PHASES) { k -> h[p + k * PHASES] }
            val branchSum = taps.sum().coerceAtLeast(1e-12)
            for (i in taps.indices) taps[i] /= branchSum
            taps
        }
    }

    /** Feeds one input sample, writing the four 4x-rate outputs into [out]. */
    fun process(x: Double, out: DoubleArray) {
        history[historyPos] = x
        for (p in 0 until PHASES) {
            val taps = phases[p]
            var acc = 0.0
            var idx = historyPos
            for (k in taps.indices) {
                acc += taps[k] * history[idx]
                idx -= PHASES
                if (idx < 0) idx += TAPS
            }
            out[p] = acc
        }
        historyPos++
        if (historyPos == TAPS) historyPos = 0
    }

    fun reset() {
        history.fill(0.0)
        historyPos = 0
    }

    companion object {
        const val PHASES = 4
        const val TAPS = 32
    }
}

/** Convenience used by tests and by the analyzer for log-spaced binning. */
fun logBinEdges(minHz: Double, maxHz: Double, rows: Int, sampleRateHz: Int, fftSize: Int): IntArray {
    val nyquist = sampleRateHz / 2.0
    val hi = minOf(maxHz, nyquist)
    val edges = IntArray(rows + 1)
    val ratio = hi / minHz
    for (r in 0..rows) {
        val f = minHz * ratio.pow(r.toDouble() / rows)
        edges[r] = (f * fftSize / sampleRateHz).toInt().coerceIn(0, fftSize / 2)
    }
    return edges
}
