package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.playback.audio.BiquadKind
import com.music.bitchord.playback.audio.HiByPeqV1Codec
import com.music.bitchord.playback.audio.ParametricEqProcessor
import com.music.bitchord.playback.audio.PeqBand
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Ten-band parametric EQ with a live RBJ response and interoperable presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeqScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { QualityPresetStore(context) }
    val qualityOptions by AppSettings.qualityOptions.collectAsStateWithLifecycle()
    val peqEnabled by AppSettings.peqEnabled.collectAsStateWithLifecycle()
    val bands by AppSettings.peqBands.collectAsStateWithLifecycle()
    val preampDb by AppSettings.peqPreampDb.collectAsStateWithLifecycle()
    val active = qualityOptions && peqEnabled

    var selectedBand by rememberSaveable { mutableStateOf(0) }
    var savedPresets by remember { mutableStateOf(store.loadPeq()) }
    var showPresets by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showTypes by remember { mutableStateOf(false) }

    val bandIndex = selectedBand.coerceIn(0, ParametricEqProcessor.BAND_COUNT - 1)
    val band = bands.getOrElse(bandIndex) { ParametricEqProcessor.DEFAULT_BANDS[bandIndex] }
    val presetOptions = remember(savedPresets) {
        listOf(QualityPresetOption(FLAT_QUALITY_PRESET_ID, NONE_PRESET_LABEL)) +
            savedPresets.map {
                QualityPresetOption(savedQualityPresetId(it.data.name), it.data.name)
            }
    }
    val selectedPresetId = matchingPeqPreset(savedPresets, bands, preampDb)
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
                title = "Parametric equalizer",
                subtitle = if (qualityOptions) {
                    "Frequency, Q and gain for ten bands"
                } else {
                    "Enable the master switch to apply the tuning"
                },
                enabled = active,
                onEnabledChange = { enabled ->
                    if (enabled) AudioConflictCoordinator.requestQualityOptions(true)
                    AppSettings.setPeqEnabled(enabled)
                },
                onReset = ::resetPeq,
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
                    shareQualityText(context, "Share PEQ preset") {
                        encodePeqShare(
                            name = if (selectedPresetId == null) "Auris PEQ" else currentPresetLabel,
                            preampDb = preampDb,
                            bands = bands,
                        )
                    }
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Frequency response (dB)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            PeqResponseGraph(
                bands = bands,
                preampDb = preampDb,
                active = active,
            )
            QualitySlider(
                title = "Pre Amp",
                valueLabel = formatPeqDb(preampDb),
                value = preampDb,
                valueRange = -15f..15f,
                steps = 59,
                enabled = active,
                onChange = AppSettings::setPeqPreampDb,
            )
            Spacer(Modifier.height(6.dp))

            LazyRow(modifier = Modifier.fillMaxWidth()) {
                items(ParametricEqProcessor.BAND_COUNT) { index ->
                    FilterChip(
                        selected = bandIndex == index,
                        onClick = { selectedBand = index },
                        label = { Text("Band$index") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Band$bandIndex",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Enable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = band.enabled,
                            onCheckedChange = { enabled ->
                                AppSettings.setPeqBand(bandIndex, band.copy(enabled = enabled))
                            },
                            enabled = active,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showTypes = true },
                            enabled = active,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Type",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = band.kind.peqLabel(),
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropDown,
                                contentDescription = "Choose band type",
                            )
                        }
                        DropdownMenu(
                            expanded = showTypes,
                            onDismissRequest = { showTypes = false },
                        ) {
                            BiquadKind.entries.forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(kind.peqLabel()) },
                                    onClick = {
                                        AppSettings.setPeqBand(bandIndex, band.copy(kind = kind))
                                        showTypes = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    PeqLogSlider(
                        title = "Frequency",
                        valueLabel = formatPeqFrequencyHz(band.frequencyHz),
                        value = band.frequencyHz,
                        minimum = ParametricEqProcessor.MIN_HZ,
                        maximum = ParametricEqProcessor.MAX_HZ,
                        enabled = active,
                        onChange = { frequency ->
                            AppSettings.setPeqBand(
                                bandIndex,
                                band.copy(frequencyHz = frequency.roundToInt().toFloat()),
                            )
                        },
                    )
                    PeqLogSlider(
                        title = "Q",
                        valueLabel = String.format(Locale.getDefault(), "%.2f", band.q),
                        value = band.q,
                        minimum = ParametricEqProcessor.MIN_Q,
                        maximum = ParametricEqProcessor.MAX_Q,
                        enabled = active,
                        onChange = { q ->
                            AppSettings.setPeqBand(
                                bandIndex,
                                band.copy(q = (q * 100f).roundToInt() / 100f),
                            )
                        },
                    )
                    QualitySlider(
                        title = "Gain",
                        valueLabel = formatPeqDb(band.gainDb),
                        value = band.gainDb,
                        valueRange = -ParametricEqProcessor.MAX_GAIN_DB..
                            ParametricEqProcessor.MAX_GAIN_DB,
                        steps = 59,
                        enabled = active,
                        onChange = { gain ->
                            AppSettings.setPeqBand(bandIndex, band.copy(gainDb = gain))
                        },
                    )
                }
            }
        }
    }

    if (showPresets) {
        QualityPresetOverlay(
            title = "PEQ presets",
            options = presetOptions,
            selectedId = selectedPresetId,
            onSelect = { id ->
                if (id == FLAT_QUALITY_PRESET_ID) {
                    resetPeq()
                } else {
                    savedPresets.firstOrNull {
                        savedQualityPresetId(it.data.name) == id
                    }?.let { preset ->
                        AppSettings.setPeqBands(preset.data.bands.map { it.toPeqBand() })
                        AppSettings.setPeqPreampDb(preset.data.preamp)
                    }
                }
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }

    if (showSave) {
        SaveQualityPresetDialog(
            title = "Save PEQ preset",
            onDismiss = { showSave = false },
            onSave = { name ->
                try {
                    store.savePeq(name, preampDb, bands)
                    savedPresets = store.loadPeq()
                    showSave = false
                    qualityMessage(context, "Saved \"$name\"")
                } catch (error: Exception) {
                    qualityError(context, "Could not save PEQ preset", error)
                }
            },
        )
    }

    if (showImport) {
        ImportQualityPresetDialog(
            title = "Import PEQ preset",
            description = "Paste a PEQ share URI. Values outside the supported controls are clamped.",
            onDismiss = { showImport = false },
            onImport = { text ->
                try {
                    val imported = HiByPeqV1Codec.decode(text.trim())
                    val importedBands = imported.bands.map { it.toPeqBand() }
                    AppSettings.setPeqBands(importedBands)
                    AppSettings.setPeqPreampDb(imported.preamp)
                    showImport = false
                    qualityMessage(context, "PEQ preset imported")
                } catch (error: Exception) {
                    qualityError(context, "Could not import PEQ preset", error)
                }
            },
        )
    }
}

@Composable
private fun PeqLogSlider(
    title: String,
    valueLabel: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val logarithmicRange = ln(maximum / minimum)
    val position = ln(value.coerceIn(minimum, maximum) / minimum) / logarithmicRange
    QualitySlider(
        title = title,
        valueLabel = valueLabel,
        value = position,
        valueRange = 0f..1f,
        enabled = enabled,
        onChange = { newPosition ->
            onChange(minimum * exp(newPosition * logarithmicRange))
        },
    )
}

private fun resetPeq() {
    AppSettings.setPeqBands(ParametricEqProcessor.DEFAULT_BANDS)
    AppSettings.setPeqPreampDb(0f)
}

private fun matchingPeqPreset(
    presets: List<SavedPeqPreset>,
    bands: List<PeqBand>,
    preampDb: Float,
): String? {
    val sharedBands = bands.map(PeqBand::toHiByPeqBand)
    presets.firstOrNull { preset ->
        preset.data.preamp == preampDb && preset.data.bands == sharedBands
    }?.let { return savedQualityPresetId(it.data.name) }

    val flat = preampDb == 0f && bands == ParametricEqProcessor.DEFAULT_BANDS
    return FLAT_QUALITY_PRESET_ID.takeIf { flat }
}

/** Reference type dropdown labels. */
internal fun BiquadKind.peqLabel(): String = when (this) {
    BiquadKind.LOW_SHELF -> "Low Shelf"
    BiquadKind.PEAKING -> "Peaking"
    BiquadKind.HIGH_SHELF -> "High Shelf"
}

/** Reference formats: Frequency:%.1fHz, Gain/Pre Amp:%.2fdB. */
internal fun formatPeqFrequencyHz(frequencyHz: Float): String =
    String.format(Locale.getDefault(), "%.1fHz", frequencyHz)

internal fun formatPeqDb(valueDb: Float): String =
    String.format(Locale.getDefault(), "%.2fdB", valueDb)
