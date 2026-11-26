package com.music.bitchord.ui.reference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Codec-neutral HiBy MSEB editor. Mount in a vertically scrolling parent. */
@Composable
fun HiByMsebSurface(
    state: MsebState,
    presets: List<AudioPreset>,
    onStateChange: (MsebState) -> Unit,
    onPresetSelected: (AudioPreset) -> Unit,
    onAction: (HiByPresetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.normalized()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReferenceSection {
            HiByProcessorHeader(
                title = "HiBy MSEB",
                enabled = model.enabled,
                presetName = model.presetName,
                presets = presets,
                onEnabledChange = { onStateChange(model.copy(enabled = it)) },
                onPresetSelected = onPresetSelected,
                onAction = onAction,
                onReset = { onStateChange(MsebReducer.reset(model)) },
            )
            ReferenceSliderRow(
                label = "Preamp",
                value = model.preampDb,
                valueRange = MsebReducer.MIN_PREAMP_DB..MsebReducer.MAX_PREAMP_DB,
                onValueChange = { onStateChange(MsebReducer.setPreamp(model, it)) },
                valueText = formatDb(model.preampDb),
                enabled = model.enabled,
                steps = 179,
                nudge = 0.5f,
            )
        }
        ReferenceSection(title = "MSEB sound character") {
            MsebControl.entries.forEachIndexed { index, control ->
                ReferenceSliderRow(
                    label = control.label,
                    value = model.values[index],
                    valueRange = MsebReducer.MIN_VALUE..MsebReducer.MAX_VALUE,
                    onValueChange = { onStateChange(MsebReducer.setValue(model, index, it)) },
                    valueText = formatMseb(model.values[index]),
                    enabled = model.enabled,
                    steps = 199,
                    nudge = 1f,
                    supportingText = "−100 ${control.negativeEndpoint} · +100 ${control.positiveEndpoint}",
                )
            }
        }
    }
}
