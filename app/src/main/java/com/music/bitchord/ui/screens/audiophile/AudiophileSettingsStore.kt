package com.music.bitchord.ui.screens.audiophile

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.playback.fades.AudioFocusSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Process-wide audiophile preferences, mirrored immediately as one coherent flow.
 * Every setter writes the complete snapshot in one SharedPreferences transaction.
 */
class AudiophileSettingsStore(
    private val preferences: SharedPreferences,
) : AutoCloseable {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    private val mutableState = MutableStateFlow(readSnapshot())
    val state: StateFlow<AudiophileSettings> = mutableState.asStateFlow()
    val settings: StateFlow<AudiophileSettings> get() = state

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in ALL_KEYS) reload()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        if (int(KEY_SCHEMA, 0) != SCHEMA_VERSION) {
            writeSnapshot(mutableState.value)
        }
    }

    @Synchronized
    fun reload() {
        mutableState.value = readSnapshot()
    }

    @Synchronized
    fun update(transform: (AudiophileSettings) -> AudiophileSettings) {
        writeSnapshot(transform(mutableState.value).normalized())
    }

    fun updateFocus(value: AudioFocusSettings) = update { it.copy(focus = value) }
    fun updateResampler(value: ResamplerSettings) = update { it.copy(resampler = value) }
    fun updateDvc(value: DvcSettings) = update { it.copy(dvc = value) }
    fun updateBitPerfect(value: BitPerfectSettings) = update { it.copy(bitPerfect = value) }
    fun updateAdvanced(value: AdvancedTweaks) = update { it.copy(advanced = value) }

    fun restoreFocusDefaults() = updateFocus(AudioFocusSettings())
    fun restoreResamplerDefaults() = updateResampler(ResamplerSettings.DEFAULTS)
    fun restoreDvcDefaults() = updateDvc(DvcSettings.DEFAULTS)
    fun restoreBitPerfectDefaults() = updateBitPerfect(BitPerfectSettings.DEFAULTS)
    fun restoreAdvancedDefaults() = updateAdvanced(AdvancedTweaks.DEFAULTS)

    /** Removes this store's current and legacy keys; unrelated preferences survive. */
    @Synchronized
    fun restoreDefaults() {
        val editor = preferences.edit()
        ALL_KEYS.forEach(editor::remove)
        editor.apply()
        mutableState.value = AudiophileSettings.DEFAULTS
    }

    @Synchronized
    private fun writeSnapshot(value: AudiophileSettings) {
        val normalized = value.normalized()
        preferences.edit()
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .putBoolean(KEY_PAUSE_IN_CALL, normalized.focus.pauseInCall)
            .putBoolean(KEY_NOTIFICATION_DUCK, normalized.focus.notificationDuck)
            .putBoolean(KEY_RESUME_ON_START, normalized.focus.resumeOnStart)
            .putBoolean(KEY_RESUME_ON_REOPEN, normalized.focus.resumeOnReopen)
            .putBoolean(KEY_WAIT_FOR_STORAGE, normalized.focus.waitForStorage)
            .putBoolean(KEY_NO_DUCK, normalized.focus.noDuckOverride)
            .putBoolean(KEY_IGNORE_AUDIO_FOCUS, normalized.focus.ignoreAudioFocus)
            .putString(KEY_RESAMPLER_QUALITY, normalized.resampler.quality.stored)
            .putInt(KEY_RESAMPLER_CUTOFF, normalized.resampler.cutoffPercent)
            .putString(KEY_DITHER, normalized.resampler.dither.stored)
            .putBoolean(KEY_DVC_ENABLED, normalized.dvc.enabled)
            .putFloat(KEY_DVC_HEADROOM, normalized.dvc.headroomDb)
            .putString(KEY_DVC_BLUETOOTH, normalized.dvc.bluetoothPolicy.stored)
            .putBoolean(KEY_DVC_COMPENSATE, normalized.dvc.compensateVolume)
            .putBoolean(KEY_PROCESS_64_BIT, normalized.bitPerfect.process64Bit)
            .putBoolean(KEY_FOLLOW_SAMPLE_RATE, normalized.bitPerfect.followSampleRate)
            .putBoolean(KEY_DSD_REMASTER, normalized.bitPerfect.dsdRemaster)
            .putBoolean(KEY_BP_SHOW_MORE, normalized.bitPerfect.showMore)
            .putBoolean(KEY_DIRECT_AVAILABLE, normalized.advanced.directWhenAvailable)
            .putBoolean(KEY_ALLOW_OFFLOAD, normalized.advanced.allowOffload)
            .putBoolean(KEY_USB_HARDWARE_VOLUME, normalized.advanced.usbHardwareVolume)
            .putBoolean(KEY_RESET_EQ_ROUTE, normalized.advanced.resetEqualizerOnRouteChange)
            .putBoolean(KEY_AUDIO_DIAGNOSTICS, normalized.advanced.audioDiagnostics)
            .apply()
        mutableState.value = normalized
    }

    private fun readSnapshot(): AudiophileSettings {
        val legacy = int(KEY_SCHEMA, 0) < SCHEMA_VERSION
        val cutoff = when {
            preferences.contains(KEY_RESAMPLER_CUTOFF) -> int(KEY_RESAMPLER_CUTOFF, ResamplerSettings.DEFAULTS.cutoffPercent)
            preferences.contains(LEGACY_RESAMPLER_CUTOFF) -> {
                val old = float(LEGACY_RESAMPLER_CUTOFF, ResamplerSettings.DEFAULTS.cutoffPercent.toFloat())
                if (old in 0.5f..1f) (old * 100f).roundToInt() else old.roundToInt()
            }
            else -> ResamplerSettings.DEFAULTS.cutoffPercent
        }
        val bluetoothPolicy = when {
            preferences.contains(KEY_DVC_BLUETOOTH) -> DvcBluetoothPolicy.fromStored(string(KEY_DVC_BLUETOOTH))
            boolean(LEGACY_NO_BT_DVC, false) -> DvcBluetoothPolicy.FORCE_OFF
            else -> DvcSettings.DEFAULTS.bluetoothPolicy
        }
        return AudiophileSettings(
            focus = AudioFocusSettings(
                pauseInCall = booleanWithLegacy(KEY_PAUSE_IN_CALL, LEGACY_PAUSE_IN_CALL, true),
                notificationDuck = booleanWithLegacy(KEY_NOTIFICATION_DUCK, LEGACY_NOTIFICATION_DUCK, true),
                resumeOnStart = booleanWithLegacy(KEY_RESUME_ON_START, LEGACY_RESUME_ON_START, false),
                resumeOnReopen = booleanWithLegacy(KEY_RESUME_ON_REOPEN, LEGACY_RESUME_ON_REOPEN, false),
                waitForStorage = booleanWithLegacy(KEY_WAIT_FOR_STORAGE, LEGACY_WAIT_FOR_STORAGE, true),
                noDuckOverride = booleanWithLegacy(KEY_NO_DUCK, LEGACY_NO_DUCK, false),
                ignoreAudioFocus = booleanWithLegacy(KEY_IGNORE_AUDIO_FOCUS, LEGACY_IGNORE_AUDIO_FOCUS, false),
            ),
            resampler = ResamplerSettings(
                quality = ResamplerQuality.fromStored(
                    if (preferences.contains(KEY_RESAMPLER_QUALITY)) {
                        string(KEY_RESAMPLER_QUALITY)
                    } else {
                        string(LEGACY_RESAMPLER_QUALITY)
                    },
                ),
                cutoffPercent = cutoff,
                dither = DitherMode.fromStored(
                    if (preferences.contains(KEY_DITHER)) string(KEY_DITHER) else string(LEGACY_DITHER),
                ),
            ),
            dvc = DvcSettings(
                enabled = booleanWithLegacy(KEY_DVC_ENABLED, LEGACY_DVC_ENABLED, true),
                headroomDb = when {
                    preferences.contains(KEY_DVC_HEADROOM) -> float(KEY_DVC_HEADROOM, DvcSettings.DEFAULTS.headroomDb)
                    legacy -> float(LEGACY_DVC_HEADROOM, DvcSettings.DEFAULTS.headroomDb)
                    else -> DvcSettings.DEFAULTS.headroomDb
                },
                bluetoothPolicy = bluetoothPolicy,
                compensateVolume = booleanWithLegacy(KEY_DVC_COMPENSATE, LEGACY_COMPENSATE_DVC_VOL, false),
            ),
            bitPerfect = BitPerfectSettings(
                process64Bit = booleanWithLegacy(KEY_PROCESS_64_BIT, LEGACY_PROCESS_64_BIT, true),
                followSampleRate = booleanWithLegacy(KEY_FOLLOW_SAMPLE_RATE, LEGACY_FOLLOW_SAMPLE_RATE, true),
                dsdRemaster = booleanWithLegacy(KEY_DSD_REMASTER, LEGACY_DSD_REMASTER, false),
                showMore = booleanWithLegacy(KEY_BP_SHOW_MORE, LEGACY_BP_SHOW_MORE, false),
            ),
            advanced = AdvancedTweaks(
                directWhenAvailable = boolean(KEY_DIRECT_AVAILABLE, true),
                allowOffload = boolean(KEY_ALLOW_OFFLOAD, false),
                usbHardwareVolume = boolean(KEY_USB_HARDWARE_VOLUME, true),
                resetEqualizerOnRouteChange = boolean(KEY_RESET_EQ_ROUTE, false),
                audioDiagnostics = boolean(KEY_AUDIO_DIAGNOSTICS, false),
            ),
        ).normalized()
    }

    private fun booleanWithLegacy(key: String, legacyKey: String, default: Boolean): Boolean =
        if (preferences.contains(key)) boolean(key, default) else boolean(legacyKey, default)

    private fun boolean(key: String, default: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, default) }.getOrDefault(default)

    private fun int(key: String, default: Int): Int =
        runCatching { preferences.getInt(key, default) }.getOrDefault(default)

    private fun float(key: String, default: Float): Float =
        runCatching { preferences.getFloat(key, default) }.getOrDefault(default)

    private fun string(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val FILE_NAME = "bitchord_audiophile_settings"
        private const val SCHEMA_VERSION = 3

        private const val KEY_SCHEMA = "schema_version"
        private const val KEY_PAUSE_IN_CALL = "focus.pause_in_call"
        private const val KEY_NOTIFICATION_DUCK = "focus.notification_duck"
        private const val KEY_RESUME_ON_START = "focus.resume_on_start"
        private const val KEY_RESUME_ON_REOPEN = "focus.resume_on_reopen"
        private const val KEY_WAIT_FOR_STORAGE = "focus.wait_for_storage"
        private const val KEY_NO_DUCK = "focus.no_duck"
        private const val KEY_IGNORE_AUDIO_FOCUS = "focus.ignore_audio_focus"
        private const val KEY_RESAMPLER_QUALITY = "resampler.quality"
        private const val KEY_RESAMPLER_CUTOFF = "resampler.cutoff_percent"
        private const val KEY_DITHER = "resampler.dither"
        private const val KEY_DVC_ENABLED = "dvc.enabled"
        private const val KEY_DVC_HEADROOM = "dvc.headroom_db"
        private const val KEY_DVC_BLUETOOTH = "dvc.bluetooth_policy"
        private const val KEY_DVC_COMPENSATE = "dvc.compensate_volume"
        private const val KEY_PROCESS_64_BIT = "bit_perfect.process_64_bit"
        private const val KEY_FOLLOW_SAMPLE_RATE = "bit_perfect.follow_sample_rate"
        private const val KEY_DSD_REMASTER = "bit_perfect.dsd_remaster"
        private const val KEY_BP_SHOW_MORE = "bit_perfect.show_more"
        private const val KEY_DIRECT_AVAILABLE = "advanced.direct_when_available"
        private const val KEY_ALLOW_OFFLOAD = "advanced.allow_offload"
        private const val KEY_USB_HARDWARE_VOLUME = "advanced.usb_hardware_volume"
        private const val KEY_RESET_EQ_ROUTE = "advanced.reset_equalizer_on_route_change"
        private const val KEY_AUDIO_DIAGNOSTICS = "advanced.audio_diagnostics"

        private const val LEGACY_PAUSE_IN_CALL = "pause_in_call"
        private const val LEGACY_NOTIFICATION_DUCK = "notification_duck"
        private const val LEGACY_RESUME_ON_START = "resume_on_start"
        private const val LEGACY_RESUME_ON_REOPEN = "resume_on_reopen"
        private const val LEGACY_WAIT_FOR_STORAGE = "wait_for_storage"
        private const val LEGACY_NO_DUCK = "no_duck"
        private const val LEGACY_IGNORE_AUDIO_FOCUS = "no_af"
        private const val LEGACY_RESAMPLER_QUALITY = "resampler_quality"
        private const val LEGACY_RESAMPLER_CUTOFF = "resampler_cutoff"
        private const val LEGACY_DITHER = "dither"
        private const val LEGACY_DVC_ENABLED = "dvc_enabled"
        private const val LEGACY_DVC_HEADROOM = "dvc_headroom_db"
        private const val LEGACY_NO_BT_DVC = "dvc_no_bluetooth"
        private const val LEGACY_COMPENSATE_DVC_VOL = "compensate_dvc_vol"
        private const val LEGACY_PROCESS_64_BIT = "64_bit_processing"
        private const val LEGACY_FOLLOW_SAMPLE_RATE = "follow_sample_rate"
        private const val LEGACY_DSD_REMASTER = "dsd_remaster"
        private const val LEGACY_BP_SHOW_MORE = "show_more"

        private val CURRENT_KEYS = setOf(
            KEY_SCHEMA,
            KEY_PAUSE_IN_CALL,
            KEY_NOTIFICATION_DUCK,
            KEY_RESUME_ON_START,
            KEY_RESUME_ON_REOPEN,
            KEY_WAIT_FOR_STORAGE,
            KEY_NO_DUCK,
            KEY_IGNORE_AUDIO_FOCUS,
            KEY_RESAMPLER_QUALITY,
            KEY_RESAMPLER_CUTOFF,
            KEY_DITHER,
            KEY_DVC_ENABLED,
            KEY_DVC_HEADROOM,
            KEY_DVC_BLUETOOTH,
            KEY_DVC_COMPENSATE,
            KEY_PROCESS_64_BIT,
            KEY_FOLLOW_SAMPLE_RATE,
            KEY_DSD_REMASTER,
            KEY_BP_SHOW_MORE,
            KEY_DIRECT_AVAILABLE,
            KEY_ALLOW_OFFLOAD,
            KEY_USB_HARDWARE_VOLUME,
            KEY_RESET_EQ_ROUTE,
            KEY_AUDIO_DIAGNOSTICS,
        )

        private val LEGACY_KEYS = setOf(
            LEGACY_PAUSE_IN_CALL,
            LEGACY_NOTIFICATION_DUCK,
            LEGACY_RESUME_ON_START,
            LEGACY_RESUME_ON_REOPEN,
            LEGACY_WAIT_FOR_STORAGE,
            LEGACY_NO_DUCK,
            LEGACY_IGNORE_AUDIO_FOCUS,
            LEGACY_RESAMPLER_QUALITY,
            LEGACY_RESAMPLER_CUTOFF,
            LEGACY_DITHER,
            LEGACY_DVC_ENABLED,
            LEGACY_DVC_HEADROOM,
            LEGACY_NO_BT_DVC,
            LEGACY_COMPENSATE_DVC_VOL,
            LEGACY_PROCESS_64_BIT,
            LEGACY_FOLLOW_SAMPLE_RATE,
            LEGACY_DSD_REMASTER,
            LEGACY_BP_SHOW_MORE,
        )

        private val ALL_KEYS = CURRENT_KEYS + LEGACY_KEYS
    }
}
