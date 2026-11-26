package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun OutputDeviceProfileSettingsPage(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { OutputDeviceStatusSection(routeId, profile, callbacks) }
        item { OutputDeviceFormatSection(routeId, profile, callbacks) }
        item { OutputDeviceBufferSection(routeId, profile, callbacks) }
        item { OutputDeviceProcessingSection(routeId, profile, callbacks) }
        item { OutputDeviceEqualizerPresetSection(routeId, profile, callbacks) }
        item { OutputDeviceBitPerfectSection(routeId, profile, callbacks) }
        item {
            AudiophileRestoreButton(
                onClick = { callbacks.onRestoreProfileDefaults(routeId, profile.id) },
                label = "Restore ${profile.name} defaults",
            )
        }
    }
}

@Composable
private fun OutputDeviceStatusSection(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = profile.name) {
        AudiophileSwitchRow(
            label = "Use this output device",
            subtitle = profile.kind.label,
            checked = profile.enabled,
            onCheckedChange = { callbacks.onProfileEnabledChange(routeId, profile.id, it) },
        )
        AudiophileBadges(outputProfileBadges(profile))
        if (profile.statusDetail.isNotBlank()) {
            AudiophileValueRow(label = "Status detail", value = profile.statusDetail)
        }
        AudiophileValueRow(
            label = "Selected",
            value = "${formatSampleRate(profile.sampleRateHz)} · ${profile.sampleFormat.label}",
        )
        profile.actualSampleRateHz?.let { actualRate ->
            AudiophileValueRow(label = "Actual sample rate", value = formatSampleRate(actualRate))
        }
        profile.actualSampleFormat?.let { actualFormat ->
            AudiophileValueRow(label = "Actual format", value = actualFormat.label)
        }
    }
}

@Composable
private fun OutputDeviceFormatSection(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = "Sample rate & format") {
        AudiophileChoiceChips(
            label = "Sample Rate",
            options = profile.supportedSampleRatesHz,
            selected = profile.sampleRateHz,
            onSelect = { callbacks.onSampleRateHzChange(routeId, profile.id, it) },
            optionLabel = ::formatSampleRate,
            enabled = profile.supportedSampleRatesHz.isNotEmpty(),
        )
        AudiophileChoiceChips(
            label = "Sample Format",
            options = OutputSampleFormat.entries,
            selected = profile.sampleFormat,
            onSelect = { callbacks.onSampleFormatChange(routeId, profile.id, it) },
            optionLabel = OutputSampleFormat::label,
        )
    }
}

@Composable
private fun OutputDeviceBufferSection(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
) {
    val custom = profile.bufferPreset == OutputBufferPreset.CUSTOM
    AudiophileSection(title = "Buffer & delay") {
        AudiophileChoiceChips(
            label = "Buffer Size",
            options = OutputBufferPreset.entries,
            selected = profile.bufferPreset,
            onSelect = { callbacks.onBufferPresetChange(routeId, profile.id, it) },
            optionLabel = OutputBufferPreset::label,
        )
        AudiophileIntSliderRow(
            label = "Buffer",
            value = profile.bufferMs,
            range = 10..2_000,
            step = 10,
            steps = 0,
            valueText = formatMilliseconds(profile.bufferMs),
            enabled = custom,
            onValueChange = { callbacks.onBufferMsChange(routeId, profile.id, it) },
        )
        AudiophileIntSliderRow(
            label = "Buffers",
            value = profile.bufferCount,
            range = 1..16,
            valueText = "${profile.bufferCount}",
            enabled = custom,
            onValueChange = { callbacks.onBufferCountChange(routeId, profile.id, it) },
        )
        AudiophileIntSliderRow(
            label = "Post-fade buffers",
            value = profile.postFadeBufferCount,
            range = 1..16,
            valueText = "${profile.postFadeBufferCount}",
            enabled = custom,
            onValueChange = { callbacks.onPostFadeBufferCountChange(routeId, profile.id, it) },
        )
        AudiophileIntSliderRow(
            label = "Visualization / Lyrics Delay",
            value = profile.visualizationLyricsDelayMs,
            range = -5_000..5_000,
            step = 10,
            steps = 0,
            valueText = formatMilliseconds(profile.visualizationLyricsDelayMs),
            subtitle = "Negative values display earlier; positive values display later.",
            onValueChange = { callbacks.onVisualizationLyricsDelayMsChange(routeId, profile.id, it) },
        )
    }
}

@Composable
private fun OutputDeviceProcessingSection(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = "Per-output processing") {
        AudiophileSwitchRow(
            label = "No DVC",
            subtitle = "Disable Direct Volume Control for this device only.",
            checked = profile.noDvc,
            onCheckedChange = { callbacks.onNoDvcChange(routeId, profile.id, it) },
        )
        AudiophileSwitchRow(
            label = "No Headroom Gain",
            subtitle = "Disable the non-DVC equalizer headroom reduction for this device only.",
            checked = profile.noHeadroomGain,
            onCheckedChange = { callbacks.onNoHeadroomGainChange(routeId, profile.id, it) },
        )
        AudiophileSwitchRow(
            label = "No Equ / Tone",
            subtitle = "Bypass equalizer and tone processing for this device only.",
            checked = profile.noEquTone,
            onCheckedChange = { callbacks.onNoEquToneChange(routeId, profile.id, it) },
        )
        AudiophileSwitchRow(
            label = "No Duck",
            subtitle = "Do not lower volume for short audio-focus changes on this device.",
            checked = profile.noDuck,
            onCheckedChange = { callbacks.onNoDuckChange(routeId, profile.id, it) },
        )
    }
}

@Composable
private fun OutputDeviceEqualizerPresetSection(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = "Equalizer Preset assignment") {
        val assigned = profile.availableEqualizerPresets.firstOrNull {
            it.id == profile.equalizerPresetId
        }
        val customAssigned = if (profile.equalizerPresetId != null && assigned == null) {
            EqualizerPresetOption(profile.equalizerPresetId, profile.equalizerPresetId)
        } else {
            null
        }
        AudiophileValueRow(
            label = "Assigned preset",
            value = (assigned ?: customAssigned)?.name ?: "No preset assigned",
        )
        if (profile.availableEqualizerPresets.isNotEmpty()) {
            AudiophileChoiceChips<EqualizerPresetOption?>(
                label = "Preset for this output",
                options = listOf(null) + profile.availableEqualizerPresets + listOfNotNull(customAssigned),
                selected = assigned ?: customAssigned,
                onSelect = { preset ->
                    callbacks.onEqualizerPresetIdChange(routeId, profile.id, preset?.id)
                },
                optionLabel = { it?.name ?: "No preset" },
            )
        } else {
            OutlinedTextField(
                value = profile.equalizerPresetId.orEmpty(),
                onValueChange = { value ->
                    callbacks.onEqualizerPresetIdChange(routeId, profile.id, value.ifBlank { null })
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Equalizer preset ID") },
                supportingText = { Text("Leave blank to use the current global preset.") },
                singleLine = true,
            )
        }
        if (profile.equalizerPresetId != null) {
            TextButton(onClick = {
                callbacks.onEqualizerPresetIdChange(routeId, profile.id, null)
            }) {
                Text("Clear Equalizer Preset assignment")
            }
        }
    }
}

@Composable
private fun OutputDeviceBitPerfectSection(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = "Bit Perfect for this device") {
        BitPerfectControls(
            settings = profile.bitPerfect,
            onPipeline64EnabledChange = {
                callbacks.onPipeline64EnabledChange(routeId, profile.id, it)
            },
            onFollowTrackSampleRateChange = {
                callbacks.onFollowTrackSampleRateChange(routeId, profile.id, it)
            },
            onDsdRemasteringEnabledChange = {
                callbacks.onDsdRemasteringEnabledChange(routeId, profile.id, it)
            },
            onDsdRateChange = { callbacks.onDsdRateChange(routeId, profile.id, it) },
            onDsdRemaster48kFamilyEnabledChange = {
                callbacks.onDsdRemaster48kFamilyEnabledChange(routeId, profile.id, it)
            },
            onDsdRemasterGainDbChange = {
                callbacks.onDsdRemasterGainDbChange(routeId, profile.id, it)
            },
            onPerfectBitPerfectEnabledChange = {
                callbacks.onPerfectBitPerfectEnabledChange(routeId, profile.id, it)
            },
        )
    }
}
