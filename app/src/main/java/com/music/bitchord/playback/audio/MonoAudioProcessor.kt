package com.music.bitchord.playback.audio

/** Stateless channel-average stage shared safely by both A/B chains. */
object MonoAudioProcessor : FloatAudioProcessor {
    @Volatile
    var isEnabled: Boolean = false

    override fun configure(sampleRate: Int, channelCount: Int) = Unit

    override fun process(block: AudioBlock) {
        if (!isEnabled || block.channelCount < 2) return
        val channels = block.channelCount
        for (frame in 0 until block.frameCount) {
            val base = frame * channels
            var sum = 0.0
            for (channel in 0 until channels) sum += block.samples[base + channel]
            val mono = (sum / channels).toFloat()
            for (channel in 0 until channels) block.samples[base + channel] = mono
        }
    }

    override fun flush() = Unit
    override fun reset() = Unit
}
