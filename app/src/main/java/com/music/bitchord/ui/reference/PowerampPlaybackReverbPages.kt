package com.music.bitchord.ui.reference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PowerampPlaybackPage(
    state: PowerampEqState,
    onStateChange: (PowerampEqState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReferenceSection(title = "Stereo") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ReferenceDial(
                    label = "Balance",
                    value = state.balance,
                    valueRange = -1f..1f,
                    onValueChange = { onStateChange(PowerampEqReducer.setBalance(state, it)) },
                    modifier = Modifier.weight(1f),
                    valueText = balanceLabel(state.balance),
                )
                ReferenceDial(
                    label = "Stereo expansion",
                    value = state.stereoWidth,
                    valueRange = 0f..1f,
                    onValueChange = { onStateChange(PowerampEqReducer.setStereoWidth(state, it)) },
                    modifier = Modifier.weight(1f),
                    valueText = formatPercent(state.stereoWidth),
                )
            }
            ReferenceToggleRow(
                label = "Mono",
                subtitle = "Combine left and right channels.",
                checked = state.monoEnabled,
                onCheckedChange = { onStateChange(state.copy(monoEnabled = it)) },
            )
        }
        ReferenceSection(title = "Playback & output") {
            ReferenceSliderRow(
                label = "Tempo",
                value = state.tempo,
                valueRange = 0.5f..3f,
                onValueChange = { onStateChange(PowerampEqReducer.setTempo(state, it)) },
                valueText = String.format(Locale.US, "%.2f×", state.tempo),
                steps = 49,
                nudge = 0.05f,
            )
            TextButton(onClick = { onStateChange(PowerampEqReducer.setTempo(state, 1f)) }) {
                Text("Reset tempo to 1.00×")
            }
            ReferenceDial(
                label = "Volume",
                value = state.volume,
                valueRange = 0f..1f,
                onValueChange = { onStateChange(PowerampEqReducer.setVolume(state, it)) },
                modifier = Modifier.fillMaxWidth(),
                valueText = formatPercent(state.volume),
                diameter = 168.dp,
            )
        }
    }
}

@Composable
internal fun PowerampReverbPage(
    state: PowerampEqState,
    onStateChange: (PowerampEqState) -> Unit,
) {
    val reverb = state.reverb
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReferenceSection(title = "Reverb") {
            ReferenceToggleRow(
                label = "Reverb enabled",
                checked = reverb.enabled,
                onCheckedChange = { enabled ->
                    onStateChange(
                        PowerampEqReducer.updateReverb(state) { it.copy(enabled = enabled) },
                    )
                },
            )
            PowerampReverbDials(state, onStateChange)
        }
        ReferenceSection(title = "Output mix") {
            ReferenceDial(
                label = "Mix",
                value = reverb.mix,
                valueRange = 0f..1f,
                onValueChange = { mix ->
                    onStateChange(PowerampEqReducer.updateReverb(state) { it.copy(mix = mix) })
                },
                modifier = Modifier.fillMaxWidth(),
                valueText = formatReverbValue(reverb.mix),
                enabled = reverb.enabled,
            )
            PowerampReverbActions(state, onStateChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerampReverbDials(
    state: PowerampEqState,
    onStateChange: (PowerampEqState) -> Unit,
) {
    val reverb = state.reverb
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        maxItemsInEachRow = 3,
    ) {
        PowerampReverbDial("Damp", reverb.damp, reverb.enabled) { value ->
            updateReverbValue(state, onStateChange) { copy(damp = value) }
        }
        PowerampReverbDial("Filter", reverb.filter, reverb.enabled) { value ->
            updateReverbValue(state, onStateChange) { copy(filter = value) }
        }
        PowerampReverbDial("Fade", reverb.fade, reverb.enabled) { value ->
            updateReverbValue(state, onStateChange) { copy(fade = value) }
        }
        PowerampReverbDial("Pre-Delay", reverb.preDelay, reverb.enabled) { value ->
            updateReverbValue(state, onStateChange) { copy(preDelay = value) }
        }
        PowerampReverbDial("Pre-Delay Mix", reverb.preDelayMix, reverb.enabled) { value ->
            updateReverbValue(state, onStateChange) { copy(preDelayMix = value) }
        }
        PowerampReverbDial("Size", reverb.size, reverb.enabled) { value ->
            updateReverbValue(state, onStateChange) { copy(size = value) }
        }
    }
}

@Composable
private fun PowerampReverbDial(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    ReferenceDial(
        label = label,
        value = value,
        valueRange = 0f..1f,
        onValueChange = onValueChange,
        valueText = formatReverbValue(value),
        enabled = enabled,
        modifier = Modifier.width(92.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerampReverbActions(
    state: PowerampEqState,
    onStateChange: (PowerampEqState) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = { onStateChange(state.copy(reverb = PowerampEqDefaults.roomPreset)) }) {
            Text("Small room")
        }
        TextButton(onClick = { onStateChange(state.copy(reverb = PowerampEqDefaults.hallPreset)) }) {
            Text("Large hall")
        }
        TextButton(onClick = { onStateChange(PowerampEqReducer.resetReverb(state)) }) {
            Text("Reset")
        }
    }
}

private fun updateReverbValue(
    state: PowerampEqState,
    onStateChange: (PowerampEqState) -> Unit,
    update: PowerampReverbState.() -> PowerampReverbState,
) {
    onStateChange(PowerampEqReducer.updateReverb(state) { it.update() })
}

private fun formatReverbValue(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun balanceLabel(value: Float): String = when {
    abs(value) < 0.025f -> "Center"
    value < 0f -> "L ${(abs(value) * 100f).roundToInt()}%"
    else -> "R ${(value * 100f).roundToInt()}%"
}
