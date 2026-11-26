package com.music.bitchord.ui.screens.audiophile

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Atomic route and named-device output profiles. A named device takes precedence over its route. */
class AudioOutputProfilesStore(
    private val preferences: SharedPreferences,
) : AutoCloseable {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    private val mutableState = MutableStateFlow(readProfiles())
    val state: StateFlow<Map<String, StoredAudioOutputProfile>> = mutableState.asStateFlow()
    val profiles: StateFlow<Map<String, StoredAudioOutputProfile>> get() = state

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in KEYS) reload()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        if (int(KEY_SCHEMA, 0) != SCHEMA_VERSION) writeProfiles(mutableState.value)
    }

    @Synchronized
    fun reload() {
        mutableState.value = readProfiles()
    }

    fun profile(target: AudioOutputTarget): OutputProfile {
        val normalized = target.normalized()
        return mutableState.value[keyFor(normalized)]?.profile ?: OutputProfile.DEFAULTS.normalizedFor(normalized.route)
    }

    fun storedProfile(target: AudioOutputTarget): StoredAudioOutputProfile? =
        mutableState.value[keyFor(target.normalized())]

    /** Returns the device override, then the route override, then stock defaults. */
    fun resolveProfile(route: AudioOutputRoute, deviceKey: String? = null): OutputProfile {
        val key = deviceKey?.trim()?.takeIf(String::isNotEmpty)
        if (key != null) {
            mutableState.value[keyFor(AudioOutputTarget(route, key))]?.let { return it.profile }
        }
        return mutableState.value[keyFor(AudioOutputTarget.route(route))]?.profile
            ?: OutputProfile.DEFAULTS.normalizedFor(route)
    }

    @Synchronized
    fun updateProfile(target: AudioOutputTarget, profile: OutputProfile) {
        val normalizedTarget = target.normalized()
        val next = LinkedHashMap(mutableState.value)
        next[keyFor(normalizedTarget)] = StoredAudioOutputProfile(
            target = normalizedTarget,
            profile = profile.normalizedFor(normalizedTarget.route),
        )
        writeProfiles(next)
    }

    @Synchronized
    fun updateProfile(target: AudioOutputTarget, transform: (OutputProfile) -> OutputProfile) {
        updateProfile(target, transform(profile(target)))
    }

    @Synchronized
    fun renameDevice(target: AudioOutputTarget, name: String) {
        val normalized = target.normalized()
        if (!normalized.isNamedDevice) return
        updateProfile(normalized.renamed(name), profile(normalized))
        val oldKey = keyFor(normalized)
        val newKey = keyFor(normalized.renamed(name))
        if (oldKey != newKey) removeProfile(normalized)
    }

    /** Restores only this route/device, leaving every other profile untouched. */
    @Synchronized
    fun restoreDefaults(target: AudioOutputTarget) {
        removeProfile(target)
    }

    @Synchronized
    fun removeProfile(target: AudioOutputTarget) {
        val key = keyFor(target.normalized())
        if (key !in mutableState.value) return
        val next = LinkedHashMap(mutableState.value)
        next.remove(key)
        writeProfiles(next)
    }

    /** Restores all output profiles without touching global audiophile or app preferences. */
    @Synchronized
    fun restoreDefaults() {
        val editor = preferences.edit()
        KEYS.forEach(editor::remove)
        editor.apply()
        mutableState.value = emptyMap()
    }

    @Synchronized
    private fun writeProfiles(profiles: Map<String, StoredAudioOutputProfile>) {
        val normalized = profiles.mapValues { (_, stored) ->
            val target = stored.target.normalized()
            stored.copy(target = target, profile = stored.profile.normalizedFor(target.route))
        }
        val encoded = normalized.values
            .sortedWith(compareBy({ it.target.route.ordinal }, { it.target.deviceKey.orEmpty() }))
            .joinToString("\n", transform = ::encodeProfile)
        preferences.edit()
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .putString(KEY_PROFILES, encoded)
            .apply()
        mutableState.value = normalized
    }

    private fun readProfiles(): Map<String, StoredAudioOutputProfile> {
        // v3 first, then the untouched v2 and v1 blobs, so an older install's
        // profiles migrate forward by simply being read and re-encoded on the
        // next write.
        val encoded = when {
            preferences.contains(KEY_PROFILES) -> string(KEY_PROFILES)
            preferences.contains(LEGACY_V2_PROFILES) -> string(LEGACY_V2_PROFILES)
            else -> string(LEGACY_PROFILES)
        }.orEmpty()
        if (encoded.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, StoredAudioOutputProfile>()
        encoded.lineSequence().forEach { line ->
            val stored = runCatching { decodeProfile(line) }.getOrNull() ?: return@forEach
            result[keyFor(stored.target)] = stored
        }
        return result
    }

    private fun encodeProfile(stored: StoredAudioOutputProfile): String {
        val target = stored.target.normalized()
        val profile = stored.profile.normalizedFor(target.route)
        val override = profile.bitPerfectOverride
        return listOf(
            "3",
            encode(keyFor(target)),
            target.route.stored,
            encodeNullable(target.deviceKey),
            encodeNullable(target.deviceName),
            profile.driver.stored,
            profile.float32.asBit(),
            profile.bufferSizeFrames.toString(),
            profile.visualDelayMs.toString(),
            profile.noDvc.asBit(),
            profile.noHeadroom.asBit(),
            profile.noEquTone.asBit(),
            profile.noDuck.asBit(),
            (override != null).asBit(),
            override?.process64Bit?.asBit() ?: "1",
            override?.followSampleRate?.asBit() ?: "1",
            override?.dsdRemaster?.asBit() ?: "0",
            override?.perfectBitPerfect?.asBit() ?: "0",
            override?.showMore?.asBit() ?: "0",
            encodeNullable(profile.eqPreset),
            profile.noAf.asBit(),
        ).joinToString("|")
    }

    private fun decodeProfile(line: String): StoredAudioOutputProfile {
        val fields = line.split('|')
        require(fields.isNotEmpty()) { "Empty profile" }
        return when (fields[0]) {
            "3" -> {
                // v3 appends the per-output equalizer preset (index 19) and the
                // per-device no_af flag (index 20); everything before is v2.
                require(fields.size == V3_FIELD_COUNT) { "Incomplete v3 profile" }
                val target = AudioOutputTarget(
                    route = AudioOutputRoute.fromStored(fields[2]),
                    deviceKey = decodeNullable(fields[3]),
                    deviceName = decodeNullable(fields[4]),
                ).normalized()
                val override = if (fields[13].asBoolean()) {
                    BitPerfectOutputOverride(
                        process64Bit = fields[14].asBoolean(),
                        followSampleRate = fields[15].asBoolean(),
                        dsdRemaster = fields[16].asBoolean(),
                        perfectBitPerfect = fields[17].asBoolean(),
                        showMore = fields[18].asBoolean(),
                    )
                } else {
                    null
                }
                StoredAudioOutputProfile(
                    target = target,
                    profile = OutputProfile(
                        driver = OutputDriver.fromStored(fields[5]),
                        float32 = fields[6].asBoolean(),
                        bufferSizeFrames = fields[7].toInt(),
                        visualDelayMs = fields[8].toInt(),
                        noDvc = fields[9].asBoolean(),
                        noHeadroom = fields[10].asBoolean(),
                        noEquTone = fields[11].asBoolean(),
                        noDuck = fields[12].asBoolean(),
                        bitPerfectOverride = override,
                        eqPreset = OutputProfile.normalizeEqPreset(decodeNullable(fields[19])),
                        noAf = fields[20].asBoolean(),
                    ).normalizedFor(target.route),
                )
            }
            "2" -> {
                // v2 is read unchanged: no per-output preset, no no_af.
                require(fields.size == V2_FIELD_COUNT) { "Incomplete v2 profile" }
                val target = AudioOutputTarget(
                    route = AudioOutputRoute.fromStored(fields[2]),
                    deviceKey = decodeNullable(fields[3]),
                    deviceName = decodeNullable(fields[4]),
                ).normalized()
                val override = if (fields[13].asBoolean()) {
                    BitPerfectOutputOverride(
                        process64Bit = fields[14].asBoolean(),
                        followSampleRate = fields[15].asBoolean(),
                        dsdRemaster = fields[16].asBoolean(),
                        perfectBitPerfect = fields[17].asBoolean(),
                        showMore = fields[18].asBoolean(),
                    )
                } else {
                    null
                }
                StoredAudioOutputProfile(
                    target = target,
                    profile = OutputProfile(
                        driver = OutputDriver.fromStored(fields[5]),
                        float32 = fields[6].asBoolean(),
                        bufferSizeFrames = fields[7].toInt(),
                        visualDelayMs = fields[8].toInt(),
                        noDvc = fields[9].asBoolean(),
                        noHeadroom = fields[10].asBoolean(),
                        noEquTone = fields[11].asBoolean(),
                        noDuck = fields[12].asBoolean(),
                        bitPerfectOverride = override,
                    ).normalizedFor(target.route),
                )
            }
            "1" -> {
                // Version 1 had the same target/driver fields and four bypass flags,
                // but no per-output Bit Perfect override.
                require(fields.size >= LEGACY_FIELD_COUNT) { "Incomplete v1 profile" }
                val target = AudioOutputTarget(
                    route = AudioOutputRoute.fromStored(fields[2]),
                    deviceKey = decodeNullable(fields[3]),
                    deviceName = decodeNullable(fields[4]),
                ).normalized()
                StoredAudioOutputProfile(
                    target = target,
                    profile = OutputProfile(
                        driver = OutputDriver.fromStored(fields[5]),
                        float32 = fields[6].asBoolean(),
                        bufferSizeFrames = fields.getOrElse(7) { "0" }.toInt(),
                        visualDelayMs = fields.getOrElse(8) { "0" }.toInt(),
                        noDvc = fields.getOrElse(9) { "0" }.asBoolean(),
                        noHeadroom = fields.getOrElse(10) { "0" }.asBoolean(),
                        noEquTone = fields.getOrElse(11) { "0" }.asBoolean(),
                        noDuck = fields.getOrElse(12) { "0" }.asBoolean(),
                    ).normalizedFor(target.route),
                )
            }
            else -> error("Unknown profile version ${fields[0]}")
        }
    }

    private fun keyFor(target: AudioOutputTarget): String {
        val normalized = target.normalized()
        return normalized.deviceKey?.let { "device:${normalized.route.stored}:$it" }
            ?: "route:${normalized.route.stored}"
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun encodeNullable(value: String?): String = value?.let(::encode) ?: "~"

    private fun decodeNullable(value: String): String? = if (value == "~") {
        null
    } else {
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }

    private fun Boolean.asBit(): String = if (this) "1" else "0"

    private fun String.asBoolean(): Boolean = when (this) {
        "1", "true" -> true
        "0", "false" -> false
        else -> error("Invalid boolean $this")
    }

    private fun int(key: String, default: Int): Int =
        runCatching { preferences.getInt(key, default) }.getOrDefault(default)

    private fun string(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val FILE_NAME = "bitchord_audiophile_output_profiles"
        private const val SCHEMA_VERSION = 3
        private const val V3_FIELD_COUNT = 21
        private const val V2_FIELD_COUNT = 19
        private const val LEGACY_FIELD_COUNT = 13
        private const val KEY_SCHEMA = "schema_version"
        private const val KEY_PROFILES = "profiles_v3"
        private const val LEGACY_V2_PROFILES = "profiles_v2"
        private const val LEGACY_PROFILES = "profiles_v1"
        private val KEYS = setOf(KEY_SCHEMA, KEY_PROFILES, LEGACY_V2_PROFILES, LEGACY_PROFILES)
    }
}
