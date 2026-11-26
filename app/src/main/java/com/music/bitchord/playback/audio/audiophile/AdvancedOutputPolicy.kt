package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.playback.audio.ResolvedAudiophileOutput
import com.music.bitchord.ui.screens.audiophile.AdvancedTweaks
import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.ResamplerQuality

/** Resolved output switches for the service; stored advanced settings remain untouched. */
data class AdvancedOutputPolicy(
    val directRequested: Boolean,
    val offloadAllowed: Boolean,
    val usbHardwareVolume: Boolean,
    val diagnosticsEnabled: Boolean,
    val decodedPcmRequired: Boolean,
) {
    companion object {
        val DEFAULTS = AdvancedOutputPolicyResolver.resolve(AdvancedTweaks.DEFAULTS, null)
    }
}

/** Pure conflict resolution for flags that depend on the active output and focus state. */
object AdvancedOutputPolicyResolver {
    fun resolve(
        tweaks: AdvancedTweaks,
        output: ResolvedAudiophileOutput?,
        focusDucked: Boolean = false,
    ): AdvancedOutputPolicy {
        val effective = output?.effective
        val decodedPcmRequired = focusDucked || effective?.let {
            it.equalizerEnabled ||
                it.toneEnabled ||
                it.replayGainEnabled ||
                it.crossfadeEnabled ||
                it.dsdRemaster ||
                it.resamplerQuality != ResamplerQuality.OFF ||
                it.dither != DitherMode.NONE ||
                it.headroomDb != 0f ||
                it.dvcCompensateDb != 0f
        } == true
        return AdvancedOutputPolicy(
            directRequested = tweaks.directWhenAvailable,
            // Offload is permission, never a claim that a custom PCM-only output can offload.
            offloadAllowed = tweaks.allowOffload && !decodedPcmRequired,
            usbHardwareVolume = tweaks.usbHardwareVolume,
            diagnosticsEnabled = tweaks.audioDiagnostics,
            decodedPcmRequired = decodedPcmRequired,
        )
    }
}
