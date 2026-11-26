package com.music.bitchord.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.audio.ReverbTuning
import com.music.bitchord.ui.components.AurisSlider
import com.music.bitchord.ui.components.RotaryKnob
import kotlin.math.roundToInt

@Composable
internal fun PowerampEqualizerPage(
    showPresets: Boolean,
    onOpenMseb: (() -> Unit)? = null,
    onOpenPeq: (() -> Unit)? = null,
) {
    val enabled by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val bands by AppSettings.equalizerBands.collectAsStateWithLifecycle()
    val preamp by AppSettings.equalizerPreampDb.collectAsStateWithLifecycle()
    val toneEnabled by AppSettings.toneEnabled.collectAsStateWithLifecycle()
    val bass by AppSettings.toneBassDb.collectAsStateWithLifecycle()
    val treble by AppSettings.toneTrebleDb.collectAsStateWithLifecycle()
    val limiter by AppSettings.jamesDspLimiter.collectAsStateWithLifecycle()
    val bitPerfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    val bandCount by AppSettings.eqBandCount.collectAsStateWithLifecycle()
    val customBands by AppSettings.eqCustomBands.collectAsStateWithLifecycle()
    val customNum by AppSettings.eqCustomBandsNum.collectAsStateWithLifecycle()
    val peqEnabled by AppSettings.peqEnabled.collectAsStateWithLifecycle()
    var presets by remember { mutableStateOf(showPresets) }
    var overflow by remember { mutableStateOf(false) }
    var editBand by remember { mutableStateOf<Int?>(null) }

    // The active layout: standard count or the custom first/last range.
    val freqs = remember(bandCount, customBands, customNum, AppSettings.eqCustomFirstHz.value, AppSettings.eqCustomLastHz.value) {
        if (customBands) {
            AppSettings.effectiveEqFrequenciesHz()
        } else {
            com.music.bitchord.playback.EqBandLayout.frequenciesFor(bandCount)
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Preamp", style = MaterialTheme.typography.labelMedium)
                BandFader(preamp.coerceIn(-12f, 12f), enabled) { AppSettings.setEqualizerPreampDb(it) }
                Text("%.1f".format(preamp), style = MaterialTheme.typography.labelSmall)
            }
            freqs.forEachIndexed { index, hz ->
                val value = bands.getOrElse(index) { 0f }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatBandFrequency(hz), style = MaterialTheme.typography.labelMedium)
                    Box(modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { editBand = index },
                    )) {
                        BandFader(value, enabled) { updated ->
                            AppSettings.setEqualizerMode(EqualizerMode.MANUAL)
                            AppSettings.setEqualizerBands(bands.toMutableList().also { it[index] = updated })
                        }
                    }
                    Text("%.1f".format(value), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Long press a band value to edit it manually (reference: "Long press
        // value to edit"); confirmed values land on the same setter.
        editBand?.let { index ->
            var text by remember(index) { mutableStateOf("%.1f".format(bands.getOrElse(index) { 0f })) }
            AlertDialog(
                onDismissRequest = { editBand = null },
                title = { Text("${formatBandFrequency(freqs[index])} Hz") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Gain (dB)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        text.toFloatOrNull()?.let { gain ->
                            AppSettings.setEqualizerMode(EqualizerMode.MANUAL)
                            AppSettings.setEqualizerBands(bands.toMutableList().also { it[index] = gain })
                        }
                        editBand = null
                    }) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { editBand = null }) { Text("Cancel") } },
            )
        }
        Text(
            text = eqStateStrip(
                bitPerfect = bitPerfect,
                equalizer = enabled,
                tone = toneEnabled,
                limiter = limiter,
                bandCount = freqs.size,
                parametric = peqEnabled,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = enabled, onClick = { AppSettings.setEqualizerEnabled(!enabled) }, label = { Text("Equ") })
            FilterChip(selected = toneEnabled, onClick = { AppSettings.setToneEnabled(!toneEnabled) }, label = { Text("Tone") })
            FilterChip(
                selected = limiter,
                onClick = {
                    AppSettings.setJamesDspLimiter(!limiter)
                    if (!limiter) AppSettings.setJamesDspEnabled(true)
                },
                label = { Text("Limit") },
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { presets = true }) { Text("Preset") }
            }
            Box {
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    onOpenMseb?.let { open ->
                        DropdownMenuItem(text = { Text("MSEB") }, onClick = { overflow = false; open() })
                    }
                    onOpenPeq?.let { open ->
                        DropdownMenuItem(text = { Text("PEQ") }, onClick = { overflow = false; open() })
                    }
                    DropdownMenuItem(
                        text = { Text("Presets") },
                        onClick = { overflow = false; presets = true },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RotaryKnob("Bass", bass, -12f..12f, AppSettings::setToneBassDb, "${((bass / 12f) * 100).roundToInt()}%", enabled = enabled && toneEnabled)
            RotaryKnob("Treble", treble, -12f..12f, AppSettings::setToneTrebleDb, "${((treble / 12f) * 100).roundToInt()}%", enabled = enabled && toneEnabled)
        }
    }
    if (presets) PowerampPresetBrowser(onDismiss = { presets = false }, onOpenPeq = onOpenPeq)
}

/**
 * Live state strip under the band sliders, e.g. "NO DVC EQ 10 TON LMT" for the
 * graphic equalizer, or "NO DVC EQ PARAM 1 TON LMT" when the parametric side is
 * what's running.
 */
internal fun eqStateStrip(
    bitPerfect: Boolean,
    equalizer: Boolean,
    tone: Boolean,
    limiter: Boolean,
    bandCount: Int = EqLayout.DEFAULT_MANUAL_COUNT,
    parametric: Boolean = false,
): String =
    buildString {
        append(if (bitPerfect) "NO DVC" else "DVC")
        if (equalizer) append(if (parametric) " EQ PARAM $bandCount" else " EQ $bandCount")
        if (tone) append(" TON")
        if (limiter) append(" LMT")
    }

@Composable
internal fun PowerampVolumePage() {
    val balance by AppSettings.equalizerBalance.collectAsStateWithLifecycle()
    val stereo by AppSettings.jamesDspStereoLevel.collectAsStateWithLifecycle()
    val speed by AppSettings.playbackSpeed.collectAsStateWithLifecycle()
    val tempo by AppSettings.tempoEnabled.collectAsStateWithLifecycle()
    val mono by AppSettings.monoEnabled.collectAsStateWithLifecycle()
    val volume by AppSettings.playerVolume.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RotaryKnob("Balance", balance, -1f..1f, AppSettings::setEqualizerBalance, "%.2f".format(balance))
            RotaryKnob("Stereo Expand", stereo, 0f..80f, {
                AppSettings.setJamesDspStereoLevel(if (it < 20f) 0f else it)
                if (it >= 20f) AppSettings.setJamesDspEnabled(true)
            }, "${stereo.roundToInt()}%")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = tempo, onClick = { AppSettings.setTempoEnabled(!tempo) }, label = { Text("Tempo") })
            TextButton(onClick = { AppSettings.setPlaybackSpeed((speed - 0.05f).coerceIn(0.5f, 2f)) }) { Text("-") }
            RotaryKnob("Tempo", speed, 0.5f..2f, {
                AppSettings.setTempoEnabled(true)
                AppSettings.setPlaybackSpeed(it)
            }, "%.2fx".format(speed), modifier = Modifier.weight(1f), enabled = tempo)
            TextButton(onClick = { AppSettings.setPlaybackSpeed((speed + 0.05f).coerceIn(0.5f, 2f)) }) { Text("+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = mono, onClick = {
                if (!mono) AudioConflictCoordinator.requestQualityOptions(true)
                AppSettings.setMonoEnabled(!mono)
            }, label = { Text("Mono") })
            TextButton(onClick = {
                AppSettings.setEqualizerBalance(0f)
                AppSettings.setJamesDspStereoLevel(0f)
                AppSettings.setPlaybackSpeed(1f)
                AppSettings.setTempoEnabled(false)
                AppSettings.setMonoEnabled(false)
                AppSettings.setPlayerVolume(1f)
            }) { Text("Reset") }
        }
        RotaryKnob("Volume", volume, 0f..1f, AppSettings::setPlayerVolume, "${(volume * 100).roundToInt()}%", sizeDp = 140.dp)
    }
}

@Composable
internal fun PowerampReverbPage() {
    val tuning by AppSettings.reverbTuning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val store = remember(context) { QualityPresetStore(context) }
    var presets by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var savedPresets by remember { mutableStateOf(store.loadReverb()) }
    fun update(value: ReverbTuning) {
        if (value.enabled) AudioConflictCoordinator.requestQualityOptions(true)
        AppSettings.setJamesDspEnabled(true)
        AppSettings.setReverbTuning(value)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RotaryKnob("Damp", tuning.damp, 0f..1f, { update(tuning.copy(enabled = true, damp = it)) }, "%.2f".format(tuning.damp), enabled = tuning.enabled)
            RotaryKnob("Filter", tuning.filter, 0f..1f, { update(tuning.copy(enabled = true, filter = it)) }, "%.2f".format(tuning.filter), enabled = tuning.enabled)
            RotaryKnob("Fade", tuning.fade, 0f..1f, { update(tuning.copy(enabled = true, fade = it)) }, "%.2f".format(tuning.fade), enabled = tuning.enabled)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RotaryKnob("Pre-Delay", tuning.preDelay, 0f..1f, { update(tuning.copy(enabled = true, preDelay = it)) }, "%.2f".format(tuning.preDelay), enabled = tuning.enabled)
            RotaryKnob("Pre-Delay Mix", tuning.preDelayMix, 0f..1f, { update(tuning.copy(enabled = true, preDelayMix = it)) }, "%.2f".format(tuning.preDelayMix), enabled = tuning.enabled)
            RotaryKnob("Size", tuning.size, 0f..1f, { update(tuning.copy(enabled = true, size = it)) }, "%.2f".format(tuning.size), enabled = tuning.enabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = tuning.enabled, onClick = { update(tuning.copy(enabled = !tuning.enabled)) }, label = { Text("Reverb") })
            TextButton(onClick = { presets = true }) { Text("Preset") }
            TextButton(onClick = { save = true }) { Text("Save") }
            TextButton(onClick = { update(tuning.copy(damp = 0f, filter = 0f, fade = 0f, preDelay = 0f, preDelayMix = 0f, size = 0f, mix = 0f)) }) { Text("Reset") }
        }
        RotaryKnob("Mix", tuning.mix, 0f..1f, { update(tuning.copy(enabled = true, mix = it)) }, "%.2f".format(tuning.mix), enabled = tuning.enabled, sizeDp = 140.dp)
    }
    if (presets) {
        AlertDialog(
            onDismissRequest = { presets = false },
            title = { Text("Reverb Preset") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    savedPresets.forEach { preset ->
                        TextButton(onClick = {
                            update(preset.tuning)
                            presets = false
                        }) { Text(preset.name) }
                    }
                    listOf("Default" to 0, "Room" to 9, "Hall" to 3, "Large Hall" to 5, "Plate" to 15, "Long" to 17).forEach { (name, index) ->
                        TextButton(onClick = {
                            AppSettings.setJamesDspReverbPreset(index)
                            AppSettings.setReverbTuning(tuning.copy(enabled = false))
                            AppSettings.setJamesDspEnabled(true)
                            presets = false
                        }) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { presets = false }) { Text("Done") } },
        )
    }
    if (save) {
        SaveQualityPresetDialog(
            title = "Save reverb preset",
            onDismiss = { save = false },
            onSave = { name ->
                try {
                    store.saveReverb(name, tuning)
                    savedPresets = store.loadReverb()
                    save = false
                    qualityMessage(context, "Saved \"$name\"")
                } catch (error: Exception) {
                    qualityError(context, "Could not save reverb preset", error)
                }
            },
        )
    }
}

@Composable
private fun PowerampPresetBrowser(
    onDismiss: () -> Unit,
    onOpenPeq: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { QualityPresetStore(context) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Built-in") }
    var action by remember { mutableStateOf<EqualizerPreset?>(null) }
    var savedAction by remember { mutableStateOf<SavedEqPreset?>(null) }
    val presets = EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM && it.name.contains(query, true) }
    // The AutoEq group is real and queryable: the device->preset assignments in
    // the preset store. Tapping one applies its preset; long press deletes the
    // assignment.
    val autoEqAssignments = remember(filter, query) {
        if (filter != "AutoEq") {
            emptyList()
        } else {
            store.loadAutoEqAssignments().toList()
                .filter { (device, preset) -> device.contains(query, true) || preset.contains(query, true) }
                .sortedBy { it.second.lowercase() }
        }
    }
    val savedEqPresets = remember(filter) { if (filter == "User") store.loadEqPresets() else emptyList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Presets") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    presetFilterChips.forEach { name ->
                        FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                    }
                    TextButton(onClick = { filter = "Built-in"; query = "" }) { Text("Reset filters") }
                }
                if (filter == "User" || filter == "Parametric") {
                    TextButton(onClick = {
                        AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                        AppSettings.setEqualizerBands(EqualizerPreset.FLAT.bands)
                        onDismiss()
                    }) { Text("Graphic Default Preset") }
                    TextButton(onClick = {
                        onDismiss()
                        onOpenPeq?.invoke()
                    }) { Text("Parametric Default Preset") }
                }
                if (filter == "User") {
                    // Saved user EQ presets: tap applies, long press deletes.
                    savedEqPresets.forEach { saved ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                            AppSettings.setEqualizerBands(saved.bands)
                            AppSettings.setEqualizerPreampDb(saved.preampDb)
                            onDismiss()
                        }, onLongClick = { savedAction = saved }), verticalAlignment = Alignment.CenterVertically) {
                            Text(saved.name, modifier = Modifier.weight(1f).padding(12.dp))
                        }
                    }
                }
                if (filter == "AutoEq") {
                    if (autoEqAssignments.isEmpty()) {
                        Text("No AutoEq presets assigned yet", modifier = Modifier.padding(16.dp))
                    }
                    autoEqAssignments.forEach { (device, presetName) ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                            val builtin = EqualizerPreset.entries.firstOrNull {
                                it != EqualizerPreset.CUSTOM && it.name.equals(presetName, true)
                            }
                            val saved = store.loadEqPresets().firstOrNull { it.name.equals(presetName, true) }
                            when {
                                builtin != null -> AppSettings.setEqualizerPreset(builtin)
                                saved != null -> {
                                    AppSettings.setEqualizerBands(saved.bands)
                                    AppSettings.setEqualizerPreampDb(saved.preampDb)
                                }
                            }
                            onDismiss()
                        }, onLongClick = {
                            // Long press an AutoEq row removes the assignment.
                            val next = store.loadAutoEqAssignments().toMutableMap()
                            next.remove(device)
                            // Reuse the store's import/export round trip to persist.
                            store.clearAutoEqSuggestions()
                            next.forEach { (d, p) -> store.assignAutoEq(d, p) }
                        }), verticalAlignment = Alignment.CenterVertically) {
                            Text("$presetName · $device", modifier = Modifier.weight(1f).padding(12.dp))
                        }
                    }
                }
                if (filter == "Graphic" || filter == "Built-in" || filter == "User") {
                    presets.forEach { preset ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                            AppSettings.setEqualizerPreset(preset)
                            onDismiss()
                        }, onLongClick = { action = preset }), verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.width(54.dp).height(28.dp)) {
                                val color = Color.Green
                                preset.bands.forEachIndexed { index, gain ->
                                    val x = index.toFloat() / (preset.bands.size - 1).coerceAtLeast(1) * size.width
                                    val y = size.height / 2f - gain / 24f * size.height
                                    drawCircle(color, 2.5.dp.toPx(), Offset(x, y.coerceIn(0f, size.height)))
                                }
                            }
                            Text(preset.name.replace('_', ' '), modifier = Modifier.padding(12.dp))
                        }
                    }
                } else if (filter != "Graphic" && filter != "Built-in" && filter != "User" && filter != "Parametric") {
                    Text("No presets in this group", modifier = Modifier.padding(16.dp))
                }
                Text(
                    "Long press any item for the selection menu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    action?.let { preset ->
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(preset.name.replace('_', ' ')) },
            text = { Text("Apply this preset to the synchronized graphic EQ?") },
            confirmButton = { TextButton(onClick = { AppSettings.setEqualizerPreset(preset); action = null; onDismiss() }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
    }
    savedAction?.let { saved ->
        AlertDialog(
            onDismissRequest = { savedAction = null },
            title = { Text(saved.name) },
            text = { Text("Apply this saved preset, or delete it?") },
            confirmButton = { TextButton(onClick = {
                AppSettings.setEqualizerBands(saved.bands)
                AppSettings.setEqualizerPreampDb(saved.preampDb)
                savedAction = null
                onDismiss()
            }) { Text("Apply") } },
            dismissButton = {
                TextButton(onClick = {
                    store.deleteEqPreset(saved.name)
                    savedAction = null
                }) { Text("Delete") }
            },
        )
    }
}

/** Filter chips exactly as the reference preset overlay lists them. */
internal val presetFilterChips = listOf("User", "Built-in", "AutoEq", "Graphic", "Parametric", "Assigned")

internal fun formatBandFrequency(hz: Float): String = if (hz >= 1000f) "${(hz / 1000f).roundToInt()}K" else "${hz.roundToInt()}"
