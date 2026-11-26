package com.music.bitchord.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ cookbook sections supported by the clean-room equalisers. */
enum class BiquadKind {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF,
}

/** One immutable filter target. Values are sanitized again when published. */
data class BiquadSpec(
    val frequencyHz: Float,
    val q: Float,
    val gainDb: Float,
    val kind: BiquadKind = BiquadKind.PEAKING,
)

/**
 * Fixed-length, double-precision biquad cascade with zipper-free target gliding.
 *
 * Settings are published as one immutable [Target] reference. The audio thread
 * never observes a partly updated band list, enable flag, or preamp. Processing
 * allocates nothing, takes no locks, and keeps every coefficient, delay line,
 * and arithmetic operation in `Double`; only the input/output [AudioBlock] is
 * Float32.
 *
 * A disabled bank glides to neutral instead of cutting a live curve out. Once
 * neutral and settled, [isEnabled] is false and [process] returns the original
 * samples byte-for-byte. [flush] clears delay lines on a discontinuity;
 * [reset] releases them.
 */
class BiquadBank(private val sections: Int) {

    init {
        require(sections in 1..MAX_SECTIONS) { "unsupported section count: $sections" }
    }

    private data class Target(
        val enabled: Boolean,
        val specs: List<BiquadSpec>,
        val preampDb: Double,
    ) {
        val neutral: Boolean = !enabled ||
            (preampDb == 0.0 && specs.all { it.gainDb == 0f })

        companion object {
            val OFF = Target(enabled = false, specs = emptyList(), preampDb = 0.0)
        }
    }

    @Volatile
    private var target: Target = Target.OFF

    /** Also covers the settling interval immediately after a settings write. */
    @Volatile
    private var settled = true

    private var sampleRate = 0
    private var channelCount = 0

    private val gainDb = DoubleArray(sections)
    private val q = DoubleArray(sections) { DEFAULT_Q }
    private val frequencyHz = DoubleArray(sections) { DEFAULT_FREQUENCY }
    private val kind = Array(sections) { BiquadKind.PEAKING }

    private val b0 = DoubleArray(sections)
    private val b1 = DoubleArray(sections)
    private val b2 = DoubleArray(sections)
    private val a1 = DoubleArray(sections)
    private val a2 = DoubleArray(sections)

    /** Two transposed-direct-form-II delay elements per section and channel. */
    private var state = DoubleArray(0)
    private var currentPreampDb = 0.0

    /** True while audible processing or a neutral-glide remains. */
    val isEnabled: Boolean
        get() = !target.neutral || !settled

    /** Publishes one complete snapshot. Called off the audio thread. */
    fun setTarget(enabled: Boolean, specs: List<BiquadSpec>, preampDb: Float = 0f) {
        val safeSpecs = if (enabled) {
            specs.take(sections).map { spec ->
                BiquadSpec(
                    frequencyHz = spec.frequencyHz.finiteOr(DEFAULT_FREQUENCY.toFloat()),
                    q = spec.q.finiteOr(DEFAULT_Q.toFloat()).coerceIn(MIN_Q, MAX_Q),
                    gainDb = spec.gainDb.finiteOr(0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
                    kind = spec.kind,
                )
            }
        } else {
            emptyList()
        }
        val safePreamp = if (enabled) {
            preampDb.finiteOr(0f).coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB).toDouble()
        } else {
            0.0
        }
        target = Target(enabled, safeSpecs, safePreamp)
        settled = false
    }

    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        val required = channelCount * sections * 2
        if (state.size != required) state = DoubleArray(required) else state.fill(0.0)
        snapToTarget()
    }

    override fun toString(): String = "BiquadBank(sections=$sections, sampleRate=$sampleRate)"

    fun process(block: AudioBlock) {
        if (block.frameCount == 0 || sampleRate <= 0 || channelCount != block.channelCount) return

        val requested = target
        if (requested.neutral && settled) return

        var remaining = block.frameCount
        var frameOffset = 0
        while (remaining > 0) {
            val frames = minOf(remaining, GLIDE_FRAMES)
            glideTowards(requested)
            updateCoefficients()
            val gain = 10.0.pow(currentPreampDb / 20.0)
            val runFilters = requested.specs.isNotEmpty() || !settled
            val samples = block.samples

            for (frame in 0 until frames) {
                val base = (frameOffset + frame) * channelCount
                for (channel in 0 until channelCount) {
                    val input = samples[base + channel].toDouble()
                    var sample = if (input.isFinite()) input else 0.0
                    if (runFilters) {
                        for (section in 0 until sections) {
                            sample = filter(section, channel, sample)
                        }
                    }
                    val output = sample * gain
                    samples[base + channel] = if (
                        output.isFinite() && abs(output) <= Float.MAX_VALUE.toDouble()
                    ) {
                        output.toFloat()
                    } else {
                        0f
                    }
                }
            }
            frameOffset += frames
            remaining -= frames
        }
        flushDenormals()
        if (requested.neutral && settled) state.fill(0.0)
    }

    /** Clears delay lines on a seek/stream discontinuity and snaps to the current target. */
    fun flush() {
        state.fill(0.0)
        snapToTarget()
    }

    /** Returns to neutral and releases stream-owned state. */
    fun reset() {
        state = DoubleArray(0)
        target = Target.OFF
        gainDb.fill(0.0)
        q.fill(DEFAULT_Q)
        frequencyHz.fill(DEFAULT_FREQUENCY)
        kind.fill(BiquadKind.PEAKING)
        b0.fill(0.0)
        b1.fill(0.0)
        b2.fill(0.0)
        a1.fill(0.0)
        a2.fill(0.0)
        currentPreampDb = 0.0
        sampleRate = 0
        channelCount = 0
        settled = true
    }

    private fun snapToTarget() {
        val requested = target
        val specs = if (requested.enabled) requested.specs else emptyList()
        for (index in 0 until sections) {
            val spec = specs.getOrNull(index) ?: FLAT_SPEC
            frequencyHz[index] = safeFrequency(spec.frequencyHz)
            q[index] = safeQ(spec.q)
            gainDb[index] = safeGain(spec.gainDb)
            kind[index] = spec.kind
        }
        currentPreampDb = if (requested.enabled) requested.preampDb else 0.0
        updateCoefficients()
        settled = true
    }

    private fun glideTowards(requested: Target) {
        var moving = false
        val specs = if (requested.enabled) requested.specs else emptyList()
        for (index in 0 until sections) {
            val spec = specs.getOrNull(index) ?: FLAT_SPEC
            val nextGain = safeGain(spec.gainDb)
            val nextQ = safeQ(spec.q)
            val nextFrequency = safeFrequency(spec.frequencyHz)
            val nextKind = spec.kind

            if (kind[index] != nextKind) {
                kind[index] = nextKind
                moving = true
            }

            val gainStep = nextGain - gainDb[index]
            if (abs(gainStep) > SETTLED_DB) {
                gainDb[index] += gainStep * GLIDE_RATE
                moving = true
            } else {
                gainDb[index] = nextGain
            }

            val qStep = nextQ - q[index]
            if (abs(qStep) > SETTLED_Q) {
                q[index] += qStep * GLIDE_RATE
                moving = true
            } else {
                q[index] = nextQ
            }

            val frequencyStep = nextFrequency - frequencyHz[index]
            if (abs(frequencyStep) > SETTLED_HZ) {
                frequencyHz[index] += frequencyStep * GLIDE_RATE
                moving = true
            } else {
                frequencyHz[index] = nextFrequency
            }
        }

        val wantedPreamp = if (requested.enabled) requested.preampDb else 0.0
        val preampStep = wantedPreamp - currentPreampDb
        if (abs(preampStep) > SETTLED_DB) {
            currentPreampDb += preampStep * GLIDE_RATE
            moving = true
        } else {
            currentPreampDb = wantedPreamp
        }
        settled = !moving
    }

    private fun updateCoefficients() {
        if (sampleRate <= 0) return
        for (index in 0 until sections) {
            design(kind[index], frequencyHz[index], q[index], gainDb[index], index)
        }
    }

    private fun design(kind: BiquadKind, frequencyHz: Double, q: Double, gainDb: Double, index: Int) {
        val amplitude = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequencyHz / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2.0 * q)
        val sqrtA = sqrt(amplitude)

        val rawB0: Double
        val rawB1: Double
        val rawB2: Double
        val rawA0: Double
        val rawA1: Double
        val rawA2: Double

        when (kind) {
            BiquadKind.PEAKING -> {
                rawB0 = 1.0 + alpha * amplitude
                rawB1 = -2.0 * cosine
                rawB2 = 1.0 - alpha * amplitude
                rawA0 = 1.0 + alpha / amplitude
                rawA1 = -2.0 * cosine
                rawA2 = 1.0 - alpha / amplitude
            }
            BiquadKind.LOW_SHELF -> {
                rawB0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha)
                rawB1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
                rawB2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha)
                rawA0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha
                rawA1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
                rawA2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha
            }
            BiquadKind.HIGH_SHELF -> {
                rawB0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha)
                rawB1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
                rawB2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha)
                rawA0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha
                rawA1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
                rawA2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha
            }
        }

        val inverseA0 = 1.0 / rawA0
        b0[index] = rawB0 * inverseA0
        b1[index] = rawB1 * inverseA0
        b2[index] = rawB2 * inverseA0
        a1[index] = rawA1 * inverseA0
        a2[index] = rawA2 * inverseA0
    }

    private fun filter(index: Int, channel: Int, input: Double): Double {
        val stateIndex = (channel * sections + index) * 2
        val z1 = state[stateIndex]
        val z2 = state[stateIndex + 1]
        val output = b0[index] * input + z1
        if (!output.isFinite()) {
            state[stateIndex] = 0.0
            state[stateIndex + 1] = 0.0
            return 0.0
        }
        val nextZ1 = b1[index] * input - a1[index] * output + z2
        val nextZ2 = b2[index] * input - a2[index] * output
        state[stateIndex] = if (nextZ1.isFinite()) nextZ1 else 0.0
        state[stateIndex + 1] = if (nextZ2.isFinite()) nextZ2 else 0.0
        return output
    }

    private fun flushDenormals() {
        for (index in state.indices) {
            if (abs(state[index]) < DENORMAL_FLOOR) state[index] = 0.0
        }
    }

    private fun safeFrequency(hz: Float): Double {
        val upper = if (sampleRate > 0) sampleRate * MAX_FREQUENCY_FRACTION else DEFAULT_FREQUENCY
        return hz.finiteOr(DEFAULT_FREQUENCY.toFloat())
            .coerceIn(MIN_HZ, upper.toFloat())
            .toDouble()
    }

    private fun safeQ(value: Float): Double =
        value.finiteOr(DEFAULT_Q.toFloat()).coerceIn(MIN_Q, MAX_Q).toDouble()

    private fun safeGain(value: Float): Double =
        value.finiteOr(0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB).toDouble()

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    companion object {
        private const val MAX_SECTIONS = 12
        private const val GLIDE_FRAMES = 64
        private const val GLIDE_RATE = 0.08
        private const val SETTLED_DB = 0.001
        private const val SETTLED_Q = 0.00005
        private const val SETTLED_HZ = 0.005
        private const val MIN_HZ = 10f
        private const val MAX_FREQUENCY_FRACTION = 0.45
        private const val MIN_Q = 0.05f
        private const val MAX_Q = 30f
        private const val MAX_GAIN_DB = 24f
        private const val MIN_PREAMP_DB = -45f
        private const val MAX_PREAMP_DB = 45f
        private const val DENORMAL_FLOOR = 1e-18
        private const val DEFAULT_Q = 0.707
        private const val DEFAULT_FREQUENCY = 1000.0

        private val FLAT_SPEC = BiquadSpec(DEFAULT_FREQUENCY.toFloat(), DEFAULT_Q.toFloat(), 0f)
    }
}
