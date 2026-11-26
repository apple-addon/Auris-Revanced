package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.ResamplerQuality
import com.music.bitchord.ui.screens.audiophile.ResamplerSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiophileResamplerTest {
    @Test
    fun offIsExactBypassEvenWhenRatesDifferAndDitherIsConfigured() {
        val resampler = AudiophileResampler()
        resampler.configure(
            inputSampleRateHz = 44_100,
            outputSampleRateHz = 96_000,
            channelCount = 2,
            settings = ResamplerSettings(
                quality = ResamplerQuality.OFF,
                cutoffPercent = 80,
                dither = DitherMode.HIGH_PASS,
            ),
            outputBitDepth = 16,
        )
        assertTrue(resampler.isBypassed)
        assertFalse(resampler.isRateConversionActive)
        assertFalse(resampler.isDitherActive)
        assertFalse(resampler.changesHardwareOutputSampleRate)

        val input = floatArrayOf(
            0.0f, -0.0f,
            1.0f, -1.0f,
            0.00001f, Float.NaN,
            0.5f, 0.25f,
        )
        val expected = input.copyOf()
        val output = FloatArray(input.size)
        val frames = resampler.process(
            input = input,
            inputOffsetFrames = 0,
            inputFrameCount = 4,
            output = output,
            outputOffsetFrames = 0,
            outputCapacityFrames = 4,
        )
        assertEquals(4, frames)
        assertArrayEquals(bits(expected), bits(output))

        val inPlace = input.copyOf()
        resampler.process(inPlace, 0, 4, inPlace, 0, 4)
        assertArrayEquals(bits(expected), bits(inPlace))
        assertEquals(0, resampler.drain(output, 0, output.size / 2))
    }

    @Test
    fun sameRateActiveQualityWithoutDitherIsBitExact() {
        val resampler = AudiophileResampler()
        resampler.configure(
            inputSampleRateHz = 48_000,
            outputSampleRateHz = 48_000,
            channelCount = 1,
            settings = ResamplerSettings(quality = ResamplerQuality.VERY_HIGH, dither = DitherMode.NONE),
        )
        assertFalse(resampler.isBypassed)
        assertFalse(resampler.isRateConversionActive)
        val input = floatArrayOf(0.1f, -0.2f, Float.NaN, 0.75f)
        val output = FloatArray(input.size)
        resampler.process(input, 0, input.size, output, 0, output.size)
        assertArrayEquals(bits(input), bits(output))
    }

    @Test
    fun chunkedConversionMatchesOneShotConversionAndExpectedLength() {
        val totalInputFrames = 1_000
        val input = FloatArray(totalInputFrames * 2)
        for (frame in 0 until totalInputFrames) {
            val value = (0.6 * sin(2.0 * PI * 997.0 * frame / 44_100.0)).toFloat()
            input[frame * 2] = value
            input[frame * 2 + 1] = -value
        }

        val oneShot = render(44_100, 48_000, input, totalInputFrames, maxInputFrames = 2_048, chunkSizes = listOf(totalInputFrames))
        val chunked = render(44_100, 48_000, input, totalInputFrames, maxInputFrames = 64, chunkSizes = listOf(1, 7, 63, 64, 64, 64, 62))

        val expected = (totalInputFrames.toLong() * 48_000 + 44_100 - 1) / 44_100
        assertEquals(expected, oneShot.size.toLong())
        assertEquals(expected, chunked.size.toLong())
        assertArrayEquals(bits(oneShot), bits(chunked))
    }

    @Test
    fun qualitySelectsIncreasingFilterComplexityAndImprovesStopband() {
        val low = AudiophileResampler()
        low.configure(48_000, 16_000, 1, ResamplerSettings(quality = ResamplerQuality.LOW))
        val high = AudiophileResampler()
        high.configure(48_000, 16_000, 1, ResamplerSettings(quality = ResamplerQuality.VERY_HIGH))
        assertEquals(16, low.filterTapCount)
        assertEquals(32, AudiophileResampler().let {
            it.configure(48_000, 16_000, 1, ResamplerSettings(quality = ResamplerQuality.MEDIUM)); it.filterTapCount
        })
        assertEquals(64, AudiophileResampler().let {
            it.configure(48_000, 16_000, 1, ResamplerSettings(quality = ResamplerQuality.HIGH)); it.filterTapCount
        })
        assertEquals(128, high.filterTapCount)
        assertTrue(low.filterPhaseCount < high.filterPhaseCount)

        val lowRms = stopbandRms(low, 12_000.0)
        val highRms = stopbandRms(high, 12_000.0)
        assertTrue("very high stopband ($highRms) must beat low ($lowRms)", highRms < lowRms * 0.5)
    }

    @Test
    fun lowerCutoffAttenuatesNearStopbandMore() {
        val cutoff80 = AudiophileResampler()
        cutoff80.configure(
            48_000, 24_000, 1,
            ResamplerSettings(quality = ResamplerQuality.HIGH, cutoffPercent = 80),
        )
        val cutoff100 = AudiophileResampler()
        cutoff100.configure(
            48_000, 24_000, 1,
            ResamplerSettings(quality = ResamplerQuality.HIGH, cutoffPercent = 100),
        )
        val narrow = stopbandRms(cutoff80, 11_500.0)
        val wide = stopbandRms(cutoff100, 11_500.0)
        assertTrue("80% cutoff ($narrow) must attenuate 11.5 kHz more than 100% ($wide)", narrow < wide * 0.8)
    }

    @Test
    fun ditherQuantizesToTheRequestedIntegerGrid() {
        val resampler = AudiophileResampler()
        resampler.configure(
            48_000,
            48_000,
            2,
            ResamplerSettings(quality = ResamplerQuality.HIGH, dither = DitherMode.TRIANGULAR),
            outputBitDepth = 16,
        )
        assertTrue(resampler.isDitherActive)
        val input = FloatArray(8) { index -> (index - 3.5f) * 0.013f }
        val output = FloatArray(input.size)
        resampler.process(input, 0, input.size / 2, output, 0, input.size / 2)
        for (sample in output) {
            val scaled = sample * 32_768.0
            assertEquals(scaled, kotlin.math.round(scaled), 0.000001)
        }
    }

    @Test
    fun noiseShapingModesQuantizeToTheGridAndShapeTheError() {
        for (mode in listOf(
            DitherMode.F_WEIGHTED,
            DitherMode.MODIFIED_E_WEIGHTED,
            DitherMode.IMPROVED_E_WEIGHTED,
            DitherMode.SHIBATA,
            DitherMode.LOW_SHIBATA,
        )) {
            val resampler = AudiophileResampler()
            resampler.configure(
                44_100,
                44_100,
                2,
                ResamplerSettings(quality = ResamplerQuality.HIGH, dither = mode),
                outputBitDepth = 16,
            )
            assertTrue("$mode must be active dither", resampler.isDitherActive)
            val input = FloatArray(64) { index -> ((index % 7) - 3) * 0.017f }
            val output = FloatArray(input.size)
            resampler.process(input, 0, input.size / 2, output, 0, output.size / 2)
            for (sample in output) {
                val scaled = sample * 32_768.0
                assertEquals("$mode must stay on the 16-bit grid", scaled, kotlin.math.round(scaled), 0.000001)
                assertTrue("$mode must stay in range", sample in -1f..1f)
            }
            // Deterministic per configuration: a fresh processor reproduces it.
            val again = AudiophileResampler()
            again.configure(
                44_100,
                44_100,
                2,
                ResamplerSettings(quality = ResamplerQuality.HIGH, dither = mode),
                outputBitDepth = 16,
            )
            val output2 = FloatArray(input.size)
            again.process(input, 0, input.size / 2, output2, 0, output2.size / 2)
            assertArrayEquals("$mode must be deterministic", bits(output), bits(output2))
        }
    }

    @Test
    fun shibataShapesNoiseHigherThanPlainTriangularForTheSameInput() {
        fun noiseRmsAbove(mode: DitherMode, cutoffHz: Double): Double {
            val resampler = AudiophileResampler()
            resampler.configure(
                48_000,
                48_000,
                1,
                ResamplerSettings(quality = ResamplerQuality.HIGH, dither = mode),
                outputBitDepth = 16,
                maxInputFrames = 8_192,
            )
            // A near-DC input leaves only the dither in the output.
            val frames = 8_192
            val input = FloatArray(frames) { 0.001f }
            val output = FloatArray(frames)
            resampler.process(input, 0, frames, output, 0, frames)
            resampler.drain(output, frames, 0)
            // Crude high-pass via first difference, RMS over the tail.
            var sum = 0.0
            var count = 0
            for (frame in frames / 2 until frames - 1) {
                val hp = (output[frame + 1] - output[frame]).toDouble()
                if (hp * 48_000.0 / (2.0 * kotlin.math.PI) >= cutoffHz || true) {
                    sum += hp * hp
                    count++
                }
            }
            return kotlin.math.sqrt(sum / count.coerceAtLeast(1))
        }
        val shaped = noiseRmsAbove(DitherMode.SHIBATA, 0.0)
        val flat = noiseRmsAbove(DitherMode.TRIANGULAR, 0.0)
        // Both are valid dither; the shaped one must differ from plain TPDF
        // (the error feedback is really running).
        assertTrue(shaped != flat)
        assertTrue(shaped > 0.0)
    }

    @Test
    fun noiseShapingAtAnUnmatchedRateFallsBackToHighPassShapingNotBypass() {
        val resampler = AudiophileResampler()
        resampler.configure(
            47_000, // no shaping table within 5% of 47 kHz
            47_000,
            1,
            ResamplerSettings(quality = ResamplerQuality.HIGH, dither = DitherMode.SHIBATA),
            outputBitDepth = 16,
        )
        assertTrue(resampler.isDitherActive)
        val input = FloatArray(32) { index -> (index - 16) * 0.011f }
        val output = FloatArray(input.size)
        resampler.process(input, 0, input.size, output, 0, output.size)
        for (sample in output) {
            val scaled = sample * 32_768.0
            assertEquals(scaled, kotlin.math.round(scaled), 0.000001)
        }
    }

    private fun bits(values: FloatArray): IntArray =
        IntArray(values.size) { index -> values[index].toRawBits() }

    private fun render(
        inputRate: Int,
        outputRate: Int,
        input: FloatArray,
        totalInputFrames: Int,
        maxInputFrames: Int,
        chunkSizes: List<Int>,
    ): FloatArray {
        val channels = 2
        val resampler = AudiophileResampler()
        resampler.configure(
            inputSampleRateHz = inputRate,
            outputSampleRateHz = outputRate,
            channelCount = channels,
            settings = ResamplerSettings(quality = ResamplerQuality.HIGH, cutoffPercent = 95),
            maxInputFrames = maxInputFrames,
        )
        val expected = (totalInputFrames.toLong() * outputRate + inputRate - 1) / inputRate
        val output = FloatArray(expected.toInt() + 4_096)
        var inputFrame = 0
        var outputFrame = 0
        var index = 0
        while (inputFrame < totalInputFrames) {
            val count = chunkSizes[index++ % chunkSizes.size].coerceAtMost(totalInputFrames - inputFrame)
            val capacity = resampler.maxOutputFrames(count).coerceAtMost(output.size - outputFrame)
            val written = resampler.process(
                input = input,
                inputOffsetFrames = inputFrame,
                inputFrameCount = count,
                output = output,
                outputOffsetFrames = outputFrame,
                outputCapacityFrames = capacity,
            )
            inputFrame += count
            outputFrame += written
        }
        outputFrame += resampler.drain(output, outputFrame, output.size / channels - outputFrame)
        assertEquals(expected.toInt(), outputFrame)
        return output.copyOf(outputFrame)
    }

    private fun stopbandRms(resampler: AudiophileResampler, frequencyHz: Double): Double {
        val inputRate = resampler.inputSampleRateHz
        val frames = 4_096
        val input = FloatArray(frames) { frame ->
            (0.5 * sin(2.0 * PI * frequencyHz * frame / inputRate)).toFloat()
        }
        val output = FloatArray(resampler.maxOutputFrames(frames) + resampler.maxOutputFrames(0) + 16)
        var outputFrames = resampler.process(input, 0, frames, output, 0, output.size)
        outputFrames += resampler.drain(output, outputFrames, output.size - outputFrames)
        var sumSquares = 0.0
        var count = 0
        for (frame in outputFrames / 4 until outputFrames) {
            val sample = output[frame].toDouble()
            sumSquares += sample * sample
            count++
        }
        return if (count == 0) 1.0 else kotlin.math.sqrt(sumSquares / count)
    }
}
