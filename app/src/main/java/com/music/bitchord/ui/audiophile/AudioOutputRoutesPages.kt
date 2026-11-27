package com.music.bitchord.ui.audiophile

import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.bitchord.playback.audio.drivers.OutputDriver

@Composable
fun AudioOutputsSettingsPage(
    settings: AudiophileAudioState,
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        settings.outputs.forEach { route ->
            item(key = route.id.name) {
                AudioOutputRouteSummary(route, settings.outputDriver, navigation, callbacks)
            }
        }
        item {
            AudiophileRestoreButton(
                onClick = callbacks.onRestoreAllOutputs,
                label = "Restore all output defaults",
            )
        }
    }
}

@Composable
private fun AudioOutputRouteSummary(
    route: AudioOutputRouteState,
    selectedDriver: OutputDriver,
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(
        title = if (route.id == AudioOutputRouteId.CHROMECAST) {
            route.id.label
        } else {
            outputRouteDriverLabel(route, selectedDriver)
        },
    ) {
        if (route.id == AudioOutputRouteId.CHROMECAST) {
            AudiophileSwitchRow(
                label = "Use ${route.id.label}",
                subtitle = "Independent Cast route; does not change the local output driver.",
                checked = route.enabled,
                enabled = route.available,
                onCheckedChange = callbacks.onChromecastEnabledChange,
            )
        } else {
            AudiophileSelectionRow(
                label = "Use ${outputRouteDriverLabel(route, selectedDriver)}",
                subtitle = outputRouteDriverDescription(route, selectedDriver),
                selected = route.enabled,
                enabled = route.available,
                onSelect = { callbacks.onRouteSelected(route.id) },
            )
        }
        AudiophileBadges(listOf(outputStatusBadge(route.status)))
        if (route.statusDetail.isNotBlank()) {
            AudiophileValueRow(label = "Status", value = route.statusDetail)
        }
        val routeLabel = outputRouteDriverLabel(route, selectedDriver)
        AudiophileNavigationRow(
            title = "Configure $routeLabel",
            subtitle = "Independent settings for every output device",
            value = "${route.profiles.count(OutputDeviceProfile::enabled)} of ${route.profiles.size} devices",
            enabled = route.available,
            onClick = { navigation.onOpenOutputRoute(route.id) },
        )
    }
}

@Composable
fun AudioOutputRouteSettingsPage(
    route: AudioOutputRouteState,
    selectedDriver: OutputDriver,
    namedDeviceName: String,
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { OutputRouteStatusSection(route, selectedDriver, callbacks) }
        if (route.id == AudioOutputRouteId.USB_EXCLUSIVE) {
            item { UsbOutputBackendSection(route, selectedDriver, callbacks) }
        }
        route.profiles.forEach { profile ->
            item(key = "${route.id.name}:${profile.id}") {
                OutputDeviceSummary(route.id, profile, navigation, callbacks)
            }
        }
        item { NamedDeviceSection(route.id, namedDeviceName, callbacks) }
        item {
            val routeLabel = outputRouteDriverLabel(route, selectedDriver)
            AudiophileRestoreButton(
                onClick = { callbacks.onRestoreRouteDefaults(route.id) },
                label = "Restore $routeLabel defaults",
            )
        }
    }
}

@Composable
private fun OutputRouteStatusSection(
    route: AudioOutputRouteState,
    selectedDriver: OutputDriver,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = "Output status") {
        AudiophileBadges(listOf(outputStatusBadge(route.status)))
        if (route.id == AudioOutputRouteId.CHROMECAST) {
            AudiophileSwitchRow(
                label = "Chromecast enabled",
                subtitle = "Independent Cast route; not a local OutputDriver.",
                checked = route.enabled,
                enabled = route.available,
                onCheckedChange = callbacks.onChromecastEnabledChange,
            )
        } else {
            AudiophileSelectionRow(
                label = "Use ${outputRouteDriverLabel(route, selectedDriver)}",
                selected = route.enabled,
                enabled = route.available,
                onSelect = { callbacks.onRouteSelected(route.id) },
            )
        }
        AudiophileValueRow(
            label = "Method",
            value = outputRouteDriverDescription(route, selectedDriver),
        )
        if (route.enabled && route.id != AudioOutputRouteId.CHROMECAST) {
            AudiophileValueRow(label = "Canonical driver", value = selectedDriver.label)
        }
        if (route.statusDetail.isNotBlank()) {
            AudiophileValueRow(label = "Detail", value = route.statusDetail)
        }
    }
}

@Composable
private fun UsbOutputBackendSection(
    route: AudioOutputRouteState,
    selectedDriver: OutputDriver,
    callbacks: OutputSettingsCallbacks,
) {
    val choices = listOf(OutputDriver.USB_EXCLUSIVE, OutputDriver.USB_SYSTEM_BIT_PERFECT)
    val current = selectedDriver.takeIf { it in choices } ?: OutputDriver.USB_EXCLUSIVE
    AudiophileSection(title = "USB output backend") {
        AudiophileSelectionGroup(
            label = "Output driver",
            options = choices,
            selected = current,
            onSelect = callbacks.onOutputDriverSelected,
            optionLabel = { it.label },
            optionSubtitle = { driver ->
                when (driver) {
                    OutputDriver.USB_EXCLUSIVE ->
                        "Userspace usbdevfs; Auris owns the DAC endpoint."
                    OutputDriver.USB_SYSTEM_BIT_PERFECT ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            "Android system direct path; no userspace USB claim."
                        } else {
                            "Requires Android 14 or newer."
                        }
                    else -> null
                }
            },
            optionEnabled = { driver ->
                driver != OutputDriver.USB_SYSTEM_BIT_PERFECT ||
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            },
            enabled = route.available,
        )
    }
}

@Composable
private fun OutputDeviceSummary(
    routeId: AudioOutputRouteId,
    profile: OutputDeviceProfile,
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = profile.name) {
        AudiophileSwitchRow(
            label = "Use this output device",
            subtitle = profile.kind.label,
            checked = profile.enabled,
            onCheckedChange = { callbacks.onProfileEnabledChange(routeId, profile.id, it) },
        )
        AudiophileBadges(outputProfileBadges(profile))
        if (profile.statusDetail.isNotBlank()) {
            AudiophileValueRow(label = "Status", value = profile.statusDetail)
        }
        AudiophileNavigationRow(
            title = "Device settings",
            subtitle = "Format, buffer, delay, processing, and Bit Perfect",
            value = "${formatSampleRate(profile.sampleRateHz)} · ${profile.sampleFormat.label}",
            onClick = { navigation.onOpenOutputProfile(routeId, profile.id) },
        )
    }
}

@Composable
private fun NamedDeviceSection(
    routeId: AudioOutputRouteId,
    name: String,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = "Named device profile") {
        OutlinedTextField(
            value = name,
            onValueChange = callbacks.onNamedDeviceNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device name") },
            supportingText = { Text("Creates an independent profile for a named Bluetooth, USB, or other device.") },
            singleLine = true,
        )
        Button(
            onClick = { callbacks.onAddNamedDevice(routeId, name) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("+ Named device")
        }
    }
}

private fun outputRouteDriverLabel(
    route: AudioOutputRouteState,
    selectedDriver: OutputDriver,
): String = if (
    route.id == AudioOutputRouteId.USB_EXCLUSIVE &&
    route.enabled &&
    selectedDriver == OutputDriver.USB_SYSTEM_BIT_PERFECT
) {
    selectedDriver.label
} else {
    route.id.label
}

private fun outputRouteDriverDescription(
    route: AudioOutputRouteState,
    selectedDriver: OutputDriver,
): String = if (
    route.id == AudioOutputRouteId.USB_EXCLUSIVE &&
    route.enabled &&
    selectedDriver == OutputDriver.USB_SYSTEM_BIT_PERFECT
) {
    "Android system direct USB output; userspace USB Exclusive is not active."
} else {
    route.id.description
}

internal fun outputStatusBadge(status: OutputConnectionStatus): AudioStatusBadge = AudioStatusBadge(
    label = status.label,
    tone = when (status) {
        OutputConnectionStatus.UNKNOWN,
        OutputConnectionStatus.AVAILABLE,
        OutputConnectionStatus.DISCONNECTED,
        -> AudioStatusBadgeTone.NEUTRAL
        OutputConnectionStatus.CONNECTED -> AudioStatusBadgeTone.ACTIVE
        OutputConnectionStatus.ACTIVE -> AudioStatusBadgeTone.SUCCESS
        OutputConnectionStatus.UNSUPPORTED -> AudioStatusBadgeTone.WARNING
        OutputConnectionStatus.ERROR -> AudioStatusBadgeTone.ERROR
    },
)

internal fun outputProfileBadges(profile: OutputDeviceProfile): List<AudioStatusBadge> = buildList {
    add(outputStatusBadge(profile.connectionStatus))
    addAll(bitPerfectBadges(profile.bitPerfect).filterNot { it.label == "Standard PCM pipeline" })
    if (profile.noDvc) add(AudioStatusBadge("No DVC", AudioStatusBadgeTone.WARNING))
    if (profile.noHeadroomGain) add(AudioStatusBadge("No Headroom", AudioStatusBadgeTone.WARNING))
    if (profile.noEquTone) add(AudioStatusBadge("No Equ / Tone"))
    if (profile.noDuck) add(AudioStatusBadge("No Duck"))
}.distinctBy(AudioStatusBadge::label)
