package com.music.bitchord.playback.fades

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Outgoing/incoming gains at one point in an overlap. */
data class CrossfadeGains(
    val outgoing: Float,
    val incoming: Float,
)

/** Gain curves independent of players, clocks, and audio APIs. */
object FadeGain {
    /** Linear single-stream transport ramp. */
    fun at(progress: Float, from: Float, to: Float): Float {
        val fraction = progress.coerceIn(0f, 1f)
        return from + (to - from) * fraction
    }

    /** sin/cos pair: outgoing² + incoming² stays at one throughout the blend. */
    fun equalPower(progress: Float): CrossfadeGains {
        val fraction = progress.coerceIn(0f, 1f)
        if (fraction == 0f) return CrossfadeGains(outgoing = 1f, incoming = 0f)
        if (fraction == 1f) return CrossfadeGains(outgoing = 0f, incoming = 1f)
        val angle = fraction.toDouble() * PI / 2.0
        return CrossfadeGains(
            outgoing = cos(angle).toFloat(),
            incoming = sin(angle).toFloat(),
        )
    }
}

/**
 * Sample-interleaved gain with a short linear ramp whenever its target changes.
 *
 * A service may update [setTarget] at its control cadence and call [process] for each audio block
 * without ever stepping straight from one gain to another. Re-targeting starts at the last rendered
 * gain, including part-way through an earlier ramp. Confine both calls to one thread (or provide
 * external happens-before handoff): deliberate lack of locks keeps sample processing real-time safe.
 */
class ClickFreeGain(
    initialGain: Float = 1f,
    private val defaultRampFrames: Int = DEFAULT_RAMP_FRAMES,
) {
    var currentGain: Float = sanitize(initialGain)
        private set

    var targetGain: Float = currentGain
        private set

    private var rampFrom = currentGain
    private var rampLength = 0
    private var rampPosition = 0

    /** Aims at [target], using [rampFrames] frames, or [defaultRampFrames] when omitted. */
    fun setTarget(target: Float, rampFrames: Int = defaultRampFrames) {
        val bounded = sanitize(target)
        if (bounded == targetGain) return
        targetGain = bounded
        rampFrom = currentGain
        rampPosition = 0
        rampLength = rampFrames.coerceAtLeast(0)
        if (rampLength == 0 || rampFrom == targetGain) {
            currentGain = targetGain
            rampLength = 0
        }
    }

    /** Immediate reset for a flushed/reconfigured stream; never needed for ordinary gain changes. */
    fun reset(gain: Float = 1f) {
        currentGain = sanitize(gain)
        targetGain = currentGain
        rampFrom = currentGain
        rampLength = 0
        rampPosition = 0
    }

    fun process(samples: FloatArray, channelCount: Int) {
        require(channelCount > 0) { "channelCount must be positive" }
        require(samples.size % channelCount == 0) { "samples must contain complete frames" }
        process(samples, samples.size / channelCount, channelCount)
    }

    /** Applies gain in place to [frameCount] complete interleaved frames. */
    fun process(
        samples: FloatArray,
        frameCount: Int,
        channelCount: Int,
        firstFrame: Int = 0,
    ) {
        require(channelCount > 0) { "channelCount must be positive" }
        require(frameCount >= 0 && firstFrame >= 0) { "frame offsets must be non-negative" }
        val endSample = (firstFrame.toLong() + frameCount) * channelCount
        require(endSample <= samples.size) { "requested frames exceed sample array" }

        repeat(frameCount) { frame ->
            val gain = nextGain()
            val base = (firstFrame + frame) * channelCount
            repeat(channelCount) { channel ->
                samples[base + channel] *= gain
            }
        }
    }

    private fun nextGain(): Float {
        if (rampPosition >= rampLength) {
            currentGain = targetGain
            return currentGain
        }
        rampPosition++
        currentGain = FadeGain.at(
            progress = rampPosition.toFloat() / rampLength,
            from = rampFrom,
            to = targetGain,
        )
        if (rampPosition == rampLength) currentGain = targetGain
        return currentGain
    }

    companion object {
        /** About 1.45ms at 44.1kHz: long enough to avoid a discontinuity, short enough for control. */
        const val DEFAULT_RAMP_FRAMES = 64

        private fun sanitize(gain: Float): Float =
            if (gain.isNaN()) 0f else gain.coerceIn(0f, 1f)
    }
}
