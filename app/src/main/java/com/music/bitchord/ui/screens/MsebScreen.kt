package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.playback.audio.HiByMsebData
import com.music.bitchord.playback.audio.HiByMsebV1Codec
import com.music.bitchord.playback.audio.MsebProcessor
import com.music.bitchord.ui.components.AurisSlider
import java.util.Locale
import kotlin.math.roundToInt

/** Compact, real-time controls for the ten MSEB dimensions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsebScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { QualityPresetStore(context) }
    val qualityOptions by AppSettings.qualityOptions.collectAsStateWithLifecycle()
    val msebEnabled by AppSettings.msebEnabled.collectAsStateWithLifecycle()
    val values by AppSettings.msebValues.collectAsStateWithLifecycle()
    val preampDb by AppSettings.msebPreampDb.collectAsStateWithLifecycle()
    val range by AppSettings.msebRange.collectAsStateWithLifecycle()
    val active = qualityOptions && msebEnabled

    var savedPresets by remember { mutableStateOf(store.loadMseb()) }
    var showPresets by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val presetOptions = remember(savedPresets) {
        listOf(QualityPresetOption(FLAT_QUALITY_PRESET_ID, NONE_PRESET_LABEL)) +
            savedPresets.map { QualityPresetOption(savedQualityPresetId(it.name), it.name) }
    }
    val selectedPresetId = matchingMsebPreset(savedPresets, values, range, preampDb)
    val currentPresetLabel = presetOptions.firstOrNull { it.id == selectedPresetId }?.label ?: CUSTOM_PRESET_LABEL

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
        ) {
            QualityScreenHeader(
                title = "MSEB",
                subtitle = if (qualityOptions) {
                    "Ten hearing-oriented controls"
                } else {
                    "Enable the master switch to apply the tuning"
                },
                enabled = active,
                onEnabledChange = { enabled ->
                    if (enabled) AudioConflictCoordinator.requestQualityOptions(true)
                    AppSettings.setMsebEnabled(enabled)
                },
                onReset = ::resetMseb,
            )
            Spacer(Modifier.height(10.dp))
            QualityPresetSelector(
                currentLabel = currentPresetLabel,
                onClick = { showPresets = true },
            )
            QualityPresetActions(
                onSave = { showSave = true },
                onImport = { showImport = true },
                onShare = {
                    shareQualityText(context, "Share MSEB preset") {
                        encodeMsebShare(values, range)
                    }
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Slide rod range adjustment",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                MSEB_RANGES.forEach { option ->
                    FilterChip(
                        selected = range == option,
                        onClick = { AppSettings.setMsebRange(option) },
                        label = { Text(msebRangeLabel(option)) },
                        enabled = active,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = "Changing range keeps every dimension value.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            QualitySlider(
                title = "Pre Amp",
                valueLabel = formatQualityDb(preampDb),
                value = preampDb,
                valueRange = -45f..45f,
                steps = 179,
                enabled = active,
                onChange = AppSettings::setMsebPreampDb,
            )
            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    MsebProcessor.NAMES.forEachIndexed { index, name ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        MsebDimensionControl(
                            name = name,
                            endpoints = MSEB_ENDPOINTS[index],
                            value = values.getOrElse(index) { 0f },
                            range = range,
                            enabled = active,
                            onChange = { AppSettings.setMsebValue(index, it) },
                        )
                    }
                }
            }
        }
    }

    if (showPresets) {
        QualityPresetOverlay(
            title = "MSEB presets",
            options = presetOptions,
            selectedId = selectedPresetId,
            onSelect = { id ->
                if (id == FLAT_QUALITY_PRESET_ID) {
                    resetMseb()
                } else {
                    savedPresets.firstOrNull { savedQualityPresetId(it.name) == id }?.let { preset ->
                        applyMsebPreset(preset.data, preset.preampDb)
                    }
                }
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }

    if (showSave) {
        SaveQualityPresetDialog(
            title = "Save MSEB preset",
            onDismiss = { showSave = false },
            onSave = { name ->
                try {
                    store.saveMseb(name, values, range, preampDb)
                    savedPresets = store.loadMseb()
                    showSave = false
                    qualityMessage(context, "Saved \"$name\"")
                } catch (error: Exception) {
                    qualityError(context, "Could not save MSEB preset", error)
                }
            },
        )
    }

    if (showImport) {
        ImportQualityPresetDialog(
            title = "Import MSEB preset",
            description = "Paste a share URI or its JSON. The current Pre Amp is kept.",
            onDismiss = { showImport = false },
            onImport = { text ->
                try {
                    val imported = decodeMsebImport(text)
                    AppSettings.setMsebValues(
                        FloatArray(MsebProcessor.DIMENSIONS) { imported.result[it].toFloat() },
                    )
                    AppSettings.setMsebRange(imported.range)
                    showImport = false
                    qualityMessage(context, "MSEB preset imported")
                } catch (error: Exception) {
                    qualityError(context, "Could not import MSEB preset", error)
                }
            },
        )
    }
}

@Composable
private fun MsebDimensionControl(
    name: String,
    endpoints: Pair<String, String>,
    value: Float,
    range: Int,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val limit = range.toFloat()
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(Locale.getDefault(), "%+d", value.roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - 1f).coerceIn(-limit, limit)) },
                enabled = enabled && value > -limit,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = "Decrease $name",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                AurisSlider(
                    value = value.coerceIn(-limit, limit),
                    onValueChange = { onChange(it.roundToInt().toFloat()) },
                    valueRange = -limit..limit,
                    steps = (range * 2 - 1).coerceAtLeast(0),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = endpoints.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Text(
                        text = endpoints.second,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                }
            }
            IconButton(
                onClick = { onChange((value + 1f).coerceIn(-limit, limit)) },
                enabled = enabled && value < limit,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Increase $name",
                )
            }
        }
    }
}

private fun resetMseb() {
    AppSettings.setMsebValues(FloatArray(MsebProcessor.DIMENSIONS))
    AppSettings.setMsebPreampDb(0f)
    AppSettings.setMsebRange(100)
}

private fun applyMsebPreset(data: HiByMsebData, preampDb: Float) {
    AppSettings.setMsebValues(
        FloatArray(MsebProcessor.DIMENSIONS) { data.result[it].toFloat() },
    )
    AppSettings.setMsebRange(data.range)
    AppSettings.setMsebPreampDb(preampDb)
}

private fun matchingMsebPreset(
    presets: List<SavedMsebPreset>,
    values: FloatArray,
    range: Int,
    preampDb: Float,
): String? {
    presets.firstOrNull { preset ->
        preset.preampDb == preampDb &&
            preset.data.range == range &&
            preset.data.result.indices.all { index ->
                values.getOrElse(index) { 0f }.roundToInt() == preset.data.result[index]
            }
    }?.let { return savedQualityPresetId(it.name) }

    val flat = values.all { it.roundToInt() == 0 } && preampDb == 0f && range == 100
    return FLAT_QUALITY_PRESET_ID.takeIf { flat }
}

private fun decodeMsebImport(text: String): HiByMsebData {
    val content = text.trim()
    require(content.isNotEmpty()) { "Share text is empty" }
    val data = if (content.startsWith(HiByMsebV1Codec.URI_PREFIX)) {
        HiByMsebV1Codec.decode(content)
    } else {
        HiByMsebV1Codec.decodeFromJson(content)
    }
    // Legacy shares used ±25/±50; snap them onto the nearest current range.
    val normalizedRange = when (data.range) {
        in MSEB_RANGES -> data.range
        25 -> 20
        50 -> 40
        else -> throw IllegalArgumentException(
            "Unsupported MSEB range ${data.range}; expected 20, 40, or 100",
        )
    }
    require(data.result.all { it in -100..100 }) {
        "MSEB dimensions must be between -100 and 100"
    }
    return data.copy(range = normalizedRange)
}

/** Slide rod range adjustment choices, in ascending order. */
internal val MSEB_RANGES = intArrayOf(20, 40, 100)

/** Reference labels: Excessive(±100), Middling (±40), Fine-tuning(±20). */
internal fun msebRangeLabel(range: Int): String = when (range) {
    100 -> "Excessive(±100)"
    40 -> "Middling (±40)"
    20 -> "Fine-tuning(±20)"
    else -> "Custom(±$range)"
}

/** Negative and positive endpoint labels, in [MsebProcessor.NAMES] order (HiBy strings). */
internal val MSEB_ENDPOINTS = listOf(
    "Cool / Bright" to "Warm / Dark",
    "Light" to "Deep",
    "Fast" to "Thumpy",
    "Crisp" to "Thick",
    "Recessed / Crisp" to "Forward / Radio Edit",
    "Detoxed" to "Intoxicating",
    "Soft" to "Crisp",
    "Soft" to "Crisp",
    "Slow / Musical" to "Fast / Hard",
    "Soft" to "Crisp",
)
