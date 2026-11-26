package com.music.bitchord.ui.reference

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PowerampEqualizerPage(
    state: PowerampEqState,
    selectedBand: Int,
    presets: List<AudioPreset>,
    selectedPresetId: String?,
    onStateChange: (PowerampEqState) -> Unit,
    onSelectedBandChange: (Int) -> Unit,
    onOpenPresets: () -> Unit,
    onPresetAction: (ReferencePresetAction, AudioPreset?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReferenceSection {
            PowerampProcessingSwitches(state, onStateChange)
            PowerampPresetPill(
                presets = presets,
                selectedPresetId = selectedPresetId,
                onOpenPresets = onOpenPresets,
                onPresetAction = onPresetAction,
            )
            PowerampCompactStrip(state)
            ReferenceSliderRow(
                label = "Preamp",
                value = state.preampDb,
                valueRange = -24f..24f,
                onValueChange = { onStateChange(PowerampEqReducer.setPreamp(state, it)) },
                valueText = formatDb(state.preampDb),
                enabled = state.equEnabled,
                steps = 95,
                nudge = 0.5f,
            )
        }

        ReferenceSection(title = "10-band equalizer · dB") {
            ReferenceChoiceChips(
                options = listOf("Graphic", "Parametric"),
                selectedIndex = if (state.parametricMode) 1 else 0,
                onSelected = { onStateChange(state.copy(parametricMode = it == 1)) },
                enabled = state.equEnabled,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.bands.forEachIndexed { index, band ->
                    ReferenceVerticalFader(
                        label = formatFrequency(band.frequencyHz),
                        value = band.gainDb,
                        valueRange = PowerampEqDefaults.MIN_DB..PowerampEqDefaults.MAX_DB,
                        onValueChange = { gain ->
                            onStateChange(
                                PowerampEqReducer.updateBand(state, index) { it.copy(gainDb = gain) },
                            )
                        },
                        enabled = state.equEnabled && band.enabled,
                    )
                }
            }
            if (state.parametricMode) {
                PowerampParametricEditor(
                    state = state,
                    selectedBand = selectedBand,
                    onSelectedBandChange = onSelectedBandChange,
                    onStateChange = onStateChange,
                )
            }
        }

        ReferenceSection(title = "Tone") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReferenceDial(
                    label = "Bass",
                    value = state.bassDb,
                    valueRange = -12f..12f,
                    onValueChange = { onStateChange(PowerampEqReducer.setBass(state, it)) },
                    valueText = formatDb(state.bassDb),
                    enabled = state.equEnabled && state.toneEnabled,
                )
                ReferenceDial(
                    label = "Treble",
                    value = state.trebleDb,
                    valueRange = -12f..12f,
                    onValueChange = { onStateChange(PowerampEqReducer.setTreble(state, it)) },
                    valueText = formatDb(state.trebleDb),
                    enabled = state.equEnabled && state.toneEnabled,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerampProcessingSwitches(
    state: PowerampEqState,
    onStateChange: (PowerampEqState) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = state.equEnabled,
            onClick = { onStateChange(state.copy(equEnabled = !state.equEnabled)) },
            label = { Text("Equ") },
        )
        FilterChip(
            selected = state.toneEnabled,
            onClick = { onStateChange(state.copy(toneEnabled = !state.toneEnabled)) },
            label = { Text("Tone") },
        )
        FilterChip(
            selected = state.limiterEnabled,
            onClick = { onStateChange(state.copy(limiterEnabled = !state.limiterEnabled)) },
            label = { Text("Limit") },
        )
    }
}

@Composable
private fun PowerampPresetPill(
    presets: List<AudioPreset>,
    selectedPresetId: String?,
    onOpenPresets: () -> Unit,
    onPresetAction: (ReferencePresetAction, AudioPreset?) -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val selectedName = presets.firstOrNull { it.id == selectedPresetId }?.name ?: "Custom"
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = onOpenPresets,
            label = { Text(selectedName, maxLines = 1) },
            modifier = Modifier.weight(1f, fill = false),
        )
        Box {
            IconButton(onClick = { overflowExpanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Preset actions")
            }
            DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                PowerampPresetMenuItem("Browse presets") {
                    overflowExpanded = false
                    onOpenPresets()
                }
                PowerampPresetMenuItem("Save preset") {
                    overflowExpanded = false
                    onPresetAction(ReferencePresetAction.SAVE, null)
                }
                PowerampPresetMenuItem("Import preset") {
                    overflowExpanded = false
                    onPresetAction(ReferencePresetAction.IMPORT, null)
                }
                PowerampPresetMenuItem("Share preset") {
                    overflowExpanded = false
                    onPresetAction(ReferencePresetAction.SHARE, null)
                }
                PowerampPresetMenuItem("Reset equalizer") {
                    overflowExpanded = false
                    onPresetAction(ReferencePresetAction.RESET, null)
                }
            }
        }
    }
}

@Composable
private fun PowerampPresetMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun PowerampParametricEditor(
    state: PowerampEqState,
    selectedBand: Int,
    onSelectedBandChange: (Int) -> Unit,
    onStateChange: (PowerampEqState) -> Unit,
) {
    val bandIndex = selectedBand.coerceIn(state.bands.indices)
    val band = state.bands[bandIndex]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.bands.indices.forEach { index ->
                FilterChip(
                    selected = index == bandIndex,
                    onClick = { onSelectedBandChange(index) },
                    label = { Text("${index + 1}") },
                )
            }
        }
        ReferenceToggleRow(
            label = "Band ${bandIndex + 1} enabled",
            checked = band.enabled,
            onCheckedChange = { enabled ->
                onStateChange(
                    PowerampEqReducer.updateBand(state, bandIndex) { it.copy(enabled = enabled) },
                )
            },
            enabled = state.equEnabled,
        )
        ReferenceSliderRow(
            label = "Frequency",
            value = peqFrequencyToFraction(band.frequencyHz),
            valueRange = 0f..1f,
            onValueChange = { fraction ->
                onStateChange(
                    PowerampEqReducer.updateBand(state, bandIndex) {
                        it.copy(frequencyHz = peqFractionToFrequency(fraction))
                    },
                )
            },
            valueText = formatFrequency(band.frequencyHz),
            enabled = state.equEnabled && band.enabled,
        )
        ReferenceSliderRow(
            label = "Q",
            value = band.q,
            valueRange = PowerampEqDefaults.MIN_Q..PowerampEqDefaults.MAX_Q,
            onValueChange = { q ->
                onStateChange(PowerampEqReducer.updateBand(state, bandIndex) { it.copy(q = q) })
            },
            valueText = String.format(java.util.Locale.US, "%.2f", band.q),
            enabled = state.equEnabled && band.enabled,
        )
        TextButton(
            onClick = {
                onStateChange(
                    PowerampEqReducer.updateBand(state, bandIndex) {
                        PowerampEqBand(frequencyHz = PowerampEqDefaults.frequenciesHz[bandIndex])
                    },
                )
            },
            enabled = state.equEnabled,
        ) {
            Text("Reset band ${bandIndex + 1}")
        }
    }
}
