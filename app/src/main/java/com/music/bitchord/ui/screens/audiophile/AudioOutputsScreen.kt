package com.music.bitchord.ui.screens.audiophile

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.EqualizerPreset
import java.util.Locale

@Composable
fun AudioOutputsScreen(
    store: AudioOutputProfilesStore,
    globalStore: AudiophileSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    crossfadeRequested: Boolean = false,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val profiles by store.state.collectAsStateWithLifecycle()
    val global = globalStore.state.collectAsStateWithLifecycle().value
    val globalPerfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    val equalizerRequested by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val toneRequested by AppSettings.toneEnabled.collectAsStateWithLifecycle()
    val replayGainRequested by AppSettings.replayGainEnabled.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val connected = rememberConnectedOutputs(context)
    var editing by remember { mutableStateOf<AudioOutputTarget?>(null) }

    fun effective(target: AudioOutputTarget, profile: OutputProfile) = AudiophileConflictPolicy.resolve(
        global = globalStore.state.value,
        target = target,
        profile = profile,
        globalPerfectBitPerfect = AppSettings.bitPerfect.value,
        equalizerRequested = AppSettings.equalizerEnabled.value,
        toneRequested = AppSettings.toneEnabled.value,
        replayGainRequested = AppSettings.replayGainEnabled.value,
        crossfadeRequested = crossfadeRequested,
    )

    fun update(target: AudioOutputTarget, profile: OutputProfile) {
        store.updateProfile(target, profile)
        val normalizedTarget = target.normalized()
        val stored = store.profile(normalizedTarget)
        runtime.onOutputProfileChanged(normalizedTarget, stored, effective(normalizedTarget, stored))
    }

    val editingTarget = editing?.let { store.storedProfile(it)?.target ?: it.normalized() }
    if (editingTarget != null) {
        val profile = store.profile(editingTarget)
        AudioOutputProfileEditor(
            target = editingTarget,
            profile = profile,
            global = global,
            globalPerfect = globalPerfect,
            onChange = { update(editingTarget, it) },
            onRestore = {
                store.restoreDefaults(editingTarget)
                runtime.onOutputProfileRemoved(editingTarget)
            },
            onBack = { editing = null },
            modifier = modifier,
            contentPadding = contentPadding,
        )
        return
    }

    AudiophilePage("Audio Outputs", modifier, onBack, contentPadding) {
        AudiophileNote("Auris may use different audio output drivers depending on the connected output device.")
        AudiophileNote("For the active named Bluetooth or USB device, a separate profile can be created with +. Profiles change persisted output policy, not Android's system route.")
        AudiophileCard {
            AudiophileInfoRow("Active Device", outputStatus.deviceName)
            AudiophileInfoRow("Active Route", outputStatus.routeKind.name)
            AudiophileInfoRow("Actual Driver", outputStatus.sink)
        }
        AudiophileSectionLabel("Outputs")
        AudiophileCard {
            AudioOutputRoute.entries.forEachIndexed { index, route ->
                val target = AudioOutputTarget.route(route)
                val stored = store.storedProfile(target)
                val live = if (outputStatus.routeKind.toOutputRoute() == route) {
                    liveOutputReadout(outputStatus.actualSampleRateHz, outputStatus.actualEncoding)
                } else {
                    null
                }
                AudiophileNavigationRow(
                    title = route.label,
                    subtitle = stored?.profile?.summary() ?: "Stock driver and processing defaults",
                    value = live ?: if (stored == null) "Defaults" else "Customized",
                    onClick = { editing = target },
                )
                if (index != AudioOutputRoute.entries.lastIndex) AudiophileDivider()
            }
            AudiophileActionButton("Restore Defaults") {
                store.restoreDefaults()
            }
        }

        val connectedTargets = connected
            .filter { it.name.isNotBlank() }
            .map(::namedTarget)
            .distinctBy { it.route to it.deviceKey }
        val connectedKeys = connectedTargets.mapTo(mutableSetOf()) { it.route to it.deviceKey }
        val disconnectedTargets = profiles.values
            .map(StoredAudioOutputProfile::target)
            .filter { it.isNamedDevice && (it.route to it.deviceKey) !in connectedKeys }
            .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
        if (connectedTargets.isNotEmpty() || disconnectedTargets.isNotEmpty()) {
            AudiophileSectionLabel("Named-device Profiles")
            AudiophileCard {
                connectedTargets.forEach { target ->
                    val device = connected.firstOrNull { it.name.isNotBlank() && namedTarget(it) == target }
                    AudiophileNavigationRow(
                        title = target.displayName,
                        subtitle = "${target.route.label} · ${if (device?.isActive == true) "Active" else "Connected"}",
                        value = if (store.storedProfile(target) == null) "Route defaults" else "Customized",
                        onClick = { editing = target },
                    )
                }
                disconnectedTargets.forEach { target ->
                    AudiophileNavigationRow(
                        title = target.displayName,
                        subtitle = "${target.route.label} · Disconnected; profile retained",
                        value = "Customized",
                        onClick = { editing = target },
                    )
                }
            }
        } else {
            AudiophileNote("Connect a named Bluetooth, USB or HDMI device to create a device-specific profile.")
        }
    }
}

@Composable
private fun AudioOutputProfileEditor(
    target: AudioOutputTarget,
    profile: OutputProfile,
    global: AudiophileSettings,
    globalPerfect: Boolean,
    onChange: (OutputProfile) -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val equalizerPreset by AppSettings.equalizerPreset.collectAsStateWithLifecycle()
    AudiophilePage(target.displayName, modifier, onBack, contentPadding) {
        AudiophileNote(if (target.isNamedDevice) "Named-device profile; it takes precedence over the ${target.route.label} route profile." else "Profile for every ${target.route.label} route without a named-device override.")
        val outputStatus = AudioOutputStatus.current.collectAsStateWithLifecycle().value
        val isActiveRoute = outputStatus.routeKind.toOutputRoute() == target.route
        AudiophileSectionLabel("Output Device")
        AudiophileCard {
            AudiophileInfoRow("Output Device", target.displayName)
            AudiophileInfoRow("Direct Volume Control (DVC)", if (global.dvc.enabled) "Enabled" else "Disabled")
            AudiophileInfoRow(
                "Status",
                if (isActiveRoute) "Active" else "Not the active output",
            )
            AudiophileInfoRow("Sample Rate", if (isActiveRoute) formatSampleRate(outputStatus.actualSampleRateHz) else "—")
            AudiophileInfoRow("Sample Format", if (isActiveRoute) AudioOutputStatus.encodingLabel(outputStatus) else "—")
            AudiophileChoiceRow(
                title = "Equalizer Preset",
                selected = profile.eqPreset,
                options = listOf<String?>(null) + EqualizerPreset.entries
                    .filter { it != EqualizerPreset.CUSTOM }
                    .map { it.name },
                optionLabel = { name ->
                    if (name == null) {
                        "Follow Global (${equalizerPreset.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)})"
                    } else {
                        name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
                    }
                },
                onSelect = { onChange(profile.copy(eqPreset = it)) },
                subtitle = "Assigned to this ${if (target.isNamedDevice) "device" else "route"} only; applied while this output is active without rewriting the global EQ.",
            )
        }
        AudiophileSectionLabel("Driver & Format")
        AudiophileCard {
            val drivers = OutputDriver.entries.filter { it != OutputDriver.USB_EXCLUSIVE || target.route == AudioOutputRoute.USB }
            AudiophileChoiceRow(
                title = "Output Driver",
                selected = profile.driver,
                options = drivers,
                optionLabel = OutputDriver::label,
                onSelect = { onChange(profile.copy(driver = it)) },
            )
            val nativeFloat = profile.driver == OutputDriver.AAUDIO || profile.driver == OutputDriver.OPENSL_ES
            AudiophileSwitchRow(
                "Float32 Sample Format",
                profile.float32,
                { onChange(profile.copy(float32 = it)) },
                if (nativeFloat) "Required by the selected native Float32 driver." else "Request 32-bit floating-point PCM instead of 16-bit PCM.",
                enabled = !nativeFloat,
            )
            val standardBuffers = listOf(0, 256, 512, 1_024, 2_048, 4_096, 8_192, 16_384)
            val bufferOptions = if (profile.bufferSizeFrames in standardBuffers) standardBuffers else standardBuffers + profile.bufferSizeFrames
            AudiophileChoiceRow(
                title = "Buffer Size",
                selected = profile.bufferSizeFrames,
                options = bufferOptions,
                optionLabel = { if (it == 0) "Preset: Normal, 50 ms" else "$it frames" },
                onSelect = { onChange(profile.copy(bufferSizeFrames = it)) },
                subtitle = "Tweak if audio skips.",
            )
            AudiophileSliderRow(
                title = "Custom Buffer",
                value = profile.bufferSizeFrames.toFloat(),
                valueRange = 0f..16_384f,
                onChange = { onChange(profile.copy(bufferSizeFrames = it.toInt())) },
                valueLabel = if (profile.bufferSizeFrames == 0) "Preset: Normal, 50 ms" else "${profile.bufferSizeFrames} frames",
                quantum = 256f,
                steps = 63,
            )
            AudiophileSliderRow(
                title = "Visualization/Lyrics Delay",
                value = profile.visualDelayMs.toFloat(),
                valueRange = -1_000f..1_000f,
                onChange = { onChange(profile.copy(visualDelayMs = it.toInt())) },
                valueLabel = "${profile.visualDelayMs} ms",
                subtitle = "Extra delay to apply to visualization/lyrics for a better sync: ${profile.visualDelayMs} ms",
                quantum = 10f,
                steps = 199,
            )
        }
        AudiophileSectionLabel("Output Policy")
        AudiophileCard {
            AudiophileSwitchRow("No DVC", profile.noDvc, { onChange(profile.copy(noDvc = it)) }, "Disable Direct Volume Control (DVC) for this output/device. Disabling DVC may be required for Hi-Res and other advanced output options to work.")
            AudiophileSwitchRow("No Headroom Gain Reduction", profile.noHeadroom, { onChange(profile.copy(noHeadroom = it)) }, "Don't reduce output gain when DVC is disabled. May cause distortion for high equ/tone gains.")
            AudiophileSwitchRow("No Equ/Tone", profile.noEquTone, { onChange(profile.copy(noEquTone = it)) }, "Disable equalizer/tone DSP for this output/device.")
            AudiophileSwitchRow("No Duck", profile.noDuck, { onChange(profile.copy(noDuck = it)) }, "Disable volume ducking for this output, e.g. when notifications cause issues with this output. Temporarily pause player instead.")
            AudiophileSwitchRow("Ignore Audio Focus", profile.noAf, { onChange(profile.copy(noAf = it)) }, "Ignore calls/short Audio Focus requests, including notification sounds and ringtones")
        }
        AudiophileSectionLabel("Bit Perfect Override")
        AudiophileCard {
            val override = profile.bitPerfectOverride
            AudiophileSwitchRow(
                "Override Global Bit Perfect",
                override != null,
                { enabled ->
                    onChange(
                        profile.copy(
                            bitPerfectOverride = if (enabled) {
                                BitPerfectOutputOverride.fromGlobal(global.bitPerfect, globalPerfect)
                            } else {
                                null
                            },
                        ),
                    )
                },
                if (override == null) "Following global Bit Perfect settings." else "This output has its own complete Bit Perfect policy.",
            )
            if (override != null) {
                AudiophileSwitchRow("64-bit Processing", override.process64Bit, { onChange(profile.copy(bitPerfectOverride = override.copy(process64Bit = it))) })
                AudiophileSwitchRow("Follow Source Sample Rate", override.followSampleRate, { onChange(profile.copy(bitPerfectOverride = override.copy(followSampleRate = it))) })
                AudiophileSwitchRow(
                    "DSD Remaster",
                    override.dsdRemaster,
                    { onChange(profile.copy(bitPerfectOverride = override.copy(dsdRemaster = it))) },
                    if (override.perfectBitPerfect) "Perfect BP suppresses remastering at runtime." else null,
                    enabled = !override.perfectBitPerfect,
                )
                AudiophileSwitchRow(
                    "Perfect BP",
                    override.perfectBitPerfect,
                    { onChange(profile.copy(bitPerfectOverride = override.copy(perfectBitPerfect = it))) },
                    "Perfect BP wins conflicts without erasing DVC, EQ, focus, fade or ReplayGain preferences.",
                )
                AudiophileActionButton(if (override.showMore) "Show Less" else "Show More") {
                    onChange(profile.copy(bitPerfectOverride = override.copy(showMore = !override.showMore)))
                }
                if (override.showMore) {
                    AudiophileInfoRow("Override Scope", if (target.isNamedDevice) target.displayName else target.route.label)
                    AudiophileInfoRow("DSD Remaster Effective", if (override.dsdRemaster && !override.perfectBitPerfect) "Enabled" else "Disabled")
                    AudiophileInfoRow("Crossfade Effective", if (override.perfectBitPerfect) "Bypassed" else "Stored policy")
                    AudiophileInfoRow("Duck Effective", if (override.perfectBitPerfect || profile.noDuck) "Bypassed" else "Focus policy")
                }
            }
            AudiophileActionButton("Restore Defaults", onClick = onRestore)
        }
    }
}

@Composable
private fun rememberConnectedOutputs(context: Context): List<AudioRouting.Device> {
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var outputs by remember(manager) { mutableStateOf(manager?.let(AudioRouting::outputs).orEmpty()) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        fun refresh() {
            outputs = AudioRouting.outputs(manager)
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refresh()
        }
        runCatching { manager.registerAudioDeviceCallback(callback, null) }
        refresh()
        onDispose {
            runCatching { manager.unregisterAudioDeviceCallback(callback) }
        }
    }
    return outputs
}

/**
 * The live readout shown on the active output's row, e.g. "Active (48 kHz 32 bit)".
 * [AudioFormat] encoding constants are compile-time inlined, so this stays
 * callable from plain JVM tests.
 */
internal fun liveOutputReadout(sampleRateHz: Int?, encoding: Int?): String {
    val rate = sampleRateHz?.takeIf { it > 0 } ?: return "Active"
    val bits = when (encoding) {
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        AudioFormat.ENCODING_PCM_16BIT -> 16
        else -> null
    } ?: return "Active (${rate / 1_000} kHz)"
    return "Active (${rate / 1_000} kHz $bits bit)"
}

private fun namedTarget(device: AudioRouting.Device): AudioOutputTarget {
    val route = device.kind.toOutputRoute()
    val name = device.name.trim()
    return AudioOutputTarget(
        route = route,
        deviceKey = "${device.kind.name}:${name.lowercase(Locale.ROOT)}",
        deviceName = name,
    ).normalized()
}

private fun AudioRouting.Kind.toOutputRoute(): AudioOutputRoute = when (this) {
    AudioRouting.Kind.PHONE -> AudioOutputRoute.PHONE
    AudioRouting.Kind.WIRED -> AudioOutputRoute.WIRED
    AudioRouting.Kind.USB -> AudioOutputRoute.USB
    AudioRouting.Kind.BLUETOOTH -> AudioOutputRoute.BLUETOOTH
    AudioRouting.Kind.HDMI -> AudioOutputRoute.HDMI
    AudioRouting.Kind.OTHER -> AudioOutputRoute.OTHER
}

private fun OutputProfile.summary(): String = buildString {
    append(driver.label)
    append(if (float32) " · Float32" else " · PCM16")
    if (noDvc) append(" · No DVC")
    if (noHeadroom) append(" · No Headroom")
    if (noEquTone) append(" · No EquTone")
    if (noDuck) append(" · No Duck")
    if (noAf) append(" · No AF")
    eqPreset?.let { append(" · EQ ${it.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)}") }
    if (bitPerfectOverride != null) append(" · BP override")
}
