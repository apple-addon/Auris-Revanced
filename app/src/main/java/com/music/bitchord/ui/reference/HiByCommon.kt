package com.music.bitchord.ui.reference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun HiByProcessorHeader(
    title: String,
    enabled: Boolean,
    presetName: String,
    presets: List<AudioPreset>,
    onEnabledChange: (Boolean) -> Unit,
    onPresetSelected: (AudioPreset) -> Unit,
    onAction: (HiByPresetAction) -> Unit,
    onReset: () -> Unit,
) {
    var presetMenuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) "Processing enabled" else "Processing bypassed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Box {
            AssistChip(
                onClick = { presetMenuExpanded = true },
                label = { Text(presetName, maxLines = 1) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Preset actions") },
            )
            DropdownMenu(
                expanded = presetMenuExpanded,
                onDismissRequest = { presetMenuExpanded = false },
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            presetMenuExpanded = false
                            onPresetSelected(preset)
                        },
                    )
                }
                if (presets.isNotEmpty()) HorizontalDivider()
                HiByActionMenuItem("Save preset") {
                    presetMenuExpanded = false
                    onAction(HiByPresetAction.SAVE)
                }
                HiByActionMenuItem("Import preset") {
                    presetMenuExpanded = false
                    onAction(HiByPresetAction.IMPORT)
                }
                HiByActionMenuItem("Share preset") {
                    presetMenuExpanded = false
                    onAction(HiByPresetAction.SHARE)
                }
                HorizontalDivider()
                HiByActionMenuItem("Reset") {
                    presetMenuExpanded = false
                    onReset()
                }
            }
        }
    }
}

@Composable
private fun HiByActionMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}
