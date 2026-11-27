package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

@Composable
fun EqualizerSettingsPage(
    settings: EqualizerSettingsState,
    callbacks: EqualizerSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { EqualizerModeSection(settings, callbacks) }
        item { EqualizerBandsSection(settings, callbacks) }
        item { EqualizerFrequencySection(settings, callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun EqualizerModeSection(settings: EqualizerSettingsState, callbacks: EqualizerSettingsCallbacks) {
    AudiophileSection(title = "Mode & layout") {
        AudiophileSelectionGroup(
            label = "Equalizer mode",
            options = AudiophileEqualizerMode.entries,
            selected = settings.mode,
            onSelect = callbacks.onModeChange,
            optionLabel = AudiophileEqualizerMode::label,
        )
        AudiophileSelectionGroup(
            label = "Layout",
            options = EqualizerLayout.entries,
            selected = settings.layout,
            onSelect = callbacks.onLayoutChange,
            optionLabel = EqualizerLayout::label,
        )
    }
}

@Composable
private fun EqualizerBandsSection(settings: EqualizerSettingsState, callbacks: EqualizerSettingsCallbacks) {
    AudiophileSection(title = "Graphic bands") {
        AudiophileSwitchRow(
            label = "Enable custom bands",
            subtitle = "Choose any count from 5 to 32 instead of a fixed layout.",
            checked = settings.customBandsEnabled,
            onCheckedChange = callbacks.onCustomBandsEnabledChange,
            enabled = settings.mode == AudiophileEqualizerMode.GRAPHIC,
        )
        if (settings.customBandsEnabled) {
            AudiophileIntSliderRow(
                label = "Custom number of bands",
                value = settings.customBandCount,
                range = 5..32,
                valueText = "${settings.customBandCount} bands",
                enabled = settings.mode == AudiophileEqualizerMode.GRAPHIC,
                onValueChange = callbacks.onCustomBandCountChange,
            )
        } else {
            AudiophileSelectionGroup(
                label = "Number of bands",
                options = AudiophileAudioDefaults.fixedBandCounts,
                selected = settings.bandCount,
                onSelect = callbacks.onBandCountChange,
                optionLabel = { "$it" },
                enabled = settings.mode == AudiophileEqualizerMode.GRAPHIC,
            )
        }
    }
}

@Composable
private fun EqualizerFrequencySection(
    settings: EqualizerSettingsState,
    callbacks: EqualizerSettingsCallbacks,
) {
    val enabled = settings.mode == AudiophileEqualizerMode.GRAPHIC
    AudiophileSection(title = "Frequency range") {
        AudiophileIntSliderRow(
            label = "First frequency",
            value = settings.firstFrequencyHz,
            range = 5..24_000,
            step = 5,
            steps = 0,
            valueText = formatFrequency(settings.firstFrequencyHz),
            enabled = enabled,
            onValueChange = callbacks.onFirstFrequencyHzChange,
        )
        AudiophileIntSliderRow(
            label = "Last frequency",
            value = settings.lastFrequencyHz,
            range = 5..24_000,
            step = 5,
            steps = 0,
            valueText = formatFrequency(settings.lastFrequencyHz),
            enabled = enabled,
            onValueChange = callbacks.onLastFrequencyHzChange,
        )
    }
}

@Composable
fun ResamplerSettingsPage(
    settings: ResamplerSettingsState,
    callbacks: ResamplerSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { ResamplerTypeSection(settings, callbacks) }
        item { ResamplerFilterSection(settings, callbacks) }
        item { ResamplerDitherSection(settings, callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun ResamplerTypeSection(settings: ResamplerSettingsState, callbacks: ResamplerSettingsCallbacks) {
    AudiophileSection(title = "Resampler") {
        AudiophileSelectionGroup(
            label = "Type",
            options = ResamplerType.entries,
            selected = settings.type,
            onSelect = callbacks.onTypeChange,
            optionLabel = ResamplerType::label,
        )
        AudiophileSelectionGroup(
            label = "Quality",
            options = ResamplerQuality.entries,
            selected = settings.quality,
            onSelect = callbacks.onQualityChange,
            optionLabel = ResamplerQuality::label,
            enabled = settings.type == ResamplerType.SOX,
        )
    }
}

@Composable
private fun ResamplerFilterSection(settings: ResamplerSettingsState, callbacks: ResamplerSettingsCallbacks) {
    val sox = settings.type == ResamplerType.SOX
    AudiophileSection(title = "SoX filter") {
        AudiophileFloatSliderRow(
            label = "Cutoff frequency ratio",
            value = settings.cutoffFrequencyPercent,
            range = 80f..99.7f,
            steps = 196,
            valueText = String.format(Locale.US, "%.1f%%", settings.cutoffFrequencyPercent),
            enabled = sox,
            subtitle = "Factory default: 97.0%. Higher values retain more bandwidth near Nyquist.",
            onValueChange = callbacks.onCutoffFrequencyPercentChange,
        )
        AudiophileSelectionGroup(
            label = "Phase response",
            options = ResamplerPhase.entries,
            selected = settings.phase,
            onSelect = callbacks.onPhaseChange,
            optionLabel = ResamplerPhase::label,
            enabled = sox,
        )
        AudiophileSwitchRow(
            label = "Steep filter",
            subtitle = "Use a narrower transition band at the selected cutoff.",
            checked = settings.steepFilterEnabled,
            onCheckedChange = callbacks.onSteepFilterEnabledChange,
            enabled = sox,
        )
    }
}

@Composable
private fun ResamplerDitherSection(settings: ResamplerSettingsState, callbacks: ResamplerSettingsCallbacks) {
    AudiophileSection(title = "Dither") {
        AudiophileSelectionGroup(
            label = "Dither mode",
            options = DitherMode.entries,
            selected = settings.dither,
            onSelect = callbacks.onDitherChange,
            optionLabel = DitherMode::label,
        )
    }
}

@Composable
fun DirectVolumeControlSettingsPage(
    settings: DirectVolumeControlState,
    callbacks: DirectVolumeControlCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { DirectVolumeSection(settings, callbacks) }
        item { BluetoothVolumeSection(settings, callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun DirectVolumeSection(
    settings: DirectVolumeControlState,
    callbacks: DirectVolumeControlCallbacks,
) {
    AudiophileSection(title = "Direct Volume Control") {
        AudiophileSwitchRow(
            label = "Direct Volume Control (DVC)",
            subtitle = "Use the output device's direct volume path when available.",
            checked = settings.dvcEnabled,
            onCheckedChange = callbacks.onDvcEnabledChange,
        )
        AudiophileSwitchRow(
            label = "Volume Compensation",
            subtitle = "Compensate for the loudness difference when Direct Volume Control is active.",
            checked = settings.volumeCompensationEnabled,
            onCheckedChange = callbacks.onVolumeCompensationEnabledChange,
        )
        AudiophileFloatSliderRow(
            label = "No DVC Headroom Gain",
            value = settings.noDvcHeadroomGainDb,
            range = -12f..0f,
            steps = 119,
            valueText = formatDb(settings.noDvcHeadroomGainDb),
            enabled = !settings.dvcEnabled,
            subtitle = "Factory default: -6.0 dB. Reserved for equalizer headroom when DVC is inactive.",
            onValueChange = callbacks.onNoDvcHeadroomGainDbChange,
        )
    }
}

@Composable
private fun BluetoothVolumeSection(
    settings: DirectVolumeControlState,
    callbacks: DirectVolumeControlCallbacks,
) {
    AudiophileSection(title = "Bluetooth Absolute Volume") {
        AudiophileSwitchRow(
            label = "Bluetooth Absolute Volume / Media Sync",
            subtitle = "Reflect the connected Bluetooth device's synchronized volume state.",
            checked = settings.bluetoothAbsoluteVolumeEnabled,
            onCheckedChange = callbacks.onBluetoothAbsoluteVolumeEnabledChange,
        )
        AudiophileSwitchRow(
            label = "No DVC for Bluetooth Absolute Volume",
            subtitle = "Disable DVC when Absolute Volume is detected, or always when its status is unknown.",
            checked = settings.noDvcForBluetoothAbsoluteVolume,
            onCheckedChange = callbacks.onNoDvcForBluetoothAbsoluteVolumeChange,
        )
    }
}

private fun formatFrequency(value: Int): String = if (value >= 1_000) {
    String.format(Locale.US, "%.1f kHz", value / 1_000f)
} else {
    "$value Hz"
}
