package com.music.bitchord.ui.audiophile

/** Immutable snapshot for the complete Audio settings hierarchy. */
data class AudiophileAudioState(
    val qualityOptionsEnabled: Boolean = false,
    val crossfadeFadeGapless: CrossfadeFadeGaplessState = CrossfadeFadeGaplessState(),
    val replayGain: ReplayGainState = ReplayGainState(),
    val audioFocus: AudioFocusState = AudioFocusState(),
    val equalizer: EqualizerSettingsState = EqualizerSettingsState(),
    val resampler: ResamplerSettingsState = ResamplerSettingsState(),
    val directVolumeControl: DirectVolumeControlState = DirectVolumeControlState(),
    val outputs: List<AudioOutputRouteState> = AudiophileAudioDefaults.outputRoutes,
    val bitPerfect: BitPerfectSettingsState = BitPerfectSettingsState(),
    val advancedTweaks: AdvancedTweaksState = AdvancedTweaksState(),
)

data class AudiophileAudioUiState(
    val page: AudiophileAudioPage = AudiophileAudioPage.HOME,
    val selectedRouteId: AudioOutputRouteId? = null,
    val selectedProfileId: String? = null,
    val bitPerfectShowMore: Boolean = false,
    val namedDeviceName: String = "",
)

enum class AudiophileAudioPage {
    HOME,
    AUDIO_INFO,
    CROSSFADE_FADE_GAPLESS,
    REPLAY_GAIN,
    AUDIO_FOCUS,
    EQUALIZER,
    RESAMPLER,
    DIRECT_VOLUME_CONTROL,
    OUTPUTS,
    OUTPUT_ROUTE,
    OUTPUT_PROFILE,
    BIT_PERFECT,
    ADVANCED_TWEAKS,
}

enum class AudiophileSettingsSection {
    QUALITY_OPTIONS,
    CROSSFADE_FADE_GAPLESS,
    REPLAY_GAIN,
    AUDIO_FOCUS,
    EQUALIZER,
    RESAMPLER,
    DIRECT_VOLUME_CONTROL,
    OUTPUTS,
    BIT_PERFECT,
    ADVANCED_TWEAKS,
}

/** Read-only runtime information. It is deliberately separate from persisted settings. */
data class AudioInfoState(
    val trackTitle: String = "No active track",
    val source: List<AudioInfoEntry> = emptyList(),
    val processing: List<AudioInfoEntry> = emptyList(),
    val output: List<AudioInfoEntry> = emptyList(),
    val badges: List<AudioStatusBadge> = emptyList(),
)

data class AudioInfoEntry(
    val label: String,
    val value: String,
)

data class AudioStatusBadge(
    val label: String,
    val tone: AudioStatusBadgeTone = AudioStatusBadgeTone.NEUTRAL,
)

enum class AudioStatusBadgeTone { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

/** Poweramp's factory transition values; durations are milliseconds. */
data class CrossfadeFadeGaplessState(
    val autoAdvanceFading: AutoAdvanceFading = AutoAdvanceFading.NO_FADING,
    val manualTrackChangeFading: ManualTrackChangeFading = ManualTrackChangeFading.SHORT_CROSSFADE,
    val crossfadeLengthMs: Int = 5_000,
    val shortManualCrossfadeLengthMs: Int = 400,
    val fadeOnPlayPauseStop: Boolean = true,
    val playPauseStopFadeLengthMs: Int = 400,
    val fadeOnSeek: Boolean = false,
    val seekFadeLengthMs: Int = 100,
    val gaplessEnabled: Boolean = true,
    val cutSilenceEnabled: Boolean = true,
    val preloadGaplessTracksMs: Int = 0,
)

enum class AutoAdvanceFading(val label: String) {
    NO_FADING("No fading"),
    CROSSFADE("Crossfade"),
}

enum class ManualTrackChangeFading(val label: String) {
    NO_FADING("No fading"),
    SHORT_CROSSFADE("Short crossfade"),
    CROSSFADE("Crossfade"),
}

data class ReplayGainState(
    val mode: ReplayGainMode = ReplayGainMode.APPLY_GAIN_PREVENT_CLIPPING,
    val source: ReplayGainSource = ReplayGainSource.ALBUM,
    val rgPreampDb: Float = 0f,
    val noRgPreampDb: Float = 0f,
)

enum class ReplayGainMode(val label: String) {
    OFF("No ReplayGain"),
    APPLY_GAIN("Apply gain"),
    APPLY_GAIN_PREVENT_CLIPPING("Apply gain / prevent clipping according to peak"),
}

enum class ReplayGainSource(val label: String) {
    TRACK("Track"),
    ALBUM("Album"),
    SMART("Smart"),
}

data class AudioFocusState(
    val pauseInCall: Boolean = true,
    val shortAudioFocusChangeCalls: Boolean = true,
    val duckVolumeEnabled: Boolean = true,
    val shortFocusDuckPolicy: AudioFocusDuckPolicy = AudioFocusDuckPolicy.DUCK,
    val notificationDuckPolicy: AudioFocusDuckPolicy = AudioFocusDuckPolicy.DUCK,
    val navigationDuckingEnabled: Boolean = true,
    val duckVolumePercent: Int = 20,
    val permanentAudioFocusChange: Boolean = true,
    val resumeAfterCall: Boolean = true,
    val resumeOnStart: Boolean = false,
    val resumeOnReopen: Boolean = false,
    val resumeOnFocusGain: Boolean = false,
    val waitForStorage: Boolean = true,
)

enum class AudioFocusDuckPolicy(val label: String) {
    PAUSE("Pause"),
    DUCK("Duck"),
    IGNORE("Ignore"),
}

/** Settings only: the actual graphic/parametric editor belongs to the EQ agent. */
data class EqualizerSettingsState(
    val bandCount: Int = 10,
    val mode: AudiophileEqualizerMode = AudiophileEqualizerMode.GRAPHIC,
    val customBandsEnabled: Boolean = false,
    val customBandCount: Int = 10,
    val firstFrequencyHz: Int = 20,
    val lastFrequencyHz: Int = 20_000,
    val layout: EqualizerLayout = EqualizerLayout.STANDARD,
)

val EqualizerSettingsState.effectiveBandCount: Int
    get() = if (customBandsEnabled) customBandCount else bandCount

enum class AudiophileEqualizerMode(val label: String) {
    GRAPHIC("Graphic"),
    PARAMETRIC("Parametric"),
}

enum class EqualizerLayout(val label: String) {
    STANDARD("Standard"),
    ALTERNATIVE("Alternative (wide screens)"),
}

data class ResamplerSettingsState(
    val type: ResamplerType = ResamplerType.SW,
    val quality: ResamplerQuality = ResamplerQuality.HIGH,
    val cutoffFrequencyPercent: Float = 97f,
    val phase: ResamplerPhase = ResamplerPhase.LINEAR,
    val steepFilterEnabled: Boolean = false,
    val dither: DitherMode = DitherMode.NONE,
)

enum class ResamplerType(val label: String) {
    SW("SW resampler"),
    SOX("SoX resampler"),
}

enum class ResamplerQuality(val label: String) {
    QUICK("Quick"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    VERY_HIGH("Very high"),
}

enum class ResamplerPhase(val label: String) {
    MINIMUM("Minimum"),
    INTERMEDIATE("Intermediate"),
    LINEAR("Linear"),
}

enum class DitherMode(val label: String) {
    NONE("None"),
    RECTANGULAR("Rectangular"),
    RECTANGULAR_HIGH_PASS("Rectangular (high-pass)"),
    TRIANGULAR("Triangular"),
    TRIANGULAR_HIGH_PASS("Triangular (high-pass)"),
    F_WEIGHTED("F-weighted noise shaping"),
    MODIFIED_E_WEIGHTED("Modified E-weighted noise shaping"),
    IMPROVED_E_WEIGHTED("Improved E-weighted noise shaping"),
    SHIBATA("Shibata noise shaping"),
}

data class DirectVolumeControlState(
    val dvcEnabled: Boolean = true,
    val volumeCompensationEnabled: Boolean = false,
    val noDvcHeadroomGainDb: Float = -6f,
    val noDvcForBluetoothAbsoluteVolume: Boolean = true,
    val bluetoothAbsoluteVolumeEnabled: Boolean = false,
)

data class BitPerfectSettingsState(
    val pipeline64Enabled: Boolean = false,
    val followTrackSampleRate: Boolean = false,
    val dsdRemasteringEnabled: Boolean = false,
    val dsdRate: DsdRate = DsdRate.DSD64,
    val dsdRemaster48kFamilyEnabled: Boolean = false,
    val dsdRemasterGainDb: Float = -2f,
    val perfectBitPerfectEnabled: Boolean = false,
)

enum class DsdRate(val label: String, val multiplier: Int) {
    DSD64("DSD64", 64),
    DSD128("DSD128", 128),
    DSD256("DSD256", 256),
    DSD512("DSD512", 512),
    DSD1024("DSD1024", 1024),
}

data class AdvancedTweaksState(
    val referenceModeEnabled: Boolean = false,
    val referenceMode: ReferenceMode = ReferenceMode.POWERAMP,
    val usbVolumeLockEnabled: Boolean = false,
    val usbGainCompensationEnabled: Boolean = false,
    val usbGainCompensationDb: Float = 0f,
    val volumeLevels: Int = 0,
    val volumeSteps: Int = 0,
    val musicFxEnabled: Boolean = false,
    val forceSpeakerOff: Boolean = false,
    val forceAudioOnFocusChange: Boolean = false,
    val emulatedMediaStream: Boolean = false,
    val audioThreadPriority: Int = 0,
)

enum class ReferenceMode(val label: String) {
    POWERAMP("Poweramp"),
    HIBY("HiBy"),
    HYBRID("Hybrid"),
    BIT_PERFECT("Bit-perfect"),
}

enum class AudioOutputRouteId(val label: String, val description: String) {
    OPEN_SL_ES("OpenSL ES Output", "Native optimized output"),
    HI_RES("Hi-Res Output", "Device high-resolution output"),
    AAUDIO("AAudio Output", "Low-latency Android output"),
    USB_EXCLUSIVE("USB Exclusive Output", "Direct USB DAC output"),
    AUDIO_TRACK("AudioTrack Output", "Java-based Android output"),
    CHROMECAST("Chromecast Output", "Network Cast output"),
}

data class AudioOutputRouteState(
    val id: AudioOutputRouteId = AudioOutputRouteId.OPEN_SL_ES,
    val enabled: Boolean = id == AudioOutputRouteId.OPEN_SL_ES,
    val available: Boolean = true,
    val status: OutputConnectionStatus = OutputConnectionStatus.UNKNOWN,
    val statusDetail: String = "",
    val profiles: List<OutputDeviceProfile> = emptyList(),
)

data class OutputDeviceProfile(
    val id: String,
    val kind: OutputDeviceKind,
    val name: String,
    val enabled: Boolean = kind.defaultEnabled,
    val connectionStatus: OutputConnectionStatus = OutputConnectionStatus.UNKNOWN,
    val statusDetail: String = "",
    val sampleRateHz: Int = 48_000,
    val supportedSampleRatesHz: List<Int> = AudiophileAudioDefaults.standardSampleRates,
    val actualSampleRateHz: Int? = null,
    val sampleFormat: OutputSampleFormat = OutputSampleFormat.PCM_16,
    val actualSampleFormat: OutputSampleFormat? = null,
    val bufferPreset: OutputBufferPreset = OutputBufferPreset.NORMAL,
    val bufferMs: Int = 100,
    val bufferCount: Int = 8,
    val postFadeBufferCount: Int = 8,
    val visualizationLyricsDelayMs: Int = 0,
    val noDvc: Boolean = false,
    val noHeadroomGain: Boolean = false,
    val noEquTone: Boolean = false,
    val noDuck: Boolean = false,
    val equalizerPresetId: String? = null,
    val availableEqualizerPresets: List<EqualizerPresetOption> = emptyList(),
    val bitPerfect: BitPerfectSettingsState = BitPerfectSettingsState(),
)

data class EqualizerPresetOption(
    val id: String,
    val name: String,
)

enum class OutputDeviceKind(val label: String, val defaultEnabled: Boolean) {
    SPEAKER("Speaker", true),
    WIRED_HEADSET("Wired Headset / AUX", true),
    BLUETOOTH("Bluetooth", true),
    USB_DAC("USB DAC", true),
    CHROMECAST("Chromecast", true),
    OTHER("Other output devices", true),
    NAMED("Named device", true),
}

enum class OutputConnectionStatus(val label: String) {
    UNKNOWN("Status unknown"),
    AVAILABLE("Available"),
    CONNECTED("Connected"),
    ACTIVE("Active"),
    DISCONNECTED("Disconnected"),
    UNSUPPORTED("Unsupported"),
    ERROR("Error"),
}

enum class OutputSampleFormat(val label: String) {
    PCM_16("16-bit PCM"),
    PCM_24("24-bit PCM"),
    PCM_24_PACKED("24-bit packed PCM"),
    PCM_32("32-bit PCM"),
    FLOAT_32("32-bit float"),
    DSD_NATIVE("Native DSD"),
    DSD_OVER_PCM("DSD over PCM (DoP)"),
}

enum class OutputBufferPreset(val label: String) {
    SMALL("Small"),
    NORMAL("Normal"),
    LARGE("Large"),
    CUSTOM("Custom"),
}

object AudiophileAudioDefaults {
    const val CROSSFADE_LENGTH_MS = 5_000
    const val SHORT_MANUAL_CROSSFADE_LENGTH_MS = 400
    const val PLAY_PAUSE_STOP_FADE_LENGTH_MS = 400
    const val SEEK_FADE_LENGTH_MS = 100
    const val PRELOAD_GAPLESS_TRACKS_MS = 0
    const val RESAMPLER_CUTOFF_PERCENT = 97f
    const val NO_DVC_HEADROOM_GAIN_DB = -6f
    const val DSD_REMASTER_GAIN_DB = -2f

    val fixedBandCounts: List<Int> = listOf(5, 8, 10, 12, 15, 16, 24, 31, 32)
    val standardSampleRates: List<Int> = listOf(44_100, 48_000)
    val highResolutionSampleRates: List<Int> = listOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000,
    )

    val outputRoutes: List<AudioOutputRouteState> = AudioOutputRouteId.entries.map(::defaultRoute)

    fun defaultRoute(id: AudioOutputRouteId): AudioOutputRouteState {
        val profiles = when (id) {
            AudioOutputRouteId.CHROMECAST -> listOf(OutputDeviceKind.CHROMECAST)
            else -> listOf(
                OutputDeviceKind.SPEAKER,
                OutputDeviceKind.WIRED_HEADSET,
                OutputDeviceKind.BLUETOOTH,
                OutputDeviceKind.USB_DAC,
                OutputDeviceKind.OTHER,
            )
        }.map { kind -> defaultProfile(id, kind) }
        return AudioOutputRouteState(
            id = id,
            enabled = id == AudioOutputRouteId.OPEN_SL_ES,
            status = if (id == AudioOutputRouteId.OPEN_SL_ES) {
                OutputConnectionStatus.AVAILABLE
            } else {
                OutputConnectionStatus.UNKNOWN
            },
            profiles = profiles,
        )
    }

    fun defaultProfile(
        routeId: AudioOutputRouteId,
        kind: OutputDeviceKind,
        name: String = kind.label,
        id: String = kind.name.lowercase(),
    ): OutputDeviceProfile = OutputDeviceProfile(
        id = id,
        kind = kind,
        name = name,
        supportedSampleRatesHz = when (routeId) {
            AudioOutputRouteId.HI_RES,
            AudioOutputRouteId.AAUDIO,
            AudioOutputRouteId.USB_EXCLUSIVE,
            -> highResolutionSampleRates
            else -> standardSampleRates
        },
        sampleFormat = when (routeId) {
            AudioOutputRouteId.HI_RES,
            AudioOutputRouteId.AAUDIO,
            AudioOutputRouteId.USB_EXCLUSIVE,
            -> OutputSampleFormat.PCM_24
            else -> OutputSampleFormat.PCM_16
        },
        bufferPreset = if (routeId == AudioOutputRouteId.CHROMECAST) {
            OutputBufferPreset.LARGE
        } else {
            OutputBufferPreset.NORMAL
        },
        bufferMs = if (routeId == AudioOutputRouteId.CHROMECAST) 1_500 else 100,
    )
}
