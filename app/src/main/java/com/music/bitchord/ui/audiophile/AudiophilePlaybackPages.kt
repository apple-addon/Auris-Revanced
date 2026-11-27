package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun CrossfadeFadeGaplessSettingsPage(
    settings: CrossfadeFadeGaplessState,
    callbacks: CrossfadeFadeGaplessCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { CrossfadeChoicesSection(settings, callbacks) }
        item { FadeSettingsSection(settings, callbacks) }
        item { GaplessSettingsSection(settings, callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun CrossfadeChoicesSection(
    settings: CrossfadeFadeGaplessState,
    callbacks: CrossfadeFadeGaplessCallbacks,
) {
    AudiophileSection(title = "Crossfade") {
        AudiophileSelectionGroup(
            label = "Auto-advance fading",
            options = AutoAdvanceFading.entries,
            selected = settings.autoAdvanceFading,
            onSelect = callbacks.onAutoAdvanceFadingChange,
            optionLabel = AutoAdvanceFading::label,
        )
        AudiophileSelectionGroup(
            label = "Manual track-change fading",
            options = ManualTrackChangeFading.entries,
            selected = settings.manualTrackChangeFading,
            onSelect = callbacks.onManualTrackChangeFadingChange,
            optionLabel = ManualTrackChangeFading::label,
        )
        AudiophileIntSliderRow(
            label = "Crossfade length",
            value = settings.crossfadeLengthMs,
            range = 1_000..15_000,
            step = 100,
            valueText = formatMilliseconds(settings.crossfadeLengthMs),
            enabled = settings.autoAdvanceFading == AutoAdvanceFading.CROSSFADE ||
                settings.manualTrackChangeFading == ManualTrackChangeFading.CROSSFADE,
            onValueChange = callbacks.onCrossfadeLengthMsChange,
        )
        AudiophileIntSliderRow(
            label = "Short manual crossfade length",
            value = settings.shortManualCrossfadeLengthMs,
            range = 10..5_000,
            step = 10,
            valueText = formatMilliseconds(settings.shortManualCrossfadeLengthMs),
            enabled = settings.manualTrackChangeFading == ManualTrackChangeFading.SHORT_CROSSFADE,
            onValueChange = callbacks.onShortManualCrossfadeLengthMsChange,
        )
    }
}

@Composable
private fun FadeSettingsSection(
    settings: CrossfadeFadeGaplessState,
    callbacks: CrossfadeFadeGaplessCallbacks,
) {
    AudiophileSection(title = "Fade") {
        AudiophileSwitchRow(
            label = "Fade on play/pause/stop",
            checked = settings.fadeOnPlayPauseStop,
            onCheckedChange = callbacks.onFadeOnPlayPauseStopChange,
        )
        AudiophileIntSliderRow(
            label = "Play/pause/stop fade length",
            value = settings.playPauseStopFadeLengthMs,
            range = 0..500,
            step = 10,
            valueText = formatMilliseconds(settings.playPauseStopFadeLengthMs),
            enabled = settings.fadeOnPlayPauseStop,
            onValueChange = callbacks.onPlayPauseStopFadeLengthMsChange,
        )
        AudiophileSwitchRow(
            label = "Fade on seek",
            checked = settings.fadeOnSeek,
            onCheckedChange = callbacks.onFadeOnSeekChange,
        )
        AudiophileIntSliderRow(
            label = "Seek fade length",
            value = settings.seekFadeLengthMs,
            range = 0..500,
            step = 10,
            valueText = formatMilliseconds(settings.seekFadeLengthMs),
            enabled = settings.fadeOnSeek,
            onValueChange = callbacks.onSeekFadeLengthMsChange,
        )
    }
}

@Composable
private fun GaplessSettingsSection(
    settings: CrossfadeFadeGaplessState,
    callbacks: CrossfadeFadeGaplessCallbacks,
) {
    AudiophileSection(title = "Gapless") {
        AudiophileSwitchRow(
            label = "Gapless playback",
            subtitle = "Preserve continuous albums without adding silence between tracks.",
            checked = settings.gaplessEnabled,
            onCheckedChange = callbacks.onGaplessEnabledChange,
        )
        AudiophileSwitchRow(
            label = "Cut silence",
            subtitle = "Trim leading and trailing silence supported by the decoder.",
            checked = settings.cutSilenceEnabled,
            onCheckedChange = callbacks.onCutSilenceEnabledChange,
            enabled = settings.gaplessEnabled,
        )
        AudiophileIntSliderRow(
            label = "Preload gapless tracks",
            value = settings.preloadGaplessTracksMs,
            range = 0..10_000,
            step = 100,
            valueText = formatMilliseconds(settings.preloadGaplessTracksMs),
            enabled = settings.gaplessEnabled,
            subtitle = "Use a larger preload for slow storage; 0 ms is the factory default.",
            onValueChange = callbacks.onPreloadGaplessTracksMsChange,
        )
    }
}

@Composable
fun ReplayGainSettingsPage(
    settings: ReplayGainState,
    callbacks: ReplayGainCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { ReplayGainModeSection(settings, callbacks) }
        item { ReplayGainPreampSection(settings, callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun ReplayGainModeSection(settings: ReplayGainState, callbacks: ReplayGainCallbacks) {
    AudiophileSection(title = "Replay Gain") {
        AudiophileSelectionGroup(
            label = "Replay Gain",
            options = ReplayGainMode.entries,
            selected = settings.mode,
            onSelect = callbacks.onModeChange,
            optionLabel = ReplayGainMode::label,
        )
        AudiophileSelectionGroup(
            label = "Source",
            options = ReplayGainSource.entries,
            selected = settings.source,
            onSelect = callbacks.onSourceChange,
            optionLabel = ReplayGainSource::label,
            enabled = settings.mode != ReplayGainMode.OFF,
        )
    }
}

@Composable
private fun ReplayGainPreampSection(settings: ReplayGainState, callbacks: ReplayGainCallbacks) {
    val enabled = settings.mode != ReplayGainMode.OFF
    AudiophileSection(title = "Preamp") {
        AudiophileFloatSliderRow(
            label = "RG preamp",
            value = settings.rgPreampDb,
            range = -15f..15f,
            steps = 299,
            valueText = formatDb(settings.rgPreampDb),
            enabled = enabled,
            subtitle = "Applied to songs which contain Replay Gain information.",
            onValueChange = callbacks.onRgPreampDbChange,
        )
        AudiophileFloatSliderRow(
            label = "Preamp for songs without RG info",
            value = settings.noRgPreampDb,
            range = -15f..15f,
            steps = 299,
            valueText = formatDb(settings.noRgPreampDb),
            enabled = enabled,
            onValueChange = callbacks.onNoRgPreampDbChange,
        )
    }
}

@Composable
fun AudioFocusSettingsPage(
    settings: AudioFocusState,
    callbacks: AudioFocusCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { TemporaryFocusSection(settings, callbacks) }
        item { PermanentFocusSection(settings, callbacks) }
        item { AudiophileRestoreButton(callbacks.onRestoreDefaults) }
    }
}

@Composable
private fun TemporaryFocusSection(settings: AudioFocusState, callbacks: AudioFocusCallbacks) {
    AudiophileSection(title = "Temporary focus changes") {
        AudiophileSwitchRow(
            label = "Pause in Call",
            subtitle = "Pause playback whenever a telephone call is active.",
            checked = settings.pauseInCall,
            onCheckedChange = callbacks.onPauseInCallChange,
        )
        AudiophileSwitchRow(
            label = "Short Audio Focus Change / Calls",
            subtitle = "Handle temporary focus losses from notifications and calls.",
            checked = settings.shortAudioFocusChangeCalls,
            onCheckedChange = callbacks.onShortAudioFocusChangeCallsChange,
        )
        AudiophileSwitchRow(
            label = "Duck Volume",
            subtitle = "Enable the configured short-focus, notification, and navigation duck policies.",
            checked = settings.duckVolumeEnabled,
            onCheckedChange = callbacks.onDuckVolumeEnabledChange,
            enabled = settings.shortAudioFocusChangeCalls,
        )
        AudiophileSelectionGroup(
            label = "Short focus duck policy",
            options = AudioFocusDuckPolicy.entries,
            selected = settings.shortFocusDuckPolicy,
            onSelect = callbacks.onShortFocusDuckPolicyChange,
            optionLabel = AudioFocusDuckPolicy::label,
            enabled = settings.shortAudioFocusChangeCalls && settings.duckVolumeEnabled,
        )
        AudiophileSelectionGroup(
            label = "Notification duck policy",
            options = AudioFocusDuckPolicy.entries,
            selected = settings.notificationDuckPolicy,
            onSelect = callbacks.onNotificationDuckPolicyChange,
            optionLabel = AudioFocusDuckPolicy::label,
            enabled = settings.shortAudioFocusChangeCalls && settings.duckVolumeEnabled,
        )
        AudiophileSwitchRow(
            label = "Navigation Ducking",
            subtitle = "Lower music for spoken navigation instructions.",
            checked = settings.navigationDuckingEnabled,
            onCheckedChange = callbacks.onNavigationDuckingEnabledChange,
            enabled = settings.shortAudioFocusChangeCalls && settings.duckVolumeEnabled,
        )
        AudiophileIntSliderRow(
            label = "Duck volume",
            value = settings.duckVolumePercent,
            range = 0..100,
            step = 1,
            valueText = "${settings.duckVolumePercent}%",
            enabled = settings.shortAudioFocusChangeCalls && settings.duckVolumeEnabled,
            onValueChange = callbacks.onDuckVolumePercentChange,
        )
    }
}

@Composable
private fun PermanentFocusSection(settings: AudioFocusState, callbacks: AudioFocusCallbacks) {
    AudiophileSection(title = "Playback resumption") {
        AudiophileSwitchRow(
            label = "Permanent Audio Focus Change",
            subtitle = "Pause when another app takes permanent audio focus.",
            checked = settings.permanentAudioFocusChange,
            onCheckedChange = callbacks.onPermanentAudioFocusChange,
        )
        AudiophileSwitchRow(
            label = "Resume After Call",
            checked = settings.resumeAfterCall,
            onCheckedChange = callbacks.onResumeAfterCallChange,
        )
        AudiophileSwitchRow(
            label = "Resume On Start",
            checked = settings.resumeOnStart,
            onCheckedChange = callbacks.onResumeOnStartChange,
        )
        AudiophileSwitchRow(
            label = "Resume On Reopen",
            checked = settings.resumeOnReopen,
            onCheckedChange = callbacks.onResumeOnReopenChange,
        )
        AudiophileSwitchRow(
            label = "Resume On Focus Gain",
            subtitle = "Resume only if playback was interrupted by a focus loss.",
            checked = settings.resumeOnFocusGain,
            onCheckedChange = callbacks.onResumeOnFocusGainChange,
        )
        AudiophileSwitchRow(
            label = "Wait for Storage",
            subtitle = "Delay playback until removable storage is available after mounting or reopening.",
            checked = settings.waitForStorage,
            onCheckedChange = callbacks.onWaitForStorageChange,
        )
    }
}
