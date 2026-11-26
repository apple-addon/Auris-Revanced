package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.ResamplerQuality
import com.music.bitchord.ui.screens.audiophile.ResamplerSettings
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Streaming, interleaved-Float32 sample-rate converter with 64-bit coefficients and accumulation.
 *
 * [configure] allocates and is a control/stream-boundary operation. [process] and [drain] allocate
 * nothing, retain filter history across arbitrary chunks, and can safely use the same array for
 * input and output. OFF is an exact bypass: no dither, quantization, filter, or sample arithmetic is
 * performed; a copy is made only when the input and output regions differ.
 *
 * Dither is opt-in quantization for an integer destination. Supply [outputBitDepth] only at the
 * final integer PCM boundary; leave it null for Float32 DSP blocks. Dither NONE leaves Float32
 * samples untouched for a same-rate configuration. Perfect BP and Float32 policy normally force
 * dither off before this processor is configured.
 *
 * The requested output rate is solely a software conversion ratio. This class does not negotiate,
 * claim, or report a hardware output sample-rate change. The service must mount it upstream of an
 * output that is actually configured for the corresponding PCM rate.
 */
class AudiophileResampler {
    var inputSampleRateHz: Int = 0
        private set
    var outputSampleRateHz: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var settings: ResamplerSettings = ResamplerSettings.DEFAULTS
        private set
    var outputBitDepth: Int? = null
        private set

    val isConfigured: Boolean get() = inputSampleRateHz > 0
    val isBypassed: Boolean get() = isConfigured && settings.quality == ResamplerQuality.OFF

    /** Always false: this software processor never negotiates or claims a hardware rate change. */
    val changesHardwareOutputSampleRate: Boolean get() = false
    val isRateConversionActive: Boolean
        get() = isConfigured && settings.quality != ResamplerQuality.OFF &&
            inputSampleRateHz != outputSampleRateHz
    val isDitherActive: Boolean
        get() = isConfigured && settings.quality != ResamplerQuality.OFF &&
            settings.dither != DitherMode.NONE && outputBitDepth != null

    /** Filter complexity selected by quality; zero before configuration. */
    val filterTapCount: Int get() = taps
    val filterPhaseCount: Int get() = phases

    private var maxInputFrames: Int = 0
    private var taps: Int = 0
    private var halfTaps: Int = 0
    private var phases: Int = 0
    private var coefficients = DoubleArray(0)
    private var history = FloatArray(0)
    private var previousTpdf = DoubleArray(0)

    /**
     * Error-feedback state for the five PAWS noise-shaping dither modes: the
     * selected FIR shaping filter (SoX/SSRC coefficient tables, matched to the
     * output rate within 5% exactly like libswresample), its clip-avoidance
     * gain in centibels, and a 2×taps ring buffer of past quantization errors
     * per channel. Empty when the active mode is not a shaping mode.
     */
    private var noiseShapeCoeffs = DoubleArray(0)
    private var noiseShapeGainCB = 0.0
    private var errorBuffers = Array(0) { DoubleArray(0) }
    private var errorPos = IntArray(0)

    private var historyStartFrame = 0L
    private var historyFrames = 0
    private var totalInputFrames = 0L
    private var emittedOutputFrames = 0L
    private var nextInputPosition = 0.0
    private var inputFramesPerOutputFrame = 1.0
    private var drained = false
    private var randomState = RANDOM_SEED

    fun configure(
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        channelCount: Int,
        settings: ResamplerSettings,
        outputBitDepth: Int? = null,
        maxInputFrames: Int = 4096,
    ) {
        require(inputSampleRateHz > 0) { "inputSampleRateHz must be positive" }
        require(outputSampleRateHz > 0) { "outputSampleRateHz must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
        require(maxInputFrames > 0) { "maxInputFrames must be positive" }
        require(outputBitDepth == null || outputBitDepth in 8..32) {
            "outputBitDepth must be null or 8..32"
        }

        this.inputSampleRateHz = inputSampleRateHz
        this.outputSampleRateHz = outputSampleRateHz
        this.channelCount = channelCount
        this.settings = settings.normalized()
        this.outputBitDepth = outputBitDepth
        this.maxInputFrames = maxInputFrames
        this.inputFramesPerOutputFrame = inputSampleRateHz.toDouble() / outputSampleRateHz.toDouble()

        halfTaps = when (settings.quality) {
            ResamplerQuality.OFF -> 0
            ResamplerQuality.LOW -> 8
            ResamplerQuality.MEDIUM -> 16
            ResamplerQuality.HIGH -> 32
            ResamplerQuality.VERY_HIGH -> 64
        }
        taps = halfTaps * 2
        phases = when (settings.quality) {
            ResamplerQuality.OFF -> 0
            ResamplerQuality.LOW -> 64
            ResamplerQuality.MEDIUM -> 128
            ResamplerQuality.HIGH -> 256
            ResamplerQuality.VERY_HIGH -> 512
        }
        previousTpdf = DoubleArray(channelCount)
        if (settings.dither.isNoiseShaping) {
            val table = shapeTableFor(settings.dither, outputSampleRateHz)
            noiseShapeCoeffs = table?.coeffs ?: DoubleArray(0)
            noiseShapeGainCB = table?.gainCB ?: 0.0
            errorBuffers = Array(channelCount) { DoubleArray(MAX_SHAPE_TAPS * 2) }
            errorPos = IntArray(channelCount)
        } else {
            noiseShapeCoeffs = DoubleArray(0)
            noiseShapeGainCB = 0.0
            errorBuffers = Array(0) { DoubleArray(0) }
            errorPos = IntArray(0)
        }
        if (isRateConversionActive) {
            coefficients = buildCoefficients()
            history = FloatArray((maxInputFrames + taps + 4) * channelCount)
        } else {
            coefficients = DoubleArray(0)
            history = FloatArray(0)
        }
        reset()
    }

    /** Conservative output capacity accepted by [process], including retained filter lookahead. */
    fun maxOutputFrames(inputFrameCount: Int): Int {
        require(isConfigured)
        require(inputFrameCount >= 0)
        if (!isRateConversionActive) return inputFrameCount
        val frames = ceil(
            (inputFrameCount + taps + 2) * outputSampleRateHz.toDouble() / inputSampleRateHz,
        ).toLong() + 2L
        require(frames <= Int.MAX_VALUE)
        return frames.toInt()
    }

    /** Exact stream length after [drain], once all input has been supplied. */
    fun expectedFinalOutputFrameCount(): Long {
        if (!isRateConversionActive) return totalInputFrames
        return (totalInputFrames * outputSampleRateHz + inputSampleRateHz - 1L) / inputSampleRateHz
    }

    /**
     * Consumes one complete interleaved chunk and returns the number of output frames written.
     * Use [maxOutputFrames] for [outputCapacityFrames]. Filter tail frames are emitted by [drain].
     */
    fun process(
        input: FloatArray,
        inputOffsetFrames: Int,
        inputFrameCount: Int,
        output: FloatArray,
        outputOffsetFrames: Int = 0,
        outputCapacityFrames: Int,
    ): Int {
        require(isConfigured)
        require(inputOffsetFrames >= 0 && inputFrameCount >= 0)
        require(outputOffsetFrames >= 0 && outputCapacityFrames >= 0)
        require(inputFrameCount <= maxInputFrames)
        require(inputOffsetFrames.toLong() + inputFrameCount.toLong() <= input.size / channelCount)
        require(outputOffsetFrames.toLong() + outputCapacityFrames.toLong() <= output.size / channelCount)

        if (settings.quality == ResamplerQuality.OFF) {
            require(outputCapacityFrames >= inputFrameCount)
            copyPassthrough(input, inputOffsetFrames, inputFrameCount, output, outputOffsetFrames)
            return inputFrameCount
        }

        if (!isRateConversionActive) {
            require(outputCapacityFrames >= inputFrameCount)
            copyPassthrough(input, inputOffsetFrames, inputFrameCount, output, outputOffsetFrames)
            if (isDitherActive) {
                ditherRegion(output, outputOffsetFrames, inputFrameCount)
            }
            return inputFrameCount
        }

        require(!drained || inputFrameCount == 0)
        if (drained) return 0
        require(outputCapacityFrames >= maxOutputFrames(inputFrameCount))

        val inputSampleOffset = inputOffsetFrames * channelCount
        val historySampleOffset = historyFrames * channelCount
        if (inputFrameCount > 0) {
            System.arraycopy(
                input,
                inputSampleOffset,
                history,
                historySampleOffset,
                inputFrameCount * channelCount,
            )
            historyFrames += inputFrameCount
            totalInputFrames += inputFrameCount
        }

        var outputFrames = 0
        while (
            nextInputPosition < totalInputFrames.toDouble() &&
            floor(nextInputPosition).toLong() + halfTaps < totalInputFrames
        ) {
            renderFrame(output, outputOffsetFrames + outputFrames)
            outputFrames++
            emittedOutputFrames++
            nextInputPosition += inputFramesPerOutputFrame
        }
        compactHistory()
        return outputFrames
    }

    /**
     * Emits the zero-padded filter tail. Call once after the final input chunk. The required
     * capacity is at most [maxOutputFrames] with an input count of zero. A new stream needs [reset].
     */
    fun drain(
        output: FloatArray,
        outputOffsetFrames: Int = 0,
        outputCapacityFrames: Int,
    ): Int {
        require(isConfigured)
        require(outputOffsetFrames >= 0 && outputCapacityFrames >= 0)
        require(outputOffsetFrames.toLong() + outputCapacityFrames.toLong() <= output.size / channelCount)
        if (!isRateConversionActive || drained) return 0
        val remaining = expectedFinalOutputFrameCount() - emittedOutputFrames
        require(remaining >= 0L && remaining <= Int.MAX_VALUE)
        require(outputCapacityFrames >= remaining.toInt())

        var outputFrames = 0
        while (emittedOutputFrames < expectedFinalOutputFrameCount()) {
            renderFrame(output, outputOffsetFrames + outputFrames)
            outputFrames++
            emittedOutputFrames++
            nextInputPosition += inputFramesPerOutputFrame
        }
        drained = true
        historyFrames = 0
        historyStartFrame = totalInputFrames
        return outputFrames
    }

    /** Drops stream history but retains all configured buffers for allocation-free reuse. */
    fun reset() {
        historyStartFrame = 0L
        historyFrames = 0
        totalInputFrames = 0L
        emittedOutputFrames = 0L
        nextInputPosition = 0.0
        drained = false
        randomState = RANDOM_SEED
        previousTpdf.fill(0.0)
        errorBuffers.forEach { it.fill(0.0) }
        errorPos.fill(0)
    }

    /** Flush is a discontinuity, not an end-of-stream drain. */
    fun flush() = reset()

    private fun copyPassthrough(
        input: FloatArray,
        inputOffsetFrames: Int,
        inputFrameCount: Int,
        output: FloatArray,
        outputOffsetFrames: Int,
    ) {
        if (input === output && inputOffsetFrames == outputOffsetFrames) return
        System.arraycopy(
            input,
            inputOffsetFrames * channelCount,
            output,
            outputOffsetFrames * channelCount,
            inputFrameCount * channelCount,
        )
    }

    private fun renderFrame(output: FloatArray, outputFrame: Int) {
        var base = floor(nextInputPosition).toLong()
        val fraction = nextInputPosition - base
        var phase = (fraction * phases + 0.5).toInt()
        if (phase == phases) {
            base++
            phase = 0
        }
        val coefficientOffset = phase * taps
        val firstInputFrame = base - halfTaps + 1L
        val outputOffset = outputFrame * channelCount
        for (channel in 0 until channelCount) {
            var sum = 0.0
            for (tap in 0 until taps) {
                val sample = historySample(firstInputFrame + tap, channel)
                sum += sample * coefficients[coefficientOffset + tap]
            }
            val value = if (isDitherActive) {
                quantizeWithDither(sum, channel)
            } else {
                sum
            }
            output[outputOffset + channel] = value.toFloat()
        }
    }

    private fun historySample(frame: Long, channel: Int): Double {
        val relative = frame - historyStartFrame
        if (relative < 0L || relative >= historyFrames) return 0.0
        return history[(relative.toInt() * channelCount) + channel].toDouble()
    }

    private fun compactHistory() {
        val nextBase = floor(nextInputPosition).toLong()
        val neededStart = (nextBase - halfTaps + 1L).coerceIn(0L, totalInputFrames)
        val drop = (neededStart - historyStartFrame).toInt().coerceIn(0, historyFrames)
        if (drop <= 0) return
        val remaining = historyFrames - drop
        if (remaining > 0) {
            System.arraycopy(
                history,
                drop * channelCount,
                history,
                0,
                remaining * channelCount,
            )
        }
        historyStartFrame += drop
        historyFrames = remaining
    }

    private fun ditherRegion(output: FloatArray, offsetFrames: Int, frameCount: Int) {
        val scale = integerScale(outputBitDepth ?: return)
        var sampleOffset = offsetFrames * channelCount
        val end = sampleOffset + frameCount * channelCount
        while (sampleOffset < end) {
            val channel = (sampleOffset - offsetFrames * channelCount) % channelCount
            val sample = output[sampleOffset].toDouble()
            output[sampleOffset] = quantizeWithDither(sample, channel, scale).toFloat()
            sampleOffset++
        }
    }

    private fun quantizeWithDither(
        sample: Double,
        channel: Int,
        knownScale: Double? = null,
    ): Double {
        val scale = knownScale ?: integerScale(outputBitDepth ?: return sample)
        return when (settings.dither) {
            DitherMode.NONE -> quantize(sample, scale, 0.0)
            DitherMode.RECTANGULAR -> quantize(sample, scale, nextUniform())
            DitherMode.TRIANGULAR -> quantize(sample, scale, nextUniform() + nextUniform())
            DitherMode.HIGH_PASS -> {
                val current = nextUniform() + nextUniform()
                val highPassed = (current - previousTpdf[channel]) * 0.5
                previousTpdf[channel] = current
                quantize(sample, scale, highPassed)
            }
            // The five PAWS noise-shaping modes: TPDF dither plus a real
            // error-feedback FIR shaping filter (SoX/SSRC tables).
            DitherMode.F_WEIGHTED,
            DitherMode.MODIFIED_E_WEIGHTED,
            DitherMode.IMPROVED_E_WEIGHTED,
            DitherMode.SHIBATA,
            DitherMode.LOW_SHIBATA,
            -> quantizeWithNoiseShaping(sample, channel, scale)
        }
    }

    private fun quantize(sample: Double, scale: Double, noiseLsb: Double): Double {
        val quantized = floor(sample * scale + noiseLsb + 0.5)
            .coerceIn(-scale, scale - 1.0)
        return quantized / scale
    }

    /**
     * Error-feedback noise shaping, following the libswresample/SoX scheme:
     * `d = x·scale·g − Σ coefs[j]·errors[pos+j]`, `q = rint(d + tpdf)`,
     * `error = q − d` pushed into the ring. [noiseShapeGainCB] is the table's
     * clip-avoidance gain in centibels; the input is scaled by
     * `1 − 10^(gain/200)/scale` so shaped peaks stay inside full scale. With no
     * table matched to the output rate the mode degrades to triangular
     * high-pass shaping rather than to an undithered quantize.
     */
    private fun quantizeWithNoiseShaping(sample: Double, channel: Int, scale: Double): Double {
        val coeffs = noiseShapeCoeffs
        if (coeffs.isEmpty()) {
            val current = nextUniform() + nextUniform()
            val highPassed = (current - previousTpdf[channel]) * 0.5
            previousTpdf[channel] = current
            return quantize(sample, scale, highPassed)
        }
        val taps = coeffs.size
        val buffer = errorBuffers[channel]
        var pos = errorPos[channel]
        val inputCompensation = 1.0 - (10.0).pow(noiseShapeGainCB / 200.0) / scale
        var d = sample * scale * inputCompensation
        for (tap in 0 until taps) {
            d -= coeffs[tap] * buffer[pos + tap]
        }
        val noise = nextUniform() + nextUniform()
        val quantized = floor(d + noise + 0.5).coerceIn(-scale, scale - 1.0)
        pos = if (pos == 0) taps - 1 else pos - 1
        buffer[pos + taps] = quantized - d
        buffer[pos] = quantized - d
        errorPos[channel] = pos
        return quantized / scale
    }

    private fun integerScale(bits: Int): Double = (1L shl (bits - 1)).toDouble()

    /** One SoX/SSRC noise-shaping FIR table: the rate it was designed for, its clip gain, its coefficients. */
    private data class ShapeTable(val rateHz: Int, val gainCB: Double, val coeffs: DoubleArray)

    /**
     * Picks the shaping table for [mode] at [rateHz] with the libswresample
     * tolerance (|rate − table|·20 ≤ table, first match wins). Null when no
     * table covers the rate, which makes the caller fall back to triangular
     * high-pass shaping.
     */
    private fun shapeTableFor(mode: DitherMode, rateHz: Int): ShapeTable? {
        val tables = when (mode) {
            DitherMode.F_WEIGHTED -> listOf(ShapeTable(46_000, 276.0, FWE44))
            DitherMode.MODIFIED_E_WEIGHTED -> listOf(ShapeTable(46_000, 160.0, MEW44))
            DitherMode.IMPROVED_E_WEIGHTED -> listOf(ShapeTable(46_000, 321.0, IEW44))
            DitherMode.SHIBATA -> listOf(
                ShapeTable(48_000, 301.0, SHI48),
                ShapeTable(44_100, 333.0, SHI44),
                ShapeTable(37_800, 240.0, SHI38),
                ShapeTable(32_000, 240.0, SHI32),
                ShapeTable(22_050, 240.0, SHI22),
                ShapeTable(16_000, 240.0, SHI16),
                ShapeTable(11_025, 240.0, SHI11),
                ShapeTable(8_000, 240.0, SHI08),
            )
            DitherMode.LOW_SHIBATA -> listOf(
                ShapeTable(48_000, 250.0, SHL48),
                ShapeTable(44_100, 250.0, SHL44),
            )
            else -> emptyList()
        }
        return tables.firstOrNull { kotlin.math.abs(rateHz - it.rateHz) * 20 <= it.rateHz }
    }

    /** Xorshift64*: deterministic, cheap, and no object allocation on the audio thread. */
    private fun nextUniform(): Double {
        var value = randomState
        value = value xor (value ushr 12)
        value = value xor (value shl 25)
        value = value xor (value ushr 27)
        randomState = value
        val random = value * -7046029254386353131L
        return (random ushr 11).toDouble() * (1.0 / 9007199254740992.0) - 0.5
    }

    private fun buildCoefficients(): DoubleArray {
        val result = DoubleArray(phases * taps)
        val ratio = outputSampleRateHz.toDouble() / inputSampleRateHz.toDouble()
        val cutoff = 0.5 * (settings.cutoffPercent / 100.0) * min(1.0, ratio)
        for (phase in 0 until phases) {
            val fraction = phase.toDouble() / phases
            val offset = phase * taps
            var sum = 0.0
            for (tap in 0 until taps) {
                val distance = fraction + halfTaps - 1 - tap
                val coefficient = if (distance == 0.0) {
                    2.0 * cutoff
                } else {
                    val sincArgument = 2.0 * cutoff * distance
                    val sinc = sin(PI * sincArgument) / (PI * sincArgument)
                    val window = 0.42 +
                        0.5 * cos(PI * distance / halfTaps) +
                        0.08 * cos(2.0 * PI * distance / halfTaps)
                    2.0 * cutoff * sinc * window
                }
                result[offset + tap] = coefficient
                sum += coefficient
            }
            if (sum != 0.0) {
                for (tap in 0 until taps) result[offset + tap] /= sum
            }
        }
        return result
    }

    private companion object {
        const val RANDOM_SEED = 0x4D595DF4D0F33173L

        /** The longest shaping table in the set is 20 taps, so 2×20 per channel covers every mode. */
        const val MAX_SHAPE_TAPS = 20

        // SoX/SSRC noise-shaping coefficient tables (as carried by libswresample's
        // noise_shaping_data.c). Applied to the quantization error, LSB units.
        private val FWE44 = doubleArrayOf(
            2.412, -3.370, 3.937, -4.174, 3.353, -2.205, 1.281, -.569, .0847,
        )
        private val MEW44 = doubleArrayOf(
            1.662, -1.263, .4827, -.2913, .1268, -.1124, .03252, -.01265, -.03524,
        )
        private val IEW44 = doubleArrayOf(
            2.847, -4.685, 6.214, -7.184, 6.639, -5.032, 3.263, -1.632, .4191,
        )
        private val SHI48 = doubleArrayOf(
            2.8720729351043701172, -5.0413231849670410156, 6.2442994117736816406,
            -5.8483986854553222656, 3.7067542076110839844, -1.0495119094848632812,
            -1.1830236911773681641, 2.1126792430877685547, -1.9094531536102294922,
            0.99913084506988525391, -0.17090806365013122559, -0.32615602016448974609,
            0.39127644896507263184, -0.26876461505889892578, 0.097676105797290802002,
            -0.023749845794796943665,
        )
        private val SHI44 = doubleArrayOf(
            2.6773197650909423828, -4.8308925628662109375, 6.570110321044921875,
            -7.4572014808654785156, 6.7263274192810058594, -4.8481650352478027344,
            2.0412089824676513672, 0.7006359100341796875, -2.9537565708160400391,
            4.0800385475158691406, -4.1845216751098632812, 3.3311812877655029297,
            -2.1179926395416259766, 0.879302978515625, -0.031759146600961685181,
            -0.42382788658142089844, 0.47882103919982910156, -0.35490813851356506348,
            0.17496839165687561035, -0.060908168554306030273,
        )
        private val SHI38 = doubleArrayOf(
            1.6335992813110351562, -2.2615493343902587891, 2.4077029228210449219,
            -2.6341717243194580078, 2.1440362930297851562, -1.8153258685102012695,
            1.0816224841461303711, -0.70302653312683105469, 0.15991993248462677002,
            0.041549518704414367676, -0.29416578027870178223, 0.2518316805362701416,
            -0.27764678061676025391, 0.15785403549671173096, -0.10165894031524658203,
            0.016833892092108726501,
        )
        private val SHI32 = doubleArrayOf(
            0.82118552923202515, -1.0063692331314087, 0.62341964244842529,
            -1.0447187423706055, 0.64532512426376343, -0.87615132331848145,
            0.52219754457473755, -0.67433269230370117, 0.44954317808151245,
            -0.52557498216629028, 0.34569299485206604, -0.39618203043937683,
            0.26791760325811824, -0.28936097025871277, 0.1883765310049057,
            -0.19090308814525604, 0.10431359708309174, -0.10644808545882228,
            0.046832218766212463, -0.039653312414884567,
        )
        private val SHI22 = doubleArrayOf(
            0.056581053882837296, -0.56956905126571655, -0.40727514765734863,
            -0.33869107789167664, -0.29810589913813898, -0.19039161503314972,
            -0.16070162743653955, -0.13487268943202789, -0.096737769571781158,
            -0.080959128937694695, -0.064985058958053589, -0.054295850970530405,
            -0.043378707021474838, -0.03660014271736145, -0.026256965973294228,
            -0.018786206841468811, -0.013387725688517094, -0.0090983230620622635,
            -0.0026585909072309732, -0.00042083300650119781,
        )
        private val SHI16 = doubleArrayOf(
            -0.37251132726669312, -0.81423574686050415, -0.55010956525802612,
            -0.47405768440795898, -0.32624638525939804, -0.3161267529083252,
            -0.2286367267370224, -0.22916565754778862, -0.19556616548061171,
            -0.18160104751586914, -0.15408853150700383, -0.14104511841430427,
            -0.11844276638836898, -0.097583092749118805, -0.076493598520755768,
            -0.068110969531053833, -0.041815654411554337, -0.036922455031621987,
            -0.019345045969115234, -0.014994377957340309,
        )
        private val SHI11 = doubleArrayOf(
            -0.9264228343963623, -0.98695987462997437, -0.631156325340271,
            -0.51969935396194458, -0.39738747645287766, -0.35688905860921997,
            -0.29720788606999207, -0.26347869554689407, -0.21719555151214142,
            -0.18162521791191147, -0.15000095837051392, -0.12160794835968206,
            -0.10339745879173279, -0.083688631653785706, -0.05875682448279449,
            -0.046893671154975891, -0.027950936928391457, -0.020710445969349209,
            -0.012760160682608437, -0.0060260165826146603,
        )
        private val SHI08 = doubleArrayOf(
            -1.202865335609436, -0.94299587915649414, -0.67878556251525879,
            -0.57701335551525879, -0.50004476308822632, -0.44349345564842224,
            -0.37833768129348755, -0.34028746939545129, -0.29413089156150928,
            -0.24994957447052002, -0.21572704352447510, -0.18792112171649933,
            -0.15268312594618988, -0.12135542929172516, -0.099603092749118805,
            -0.076536598520755768, -0.068110969531053833, -0.048815654411554337,
            -0.036922455031621987, -0.028993291358861458, -0.011869125068187114,
        )
        private val SHL48 = doubleArrayOf(
            2.3925774097442626953, -3.4350297451019287109, 3.1853709220886230469,
            -1.8117271166758422852, -0.20124770700931549072, 1.4759907722473144531,
            -1.7210904359817504883, 0.97746700048446655273, -0.13790138065147971924,
            -0.38185903429985046387, 0.27421241998672485352, 0.066584214568138122559,
            -0.35223302245140075684, 0.37672343850135803223, -0.23964276909828186035,
            0.068674825131893157959,
        )
        private val SHL44 = doubleArrayOf(
            2.0833916664123535156, -3.0418450832366943359, 3.2047898769378662109,
            -2.7571926116943359375, 1.4978630542755126953, -0.3427594602108001709,
            -0.71733748912811279297, 1.0737057924270629883, -1.0225815703010253906,
            0.56649994850158691406, -0.20968692004680633545, -0.065378531813621520996,
            0.10322438180446624756, -0.067442022264003753662, -0.00495197344571352005,
            0.0,
        )
    }
}
