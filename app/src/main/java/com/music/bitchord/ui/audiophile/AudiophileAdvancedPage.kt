package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AdvancedTweaksSettingsPage(
    settings: AdvancedTweaksState,
    callbacks: AdvancedTweaksCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { ReferenceModeSection(settings, callbacks) }
        item { UsbAdvancedSection(settings, callbacks) }
        item { SystemWorkaroundsSection(settings, callbacks) }
        item { PresetResetSection(callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun ReferenceModeSection(settings: AdvancedTweaksState, callbacks: AdvancedTweaksCallbacks) {
    AudiophileSection(title = "Reference Mode") {
        AudiophileSwitchRow(
            label = "Reference Mode",
            subtitle = "Use the neutral reference processing path without changing saved EQ or output profiles.",
            checked = settings.referenceModeEnabled,
            onCheckedChange = callbacks.onReferenceModeEnabledChange,
        )
        AudiophileSelectionGroup(
            label = "Reference engine",
            options = ReferenceMode.entries,
            selected = settings.referenceMode,
            onSelect = callbacks.onReferenceModeChange,
            optionLabel = ReferenceMode::label,
            enabled = settings.referenceModeEnabled,
        )
    }
}

@Composable
private fun UsbAdvancedSection(settings: AdvancedTweaksState, callbacks: AdvancedTweaksCallbacks) {
    AudiophileSection(title = "USB audio") {
        AudiophileSwitchRow(
            label = "USB Volume Lock",
            subtitle = "Keep the USB DAC hardware volume fixed while the player controls digital volume.",
            checked = settings.usbVolumeLockEnabled,
            onCheckedChange = callbacks.onUsbVolumeLockEnabledChange,
        )
        AudiophileSwitchRow(
            label = "USB Gain Compensation",
            subtitle = "Compensate for a fixed DAC or amplifier gain without altering per-output headroom.",
            checked = settings.usbGainCompensationEnabled,
            onCheckedChange = callbacks.onUsbGainCompensationEnabledChange,
        )
        AudiophileFloatSliderRow(
            label = "Gain compensation",
            value = settings.usbGainCompensationDb,
            range = -12f..12f,
            steps = 239,
            valueText = formatDb(settings.usbGainCompensationDb),
            enabled = settings.usbGainCompensationEnabled,
            onValueChange = callbacks.onUsbGainCompensationDbChange,
        )
    }
}

@Composable
private fun SystemWorkaroundsSection(
    settings: AdvancedTweaksState,
    callbacks: AdvancedTweaksCallbacks,
) {
    AudiophileSection(title = "System workarounds") {
        AudiophileSelectionGroup(
            label = "Volume Levels",
            options = listOf(0, 16, 30, 50, 100, 150, 200, 256),
            selected = settings.volumeLevels,
            onSelect = callbacks.onVolumeLevelsChange,
            optionLabel = { value -> if (value == 0) "Default" else "$value steps" },
        )
        AudiophileIntSliderRow(
            label = "Volume steps",
            value = settings.volumeSteps,
            range = 0..256,
            valueText = if (settings.volumeSteps == 0) "System default" else "${settings.volumeSteps} steps",
            subtitle = "Independent custom hardware-volume granularity; 0 uses the system default.",
            onValueChange = callbacks.onVolumeStepsChange,
        )
        AudiophileSwitchRow(
            label = "MusicFX",
            subtitle = "Allow the Android system MusicFX panel and effects integration.",
            checked = settings.musicFxEnabled,
            onCheckedChange = callbacks.onMusicFxEnabledChange,
        )
        AudiophileSelectionGroup(
            label = "Audio thread priority",
            options = (-2..3).toList(),
            selected = settings.audioThreadPriority,
            onSelect = callbacks.onAudioThreadPriorityChange,
            optionLabel = ::threadPriorityLabel,
        )
        AudiophileSwitchRow(
            label = "Force Speaker Off (for Hi-Res Output)",
            checked = settings.forceSpeakerOff,
            onCheckedChange = callbacks.onForceSpeakerOffChange,
        )
        AudiophileSwitchRow(
            label = "Force Audio On Focus Change",
            subtitle = "Keep audio running on devices which incorrectly route focus changes.",
            checked = settings.forceAudioOnFocusChange,
            onCheckedChange = callbacks.onForceAudioOnFocusChange,
        )
        AudiophileSwitchRow(
            label = "Emulated Media Stream",
            subtitle = "Use the compatibility media stream for devices with a broken music stream.",
            checked = settings.emulatedMediaStream,
            onCheckedChange = callbacks.onEmulatedMediaStreamChange,
        )
    }
}

@Composable
private fun PresetResetSection(callbacks: AdvancedTweaksCallbacks) {
    AudiophileSection(title = "Preset maintenance") {
        AudiophileRestoreButton(
            onClick = callbacks.onResetEqualizerPresets,
            label = "Reset Equalizer Presets",
        )
        AudiophileRestoreButton(
            onClick = callbacks.onResetReverbPresets,
            label = "Reset Reverb Presets",
        )
    }
}

private fun threadPriorityLabel(value: Int): String = when (value) {
    -2 -> "Lowest"
    -1 -> "Low"
    0 -> "Normal"
    1 -> "High"
    2 -> "Higher"
    else -> "Highest"
}
