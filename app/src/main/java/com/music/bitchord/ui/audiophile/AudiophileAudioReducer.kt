package com.music.bitchord.ui.audiophile

/**
 * Pure, atomic updates for settings. Runtime status, device identity and unrelated sections are
 * preserved. In particular, Perfect Bit Perfect does not destructively switch off DSP settings;
 * the engine may bypass them while leaving the user's choices intact for when it is disabled.
 */
object AudiophileAudioReducer {
    fun setQualityOptions(
        state: AudiophileAudioState,
        enabled: Boolean,
    ): AudiophileAudioState = state.copy(qualityOptionsEnabled = enabled)

    fun updateCrossfadeFadeGapless(
        state: AudiophileAudioState,
        update: (CrossfadeFadeGaplessState) -> CrossfadeFadeGaplessState,
    ): AudiophileAudioState = state.copy(
        crossfadeFadeGapless = update(state.crossfadeFadeGapless).normalized(),
    )

    fun updateReplayGain(
        state: AudiophileAudioState,
        update: (ReplayGainState) -> ReplayGainState,
    ): AudiophileAudioState = state.copy(replayGain = update(state.replayGain).normalized())

    fun updateAudioFocus(
        state: AudiophileAudioState,
        update: (AudioFocusState) -> AudioFocusState,
    ): AudiophileAudioState = state.copy(audioFocus = update(state.audioFocus).normalized())

    fun updateEqualizer(
        state: AudiophileAudioState,
        update: (EqualizerSettingsState) -> EqualizerSettingsState,
    ): AudiophileAudioState = state.copy(equalizer = update(state.equalizer).normalized())

    fun updateResampler(
        state: AudiophileAudioState,
        update: (ResamplerSettingsState) -> ResamplerSettingsState,
    ): AudiophileAudioState = state.copy(resampler = update(state.resampler).normalized())

    fun updateDirectVolumeControl(
        state: AudiophileAudioState,
        update: (DirectVolumeControlState) -> DirectVolumeControlState,
    ): AudiophileAudioState = state.copy(
        directVolumeControl = update(state.directVolumeControl).normalized(),
    )

    fun updateBitPerfect(
        state: AudiophileAudioState,
        update: (BitPerfectSettingsState) -> BitPerfectSettingsState,
    ): AudiophileAudioState = state.copy(bitPerfect = update(state.bitPerfect).normalized())

    fun updateAdvancedTweaks(
        state: AudiophileAudioState,
        update: (AdvancedTweaksState) -> AdvancedTweaksState,
    ): AudiophileAudioState = state.copy(advancedTweaks = update(state.advancedTweaks).normalized())

    fun updateOutputRoute(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
        update: (AudioOutputRouteState) -> AudioOutputRouteState,
    ): AudiophileAudioState {
        if (state.outputs.none { it.id == routeId }) return state
        return state.copy(
            outputs = state.outputs.map { route ->
                if (route.id == routeId) update(route) else route
            },
        )
    }

    fun updateOutputProfile(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
        profileId: String,
        update: (OutputDeviceProfile) -> OutputDeviceProfile,
    ): AudiophileAudioState {
        val route = state.outputs.firstOrNull { it.id == routeId } ?: return state
        if (route.profiles.none { it.id == profileId }) return state
        return state.copy(
            outputs = state.outputs.map { current ->
                if (current.id != routeId) {
                    current
                } else {
                    current.copy(
                        profiles = current.profiles.map { profile ->
                            if (profile.id == profileId) update(profile).normalized() else profile
                        },
                    )
                }
            },
        )
    }

    fun updateOutputBitPerfect(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
        profileId: String,
        update: (BitPerfectSettingsState) -> BitPerfectSettingsState,
    ): AudiophileAudioState = updateOutputProfile(state, routeId, profileId) { profile ->
        profile.copy(bitPerfect = update(profile.bitPerfect).normalized())
    }

    fun addNamedDevice(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
        name: String,
    ): AudiophileAudioState {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || state.outputs.none { it.id == routeId }) return state
        return updateOutputRoute(state, routeId) { route ->
            var suffix = route.profiles.count { it.kind == OutputDeviceKind.NAMED } + 1
            var id = "named-$suffix"
            while (route.profiles.any { it.id == id }) {
                suffix += 1
                id = "named-$suffix"
            }
            route.copy(
                profiles = route.profiles + AudiophileAudioDefaults.defaultProfile(
                    routeId = routeId,
                    kind = OutputDeviceKind.NAMED,
                    name = trimmedName,
                    id = id,
                ).copy(connectionStatus = OutputConnectionStatus.DISCONNECTED),
            )
        }
    }

    fun resetSection(
        state: AudiophileAudioState,
        section: AudiophileSettingsSection,
    ): AudiophileAudioState = when (section) {
        AudiophileSettingsSection.QUALITY_OPTIONS -> state.copy(qualityOptionsEnabled = false)
        AudiophileSettingsSection.CROSSFADE_FADE_GAPLESS -> state.copy(
            crossfadeFadeGapless = CrossfadeFadeGaplessState(),
        )
        AudiophileSettingsSection.REPLAY_GAIN -> state.copy(replayGain = ReplayGainState())
        AudiophileSettingsSection.AUDIO_FOCUS -> state.copy(audioFocus = AudioFocusState())
        AudiophileSettingsSection.EQUALIZER -> state.copy(equalizer = EqualizerSettingsState())
        AudiophileSettingsSection.RESAMPLER -> state.copy(resampler = ResamplerSettingsState())
        AudiophileSettingsSection.DIRECT_VOLUME_CONTROL -> state.copy(
            directVolumeControl = DirectVolumeControlState(),
        )
        AudiophileSettingsSection.OUTPUTS -> resetOutputs(state)
        AudiophileSettingsSection.BIT_PERFECT -> state.copy(bitPerfect = BitPerfectSettingsState())
        AudiophileSettingsSection.ADVANCED_TWEAKS -> state.copy(
            advancedTweaks = AdvancedTweaksState(),
        )
    }

    fun resetOutputs(state: AudiophileAudioState): AudiophileAudioState = state.copy(
        outputs = state.outputs.map { route -> route.resetDefaults() },
    )

    fun resetOutputRoute(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
    ): AudiophileAudioState = updateOutputRouteWithoutNormalizingOthers(state, routeId) {
        it.resetDefaults()
    }

    fun resetOutputProfile(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
        profileId: String,
    ): AudiophileAudioState = updateOutputProfile(state, routeId, profileId) {
        it.resetDefaults(routeId)
    }

    fun resetAll(): AudiophileAudioState = AudiophileAudioState()

    private fun updateOutputRouteWithoutNormalizingOthers(
        state: AudiophileAudioState,
        routeId: AudioOutputRouteId,
        update: (AudioOutputRouteState) -> AudioOutputRouteState,
    ): AudiophileAudioState {
        if (state.outputs.none { it.id == routeId }) return state
        return state.copy(
            outputs = state.outputs.map { if (it.id == routeId) update(it) else it },
        )
    }
}

fun CrossfadeFadeGaplessState.normalized(): CrossfadeFadeGaplessState = copy(
    crossfadeLengthMs = crossfadeLengthMs.coerceIn(1_000, 15_000),
    shortManualCrossfadeLengthMs = shortManualCrossfadeLengthMs.coerceIn(10, 5_000),
    playPauseStopFadeLengthMs = playPauseStopFadeLengthMs.coerceIn(0, 500),
    seekFadeLengthMs = seekFadeLengthMs.coerceIn(0, 500),
    preloadGaplessTracksMs = preloadGaplessTracksMs.coerceIn(0, 10_000),
)

fun ReplayGainState.normalized(): ReplayGainState = copy(
    rgPreampDb = rgPreampDb.finiteOr(0f).coerceIn(-15f, 15f),
    noRgPreampDb = noRgPreampDb.finiteOr(0f).coerceIn(-15f, 15f),
)

fun AudioFocusState.normalized(): AudioFocusState = copy(
    duckVolumePercent = duckVolumePercent.coerceIn(0, 100),
)

fun EqualizerSettingsState.normalized(): EqualizerSettingsState = copy(
    bandCount = bandCount.coerceIn(5, 32),
    customBandCount = customBandCount.coerceIn(5, 32),
    firstFrequencyHz = firstFrequencyHz.coerceIn(5, 24_000),
    lastFrequencyHz = lastFrequencyHz.coerceIn(5, 24_000),
)

fun ResamplerSettingsState.normalized(): ResamplerSettingsState = copy(
    cutoffFrequencyPercent = cutoffFrequencyPercent.finiteOr(97f).coerceIn(80f, 99.7f),
)

fun DirectVolumeControlState.normalized(): DirectVolumeControlState = copy(
    noDvcHeadroomGainDb = noDvcHeadroomGainDb.finiteOr(-6f).coerceIn(-12f, 0f),
)

fun BitPerfectSettingsState.normalized(): BitPerfectSettingsState = copy(
    dsdRemasterGainDb = dsdRemasterGainDb.finiteOr(-2f).coerceIn(-6f, 0f),
)

fun AdvancedTweaksState.normalized(): AdvancedTweaksState = copy(
    usbGainCompensationDb = usbGainCompensationDb.finiteOr(0f).coerceIn(-12f, 12f),
    volumeLevels = volumeLevels.coerceIn(0, 256),
    volumeSteps = volumeSteps.coerceIn(0, 256),
    audioThreadPriority = audioThreadPriority.coerceIn(-2, 3),
)

fun AudioOutputRouteState.normalized(): AudioOutputRouteState = copy(
    profiles = profiles.map(OutputDeviceProfile::normalized),
)

fun OutputDeviceProfile.normalized(): OutputDeviceProfile = copy(
    name = name.trim().ifEmpty { kind.label },
    supportedSampleRatesHz = supportedSampleRatesHz
        .filter { it in 8_000..768_000 }
        .distinct()
        .sorted(),
    sampleRateHz = sampleRateHz.coerceIn(8_000, 768_000),
    actualSampleRateHz = actualSampleRateHz?.coerceIn(8_000, 768_000),
    bufferMs = bufferMs.coerceIn(10, 2_000),
    bufferCount = bufferCount.coerceIn(1, 16),
    postFadeBufferCount = postFadeBufferCount.coerceIn(1, 16),
    visualizationLyricsDelayMs = visualizationLyricsDelayMs.coerceIn(-5_000, 5_000),
    equalizerPresetId = equalizerPresetId?.trim()?.ifEmpty { null },
    availableEqualizerPresets = availableEqualizerPresets.map { preset ->
        val id = preset.id.trim()
        preset.copy(id = id, name = preset.name.trim().ifEmpty { id })
    }.filter { it.id.isNotEmpty() }.distinctBy(EqualizerPresetOption::id),
    bitPerfect = bitPerfect.normalized(),
)

/** Restores configuration while retaining runtime status, capabilities and named-device identity. */
fun OutputDeviceProfile.resetDefaults(routeId: AudioOutputRouteId): OutputDeviceProfile {
    val defaults = AudiophileAudioDefaults.defaultProfile(routeId, kind, name, id)
    return copy(
        enabled = kind.defaultEnabled,
        sampleRateHz = defaults.sampleRateHz,
        sampleFormat = defaults.sampleFormat,
        bufferPreset = defaults.bufferPreset,
        bufferMs = defaults.bufferMs,
        bufferCount = defaults.bufferCount,
        postFadeBufferCount = defaults.postFadeBufferCount,
        visualizationLyricsDelayMs = defaults.visualizationLyricsDelayMs,
        noDvc = defaults.noDvc,
        noHeadroomGain = defaults.noHeadroomGain,
        noEquTone = defaults.noEquTone,
        noDuck = defaults.noDuck,
        equalizerPresetId = null,
        bitPerfect = BitPerfectSettingsState(),
    )
}

fun AudioOutputRouteState.resetDefaults(): AudioOutputRouteState {
    val routeDefaults = AudiophileAudioDefaults.defaultRoute(id)
    val restoredBuiltIns = routeDefaults.profiles.map { defaultProfile ->
        profiles.firstOrNull { it.id == defaultProfile.id }?.resetDefaults(id) ?: defaultProfile
    }
    val namedProfiles = profiles.filter { profile ->
        profile.kind == OutputDeviceKind.NAMED && restoredBuiltIns.none { it.id == profile.id }
    }.map { profile -> profile.resetDefaults(id) }
    return copy(
        enabled = routeDefaults.enabled,
        profiles = restoredBuiltIns + namedProfiles,
    )
}

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
