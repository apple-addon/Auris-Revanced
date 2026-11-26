package com.music.bitchord.ui.screens.audiophile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.replaygain.ReplayGainSource
import com.music.bitchord.playback.fades.FadeSettingsStore
import com.music.bitchord.playback.fades.GaplessPreloadPreset
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioInfoScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val status by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val rate = status.actualSampleRateHz
    val bufferDetail = status.bufferSize?.let { frames ->
        rate?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f ms", frames * 1_000.0 / it) }
    }
    AudiophilePage("Audio Info", modifier, onBack, contentPadding) {
        AudiophileNote("Live telemetry from AudioOutputStatus. Values change with the playing track and connected route.")
        AudiophileSectionLabel("Output")
        AudiophileCard {
            AudiophileInfoRow("Device", status.deviceName, status.activeDeviceId?.let { "Android device $it" })
            AudiophileInfoRow("Route", status.routeKind.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
            AudiophileInfoRow("Driver", status.sink, status.transportType.name.replace('_', ' '))
            AudiophileInfoRow(
                "Output Format",
                AudioOutputStatus.encodingLabel(status),
                status.actualSampleRateHz?.let(::formatSampleRate)
                    ?: status.sampleRatesHz.takeIf(IntArray::isNotEmpty)?.joinToString { "$it Hz" },
            )
            AudiophileInfoRow(
                "System Mixer",
                status.systemMixerRateHz?.let(::formatSampleRate)
                    ?: if (status.exclusiveUsbActive || status.directPlaybackActual) "Bypassed" else "Not measured",
            )
            AudiophileInfoRow("Buffer", status.bufferSize?.let { "$it frames" } ?: "Not measured", bufferDetail)
            AudiophileInfoRow(
                "Visual Delay",
                "${status.visualDelayMs} ms",
                status.visualBufferDelayMs?.let { String.format(Locale.getDefault(), "Output buffer %.1f ms; visuals apply the profile delay only", it) },
            )
        }
        AudiophileSectionLabel("Decoder & DSP")
        AudiophileCard {
            AudiophileInfoRow("Decoder", status.decoderName ?: "Waiting for playback")
            AudiophileInfoRow("Decoder Format", status.decoderOutputEncoding ?: "Not measured")
            AudiophileInfoRow(
                "DSP",
                status.dspFormat,
                when {
                    !status.dspAvailable -> "Unavailable for this bitstream"
                    status.dspBypassed -> "Bypassed"
                    else -> "Available"
                },
            )
            AudiophileInfoRow(
                "Resampler",
                when {
                    rate == null -> "Not measured"
                    status.systemMixerRateHz == null -> "Passthrough / unknown mixer rate"
                    rate == status.systemMixerRateHz -> "Passthrough"
                    else -> "${formatSampleRate(rate)} → ${formatSampleRate(status.systemMixerRateHz)}"
                },
            )
            AudiophileInfoRow("ReplayGain", status.loudnessGainDb?.let(::formatDb) ?: "Inactive", status.loudnessLufs?.let { "$it LUFS" })
        }
        AudiophileSectionLabel("Signal Path")
        AudiophileCard {
            AudiophileInfoRow(
                "Bit Exact",
                if (status.outputExact) "Yes" else "No / not measured",
                status.outputExactDetail,
            )
            AudiophileInfoRow(
                "Direct Path",
                when {
                    status.exclusiveUsbActive -> "USB Exclusive active"
                    status.directPlaybackActual -> "Direct AudioTrack active"
                    status.directPlaybackRejected -> "Direct request rejected"
                    else -> "Shared output"
                },
                status.directPlaybackDetail,
            )
            AudiophileInfoRow("USB Exclusive", if (status.exclusiveUsbActive) "Active" else "Inactive", status.exclusiveUsbDetail)
            AudiophileInfoRow("USB Endpoint", status.usbEndpointFormat ?: "Not reported", status.halFormat)
            AudiophileInfoRow(
                "Bluetooth",
                status.bluetoothProfile ?: if (status.routeKind.name == "BLUETOOTH") "Bluetooth" else "Not active",
                status.bluetoothTelemetry?.codecName,
            )
            AudiophileInfoRow("Fallback", status.fallbackReason.name.replace('_', ' '), status.fallbackDetail)
            AudiophileInfoRow(
                "Volume",
                status.volumeApplication.name.replace('_', ' '),
                status.volumeOffsetDb?.let { String.format(Locale.getDefault(), "%+.1f dB", it) },
            )
        }
    }
}

@Composable
fun CrossfadeFadeGaplessScreen(
    store: FadeSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val settings by store.state.collectAsStateWithLifecycle()
    val perfectBitPerfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    fun update(value: com.music.bitchord.playback.fades.FadeSettings) {
        store.update(value)
        runtime.onFadeSettingsChanged(store.state.value)
    }
    AudiophilePage("Crossfade, Fade, and Gapless", modifier, onBack, contentPadding) {
        AudiophileNote("Tracks with different sample rates may not be stitched together gaplessly.")
        AudiophileNote("Fading is not applied when switching between tracks with different sample rates.")
        if (perfectBitPerfect) {
            AudiophileNote("Perfect Bit Perfect mode disables all fading effects.")
        }
        AudiophileSectionLabel("Track Transitions")
        AudiophileCard {
            AudiophileSliderRow(
                title = "Preload Gapless Tracks",
                value = settings.preloadGaplessTracksMs.toFloat(),
                valueRange = 0f..5_000f,
                onChange = { update(settings.copy(preloadGaplessTracksMs = it.toLong())) },
                valueLabel = "${settings.preloadGaplessTracksMs} ms",
                subtitle = "Increase (to Normal or more) for slow storages to improve gapless",
                quantum = 100f,
                steps = 49,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GaplessPreloadPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.preloadGaplessTracksMs == preset.durationMs,
                        onClick = { update(settings.copy(preloadGaplessTracksMs = preset.durationMs)) },
                        label = { Text(preset.chipLabel()) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AudiophileSliderRow(
                title = "Silence Between Tracks",
                value = settings.silenceBetweenTracksMs.toFloat(),
                valueRange = 0f..5_000f,
                onChange = { update(settings.copy(silenceBetweenTracksMs = it.toLong())) },
                valueLabel = "${settings.silenceBetweenTracksMs} ms",
                subtitle = "Applied to all tracks excluding CUE tracks. Elapsed time may go beyond track duration",
                quantum = 100f,
                steps = 49,
            )
            AudiophileSwitchRow(
                "Auto-advance Fading",
                settings.crossfadeAutoAdvance,
                { update(settings.copy(crossfadeAutoAdvance = it)) },
                "Applied when the song is changed automatically",
            )
            AudiophileSwitchRow(
                "Manual Track Change Fading",
                settings.fadeManualAdvance,
                { update(settings.copy(fadeManualAdvance = it)) },
                "Applied when the song is changed manually",
            )
            AudiophileSliderRow(
                title = "Crossfade Length",
                value = settings.crossfadeLengthMs.toFloat(),
                valueRange = 100f..15_000f,
                onChange = { update(settings.copy(crossfadeLengthMs = it.toLong())) },
                valueLabel = "${settings.crossfadeLengthMs} ms",
                enabled = settings.crossfadeAutoAdvance,
                quantum = 50f,
                steps = 297,
            )
            AudiophileSliderRow(
                title = "Short Manual Crossfade Length",
                value = settings.shortManualCrossfadeMs.toFloat(),
                valueRange = 10f..1_000f,
                onChange = { update(settings.copy(shortManualCrossfadeMs = it.toLong())) },
                valueLabel = "${settings.shortManualCrossfadeMs} ms",
                enabled = settings.fadeManualAdvance,
                quantum = 10f,
                steps = 98,
            )
        }
        AudiophileSectionLabel("Transport Fades")
        AudiophileCard {
            AudiophileSwitchRow(
                "Fade Play/Pause/Stop",
                settings.fadePlayPause,
                { update(settings.copy(fadePlayPause = it)) },
            )
            AudiophileSwitchRow(
                "Fade on Seek",
                settings.fadeSeek,
                { update(settings.copy(fadeSeek = it)) },
            )
            AudiophileSliderRow(
                title = "Play/Pause/Stop Fade Length",
                value = settings.playPauseStopFadeMs.toFloat(),
                valueRange = 10f..1_000f,
                onChange = { update(settings.copy(playPauseStopFadeMs = it.toLong())) },
                valueLabel = "${settings.playPauseStopFadeMs} ms",
                enabled = settings.fadePlayPause,
                quantum = 10f,
                steps = 98,
            )
            AudiophileSliderRow(
                title = "Seek Fade Length",
                value = settings.seekFadeMs.toFloat(),
                valueRange = 10f..500f,
                onChange = { update(settings.copy(seekFadeMs = it.toLong())) },
                valueLabel = "${settings.seekFadeMs} ms",
                enabled = settings.fadeSeek,
                quantum = 10f,
                steps = 48,
            )
            AudiophileActionButton("Restore Defaults") {
                store.restoreDefaults()
                runtime.onFadeSettingsChanged(store.state.value)
            }
        }
    }
}

/** Slider preset labels exactly as the reference gapless preload seekbar shows them. */
internal fun GaplessPreloadPreset.chipLabel(): String = when (this) {
    GaplessPreloadPreset.NONE -> "None"
    GaplessPreloadPreset.NORMAL -> "Normal"
    GaplessPreloadPreset.MORE -> "More"
}

@Composable
fun ReplayGainScreen(
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val enabled by AppSettings.replayGainEnabled.collectAsStateWithLifecycle()
    val source by AppSettings.replayGainSource.collectAsStateWithLifecycle()
    val preamp by AppSettings.replayGainPreampDb.collectAsStateWithLifecycle()
    val nonRgPreamp by AppSettings.nonReplayGainPreampDb.collectAsStateWithLifecycle()
    val preventClipping by AppSettings.replayGainPreventClipping.collectAsStateWithLifecycle()
    val perfectBitPerfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    fun changed() = runtime.onReplayGainChanged()
    AudiophilePage("Replay Gain (RG)", modifier, onBack, contentPadding) {
        if (perfectBitPerfect) AudiophileNote("Perfect BP currently bypasses ReplayGain; these saved choices are preserved.")
        AudiophileCard {
            // rg_type: "Don't apply" / "Apply Gain" / "Apply Gain/prevent clipping according to Peak",
            // mapped onto the persisted enabled + preventClipping pair.
            AudiophileChoiceRow(
                title = "Replay Gain",
                selected = replayGainType(enabled, preventClipping),
                options = ReplayGainType.entries,
                optionLabel = { it.label },
                onSelect = { type ->
                    AppSettings.setReplayGainEnabled(type != ReplayGainType.NONE)
                    AppSettings.setReplayGainPreventClipping(type == ReplayGainType.APPLY_PEAK)
                    changed()
                },
            )
            AudiophileChoiceRow(
                title = "Source",
                selected = source,
                options = ReplayGainSource.entries,
                optionLabel = {
                    when (it) {
                        ReplayGainSource.TRACK -> "Track"
                        ReplayGainSource.ALBUM -> "Album"
                        ReplayGainSource.SMART -> "Smart (Album in album order)"
                    }
                },
                onSelect = { AppSettings.setReplayGainSource(it); changed() },
                enabled = enabled,
            )
            AudiophileSliderRow(
                title = "RG preamp",
                value = preamp,
                valueRange = -16f..16f,
                onChange = { AppSettings.setReplayGainPreampDb(it); changed() },
                valueLabel = formatRgDb(preamp),
                enabled = enabled,
                quantum = 0.1f,
                steps = 319,
            )
            AudiophileSliderRow(
                title = "Preamp for songs without RG info",
                value = nonRgPreamp,
                valueRange = -16f..16f,
                onChange = { AppSettings.setNonReplayGainPreampDb(it); changed() },
                valueLabel = formatRgDb(nonRgPreamp),
                subtitle = "Used only when the selected source has no ReplayGain tags.",
                enabled = enabled,
                quantum = 0.1f,
                steps = 319,
            )
            AudiophileActionButton("Restore Defaults") {
                AppSettings.setReplayGainEnabled(false)
                AppSettings.setReplayGainSource(ReplayGainSource.ALBUM)
                AppSettings.setReplayGainPreampDb(0f)
                AppSettings.setNonReplayGainPreampDb(0f)
                AppSettings.setReplayGainPreventClipping(false)
                changed()
            }
        }
    }
}

/** The reference rg_type list, as persisted through the enabled/preventClipping pair. */
internal enum class ReplayGainType(val label: String) {
    NONE("\"Don't apply\""),
    APPLY("Apply Gain"),
    APPLY_PEAK("Apply Gain/prevent clipping according to Peak"),
    ;
}

internal fun replayGainType(enabled: Boolean, preventClipping: Boolean): ReplayGainType =
    when {
        !enabled -> ReplayGainType.NONE
        preventClipping -> ReplayGainType.APPLY_PEAK
        else -> ReplayGainType.APPLY
    }

/** Reference d_db format: %.1fdB. */
internal fun formatRgDb(value: Float): String = String.format(Locale.getDefault(), "%.1fdB", value)

@Composable
fun AudioFocusScreen(
    store: AudiophileSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val focus = store.state.collectAsStateWithLifecycle().value.focus
    fun update(value: com.music.bitchord.playback.fades.AudioFocusSettings) {
        store.updateFocus(value)
        runtime.onAudioFocusChanged(value)
    }
    AudiophilePage("Audio Focus", modifier, onBack, contentPadding) {
        AudiophileSectionLabel("Interruptions")
        AudiophileCard {
            AudiophileSwitchRow("Pause in Call", focus.pauseInCall, { update(focus.copy(pauseInCall = it)) }, "Pause for an active call and preserve interrupted-playback intent.")
            AudiophileSwitchRow("Notification Duck", focus.notificationDuck, { update(focus.copy(notificationDuck = it)) }, "Lower music to 25% for duckable notifications; pause for non-duckable focus loss.")
            AudiophileSwitchRow("No Duck", focus.noDuckOverride, { update(focus.copy(noDuckOverride = it)) }, "Keep full volume during duckable notifications.")
        }
        AudiophileSectionLabel("Lifecycle")
        AudiophileCard {
            AudiophileSwitchRow("Resume on Start", focus.resumeOnStart, { update(focus.copy(resumeOnStart = it)) }, "Request playback when the playback service starts.")
            AudiophileSwitchRow("Resume on Reopen", focus.resumeOnReopen, { update(focus.copy(resumeOnReopen = it)) }, "Request playback when the app returns to the foreground.")
            AudiophileSwitchRow("Wait for Storage", focus.waitForStorage, { update(focus.copy(waitForStorage = it)) }, "Hold automatic resume until removable music storage is ready.")
            AudiophileActionButton("Restore Defaults") {
                store.restoreFocusDefaults()
                runtime.onAudioFocusChanged(store.state.value.focus)
            }
        }
    }
}
