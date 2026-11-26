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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Metric math for the Audio Analysis engine, measured on synthetic PCM.
 *
 * Every fixture is generated in-process, so each assertion is the arithmetic
 * itself: a half-scale sine must read -6.02 dBFS peak and -9.03 dBFS RMS, a
 * 0 dBFS mono 1 kHz tone must land at the BS.1770 -3 LUFS calibration point,
 * clipping must need a run of three samples, and so on.
 */
class AudioAnalyzerTest {

    // ── Fixtures ────────────────────────────────────────────────────────

    private class FloatPcmSource(
        private val samples: FloatArray,
        override val format: PcmFormat,
        override val durationMs: Long,
    ) : PcmFrameSource {
        private var position = 0

        override fun read(buffer: FloatArray, frames: Int): Int {
            val available = samples.size / format.channels - position
            val count = min(frames, available)
            if (count <= 0) return 0
            System.arraycopy(
                samples,
                position * format.channels,
                buffer,
                0,
                count * format.channels,
            )
            position += count
            return count
        }
    }

    private fun source(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        mime: String = "audio/flac",
    ): PcmFrameSource = FloatPcmSource(
        samples = samples,
        format = PcmFormat(
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = 16,
            isFloat = false,
            encodingLabel = "PCM16",
            mimeType = mime,
            bitrateKbps = 1411,
        ),
        durationMs = samples.size / channels * 1000L / sampleRate,
    )

    private fun sine(
        sampleRate: Int,
        channels: Int,
        seconds: Double,
        hz: Double,
        amplitude: Double,
        leadingSeconds: Double = 0.0,
        trailingSeconds: Double = 0.0,
    ): FloatArray {
        val lead = (leadingSeconds * sampleRate).toInt()
        val frames = (seconds * sampleRate).toInt()
        val trail = (trailingSeconds * sampleRate).toInt()
        val out = FloatArray((lead + frames + trail) * channels)
        for (i in 0 until frames) {
            val value = (amplitude * sin(2.0 * PI * hz * i / sampleRate)).toFloat()
            for (c in 0 until channels) out[(lead + i) * channels + c] = value
        }
        return out
    }

    private val analyzer = AudioAnalyzer(
        AudioAnalyzer.Config(
            fftSize = 2048,
            fftHop = 1024,
            spectrogramColumns = 128,
            spectrogramRows = 64,
            drBlockMs = 1000L,
        ),
    )

    private fun analyze(samples: FloatArray, sampleRate: Int, channels: Int, mime: String = "audio/flac") =
        analyzer.analyze(source(samples, sampleRate, channels, mime))

    // ── Levels ──────────────────────────────────────────────────────────

    @Test
    fun halfScaleSineReportsPeakRmsAndCrest() {
        val result = analyze(sine(44_100, 1, 2.0, 1_000.0, 0.5), 44_100, 1)
        assertEquals(-6.02, result.peakDbfs, 0.1)
        assertEquals(-9.03, result.rmsDbfs, 0.1)
        assertEquals(3.01, result.perChannel.first().crestDb, 0.1)
        assertEquals(0.0, result.dcOffsetPercent, 0.05)
        // 1 kHz is near the K-weighting's unity point, so the true peak of a
        // sine equals its sample peak.
        assertEquals(-6.02, result.truePeakDbtp, 0.4)
    }

    @Test
    fun dcOffsetIsReportedAsPercentOfFullScale() {
        val dc = FloatArray(44_100) { 0.25f }
        val result = analyze(dc, 44_100, 1, mime = "audio/wav")
        assertEquals(25.0, result.dcOffsetPercent, 0.05)
        assertEquals(-12.04, result.perChannel.first().rmsDbfs, 0.05)
    }

    @Test
    fun peakPositionIsReportedInMilliseconds() {
        val samples = FloatArray(44_100)
        val frame = 22_050
        samples[frame - 1] = 0.9f
        val result = analyze(samples, 44_100, 1, mime = "audio/wav")
        // Positions are published in whole milliseconds (frame 22049 -> 499 ms).
        assertEquals((frame - 1) * 1000L / 44_100, result.peakAtMs)
    }

    // ── Clipping ────────────────────────────────────────────────────────

    @Test
    fun clippingNeedsRunsOfThreeFullScaleSamples() {
        val short = FloatArray(44_100)
        short[100] = 1f
        short[101] = 1f
        assertFalse(analyze(short, 44_100, 1, "audio/wav").clipping.any)

        val long = FloatArray(44_100)
        for (i in 0 until 5) long[100 + i] = 1f
        val result = analyze(long, 44_100, 1, "audio/wav")
        assertTrue(result.clipping.any)
        assertEquals(1, result.clipping.events)
        assertEquals(5L, result.clipping.samples)
        assertEquals(2L, result.clipping.firstAtMs)
    }

    // ── Silence ─────────────────────────────────────────────────────────

    @Test
    fun leadingTrailingAndInternalSilenceAreMeasured() {
        val withEdges = analyze(
            sine(44_100, 1, 0.5, 1_000.0, 0.5, leadingSeconds = 0.2, trailingSeconds = 0.1),
            44_100,
            1,
        )
        assertTrue(abs(withEdges.silence.leadingMs - 200L) <= 40L)
        assertTrue(abs(withEdges.silence.trailingMs - 100L) <= 40L)
        assertEquals(0, withEdges.silence.gapCount)

        // 0.5 s tone, 0.3 s gap, 0.5 s tone.
        val gap = FloatArray(44_100 * 13 / 10)
        val first = sine(44_100, 1, 0.5, 1_000.0, 0.5)
        System.arraycopy(first, 0, gap, 0, first.size)
        val second = sine(44_100, 1, 0.5, 1_000.0, 0.5)
        System.arraycopy(second, 0, gap, 44_100 * 8 / 10, second.size)
        val result = analyze(gap, 44_100, 1)
        assertEquals(1, result.silence.gapCount)
        assertTrue(abs(result.silence.longestGapMs - 300L) <= 40L)
        assertTrue(abs(result.silence.longestGapAtMs - 500L) <= 40L)
    }

    // ── Stereo field ────────────────────────────────────────────────────

    @Test
    fun identicalChannelsCorrelateAtOne() {
        val result = analyze(sine(44_100, 2, 1.0, 440.0, 0.5), 44_100, 2)
        assertEquals(1.0, result.correlation, 0.001)
        assertTrue(result.identicalChannels)
        assertFalse(result.reversedPolarity)
        assertFalse(result.sideMidDb.isFinite())
    }

    @Test
    fun oppositePolarityIsFlagged() {
        val frames = 44_100
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val value = (0.5 * sin(2.0 * PI * 440.0 * i / 44_100)).toFloat()
            samples[2 * i] = value
            samples[2 * i + 1] = -value
        }
        val result = analyze(samples, 44_100, 2)
        assertEquals(-1.0, result.correlation, 0.001)
        assertTrue(result.reversedPolarity)
        assertFalse(result.identicalChannels)
    }

    // ── Spectrum and verdicts ───────────────────────────────────────────

    @Test
    fun fftPutsAToneInTheExpectedBin() {
        val size = 1_024
        val sr = 48_000
        val re = DoubleArray(size) { sin(2.0 * PI * 1_000.0 * it / sr) }
        val im = DoubleArray(size)
        Fft.forward(re, im)
        var best = 0
        var bestPower = -1.0
        for (bin in 0..size / 2) {
            val power = re[bin] * re[bin] + im[bin] * im[bin]
            if (power > bestPower) {
                bestPower = power
                best = bin
            }
        }
        // 1000 Hz / (48000 / 1024) = 21.3
        assertEquals(21, best)
    }

    @Test
    fun broadbandNoiseReachesNyquistAndReadsLossless() {
        val samples = noise(44_100, 2, 2.0)
        val result = analyze(samples, 44_100, 2)
        assertEquals(AnalysisLabels.FULL_BAND, result.cutoffLabel)
        assertEquals(SourceVerdict.LOSSLESS, result.verdict)
        assertEquals(22_050.0, result.nyquistHz, 0.1)
    }

    @Test
    fun bandLimitedToneReadsAsLossySource() {
        val samples = sine(44_100, 2, 2.0, 10_000.0, 0.4)
        val result = analyze(samples, 44_100, 2)
        assertTrue(result.cutoffLabel.startsWith("10"))
        assertEquals(SourceVerdict.LOSSY, result.verdict)
        assertEquals(AnalysisLabels.LOSSY_SOURCE, result.verdictLabel)
    }

    @Test
    fun halfNyquistCutoffReadsAsUpsampled() {
        val sr = 96_000
        val samples = FloatArray(sr * 2)
        for (i in 0 until sr) {
            val value = (
                0.3 * sin(2.0 * PI * 1_000.0 * i / sr) +
                    0.3 * sin(2.0 * PI * 21_000.0 * i / sr)
                ).toFloat()
            samples[2 * i] = value
            samples[2 * i + 1] = value
        }
        val result = analyze(samples, sr, 2)
        assertEquals(SourceVerdict.UPSAMPLED, result.verdict)
        assertTrue(result.verdictLabel.startsWith("Likely upsampled from"))
        assertEquals(48_000.0, result.nyquistHz, 0.1)
    }

    @Test
    fun bandEnergiesPlaceTheToneInItsBand() {
        val result = analyze(sine(44_100, 2, 2.0, 100.0, 0.5), 44_100, 2)
        val bass = result.bandEnergiesDb.getValue(Band.BASS)!!
        val treble = result.bandEnergiesDb.getValue(Band.TREBLE)!!
        assertTrue("bass=$bass", bass > -3.0)
        assertTrue("treble=$treble", treble < -30.0)
    }

    // ── Loudness ────────────────────────────────────────────────────────

    @Test
    fun fullScaleMonoToneFollowsTheBs1770Calibration() {
        val result = analyze(sine(44_100, 1, 2.0, 1_000.0, 1.0), 44_100, 1)
        // 0 dBFS 1 kHz mono tone calibration: about -3 LUFS.
        assertEquals(-3.0, result.loudnessLufs, 0.7)
        assertEquals(-3.0, result.maxMomentaryLufs, 0.7)
    }

    @Test
    fun suggestedGainAimsAtTheReferenceAndKeepsHeadroomArithmetic() {
        val result = analyze(sine(44_100, 1, 2.0, 1_000.0, 0.5), 44_100, 1)
        assertEquals(-18.0 - result.loudnessLufs, result.gain.suggestedGainDb, 1e-6)
        assertEquals(result.headroomDb - result.gain.suggestedGainDb, result.gain.afterSuggestedDb, 1e-6)
        assertEquals(-result.truePeakDbtp, result.headroomDb, 1e-6)
    }

    @Test
    fun tagGainIsCarriedThroughToTheAfterGainHeadroom() {
        val tagged = analyzer.analyze(
            source(sine(44_100, 1, 2.0, 1_000.0, 0.5), 44_100, 1),
            tagGainDb = -4.0,
        )
        assertEquals(-4.0, tagged.gain.tagGainDb!!, 1e-6)
        assertEquals(tagged.headroomDb + 4.0, tagged.gain.afterTagDb!!, 1e-6)
    }

    @Test
    fun dynamicRangeGrowsWithCrestFactor() {
        val steady = analyze(sine(44_100, 1, 12.0, 1_000.0, 0.5), 44_100, 1)
        assertTrue("steady DR=${steady.dynamicRange}", steady.dynamicRange in 0..5)

        // 27 s quiet + 3 s loud: the loud blocks sit well above the average.
        val dynamic = FloatArray(44_100 * 30)
        val quietFrames = 44_100 * 27
        for (i in dynamic.indices) {
            val amplitude = if (i < quietFrames) 0.1 else 0.9
            dynamic[i] = (amplitude * sin(2.0 * PI * 1_000.0 * i / 44_100.0)).toFloat()
        }
        val result = analyze(dynamic, 44_100, 1)
        assertTrue("dynamic DR=${result.dynamicRange}", result.dynamicRange > steady.dynamicRange)
        assertTrue("dynamic DR=${result.dynamicRange}", result.dynamicRange >= 5)
    }

    // ── Spectrogram ─────────────────────────────────────────────────────

    @Test
    fun spectrogramIsBoundedAndSized() {
        val result = analyze(sine(44_100, 2, 2.0, 1_000.0, 0.5), 44_100, 2)
        val spectrogram = result.spectrogram
        assertTrue(spectrogram.columns > 0)
        assertTrue(spectrogram.rows > 0)
        assertEquals(2, spectrogram.channelSpectrograms.size)
        val all = spectrogram.channelDb(null)
        assertEquals(spectrogram.rows * spectrogram.columns, all.size)
        assertTrue(all.all { it >= Spectrogram.SPEC_DB_MIN - 0.01f && it <= 0f })
        assertTrue(spectrogram.channelDb(0).any { it > -20f })
    }

    // ── Control ─────────────────────────────────────────────────────────

    @Test
    fun cancellationStopsThePass() {
        assertThrows(AnalysisCancelledException::class.java) {
            analyzer.analyze(
                source(sine(44_100, 1, 2.0, 1_000.0, 0.5), 44_100, 1),
                isCancelled = { true },
            )
        }
    }

    @Test
    fun progressIsMonotonicAndCompletes() {
        val seen = ArrayList<Float>()
        analyzer.analyze(
            source(sine(44_100, 1, 1.0, 1_000.0, 0.5), 44_100, 1),
            progress = { seen.add(it) },
        )
        assertTrue(seen.isNotEmpty())
        assertEquals(1f, seen.last(), 0.0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun decodingReportCarriesDurationDepthAndBitrate() {
        val result = analyze(sine(44_100, 2, 2.0, 1_000.0, 0.5), 44_100, 2)
        assertTrue(abs(result.durationMs - 2_000L) <= 30L)
        assertEquals(16, result.source.bitDepth)
        assertEquals("PCM16", result.source.decodedFormat)
        assertEquals(1_411, result.source.bitrateKbps)
        assertEquals(2, result.source.channels)
        assertEquals(88_200L, result.framesAnalyzed)
    }

    @Test
    fun formatHelpersRenderReferenceUnits() {
        assertEquals("-6.02 dBFS", fmtDbfs(-6.02))
        assertEquals("0.52 dBTP", fmtDbtp(0.52))
        assertEquals("-13.10 LUFS", fmtLufs(-13.1))
        assertEquals("2.30 LU", fmtLu(2.3))
        assertEquals("DR8", fmtDr(8))
        assertEquals("21.1 kHz", fmtKhz(21_100.0))
        assertEquals("2:20.7", fmtTimecode(140_700))
        assertEquals("0.0 s", fmtSeconds(0))
    }

    private fun noise(sampleRate: Int, channels: Int, seconds: Double): FloatArray {
        val frames = (seconds * sampleRate).toInt()
        val out = FloatArray(frames * channels)
        var seed = 123456789L
        for (i in out.indices) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val value = (seed shr 33).toInt() / Int.MAX_VALUE.toFloat() * 0.25f
            out[i] = value
        }
        return out
    }
}
