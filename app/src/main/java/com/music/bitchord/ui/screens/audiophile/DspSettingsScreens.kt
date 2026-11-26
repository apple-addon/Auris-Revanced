package com.music.bitchord.ui.screens.audiophile

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.playback.EqBandLayout
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.playback.audio.NativeOutputBackend
import com.music.bitchord.playback.audio.ReferenceMode
import com.music.bitchord.playback.audio.ReverbTuning
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudiophileEqualizerScreen(
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val enabled by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val mode by AppSettings.equalizerMode.collectAsStateWithLifecycle()
    val bands by AppSettings.equalizerBands.collectAsStateWithLifecycle()
    val preset by AppSettings.equalizerPreset.collectAsStateWithLifecycle()
    val preamp by AppSettings.equalizerPreampDb.collectAsStateWithLifecycle()
    val toneX by AppSettings.equalizerToneX.collectAsStateWithLifecycle()
    val toneY by AppSettings.equalizerToneY.collectAsStateWithLifecycle()
    val focused by AppSettings.equalizerFocused.collectAsStateWithLifecycle()
    val toneEnabled by AppSettings.toneEnabled.collectAsStateWithLifecycle()
    val bass by AppSettings.toneBassDb.collectAsStateWithLifecycle()
    val treble by AppSettings.toneTrebleDb.collectAsStateWithLifecycle()
    val balance by AppSettings.equalizerBalance.collectAsStateWithLifecycle()
    val bandCount by AppSettings.eqBandCount.collectAsStateWithLifecycle()
    val customBands by AppSettings.eqCustomBands.collectAsStateWithLifecycle()
    val customNum by AppSettings.eqCustomBandsNum.collectAsStateWithLifecycle()
    val customFirst by AppSettings.eqCustomFirstHz.collectAsStateWithLifecycle()
    val customLast by AppSettings.eqCustomLastHz.collectAsStateWithLifecycle()
    val bandLocks by AppSettings.eqBandLocks.collectAsStateWithLifecycle()
    val eqLabels by AppSettings.eqLabels.collectAsStateWithLifecycle()
    val toneLabels by AppSettings.toneLabels.collectAsStateWithLifecycle()
    val autoSave by AppSettings.eqAutoSave.collectAsStateWithLifecycle()
    val borderGain by AppSettings.dspBorderGain.collectAsStateWithLifecycle()
    val bassFreq by AppSettings.toneBassFreqHz.collectAsStateWithLifecycle()
    val trebleFreq by AppSettings.toneTrebleFreqHz.collectAsStateWithLifecycle()
    val bassQ by AppSettings.toneBassQ.collectAsStateWithLifecycle()
    val trebleQ by AppSettings.toneTrebleQ.collectAsStateWithLifecycle()
    val freqs = AppSettings.effectiveEqFrequenciesHz()
    var selectedBands by remember { mutableStateOf(setOf<Int>()) }
    var pendingDelete by remember { mutableStateOf(false) }
    var bandMenu by remember { mutableStateOf<Int?>(null) }
    var editValue by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun changed() = runtime.onEqualizerChanged()
    AudiophilePage("Equalizer", modifier, onBack, contentPadding) {
        AudiophileCard {
            AudiophileSwitchRow(
                "Equalizer",
                enabled,
                { AppSettings.setEqualizerEnabled(it); changed() },
                "Controls the existing synchronized app equalizer; the EQ Hub remains independent.",
            )
            AudiophileChoiceRow(
                title = "Mode",
                selected = mode,
                options = EqualizerMode.entries,
                optionLabel = { if (it == EqualizerMode.DYNAMIC) "Dynamic" else "Manual" },
                onSelect = { AppSettings.setEqualizerMode(it); changed() },
                enabled = enabled,
            )
        }
        AudiophileSectionLabel("Graphic Equalizer Bands")
        AudiophileCard {
            AudiophileChoiceRow(
                title = "Number of Bands",
                selected = bandCount,
                options = EqBandLayout.BAND_COUNTS.toList(),
                optionLabel = EqBandLayout::labelFor,
                onSelect = { AppSettings.setEqBandCount(it); changed() },
                subtitle = "High number of bands may require manual Block Size adjustment for the increased frequency resolution",
                enabled = enabled && !customBands,
            )
            AudiophileSwitchRow(
                "Enable Custom Bands",
                customBands,
                { AppSettings.setEqCustomBands(it); changed() },
                enabled = enabled,
            )
            if (customBands) {
                AudiophileSliderRow(
                    title = "Custom Number of Bands",
                    value = customNum.toFloat(),
                    valueRange = 5f..32f,
                    onChange = { AppSettings.setEqCustomBandsNum(it.roundToInt()); changed() },
                    valueLabel = "$customNum",
                    enabled = enabled,
                    quantum = 1f,
                    steps = 26,
                )
                AudiophileSliderRow(
                    title = "First Frequency",
                    value = customFirst.toFloat(),
                    valueRange = 5f..200f,
                    onChange = { AppSettings.setEqCustomFirstHz(it.roundToInt()); changed() },
                    valueLabel = "$customFirst Hz",
                    enabled = enabled,
                    quantum = 1f,
                    steps = 194,
                )
                AudiophileSliderRow(
                    title = "Last Frequency",
                    value = customLast.toFloat(),
                    valueRange = 14_000f..20_000f,
                    onChange = { AppSettings.setEqCustomLastHz(it.roundToInt()); changed() },
                    valueLabel = formatBandFrequency(customLast.toFloat()),
                    enabled = enabled,
                    quantum = 500f,
                    steps = 11,
                )
            }
            AudiophileActionButton("Add Band", enabled = enabled && freqs.size < EqBandLayout.MAX_COUNT) {
                AppSettings.addEqBand()
                changed()
            }
            AudiophileActionButton(
                "Delete Band${if (selectedBands.isNotEmpty()) "s" else ""}…",
                enabled = enabled && (selectedBands.isNotEmpty() || freqs.size > EqBandLayout.MIN_COUNT),
            ) {
                if (selectedBands.isEmpty()) {
                    // No selection: delete the highest band, asking first.
                    pendingDelete = true
                } else {
                    pendingDelete = true
                }
            }
            AudiophileNote("Long press a band for value edit, Select, Lock/unlock or Delete.")
        }
        // "Delete bands?" confirmation before any band is removed.
        if (pendingDelete) {
            AlertDialog(
                onDismissRequest = { pendingDelete = false },
                title = { Text("Delete bands?") },
                text = {
                    Text(
                        if (selectedBands.isEmpty()) {
                            "Delete the highest band (${freqs.size} → ${freqs.size - 1})?"
                        } else {
                            "Delete the ${selectedBands.size} selected band(s)?"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (selectedBands.isEmpty()) {
                            AppSettings.deleteEqBands(setOf(freqs.lastIndex))
                        } else {
                            AppSettings.deleteEqBands(selectedBands)
                        }
                        selectedBands = emptySet()
                        pendingDelete = false
                        changed()
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = false }) { Text("Cancel") } },
            )
        }
        // Per-band long-press menu: manual value entry, Select, Lock/unlock, Delete.
        bandMenu?.let { index ->
            val hz = freqs.getOrNull(index) ?: 0f
            AlertDialog(
                onDismissRequest = { bandMenu = null },
                title = { Text("${formatBandFrequency(hz)} Hz") },
                text = {
                    Column {
                        TextButton(onClick = { editValue = index to "%.1f".format(bands.getOrElse(index) { 0f }); bandMenu = null }) {
                            Text("Enter value manually")
                        }
                        TextButton(onClick = {
                            selectedBands = if (index in selectedBands) selectedBands - index else selectedBands + index
                            bandMenu = null
                        }) { Text(if (index in selectedBands) "Deselect" else "Select") }
                        TextButton(onClick = { AppSettings.toggleEqBandLock(index); bandMenu = null }) {
                            Text(if (index in bandLocks) "Unlock" else "Lock/unlock")
                        }
                        TextButton(onClick = { AppSettings.deleteEqBands(setOf(index)); selectedBands = selectedBands - index; bandMenu = null; changed() }) {
                            Text("Delete")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { bandMenu = null }) { Text("Close") } },
            )
        }
        // Long-press manual value entry dialog ("Long press value to edit").
        editValue?.let { (index, initial) ->
            var text by remember(index) { mutableStateOf(initial) }
            AlertDialog(
                onDismissRequest = { editValue = null },
                title = { Text("Band ${formatBandFrequency(freqs.getOrNull(index) ?: 0f)} Hz") },
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
                            val next = bands.toMutableList()
                            if (index < next.size) next[index] = gain
                            AppSettings.setEqualizerBands(next)
                            changed()
                        }
                        editValue = null
                    }) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { editValue = null }) { Text("Cancel") } },
            )
        }
        if (mode == EqualizerMode.DYNAMIC) {
            AudiophileSectionLabel("Dynamic Tone")
            AudiophileCard {
                AudiophileSliderRow(
                    "Tone X (Warm / Bright)",
                    toneX.toFloat(),
                    -5f..5f,
                    { AppSettings.setEqualizerTone(it.roundToInt(), toneY); changed() },
                    if (toneX > 0) "+$toneX" else toneX.toString(),
                    enabled = enabled,
                    quantum = 1f,
                    steps = 9,
                )
                AudiophileSliderRow(
                    "Tone Y (Scooped / Mid-Forward)",
                    toneY.toFloat(),
                    -5f..5f,
                    { AppSettings.setEqualizerTone(toneX, it.roundToInt()); changed() },
                    if (toneY > 0) "+$toneY" else toneY.toString(),
                    enabled = enabled,
                    quantum = 1f,
                    steps = 9,
                )
                AudiophileSwitchRow(
                    "Focused",
                    focused,
                    { AppSettings.setEqualizerFocused(it); changed() },
                    if (focused) "Narrow tone contour." else "Broad tone contour.",
                    enabled,
                )
            }
        } else {
            AudiophileSectionLabel("Manual Bands")
            AudiophileCard {
                AudiophileChoiceRow(
                    title = "Preset",
                    selected = preset,
                    options = EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM },
                    optionLabel = { it.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) },
                    onSelect = { AppSettings.setEqualizerPreset(it); changed() },
                    enabled = enabled,
                )
                freqs.forEachIndexed { index, hz ->
                    val gain = bands.getOrElse(index) { 0f }
                    Box(modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = { bandMenu = index },
                    )) {
                        AudiophileSliderRow(
                            title = "Band ${formatBandFrequency(hz)}${if (index in bandLocks) " (locked)" else ""}",
                            value = gain,
                            valueRange = -EqLayout.MANUAL_RANGE_DB..EqLayout.MANUAL_RANGE_DB,
                            onChange = { updated ->
                                val next = MutableList(freqs.size) { bands.getOrElse(it) { 0f } }
                                next[index] = updated
                                AppSettings.setEqualizerBands(next)
                                changed()
                            },
                            valueLabel = formatDb(gain),
                            enabled = enabled && index !in bandLocks,
                            quantum = 0.5f,
                            steps = 47,
                        )
                    }
                }
                AudiophileActionButton("Flat Bands", enabled = enabled) {
                    AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                    changed()
                }
            }
        }
        AudiophileSectionLabel("Gain & Tone")
        AudiophileCard {
            AudiophileSliderRow(
                "Preamp",
                preamp,
                -15f..15f,
                { AppSettings.setEqualizerPreampDb(it); changed() },
                formatDb(preamp),
                enabled = enabled,
                quantum = 0.5f,
                steps = 59,
            )
            AudiophileSwitchRow(
                "Tone",
                toneEnabled,
                { AppSettings.setToneEnabled(it); changed() },
                "Bass and treble after the selected equalizer mode.",
                enabled,
            )
            AudiophileSliderRow(
                "Bass",
                bass,
                -12f..12f,
                { AppSettings.setToneBassDb(it); changed() },
                formatDb(bass),
                enabled = enabled && toneEnabled,
                quantum = 0.5f,
                steps = 47,
            )
            AudiophileSliderRow(
                "Treble",
                treble,
                -12f..12f,
                { AppSettings.setToneTrebleDb(it); changed() },
                formatDb(treble),
                enabled = enabled && toneEnabled,
                quantum = 0.5f,
                steps = 47,
            )
            AudiophileSliderRow(
                "Balance",
                balance,
                -1f..1f,
                { AppSettings.setEqualizerBalance(it); changed() },
                String.format(Locale.getDefault(), "%+.2f", balance),
                enabled = enabled,
                quantum = 0.01f,
                steps = 199,
            )
            // Tone knob corners (reference peq_equ_tone.xml): bass/treble
            // frequency and Q, with long-press manual entry on the value.
            AudiophileNote("Long press value to edit")
            AudiophileSliderRow(
                "Bass Frequency",
                bassFreq,
                20f..2_000f,
                { AppSettings.setToneBassFreqHz(it); changed() },
                formatBandFrequency(bassFreq),
                enabled = enabled && toneEnabled,
                quantum = 10f,
                steps = 197,
            )
            AudiophileSliderRow(
                "Bass Q",
                bassQ,
                0.05f..8f,
                { AppSettings.setToneBassQ(it); changed() },
                "%.2f".format(bassQ),
                enabled = enabled && toneEnabled,
                quantum = 0.05f,
                steps = 158,
            )
            AudiophileSliderRow(
                "Treble Frequency",
                trebleFreq,
                500f..20_000f,
                { AppSettings.setToneTrebleFreqHz(it); changed() },
                formatBandFrequency(trebleFreq),
                enabled = enabled && toneEnabled,
                quantum = 100f,
                steps = 194,
            )
            AudiophileSliderRow(
                "Treble Q",
                trebleQ,
                0.05f..8f,
                { AppSettings.setToneTrebleQ(it); changed() },
                "%.2f".format(trebleQ),
                enabled = enabled && toneEnabled,
                quantum = 0.05f,
                steps = 158,
            )
            AudiophileActionButton("Restore Equalizer Defaults") {
                AppSettings.setEqualizerEnabled(false)
                AppSettings.setEqualizerMode(EqualizerMode.DYNAMIC)
                AppSettings.setEqualizerTone(0, 0)
                AppSettings.setEqualizerFocused(false)
                AppSettings.setEqualizerBalance(0f)
                AppSettings.setEqualizerPreampDb(0f)
                AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                AppSettings.setToneEnabled(true)
                AppSettings.setToneBassDb(0f)
                AppSettings.setToneTrebleDb(0f)
                changed()
            }
        }
        AudiophileSectionLabel("Labels & Saving")
        AudiophileCard {
            AudiophileChoiceRow(
                title = "Equalizer Values",
                selected = eqLabels,
                options = listOf(0, 1, 2),
                optionLabel = { listOf("Hidden", "dB", "%")[it] },
                onSelect = { AppSettings.setEqLabels(it) },
                enabled = enabled,
            )
            AudiophileChoiceRow(
                title = "Tone Values",
                selected = toneLabels,
                options = listOf(0, 1, 2),
                optionLabel = { listOf("Hidden", "dB", "%")[it] },
                onSelect = { AppSettings.setToneLabels(it) },
                enabled = enabled,
            )
            AudiophileSwitchRow("Auto Save", autoSave, { AppSettings.setEqAutoSave(it) }, "Auto save current preset", enabled)
            AudiophileSwitchRow(
                "Smooth Equalizer/Tone Gains",
                borderGain,
                { AppSettings.setDspBorderGain(it); changed() },
                "Automatically reduce Equalizer/Tone band gains near maximum volume to avoid overloading",
                enabled,
            )
        }
    }
}

@Composable
fun ResamplerScreen(
    store: AudiophileSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val settings = store.state.collectAsStateWithLifecycle().value
    val resampler = settings.resampler
    val perfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    fun update(value: ResamplerSettings) {
        store.updateResampler(value)
        runtime.onResamplerChanged(store.state.value.resampler)
    }
    AudiophilePage("Resampler", modifier, onBack, contentPadding) {
        if (perfect) AudiophileNote("Perfect BP forbids dither and follows the source rate whenever requested; saved values are preserved.")
        AudiophileCard {
            AudiophileChoiceRow(
                title = "Resampler Type",
                selected = resampler.quality,
                options = ResamplerQuality.entries,
                optionLabel = ResamplerQuality::label,
                onSelect = { update(resampler.copy(quality = it)) },
                subtitle = "Off / Passthrough never invokes a resampling stage.",
                enabled = !(perfect && settings.bitPerfect.followSampleRate),
            )
            AudiophileSliderRow(
                title = "Resampler Cutoff Frequency Ratio",
                value = resampler.cutoffPercent.toFloat(),
                valueRange = 80f..99f,
                onChange = { update(resampler.copy(cutoffPercent = it.roundToInt())) },
                valueLabel = String.format(Locale.getDefault(), "%.1f%%", resampler.cutoffPercent.toFloat()),
                subtitle = "Anti-alias filter corner relative to the Nyquist frequency.",
                enabled = resampler.quality != ResamplerQuality.OFF && !(perfect && settings.bitPerfect.followSampleRate),
                quantum = 1f,
                steps = 18,
            )
            AudiophileChoiceRow(
                title = "Dither",
                selected = resampler.dither,
                options = DitherMode.entries,
                optionLabel = DitherMode::label,
                onSelect = { update(resampler.copy(dither = it)) },
                subtitle = "Used only for integer PCM output; Float32 and Perfect BP force None at runtime.",
                enabled = !perfect,
            )
            AudiophileActionButton("Restore Defaults") {
                store.restoreResamplerDefaults()
                runtime.onResamplerChanged(store.state.value.resampler)
            }
        }
    }
}

@Composable
fun DvcScreen(
    store: AudiophileSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val dvc = store.state.collectAsStateWithLifecycle().value.dvc
    val perfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    fun update(value: DvcSettings) {
        store.updateDvc(value)
        runtime.onDvcChanged(store.state.value.dvc)
    }
    AudiophilePage("Direct Volume Control (DVC)", modifier, onBack, contentPadding) {
        if (perfect) AudiophileNote("Perfect BP bypasses DVC and headroom without erasing these settings.")
        AudiophileCard {
            AudiophileSwitchRow(
                "Enable Direct Volume Control",
                dvc.enabled,
                { update(dvc.copy(enabled = it)) },
                "Direct Volume Control keeps app volume and headroom in the PCM path.",
            )
            AudiophileSliderRow(
                title = "No DVC - Preamp Reduction",
                value = dvc.headroomDb,
                valueRange = -10f..0f,
                onChange = { update(dvc.copy(headroomDb = it)) },
                valueLabel = formatRgDb(dvc.headroomDb),
                subtitle = "Allows equalization/basses without distortion. Set to 0 dB to match other players’ volume, though this makes equalization less effective. Applied only when DVC is disabled for any reason.",
                enabled = dvc.enabled && !perfect,
                quantum = 0.5f,
                steps = 19,
            )
            AudiophileSwitchRow(
                "Compensate DVC Volume",
                dvc.compensateVolume,
                { update(dvc.copy(compensateVolume = it)) },
                "Specifically compensate low volume in DVC mode on some Android 15+ firmwares. Applied only when the issue is detected",
                enabled = !perfect,
            )
        }
        AudiophileSectionLabel("Bluetooth")
        AudiophileCard {
            AudiophileChoiceRow(
                title = "No DVC for Bluetooth Absolute Volume",
                selected = dvc.bluetoothPolicy,
                options = DvcBluetoothPolicy.entries,
                optionLabel = DvcBluetoothPolicy::label,
                onSelect = { update(dvc.copy(bluetoothPolicy = it)) },
                subtitle = "Automatically disable DVC for Bluetooth when Absolute Volume is detected or it's not possible to detect it.",
            )
            AudiophileActionButton("Restore Defaults") {
                store.restoreDvcDefaults()
                runtime.onDvcChanged(store.state.value.dvc)
            }
        }
    }
}

@Composable
fun ReferenceModeScreen(
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val qualityOptions by AppSettings.qualityOptions.collectAsStateWithLifecycle()
    val mode by AppSettings.referenceMode.collectAsStateWithLifecycle()
    val nativeBitPerfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    AudiophilePage("Reference Mode", modifier, onBack, contentPadding) {
        AudiophileCard {
            AudiophileSwitchRow(
                "Quality Options",
                qualityOptions,
                AudioConflictCoordinator::requestQualityOptions,
                "Enables the configured reference-engine stages. Existing conflict confirmation remains authoritative.",
            )
            AudiophileChoiceRow(
                title = "ReferenceMode",
                selected = mode,
                options = ReferenceMode.entries,
                optionLabel = ReferenceMode::label,
                onSelect = {
                    AppSettings.setReferenceMode(it)
                    runtime.onReferenceModeChanged(it)
                },
                subtitle = "Selects Poweramp style, HiBy style, Hybrid, or Bit-Perfect reference behavior.",
            )
            AudiophileInfoRow("Poweramp Stages", if (mode.runsPowerampStages) "Allowed" else "Bypassed")
            AudiophileInfoRow("HiBy Stages", if (mode.runsHibyStages) "Allowed" else "Bypassed")
            AudiophileInfoRow("Native Perfect BP", if (nativeBitPerfect) "Active" else "Inactive")
            AudiophileActionButton("Restore Reference Defaults") {
                AppSettings.setQualityOptions(false)
                AppSettings.setReferenceMode(ReferenceMode.POWERAMP)
                runtime.onReferenceModeChanged(ReferenceMode.POWERAMP)
            }
        }
    }
}

@Composable
fun AdvancedTweaksScreen(
    store: AudiophileSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val advanced = store.state.collectAsStateWithLifecycle().value.advanced
    val backend by AppSettings.nativeOutputBackend.collectAsStateWithLifecycle()
    val preferUsb by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    fun update(value: AdvancedTweaks) {
        store.updateAdvanced(value)
        runtime.onAdvancedTweaksChanged(store.state.value.advanced)
    }
    AudiophilePage("Advanced Tweaks", modifier, onBack, contentPadding) {
        AudiophileSectionLabel("Output Path")
        AudiophileCard {
            AudiophileChoiceRow(
                title = "Fallback Native Driver",
                selected = backend,
                options = NativeOutputBackend.entries,
                optionLabel = NativeOutputBackend::label,
                onSelect = {
                    AppSettings.setNativeOutputBackend(it)
                    runtime.onNativeOutputBackendChanged(it)
                },
                subtitle = "Used when the active route has no named-device or per-route driver override.",
            )
            AudiophileSwitchRow("Direct When Available", advanced.directWhenAvailable, { update(advanced.copy(directWhenAvailable = it)) }, "Request a measured direct path and expose any fallback in Audio Info.")
            AudiophileSwitchRow("Allow Offload", advanced.allowOffload, { update(advanced.copy(allowOffload = it)) }, "Permit compressed offload; DSP-dependent tracks must stay decoded.")
            AudiophileSwitchRow("USB Hardware Volume", advanced.usbHardwareVolume, { update(advanced.copy(usbHardwareVolume = it)) }, "Prefer the DAC hardware control when its endpoint advertises one.")
            AudiophileSwitchRow("Prefer USB DAC", preferUsb, AppSettings::setPreferUsbDac, "Prefer an attached USB audio output over the normal route.")
        }
        AudiophileSectionLabel("Presets")
        AudiophileCard {
            AudiophileActionButton("Reset Equalizer Presets") {
                AppSettings.setEqualizerPreset(EqualizerPreset.FLAT)
                AppSettings.setEqualizerBands(EqualizerPreset.FLAT.bands)
                AppSettings.setEqualizerPreampDb(0f)
                runtime.onEqualizerChanged()
            }
            AudiophileActionButton("Reset Reverb Presets") {
                AppSettings.setReverbTuning(ReverbTuning())
                AppSettings.setJamesDspReverbPreset(-1)
                runtime.onAdvancedTweaksChanged(store.state.value.advanced)
            }
        }
        AudiophileSectionLabel("Playback & Diagnostics")
        AudiophileCard {
            AudiophileSwitchRow("Reset EQ on Route Change", advanced.resetEqualizerOnRouteChange, { update(advanced.copy(resetEqualizerOnRouteChange = it)) }, "Apply the destination profile instead of carrying a route-specific curve forward.")
            AudiophileSwitchRow("Audio Diagnostics", advanced.audioDiagnostics, { update(advanced.copy(audioDiagnostics = it)) }, "Keep detailed output negotiation diagnostics available to the parent runtime.")
            AudiophileSwitchRow("Skip Silence", skipSilence, AppSettings::setSkipSilence, "Skip decoded silent regions where the playback pipeline supports it.")
            AudiophileSwitchRow("High Performance Mode", highPerformance, AppSettings::setHighPerformanceMode, "Request the app's sustained high-refresh performance policy.")
            AudiophileSwitchRow("Show Nerd Stats", nerdStats, AppSettings::setShowNerdStats, "Show codec, bitrate and sample-rate details on the player.")
            AudiophileActionButton("Restore Advanced Defaults") {
                store.restoreAdvancedDefaults()
                AppSettings.setNativeOutputBackend(NativeOutputBackend.AUDIO_TRACK)
                AppSettings.setPreferUsbDac(false)
                AppSettings.setSkipSilence(false)
                AppSettings.setHighPerformanceMode(false)
                AppSettings.setShowNerdStats(false)
                runtime.onAdvancedTweaksChanged(store.state.value.advanced)
                runtime.onNativeOutputBackendChanged(NativeOutputBackend.AUDIO_TRACK)
            }
        }
    }
}

private fun formatBandFrequency(hz: Float): String = when {
    hz < 1_000f -> "${hz.roundToInt()} Hz"
    hz % 1_000f == 0f -> "${(hz / 1_000f).roundToInt()} kHz"
    else -> String.format(Locale.getDefault(), "%.1f kHz", hz / 1_000f)
}
