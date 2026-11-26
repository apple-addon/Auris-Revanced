package com.music.bitchord.playback.audio

import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.playback.EqualizerProcessor
import com.music.bitchord.playback.LoudnessProcessor
import com.music.bitchord.playback.SpatialAudioProcessor
import com.music.bitchord.playback.TransitionFilterProcessor
import com.music.bitchord.playback.VocalCutProcessor
import com.music.bitchord.playback.audio.replaygain.ReplayGainProcessor
import com.music.bitchord.playback.fades.FadeGainProcessor

/**
 * Composite DSP chain executing BitChord's custom audio processors in their canonical sequence:
 *
 * AudioBlock(Float32) -> VocalCutProcessor -> SpatialAudioProcessor -> MsebProcessor
 *   -> ParametricEqProcessor -> EqualizerProcessor -> JamesDspProcessor
 *   -> TransitionFilterProcessor -> LoudnessProcessor -> FadeGainProcessor
 *   -> FocusGainProcessor -> AudioBlock(Float32)
 *
 * The HiBy-shaped stages (MSEB, parametric EQ) run before the Poweramp-shaped
 * ones so Hybrid is a defined order rather than two curves racing. Both are
 * self-bypassing and contribute to [isBypassed], so an unused mode costs
 * nothing and a fully idle chain still hands the block through untouched.
 *
 * Sing's vocal cut runs first: it removes the center the other stages would
 * otherwise widen, equalise and level along with everything else.
 *
 * JamesDSP runs after the equaliser and before the per-track transition and
 * loudness stages. It is a tone/space stage here, not a second output chain:
 * its own limiter protects the boosts it applies, then LoudnessProcessor has
 * the last word on level. It is bypassed entirely when switched off.
 *
 * Operates purely on in-place Float32 audio blocks without intermediate fixed-point quantization,
 * preserving full dynamic range and headroom.
 *
 * Loudness normalization is followed only by the fade/focus gain stages. The
 * transport fade ([fadeGain]) belongs to the crossfade controller; the focus
 * duck ([focusGain]) belongs to the audiophile runtime's focus policy and is
 * driven by [AudiophileRuntimeListener.onFocusGainChanged]. They are separate
 * stages because they are separate owners: the fader re-targets every 10 ms
 * and would overwrite a duck level parked on the same processor. Both are
 * [FadeGainProcessor]s, so both ramp through [com.music.bitchord.playback.fades.ClickFreeGain]
 * and neither can click. Loudness still has the last word on level among the
 * shaping stages, because its limiter has to see the signal every other stage
 * has finished shaping.
 *
 * ## Staying out of the way
 *
 * Every stage here self-bypasses when its own setting is off, returning before
 * it reads a single sample rather than multiplying through by unity. With every
 * stage idle the block leaves this class byte-identical to how it arrived,
 * which is what lets [PcmBoundary]'s power-of-two scaling round-trip 16- and
 * 24-bit integers unchanged — see `PrecisionAudioSink.publishOutputExactness`.
 * [isBypassed] lets the sink skip the decode-and-re-encode around that
 * byte-identical trip entirely.
 */
class DspChain(
    val sing: VocalCutProcessor = VocalCutProcessor(),
    val spatial: SpatialAudioProcessor = SpatialAudioProcessor(),
    val simAtmos: SimulatedAtmosProcessor = SimulatedAtmosProcessor(),
    val equalizer: EqualizerProcessor = EqualizerProcessor(),
    val jamesDsp: JamesDspProcessor = JamesDspProcessor(),
    val transition: TransitionFilterProcessor = TransitionFilterProcessor(),
    val loudness: LoudnessProcessor = LoudnessProcessor(),
    val mseb: MsebProcessor = MsebProcessor(),
    val peq: ParametricEqProcessor = ParametricEqProcessor(),
    val mono: MonoAudioProcessor = MonoAudioProcessor,
    val replayGain: ReplayGainProcessor = ReplayGainProcessor(),
    val fadeGain: FadeGainProcessor = FadeGainProcessor(),
    val nativeReference: NativeReferenceProcessor = NativeReferenceProcessor(),
    /**
     * Focus-duck gain from the audiophile runtime, last in the chain so it
     * attenuates everything the other stages produced. Distinct from
     * [fadeGain]: that one is the crossfade controller's, this one is the
     * focus policy's, and the two must not share a target.
     */
    val focusGain: FadeGainProcessor = FadeGainProcessor(),
) : FloatAudioProcessor {

    private var currentSampleRate: Int = 0
    private var processCounter: Long = 0L

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.currentSampleRate = sampleRate
        sing.configure(sampleRate, channelCount)
        mono.configure(sampleRate, channelCount)
        spatial.configure(sampleRate, channelCount)
        simAtmos.configure(sampleRate, channelCount)
        equalizer.configure(sampleRate, channelCount)
        jamesDsp.configure(sampleRate, channelCount)
        transition.configure(sampleRate, channelCount)
        loudness.configure(sampleRate, channelCount)
        mseb.configure(sampleRate, channelCount)
        peq.configure(sampleRate, channelCount)
        replayGain.configure(sampleRate, channelCount)
        fadeGain.configure(sampleRate, channelCount)
        nativeReference.configure(sampleRate, channelCount)
        focusGain.configure(sampleRate, channelCount)
    }

    /** A new track has begun gaplessly on this sink; see [LoudnessProcessor.onStreamBoundary]. */
    fun onStreamBoundary() {
        loudness.onStreamBoundary()
    }

    /**
     * Whether [process] would return the block byte-identical, i.e. every stage
     * is idle. Read on the audio thread once per decoder buffer; when true and
     * the input and output encodings already match, the sink hands the decoder's
     * buffer straight to the delegate instead of decoding it to float and
     * encoding it back.
     */
    fun isBypassed(): Boolean =
        currentSampleRate > 0 &&
            sing.level <= 0.001f &&
            !mono.isEnabled &&
            !spatial.enabled &&
            !simAtmos.enabled &&
            !equalizer.isEnabled &&
            !jamesDsp.isEnabled &&
            !transition.isEngaged &&
            !mseb.isEnabled &&
            !peq.isEnabled &&
            replayGain.isBypassed &&
            fadeGain.isBypassed &&
            !nativeReference.isEnabled &&
            focusGain.isBypassed &&
            loudness.isParked()

    override fun process(block: AudioBlock) {
        if (block.frameCount == 0) return

        if (BuildConfig.DEBUG) {
            processCounter++
            if (processCounter == 1L || processCounter % 500L == 0L) {
                try {
                    val count = processCounter
                    val frames = block.frameCount
                    val sr = currentSampleRate
                    val spatialOn = spatial.enabled
                    val eqOn = equalizer.isEnabled
                    val transitionOn = transition.isFiltering
                    Log.d(
                        TAG,
                        "process() #$count frames=$frames sr=$sr " +
                            "sing=${sing.level} spatial=$spatialOn eq=$eqOn transition=$transitionOn",
                    )
                } catch (_: Throwable) {
                }
            }
        }

        replayGain.process(block)
        nativeReference.process(block)
        sing.process(block)
        mono.process(block)
        spatial.process(block)
        simAtmos.process(block)
        if (!nativeReference.isEnabled) {
            mseb.process(block)
            peq.process(block)
        }
        if (!nativeReference.runsPowerampStages) {
            equalizer.process(block)
        }
        jamesDsp.process(block)
        transition.process(block)
        loudness.process(block)
        fadeGain.process(block)
        focusGain.process(block)
    }

    @Suppress("DEPRECATION")
    override fun flush() {
        sing.flush()
        mono.flush()
        simAtmos.flush()
        spatial.flush()
        equalizer.flush()
        jamesDsp.flush()
        transition.flush()
        loudness.flush()
        mseb.flush()
        peq.flush()
        replayGain.flush()
        fadeGain.flush()
        nativeReference.flush()
        // A flush is a discontinuity, not a policy change: an active focus
        // duck has to survive it, because the runtime only re-delivers the
        // gain when it *changes*. Restore the target after the reset.
        val focusTarget = focusGain.gain.targetGain
        focusGain.flush()
        focusGain.setTarget(focusTarget)
    }

    override fun reset() {
        processCounter = 0L
        sing.reset()
        mono.reset()
        spatial.reset()
        simAtmos.reset()
        equalizer.reset()
        jamesDsp.reset()
        transition.reset()
        loudness.reset()
        mseb.reset()
        peq.reset()
        replayGain.reset()
        fadeGain.reset()
        nativeReference.reset()
        // Same as flush: the duck level is policy state, not stream state.
        val focusTarget = focusGain.gain.targetGain
        focusGain.reset()
        focusGain.setTarget(focusTarget)
    }

    companion object {
        private const val TAG = "DspChain"
    }
}
