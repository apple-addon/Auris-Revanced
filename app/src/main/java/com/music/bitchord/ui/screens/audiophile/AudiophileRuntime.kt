package com.music.bitchord.ui.screens.audiophile

import android.content.Context
import com.music.bitchord.playback.audio.NativeOutputBackend
import com.music.bitchord.playback.audio.ReferenceMode
import com.music.bitchord.playback.fades.AudioFocusSettings
import com.music.bitchord.playback.fades.FadeSettings
import com.music.bitchord.playback.fades.FadeSettingsStore

/**
 * Playback integration hooks. Persistence always happens before the corresponding hook runs.
 * A parent can keep one instance and forward changes to its service without touching these screens.
 */
data class AudiophileRuntime(
    val onAudioFocusChanged: (AudioFocusSettings) -> Unit = {},
    val onResamplerChanged: (ResamplerSettings) -> Unit = {},
    val onDvcChanged: (DvcSettings) -> Unit = {},
    val onBitPerfectChanged: (BitPerfectSettings) -> Unit = {},
    val onAdvancedTweaksChanged: (AdvancedTweaks) -> Unit = {},
    val onOutputProfileChanged: (
        target: AudioOutputTarget,
        profile: OutputProfile,
        effective: EffectiveAudiophileOutput,
    ) -> Unit = { _, _, _ -> },
    val onOutputProfileRemoved: (AudioOutputTarget) -> Unit = {},
    val onFadeSettingsChanged: (FadeSettings) -> Unit = {},
    val onReplayGainChanged: () -> Unit = {},
    val onEqualizerChanged: () -> Unit = {},
    val onReferenceModeChanged: (ReferenceMode) -> Unit = {},
    val onPerfectBitPerfectRequested: (Boolean) -> Unit = {},
    val onNativeOutputBackendChanged: (NativeOutputBackend) -> Unit = {},
) {
    companion object {
        val NONE = AudiophileRuntime()
    }
}

/** Owned store group used by the hierarchy, or mounted independently by a parent/service. */
class AudiophileStores(
    val settings: AudiophileSettingsStore,
    val outputs: AudioOutputProfilesStore,
    val fades: FadeSettingsStore,
) : AutoCloseable {
    constructor(context: Context) : this(
        settings = AudiophileSettingsStore(context),
        outputs = AudioOutputProfilesStore(context),
        fades = FadeSettingsStore(context),
    )

    override fun close() {
        settings.close()
        outputs.close()
        fades.close()
    }
}

enum class AudiophileSection(val title: String, val subtitle: String) {
    AUDIO_INFO(
        "Audio Info",
        "Detailed info about the audio processing. Also available by long press on small meta info on the Main and Equalizer screens",
    ),
    FADES("Crossfade, Fade, and Gapless", "Crossfade options, fade type and length, gapless"),
    REPLAY_GAIN("Replay Gain (RG)", "Enable RG, set source, preamp values"),
    AUDIO_FOCUS("Audio Focus", "Pause/Resume/Duck volume on calls/notifications/start"),
    EQUALIZER("Equalizer", "Equalizer settings, number of bands, frequencies"),
    RESAMPLER("Resampler", "Resampling/dither settings"),
    DVC("Direct Volume Control (DVC)", "DVC options, DVC for Bluetooth"),
    AUDIO_OUTPUTS("Audio Outputs", "Wired Headset, Bluetooth, USB, Chromecast, and other outputs"),
    BIT_PERFECT("Bit Perfect", "64-bit audio pipeline, Perfect Bit Perfect"),
    ADVANCED("Advanced Tweaks", "Volume levels, MusicFX, equalizer/reverb presets reset"),
    REFERENCE_MODE("Reference Mode", "Reference engine and Quality Options"),
}
