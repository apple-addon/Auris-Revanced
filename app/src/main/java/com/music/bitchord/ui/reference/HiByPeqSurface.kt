package com.music.bitchord.ui.reference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** HiBy PEQ editor with a logarithmic 20 Hz…20 kHz graph and ten complete band editors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiByPeqSurface(
    state: PeqState,
    presets: List<AudioPreset>,
    selectedBand: Int,
    onSelectedBandChange: (Int) -> Unit,
    onStateChange: (PeqState) -> Unit,
    onPresetSelected: (AudioPreset) -> Unit,
    onAction: (HiByPresetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.normalized()
    val bandIndex = selectedBand.coerceIn(0, PeqDefaults.BAND_COUNT - 1)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReferenceSection {
            HiByProcessorHeader(
                title = "HiBy PEQ",
                enabled = model.enabled,
                presetName = model.presetName,
                presets = presets,
                onEnabledChange = { onStateChange(model.copy(enabled = it)) },
                onPresetSelected = onPresetSelected,
                onAction = onAction,
                onReset = { onStateChange(PeqReducer.reset(model)) },
            )
            ReferenceSliderRow(
                label = "Preamp",
                value = model.preampDb,
                valueRange = -12f..12f,
                onValueChange = { onStateChange(PeqReducer.setPreamp(model, it)) },
                valueText = formatDb(model.preampDb),
                enabled = model.enabled,
                steps = 47,
                nudge = 0.5f,
            )
        }
        ReferenceSection(title = "Response · −12…+12 dB") {
            PeqResponseGraph(
                state = model,
                selectedBand = bandIndex,
                onBandSelected = onSelectedBandChange,
                onStateChange = onStateChange,
            )
        }
        ReferenceSection(title = "Bands") {
            ScrollableTabRow(
                selectedTabIndex = bandIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp,
            ) {
                model.bands.forEachIndexed { index, band ->
                    Tab(
                        selected = index == bandIndex,
                        onClick = { onSelectedBandChange(index) },
                        text = { Text("${index + 1} · ${formatFrequency(band.frequencyHz)}", maxLines = 1) },
                    )
                }
            }
            PeqBandEditor(
                state = model,
                bandIndex = bandIndex,
                onStateChange = onStateChange,
            )
        }
    }
}

@Composable
private fun PeqBandEditor(
    state: PeqState,
    bandIndex: Int,
    onStateChange: (PeqState) -> Unit,
) {
    val band = state.bands[bandIndex]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReferenceToggleRow(
            label = "Band ${bandIndex + 1} enabled",
            checked = band.enabled,
            onCheckedChange = { enabled ->
                onStateChange(PeqReducer.updateBand(state, bandIndex) { it.copy(enabled = enabled) })
            },
            enabled = state.enabled,
        )
        PeqFilterTypePicker(
            selected = band.type,
            enabled = state.enabled && band.enabled,
            onSelected = { type ->
                onStateChange(PeqReducer.updateBand(state, bandIndex) { it.copy(type = type) })
            },
        )
        ReferenceSliderRow(
            label = "Frequency",
            value = peqFrequencyToFraction(band.frequencyHz),
            valueRange = 0f..1f,
            onValueChange = { fraction ->
                onStateChange(
                    PeqReducer.updateBand(state, bandIndex) {
                        it.copy(frequencyHz = peqFractionToFrequency(fraction))
                    },
                )
            },
            valueText = formatFrequency(band.frequencyHz),
            enabled = state.enabled && band.enabled,
        )
        ReferenceSliderRow(
            label = "Q",
            value = band.q,
            valueRange = PeqDefaults.MIN_Q..PeqDefaults.MAX_Q,
            onValueChange = { q ->
                onStateChange(PeqReducer.updateBand(state, bandIndex) { it.copy(q = q) })
            },
            valueText = String.format(Locale.US, "%.2f", band.q),
            enabled = state.enabled && band.enabled,
        )
        ReferenceSliderRow(
            label = "Gain",
            value = band.gainDb,
            valueRange = -12f..12f,
            onValueChange = { gain ->
                onStateChange(PeqReducer.updateBand(state, bandIndex) { it.copy(gainDb = gain) })
            },
            valueText = formatDb(band.gainDb),
            enabled = state.enabled && band.enabled,
            steps = 47,
            nudge = 0.5f,
        )
        TextButton(
            onClick = {
                onStateChange(
                    PeqReducer.updateBand(state, bandIndex) {
                        PeqBand(frequencyHz = PeqDefaults.frequenciesHz[bandIndex])
                    },
                )
            },
            enabled = state.enabled,
        ) {
            Text("Reset band ${bandIndex + 1}")
        }
    }
}

@Composable
private fun PeqFilterTypePicker(
    selected: PeqFilterType,
    enabled: Boolean,
    onSelected: (PeqFilterType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("Type: ${selected.label}")
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose filter type")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PeqFilterType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label) },
                    onClick = {
                        expanded = false
                        onSelected(type)
                    },
                )
            }
        }
    }
}
