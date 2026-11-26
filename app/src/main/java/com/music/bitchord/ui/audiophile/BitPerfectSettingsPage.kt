package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun BitPerfectSettingsPage(
    settings: BitPerfectSettingsState,
    info: AudioInfoState,
    showMore: Boolean,
    callbacks: BitPerfectSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { BitPerfectStatusSection(settings, info, showMore, callbacks.onShowMoreChange) }
        item {
            AudiophileSection(title = "Bit Perfect Modes") {
                BitPerfectControls(
                    settings = settings,
                    onPipeline64EnabledChange = callbacks.onPipeline64EnabledChange,
                    onFollowTrackSampleRateChange = callbacks.onFollowTrackSampleRateChange,
                    onDsdRemasteringEnabledChange = callbacks.onDsdRemasteringEnabledChange,
                    onDsdRateChange = callbacks.onDsdRateChange,
                    onDsdRemaster48kFamilyEnabledChange = callbacks.onDsdRemaster48kFamilyEnabledChange,
                    onDsdRemasterGainDbChange = callbacks.onDsdRemasterGainDbChange,
                    onPerfectBitPerfectEnabledChange = callbacks.onPerfectBitPerfectEnabledChange,
                )
            }
        }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun BitPerfectStatusSection(
    settings: BitPerfectSettingsState,
    info: AudioInfoState,
    showMore: Boolean,
    onShowMoreChange: (Boolean) -> Unit,
) {
    val badges = bitPerfectBadges(settings, info)
    AudiophileSection(title = "Current signal") {
        AudiophileBadges(badges)
        if (info.trackTitle != "No active track") {
            AudiophileValueRow(label = "Track", value = info.trackTitle)
        }
        TextButton(
            onClick = { onShowMoreChange(!showMore) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (showMore) "Show less" else "Show more")
            Icon(
                imageVector = if (showMore) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
            )
        }
        if (showMore) {
            BitPerfectTechnicalDetails(settings, info)
        }
    }
}

@Composable
private fun BitPerfectTechnicalDetails(settings: BitPerfectSettingsState, info: AudioInfoState) {
    AudiophileValueRow(
        label = "Processing pipeline",
        value = if (settings.pipeline64Enabled) "64-bit floating point" else "Standard floating point",
    )
    AudiophileValueRow(
        label = "Output sample rate",
        value = if (settings.followTrackSampleRate) "Follows each track" else "Output profile selection",
    )
    AudiophileValueRow(
        label = "DSD remastering",
        value = if (settings.dsdRemasteringEnabled) {
            "${settings.dsdRate.label} · ${if (settings.dsdRemaster48kFamilyEnabled) "48k family" else "44.1k family"} · ${formatDb(settings.dsdRemasterGainDb)}"
        } else {
            "Disabled"
        },
    )
    AudiophileValueRow(
        label = "DSP, gains, and crossfade",
        value = if (settings.perfectBitPerfectEnabled) "Temporarily bypassed" else "Available",
    )
    info.output.forEach { entry -> AudiophileValueRow(label = entry.label, value = entry.value) }
}

@Composable
internal fun BitPerfectControls(
    settings: BitPerfectSettingsState,
    onPipeline64EnabledChange: (Boolean) -> Unit,
    onFollowTrackSampleRateChange: (Boolean) -> Unit,
    onDsdRemasteringEnabledChange: (Boolean) -> Unit,
    onDsdRateChange: (DsdRate) -> Unit,
    onDsdRemaster48kFamilyEnabledChange: (Boolean) -> Unit,
    onDsdRemasterGainDbChange: (Float) -> Unit,
    onPerfectBitPerfectEnabledChange: (Boolean) -> Unit,
) {
    AudiophileSwitchRow(
        label = "64-bit Pipeline",
        subtitle = "Extend the internal processing format to Float64 where supported.",
        checked = settings.pipeline64Enabled,
        onCheckedChange = onPipeline64EnabledChange,
    )
    AudiophileSwitchRow(
        label = "Follow Track Sample Rate",
        subtitle = "Select a supported output rate for every track instead of forcing one rate.",
        checked = settings.followTrackSampleRate,
        onCheckedChange = onFollowTrackSampleRateChange,
    )
    AudiophileSwitchRow(
        label = "DSD Remastering",
        subtitle = "Convert PCM-domain audio to a DSD stream for a compatible output.",
        checked = settings.dsdRemasteringEnabled,
        onCheckedChange = onDsdRemasteringEnabledChange,
    )
    AudiophileChoiceChips(
        label = "DSD rate",
        options = DsdRate.entries,
        selected = settings.dsdRate,
        onSelect = onDsdRateChange,
        optionLabel = DsdRate::label,
        enabled = settings.dsdRemasteringEnabled,
    )
    AudiophileSwitchRow(
        label = "48k-family DSD Remastering",
        subtitle = "Use 48 kHz-based DSD rates instead of the default 44.1 kHz family.",
        checked = settings.dsdRemaster48kFamilyEnabled,
        onCheckedChange = onDsdRemaster48kFamilyEnabledChange,
        enabled = settings.dsdRemasteringEnabled,
    )
    AudiophileFloatSliderRow(
        label = "Remaster gain",
        value = settings.dsdRemasterGainDb,
        range = -6f..0f,
        steps = 59,
        valueText = formatDb(settings.dsdRemasterGainDb),
        enabled = settings.dsdRemasteringEnabled,
        subtitle = "Factory default: -2.0 dB.",
        onValueChange = onDsdRemasterGainDbChange,
    )
    AudiophileSwitchRow(
        label = "Perfect Bit Perfect",
        subtitle = "Temporarily bypass DSP, gains, and crossfade. Saved settings are not changed and can be combined with DSD Remastering.",
        checked = settings.perfectBitPerfectEnabled,
        onCheckedChange = onPerfectBitPerfectEnabledChange,
    )
}

internal fun bitPerfectBadges(
    settings: BitPerfectSettingsState,
    info: AudioInfoState = AudioInfoState(),
): List<AudioStatusBadge> = buildList {
    if (settings.pipeline64Enabled) {
        add(AudioStatusBadge("64-bit Pipeline", AudioStatusBadgeTone.ACTIVE))
    }
    if (settings.followTrackSampleRate) {
        add(AudioStatusBadge("Follow Track", AudioStatusBadgeTone.ACTIVE))
    }
    if (settings.dsdRemasteringEnabled) {
        add(AudioStatusBadge("${settings.dsdRate.label} Remastering", AudioStatusBadgeTone.ACTIVE))
        if (settings.dsdRemaster48kFamilyEnabled) {
            add(AudioStatusBadge("48k DSD family", AudioStatusBadgeTone.ACTIVE))
        }
    }
    if (settings.perfectBitPerfectEnabled) {
        add(AudioStatusBadge("Perfect Bit Perfect", AudioStatusBadgeTone.SUCCESS))
    }
    addAll(info.badges)
    if (isEmpty()) add(AudioStatusBadge("Standard PCM pipeline"))
}.distinctBy(AudioStatusBadge::label)
