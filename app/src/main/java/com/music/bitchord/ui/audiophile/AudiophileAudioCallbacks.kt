package com.music.bitchord.ui.audiophile

/** Explicit control callbacks, grouped to keep constructors and Compose capture sets small. */
data class AudiophileAudioCallbacks(
    val navigation: AudiophileNavigationCallbacks,
    val qualityOptions: QualityOptionsCallbacks,
    val crossfadeFadeGapless: CrossfadeFadeGaplessCallbacks,
    val replayGain: ReplayGainCallbacks,
    val audioFocus: AudioFocusCallbacks,
    val equalizer: EqualizerSettingsCallbacks,
    val resampler: ResamplerSettingsCallbacks,
    val directVolumeControl: DirectVolumeControlCallbacks,
    val outputs: OutputSettingsCallbacks,
    val bitPerfect: BitPerfectSettingsCallbacks,
    val advancedTweaks: AdvancedTweaksCallbacks,
)

data class AudiophileNavigationCallbacks(
    val onBack: () -> Unit,
    val onOpenAudioInfo: () -> Unit,
    val onOpenCrossfadeFadeGapless: () -> Unit,
    val onOpenReplayGain: () -> Unit,
    val onOpenAudioFocus: () -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onOpenResampler: () -> Unit,
    val onOpenDirectVolumeControl: () -> Unit,
    val onOpenOutputs: () -> Unit,
    val onOpenOutputRoute: (AudioOutputRouteId) -> Unit,
    val onOpenOutputProfile: (AudioOutputRouteId, String) -> Unit,
    val onOpenBitPerfect: () -> Unit,
    val onOpenAdvancedTweaks: () -> Unit,
)

data class QualityOptionsCallbacks(
    val onQualityOptionsEnabledChange: (Boolean) -> Unit,
)

data class CrossfadeFadeGaplessCallbacks(
    val onAutoAdvanceFadingChange: (AutoAdvanceFading) -> Unit,
    val onManualTrackChangeFadingChange: (ManualTrackChangeFading) -> Unit,
    val onCrossfadeLengthMsChange: (Int) -> Unit,
    val onShortManualCrossfadeLengthMsChange: (Int) -> Unit,
    val onFadeOnPlayPauseStopChange: (Boolean) -> Unit,
    val onPlayPauseStopFadeLengthMsChange: (Int) -> Unit,
    val onFadeOnSeekChange: (Boolean) -> Unit,
    val onSeekFadeLengthMsChange: (Int) -> Unit,
    val onGaplessEnabledChange: (Boolean) -> Unit,
    val onCutSilenceEnabledChange: (Boolean) -> Unit,
    val onPreloadGaplessTracksMsChange: (Int) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class ReplayGainCallbacks(
    val onModeChange: (ReplayGainMode) -> Unit,
    val onSourceChange: (ReplayGainSource) -> Unit,
    val onRgPreampDbChange: (Float) -> Unit,
    val onNoRgPreampDbChange: (Float) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class AudioFocusCallbacks(
    val onPauseInCallChange: (Boolean) -> Unit,
    val onShortAudioFocusChangeCallsChange: (Boolean) -> Unit,
    val onDuckVolumeEnabledChange: (Boolean) -> Unit,
    val onShortFocusDuckPolicyChange: (AudioFocusDuckPolicy) -> Unit,
    val onNotificationDuckPolicyChange: (AudioFocusDuckPolicy) -> Unit,
    val onNavigationDuckingEnabledChange: (Boolean) -> Unit,
    val onDuckVolumePercentChange: (Int) -> Unit,
    val onPermanentAudioFocusChange: (Boolean) -> Unit,
    val onResumeAfterCallChange: (Boolean) -> Unit,
    val onResumeOnStartChange: (Boolean) -> Unit,
    val onResumeOnReopenChange: (Boolean) -> Unit,
    val onResumeOnFocusGainChange: (Boolean) -> Unit,
    val onWaitForStorageChange: (Boolean) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class EqualizerSettingsCallbacks(
    val onModeChange: (AudiophileEqualizerMode) -> Unit,
    val onBandCountChange: (Int) -> Unit,
    val onCustomBandsEnabledChange: (Boolean) -> Unit,
    val onCustomBandCountChange: (Int) -> Unit,
    val onFirstFrequencyHzChange: (Int) -> Unit,
    val onLastFrequencyHzChange: (Int) -> Unit,
    val onLayoutChange: (EqualizerLayout) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class ResamplerSettingsCallbacks(
    val onTypeChange: (ResamplerType) -> Unit,
    val onQualityChange: (ResamplerQuality) -> Unit,
    val onCutoffFrequencyPercentChange: (Float) -> Unit,
    val onPhaseChange: (ResamplerPhase) -> Unit,
    val onSteepFilterEnabledChange: (Boolean) -> Unit,
    val onDitherChange: (DitherMode) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class DirectVolumeControlCallbacks(
    val onDvcEnabledChange: (Boolean) -> Unit,
    val onVolumeCompensationEnabledChange: (Boolean) -> Unit,
    val onNoDvcHeadroomGainDbChange: (Float) -> Unit,
    val onNoDvcForBluetoothAbsoluteVolumeChange: (Boolean) -> Unit,
    val onBluetoothAbsoluteVolumeEnabledChange: (Boolean) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class BitPerfectSettingsCallbacks(
    val onPipeline64EnabledChange: (Boolean) -> Unit,
    val onFollowTrackSampleRateChange: (Boolean) -> Unit,
    val onDsdRemasteringEnabledChange: (Boolean) -> Unit,
    val onDsdRateChange: (DsdRate) -> Unit,
    val onDsdRemaster48kFamilyEnabledChange: (Boolean) -> Unit,
    val onDsdRemasterGainDbChange: (Float) -> Unit,
    val onPerfectBitPerfectEnabledChange: (Boolean) -> Unit,
    val onShowMoreChange: (Boolean) -> Unit,
    val onRestoreDefaults: () -> Unit,
)

data class OutputSettingsCallbacks(
    val onRouteEnabledChange: (AudioOutputRouteId, Boolean) -> Unit,
    val onProfileEnabledChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onSampleRateHzChange: (AudioOutputRouteId, String, Int) -> Unit,
    val onSampleFormatChange: (AudioOutputRouteId, String, OutputSampleFormat) -> Unit,
    val onBufferPresetChange: (AudioOutputRouteId, String, OutputBufferPreset) -> Unit,
    val onBufferMsChange: (AudioOutputRouteId, String, Int) -> Unit,
    val onBufferCountChange: (AudioOutputRouteId, String, Int) -> Unit,
    val onPostFadeBufferCountChange: (AudioOutputRouteId, String, Int) -> Unit,
    val onVisualizationLyricsDelayMsChange: (AudioOutputRouteId, String, Int) -> Unit,
    val onNoDvcChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onNoHeadroomGainChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onNoEquToneChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onNoDuckChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onEqualizerPresetIdChange: (AudioOutputRouteId, String, String?) -> Unit,
    val onPipeline64EnabledChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onFollowTrackSampleRateChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onDsdRemasteringEnabledChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onDsdRateChange: (AudioOutputRouteId, String, DsdRate) -> Unit,
    val onDsdRemaster48kFamilyEnabledChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onDsdRemasterGainDbChange: (AudioOutputRouteId, String, Float) -> Unit,
    val onPerfectBitPerfectEnabledChange: (AudioOutputRouteId, String, Boolean) -> Unit,
    val onNamedDeviceNameChange: (String) -> Unit,
    val onAddNamedDevice: (AudioOutputRouteId, String) -> Unit,
    val onRestoreRouteDefaults: (AudioOutputRouteId) -> Unit,
    val onRestoreProfileDefaults: (AudioOutputRouteId, String) -> Unit,
    val onRestoreAllOutputs: () -> Unit,
)

data class AdvancedTweaksCallbacks(
    val onReferenceModeEnabledChange: (Boolean) -> Unit,
    val onReferenceModeChange: (ReferenceMode) -> Unit,
    val onUsbVolumeLockEnabledChange: (Boolean) -> Unit,
    val onUsbGainCompensationEnabledChange: (Boolean) -> Unit,
    val onUsbGainCompensationDbChange: (Float) -> Unit,
    val onVolumeLevelsChange: (Int) -> Unit,
    val onVolumeStepsChange: (Int) -> Unit,
    val onMusicFxEnabledChange: (Boolean) -> Unit,
    val onForceSpeakerOffChange: (Boolean) -> Unit,
    val onForceAudioOnFocusChange: (Boolean) -> Unit,
    val onEmulatedMediaStreamChange: (Boolean) -> Unit,
    val onAudioThreadPriorityChange: (Int) -> Unit,
    val onResetEqualizerPresets: () -> Unit,
    val onResetReverbPresets: () -> Unit,
    val onRestoreDefaults: () -> Unit,
)
