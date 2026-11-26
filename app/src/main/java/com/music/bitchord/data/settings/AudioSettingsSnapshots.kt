package com.music.bitchord.data.settings

import com.music.bitchord.ui.audiophile.AdvancedTweaksState
import com.music.bitchord.ui.audiophile.AudioFocusState
import com.music.bitchord.ui.audiophile.AudioFocusDuckPolicy
import com.music.bitchord.ui.audiophile.AudioOutputRouteId
import com.music.bitchord.ui.audiophile.AudioOutputRouteState
import com.music.bitchord.ui.audiophile.AudiophileAudioDefaults
import com.music.bitchord.ui.audiophile.AudiophileAudioState
import com.music.bitchord.ui.audiophile.AudiophileEqualizerMode
import com.music.bitchord.ui.audiophile.AutoAdvanceFading
import com.music.bitchord.ui.audiophile.BitPerfectSettingsState
import com.music.bitchord.ui.audiophile.CrossfadeFadeGaplessState
import com.music.bitchord.ui.audiophile.DirectVolumeControlState
import com.music.bitchord.ui.audiophile.DitherMode
import com.music.bitchord.ui.audiophile.DsdRate
import com.music.bitchord.ui.audiophile.EqualizerLayout
import com.music.bitchord.ui.audiophile.EqualizerSettingsState
import com.music.bitchord.ui.audiophile.ManualTrackChangeFading
import com.music.bitchord.ui.audiophile.OutputBufferPreset
import com.music.bitchord.ui.audiophile.OutputDeviceKind
import com.music.bitchord.ui.audiophile.OutputDeviceProfile
import com.music.bitchord.ui.audiophile.OutputSampleFormat
import com.music.bitchord.ui.audiophile.ReplayGainMode
import com.music.bitchord.ui.audiophile.ReplayGainSource
import com.music.bitchord.ui.audiophile.ReplayGainState
import com.music.bitchord.ui.audiophile.ReferenceMode
import com.music.bitchord.ui.audiophile.ResamplerPhase
import com.music.bitchord.ui.audiophile.ResamplerQuality
import com.music.bitchord.ui.audiophile.ResamplerSettingsState
import com.music.bitchord.ui.audiophile.ResamplerType
import com.music.bitchord.ui.audiophile.normalized
import com.music.bitchord.ui.reference.MsebState
import com.music.bitchord.ui.reference.PeqBand
import com.music.bitchord.ui.reference.PeqDefaults
import com.music.bitchord.ui.reference.PeqFilterType
import com.music.bitchord.ui.reference.PeqState
import com.music.bitchord.ui.reference.PowerampEqBand
import com.music.bitchord.ui.reference.PowerampEqDefaults
import com.music.bitchord.ui.reference.PowerampEqState
import com.music.bitchord.ui.reference.PowerampReverbState
import com.music.bitchord.ui.reference.normalized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Versioned, all-or-default JSON snapshots. Runtime route status is never persisted. */
private val snapshotJson = Json { ignoreUnknownKeys = true }

internal fun encodePowerampEqSnapshot(state: PowerampEqState): String = snapshotJson.encodeToString(
    JsonObject.serializer(),
    JsonObject(
        mapOf(
            "v" to 1.json,
            "equ" to state.equEnabled.json,
            "tone" to state.toneEnabled.json,
            "limiter" to state.limiterEnabled.json,
            "parametric" to state.parametricMode.json,
            "preamp" to state.preampDb.json,
            "bands" to JsonArray(state.normalized().bands.map { band ->
                JsonObject(mapOf(
                    "f" to band.frequencyHz.json,
                    "g" to band.gainDb.json,
                    "q" to band.q.json,
                    "on" to band.enabled.json,
                ))
            }),
            "bass" to state.bassDb.json,
            "treble" to state.trebleDb.json,
            "balance" to state.balance.json,
            "width" to state.stereoWidth.json,
            "tempo" to state.tempo.json,
            "mono" to state.monoEnabled.json,
            "volume" to state.volume.json,
            "reverb" to state.reverb.let { reverb ->
                JsonObject(mapOf(
                    "on" to reverb.enabled.json,
                    "damp" to reverb.damp.json,
                    "filter" to reverb.filter.json,
                    "fade" to reverb.fade.json,
                    "delay" to reverb.preDelay.json,
                    "delayMix" to reverb.preDelayMix.json,
                    "size" to reverb.size.json,
                    "mix" to reverb.mix.json,
                ))
            },
        ),
    ),
)

internal fun decodePowerampEqSnapshot(encoded: String?): PowerampEqState = runCatching {
    val root = encoded?.jsonRoot() ?: return PowerampEqState()
    root.requireVersion()
    PowerampEqState(
        equEnabled = root.boolean("equ", true),
        toneEnabled = root.boolean("tone", true),
        limiterEnabled = root.boolean("limiter", false),
        parametricMode = root.boolean("parametric", false),
        preampDb = root.float("preamp", 0f),
        bands = root.array("bands")?.take(PowerampEqDefaults.BAND_COUNT)?.mapIndexed { index, element ->
            val band = element.jsonObject
            PowerampEqBand(
                frequencyHz = band.float("f", PowerampEqDefaults.frequenciesHz[index]),
                gainDb = band.float("g", 0f),
                q = band.float("q", 1f),
                enabled = band.boolean("on", true),
            )
        } ?: PowerampEqDefaults.bands,
        bassDb = root.float("bass", 0f),
        trebleDb = root.float("treble", 0f),
        balance = root.float("balance", 0f),
        stereoWidth = root.float("width", 0f),
        tempo = root.float("tempo", 1f),
        monoEnabled = root.boolean("mono", false),
        volume = root.float("volume", 1f),
        reverb = root.objectOrNull("reverb")?.let { reverb ->
            PowerampReverbState(
                enabled = reverb.boolean("on", false),
                damp = reverb.float("damp", 0f),
                filter = reverb.float("filter", 0f),
                fade = reverb.float("fade", 0f),
                preDelay = reverb.float("delay", 0f),
                preDelayMix = reverb.float("delayMix", 0f),
                size = reverb.float("size", 0f),
                mix = reverb.float("mix", 0f),
            )
        } ?: PowerampReverbState(),
    ).normalized()
}.getOrDefault(PowerampEqState())

internal fun encodeMsebSnapshot(state: MsebState): String = snapshotJson.encodeToString(
    JsonObject.serializer(),
    JsonObject(mapOf(
        "v" to 1.json,
        "on" to state.enabled.json,
        "name" to state.presetName.json,
        "preamp" to state.preampDb.json,
        "range" to state.range.json,
        "values" to JsonArray(state.normalized().values.map { it.json }),
    )),
)

internal fun decodeMsebSnapshot(encoded: String?): MsebState = runCatching {
    val root = encoded?.jsonRoot() ?: return MsebState()
    root.requireVersion()
    MsebState(
        enabled = root.boolean("on", true),
        presetName = root.string("name", "Custom"),
        preampDb = root.float("preamp", 0f),
        range = root.int("range", 0),
        values = root.array("values")?.map { it.floatOrNull() ?: 0f } ?: MsebState().values,
    ).normalized()
}.getOrDefault(MsebState())

internal fun encodePeqSnapshot(state: PeqState): String = snapshotJson.encodeToString(
    JsonObject.serializer(),
    JsonObject(mapOf(
        "v" to 1.json,
        "on" to state.enabled.json,
        "name" to state.presetName.json,
        "preamp" to state.preampDb.json,
        "bands" to JsonArray(state.normalized().bands.map { band ->
            JsonObject(mapOf(
                "f" to band.frequencyHz.json,
                "g" to band.gainDb.json,
                "q" to band.q.json,
                "type" to band.type.name.json,
                "on" to band.enabled.json,
            ))
        }),
    )),
)

internal fun decodePeqSnapshot(encoded: String?): PeqState = runCatching {
    val root = encoded?.jsonRoot() ?: return PeqState()
    root.requireVersion()
    PeqState(
        enabled = root.boolean("on", true),
        presetName = root.string("name", "Custom"),
        preampDb = root.float("preamp", 0f),
        bands = root.array("bands")?.take(PeqDefaults.BAND_COUNT)?.mapIndexed { index, element ->
            val band = element.jsonObject
            PeqBand(
                frequencyHz = band.float("f", PeqDefaults.frequenciesHz[index]),
                gainDb = band.float("g", 0f),
                q = band.float("q", 0.2f),
                type = band.enumValue<PeqFilterType>("type", PeqFilterType.PEAK),
                enabled = band.boolean("on", true),
            )
        } ?: PeqDefaults.bands,
    ).normalized()
}.getOrDefault(PeqState())

internal fun encodeAudiophileSnapshot(state: AudiophileAudioState): String = snapshotJson.encodeToString(
    JsonObject.serializer(),
    JsonObject(mapOf(
        "v" to 1.json,
        "quality" to state.qualityOptionsEnabled.json,
        "crossfade" to state.crossfadeFadeGapless.let { value ->
            JsonObject(mapOf(
                "auto" to value.autoAdvanceFading.name.json,
                "manual" to value.manualTrackChangeFading.name.json,
                "length" to value.crossfadeLengthMs.json,
                "short" to value.shortManualCrossfadeLengthMs.json,
                "transport" to value.fadeOnPlayPauseStop.json,
                "transportMs" to value.playPauseStopFadeLengthMs.json,
                "seek" to value.fadeOnSeek.json,
                "seekMs" to value.seekFadeLengthMs.json,
                "gapless" to value.gaplessEnabled.json,
                "cut" to value.cutSilenceEnabled.json,
                "preload" to value.preloadGaplessTracksMs.json,
            ))
        },
        "replayGain" to state.replayGain.let { value ->
            JsonObject(mapOf(
                "mode" to value.mode.name.json,
                "source" to value.source.name.json,
                "rgPreamp" to value.rgPreampDb.json,
                "noRgPreamp" to value.noRgPreampDb.json,
            ))
        },
        "focus" to state.audioFocus.let { value ->
            JsonObject(mapOf(
                "pauseInCall" to value.pauseInCall.json,
                "calls" to value.shortAudioFocusChangeCalls.json,
                "duck" to value.duckVolumeEnabled.json,
                "shortDuck" to value.shortFocusDuckPolicy.name.json,
                "notificationDuck" to value.notificationDuckPolicy.name.json,
                "navigationDuck" to value.navigationDuckingEnabled.json,
                "duckPercent" to value.duckVolumePercent.json,
                "permanent" to value.permanentAudioFocusChange.json,
                "afterCall" to value.resumeAfterCall.json,
                "onStart" to value.resumeOnStart.json,
                "onReopen" to value.resumeOnReopen.json,
                "onGain" to value.resumeOnFocusGain.json,
                "waitStorage" to value.waitForStorage.json,
            ))
        },
        "equalizer" to state.equalizer.let { value ->
            JsonObject(mapOf(
                "count" to value.bandCount.json,
                "mode" to value.mode.name.json,
                "custom" to value.customBandsEnabled.json,
                "customCount" to value.customBandCount.json,
                "first" to value.firstFrequencyHz.json,
                "last" to value.lastFrequencyHz.json,
                "layout" to value.layout.name.json,
            ))
        },
        "resampler" to state.resampler.let { value ->
            JsonObject(mapOf(
                "type" to value.type.name.json,
                "quality" to value.quality.name.json,
                "cutoff" to value.cutoffFrequencyPercent.json,
                "phase" to value.phase.name.json,
                "steep" to value.steepFilterEnabled.json,
                "dither" to value.dither.name.json,
            ))
        },
        "dvc" to state.directVolumeControl.let { value ->
            JsonObject(mapOf(
                "on" to value.dvcEnabled.json,
                "compensation" to value.volumeCompensationEnabled.json,
                "headroom" to value.noDvcHeadroomGainDb.json,
                "noBt" to value.noDvcForBluetoothAbsoluteVolume.json,
                "bt" to value.bluetoothAbsoluteVolumeEnabled.json,
            ))
        },
        "bitPerfect" to encodeBitPerfect(state.bitPerfect),
        "advanced" to state.advancedTweaks.let { value ->
            JsonObject(mapOf(
                "reference" to value.referenceModeEnabled.json,
                "referenceMode" to value.referenceMode.name.json,
                "usbLock" to value.usbVolumeLockEnabled.json,
                "gainOn" to value.usbGainCompensationEnabled.json,
                "gain" to value.usbGainCompensationDb.json,
                "levels" to value.volumeLevels.json,
                "steps" to value.volumeSteps.json,
                "musicFx" to value.musicFxEnabled.json,
                "speakerOff" to value.forceSpeakerOff.json,
                "focusAudio" to value.forceAudioOnFocusChange.json,
                "emulated" to value.emulatedMediaStream.json,
                "thread" to value.audioThreadPriority.json,
            ))
        },
        "outputs" to JsonArray(state.outputs.map { route ->
            JsonObject(mapOf(
                "id" to route.id.name.json,
                "on" to route.enabled.json,
                "profiles" to JsonArray(route.profiles.map { profile -> encodeOutputProfile(profile) }),
            ))
        }),
    )),
)

internal fun decodeAudiophileSnapshot(encoded: String?): AudiophileAudioState = runCatching {
    val root = encoded?.jsonRoot() ?: return AudiophileAudioState()
    root.requireVersion()
    val defaults = AudiophileAudioState()
    val crossfade = root.objectOrNull("crossfade")
    val replayGain = root.objectOrNull("replayGain")
    val focus = root.objectOrNull("focus")
    val equalizer = root.objectOrNull("equalizer")
    val resampler = root.objectOrNull("resampler")
    val dvc = root.objectOrNull("dvc")
    val advanced = root.objectOrNull("advanced")
    AudiophileAudioState(
        qualityOptionsEnabled = root.boolean("quality", false),
        crossfadeFadeGapless = CrossfadeFadeGaplessState(
            autoAdvanceFading = crossfade.enumValue("auto", AutoAdvanceFading.NO_FADING),
            manualTrackChangeFading = crossfade.enumValue("manual", ManualTrackChangeFading.SHORT_CROSSFADE),
            crossfadeLengthMs = crossfade.int("length", 5_000),
            shortManualCrossfadeLengthMs = crossfade.int("short", 400),
            fadeOnPlayPauseStop = crossfade.boolean("transport", true),
            playPauseStopFadeLengthMs = crossfade.int("transportMs", 50),
            fadeOnSeek = crossfade.boolean("seek", false),
            seekFadeLengthMs = crossfade.int("seekMs", 50),
            gaplessEnabled = crossfade.boolean("gapless", true),
            cutSilenceEnabled = crossfade.boolean("cut", true),
            preloadGaplessTracksMs = crossfade.int("preload", 0),
        ).normalized(),
        replayGain = ReplayGainState(
            mode = replayGain.enumValue("mode", ReplayGainMode.APPLY_GAIN_PREVENT_CLIPPING),
            source = replayGain.enumValue("source", ReplayGainSource.ALBUM),
            rgPreampDb = replayGain.float("rgPreamp", 0f),
            noRgPreampDb = replayGain.float("noRgPreamp", 0f),
        ).normalized(),
        audioFocus = AudioFocusState(
            pauseInCall = focus.boolean("pauseInCall", true),
            shortAudioFocusChangeCalls = focus.boolean("calls", true),
            duckVolumeEnabled = focus.boolean("duck", true),
            shortFocusDuckPolicy = focus.enumValue("shortDuck", AudioFocusDuckPolicy.DUCK),
            notificationDuckPolicy = focus.enumValue("notificationDuck", AudioFocusDuckPolicy.DUCK),
            navigationDuckingEnabled = focus.boolean("navigationDuck", true),
            duckVolumePercent = focus.int("duckPercent", 20),
            permanentAudioFocusChange = focus.boolean("permanent", true),
            resumeAfterCall = focus.boolean("afterCall", true),
            resumeOnStart = focus.boolean("onStart", false),
            resumeOnReopen = focus.boolean("onReopen", false),
            resumeOnFocusGain = focus.boolean("onGain", false),
            waitForStorage = focus.boolean("waitStorage", true),
        ).normalized(),
        equalizer = EqualizerSettingsState(
            bandCount = equalizer.int("count", 10),
            mode = equalizer.enumValue("mode", AudiophileEqualizerMode.GRAPHIC),
            customBandsEnabled = equalizer.boolean("custom", false),
            customBandCount = equalizer.int("customCount", 10),
            firstFrequencyHz = equalizer.int("first", 20),
            lastFrequencyHz = equalizer.int("last", 20_000),
            layout = equalizer.enumValue("layout", EqualizerLayout.STANDARD),
        ).normalized(),
        resampler = ResamplerSettingsState(
            type = resampler.enumValue("type", ResamplerType.SW),
            quality = resampler.enumValue("quality", ResamplerQuality.HIGH),
            cutoffFrequencyPercent = resampler.float("cutoff", 97f),
            phase = resampler.enumValue("phase", ResamplerPhase.LINEAR),
            steepFilterEnabled = resampler.boolean("steep", false),
            dither = resampler.enumValue("dither", DitherMode.NONE),
        ).normalized(),
        directVolumeControl = DirectVolumeControlState(
            dvcEnabled = dvc.boolean("on", true),
            volumeCompensationEnabled = dvc.boolean("compensation", false),
            noDvcHeadroomGainDb = dvc.float("headroom", -6f),
            noDvcForBluetoothAbsoluteVolume = dvc.boolean("noBt", true),
            bluetoothAbsoluteVolumeEnabled = dvc.boolean("bt", false),
        ).normalized(),
        bitPerfect = decodeBitPerfect(root.objectOrNull("bitPerfect")),
        advancedTweaks = AdvancedTweaksState(
            referenceModeEnabled = advanced.boolean("reference", false),
            referenceMode = advanced.enumValue("referenceMode", ReferenceMode.POWERAMP),
            usbVolumeLockEnabled = advanced.boolean("usbLock", false),
            usbGainCompensationEnabled = advanced.boolean("gainOn", false),
            usbGainCompensationDb = advanced.float("gain", 0f),
            volumeLevels = advanced.int("levels", 0),
            volumeSteps = advanced.int("steps", 0),
            musicFxEnabled = advanced.boolean("musicFx", false),
            forceSpeakerOff = advanced.boolean("speakerOff", false),
            forceAudioOnFocusChange = advanced.boolean("focusAudio", false),
            emulatedMediaStream = advanced.boolean("emulated", false),
            audioThreadPriority = advanced.int("thread", 0),
        ).normalized(),
        outputs = decodeOutputRoutes(root.array("outputs")) ?: defaults.outputs,
    )
}.getOrDefault(AudiophileAudioState())

private fun encodeBitPerfect(value: BitPerfectSettingsState) = JsonObject(mapOf(
    "pipeline64" to value.pipeline64Enabled.json,
    "follow" to value.followTrackSampleRate.json,
    "dsd" to value.dsdRemasteringEnabled.json,
    "rate" to value.dsdRate.name.json,
    "family48" to value.dsdRemaster48kFamilyEnabled.json,
    "gain" to value.dsdRemasterGainDb.json,
    "perfect" to value.perfectBitPerfectEnabled.json,
))

private fun decodeBitPerfect(value: JsonObject?): BitPerfectSettingsState = BitPerfectSettingsState(
    pipeline64Enabled = value.boolean("pipeline64", false),
    followTrackSampleRate = value.boolean("follow", false),
    dsdRemasteringEnabled = value.boolean("dsd", false),
    dsdRate = value.enumValue("rate", DsdRate.DSD64),
    dsdRemaster48kFamilyEnabled = value.boolean("family48", false),
    dsdRemasterGainDb = value.float("gain", -2f),
    perfectBitPerfectEnabled = value.boolean("perfect", false),
).normalized()

private fun encodeOutputProfile(profile: OutputDeviceProfile) = JsonObject(mapOf(
    "id" to profile.id.json,
    "kind" to profile.kind.name.json,
    "name" to profile.name.json,
    "on" to profile.enabled.json,
    "rate" to profile.sampleRateHz.json,
    "format" to profile.sampleFormat.name.json,
    "preset" to profile.bufferPreset.name.json,
    "bufferMs" to profile.bufferMs.json,
    "buffers" to profile.bufferCount.json,
    "postFade" to profile.postFadeBufferCount.json,
    "delay" to profile.visualizationLyricsDelayMs.json,
    "noDvc" to profile.noDvc.json,
    "noHeadroom" to profile.noHeadroomGain.json,
    "noEq" to profile.noEquTone.json,
    "noDuck" to profile.noDuck.json,
    "bitPerfect" to encodeBitPerfect(profile.bitPerfect),
))

private fun decodeOutputRoutes(elements: List<JsonElement>?): List<AudioOutputRouteState>? {
    elements ?: return null
    return elements.mapNotNull { element ->
        val route = element as? JsonObject ?: return@mapNotNull null
        val id = route.enumValueOrNull<AudioOutputRouteId>("id") ?: return@mapNotNull null
        val defaults = AudiophileAudioDefaults.defaultRoute(id)
        val profiles = route.array("profiles")?.mapNotNull { profileElement ->
            val profile = profileElement as? JsonObject ?: return@mapNotNull null
            val kind = profile.enumValueOrNull<OutputDeviceKind>("kind") ?: return@mapNotNull null
            val profileId = profile.string("id", kind.name.lowercase())
            val profileDefaults = AudiophileAudioDefaults.defaultProfile(id, kind, id = profileId)
            OutputDeviceProfile(
                id = profileId,
                kind = kind,
                name = profile.string("name", kind.label),
                enabled = profile.boolean("on", profileDefaults.enabled),
                sampleRateHz = profile.int("rate", profileDefaults.sampleRateHz),
                supportedSampleRatesHz = profileDefaults.supportedSampleRatesHz,
                sampleFormat = profile.enumValue("format", profileDefaults.sampleFormat),
                bufferPreset = profile.enumValue("preset", profileDefaults.bufferPreset),
                bufferMs = profile.int("bufferMs", profileDefaults.bufferMs),
                bufferCount = profile.int("buffers", profileDefaults.bufferCount),
                postFadeBufferCount = profile.int("postFade", profileDefaults.postFadeBufferCount),
                visualizationLyricsDelayMs = profile.int("delay", 0),
                noDvc = profile.boolean("noDvc", false),
                noHeadroomGain = profile.boolean("noHeadroom", false),
                noEquTone = profile.boolean("noEq", false),
                noDuck = profile.boolean("noDuck", false),
                bitPerfect = decodeBitPerfect(profile.objectOrNull("bitPerfect")),
            ).normalized()
        } ?: defaults.profiles
        defaults.copy(
            enabled = route.boolean("on", defaults.enabled),
            profiles = profiles,
        )
    }.takeIf { it.isNotEmpty() }
}

private fun String.jsonRoot(): JsonObject = snapshotJson.parseToJsonElement(this) as? JsonObject
    ?: error("Settings snapshot is not an object")

private fun JsonObject.requireVersion() {
    check(int("v", -1) == 1) { "Unsupported settings snapshot version" }
}

private val Boolean.json get() = JsonPrimitive(this)
private val Int.json get() = JsonPrimitive(this)
private val Float.json get() = JsonPrimitive(this)
private val String.json get() = JsonPrimitive(this)

private fun JsonObject?.objectOrNull(key: String): JsonObject? = this?.get(key) as? JsonObject
private fun JsonObject?.array(key: String): List<JsonElement>? = (this?.get(key) as? JsonArray)?.toList()
private fun JsonObject?.string(key: String, fallback: String): String =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: fallback
private fun JsonObject?.boolean(key: String, fallback: Boolean): Boolean =
    (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: fallback
private fun JsonObject?.int(key: String, fallback: Int): Int =
    (this?.get(key) as? JsonPrimitive)?.intOrNull ?: fallback
private fun JsonObject?.float(key: String, fallback: Float): Float =
    (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() } ?: fallback
private fun JsonElement.floatOrNull(): Float? =
    (this as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it.isFinite() }

private inline fun <reified T : Enum<T>> JsonObject?.enumValue(key: String, fallback: T): T =
    enumValueOrNull<T>(key) ?: fallback

private inline fun <reified T : Enum<T>> JsonObject?.enumValueOrNull(key: String): T? =
    string(key, "").takeIf { it.isNotEmpty() }?.let { value ->
        runCatching { enumValueOf<T>(value) }.getOrNull()
    }
