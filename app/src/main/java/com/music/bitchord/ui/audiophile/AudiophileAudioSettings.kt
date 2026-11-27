package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Stateful-host entry point: persist both snapshots and handle exit when already at Audio home. */
@Composable
fun AudiophileAudioSettingsScreen(
    settings: AudiophileAudioState,
    audioInfo: AudioInfoState,
    uiState: AudiophileAudioUiState,
    onSettingsChange: (AudiophileAudioState) -> Unit,
    onUiStateChange: (AudiophileAudioUiState) -> Unit,
    onExit: () -> Unit,
    onResetEqualizerPresets: () -> Unit,
    onResetReverbPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val callbacks = remember(
        settings,
        uiState,
        onSettingsChange,
        onUiStateChange,
        onExit,
        onResetEqualizerPresets,
        onResetReverbPresets,
    ) {
        audiophileReducerCallbacks(
            settings,
            uiState,
            onSettingsChange,
            onUiStateChange,
            onExit,
            onResetEqualizerPresets,
            onResetReverbPresets,
        )
    }
    AudiophileAudioSettingsScreen(
        settings = settings,
        audioInfo = audioInfo,
        uiState = uiState,
        callbacks = callbacks,
        modifier = modifier,
    )
}

/** Fully explicit-callback entry point for hosts with their own persistence or navigation. */
@Composable
fun AudiophileAudioSettingsScreen(
    settings: AudiophileAudioState,
    audioInfo: AudioInfoState,
    uiState: AudiophileAudioUiState,
    callbacks: AudiophileAudioCallbacks,
    modifier: Modifier = Modifier,
) {
    val route = settings.outputs.firstOrNull { it.id == uiState.selectedRouteId }
    val profile = route?.profiles?.firstOrNull { it.id == uiState.selectedProfileId }
    val title = when (uiState.page) {
        AudiophileAudioPage.HOME -> "Audio"
        AudiophileAudioPage.AUDIO_INFO -> "Audio Info"
        AudiophileAudioPage.CROSSFADE_FADE_GAPLESS -> "Crossfade, Fade, and Gapless"
        AudiophileAudioPage.REPLAY_GAIN -> "Replay Gain"
        AudiophileAudioPage.AUDIO_FOCUS -> "Audio Focus"
        AudiophileAudioPage.EQUALIZER -> "Equalizer Settings"
        AudiophileAudioPage.RESAMPLER -> "Resampler"
        AudiophileAudioPage.DIRECT_VOLUME_CONTROL -> "Direct Volume Control"
        AudiophileAudioPage.OUTPUTS -> "Audio Outputs"
        AudiophileAudioPage.OUTPUT_ROUTE -> when {
            route == null -> "Audio Output"
            route.id == AudioOutputRouteId.USB_EXCLUSIVE &&
                settings.outputDriver == com.music.bitchord.playback.audio.drivers.OutputDriver.USB_SYSTEM_BIT_PERFECT ->
                settings.outputDriver.label
            else -> route.id.label
        }
        AudiophileAudioPage.OUTPUT_PROFILE -> profile?.name ?: "Output Device"
        AudiophileAudioPage.BIT_PERFECT -> "Bit Perfect"
        AudiophileAudioPage.ADVANCED_TWEAKS -> "Advanced Tweaks"
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { AudiophileTopBar(title, callbacks.navigation.onBack) },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (uiState.page) {
                AudiophileAudioPage.HOME -> AudiophileHomePage(settings, audioInfo, callbacks)
                AudiophileAudioPage.AUDIO_INFO -> AudioInfoPage(
                    info = audioInfo,
                    qualityOptionsEnabled = settings.qualityOptionsEnabled,
                )
                AudiophileAudioPage.CROSSFADE_FADE_GAPLESS -> CrossfadeFadeGaplessSettingsPage(
                    settings = settings.crossfadeFadeGapless,
                    callbacks = callbacks.crossfadeFadeGapless,
                )
                AudiophileAudioPage.REPLAY_GAIN -> ReplayGainSettingsPage(
                    settings = settings.replayGain,
                    callbacks = callbacks.replayGain,
                )
                AudiophileAudioPage.AUDIO_FOCUS -> AudioFocusSettingsPage(
                    settings = settings.audioFocus,
                    callbacks = callbacks.audioFocus,
                )
                AudiophileAudioPage.EQUALIZER -> EqualizerSettingsPage(
                    settings = settings.equalizer,
                    callbacks = callbacks.equalizer,
                )
                AudiophileAudioPage.RESAMPLER -> ResamplerSettingsPage(
                    settings = settings.resampler,
                    callbacks = callbacks.resampler,
                )
                AudiophileAudioPage.DIRECT_VOLUME_CONTROL -> DirectVolumeControlSettingsPage(
                    settings = settings.directVolumeControl,
                    callbacks = callbacks.directVolumeControl,
                )
                AudiophileAudioPage.OUTPUTS -> AudioOutputsSettingsPage(
                    settings = settings,
                    navigation = callbacks.navigation,
                    callbacks = callbacks.outputs,
                )
                AudiophileAudioPage.OUTPUT_ROUTE -> if (route != null) {
                    AudioOutputRouteSettingsPage(
                        route = route,
                        selectedDriver = settings.outputDriver,
                        namedDeviceName = uiState.namedDeviceName,
                        navigation = callbacks.navigation,
                        callbacks = callbacks.outputs,
                    )
                } else {
                    UnavailableOutputPage(callbacks.navigation.onBack)
                }
                AudiophileAudioPage.OUTPUT_PROFILE -> if (route != null && profile != null) {
                    OutputDeviceProfileSettingsPage(
                        routeId = route.id,
                        profile = profile,
                        callbacks = callbacks.outputs,
                    )
                } else {
                    UnavailableOutputPage(callbacks.navigation.onBack)
                }
                AudiophileAudioPage.BIT_PERFECT -> BitPerfectSettingsPage(
                    settings = settings.bitPerfect,
                    info = audioInfo,
                    showMore = uiState.bitPerfectShowMore,
                    callbacks = callbacks.bitPerfect,
                )
                AudiophileAudioPage.ADVANCED_TWEAKS -> AdvancedTweaksSettingsPage(
                    settings = settings.advancedTweaks,
                    callbacks = callbacks.advancedTweaks,
                )
            }
        }
    }
}

@Composable
private fun AudiophileTopBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AudiophileHomePage(
    settings: AudiophileAudioState,
    audioInfo: AudioInfoState,
    callbacks: AudiophileAudioCallbacks,
) {
    AudiophilePageList(modifier = Modifier.fillMaxSize()) {
        item {
            AudiophileHomeQuality(
                enabled = settings.qualityOptionsEnabled,
                audioInfo = audioInfo,
                qualityCallbacks = callbacks.qualityOptions,
                navigation = callbacks.navigation,
            )
        }
        item { AudiophileHomePlayback(settings, callbacks.navigation) }
        item { AudiophileHomeProcessing(settings, callbacks.navigation) }
        item { AudiophileHomeOutput(settings, callbacks.navigation) }
    }
}

@Composable
private fun AudiophileHomeQuality(
    enabled: Boolean,
    audioInfo: AudioInfoState,
    qualityCallbacks: QualityOptionsCallbacks,
    navigation: AudiophileNavigationCallbacks,
) {
    AudiophileSection(title = "Audiophile") {
        AudiophileSwitchRow(
            label = "Quality Options",
            subtitle = "Enable the reference audio processing chain. Saved choices remain intact while off.",
            checked = enabled,
            onCheckedChange = qualityCallbacks.onQualityOptionsEnabledChange,
        )
        AudiophileNavigationRow(
            title = "Audio Info",
            subtitle = audioInfo.trackTitle,
            value = audioInfo.badges.firstOrNull()?.label,
            onClick = navigation.onOpenAudioInfo,
        )
    }
}

@Composable
private fun AudiophileHomePlayback(
    settings: AudiophileAudioState,
    navigation: AudiophileNavigationCallbacks,
) {
    AudiophileSection(title = "Playback") {
        AudiophileNavigationRow(
            title = "Crossfade, Fade, and Gapless",
            value = if (settings.crossfadeFadeGapless.autoAdvanceFading == AutoAdvanceFading.NO_FADING) {
                "No auto crossfade"
            } else {
                formatMilliseconds(settings.crossfadeFadeGapless.crossfadeLengthMs)
            },
            onClick = navigation.onOpenCrossfadeFadeGapless,
        )
        AudiophileNavigationRow(
            title = "Replay Gain",
            value = settings.replayGain.mode.label,
            onClick = navigation.onOpenReplayGain,
        )
        AudiophileNavigationRow(
            title = "Audio Focus",
            value = if (settings.audioFocus.duckVolumeEnabled) "Duck enabled" else "Duck disabled",
            onClick = navigation.onOpenAudioFocus,
        )
    }
}

@Composable
private fun AudiophileHomeProcessing(
    settings: AudiophileAudioState,
    navigation: AudiophileNavigationCallbacks,
) {
    AudiophileSection(title = "Processing") {
        AudiophileNavigationRow(
            title = "Equalizer",
            subtitle = "Band count, mode, frequencies, and layout",
            value = "${settings.equalizer.effectiveBandCount} bands · ${settings.equalizer.mode.label}",
            onClick = navigation.onOpenEqualizer,
        )
        AudiophileNavigationRow(
            title = "Resampler",
            value = settings.resampler.type.label,
            onClick = navigation.onOpenResampler,
        )
        AudiophileNavigationRow(
            title = "Direct Volume Control (DVC)",
            value = if (settings.directVolumeControl.dvcEnabled) "Enabled" else "Disabled",
            onClick = navigation.onOpenDirectVolumeControl,
        )
        AudiophileNavigationRow(
            title = "Bit Perfect",
            subtitle = "64-bit pipeline, sample-rate following, DSD, and status",
            value = when {
                settings.bitPerfect.perfectBitPerfectEnabled -> "Perfect"
                settings.bitPerfect != BitPerfectSettingsState() -> "Custom"
                else -> "Off"
            },
            onClick = navigation.onOpenBitPerfect,
        )
    }
}

@Composable
private fun AudiophileHomeOutput(
    settings: AudiophileAudioState,
    navigation: AudiophileNavigationCallbacks,
) {
    val activeOutputs = settings.outputs.count { route ->
        route.status == OutputConnectionStatus.ACTIVE ||
            route.profiles.any { it.connectionStatus == OutputConnectionStatus.ACTIVE }
    }
    AudiophileSection(title = "Output & device") {
        AudiophileNavigationRow(
            title = "Output",
            subtitle = "Routes and independent per-device profiles",
            value = if (activeOutputs == 0) "${settings.outputs.size} routes" else "$activeOutputs active",
            onClick = navigation.onOpenOutputs,
        )
        AudiophileNavigationRow(
            title = "Advanced Tweaks",
            subtitle = "Reference Mode, USB volume, gain compensation, and system workarounds",
            value = settings.advancedTweaks.referenceMode.label.takeIf {
                settings.advancedTweaks.referenceModeEnabled
            },
            onClick = navigation.onOpenAdvancedTweaks,
        )
    }
}

@Composable
fun AudioInfoPage(
    info: AudioInfoState,
    qualityOptionsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item {
            AudiophileSection(title = info.trackTitle) {
                AudiophileValueRow(
                    label = "Quality Options",
                    value = if (qualityOptionsEnabled) "Enabled" else "Disabled",
                )
                if (info.badges.isNotEmpty()) AudiophileBadges(info.badges)
            }
        }
        if (info.source.isNotEmpty()) {
            item { AudioInfoSection("Source", info.source) }
        }
        if (info.processing.isNotEmpty()) {
            item { AudioInfoSection("Processing", info.processing) }
        }
        if (info.output.isNotEmpty()) {
            item { AudioInfoSection("Output", info.output) }
        }
    }
}

@Composable
private fun AudioInfoSection(title: String, entries: List<AudioInfoEntry>) {
    AudiophileSection(title = title) {
        entries.forEach { entry -> AudiophileValueRow(label = entry.label, value = entry.value) }
    }
}

@Composable
private fun UnavailableOutputPage(onBack: () -> Unit) {
    AudiophilePageList(modifier = Modifier.fillMaxSize()) {
        item {
            AudiophileSection(title = "Output unavailable") {
                Text(
                    text = "This route or device is no longer available. Return to the output list and choose another device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBack) { Text("Back to outputs") }
            }
        }
    }
}
