package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AudioOutputsSettingsPage(
    routes: List<AudioOutputRouteState>,
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        routes.forEach { route ->
            item(key = route.id.name) {
                AudioOutputRouteSummary(route, navigation, callbacks)
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
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
) {
    AudiophileSection(title = route.id.label) {
        AudiophileSwitchRow(
            label = "Use ${route.id.label}",
            subtitle = route.id.description,
            checked = route.enabled,
            enabled = route.available,
            onCheckedChange = { callbacks.onRouteEnabledChange(route.id, it) },
        )
        AudiophileBadges(listOf(outputStatusBadge(route.status)))
        if (route.statusDetail.isNotBlank()) {
            AudiophileValueRow(label = "Status", value = route.statusDetail)
        }
        AudiophileNavigationRow(
            title = "Configure ${route.id.label}",
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
    namedDeviceName: String,
    navigation: AudiophileNavigationCallbacks,
    callbacks: OutputSettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    AudiophilePageList(modifier = modifier.fillMaxSize()) {
        item { OutputRouteStatusSection(route, callbacks) }
        route.profiles.forEach { profile ->
            item(key = "${route.id.name}:${profile.id}") {
                OutputDeviceSummary(route.id, profile, navigation, callbacks)
            }
        }
        item { NamedDeviceSection(route.id, namedDeviceName, callbacks) }
        item {
            AudiophileRestoreButton(
                onClick = { callbacks.onRestoreRouteDefaults(route.id) },
                label = "Restore ${route.id.label} defaults",
            )
        }
    }
}

@Composable
private fun OutputRouteStatusSection(route: AudioOutputRouteState, callbacks: OutputSettingsCallbacks) {
    AudiophileSection(title = "Output status") {
        AudiophileBadges(listOf(outputStatusBadge(route.status)))
        AudiophileSwitchRow(
            label = "Output enabled",
            checked = route.enabled,
            enabled = route.available,
            onCheckedChange = { callbacks.onRouteEnabledChange(route.id, it) },
        )
        AudiophileValueRow(label = "Method", value = route.id.description)
        if (route.statusDetail.isNotBlank()) {
            AudiophileValueRow(label = "Detail", value = route.statusDetail)
        }
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
