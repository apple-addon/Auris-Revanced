package com.music.bitchord.ui.audiophile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiophileAudioReducerTest {
    @Test
    fun transitionDefaultsMatchPowerampFactoryValues() {
        val state = CrossfadeFadeGaplessState()
        assertEquals(AutoAdvanceFading.NO_FADING, state.autoAdvanceFading)
        assertEquals(ManualTrackChangeFading.SHORT_CROSSFADE, state.manualTrackChangeFading)
        assertEquals(5_000, state.crossfadeLengthMs)
        assertEquals(400, state.shortManualCrossfadeLengthMs)
        assertTrue(state.fadeOnPlayPauseStop)
        assertEquals(400, state.playPauseStopFadeLengthMs)
        assertFalse(state.fadeOnSeek)
        assertEquals(100, state.seekFadeLengthMs)
        assertTrue(state.gaplessEnabled)
        assertTrue(state.cutSilenceEnabled)
        assertEquals(0, state.preloadGaplessTracksMs)
    }

    @Test
    fun processingAndVolumeDefaultsAreExact() {
        val state = AudiophileAudioState()
        assertFalse(state.qualityOptionsEnabled)
        assertEquals(ReplayGainSource.ALBUM, state.replayGain.source)
        assertEquals(
            listOf(ReplayGainSource.TRACK, ReplayGainSource.ALBUM, ReplayGainSource.SMART),
            ReplayGainSource.entries,
        )
        assertTrue(state.audioFocus.pauseInCall)
        assertTrue(state.audioFocus.navigationDuckingEnabled)
        assertFalse(state.audioFocus.resumeOnReopen)
        assertTrue(state.audioFocus.waitForStorage)
        assertFloat(0f, state.replayGain.rgPreampDb)
        assertFloat(0f, state.replayGain.noRgPreampDb)
        assertEquals(10, state.equalizer.bandCount)
        assertEquals(AudiophileEqualizerMode.GRAPHIC, state.equalizer.mode)
        assertFloat(97f, state.resampler.cutoffFrequencyPercent)
        assertEquals(DitherMode.NONE, state.resampler.dither)
        assertTrue(state.directVolumeControl.dvcEnabled)
        assertFalse(state.directVolumeControl.volumeCompensationEnabled)
        assertFloat(-6f, state.directVolumeControl.noDvcHeadroomGainDb)
        assertTrue(state.directVolumeControl.noDvcForBluetoothAbsoluteVolume)
        assertFloat(-2f, state.bitPerfect.dsdRemasterGainDb)
        assertFalse(state.bitPerfect.perfectBitPerfectEnabled)
        assertFalse(state.bitPerfect.dsdRemaster48kFamilyEnabled)
        assertFalse(state.advancedTweaks.referenceModeEnabled)
        assertEquals(ReferenceMode.POWERAMP, state.advancedTweaks.referenceMode)
        assertEquals(0, state.advancedTweaks.volumeSteps)
        assertFalse(state.advancedTweaks.musicFxEnabled)
        assertFalse(state.advancedTweaks.usbVolumeLockEnabled)
    }

    @Test
    fun sectionResetRestoresOnlyThatSection() {
        val original = AudiophileAudioState(
            qualityOptionsEnabled = true,
            crossfadeFadeGapless = CrossfadeFadeGaplessState(
                autoAdvanceFading = AutoAdvanceFading.CROSSFADE,
                crossfadeLengthMs = 9_000,
                fadeOnSeek = true,
                preloadGaplessTracksMs = 4_000,
            ),
            replayGain = ReplayGainState(source = ReplayGainSource.TRACK, rgPreampDb = 7f),
            resampler = ResamplerSettingsState(type = ResamplerType.SOX, dither = DitherMode.SHIBATA),
            advancedTweaks = AdvancedTweaksState(referenceModeEnabled = true),
        )
        val reset = AudiophileAudioReducer.resetSection(
            original,
            AudiophileSettingsSection.CROSSFADE_FADE_GAPLESS,
        )

        assertEquals(CrossfadeFadeGaplessState(), reset.crossfadeFadeGapless)
        assertEquals(original.replayGain, reset.replayGain)
        assertEquals(original.resampler, reset.resampler)
        assertEquals(original.advancedTweaks, reset.advancedTweaks)
        assertTrue(reset.qualityOptionsEnabled)
        assertEquals(9_000, original.crossfadeFadeGapless.crossfadeLengthMs)
        assertNotSame(original, reset)
    }

    @Test
    fun everySectionCanResetWithoutTouchingQualityChoice() {
        val original = AudiophileAudioState(
            qualityOptionsEnabled = true,
            crossfadeFadeGapless = CrossfadeFadeGaplessState(fadeOnSeek = true),
            replayGain = ReplayGainState(source = ReplayGainSource.TRACK),
            audioFocus = AudioFocusState(duckVolumePercent = 55),
            equalizer = EqualizerSettingsState(bandCount = 32),
            resampler = ResamplerSettingsState(type = ResamplerType.SOX),
            directVolumeControl = DirectVolumeControlState(dvcEnabled = false),
            bitPerfect = BitPerfectSettingsState(pipeline64Enabled = true),
            advancedTweaks = AdvancedTweaksState(referenceModeEnabled = true),
        )
        val sections = AudiophileSettingsSection.entries.filterNot {
            it == AudiophileSettingsSection.OUTPUTS || it == AudiophileSettingsSection.QUALITY_OPTIONS
        }
        sections.forEach { section ->
            val reset = AudiophileAudioReducer.resetSection(original, section)
            assertTrue("Quality Options changed by $section", reset.qualityOptionsEnabled)
        }
        val qualityReset = AudiophileAudioReducer.resetSection(
            original,
            AudiophileSettingsSection.QUALITY_OPTIONS,
        )
        assertFalse(qualityReset.qualityOptionsEnabled)
        assertEquals(original.replayGain, qualityReset.replayGain)
        assertEquals(AudiophileAudioState(), AudiophileAudioReducer.resetAll())
    }

    @Test
    fun replayGainSmartSourceDoesNotChangeGainPolicy() {
        val original = AudiophileAudioState(
            replayGain = ReplayGainState(mode = ReplayGainMode.APPLY_GAIN, rgPreampDb = 3f),
        )
        val changed = AudiophileAudioReducer.updateReplayGain(original) {
            it.copy(source = ReplayGainSource.SMART)
        }
        assertEquals(ReplayGainSource.SMART, changed.replayGain.source)
        assertEquals(ReplayGainMode.APPLY_GAIN, changed.replayGain.mode)
        assertFloat(3f, changed.replayGain.rgPreampDb)
    }

    @Test
    fun audioFocusPoliciesAndResumeControlsUpdateIndependently() {
        val original = AudiophileAudioState()
        val changed = AudiophileAudioReducer.updateAudioFocus(original) {
            it.copy(
                pauseInCall = false,
                shortFocusDuckPolicy = AudioFocusDuckPolicy.PAUSE,
                notificationDuckPolicy = AudioFocusDuckPolicy.IGNORE,
                navigationDuckingEnabled = false,
                resumeOnStart = true,
                resumeOnReopen = true,
                waitForStorage = false,
            )
        }
        assertFalse(changed.audioFocus.pauseInCall)
        assertEquals(AudioFocusDuckPolicy.PAUSE, changed.audioFocus.shortFocusDuckPolicy)
        assertEquals(AudioFocusDuckPolicy.IGNORE, changed.audioFocus.notificationDuckPolicy)
        assertFalse(changed.audioFocus.navigationDuckingEnabled)
        assertTrue(changed.audioFocus.resumeOnStart)
        assertTrue(changed.audioFocus.resumeOnReopen)
        assertFalse(changed.audioFocus.waitForStorage)
        assertTrue(changed.audioFocus.duckVolumeEnabled)
        assertTrue(changed.audioFocus.shortAudioFocusChangeCalls)
        assertTrue(changed.audioFocus.resumeAfterCall)
        assertEquals(original.replayGain, changed.replayGain)
    }

    @Test
    fun dvcCompensationAndDsdFamilyPreserveNeighboringOptions() {
        val original = AudiophileAudioState(
            bitPerfect = BitPerfectSettingsState(
                dsdRemasteringEnabled = true,
                dsdRate = DsdRate.DSD128,
                dsdRemasterGainDb = -3f,
            ),
        )
        val dvc = AudiophileAudioReducer.updateDirectVolumeControl(original) {
            it.copy(volumeCompensationEnabled = true)
        }
        assertTrue(dvc.directVolumeControl.volumeCompensationEnabled)
        assertTrue(dvc.directVolumeControl.dvcEnabled)
        assertFloat(-6f, dvc.directVolumeControl.noDvcHeadroomGainDb)
        assertTrue(dvc.directVolumeControl.noDvcForBluetoothAbsoluteVolume)

        val dsd = AudiophileAudioReducer.updateBitPerfect(dvc) {
            it.copy(dsdRemaster48kFamilyEnabled = true)
        }
        assertTrue(dsd.bitPerfect.dsdRemaster48kFamilyEnabled)
        assertTrue(dsd.bitPerfect.dsdRemasteringEnabled)
        assertEquals(DsdRate.DSD128, dsd.bitPerfect.dsdRate)
        assertFloat(-3f, dsd.bitPerfect.dsdRemasterGainDb)
        assertTrue(dsd.directVolumeControl.volumeCompensationEnabled)
    }

    @Test
    fun advancedSelectorStepsAndMusicFxAreIndependent() {
        val original = AudiophileAudioState(
            advancedTweaks = AdvancedTweaksState(
                referenceModeEnabled = true,
                usbVolumeLockEnabled = true,
                volumeLevels = 100,
            ),
        )
        val changed = AudiophileAudioReducer.updateAdvancedTweaks(original) {
            it.copy(
                referenceMode = ReferenceMode.HYBRID,
                volumeSteps = 64,
                musicFxEnabled = true,
            )
        }
        assertTrue(changed.advancedTweaks.referenceModeEnabled)
        assertEquals(ReferenceMode.HYBRID, changed.advancedTweaks.referenceMode)
        assertEquals(100, changed.advancedTweaks.volumeLevels)
        assertEquals(64, changed.advancedTweaks.volumeSteps)
        assertTrue(changed.advancedTweaks.musicFxEnabled)
        assertTrue(changed.advancedTweaks.usbVolumeLockEnabled)
        val clamped = AudiophileAudioReducer.updateAdvancedTweaks(changed) {
            it.copy(volumeSteps = 999)
        }
        assertEquals(256, clamped.advancedTweaks.volumeSteps)
        assertEquals(ReferenceMode.HYBRID, clamped.advancedTweaks.referenceMode)
        val reset = AudiophileAudioReducer.resetSection(
            clamped,
            AudiophileSettingsSection.ADVANCED_TWEAKS,
        )
        assertEquals(AdvancedTweaksState(), reset.advancedTweaks)
        assertEquals(clamped.audioFocus, reset.audioFocus)
    }

    @Test
    fun perfectBitPerfectDoesNotDestructivelyChangeOtherSettings() {
        val original = AudiophileAudioState(
            crossfadeFadeGapless = CrossfadeFadeGaplessState(
                autoAdvanceFading = AutoAdvanceFading.CROSSFADE,
            ),
            replayGain = ReplayGainState(mode = ReplayGainMode.APPLY_GAIN),
            bitPerfect = BitPerfectSettingsState(
                dsdRemasteringEnabled = true,
                dsdRate = DsdRate.DSD256,
            ),
        )
        val changed = AudiophileAudioReducer.updateBitPerfect(original) {
            it.copy(perfectBitPerfectEnabled = true, pipeline64Enabled = true)
        }

        assertTrue(changed.bitPerfect.perfectBitPerfectEnabled)
        assertTrue(changed.bitPerfect.dsdRemasteringEnabled)
        assertEquals(DsdRate.DSD256, changed.bitPerfect.dsdRate)
        assertEquals(original.crossfadeFadeGapless, changed.crossfadeFadeGapless)
        assertEquals(original.replayGain, changed.replayGain)
    }

    @Test
    fun profileUpdateIsIndependentOfOtherFlagsRoutesAndSections() {
        val routeId = AudioOutputRouteId.HI_RES
        val profileId = OutputDeviceKind.BLUETOOTH.name.lowercase()
        val original = AudiophileAudioReducer.updateOutputProfile(
            AudiophileAudioState(replayGain = ReplayGainState(source = ReplayGainSource.TRACK)),
            routeId,
            profileId,
        ) {
            it.copy(
                noHeadroomGain = true,
                noEquTone = true,
                noDuck = true,
                bufferMs = 350,
                visualizationLyricsDelayMs = 120,
                bitPerfect = BitPerfectSettingsState(pipeline64Enabled = true),
            )
        }
        val otherRoute = original.outputs.first { it.id == AudioOutputRouteId.OPEN_SL_ES }
        val changed = AudiophileAudioReducer.updateOutputProfile(original, routeId, profileId) {
            it.copy(noDvc = true)
        }
        val changedProfile = changed.outputs.first { it.id == routeId }.profiles.first { it.id == profileId }

        assertTrue(changedProfile.noDvc)
        assertTrue(changedProfile.noHeadroomGain)
        assertTrue(changedProfile.noEquTone)
        assertTrue(changedProfile.noDuck)
        assertEquals(350, changedProfile.bufferMs)
        assertEquals(120, changedProfile.visualizationLyricsDelayMs)
        assertTrue(changedProfile.bitPerfect.pipeline64Enabled)
        assertEquals(otherRoute, changed.outputs.first { it.id == AudioOutputRouteId.OPEN_SL_ES })
        assertEquals(original.replayGain, changed.replayGain)
        assertNotSame(original, changed)
    }

    @Test
    fun outputBitPerfectUpdatesOnlyTheSelectedProfile() {
        val original = AudiophileAudioState()
        val changed = AudiophileAudioReducer.updateOutputBitPerfect(
            original,
            AudioOutputRouteId.USB_EXCLUSIVE,
            OutputDeviceKind.USB_DAC.name.lowercase(),
        ) {
            it.copy(
                pipeline64Enabled = true,
                followTrackSampleRate = true,
                dsdRemasteringEnabled = true,
                dsdRate = DsdRate.DSD512,
                dsdRemaster48kFamilyEnabled = true,
            )
        }
        val target = changed.outputs.first { it.id == AudioOutputRouteId.USB_EXCLUSIVE }
            .profiles.first { it.kind == OutputDeviceKind.USB_DAC }
        assertTrue(target.bitPerfect.pipeline64Enabled)
        assertTrue(target.bitPerfect.followTrackSampleRate)
        assertEquals(DsdRate.DSD512, target.bitPerfect.dsdRate)
        assertTrue(target.bitPerfect.dsdRemaster48kFamilyEnabled)
        assertEquals(BitPerfectSettingsState(), changed.bitPerfect)
        changed.outputs.filterNot { it.id == AudioOutputRouteId.USB_EXCLUSIVE }.forEach { route ->
            assertTrue(route.profiles.all { it.bitPerfect == BitPerfectSettingsState() })
        }
    }

    @Test
    fun equalizerPresetAssignmentIsPerProfileAndResetKeepsCatalogue() {
        val routeId = AudioOutputRouteId.USB_EXCLUSIVE
        val profileId = OutputDeviceKind.USB_DAC.name.lowercase()
        val presets = listOf(
            EqualizerPresetOption("flat", "Flat"),
            EqualizerPresetOption("studio", "Studio"),
        )
        val original = AudiophileAudioReducer.updateOutputProfile(
            AudiophileAudioState(),
            routeId,
            profileId,
        ) {
            it.copy(availableEqualizerPresets = presets, noDvc = true)
        }
        val assigned = AudiophileAudioReducer.updateOutputProfile(original, routeId, profileId) {
            it.copy(equalizerPresetId = " studio ")
        }
        val target = assigned.outputs.first { it.id == routeId }.profiles.first { it.id == profileId }
        assertEquals("studio", target.equalizerPresetId)
        assertEquals(presets, target.availableEqualizerPresets)
        assertTrue(target.noDvc)
        assertTrue(assigned.outputs.first { it.id == AudioOutputRouteId.HI_RES }
            .profiles.all { it.equalizerPresetId == null })

        val reset = AudiophileAudioReducer.resetOutputProfile(assigned, routeId, profileId)
        val restored = reset.outputs.first { it.id == routeId }.profiles.first { it.id == profileId }
        assertEquals(null, restored.equalizerPresetId)
        assertEquals(presets, restored.availableEqualizerPresets)
    }

    @Test
    fun namedDevicesAreTrimmedUniqueAndIndependent() {
        val initial = AudiophileAudioState()
        val first = AudiophileAudioReducer.addNamedDevice(
            initial,
            AudioOutputRouteId.HI_RES,
            "  Studio DAC  ",
        )
        val second = AudiophileAudioReducer.addNamedDevice(
            first,
            AudioOutputRouteId.HI_RES,
            "Studio DAC",
        )
        val route = second.outputs.first { it.id == AudioOutputRouteId.HI_RES }
        val named = route.profiles.filter { it.kind == OutputDeviceKind.NAMED }

        assertEquals(listOf("named-1", "named-2"), named.map(OutputDeviceProfile::id))
        assertEquals(listOf("Studio DAC", "Studio DAC"), named.map(OutputDeviceProfile::name))
        assertTrue(named.all { it.connectionStatus == OutputConnectionStatus.DISCONNECTED })
        assertEquals(0, second.outputs.first { it.id == AudioOutputRouteId.OPEN_SL_ES }
            .profiles.count { it.kind == OutputDeviceKind.NAMED })
        assertSame(second, AudiophileAudioReducer.addNamedDevice(second, AudioOutputRouteId.HI_RES, "  "))
    }

    @Test
    fun profileResetUsesItsRouteDefaultsAndPreservesRuntimeIdentity() {
        val routeId = AudioOutputRouteId.HI_RES
        val profileId = OutputDeviceKind.WIRED_HEADSET.name.lowercase()
        val capabilities = listOf(48_000, 96_000, 192_000)
        val changed = AudiophileAudioReducer.updateOutputProfile(
            AudiophileAudioState(),
            routeId,
            profileId,
        ) {
            it.copy(
                name = "Studio headphones",
                connectionStatus = OutputConnectionStatus.ACTIVE,
                statusDetail = "Direct HD",
                supportedSampleRatesHz = capabilities,
                actualSampleRateHz = 96_000,
                sampleRateHz = 192_000,
                sampleFormat = OutputSampleFormat.PCM_16,
                noDvc = true,
                noHeadroomGain = true,
                noEquTone = true,
                noDuck = true,
                bufferPreset = OutputBufferPreset.CUSTOM,
                bufferMs = 700,
                bitPerfect = BitPerfectSettingsState(perfectBitPerfectEnabled = true),
            )
        }
        val reset = AudiophileAudioReducer.resetOutputProfile(changed, routeId, profileId)
        val profile = reset.outputs.first { it.id == routeId }.profiles.first { it.id == profileId }

        assertEquals(profileId, profile.id)
        assertEquals("Studio headphones", profile.name)
        assertEquals(OutputConnectionStatus.ACTIVE, profile.connectionStatus)
        assertEquals("Direct HD", profile.statusDetail)
        assertEquals(capabilities, profile.supportedSampleRatesHz)
        assertEquals(96_000, profile.actualSampleRateHz)
        assertEquals(48_000, profile.sampleRateHz)
        assertEquals(OutputSampleFormat.PCM_24, profile.sampleFormat)
        assertEquals(OutputBufferPreset.NORMAL, profile.bufferPreset)
        assertEquals(100, profile.bufferMs)
        assertFalse(profile.noDvc)
        assertFalse(profile.noHeadroomGain)
        assertFalse(profile.noEquTone)
        assertFalse(profile.noDuck)
        assertEquals(BitPerfectSettingsState(), profile.bitPerfect)
    }

    @Test
    fun routeResetRestoresBuiltInsAndKeepsNamedDeviceIdentity() {
        val named = AudiophileAudioDefaults.defaultProfile(
            AudioOutputRouteId.HI_RES,
            OutputDeviceKind.NAMED,
            "Living room DAC",
            "named-7",
        ).copy(noDvc = true, connectionStatus = OutputConnectionStatus.CONNECTED)
        val custom = AudiophileAudioState(
            outputs = listOf(
                AudioOutputRouteState(
                    id = AudioOutputRouteId.HI_RES,
                    enabled = false,
                    status = OutputConnectionStatus.ACTIVE,
                    statusDetail = "USB direct",
                    profiles = listOf(named),
                ),
            ),
        )
        val reset = AudiophileAudioReducer.resetOutputRoute(custom, AudioOutputRouteId.HI_RES)
        val route = reset.outputs.single()

        assertEquals(OutputConnectionStatus.ACTIVE, route.status)
        assertEquals("USB direct", route.statusDetail)
        assertEquals(6, route.profiles.size)
        assertEquals(5, route.profiles.count { it.kind != OutputDeviceKind.NAMED })
        val restoredNamed = route.profiles.single { it.kind == OutputDeviceKind.NAMED }
        assertEquals("named-7", restoredNamed.id)
        assertEquals("Living room DAC", restoredNamed.name)
        assertEquals(OutputConnectionStatus.CONNECTED, restoredNamed.connectionStatus)
        assertFalse(restoredNamed.noDvc)
        assertSame(custom, AudiophileAudioReducer.resetOutputRoute(custom, AudioOutputRouteId.AAUDIO))
    }

    @Test
    fun presetResetActionsUseIndependentCallbacksWithoutChangingSettings() {
        var updated: AudiophileAudioState? = null
        var settingsUpdates = 0
        var equalizerResets = 0
        var reverbResets = 0
        val callbacks = audiophileReducerCallbacks(
            settings = AudiophileAudioState(),
            uiState = AudiophileAudioUiState(),
            onSettingsChange = { updated = it; settingsUpdates += 1 },
            onUiStateChange = {},
            onExit = {},
            onResetEqualizerPresets = { equalizerResets += 1 },
            onResetReverbPresets = { reverbResets += 1 },
        )

        callbacks.advancedTweaks.onReferenceModeChange(ReferenceMode.BIT_PERFECT)
        assertEquals(ReferenceMode.BIT_PERFECT, updated?.advancedTweaks?.referenceMode)
        callbacks.advancedTweaks.onResetEqualizerPresets()
        callbacks.advancedTweaks.onResetReverbPresets()

        assertEquals(1, equalizerResets)
        assertEquals(1, reverbResets)
        assertEquals(1, settingsUpdates)
    }

    @Test
    fun invalidTargetsAndNumericValuesAreHandledWithoutSideEffects() {
        val original = AudiophileAudioState()
        assertSame(
            original,
            AudiophileAudioReducer.updateOutputProfile(original, AudioOutputRouteId.HI_RES, "missing") {
                it.copy(noDvc = true)
            },
        )
        val normalized = AudiophileAudioReducer.updateCrossfadeFadeGapless(original) {
            it.copy(crossfadeLengthMs = 99_999, shortManualCrossfadeLengthMs = 1)
        }
        assertEquals(15_000, normalized.crossfadeFadeGapless.crossfadeLengthMs)
        assertEquals(10, normalized.crossfadeFadeGapless.shortManualCrossfadeLengthMs)
        val gain = AudiophileAudioReducer.updateReplayGain(original) {
            it.copy(rgPreampDb = Float.NaN, noRgPreampDb = Float.POSITIVE_INFINITY)
        }
        assertFloat(0f, gain.replayGain.rgPreampDb)
        assertFloat(0f, gain.replayGain.noRgPreampDb)
    }

    private fun assertFloat(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0001f)
}
