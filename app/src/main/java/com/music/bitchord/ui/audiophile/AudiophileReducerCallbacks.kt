package com.music.bitchord.ui.audiophile

/**
 * Adapter for hosts which store one immutable settings snapshot and one immutable UI snapshot.
 * Every explicit callback is wired; hosts needing custom behaviour can construct the groups directly.
 */
fun audiophileReducerCallbacks(
    settings: AudiophileAudioState,
    uiState: AudiophileAudioUiState,
    onSettingsChange: (AudiophileAudioState) -> Unit,
    onUiStateChange: (AudiophileAudioUiState) -> Unit,
    onExit: () -> Unit,
    onResetEqualizerPresets: () -> Unit,
    onResetReverbPresets: () -> Unit,
): AudiophileAudioCallbacks = AudiophileAudioCallbacks(
    navigation = navigationCallbacks(uiState, onUiStateChange, onExit),
    qualityOptions = QualityOptionsCallbacks(
        onQualityOptionsEnabledChange = {
            onSettingsChange(AudiophileAudioReducer.setQualityOptions(settings, it))
        },
    ),
    crossfadeFadeGapless = crossfadeCallbacks(settings, onSettingsChange),
    replayGain = replayGainCallbacks(settings, onSettingsChange),
    audioFocus = audioFocusCallbacks(settings, onSettingsChange),
    equalizer = equalizerCallbacks(settings, onSettingsChange),
    resampler = resamplerCallbacks(settings, onSettingsChange),
    directVolumeControl = dvcCallbacks(settings, onSettingsChange),
    outputs = outputCallbacks(settings, uiState, onSettingsChange, onUiStateChange),
    bitPerfect = bitPerfectCallbacks(settings, uiState, onSettingsChange, onUiStateChange),
    advancedTweaks = advancedTweaksCallbacks(
        settings,
        onSettingsChange,
        onResetEqualizerPresets,
        onResetReverbPresets,
    ),
)

private fun navigationCallbacks(
    uiState: AudiophileAudioUiState,
    onUiStateChange: (AudiophileAudioUiState) -> Unit,
    onExit: () -> Unit,
): AudiophileNavigationCallbacks {
    fun open(page: AudiophileAudioPage) {
        onUiStateChange(
            uiState.copy(
                page = page,
                selectedRouteId = null,
                selectedProfileId = null,
                namedDeviceName = "",
            ),
        )
    }
    return AudiophileNavigationCallbacks(
        onBack = {
            when (uiState.page) {
                AudiophileAudioPage.HOME -> onExit()
                AudiophileAudioPage.OUTPUT_PROFILE -> onUiStateChange(
                    uiState.copy(
                        page = AudiophileAudioPage.OUTPUT_ROUTE,
                        selectedProfileId = null,
                        namedDeviceName = "",
                    ),
                )
                AudiophileAudioPage.OUTPUT_ROUTE -> onUiStateChange(
                    uiState.copy(
                        page = AudiophileAudioPage.OUTPUTS,
                        selectedRouteId = null,
                        namedDeviceName = "",
                    ),
                )
                else -> open(AudiophileAudioPage.HOME)
            }
        },
        onOpenAudioInfo = { open(AudiophileAudioPage.AUDIO_INFO) },
        onOpenCrossfadeFadeGapless = { open(AudiophileAudioPage.CROSSFADE_FADE_GAPLESS) },
        onOpenReplayGain = { open(AudiophileAudioPage.REPLAY_GAIN) },
        onOpenAudioFocus = { open(AudiophileAudioPage.AUDIO_FOCUS) },
        onOpenEqualizer = { open(AudiophileAudioPage.EQUALIZER) },
        onOpenResampler = { open(AudiophileAudioPage.RESAMPLER) },
        onOpenDirectVolumeControl = { open(AudiophileAudioPage.DIRECT_VOLUME_CONTROL) },
        onOpenOutputs = { open(AudiophileAudioPage.OUTPUTS) },
        onOpenOutputRoute = { routeId ->
            onUiStateChange(
                uiState.copy(
                    page = AudiophileAudioPage.OUTPUT_ROUTE,
                    selectedRouteId = routeId,
                    selectedProfileId = null,
                    namedDeviceName = "",
                ),
            )
        },
        onOpenOutputProfile = { routeId, profileId ->
            onUiStateChange(
                uiState.copy(
                    page = AudiophileAudioPage.OUTPUT_PROFILE,
                    selectedRouteId = routeId,
                    selectedProfileId = profileId,
                    namedDeviceName = "",
                ),
            )
        },
        onOpenBitPerfect = { open(AudiophileAudioPage.BIT_PERFECT) },
        onOpenAdvancedTweaks = { open(AudiophileAudioPage.ADVANCED_TWEAKS) },
    )
}

private fun crossfadeCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
): CrossfadeFadeGaplessCallbacks {
    fun update(update: (CrossfadeFadeGaplessState) -> CrossfadeFadeGaplessState) {
        onChange(AudiophileAudioReducer.updateCrossfadeFadeGapless(state, update))
    }
    return CrossfadeFadeGaplessCallbacks(
        onAutoAdvanceFadingChange = { value -> update { it.copy(autoAdvanceFading = value) } },
        onManualTrackChangeFadingChange = { value -> update { it.copy(manualTrackChangeFading = value) } },
        onCrossfadeLengthMsChange = { value -> update { it.copy(crossfadeLengthMs = value) } },
        onShortManualCrossfadeLengthMsChange = { value ->
            update { it.copy(shortManualCrossfadeLengthMs = value) }
        },
        onFadeOnPlayPauseStopChange = { value -> update { it.copy(fadeOnPlayPauseStop = value) } },
        onPlayPauseStopFadeLengthMsChange = { value ->
            update { it.copy(playPauseStopFadeLengthMs = value) }
        },
        onFadeOnSeekChange = { value -> update { it.copy(fadeOnSeek = value) } },
        onSeekFadeLengthMsChange = { value -> update { it.copy(seekFadeLengthMs = value) } },
        onGaplessEnabledChange = { value -> update { it.copy(gaplessEnabled = value) } },
        onCutSilenceEnabledChange = { value -> update { it.copy(cutSilenceEnabled = value) } },
        onPreloadGaplessTracksMsChange = { value -> update { it.copy(preloadGaplessTracksMs = value) } },
        onRestoreDefaults = {
            onChange(
                AudiophileAudioReducer.resetSection(
                    state,
                    AudiophileSettingsSection.CROSSFADE_FADE_GAPLESS,
                ),
            )
        },
    )
}

private fun replayGainCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
): ReplayGainCallbacks {
    fun update(update: (ReplayGainState) -> ReplayGainState) {
        onChange(AudiophileAudioReducer.updateReplayGain(state, update))
    }
    return ReplayGainCallbacks(
        onModeChange = { value -> update { it.copy(mode = value) } },
        onSourceChange = { value -> update { it.copy(source = value) } },
        onRgPreampDbChange = { value -> update { it.copy(rgPreampDb = value) } },
        onNoRgPreampDbChange = { value -> update { it.copy(noRgPreampDb = value) } },
        onRestoreDefaults = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.REPLAY_GAIN))
        },
    )
}

private fun audioFocusCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
): AudioFocusCallbacks {
    fun update(update: (AudioFocusState) -> AudioFocusState) {
        onChange(AudiophileAudioReducer.updateAudioFocus(state, update))
    }
    return AudioFocusCallbacks(
        onPauseInCallChange = { value -> update { it.copy(pauseInCall = value) } },
        onShortAudioFocusChangeCallsChange = { value ->
            update { it.copy(shortAudioFocusChangeCalls = value) }
        },
        onDuckVolumeEnabledChange = { value -> update { it.copy(duckVolumeEnabled = value) } },
        onShortFocusDuckPolicyChange = { value -> update { it.copy(shortFocusDuckPolicy = value) } },
        onNotificationDuckPolicyChange = { value ->
            update { it.copy(notificationDuckPolicy = value) }
        },
        onNavigationDuckingEnabledChange = { value ->
            update { it.copy(navigationDuckingEnabled = value) }
        },
        onDuckVolumePercentChange = { value -> update { it.copy(duckVolumePercent = value) } },
        onPermanentAudioFocusChange = { value -> update { it.copy(permanentAudioFocusChange = value) } },
        onResumeAfterCallChange = { value -> update { it.copy(resumeAfterCall = value) } },
        onResumeOnStartChange = { value -> update { it.copy(resumeOnStart = value) } },
        onResumeOnReopenChange = { value -> update { it.copy(resumeOnReopen = value) } },
        onResumeOnFocusGainChange = { value -> update { it.copy(resumeOnFocusGain = value) } },
        onWaitForStorageChange = { value -> update { it.copy(waitForStorage = value) } },
        onRestoreDefaults = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.AUDIO_FOCUS))
        },
    )
}

private fun equalizerCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
): EqualizerSettingsCallbacks {
    fun update(update: (EqualizerSettingsState) -> EqualizerSettingsState) {
        onChange(AudiophileAudioReducer.updateEqualizer(state, update))
    }
    return EqualizerSettingsCallbacks(
        onModeChange = { value -> update { it.copy(mode = value) } },
        onBandCountChange = { value -> update { it.copy(bandCount = value) } },
        onCustomBandsEnabledChange = { value -> update { it.copy(customBandsEnabled = value) } },
        onCustomBandCountChange = { value -> update { it.copy(customBandCount = value) } },
        onFirstFrequencyHzChange = { value -> update { it.copy(firstFrequencyHz = value) } },
        onLastFrequencyHzChange = { value -> update { it.copy(lastFrequencyHz = value) } },
        onLayoutChange = { value -> update { it.copy(layout = value) } },
        onRestoreDefaults = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.EQUALIZER))
        },
    )
}

private fun resamplerCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
): ResamplerSettingsCallbacks {
    fun update(update: (ResamplerSettingsState) -> ResamplerSettingsState) {
        onChange(AudiophileAudioReducer.updateResampler(state, update))
    }
    return ResamplerSettingsCallbacks(
        onTypeChange = { value -> update { it.copy(type = value) } },
        onQualityChange = { value -> update { it.copy(quality = value) } },
        onCutoffFrequencyPercentChange = { value -> update { it.copy(cutoffFrequencyPercent = value) } },
        onPhaseChange = { value -> update { it.copy(phase = value) } },
        onSteepFilterEnabledChange = { value -> update { it.copy(steepFilterEnabled = value) } },
        onDitherChange = { value -> update { it.copy(dither = value) } },
        onRestoreDefaults = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.RESAMPLER))
        },
    )
}

private fun dvcCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
): DirectVolumeControlCallbacks {
    fun update(update: (DirectVolumeControlState) -> DirectVolumeControlState) {
        onChange(AudiophileAudioReducer.updateDirectVolumeControl(state, update))
    }
    return DirectVolumeControlCallbacks(
        onDvcEnabledChange = { value -> update { it.copy(dvcEnabled = value) } },
        onVolumeCompensationEnabledChange = { value ->
            update { it.copy(volumeCompensationEnabled = value) }
        },
        onNoDvcHeadroomGainDbChange = { value -> update { it.copy(noDvcHeadroomGainDb = value) } },
        onNoDvcForBluetoothAbsoluteVolumeChange = { value ->
            update { it.copy(noDvcForBluetoothAbsoluteVolume = value) }
        },
        onBluetoothAbsoluteVolumeEnabledChange = { value ->
            update { it.copy(bluetoothAbsoluteVolumeEnabled = value) }
        },
        onRestoreDefaults = {
            onChange(
                AudiophileAudioReducer.resetSection(
                    state,
                    AudiophileSettingsSection.DIRECT_VOLUME_CONTROL,
                ),
            )
        },
    )
}

private fun bitPerfectCallbacks(
    state: AudiophileAudioState,
    uiState: AudiophileAudioUiState,
    onChange: (AudiophileAudioState) -> Unit,
    onUiStateChange: (AudiophileAudioUiState) -> Unit,
): BitPerfectSettingsCallbacks {
    fun update(update: (BitPerfectSettingsState) -> BitPerfectSettingsState) {
        onChange(AudiophileAudioReducer.updateBitPerfect(state, update))
    }
    return BitPerfectSettingsCallbacks(
        onPipeline64EnabledChange = { value -> update { it.copy(pipeline64Enabled = value) } },
        onFollowTrackSampleRateChange = { value -> update { it.copy(followTrackSampleRate = value) } },
        onDsdRemasteringEnabledChange = { value -> update { it.copy(dsdRemasteringEnabled = value) } },
        onDsdRateChange = { value -> update { it.copy(dsdRate = value) } },
        onDsdRemaster48kFamilyEnabledChange = { value ->
            update { it.copy(dsdRemaster48kFamilyEnabled = value) }
        },
        onDsdRemasterGainDbChange = { value -> update { it.copy(dsdRemasterGainDb = value) } },
        onPerfectBitPerfectEnabledChange = { value ->
            update { it.copy(perfectBitPerfectEnabled = value) }
        },
        onShowMoreChange = { value -> onUiStateChange(uiState.copy(bitPerfectShowMore = value)) },
        onRestoreDefaults = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.BIT_PERFECT))
        },
    )
}

private fun outputCallbacks(
    state: AudiophileAudioState,
    uiState: AudiophileAudioUiState,
    onChange: (AudiophileAudioState) -> Unit,
    onUiStateChange: (AudiophileAudioUiState) -> Unit,
): OutputSettingsCallbacks {
    fun profile(
        routeId: AudioOutputRouteId,
        profileId: String,
        update: (OutputDeviceProfile) -> OutputDeviceProfile,
    ) {
        onChange(AudiophileAudioReducer.updateOutputProfile(state, routeId, profileId, update))
    }
    fun bitPerfect(
        routeId: AudioOutputRouteId,
        profileId: String,
        update: (BitPerfectSettingsState) -> BitPerfectSettingsState,
    ) {
        onChange(AudiophileAudioReducer.updateOutputBitPerfect(state, routeId, profileId, update))
    }
    return OutputSettingsCallbacks(
        onRouteEnabledChange = { routeId, value ->
            onChange(
                AudiophileAudioReducer.updateOutputRoute(state, routeId) { it.copy(enabled = value) },
            )
        },
        onProfileEnabledChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(enabled = value) }
        },
        onSampleRateHzChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(sampleRateHz = value) }
        },
        onSampleFormatChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(sampleFormat = value) }
        },
        onBufferPresetChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(bufferPreset = value) }
        },
        onBufferMsChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(bufferMs = value, bufferPreset = OutputBufferPreset.CUSTOM) }
        },
        onBufferCountChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(bufferCount = value, bufferPreset = OutputBufferPreset.CUSTOM) }
        },
        onPostFadeBufferCountChange = { routeId, profileId, value ->
            profile(routeId, profileId) {
                it.copy(postFadeBufferCount = value, bufferPreset = OutputBufferPreset.CUSTOM)
            }
        },
        onVisualizationLyricsDelayMsChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(visualizationLyricsDelayMs = value) }
        },
        onNoDvcChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(noDvc = value) }
        },
        onNoHeadroomGainChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(noHeadroomGain = value) }
        },
        onNoEquToneChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(noEquTone = value) }
        },
        onNoDuckChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(noDuck = value) }
        },
        onEqualizerPresetIdChange = { routeId, profileId, value ->
            profile(routeId, profileId) { it.copy(equalizerPresetId = value) }
        },
        onPipeline64EnabledChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(pipeline64Enabled = value) }
        },
        onFollowTrackSampleRateChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(followTrackSampleRate = value) }
        },
        onDsdRemasteringEnabledChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(dsdRemasteringEnabled = value) }
        },
        onDsdRateChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(dsdRate = value) }
        },
        onDsdRemaster48kFamilyEnabledChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(dsdRemaster48kFamilyEnabled = value) }
        },
        onDsdRemasterGainDbChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(dsdRemasterGainDb = value) }
        },
        onPerfectBitPerfectEnabledChange = { routeId, profileId, value ->
            bitPerfect(routeId, profileId) { it.copy(perfectBitPerfectEnabled = value) }
        },
        onNamedDeviceNameChange = { value -> onUiStateChange(uiState.copy(namedDeviceName = value)) },
        onAddNamedDevice = { routeId, name ->
            onChange(AudiophileAudioReducer.addNamedDevice(state, routeId, name))
            if (name.isNotBlank()) onUiStateChange(uiState.copy(namedDeviceName = ""))
        },
        onRestoreRouteDefaults = { routeId ->
            onChange(AudiophileAudioReducer.resetOutputRoute(state, routeId))
        },
        onRestoreProfileDefaults = { routeId, profileId ->
            onChange(AudiophileAudioReducer.resetOutputProfile(state, routeId, profileId))
        },
        onRestoreAllOutputs = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.OUTPUTS))
        },
    )
}

private fun advancedTweaksCallbacks(
    state: AudiophileAudioState,
    onChange: (AudiophileAudioState) -> Unit,
    onResetEqualizerPresets: () -> Unit,
    onResetReverbPresets: () -> Unit,
): AdvancedTweaksCallbacks {
    fun update(update: (AdvancedTweaksState) -> AdvancedTweaksState) {
        onChange(AudiophileAudioReducer.updateAdvancedTweaks(state, update))
    }
    return AdvancedTweaksCallbacks(
        onReferenceModeEnabledChange = { value -> update { it.copy(referenceModeEnabled = value) } },
        onReferenceModeChange = { value -> update { it.copy(referenceMode = value) } },
        onUsbVolumeLockEnabledChange = { value -> update { it.copy(usbVolumeLockEnabled = value) } },
        onUsbGainCompensationEnabledChange = { value ->
            update { it.copy(usbGainCompensationEnabled = value) }
        },
        onUsbGainCompensationDbChange = { value -> update { it.copy(usbGainCompensationDb = value) } },
        onVolumeLevelsChange = { value -> update { it.copy(volumeLevels = value) } },
        onVolumeStepsChange = { value -> update { it.copy(volumeSteps = value) } },
        onMusicFxEnabledChange = { value -> update { it.copy(musicFxEnabled = value) } },
        onForceSpeakerOffChange = { value -> update { it.copy(forceSpeakerOff = value) } },
        onForceAudioOnFocusChange = { value -> update { it.copy(forceAudioOnFocusChange = value) } },
        onEmulatedMediaStreamChange = { value -> update { it.copy(emulatedMediaStream = value) } },
        onAudioThreadPriorityChange = { value -> update { it.copy(audioThreadPriority = value) } },
        onResetEqualizerPresets = onResetEqualizerPresets,
        onResetReverbPresets = onResetReverbPresets,
        onRestoreDefaults = {
            onChange(AudiophileAudioReducer.resetSection(state, AudiophileSettingsSection.ADVANCED_TWEAKS))
        },
    )
}
