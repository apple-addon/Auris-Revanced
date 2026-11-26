package com.music.bitchord.playback.audio.analysis

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley–Tukey FFT.
 *
 * This is the app's own decoder-path DSP (no PAWS/HiBy native engine): every
 * spectral number the analysis dialog shows — spectrogram columns, band
 * energies, spectral cutoff — comes out of this transform applied to decoded
 * PCM. [size] must be a power of two; [re] and [im] are modified in place.
 */
internal object Fft {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    fun nextPowerOfTwo(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    /**
     * Forward transform (sign convention e^{-2πi kn/N}). After the call,
     * [re]/[im] hold the complex spectrum X[k].
     */
    fun forward(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && isPowerOfTwo(n)) { "FFT size must be a power of two" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until half) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half] * wr - im[i + k + half] * wi
                    val bIm = re[i + k + half] * wi + im[i + k + half] * wr
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + half] = aRe - bRe
                    im[i + k + half] = aIm - bIm
                    val nwr = wr * wRe - wi * wIm
                    wi = wr * wIm + wi * wRe
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitude (linear) spectrum of a real block of [size] samples, first size/2 bins. */
    fun magnitudes(block: DoubleArray, size: Int = block.size): DoubleArray {
        require(isPowerOfTwo(size) && size <= block.size)
        val re = block.copyOf(size)
        val im = DoubleArray(size)
        // Hann window suppresses leakage between log-spaced spectrogram bins.
        for (i in 0 until size) {
            re[i] *= 0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))
        }
        forward(re, im)
        val half = size / 2
        val mag = DoubleArray(half)
        for (k in 0 until half) {
            mag[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) / (size / 2.0)
        }
        return mag
    }

    /** Power spectrum (magnitude squared), first size/2 bins. */
    fun powerSpectrum(block: DoubleArray, size: Int = block.size): DoubleArray {
        val mag = magnitudes(block, size)
        for (k in mag.indices) mag[k] = mag[k] * mag[k]
        return mag
    }
}
