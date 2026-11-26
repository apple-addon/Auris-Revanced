package com.music.bitchord.ui.reference

enum class AudioPresetTag {
    USER,
    BUILT_IN,
    AUTO_EQ,
    GRAPHIC,
    PARAMETRIC,
    ASSIGNED,
    DEVICE,
}

/** Codec-neutral preset metadata. Import/export and audio application stay outside this package. */
data class AudioPreset(
    val id: String,
    val name: String,
    val tags: Set<AudioPresetTag> = setOf(AudioPresetTag.USER),
    val subtitle: String? = null,
    val deviceName: String? = null,
    /** Optional graphic response samples in dB, drawn as the row's compact preview. */
    val previewGainsDb: List<Float> = emptyList(),
)

data class AudioPresetPreviewGroup(
    val title: String,
    val presets: List<AudioPreset>,
)

enum class ReferencePresetAction {
    SAVE,
    IMPORT,
    SHARE,
    RENAME,
    DUPLICATE,
    DELETE,
    RESET,
}

fun filterAudioPresets(
    presets: List<AudioPreset>,
    query: String,
    filter: PowerampPresetFilter,
): List<AudioPreset> {
    val needle = query.trim()
    return presets.filter { preset ->
        val inFilter = when (filter) {
            PowerampPresetFilter.USER -> AudioPresetTag.USER in preset.tags
            PowerampPresetFilter.BUILT_IN -> AudioPresetTag.BUILT_IN in preset.tags
            PowerampPresetFilter.AUTO_EQ -> AudioPresetTag.AUTO_EQ in preset.tags
            PowerampPresetFilter.GRAPHIC -> AudioPresetTag.GRAPHIC in preset.tags
            PowerampPresetFilter.PARAMETRIC -> AudioPresetTag.PARAMETRIC in preset.tags
            PowerampPresetFilter.ASSIGNED -> AudioPresetTag.ASSIGNED in preset.tags
            PowerampPresetFilter.DEVICE ->
                AudioPresetTag.DEVICE in preset.tags || preset.deviceName?.isNotBlank() == true
            // RESET is an action chip, never a retained selection. Neutral here for pure callers.
            PowerampPresetFilter.RESET -> true
        }
        inFilter && (
            needle.isEmpty() ||
                preset.name.contains(needle, ignoreCase = true) ||
                preset.subtitle?.contains(needle, ignoreCase = true) == true ||
                preset.deviceName?.contains(needle, ignoreCase = true) == true
            )
    }
}

/** Preserves User/Built-in/AutoEq sections even when a type/assignment filter mixes sources. */
fun groupAudioPresets(presets: List<AudioPreset>): List<AudioPresetPreviewGroup> {
    val groups = presets.groupBy { preset ->
        when {
            AudioPresetTag.AUTO_EQ in preset.tags -> "AutoEq"
            AudioPresetTag.BUILT_IN in preset.tags -> "Built-in"
            AudioPresetTag.USER in preset.tags -> "User"
            else -> "Other"
        }
    }
    return listOf("User", "Built-in", "AutoEq", "Other").mapNotNull { title ->
        groups[title]?.takeIf { it.isNotEmpty() }?.let { AudioPresetPreviewGroup(title, it) }
    }
}
