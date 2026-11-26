package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.playback.audio.ResolvedAudiophileOutput
import com.music.bitchord.ui.screens.audiophile.AdvancedTweaks
import java.util.Locale

/** Stable identity used to distinguish a real route/device change from a telemetry refresh. */
data class AudioOutputRouteKey(
    val routeKindName: String,
    val deviceIdentity: String,
) {
    companion object {
        fun of(
            routeKindName: String,
            deviceName: String,
            output: ResolvedAudiophileOutput,
        ): AudioOutputRouteKey = AudioOutputRouteKey(
            routeKindName = routeKindName,
            deviceIdentity = output.target.deviceKey
                ?: deviceName.trim().lowercase(Locale.ROOT),
        )
    }
}

/** Explicit service instruction for an output transition; no app preference is mutated here. */
data class RouteEqualizerChange(
    val previous: ResolvedAudiophileOutput,
    val current: ResolvedAudiophileOutput,
    val resetEqualizer: Boolean,
) {
    val equalizerEnabled: Boolean get() = current.effective.equalizerEnabled
    val toneEnabled: Boolean get() = current.effective.toneEnabled
}

object RouteEqualizerPolicy {
    fun onOutputChanged(
        previousKey: AudioOutputRouteKey,
        currentKey: AudioOutputRouteKey,
        previous: ResolvedAudiophileOutput,
        current: ResolvedAudiophileOutput,
        tweaks: AdvancedTweaks,
    ): RouteEqualizerChange? {
        if (previousKey == currentKey) return null
        return RouteEqualizerChange(
            previous = previous,
            current = current,
            resetEqualizer = tweaks.resetEqualizerOnRouteChange,
        )
    }
}
