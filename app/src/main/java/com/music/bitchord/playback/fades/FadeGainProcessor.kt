package com.music.bitchord.playback.fades

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor

/** One independent transport-fade gain stage per A/B player. */
class FadeGainProcessor : FloatAudioProcessor {
    val gain = ClickFreeGain()

    val isBypassed: Boolean
        get() = gain.currentGain == 1f && gain.targetGain == 1f

    fun setTarget(value: Float) = gain.setTarget(value)

    override fun configure(sampleRate: Int, channelCount: Int) = Unit

    override fun process(block: AudioBlock) {
        if (!isBypassed) gain.process(block.samples, block.frameCount, block.channelCount)
    }

    override fun flush() = gain.reset()
    override fun reset() = gain.reset()
}
