package com.music.bitchord.playback.audio.replaygain

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor

/**
 * Stateless, allocation-free ReplayGain stage for canonical Float32 blocks.
 *
 * [update] and the narrower setters construct one immutable snapshot off the
 * audio thread. [process] reads that snapshot once and performs only an
 * in-place multiply. Disabled and unity-gain states return before touching a
 * sample, preserving the input bit pattern exactly.
 *
 * Peak prevention follows the ReplayGain specification: the selected gain is
 * capped at `1 / selectedPeak`, using the peak paired with the selected gain
 * after source fallback. It is not a lookahead limiter and never hard-clips a
 * waveform. [nonRgPreampDb] applies only when neither source provides a gain.
 * A present 0 dB gain still counts as ReplayGain and uses [rgPreampDb].
 */
class ReplayGainProcessor : FloatAudioProcessor {
    @Volatile
    private var state = ReplayGainSnapshot(
        enabled = false,
        source = ReplayGainSource.TRACK,
        metadata = ReplayGainMetadata.EMPTY,
        albumContext = false,
        rgPreampDb = 0f,
        nonRgPreampDb = 0f,
        preventClipping = true,
    )

    val snapshot: ReplayGainSnapshot
        get() = state

    val isEnabled: Boolean
        get() = state.enabled

    val isBypassed: Boolean
        get() = state.isBypassed

    /** Publishes track metadata and every tuning value as one atomic update. */
    @Synchronized
    fun update(
        enabled: Boolean,
        metadata: ReplayGainMetadata,
        source: ReplayGainSource = ReplayGainSource.TRACK,
        albumContext: Boolean = false,
        rgPreampDb: Float = 0f,
        nonRgPreampDb: Float = 0f,
        preventClipping: Boolean = true,
    ) {
        state = ReplayGainSnapshot(
            enabled = enabled,
            source = source,
            metadata = metadata,
            albumContext = albumContext,
            rgPreampDb = rgPreampDb,
            nonRgPreampDb = nonRgPreampDb,
            preventClipping = preventClipping,
        )
    }

    /** Changes settings without changing the current track or album context. */
    @Synchronized
    fun setTuning(
        enabled: Boolean,
        source: ReplayGainSource = ReplayGainSource.TRACK,
        rgPreampDb: Float = 0f,
        nonRgPreampDb: Float = 0f,
        preventClipping: Boolean = true,
    ) {
        val current = state
        state = ReplayGainSnapshot(
            enabled = enabled,
            source = source,
            metadata = current.metadata,
            albumContext = current.albumContext,
            rgPreampDb = rgPreampDb,
            nonRgPreampDb = nonRgPreampDb,
            preventClipping = preventClipping,
        )
    }

    /** Publishes a track boundary without changing any tuning value. */
    @Synchronized
    fun setMetadata(metadata: ReplayGainMetadata, albumContext: Boolean = false) {
        val current = state
        state = ReplayGainSnapshot(
            enabled = current.enabled,
            source = current.source,
            metadata = metadata,
            albumContext = albumContext,
            rgPreampDb = current.rgPreampDb,
            nonRgPreampDb = current.nonRgPreampDb,
            preventClipping = current.preventClipping,
        )
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val current = state
        if (current.enabled == enabled) return
        state = ReplayGainSnapshot(
            enabled = enabled,
            source = current.source,
            metadata = current.metadata,
            albumContext = current.albumContext,
            rgPreampDb = current.rgPreampDb,
            nonRgPreampDb = current.nonRgPreampDb,
            preventClipping = current.preventClipping,
        )
    }

    override fun configure(sampleRate: Int, channelCount: Int) = Unit

    override fun process(block: AudioBlock) {
        val current = state
        if (current.isBypassed || block.frameCount == 0) return

        val samples = block.samples
        val sampleCount = block.sampleCount
        val gain = current.linearGain
        var index = 0
        while (index < sampleCount) {
            samples[index] *= gain
            index++
        }
    }

    override fun flush() = Unit

    /** ReplayGain has no filter state or buffers; tuning survives a reset. */
    override fun reset() = Unit
}
