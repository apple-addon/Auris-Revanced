package com.music.bitchord.playback.audio

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.audiophile.AdvancedOutputPolicyResolver
import com.music.bitchord.playback.audio.audiophile.AudioFocusStateMachine
import com.music.bitchord.playback.audio.audiophile.AudiophileResampler
import com.music.bitchord.playback.audio.audiophile.AudioOutputRouteKey
import com.music.bitchord.playback.audio.audiophile.RouteEqualizerChange
import com.music.bitchord.playback.audio.audiophile.RouteEqualizerPolicy
import com.music.bitchord.playback.audio.audiophile.VisualDelayPolicy
import com.music.bitchord.playback.audio.audiophile.VisualDelayTelemetry
import com.music.bitchord.playback.fades.AudioFocusCommand
import com.music.bitchord.playback.fades.AudioFocusEvent
import com.music.bitchord.playback.fades.AudioFocusSettings
import com.music.bitchord.playback.fades.AudioFocusState
import com.music.bitchord.playback.fades.AudioFocusTransition
import com.music.bitchord.playback.fades.FadeSettings
import com.music.bitchord.ui.screens.audiophile.AdvancedTweaks
import com.music.bitchord.ui.screens.audiophile.AudioOutputProfilesStore
import com.music.bitchord.ui.screens.audiophile.AudioOutputRoute
import com.music.bitchord.ui.screens.audiophile.AudiophileSettingsStore
import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.OutputDriver
import com.music.bitchord.ui.screens.audiophile.ResamplerQuality
import com.music.bitchord.ui.screens.audiophile.ResamplerSettings
import com.music.bitchord.ui.screens.audiophile.StoredAudioOutputProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Explicit callbacks for the playback-service integrator.
 *
 * Gain is delivered before a play command so a resume cannot briefly render at full volume while
 * a notification duck is still active. Route events are only emitted for a real route/device
 * identity change, never for the initial resolution or repeated telemetry refresh.
 */
interface AudiophileRuntimeListener {
    fun onFocusGainChanged(gain: Float) {}
    fun onFocusCommand(command: AudioFocusCommand) {}
    fun onRouteEqualizerChange(change: RouteEqualizerChange) {}
}

/** Service-owned mirror of the persisted Audiophile stores and the currently active route. */
class AudiophileRuntimeController(
    context: Context,
    private val scope: CoroutineScope,
    private val fades: StateFlow<FadeSettings>,
) : AutoCloseable {
    private val lock = Any()
    private val settingsStore = AudiophileSettingsStore(context.applicationContext)
    private val profilesStore = AudioOutputProfilesStore(context.applicationContext)
    private val mutableResolved = MutableStateFlow(resolveCurrent())

    val resolved: StateFlow<ResolvedAudiophileOutput> = mutableResolved.asStateFlow()

    /** Alias making the per-output profile exposure explicit to the service integrator. */
    val activeProfile: StateFlow<ResolvedAudiophileOutput> get() = resolved
    val outputProfiles: StateFlow<Map<String, StoredAudioOutputProfile>> get() = profilesStore.state

    private val mutableFocusSettings = MutableStateFlow(settingsStore.state.value.focus)
    private val mutableEffectiveFocusSettings = MutableStateFlow(settingsStore.state.value.focus)
    private val mutableResamplerSettings = MutableStateFlow(settingsStore.state.value.resampler)
    private val mutableEffectiveResamplerSettings = MutableStateFlow(settingsStore.state.value.resampler)
    private val mutableAdvancedTweaks = MutableStateFlow(settingsStore.state.value.advanced)
    private val mutableAdvancedOutputPolicy = MutableStateFlow(AdvancedOutputPolicyResolver.resolve(settingsStore.state.value.advanced, mutableResolved.value))
    private val mutableVisualDelay = MutableStateFlow(visualTelemetry(mutableResolved.value))
    private val mutableFocusState = MutableStateFlow(AudioFocusState())
    private val focusMachine = AudioFocusStateMachine(
        settingsStore.state.value.focus,
        outputNoDuck(mutableResolved.value),
        outputIgnoreAudioFocus(mutableResolved.value),
    )
    private var lastRouteKey = routeKey(mutableResolved.value, AudioOutputStatus.current.value)
    private var collectionJob: Job? = null
    private var listener: AudiophileRuntimeListener? = null

    /** Persisted focus switches; no per-output conflict folding is applied to this flow. */
    val focusSettings: StateFlow<AudioFocusSettings> = mutableFocusSettings.asStateFlow()

    /** Focus policy after per-output No Duck and Perfect BP conflicts are folded in. */
    val effectiveFocusSettings: StateFlow<AudioFocusSettings> = mutableEffectiveFocusSettings.asStateFlow()

    /** Persisted resampler switches, including the stored cutoff. */
    val resamplerSettings: StateFlow<ResamplerSettings> = mutableResamplerSettings.asStateFlow()

    /** Resampler policy after Perfect BP and Float32 conflicts; OFF always forces dither off here. */
    val effectiveResamplerSettings: StateFlow<ResamplerSettings> = mutableEffectiveResamplerSettings.asStateFlow()

    val advancedTweaks: StateFlow<AdvancedTweaks> = mutableAdvancedTweaks.asStateFlow()
    val advancedOutputPolicy = mutableAdvancedOutputPolicy.asStateFlow()
    val focusState: StateFlow<AudioFocusState> = mutableFocusState.asStateFlow()
    val visualDelay: StateFlow<VisualDelayTelemetry> = mutableVisualDelay.asStateFlow()

    init {
        refresh()
    }

    /** One collector is enough: every resolution reads one coherent current snapshot. */
    fun start() {
        synchronized(lock) {
            if (collectionJob?.isActive == true) return
            collectionJob = scope.launch {
                combine<Any, Unit>(
                    settingsStore.state,
                    profilesStore.state,
                    AudioOutputStatus.current,
                    fades,
                    AppSettings.nativeOutputBackend,
                    AppSettings.outputPcmMode,
                    AppSettings.bitPerfect,
                    AppSettings.equalizerEnabled,
                    AppSettings.toneEnabled,
                    AppSettings.replayGainEnabled,
                ) { }.collect {
                    refresh()
                }
            }
        }
    }

    /**
     * Registers the service's explicit callbacks and immediately publishes the current focus gain.
     * This does not request Android focus or start playback.
     */
    fun attach(listener: AudiophileRuntimeListener) {
        val gain = synchronized(lock) {
            this.listener = listener
            focusMachine.state.outputGain
        }
        listener.onFocusGainChanged(gain)
    }

    fun detach(listener: AudiophileRuntimeListener) {
        synchronized(lock) {
            if (this.listener === listener) this.listener = null
        }
    }

    fun detach() {
        synchronized(lock) {
            listener = null
        }
    }

    fun dispatchFocusEvent(event: AudioFocusEvent): AudioFocusTransition {
        val transition: AudioFocusTransition
        synchronized(lock) {
            transition = focusMachine.dispatch(event)
            mutableFocusState.value = focusMachine.state
            mutableAdvancedOutputPolicy.value = AdvancedOutputPolicyResolver.resolve(
                settingsStore.state.value.advanced,
                mutableResolved.value,
                focusMachine.state.isDucked,
            )
        }
        deliverFocusTransition(transition)
        return transition
    }

    fun onUserPlay() = dispatchFocusEvent(AudioFocusEvent.UserPlay)
    fun onUserPause() = dispatchFocusEvent(AudioFocusEvent.UserPause)
    fun onServiceStarted() = dispatchFocusEvent(AudioFocusEvent.ServiceStarted)
    fun onAppReopened() = dispatchFocusEvent(AudioFocusEvent.AppReopened)
    fun onCallChanged(active: Boolean) = dispatchFocusEvent(AudioFocusEvent.CallChanged(active))
    fun onNotificationChanged(active: Boolean, canDuck: Boolean = true) =
        dispatchFocusEvent(AudioFocusEvent.NotificationChanged(active, canDuck))
    fun onPermanentFocusLost() = dispatchFocusEvent(AudioFocusEvent.PermanentFocusLost)
    fun onStorageChanged(ready: Boolean) = dispatchFocusEvent(AudioFocusEvent.StorageChanged(ready))

    /**
     * Applies the effective resampler policy at a safe stream boundary. [outputSampleRateHz] is the
     * software PCM rate the sink has actually been opened for; this method never changes hardware
     * output rate. Later DspChain mounting should configure from [effectiveResamplerSettings] and
     * use [AudiophileResampler.maxOutputFrames] for its output block.
     */
    fun configureResampler(
        processor: AudiophileResampler,
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        channelCount: Int,
        outputBitDepth: Int? = null,
        maxInputFrames: Int = 4096,
    ) = processor.configure(
        inputSampleRateHz = inputSampleRateHz,
        outputSampleRateHz = outputSampleRateHz,
        channelCount = channelCount,
        settings = effectiveResamplerSettings.value,
        outputBitDepth = outputBitDepth,
        maxInputFrames = maxInputFrames,
    )

    /** Visual-only position for UI consumers; audio timestamps are never changed. */
    fun visualPositionMs(audioPositionMs: Long, durationMs: Long? = null): Long =
        mutableVisualDelay.value.visualPositionMs(audioPositionMs, durationMs)

    private fun refresh() {
        var settingsTransition: AudioFocusTransition? = null
        var routeChange: RouteEqualizerChange? = null
        var routeListener: AudiophileRuntimeListener? = null
        synchronized(lock) {
            val previous = mutableResolved.value
            val global = settingsStore.state.value
            val output = resolveCurrent()
            mutableResolved.value = output
            mutableFocusSettings.value = global.focus
            mutableResamplerSettings.value = global.resampler
            mutableAdvancedTweaks.value = global.advanced
            mutableEffectiveResamplerSettings.value = effectiveResampler(global.resampler, output)

            settingsTransition = focusMachine.updateSettings(
                global.focus,
                outputNoDuck(output),
                outputIgnoreAudioFocus(output),
            )
            mutableFocusState.value = focusMachine.state
            mutableEffectiveFocusSettings.value = focusMachine.settings

            val nextRouteKey = routeKey(output, AudioOutputStatus.current.value)
            routeChange = RouteEqualizerPolicy.onOutputChanged(
                previousKey = lastRouteKey,
                currentKey = nextRouteKey,
                previous = previous,
                current = output,
                tweaks = global.advanced,
            )
            if (routeChange != null) lastRouteKey = nextRouteKey

            mutableAdvancedOutputPolicy.value = AdvancedOutputPolicyResolver.resolve(
                global.advanced,
                output,
                focusMachine.state.isDucked,
            )
            mutableVisualDelay.value = visualTelemetry(output)
            AudioOutputStatus.publishVisualDelay(output.profile.visualDelayMs)
            routeListener = listener
        }
        settingsTransition?.let(::deliverFocusTransition)
        routeChange?.let { change -> routeListener?.onRouteEqualizerChange(change) }
    }

    private fun deliverFocusTransition(transition: AudioFocusTransition) {
        val current = synchronized(lock) { listener } ?: return
        if (transition.gainChanged) current.onFocusGainChanged(transition.state.outputGain)
        if (transition.command != AudioFocusCommand.NONE) current.onFocusCommand(transition.command)
    }

    private fun resolveCurrent(): ResolvedAudiophileOutput {
        val output = AudioOutputStatus.current.value
        val route = runCatching { AudioOutputRoute.valueOf(output.routeKind.name) }
            .getOrDefault(AudioOutputRoute.OTHER)
        val fade = fades.value
        return resolveAudiophileOutput(
            route = route,
            routeKindName = output.routeKind.name,
            deviceName = output.deviceName,
            global = settingsStore.state.value,
            profiles = profilesStore.state.value,
            baselineDriver = when (AppSettings.nativeOutputBackend.value) {
                NativeOutputBackend.AUDIO_TRACK -> OutputDriver.AUDIO_TRACK
                NativeOutputBackend.AAUDIO -> OutputDriver.AAUDIO
                NativeOutputBackend.OPENSL_ES -> OutputDriver.OPENSL_ES
            },
            baselineFloat32 = AppSettings.outputPcmMode.value == OutputPcmMode.FLOAT_32,
            globalPerfectBitPerfect = AppSettings.bitPerfect.value,
            equalizerRequested = AppSettings.equalizerEnabled.value,
            toneRequested = AppSettings.toneEnabled.value,
            replayGainRequested = AppSettings.replayGainEnabled.value,
            crossfadeRequested = (fade.crossfadeAutoAdvance && fade.crossfadeLengthMs > 0L) ||
                (fade.fadeManualAdvance && fade.shortManualCrossfadeMs > 0L),
        )
    }

    private fun effectiveResampler(
        stored: ResamplerSettings,
        output: ResolvedAudiophileOutput,
    ): ResamplerSettings {
        val effective = output.effective
        return ResamplerSettings(
            quality = effective.resamplerQuality,
            cutoffPercent = stored.cutoffPercent,
            dither = when {
                effective.resamplerQuality == ResamplerQuality.OFF -> DitherMode.NONE
                else -> effective.dither
            },
        )
    }

    private fun outputNoDuck(output: ResolvedAudiophileOutput): Boolean =
        output.profile.noDuck || output.effective.perfectBitPerfect

    /** Per-device no_af folds into the effective focus policy alongside No Duck. */
    private fun outputIgnoreAudioFocus(output: ResolvedAudiophileOutput): Boolean =
        output.profile.noAf || output.effective.ignoreAudioFocus

    private fun routeKey(
        output: ResolvedAudiophileOutput,
        status: AudioOutputStatus.Snapshot,
    ): AudioOutputRouteKey = AudioOutputRouteKey.of(
        routeKindName = status.routeKind.name,
        deviceName = status.deviceName,
        output = output,
    )

    private fun visualTelemetry(output: ResolvedAudiophileOutput): VisualDelayTelemetry {
        val status = AudioOutputStatus.current.value
        return VisualDelayPolicy.telemetry(
            output = output,
            bufferSizeFrames = status.bufferSize,
            outputSampleRateHz = status.actualSampleRateHz,
        )
    }

    override fun close() {
        val job = synchronized(lock) {
            val current = collectionJob
            collectionJob = null
            listener = null
            current
        }
        job?.cancel()
        settingsStore.close()
        profilesStore.close()
    }
}
