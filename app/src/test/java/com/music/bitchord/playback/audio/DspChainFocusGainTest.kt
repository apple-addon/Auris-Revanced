package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus-duck stage of [DspChain]: the audiophile runtime's
 * [com.music.bitchord.playback.audio.AudiophileRuntimeListener.onFocusGainChanged]
 * parks its gain on [DspChain.focusGain], which is separate from the
 * crossfade's [DspChain.fadeGain] so the transport fader cannot overwrite it.
 */
class DspChainFocusGainTest {

    private val sampleRate = 48_000

    private fun block(frames: Int, value: Float): AudioBlock {
        val block = AudioBlock(channelCount = 2, capacityFrames = frames)
        for (i in 0 until frames * 2) block.samples[i] = value
        block.reset(frames)
        return block
    }

    @Test
    fun `focus gain at unity keeps the chain bypassed and samples untouched`() {
        val chain = DspChain()
        chain.configure(sampleRate, 2)
        chain.focusGain.setTarget(1f)
        assertTrue(chain.isBypassed())

        val input = block(64, 0.5f)
        val before = input.samples.copyOf()
        chain.process(input)
        assertTrue(input.samples.contentEquals(before))
    }

    @Test
    fun `focus duck attenuates every sample and un-bypasses the chain`() {
        val chain = DspChain()
        chain.configure(sampleRate, 2)
        chain.focusGain.setTarget(0.25f)
        assertFalse(chain.isBypassed())

        // Let the click-free ramp settle past its (64-frame) default length.
        repeat(4) {
            val warm = block(64, 1.0f)
            chain.process(warm)
        }
        val input = block(64, 1.0f)
        chain.process(input)
        for (i in 0 until input.sampleCount) {
            assertEquals("sample $i not ducked to 0.25", 0.25f, input.samples[i], 1e-4f)
        }
    }

    @Test
    fun `focus duck survives flush and reset because it is policy state`() {
        val chain = DspChain()
        chain.configure(sampleRate, 2)
        chain.focusGain.setTarget(0.25f)

        chain.flush()
        assertEquals(0.25f, chain.focusGain.gain.targetGain, 0f)
        assertFalse(chain.isBypassed())

        chain.reset()
        assertEquals(0.25f, chain.focusGain.gain.targetGain, 0f)
        assertFalse(chain.isBypassed())
    }

    @Test
    fun `duck then restore returns the chain to the exact bypass`() {
        val chain = DspChain()
        chain.configure(sampleRate, 2)
        chain.focusGain.setTarget(0.25f)
        chain.focusGain.setTarget(1f)
        // Let the restore ramp settle before reading isBypassed, which looks
        // at the current gain as well as the target.
        chain.process(block(64, 0.5f))
        assertTrue(chain.isBypassed())

        val input = block(64, 0.5f)
        val before = input.samples.copyOf()
        chain.process(input)
        assertTrue(input.samples.contentEquals(before))
    }
}
