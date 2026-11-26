package com.music.bitchord.ui.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PowerampPresetBrowser(
    uiState: PowerampEqUiState,
    presets: List<AudioPreset>,
    selectedPresetId: String?,
    onUiStateChange: (PowerampEqUiState) -> Unit,
    onPresetSelected: (AudioPreset) -> Unit,
    onPresetAction: (ReferencePresetAction, AudioPreset?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = uiState.normalized()
    val visiblePresets = filterAudioPresets(presets, ui.presetQuery, ui.presetFilter)
    val previewGroups = groupAudioPresets(visiblePresets)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Equalizer presets",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close presets")
                    }
                }
                OutlinedTextField(
                    value = ui.presetQuery,
                    onValueChange = { onUiStateChange(ui.copy(presetQuery = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search presets") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.presetQuery.isNotEmpty()) {
                            IconButton(onClick = { onUiStateChange(ui.copy(presetQuery = "")) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PowerampPresetFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = filter != PowerampPresetFilter.RESET && ui.presetFilter == filter,
                            onClick = {
                                onUiStateChange(
                                    if (filter == PowerampPresetFilter.RESET) {
                                        ui.resetPresetFilters()
                                    } else {
                                        ui.copy(presetFilter = filter)
                                    },
                                )
                            },
                            label = { Text(filter.label) },
                        )
                    }
                }
                Text(
                    text = "Tap to apply · hold for actions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    previewGroups.forEach { group ->
                        item(key = "header_${group.title}") {
                            Text(
                                text = group.title,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(group.presets, key = { "${group.title}:${it.id}" }) { preset ->
                            PowerampPresetRow(
                                preset = preset,
                                selected = preset.id == selectedPresetId,
                                onApply = {
                                    onPresetSelected(preset)
                                    onUiStateChange(ui.copy(presetsVisible = false, contextPresetId = null))
                                },
                                onLongPress = { onUiStateChange(ui.copy(contextPresetId = preset.id)) },
                            )
                        }
                    }
                    if (visiblePresets.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = "No presets match this search.",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                PresetBrowserFooter(onPresetAction)
            }
        }
    }

    val contextPreset = presets.firstOrNull { it.id == ui.contextPresetId }
    if (contextPreset != null) {
        PresetContextDialog(
            preset = contextPreset,
            onDismiss = { onUiStateChange(ui.copy(contextPresetId = null)) },
            onApply = {
                onPresetSelected(contextPreset)
                onUiStateChange(ui.copy(presetsVisible = false, contextPresetId = null))
            },
            onAction = { action ->
                onPresetAction(action, contextPreset)
                onUiStateChange(ui.copy(contextPresetId = null))
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PowerampPresetRow(
    preset: AudioPreset,
    selected: Boolean,
    onApply: () -> Unit,
    onLongPress: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onApply,
                onLongClick = onLongPress,
                onLongClickLabel = "Actions for ${preset.name}",
            ),
        headlineContent = { Text(preset.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    text = preset.subtitle ?: preset.deviceName ?: presetTagsLabel(preset),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preset.previewGainsDb.isNotEmpty()) {
                    PowerampPresetPreview(preset.previewGainsDb)
                }
            }
        },
        trailingContent = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetContextDialog(
    preset: AudioPreset,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onAction: (ReferencePresetAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preset.name) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onApply) { Text("Apply") }
                TextButton(onClick = { onAction(ReferencePresetAction.DUPLICATE) }) { Text("Duplicate") }
                TextButton(onClick = { onAction(ReferencePresetAction.SHARE) }) { Text("Share") }
                if (AudioPresetTag.USER in preset.tags) {
                    TextButton(onClick = { onAction(ReferencePresetAction.RENAME) }) { Text("Rename") }
                    TextButton(onClick = { onAction(ReferencePresetAction.DELETE) }) { Text("Delete") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PresetBrowserFooter(onPresetAction: (ReferencePresetAction, AudioPreset?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onPresetAction(ReferencePresetAction.SAVE, null) }) { Text("Save") }
        TextButton(onClick = { onPresetAction(ReferencePresetAction.IMPORT, null) }) { Text("Import") }
        TextButton(onClick = { onPresetAction(ReferencePresetAction.SHARE, null) }) { Text("Share") }
    }
}

@Composable
private fun PowerampPresetPreview(gainsDb: List<Float>) {
    val curve = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Canvas(
        modifier = Modifier
            .padding(top = 4.dp)
            .width(120.dp)
            .height(24.dp),
    ) {
        drawLine(grid, start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f))
        val points = gainsDb.mapIndexed { index, gain ->
            val x = if (gainsDb.size == 1) size.width / 2f else index * size.width / (gainsDb.size - 1)
            val y = size.height / 2f - gain.coerceIn(-15f, 15f) / 15f * size.height * 0.45f
            androidx.compose.ui.geometry.Offset(x, y)
        }
        for (index in 1 until points.size) {
            drawLine(curve, points[index - 1], points[index], strokeWidth = 2.dp.toPx())
        }
        if (points.size == 1) drawCircle(curve, radius = 2.dp.toPx(), center = points.first())
    }
}

private fun presetTagsLabel(preset: AudioPreset): String {
    val labels = buildList {
        if (AudioPresetTag.USER in preset.tags) add("User")
        if (AudioPresetTag.BUILT_IN in preset.tags) add("Built-in")
        if (AudioPresetTag.AUTO_EQ in preset.tags) add("AutoEq")
        if (AudioPresetTag.GRAPHIC in preset.tags) add("Graphic")
        if (AudioPresetTag.PARAMETRIC in preset.tags) add("Parametric")
        if (AudioPresetTag.ASSIGNED in preset.tags) add("Assigned")
        if (AudioPresetTag.DEVICE in preset.tags) add("Device")
    }
    return if (labels.isEmpty()) "Preset" else labels.joinToString(" · ")
}
