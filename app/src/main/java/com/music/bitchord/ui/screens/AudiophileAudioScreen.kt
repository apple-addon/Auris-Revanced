package com.music.bitchord.ui.screens

import android.media.AudioFormat
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.audio.TransportType
import com.music.bitchord.playback.audio.drivers.DsdOutputMode
import com.music.bitchord.playback.audio.drivers.OutputDriver
import com.music.bitchord.playback.reference.ReferenceEngineSnapshot
import com.music.bitchord.playback.reference.ReferenceEngineLoader
import com.music.bitchord.ui.audiophile.AudioInfoEntry
import com.music.bitchord.ui.audiophile.AudioOutputRouteId
import com.music.bitchord.ui.audiophile.AudiophileAudioState
import com.music.bitchord.ui.audiophile.AudiophileOutputRouting
import com.music.bitchord.ui.audiophile.OutputConnectionStatus
import com.music.bitchord.ui.audiophile.OutputDeviceKind
import com.music.bitchord.ui.audiophile.OutputSampleFormat
import com.music.bitchord.ui.audiophile.AudioInfoState
import com.music.bitchord.ui.audiophile.AudioStatusBadge
import com.music.bitchord.ui.audiophile.AudioStatusBadgeTone
import com.music.bitchord.ui.audiophile.AudiophileAudioSettingsScreen
import com.music.bitchord.ui.audiophile.AudiophileAudioUiState
import com.music.bitchord.ui.policy.ProviderAudioSurface
import com.music.bitchord.ui.policy.ProviderAudioTheme
import com.music.bitchord.ui.reference.PowerampEqState
import com.music.bitchord.ui.reference.PowerampReverbState

/** Full-screen Poweramp-shaped Audio hierarchy; the sheet editors remain separate. */
@Composable
internal fun AudiophileAudioScreen(
    contentPadding: PaddingValues,
    onExit: () -> Unit,
) {
    val settings by AppSettings.audiophileAudioState.collectAsStateWithLifecycle()
    val providerAxes by AppSettings.providerAxes.collectAsStateWithLifecycle()
    val output by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
    val engines by ReferenceEngineLoader.status.collectAsStateWithLifecycle()
    val dsdOutputMode by AppSettings.dsdOutputMode.collectAsStateWithLifecycle()
    var uiState by remember { mutableStateOf(AudiophileAudioUiState()) }
    val dsdSource = nerdStats?.mimeType?.startsWith("audio/dsd") == true || nerdStats?.container == "SACD ISO"
    val rawDsdOutput = nerdStats?.mimeType?.startsWith("audio/dsd") == true &&
        dsdOutputMode != DsdOutputMode.PCM &&
        output.exclusiveUsbActive &&
        output.transportType == TransportType.DIRECT_USB

    val audioInfo = AudioInfoState(
        trackTitle = nerdStats?.sourceName ?: "No active track",
        source = listOfNotNull(
            nerdStats?.mimeType?.let { AudioInfoEntry("Format", it) },
            nerdStats?.sampleRateHz?.let { AudioInfoEntry("Source rate", "$it Hz") },
            nerdStats?.channels?.let { AudioInfoEntry("Channels", it.toString()) },
            nerdStats?.bitDepth?.let { AudioInfoEntry("Source depth", "$it-bit") },
        ),
        processing = buildList {
            add(AudioInfoEntry("Reference graph", ReferenceEngineLoader.telemetrySummary()))
            if (dsdSource) {
                add(AudioInfoEntry(
                    "DSD / SACD",
                    if (rawDsdOutput) {
                        "${dsdOutputMode.label} over exclusive USB; packed DSD unchanged and DSP bypassed"
                    } else {
                        "Decoded to Float32 PCM by the native decimator; raw DSD/DoP is not active"
                    },
                ))
            }
            add(AudioInfoEntry("Tracker modules", "MOD/IT/S3M/XM signature detection only; no tracker decoder is advertised"))
            add(AudioInfoEntry(
                "Resampler",
                "${settings.resampler.type.label} / ${settings.resampler.quality.label} is a saved request; no arbitrary output-rate converter is claimed",
            ))
            if (settings.bitPerfect.dsdRemasteringEnabled) {
                add(AudioInfoEntry("DSD remastering", "Requested, not active: the installed DSD path decodes to PCM and does not re-encode DSD"))
            }
        },
        output = listOfNotNull(
            AudioInfoEntry("Driver", engines.driverTelemetry?.driver ?: output.sink.ifBlank { "Not reported" }),
            AudioInfoEntry("Device", output.deviceName.ifBlank { "System default" }),
            output.actualSampleRateHz?.let { AudioInfoEntry("Output stream rate", "$it Hz") },
            engines.driverTelemetry?.sharing?.let { AudioInfoEntry("Sharing", it) },
            engines.driverTelemetry?.detail?.let { AudioInfoEntry("Driver detail", it) },
        ),
        badges = buildList {
            if (settings.qualityOptionsEnabled) {
                add(AudioStatusBadge("Quality Options", AudioStatusBadgeTone.ACTIVE))
            }
            if (settings.bitPerfect.perfectBitPerfectEnabled) {
                add(AudioStatusBadge("Bit Perfect", AudioStatusBadgeTone.SUCCESS))
            }
            if (output.exclusiveUsbActive) {
                add(AudioStatusBadge("USB Exclusive", AudioStatusBadgeTone.SUCCESS))
            }
            if (engines.pawsOutputActive) {
                add(AudioStatusBadge("PAWS PCM Active", AudioStatusBadgeTone.SUCCESS))
            }
            if (engines.hibyOutputActive) {
                add(AudioStatusBadge("HiBy Native Active", AudioStatusBadgeTone.SUCCESS))
            }
        },
    )

    ProviderAudioTheme(provider = providerAxes.ui, surface = ProviderAudioSurface.SETTINGS) {
        AudiophileAudioSettingsScreen(
            settings = settings.withRuntimeOutputs(output, engines),
            audioInfo = audioInfo,
            uiState = uiState,
            onSettingsChange = AppSettings::setAudiophileAudioState,
            onUiStateChange = { uiState = it },
            onExit = onExit,
            onResetEqualizerPresets = { AppSettings.setPowerampEqState(PowerampEqState()) },
            onResetReverbPresets = {
                AppSettings.setPowerampEqState(
                    AppSettings.powerampEqState.value.copy(reverb = PowerampReverbState()),
                )
            },
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        )
    }
}

private fun AudiophileAudioState.withRuntimeOutputs(
    output: AudioOutputStatus.Snapshot,
    engines: ReferenceEngineSnapshot,
): AudiophileAudioState {
    val driver = outputDriver
    val actualRate = engines.driverTelemetry?.sampleRateHz?.takeIf { it > 0 } ?: output.actualSampleRateHz
    val actualFormat = when (output.actualEncoding) {
        AudioFormat.ENCODING_PCM_16BIT -> OutputSampleFormat.PCM_16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputSampleFormat.PCM_24_PACKED
        AudioFormat.ENCODING_PCM_32BIT -> OutputSampleFormat.PCM_32
        AudioFormat.ENCODING_PCM_FLOAT -> OutputSampleFormat.FLOAT_32
        else -> null
    }
    val activeKind = when (output.routeKind) {
        AudioRouting.Kind.PHONE -> OutputDeviceKind.SPEAKER
        AudioRouting.Kind.WIRED -> OutputDeviceKind.WIRED_HEADSET
        AudioRouting.Kind.BLUETOOTH -> OutputDeviceKind.BLUETOOTH
        AudioRouting.Kind.USB -> OutputDeviceKind.USB_DAC
        AudioRouting.Kind.HDMI, AudioRouting.Kind.OTHER -> OutputDeviceKind.OTHER
    }
    val reconciled = AudiophileOutputRouting.reconcile(this, driver)
    val backendActive: Boolean? = when (driver) {
        OutputDriver.USB_EXCLUSIVE -> output.exclusiveUsbActive
        OutputDriver.USB_SYSTEM_BIT_PERFECT -> output.bitPerfectMixer?.active == true
        else -> null
    }
    val backendProblem = when {
        backendActive != null && !backendActive && activeKind == OutputDeviceKind.USB_DAC ->
            if (driver == OutputDriver.USB_EXCLUSIVE) {
                output.exclusiveUsbDetail ?: "USB Exclusive is not active; the system path is the fallback."
            } else {
                output.bitPerfectMixer?.detail
                    ?: "USB System Bit-Perfect is not confirmed active; the mixer may resample."
            }
        backendActive != null && !backendActive ->
            "No USB DAC is connected; ${driver.label} is selected but not in the signal path."
        else -> null
    }
    return reconciled.copy(
        outputs = reconciled.outputs.map { route ->
            val selected = route.id != AudioOutputRouteId.CHROMECAST && route.enabled
            val backendEligible = selected && backendActive != false
            val profiles = route.profiles.map { profile ->
                val active = backendEligible && when (profile.kind) {
                    OutputDeviceKind.NAMED -> profile.name == output.deviceName
                    else -> profile.kind == activeKind
                }
                if (!active) {
                    if (selected && profile.connectionStatus == OutputConnectionStatus.ACTIVE) {
                        return@map profile.copy(
                            connectionStatus = OutputConnectionStatus.AVAILABLE,
                            statusDetail = "",
                            actualSampleRateHz = null,
                            actualSampleFormat = null,
                        )
                    }
                    return@map profile
                }
                val requestedRate = profile.sampleRateHz
                val rateDetail = if (actualRate != null && requestedRate != actualRate) {
                    "Requested $requestedRate Hz; actual stream $actualRate Hz. No rate conversion is claimed."
                } else {
                    "Actual output stream reported by the active driver"
                }
                profile.copy(
                    connectionStatus = OutputConnectionStatus.ACTIVE,
                    statusDetail = rateDetail,
                    actualSampleRateHz = actualRate,
                    actualSampleFormat = actualFormat,
                )
            }
            val anyActive = profiles.any { it.connectionStatus == OutputConnectionStatus.ACTIVE }
            val activeDetail = when (driver) {
                OutputDriver.USB_EXCLUSIVE -> output.exclusiveUsbDetail
                OutputDriver.USB_SYSTEM_BIT_PERFECT -> output.bitPerfectMixer?.detail
                else -> null
            } ?: route.statusDetail
            route.copy(
                profiles = profiles,
                // Route-level ACTIVE is derived from an actually active device profile and,
                // for USB backends, from the requested transport being genuinely in use.
                status = when {
                    selected && backendProblem != null -> OutputConnectionStatus.UNSUPPORTED
                    selected && anyActive -> OutputConnectionStatus.ACTIVE
                    route.status == OutputConnectionStatus.ACTIVE -> OutputConnectionStatus.AVAILABLE
                    else -> route.status
                },
                statusDetail = when {
                    selected && backendProblem != null -> backendProblem
                    selected && anyActive -> activeDetail
                    route.status == OutputConnectionStatus.ACTIVE -> ""
                    else -> route.statusDetail
                },
            )
        },
    )
}
