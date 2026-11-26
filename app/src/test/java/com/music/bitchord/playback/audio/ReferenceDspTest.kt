package com.music.bitchord.playback.audio

import com.music.bitchord.playback.EqLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class ReferenceDspTest {
    @Test
    fun `graphic layout has ten requested centres and independent tone slots`() {
        assertEquals(10, EqLayout.MANUAL_COUNT)
        assertEquals(35, EqLayout.SLOTS)
        assertEquals(31f, EqLayout.MANUAL_BANDS_HZ.first())
        assertEquals(16_000f, EqLayout.MANUAL_BANDS_HZ.last())
        assertEquals(32, EqLayout.TONE_LOW)
        assertTrue(EqLayout.TONE_LOW >= EqLayout.MANUAL_COUNT)
    }

    @Test
    fun `preamp works without active biquad bands`() {
        val bank = BiquadBank(10)
        bank.configure(48_000, 2)
        bank.setTarget(enabled = true, specs = emptyList(), preampDb = 6f)
        bank.flush()
        val block = AudioBlock(2, 64)
        block.samples.fill(0.25f)
        block.reset(64)
        bank.process(block)
        val expected = 0.25f * 10f.pow(6f / 20f)
        block.samples.forEach { assertEquals(expected, it, 0.0001f) }
    }

    @Test
    fun `disabled bank settles back to exact passthrough`() {
        val bank = BiquadBank(10)
        bank.configure(48_000, 2)
        bank.setTarget(true, listOf(BiquadSpec(1000f, 0.7f, 6f)), 3f)
        bank.flush()
        bank.setTarget(false, emptyList())
        val block = AudioBlock(2, 64)
        repeat(300) {
            block.samples.fill(0.2f)
            block.reset(64)
            bank.process(block)
        }
        assertFalse(bank.isEnabled)
        for (index in block.samples.indices) block.samples[index] = (index + 1) * 0.001f
        val before = block.samples.copyOf()
        bank.process(block)
        assertTrue(before.contentEquals(block.samples))
    }

    @Test
    fun `non finite settings and samples cannot poison filter state`() {
        val bank = BiquadBank(10)
        bank.configure(48_000, 2)
        bank.setTarget(true, listOf(BiquadSpec(Float.NaN, Float.NaN, 6f)), Float.NaN)
        bank.flush()
        val block = AudioBlock(2, 64)
        block.samples.fill(0.2f)
        block.samples[0] = Float.NaN
        block.samples[1] = Float.POSITIVE_INFINITY
        block.reset(64)
        bank.process(block)
        block.samples.forEach { assertTrue(it.isFinite()) }
        assertEquals(0f, block.samples[0], 0f)
        assertEquals(0f, block.samples[1], 0f)
    }

    @Test
    fun `mono averages every channel without touching other frames`() {
        val block = AudioBlock(2, 2)
        block.samples[0] = -1f
        block.samples[1] = 0.5f
        block.samples[2] = 0.25f
        block.samples[3] = 0.75f
        block.reset(1)
        MonoAudioProcessor.isEnabled = true
        MonoAudioProcessor.process(block)
        assertEquals(-0.25f, block.samples[0], 0f)
        assertEquals(-0.25f, block.samples[1], 0f)
        assertEquals(0.25f, block.samples[2], 0f)
        assertEquals(0.75f, block.samples[3], 0f)
        MonoAudioProcessor.isEnabled = false
    }
}
