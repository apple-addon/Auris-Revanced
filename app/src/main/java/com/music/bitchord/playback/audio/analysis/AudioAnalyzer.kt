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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Thrown when a caller cancels a running analysis pass. */
class AnalysisCancelledException : RuntimeException("Audio analysis cancelled")

/**
 * One background, cancellable pass over decoded PCM.
 *
 * Memory is bounded up front: one chunk buffer, one FFT carry per analysed
 * channel, one spectrogram raster per analysed channel, one 3 s DR block
 * accumulator, and two loudness ring buffers per channel. Nothing scales
 * with track length except the short list of DR blocks and momentary
 * loudness values (one per 3 s / 100 ms), and the internal-gap list is
 * capped at [Config.maxGaps].
 *
 * The engine is pure Kotlin — no Android, no ExoPlayer, no native code — so
 * every metric below is unit-testable on the JVM.
 */
class AudioAnalyzer(private val config: Config = Config()) {

    data class Config(
        val chunkFrames: Int = 8192,
        val fftSize: Int = 4096,
        val fftHop: Int = 2048,
        val spectrogramColumns: Int = 320,
        val spectrogramRows: Int = 96,
        /** -60 dBFS: below this a frame counts as silence. */
        val silenceThresholdLinear: Float = 0.001f,
        val gapMinMs: Long = 50L,
        val clipRunMin: Int = 3,
        val clipFullScale: Float = 0.99999f,
        val maxChannels: Int = 8,
        /** At most this many channels get a spectrogram (memory bound). */
        val maxSpectralChannels: Int = 4,
        val drBlockMs: Long = 3000L,
        val maxGaps: Int = 64,
        val cutoffDb: Double = 60.0,
    )

    fun analyze(
        source: PcmFrameSource,
        tagGainDb: Double? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AudioAnalysisResult {
        val format = source.format
        val sampleRate = format.sampleRateHz.coerceAtLeast(8000)
        val sourceChannels = format.channels.coerceAtLeast(1)
        val totalChannels = sourceChannels.coerceAtMost(config.maxChannels)
        val spectralChannels = min(totalChannels, config.maxSpectralChannels)
        val chunkFrames = config.chunkFrames.coerceAtLeast(256)
        val fftSize = config.fftSize
        val fftHop = config.fftHop.coerceIn(1, fftSize)
        val binCount = fftSize / 2 + 1
        val rows = config.spectrogramRows.coerceAtLeast(8)
        val columns = config.spectrogramColumns.coerceAtLeast(8)

        val chunk = FloatArray(chunkFrames * sourceChannels)
        val deinterleaved = Array(totalChannels) { FloatArray(chunkFrames) }
        val loudFrame = FloatArray(totalChannels)

        // ── Per-channel sample statistics ───────────────────────────────
        val sumSq = DoubleArray(totalChannels)
        val peak = DoubleArray(totalChannels)
        val peakFrame = LongArray(totalChannels)
        val dcSum = DoubleArray(totalChannels)

        // ── Clipping runs ───────────────────────────────────────────────
        val clipRun = IntArray(totalChannels)
        val clipEvents = IntArray(totalChannels)
        val clipSamples = LongArray(totalChannels)
        val clipFirstFrame = LongArray(totalChannels) { -1L }
        val clipLongestRun = IntArray(totalChannels)
        val clipLongestFrame = LongArray(totalChannels) { -1L }

        // ── True peak ───────────────────────────────────────────────────
        val oversamplers = Array(totalChannels) { TruePeakOversampler() }
        val oversampled = DoubleArray(TruePeakOversampler.PHASES)
        val truePeak = DoubleArray(totalChannels)
        val truePeakFrame = LongArray(totalChannels)

        // ── Stereo field ────────────────────────────────────────────────
        var sumL2 = 0.0
        var sumR2 = 0.0
        var sumLR = 0.0
        var sumMid2 = 0.0
        var sumSide2 = 0.0
        var maxChannelDiff = 0f

        // ── DR blocks (3 s) ─────────────────────────────────────────────
        val drBlockFrames = max(1L, config.drBlockMs * sampleRate / 1000L)
        val drBlocks = ArrayList<Double>()
        val drBlocksPerChannel = Array(totalChannels) { ArrayList<Double>() }
        val drSum = DoubleArray(totalChannels)
        var drFrameCount = 0L

        // ── Silence / gaps ──────────────────────────────────────────────
        var firstNonSilentFrame = -1L
        var lastNonSilentFrame = -1L
        var silentRunStart = -1L
        var gapCount = 0
        val gaps = ArrayList<Gap>()
        var longestGapMs = 0L
        var longestGapAtMs = 0L

        // ── Spectra ─────────────────────────────────────────────────────
        val window = hannWindow(fftSize)
        val windowSum = window.sum().coerceAtLeast(1e-12)
        val fftRe = DoubleArray(fftSize)
        val fftIm = DoubleArray(fftSize)
        val avgPower = Array(spectralChannels) { DoubleArray(binCount) }
        val fftFrames = LongArray(spectralChannels)
        val fftCarry = Array(spectralChannels) { DoubleArray(fftSize) }
        val fftFill = IntArray(spectralChannels)
        val specAccum = Array(spectralChannels) { FloatArray(columns * rows) }
        val specCounts = Array(spectralChannels) { IntArray(columns) }

        val nyquist = sampleRate / 2.0
        val binHz = sampleRate.toDouble() / fftSize
        val rowHiHz = if (nyquist > 40.0) min(20_000.0, nyquist) else nyquist
        val rowEdges = logBinEdges(20.0, rowHiHz, rows, sampleRate, fftSize)

        val loudness = LoudnessMeter(sampleRate, totalChannels)

        val totalFramesExpected = if (source.durationMs > 0) {
            source.durationMs * sampleRate / 1000L
        } else {
            0L
        }
        val totalHops = if (totalFramesExpected > fftSize) {
            (totalFramesExpected - fftSize) / fftHop + 1
        } else {
            0L
        }
        val fixedFramesPerColumn = max(1L, totalHops / columns)
        val fallbackFramesPerColumn = max(1L, (sampleRate * 0.5 / fftHop).toLong())
        val framesPerColumn = if (totalHops > 0) fixedFramesPerColumn else fallbackFramesPerColumn

        var frameOffset = 0L
        var lastProgress = -1f

        while (true) {
            if (isCancelled()) throw AnalysisCancelledException()
            val frames = source.read(chunk, chunkFrames)
            if (frames <= 0) break

            for (c in 0 until totalChannels) {
                val row = deinterleaved[c]
                var idx = c
                for (f in 0 until frames) {
                    row[f] = chunk[idx]
                    idx += sourceChannels
                }
            }

            // Sample statistics, clipping, silence, DR and loudness.
            for (f in 0 until frames) {
                val absoluteFrame = frameOffset + f
                var frameMax = 0f
                for (c in 0 until totalChannels) {
                    val value = deinterleaved[c][f]
                    val magnitude = kotlin.math.abs(value)
                    if (magnitude > frameMax) frameMax = magnitude

                    val v = value.toDouble()
                    sumSq[c] += v * v
                    dcSum[c] += v
                    if (magnitude > peak[c]) {
                        peak[c] = magnitude.toDouble()
                        peakFrame[c] = absoluteFrame
                    }

                    // Clipping: runs of >= clipRunMin samples at digital full scale.
                    if (magnitude >= config.clipFullScale) {
                        if (clipRun[c] == 0) {
                            clipFirstFrame[c] = if (clipFirstFrame[c] < 0) absoluteFrame else clipFirstFrame[c]
                        }
                        clipRun[c]++
                    } else {
                        flushClipRun(
                            channel = c,
                            absoluteFrame = absoluteFrame,
                            clipRun = clipRun,
                            clipEvents = clipEvents,
                            clipSamples = clipSamples,
                            clipLongestRun = clipLongestRun,
                            clipLongestFrame = clipLongestFrame,
                            clipFirstFrame = clipFirstFrame,
                        )
                    }

                    // True peak via 4x polyphase oversampling.
                    oversamplers[c].process(v, oversampled)
                    for (k in oversampled.indices) {
                        val m = kotlin.math.abs(oversampled[k])
                        if (m > truePeak[c]) {
                            truePeak[c] = m
                            truePeakFrame[c] = absoluteFrame
                        }
                    }

                    drSum[c] += v * v
                }

                if (totalChannels >= 2) {
                    val l = deinterleaved[0][f].toDouble()
                    val r = deinterleaved[1][f].toDouble()
                    val diff = kotlin.math.abs(deinterleaved[0][f] - deinterleaved[1][f])
                    if (diff > maxChannelDiff) maxChannelDiff = diff
                    sumL2 += l * l
                    sumR2 += r * r
                    sumLR += l * r
                    val mid = (l + r) * 0.5
                    val side = (l - r) * 0.5
                    sumMid2 += mid * mid
                    sumSide2 += side * side
                }

                val silent = frameMax < config.silenceThresholdLinear
                if (silent) {
                    if (silentRunStart < 0) silentRunStart = absoluteFrame
                } else {
                    if (firstNonSilentFrame < 0) {
                        firstNonSilentFrame = absoluteFrame
                        // The run before the first audio is the leading silence.
                        silentRunStart = -1
                    } else if (silentRunStart >= 0) {
                        val runStartMs = silentRunStart * 1000L / sampleRate
                        val runMs = (absoluteFrame - silentRunStart) * 1000L / sampleRate
                        if (runMs >= config.gapMinMs) {
                            gapCount++
                            if (gaps.size < config.maxGaps) {
                                gaps.add(Gap(startMs = runStartMs, durationMs = runMs))
                            }
                            if (runMs > longestGapMs) {
                                longestGapMs = runMs
                                longestGapAtMs = runStartMs
                            }
                        }
                        silentRunStart = -1
                    }
                    lastNonSilentFrame = absoluteFrame
                }

                for (c in 0 until totalChannels) loudFrame[c] = deinterleaved[c][f]
                loudness.process(loudFrame)

                drFrameCount++
                if (drFrameCount >= drBlockFrames) {
                    val allPower = drSum.sum() / (drFrameCount * totalChannels)
                    drBlocks.add(allPower)
                    for (c in 0 until totalChannels) {
                        drBlocksPerChannel[c].add(drSum[c] / drFrameCount)
                    }
                    drSum.fill(0.0)
                    drFrameCount = 0
                }
            }

            // Spectra + spectrogram: one FFT per hop per analysed channel.
            for (c in 0 until spectralChannels) {
                val data = deinterleaved[c]
                val carry = fftCarry[c]
                var fill = fftFill[c]
                for (f in 0 until frames) {
                    carry[fill++] = data[f].toDouble()
                    if (fill == fftSize) {
                        if (isCancelled()) throw AnalysisCancelledException()
                        for (i in 0 until fftSize) {
                            fftRe[i] = carry[i] * window[i]
                            fftIm[i] = 0.0
                        }
                        Fft.forward(fftRe, fftIm)
                        val column = min(columns - 1, (fftFrames[c] / framesPerColumn).toInt())
                        specCounts[c][column]++
                        val accum = specAccum[c]
                        val colBase = column * rows
                        for (bin in 0 until binCount) {
                            val power = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
                            avgPower[c][bin] += power
                        }
                        fftFrames[c]++
                        for (r in 0 until rows) {
                            val lo = rowEdges[r]
                            val hi = max(lo + 1, rowEdges[r + 1])
                            var best = Double.NEGATIVE_INFINITY
                            for (bin in lo until min(hi, binCount)) {
                                val mag = 2.0 * sqrt(fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]) / windowSum
                                val db = amplitudeDb(mag)
                                if (db > best) best = db
                            }
                            if (best.isFinite()) {
                                val clamped = best.coerceIn(Spectrogram.SPEC_DB_MIN.toDouble(), 0.0).toFloat()
                                accum[colBase + r] += clamped
                            } else {
                                accum[colBase + r] += Spectrogram.SPEC_DB_MIN
                            }
                        }
                        System.arraycopy(carry, fftHop, carry, 0, fftSize - fftHop)
                        fill = fftSize - fftHop
                    }
                }
                fftFill[c] = fill
            }

            frameOffset += frames
            val fraction = when {
                totalFramesExpected > 0 -> (frameOffset.toDouble() / totalFramesExpected).toFloat()
                else -> (frameOffset / (frameOffset + sampleRate * 60.0)).toFloat()
            }.coerceIn(0f, 1f)
            if (fraction - lastProgress >= 0.01f || fraction >= 1f) {
                lastProgress = fraction
                progress(fraction)
            }
        }

        // Close any clipping run that ends with the stream.
        for (c in 0 until totalChannels) {
            flushClipRun(
                channel = c,
                absoluteFrame = frameOffset,
                clipRun = clipRun,
                clipEvents = clipEvents,
                clipSamples = clipSamples,
                clipLongestRun = clipLongestRun,
                clipLongestFrame = clipLongestFrame,
                clipFirstFrame = clipFirstFrame,
            )
        }
        // A short tail block still counts toward DR.
        if (drFrameCount > 0) {
            val allPower = (0 until totalChannels).sumOf { drSum[it] } /
                (drFrameCount * totalChannels)
            drBlocks.add(allPower)
            for (c in 0 until totalChannels) {
                drBlocksPerChannel[c].add(drSum[c] / drFrameCount)
            }
        }

        if (frameOffset == 0L) {
            throw PcmSourceException(AnalysisLabels.ERR_NO_AUDIO)
        }

        val sampleCount = frameOffset
        val durationMs = when {
            source.durationMs > 0 -> source.durationMs
            else -> sampleCount * 1000L / sampleRate
        }

        // ── Spectral summary ────────────────────────────────────────────
        val combinedAvg = DoubleArray(binCount)
        for (c in 0 until spectralChannels) {
            val n = max(1L, fftFrames[c])
            for (bin in 0 until binCount) combinedAvg[bin] += avgPower[c][bin] / n
        }
        val spectralScale = 1.0 / max(1, spectralChannels)
        for (bin in 0 until binCount) combinedAvg[bin] *= spectralScale

        val refLo = (1000.0 / binHz).toInt().coerceIn(0, binCount - 1)
        val refHi = (5000.0 / binHz).toInt().coerceIn(refLo, binCount - 1)
        var refPower = 0.0
        for (bin in refLo..refHi) refPower += combinedAvg[bin]
        refPower /= (refHi - refLo + 1).coerceAtLeast(1)
        // The 1-5 kHz level is the reference only when that band actually
        // carries content. A pure HF tone leaves nothing there but numerical
        // leakage, and measuring 60 dB below leakage would call every such
        // file "Full band"; fall back to the loudest bin instead.
        val peakBinPower = combinedAvg.maxOrNull() ?: 0.0
        val referencePower = if (refPower >= peakBinPower * 1e-7) refPower else peakBinPower
        val cutoffThreshold = referencePower * 10.0.pow(-config.cutoffDb / 10.0)
        var cutoffBin = -1
        for (bin in binCount - 1 downTo 0) {
            if (combinedAvg[bin] >= cutoffThreshold) {
                cutoffBin = bin
                break
            }
        }
        val cutoffHz = when {
            cutoffBin < 0 || referencePower <= 0.0 -> 0.0
            cutoffBin >= binCount - 1 -> nyquist
            else -> cutoffBin * binHz
        }

        fun bandPower(loHz: Double, hiHz: Double): Double {
            val lo = (loHz / binHz).toInt().coerceIn(0, binCount - 1)
            val hi = (hiHz / binHz).toInt().coerceIn(lo, binCount - 1)
            var p = 0.0
            for (bin in lo..hi) p += combinedAvg[bin]
            return p
        }

        val totalBandPower = bandPower(20.0, min(20_000.0, nyquist))
        fun bandDb(loHz: Double, hiHz: Double): Double {
            if (totalBandPower <= 0.0) return Double.NEGATIVE_INFINITY
            return powerDb(bandPower(loHz, hiHz) / totalBandPower)
        }
        val bandEnergies = mapOf(
            Band.SUBSONIC to bandDb(0.0, 20.0),
            Band.BASS to bandDb(20.0, 250.0),
            Band.MID to bandDb(250.0, 4000.0),
            Band.TREBLE to bandDb(4000.0, 20_000.0),
            Band.ULTRASONIC to bandDb(20_000.0, nyquist),
        )

        // ── Per-channel and overall figures ─────────────────────────────
        fun channelStats(c: Int): ChannelStats {
            val peakDbfs = amplitudeDb(peak[c])
            val rms = sqrt(sumSq[c] / sampleCount)
            val rmsDbfs = amplitudeDb(rms)
            val dr = DynamicRange.dr(drBlocksPerChannel[c], peak[c] * peak[c])
            return ChannelStats(
                peakDbfs = peakDbfs,
                rmsDbfs = rmsDbfs,
                crestDb = peakDbfs - rmsDbfs,
                dr = dr,
                dcOffsetPercent = (dcSum[c] / sampleCount) * 100.0,
            )
        }
        val perChannel = (0 until totalChannels).map { channelStats(it) }

        val overallPeak = peak.maxOrNull() ?: 0.0
        val overallPeakAt = peak.indices.maxByOrNull { peak[it] }?.let { peakFrame[it] } ?: 0L
        val overallRms = sqrt(sumSq.sum() / (sampleCount * totalChannels))
        val overallTruePeak = truePeak.maxOrNull() ?: 0.0
        val overallTruePeakAt = truePeak.indices.maxByOrNull { truePeak[it] }?.let { truePeakFrame[it] } ?: 0L
        val dr = DynamicRange.dr(drBlocks, overallPeak * overallPeak)
        val clipEventsTotal = clipEvents.sum()
        val clipSamplesTotal = clipSamples.sum()
        val firstClipAt = clipFirstFrame.filter { it >= 0 }.minOrNull()?.let { it * 1000L / sampleRate }
        val longestClipSamples = clipLongestRun.maxOrNull() ?: 0
        val longestClipAt = clipLongestRun.indices.maxByOrNull { clipLongestRun[it] }?.let {
            if (clipLongestRun[it] > 0) clipLongestFrame[it] * 1000L / sampleRate else null
        }

        val trailingSilenceMs = when {
            firstNonSilentFrame < 0 -> 0L
            silentRunStart >= 0 -> (sampleCount - silentRunStart) * 1000L / sampleRate
            else -> (sampleCount - 1 - lastNonSilentFrame).coerceAtLeast(0L) * 1000L / sampleRate
        }
        val leadingSilenceMs = when {
            firstNonSilentFrame < 0 -> durationMs
            else -> firstNonSilentFrame * 1000L / sampleRate
        }

        val loudnessResult = loudness.result()
        val correlation = if (totalChannels >= 2) {
            DynamicRange.correlation(sumL2, sumR2, sumLR)
        } else 0.0
        val sideMid = if (totalChannels >= 2) DynamicRange.sideMidDb(sumMid2, sumSide2) else Double.NEGATIVE_INFINITY
        val identical = totalChannels >= 2 && maxChannelDiff <= 1e-6f
        val reversedPolarity = totalChannels >= 2 && correlation <= -0.9

        val truePeakDbtp = amplitudeDb(overallTruePeak)
        val plr = if (loudnessResult.integratedLufs.isFinite() && truePeakDbtp.isFinite()) {
            truePeakDbtp - loudnessResult.integratedLufs
        } else {
            Double.NaN
        }
        val headroom = if (truePeakDbtp.isFinite()) -truePeakDbtp else Double.NaN

        val suggestedGain = if (loudnessResult.integratedLufs.isFinite()) {
            (LoudnessMeter.SUGGESTED_TARGET_LUFS - loudnessResult.integratedLufs).coerceIn(-60.0, 60.0)
        } else 0.0
        val afterTag = tagGainDb?.takeIf { truePeakDbtp.isFinite() }?.let { headroom - it }
        val afterSuggested = if (headroom.isFinite()) headroom - suggestedGain else Double.NaN
        val gain = GainReport(
            tagGainDb = tagGainDb,
            suggestedGainDb = suggestedGain,
            headroomDb = headroom,
            afterTagDb = afterTag,
            afterSuggestedDb = afterSuggested,
        )

        val fullBand = cutoffHz >= nyquist * 0.98
        val verdict = when {
            fullBand -> if (format.isLosslessMime) SourceVerdict.LOSSLESS else SourceVerdict.LOSSY
            cutoffHz > 0.0 && cutoffHz < 16_000.0 && sampleRate >= 44_100 -> SourceVerdict.LOSSY
            cutoffHz > 0.0 && cutoffHz < nyquist * 0.9 -> SourceVerdict.UPSAMPLED
            format.isLosslessMime -> SourceVerdict.LOSSLESS
            else -> SourceVerdict.LOSSY
        }
        val cutoffLabel = if (fullBand) AnalysisLabels.FULL_BAND else fmtKhz(cutoffHz)
        val verdictLabel = when (verdict) {
            SourceVerdict.LOSSLESS -> AnalysisLabels.LOSSLESS
            SourceVerdict.LOSSY -> AnalysisLabels.LOSSY_SOURCE
            SourceVerdict.UPSAMPLED -> AnalysisLabels.UPSAMPLED_FROM.format(
                java.util.Locale.ROOT,
                fmtKhz(cutoffHz * 2.0),
            )
            SourceVerdict.FULL_BAND -> AnalysisLabels.FULL_BAND
        }

        // ── Spectrogram raster (used columns only) ──────────────────────
        val usedColumns = (0 until spectralChannels)
            .maxOf { c -> specCounts[c].indexOfLast { it > 0 } + 1 }
            .coerceAtLeast(1)
        val channelRasters = (0 until spectralChannels).map { c ->
            val out = FloatArray(usedColumns * rows) { Spectrogram.SPEC_DB_MIN }
            val accum = specAccum[c]
            for (col in 0 until usedColumns) {
                val count = specCounts[c][col]
                if (count <= 0) continue
                for (r in 0 until rows) {
                    val value = accum[col * rows + r] / count
                    out[col * rows + r] = value.coerceIn(Spectrogram.SPEC_DB_MIN, 0f)
                }
            }
            out
        }
        val spectrogram = Spectrogram(
            rows = rows,
            columns = usedColumns,
            minHz = 20.0,
            maxHz = min(20_000.0, nyquist),
            channelSpectrograms = channelRasters,
        )

        val sourceInfo = SourceInfo(
            durationMs = durationMs,
            sampleRateHz = sampleRate,
            channels = format.channels,
            bitDepth = format.bitsPerSample,
            isFloat = format.isFloat,
            decodedFormat = format.encodingLabel,
            bitrateKbps = format.bitrateKbps,
            mimeType = format.mimeType,
            declaredLossless = format.isLosslessMime,
        )

        val result = AudioAnalysisResult(
            source = sourceInfo,
            spectrogram = spectrogram,
            perChannel = perChannel,
            peakDbfs = amplitudeDb(overallPeak),
            peakAtMs = overallPeakAt * 1000L / sampleRate,
            rmsDbfs = amplitudeDb(overallRms),
            truePeakDbtp = truePeakDbtp,
            truePeakAtMs = overallTruePeakAt * 1000L / sampleRate,
            dynamicRange = dr,
            dcOffsetPercent = perChannel.maxByOrNull { kotlin.math.abs(it.dcOffsetPercent) }
                ?.dcOffsetPercent ?: 0.0,
            clipping = ClippingReport(
                events = clipEventsTotal,
                samples = clipSamplesTotal,
                firstAtMs = firstClipAt,
                longestSamples = longestClipSamples.toLong(),
                longestAtMs = longestClipAt,
            ),
            silence = SilenceReport(
                leadingMs = leadingSilenceMs,
                trailingMs = trailingSilenceMs,
                gapCount = gapCount,
                gaps = gaps,
                longestGapMs = longestGapMs,
                longestGapAtMs = longestGapAtMs,
            ),
            spectralCutoffHz = cutoffHz,
            nyquistHz = nyquist,
            bandEnergiesDb = bandEnergies,
            correlation = correlation,
            sideMidDb = sideMid,
            identicalChannels = identical,
            reversedPolarity = reversedPolarity,
            loudnessLufs = loudnessResult.integratedLufs,
            lraLu = loudnessResult.lraLu,
            maxMomentaryLufs = loudnessResult.maxMomentaryLufs,
            maxMomentaryAtMs = loudnessResult.maxMomentaryAtMs,
            maxShortTermLufs = loudnessResult.maxShortTermLufs,
            maxShortTermAtMs = loudnessResult.maxShortTermAtMs,
            plrDb = plr,
            headroomDb = headroom,
            verdict = verdict,
            cutoffLabel = cutoffLabel,
            verdictLabel = verdictLabel,
            gain = gain,
            framesAnalyzed = sampleCount,
        )
        progress(1f)
        return result
    }

    private fun flushClipRun(
        channel: Int,
        absoluteFrame: Long,
        clipRun: IntArray,
        clipEvents: IntArray,
        clipSamples: LongArray,
        clipLongestRun: IntArray,
        clipLongestFrame: LongArray,
        clipFirstFrame: LongArray,
    ) {
        val run = clipRun[channel]
        if (run >= config.clipRunMin) {
            clipEvents[channel]++
            clipSamples[channel] += run.toLong()
            if (run > clipLongestRun[channel]) {
                clipLongestRun[channel] = run
                clipLongestFrame[channel] = absoluteFrame - run
            }
            if (clipFirstFrame[channel] < 0) {
                clipFirstFrame[channel] = absoluteFrame - run
            }
        } else if (run > 0 && clipFirstFrame[channel] == absoluteFrame - run) {
            // A run shorter than the threshold never was an event.
            clipFirstFrame[channel] = -1L
        }
        clipRun[channel] = 0
    }
}
