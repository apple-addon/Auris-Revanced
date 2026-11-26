package com.music.bitchord.playback.fades

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClickFreeGainTest {
    @Test
    fun microRampReachesEveryIntermediateFrameAcrossAllChannels() {
        val gain = ClickFreeGain(initialGain = 0f, defaultRampFrames = 4)
        val samples = FloatArray(8) { 1f }
        gain.setTarget(1f)
        gain.process(samples, channelCount = 2)

        val expected = floatArrayOf(0.25f, 0.25f, 0.5f, 0.5f, 0.75f, 0.75f, 1f, 1f)
        expected.indices.forEach { index ->
            assertEquals(expected[index], samples[index], 0.0001f)
        }
        assertEquals(1f, gain.currentGain, 0f)
    }

    @Test
    fun retargetingStartsAtLastRenderedGainWithoutJumpOrOvershoot() {
        val gain = ClickFreeGain(initialGain = 0f, defaultRampFrames = 4)
        val frame = FloatArray(1) { 1f }
        gain.setTarget(1f)
        gain.process(frame, channelCount = 1)
        gain.process(frame, channelCount = 1)
        assertEquals(0.5f, gain.currentGain, 0f)

        gain.setTarget(0f)
        frame[0] = 1f
        gain.process(frame, channelCount = 1)
        assertEquals(0.375f, gain.currentGain, 0.0001f)
        assertEquals(0.375f, frame[0], 0.0001f)

        repeat(3) { gain.process(frame, channelCount = 1) }
        assertEquals(0f, gain.currentGain, 0f)
        assertEquals(0f, frame[0], 0f)
    }

    @Test
    fun offsetProcessingLeavesOtherFramesUntouched() {
        val gain = ClickFreeGain(initialGain = 0f, defaultRampFrames = 2)
        val samples = FloatArray(6) { 1f }
        gain.setTarget(1f)
        gain.process(samples, frameCount = 2, channelCount = 1, firstFrame = 2)
        assertTrue(samples[0] == 1f && samples[1] == 1f)
        assertEquals(0.5f, samples[2], 0.0001f)
        assertEquals(1f, samples[3], 0.0001f)
        assertTrue(samples[4] == 1f && samples[5] == 1f)
    }

    @Test
    fun equalPowerCrossfadeHasExactEndpointsAndConstantPower() {
        assertEquals(CrossfadeGains(1f, 0f), FadeGain.equalPower(0f))
        assertEquals(CrossfadeGains(0f, 1f), FadeGain.equalPower(1f))
        listOf(0.2f, 0.5f, 0.8f).forEach { progress ->
            val gains = FadeGain.equalPower(progress)
            val power = gains.outgoing * gains.outgoing + gains.incoming * gains.incoming
            assertTrue(abs(1f - power) < 0.0001f)
            assertTrue(gains.outgoing in 0f..1f)
            assertTrue(gains.incoming in 0f..1f)
        }
    }
}
