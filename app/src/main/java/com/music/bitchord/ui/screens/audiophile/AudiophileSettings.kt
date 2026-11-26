package com.music.bitchord.ui.screens.audiophile

import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.fades.AudioFocusSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/** Resampling is deliberately explicit: OFF is the only sample-rate passthrough mode. */
enum class ResamplerQuality(val stored: String, val label: String) {
    OFF("off", "Off / Passthrough"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "SW - high quality"),
    VERY_HIGH("very_high", "SoX - very high quality"),
    ;

    companion object {
        fun fromStored(value: String?): ResamplerQuality = when (value?.trim()?.lowercase()) {
            "0", "none", "passthrough", "off" -> OFF
            "1", "low" -> LOW
            "2", "medium", "normal" -> MEDIUM
            "4", "best", "sox", "very_high", "very high" -> VERY_HIGH
            else -> HIGH
        }
    }
}

/**
 * The nine dither/noise-shaping shapes, in the exact order and with the exact
 * labels of the reference `pref_dither_entries` array (paws
 * res/values/arrays.xml): None, Rectangular, Triangular, Triangular with high
 * pass, then the five slow PAWS noise-shaping modes — F-weighted,
 * Modified-e-weighted, Improved-e-weighted, Shibata and Low shibata. The
 * default remains [NONE], and [AudiophileResampler] implements a real
 * error-feedback noise-shaping filter for each of the five slow modes.
 */
enum class DitherMode(val stored: String, val label: String) {
    NONE("none", "None (fastest)"),
    RECTANGULAR("rectangular", "Rectangular (fast)"),
    TRIANGULAR("triangular", "Triangular (fast)"),
    HIGH_PASS("high_pass", "Triangular with high pass (fast)"),
    F_WEIGHTED("f_weighted", "F-weighted noise shaping (slow)"),
    MODIFIED_E_WEIGHTED("modified_e_weighted", "Modified-e-weighted noise shaping (slow)"),
    IMPROVED_E_WEIGHTED("improved_e_weighted", "Improved-e-weighted noise shaping (slow)"),
    SHIBATA("shibata", "Shibata noise shaping (slow)"),
    LOW_SHIBATA("low_shibata", "Low shibata noise shaping (slow)"),
    ;

    /** True for the five PAWS noise-shaping modes that run an error-feedback filter. */
    val isNoiseShaping: Boolean
        get() = when (this) {
            F_WEIGHTED, MODIFIED_E_WEIGHTED, IMPROVED_E_WEIGHTED, SHIBATA, LOW_SHIBATA -> true
            NONE, RECTANGULAR, TRIANGULAR, HIGH_PASS -> false
        }

    companion object {
        fun fromStored(value: String?): DitherMode = when (value?.trim()?.lowercase()) {
            "1", "rect", "rectangular" -> RECTANGULAR
            "2", "tri", "triangular", "tpdf" -> TRIANGULAR
            "3", "high_pass", "high-pass", "high-pass triangular" -> HIGH_PASS
            "4", "f_weighted", "f-weighted", "f weighted" -> F_WEIGHTED
            "5", "modified_e_weighted", "modified-e-weighted", "modified e weighted" -> MODIFIED_E_WEIGHTED
            "6", "improved_e_weighted", "improved-e-weighted", "improved e weighted" -> IMPROVED_E_WEIGHTED
            "7", "shibata" -> SHIBATA
            "8", "low_shibata", "low-shibata", "low shibata" -> LOW_SHIBATA
            else -> NONE
        }
    }
}

data class ResamplerSettings(
    val quality: ResamplerQuality = ResamplerQuality.HIGH,
    /** Reference default 970 with scale 10: 97.0%, accepted 80.0..99.0%. */
    val cutoffPercent: Int = 97,
    val dither: DitherMode = DitherMode.NONE,
) {
    fun normalized(): ResamplerSettings = copy(cutoffPercent = cutoffPercent.coerceIn(80, 99))

    companion object {
        val DEFAULTS = ResamplerSettings()
    }
}

enum class DvcBluetoothPolicy(val stored: String, val label: String) {
    FOLLOW_GLOBAL("follow_global", "Follow DVC"),
    FORCE_ON("force_on", "Always On"),
    FORCE_OFF("force_off", "No DVC for Bluetooth Absolute Volume"),
    ;

    companion object {
        fun fromStored(value: String?): DvcBluetoothPolicy = when (value?.trim()?.lowercase()) {
            "1", "on", "enabled", "always_on", "force_on" -> FORCE_ON
            "2", "off", "disabled", "no_dvc", "force_off" -> FORCE_OFF
            else -> FOLLOW_GLOBAL
        }
    }
}

data class DvcSettings(
    val enabled: Boolean = true,
    /** Reference default -600 with scale 100: -6.0 dB. Always in -10..0 dB. */
    val headroomDb: Float = -6f,
    /** Reference default: No DVC for Bluetooth Absolute Volume. */
    val bluetoothPolicy: DvcBluetoothPolicy = DvcBluetoothPolicy.FORCE_OFF,
    /**
     * Reference `compensate_dvc_vol` switch, default false. At runtime it only
     * acts while DVC is disabled for the active output: a bounded positive
     * compensation gain is then applied through the native limiter/DVC path
     * (see [com.music.bitchord.playback.audio.packNativeReferenceSettings]),
     * with the limiter forced on so the boost can never clip silently.
     */
    val compensateVolume: Boolean = false,
) {
    fun normalized(): DvcSettings = copy(
        headroomDb = if (headroomDb.isFinite()) {
            -abs(headroomDb).coerceAtMost(10f)
        } else {
            0f
        },
    )

    companion object {
        val DEFAULTS = DvcSettings()

        /** The compensation gain applied while DVC is off and the switch is on. */
        const val COMPENSATE_DB: Float = 6f

        /** Hard upper bound for any compensation gain handed to the native path. */
        const val COMPENSATE_MAX_DB: Float = 12f
    }
}

/** Global processing choices. Perfect BP itself remains AppSettings.bitPerfect. */
data class BitPerfectSettings(
    val process64Bit: Boolean = true,
    val followSampleRate: Boolean = true,
    val dsdRemaster: Boolean = false,
    /** Persisted so the disclosure control works exactly like every other control. */
    val showMore: Boolean = false,
) {
    companion object {
        val DEFAULTS = BitPerfectSettings()
    }
}

data class AdvancedTweaks(
    val directWhenAvailable: Boolean = true,
    val allowOffload: Boolean = false,
    val usbHardwareVolume: Boolean = true,
    val resetEqualizerOnRouteChange: Boolean = false,
    val audioDiagnostics: Boolean = false,
) {
    companion object {
        val DEFAULTS = AdvancedTweaks()
    }
}

/** One transaction in [AudiophileSettingsStore] covers every field in this snapshot. */
data class AudiophileSettings(
    val focus: AudioFocusSettings = AudioFocusSettings(),
    val resampler: ResamplerSettings = ResamplerSettings.DEFAULTS,
    val dvc: DvcSettings = DvcSettings.DEFAULTS,
    val bitPerfect: BitPerfectSettings = BitPerfectSettings.DEFAULTS,
    val advanced: AdvancedTweaks = AdvancedTweaks.DEFAULTS,
) {
    fun normalized(): AudiophileSettings = copy(
        resampler = resampler.normalized(),
        dvc = dvc.normalized(),
    )

    companion object {
        val DEFAULTS = AudiophileSettings()
    }
}

enum class AudioOutputRoute(val stored: String, val label: String) {
    PHONE("phone", "Speaker"),
    WIRED("wired", "Wired Headset/AUX"),
    USB("usb", "USB DAC"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    HDMI("hdmi", "HDMI"),
    OTHER("other", "Other Output Devices"),
    ;

    companion object {
        fun fromStored(value: String?): AudioOutputRoute =
            entries.firstOrNull { it.stored == value || it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

enum class OutputDriver(val stored: String, val label: String) {
    AUDIO_TRACK("audio_track", "AudioTrack"),
    HI_RES("hi_res", "Hi-Res"),
    USB_EXCLUSIVE("usb_exclusive", "USB Exclusive"),
    AAUDIO("aaudio", "AAudio"),
    OPENSL_ES("opensl_es", "OpenSL ES"),
    ;

    companion object {
        fun fromStored(value: String?): OutputDriver = when (value?.trim()?.lowercase()) {
            "1", "hires", "hi-res", "hi_res", "hi_res_audio_track" -> HI_RES
            "2", "usb", "usb_exclusive", "direct_usb" -> USB_EXCLUSIVE
            "3", "aaudio" -> AAUDIO
            "4", "opensl", "opensl_es", "open sl es" -> OPENSL_ES
            else -> AUDIO_TRACK
        }
    }
}

/** A nullable override on a profile means all five decisions follow the global settings. */
data class BitPerfectOutputOverride(
    val process64Bit: Boolean = true,
    val followSampleRate: Boolean = true,
    val dsdRemaster: Boolean = false,
    val perfectBitPerfect: Boolean = false,
    val showMore: Boolean = false,
) {
    companion object {
        val DEFAULTS = BitPerfectOutputOverride()

        fun fromGlobal(settings: BitPerfectSettings, perfectBitPerfect: Boolean) = BitPerfectOutputOverride(
            process64Bit = settings.process64Bit,
            followSampleRate = settings.followSampleRate,
            dsdRemaster = settings.dsdRemaster,
            perfectBitPerfect = perfectBitPerfect,
            showMore = settings.showMore,
        )
    }
}

data class OutputProfile(
    val driver: OutputDriver = OutputDriver.AUDIO_TRACK,
    val float32: Boolean = false,
    /** Zero lets the selected driver choose its native default. */
    val bufferSizeFrames: Int = 0,
    val visualDelayMs: Int = 0,
    val noDvc: Boolean = false,
    val noHeadroom: Boolean = false,
    val noEquTone: Boolean = false,
    val noDuck: Boolean = false,
    val bitPerfectOverride: BitPerfectOutputOverride? = null,
    /**
     * Per-output/per-device equalizer preset (reference `eq_preset`), stored as
     * the [EqualizerPreset] name. Null means the global equalizer settings are
     * followed; when set, the active output applies this preset's curve without
     * rewriting the global EQ.
     */
    val eqPreset: String? = null,
    /**
     * Per-device "Ignore Audio Focus" flag (reference `no_af`). Folded into the
     * effective focus policy at runtime: the route then ignores duckable and
     * permanent audio-focus interruptions as configured.
     */
    val noAf: Boolean = false,
) {
    fun normalizedFor(route: AudioOutputRoute): OutputProfile {
        val validDriver = if (driver == OutputDriver.USB_EXCLUSIVE && route != AudioOutputRoute.USB) {
            OutputDriver.AUDIO_TRACK
        } else {
            driver
        }
        val nativeFloat = validDriver == OutputDriver.AAUDIO || validDriver == OutputDriver.OPENSL_ES
        return copy(
            driver = validDriver,
            float32 = float32 || nativeFloat,
            bufferSizeFrames = bufferSizeFrames.coerceIn(0, 1_048_576),
            visualDelayMs = visualDelayMs.coerceIn(-1_000, 1_000),
            eqPreset = normalizeEqPreset(eqPreset),
        )
    }

    companion object {
        val DEFAULTS = OutputProfile()

        /** Keeps only names of real (non-CUSTOM) presets; anything else follows global. */
        fun normalizeEqPreset(name: String?): String? {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return EqualizerPreset.entries
                .firstOrNull { it != EqualizerPreset.CUSTOM && it.name.equals(trimmed, ignoreCase = true) }
                ?.name
        }
    }
}

/** deviceKey is stable across Android's reconnect-dependent numeric AudioDeviceInfo ids. */
data class AudioOutputTarget(
    val route: AudioOutputRoute,
    val deviceKey: String? = null,
    val deviceName: String? = null,
) {
    val isNamedDevice: Boolean get() = !deviceKey.isNullOrBlank()

    val displayName: String
        get() = deviceName?.trim()?.takeIf(String::isNotEmpty) ?: route.label

    fun normalized(): AudioOutputTarget {
        val key = deviceKey?.trim()?.takeIf(String::isNotEmpty)
        return copy(
            deviceKey = key,
            deviceName = if (key == null) null else deviceName?.trim()?.takeIf(String::isNotEmpty) ?: route.label,
        )
    }

    fun renamed(name: String): AudioOutputTarget = copy(
        deviceName = name.trim().ifEmpty { route.label },
    ).normalized()

    companion object {
        fun route(route: AudioOutputRoute) = AudioOutputTarget(route = route)
    }
}

data class StoredAudioOutputProfile(
    val target: AudioOutputTarget,
    val profile: OutputProfile = OutputProfile.DEFAULTS,
)

/** The runtime result after route, device, global and Perfect BP conflicts are resolved. */
data class EffectiveAudiophileOutput(
    val driver: OutputDriver,
    val float32: Boolean,
    val process64Bit: Boolean,
    val followSampleRate: Boolean,
    val dsdRemaster: Boolean,
    val perfectBitPerfect: Boolean,
    val dvcEnabled: Boolean,
    val headroomDb: Float,
    val equalizerEnabled: Boolean,
    val toneEnabled: Boolean,
    val duckEnabled: Boolean,
    val replayGainEnabled: Boolean,
    val crossfadeEnabled: Boolean,
    val resamplerQuality: ResamplerQuality,
    val dither: DitherMode,
    /**
     * Bounded positive compensation gain (dB) applied through the native
     * limiter/DVC path while DVC is disabled and `compensate_dvc_vol` is on.
     * Zero whenever DVC is enabled or the switch is off.
     */
    val dvcCompensateDb: Float = 0f,
    /**
     * Per-output equalizer preset name to apply while this output is active, or
     * null to follow the global equalizer. Never rewrites the global EQ.
     */
    val equalizerPreset: String? = null,
    /** True when this output's `no_af` flag ignores duck and permanent focus loss. */
    val ignoreAudioFocus: Boolean = false,
)

/**
 * Pure conflict boundary shared by UI callbacks and playback integration.
 * Stored choices are never destructively rewritten merely because Perfect BP is active.
 */
object AudiophileConflictPolicy {
    fun resolve(
        global: AudiophileSettings,
        target: AudioOutputTarget,
        profile: OutputProfile,
        globalPerfectBitPerfect: Boolean,
        equalizerRequested: Boolean,
        toneRequested: Boolean,
        replayGainRequested: Boolean,
        crossfadeRequested: Boolean,
    ): EffectiveAudiophileOutput {
        val normalizedGlobal = global.normalized()
        val normalizedTarget = target.normalized()
        val normalizedProfile = profile.normalizedFor(normalizedTarget.route)
        val bitPerfect = normalizedProfile.bitPerfectOverride ?: BitPerfectOutputOverride.fromGlobal(
            normalizedGlobal.bitPerfect,
            globalPerfectBitPerfect,
        )
        val perfect = bitPerfect.perfectBitPerfect
        val bluetoothDvcOff = normalizedTarget.route == AudioOutputRoute.BLUETOOTH && when {
            normalizedGlobal.dvc.bluetoothPolicy == DvcBluetoothPolicy.FORCE_OFF -> true
            normalizedGlobal.dvc.bluetoothPolicy == DvcBluetoothPolicy.FORCE_ON -> false
            else -> !normalizedGlobal.dvc.enabled
        }
        val dvcEnabled = normalizedGlobal.dvc.enabled &&
            !normalizedProfile.noDvc &&
            !bluetoothDvcOff &&
            !perfect
        val noEquTone = normalizedProfile.noEquTone || perfect
        val noDuck = normalizedProfile.noDuck || normalizedGlobal.focus.noDuckOverride || perfect
        return EffectiveAudiophileOutput(
            driver = normalizedProfile.driver,
            float32 = normalizedProfile.float32,
            process64Bit = bitPerfect.process64Bit,
            followSampleRate = bitPerfect.followSampleRate,
            dsdRemaster = bitPerfect.dsdRemaster && !perfect,
            perfectBitPerfect = perfect,
            dvcEnabled = dvcEnabled,
            headroomDb = if (dvcEnabled && !normalizedProfile.noHeadroom && !perfect) {
                normalizedGlobal.dvc.headroomDb
            } else {
                0f
            },
            equalizerEnabled = equalizerRequested && !noEquTone,
            toneEnabled = toneRequested && !noEquTone,
            duckEnabled = normalizedGlobal.focus.notificationDuck && !noDuck,
            replayGainEnabled = replayGainRequested && !perfect,
            crossfadeEnabled = crossfadeRequested && !perfect,
            resamplerQuality = if (perfect && bitPerfect.followSampleRate) {
                ResamplerQuality.OFF
            } else {
                normalizedGlobal.resampler.quality
            },
            dither = if (perfect || normalizedProfile.float32) DitherMode.NONE else normalizedGlobal.resampler.dither,
            dvcCompensateDb = if (!dvcEnabled && normalizedGlobal.dvc.compensateVolume) {
                DvcSettings.COMPENSATE_DB.coerceIn(0f, DvcSettings.COMPENSATE_MAX_DB)
            } else {
                0f
            },
            equalizerPreset = normalizedProfile.eqPreset,
            ignoreAudioFocus = normalizedProfile.noAf,
        )
    }

    /** Rounded once at the persistence/UI boundary, never accumulated from slider deltas. */
    fun milliseconds(value: Float, maximum: Long, quantum: Long): Long =
        (value.toDouble().roundToInt().toLong() / quantum * quantum).coerceIn(0L, maximum)
}
