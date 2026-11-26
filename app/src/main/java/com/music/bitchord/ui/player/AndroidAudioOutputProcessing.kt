package com.music.bitchord.ui.player

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.preset.MsebPreset
import com.music.bitchord.playback.preset.MsebV1Codec
import com.music.bitchord.playback.preset.PeqBand as WirePeqBand
import com.music.bitchord.playback.preset.PeqPreset
import com.music.bitchord.playback.preset.PeqV1Codec
import com.music.bitchord.playback.reference.ReferenceEngineLoader
import com.music.bitchord.ui.policy.ProviderAudioSurface
import com.music.bitchord.ui.policy.ProviderAudioTheme
import com.music.bitchord.ui.reference.AudioPreset
import com.music.bitchord.ui.reference.AudioPresetTag
import com.music.bitchord.ui.reference.HiByMsebSurface
import com.music.bitchord.ui.reference.HiByPeqSurface
import com.music.bitchord.ui.reference.HiByPresetAction
import com.music.bitchord.ui.reference.MsebState
import com.music.bitchord.ui.reference.PeqBand
import com.music.bitchord.ui.reference.PeqDefaults
import com.music.bitchord.ui.reference.PeqFilterType
import com.music.bitchord.ui.reference.PeqState
import com.music.bitchord.ui.reference.PowerampEqHub
import com.music.bitchord.ui.reference.PowerampEqLauncherTile
import com.music.bitchord.ui.reference.PowerampEqState
import com.music.bitchord.ui.reference.PowerampEqUiState
import com.music.bitchord.ui.reference.PowerampEqDefaults
import com.music.bitchord.ui.reference.ReferencePresetAction
import com.music.bitchord.ui.reference.normalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OutputEditor { NONE, POWERAMP, MSEB, PEQ }

/** Android-only reference controls, mounted by the shared output drawer without a second scroller. */
@Composable
fun AndroidAudioOutputProcessing() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val poweramp by AppSettings.powerampEqState.collectAsStateWithLifecycle()
    val powerampPresetName by AppSettings.powerampPresetName.collectAsStateWithLifecycle()
    val mseb by AppSettings.hibyMsebState.collectAsStateWithLifecycle()
    val peq by AppSettings.hibyPeqState.collectAsStateWithLifecycle()
    val referenceMode by AppSettings.referenceEngineMode.collectAsStateWithLifecycle()
    val qualityOptions by AppSettings.qualityOptions.collectAsStateWithLifecycle()
    val providerAxes by AppSettings.providerAxes.collectAsStateWithLifecycle()
    val engineStatus by ReferenceEngineLoader.status.collectAsStateWithLifecycle()

    var editor by remember { mutableStateOf(OutputEditor.NONE) }
    var powerampUi by remember { mutableStateOf(PowerampEqUiState()) }
    var powerampPresetId by remember { mutableStateOf("user") }
    var peqBand by remember { mutableIntStateOf(0) }
    var importTarget by remember { mutableStateOf(OutputEditor.NONE) }
    var renameMseb by remember { mutableStateOf(false) }
    var renamePeq by remember { mutableStateOf(false) }
    var renamePoweramp by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        runCatching { context.startActivity(Intent.createChooser(send, title)) }
            .onFailure { toast("No application can share this preset") }
    }

    fun sharePoweramp() = shareText(poweramp.toPeqPreset(powerampPresetName).let(PeqV1Codec::generateContent), "Share PEQ preset")
    fun shareMseb() = shareText(mseb.toWirePreset().let(MsebV1Codec::generateContent), "Share MSEB preset")
    fun sharePeq() = shareText(peq.toWirePreset().let(PeqV1Codec::generateContent), "Share PEQ preset")

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = importTarget
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("The selected file cannot be opened")
                }
            }
            result.onSuccess { content ->
                runCatching {
                    when (target) {
                        OutputEditor.POWERAMP -> {
                            val imported = PeqV1Codec.decode(content)
                            AppSettings.setPowerampEqState(imported.toPowerampState(poweramp), imported.name.ifBlank { "Imported PEQ" })
                            powerampPresetId = "user"
                        }
                        OutputEditor.MSEB -> AppSettings.setHibyMsebState(MsebV1Codec.decode(content).toEditorState(mseb.enabled))
                        OutputEditor.PEQ -> AppSettings.setHibyPeqState(PeqV1Codec.decode(content).toEditorState(peq.enabled))
                        OutputEditor.NONE -> Unit
                    }
                }.onFailure { toast("Preset import failed: ${it.message ?: "unsupported data"}") }
            }.onFailure { toast("Preset import failed: ${it.message ?: "read error"}") }
        }
    }

    fun import(target: OutputEditor) {
        importTarget = target
        importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
    }

    val flatPoweramp = AudioPreset(
        id = "flat",
        name = "Flat",
        tags = setOf(AudioPresetTag.BUILT_IN, AudioPresetTag.GRAPHIC),
        previewGainsDb = PowerampEqDefaults.bands.map { it.gainDb },
    )
    val userPoweramp = AudioPreset(
        id = "user",
        name = powerampPresetName,
        tags = setOf(AudioPresetTag.USER, if (poweramp.parametricMode) AudioPresetTag.PARAMETRIC else AudioPresetTag.GRAPHIC),
        previewGainsDb = poweramp.bands.map { it.gainDb },
    )
    val neutralMseb = AudioPreset("neutral", "Neutral", setOf(AudioPresetTag.BUILT_IN))
    val userMseb = AudioPreset("user", mseb.presetName, setOf(AudioPresetTag.USER))
    val neutralPeq = AudioPreset("neutral", "Neutral", setOf(AudioPresetTag.BUILT_IN, AudioPresetTag.PARAMETRIC))
    val userPeq = AudioPreset("user", peq.presetName, setOf(AudioPresetTag.USER, AudioPresetTag.PARAMETRIC), previewGainsDb = peq.bands.map { it.gainDb })

    ProviderAudioTheme(provider = providerAxes.ui, surface = ProviderAudioSurface.EQ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PowerampEqLauncherTile(
                state = poweramp,
                selectedPresetName = if (powerampPresetId == "flat") "Flat" else powerampPresetName,
                onOpenHub = { editor = if (editor == OutputEditor.POWERAMP) OutputEditor.NONE else OutputEditor.POWERAMP },
                onOpenPresets = {
                    editor = OutputEditor.POWERAMP
                    powerampUi = powerampUi.copy(presetsVisible = true)
                },
            )
            ReferenceLauncherTile(
                title = "HiBy MSEB",
                subtitle = "${mseb.presetName} · ${if (mseb.enabled) "On" else "Off"} · hold to share",
                onClick = { editor = if (editor == OutputEditor.MSEB) OutputEditor.NONE else OutputEditor.MSEB },
                onLongClick = ::shareMseb,
            )
            ReferenceLauncherTile(
                title = "HiBy PEQ",
                subtitle = "${peq.presetName} · ${if (peq.enabled) "On" else "Off"} · hold to share",
                onClick = { editor = if (editor == OutputEditor.PEQ) OutputEditor.NONE else OutputEditor.PEQ },
                onLongClick = ::sharePeq,
                tuneIcon = true,
            )

            when (editor) {
                OutputEditor.POWERAMP -> {
                    EditorStatus(
                        when (referenceMode) {
                            ReferenceEngineMode.POWERAMP ->
                                "PAWS PCM: ${if (engineStatus.pawsOutputActive) "active" else "ready/not streaming"}. PEQ bands, balance, tempo and volume also map to the live controls; PAWS-only tone/reverb controls are not claimed as applied."
                            ReferenceEngineMode.HIBY, ReferenceEngineMode.HYBRID ->
                                "Ten bands are applied through HiBy PEQ. Balance, tempo and volume use Auris playback controls; PAWS-only reverb controls remain unapplied."
                            ReferenceEngineMode.BIT_PERFECT ->
                                "Bit-perfect bypasses DSP. Editor choices are saved but are not processing audio."
                        },
                    )
                    PowerampEqHub(
                        state = poweramp,
                        uiState = powerampUi,
                        presets = listOf(userPoweramp, flatPoweramp),
                        selectedPresetId = powerampPresetId,
                        onStateChange = { AppSettings.setPowerampEqState(it) },
                        onUiStateChange = { powerampUi = it },
                        onPresetSelected = { preset ->
                            powerampPresetId = preset.id
                            if (preset.id == "flat") {
                                AppSettings.setPowerampEqState(poweramp.copy(
                                    preampDb = 0f,
                                    bands = PowerampEqDefaults.bands,
                                    bassDb = 0f,
                                    trebleDb = 0f,
                                ))
                            }
                        },
                        onPresetAction = { action, _ ->
                            when (action) {
                                ReferencePresetAction.SAVE, ReferencePresetAction.DUPLICATE -> {
                                    AppSettings.setPowerampEqState(poweramp, powerampPresetName)
                                    powerampPresetId = "user"
                                    toast("Poweramp preset saved")
                                }
                                ReferencePresetAction.IMPORT -> import(OutputEditor.POWERAMP)
                                ReferencePresetAction.SHARE -> sharePoweramp()
                                ReferencePresetAction.RENAME -> renamePoweramp = true
                                ReferencePresetAction.DELETE, ReferencePresetAction.RESET -> {
                                    AppSettings.setPowerampEqState(PowerampEqState())
                                    powerampPresetId = "user"
                                }
                            }
                        },
                    )
                }
                OutputEditor.MSEB -> {
                    EditorStatus(
                        if (qualityOptions && referenceMode != ReferenceEngineMode.POWERAMP && referenceMode != ReferenceEngineMode.BIT_PERFECT) {
                            "Changes call HiBy DspUtil MSEB directly. Native status: ${engineStatus.hiby.detail}."
                        } else {
                            "Saved only. Select Quality Options with HiBy or Hybrid to call the native MSEB engine."
                        },
                    )
                    ProviderAudioTheme(provider = providerAxes.ui, surface = ProviderAudioSurface.MSEB) {
                        HiByMsebSurface(
                            state = mseb,
                            presets = listOf(userMseb, neutralMseb),
                            onStateChange = AppSettings::setHibyMsebState,
                            onPresetSelected = { preset ->
                                if (preset.id == "neutral") {
                                    AppSettings.setHibyMsebState(MsebState(enabled = mseb.enabled, presetName = "Neutral"))
                                }
                            },
                            onAction = { action ->
                                when (action) {
                                    HiByPresetAction.SAVE -> {
                                        AppSettings.setHibyMsebState(mseb.copy(presetName = "User preset"))
                                        toast("MSEB preset saved")
                                    }
                                    HiByPresetAction.IMPORT -> import(OutputEditor.MSEB)
                                    HiByPresetAction.SHARE -> shareMseb()
                                }
                            },
                        )
                    }
                }
                OutputEditor.PEQ -> {
                    EditorStatus(
                        if (qualityOptions && referenceMode != ReferenceEngineMode.POWERAMP && referenceMode != ReferenceEngineMode.BIT_PERFECT) {
                            "Changes call HiBy SmartAv PEQ directly. Native status: ${engineStatus.hiby.detail}."
                        } else {
                            "Saved only. Select Quality Options with HiBy or Hybrid to call the native PEQ engine."
                        },
                    )
                    ProviderAudioTheme(provider = providerAxes.ui, surface = ProviderAudioSurface.PEQ) {
                        HiByPeqSurface(
                            state = peq,
                            presets = listOf(userPeq, neutralPeq),
                            selectedBand = peqBand,
                            onSelectedBandChange = { peqBand = it },
                            onStateChange = AppSettings::setHibyPeqState,
                            onPresetSelected = { preset ->
                                if (preset.id == "neutral") {
                                    AppSettings.setHibyPeqState(PeqState(enabled = peq.enabled, presetName = "Neutral"))
                                }
                            },
                            onAction = { action ->
                                when (action) {
                                    HiByPresetAction.SAVE -> {
                                        AppSettings.setHibyPeqState(peq.copy(presetName = "User preset"))
                                        toast("PEQ preset saved")
                                    }
                                    HiByPresetAction.IMPORT -> import(OutputEditor.PEQ)
                                    HiByPresetAction.SHARE -> sharePeq()
                                }
                            },
                        )
                    }
                }
                OutputEditor.NONE -> Unit
            }
        }
    }

    if (renamePoweramp || renameMseb || renamePeq) {
        val initial = when {
            renamePoweramp -> powerampPresetName
            renameMseb -> mseb.presetName
            else -> peq.presetName
        }
        var name by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = {
                renamePoweramp = false; renameMseb = false; renamePeq = false
            },
            title = { Text("Rename preset") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            },
            dismissButton = {
                TextButton(onClick = { renamePoweramp = false; renameMseb = false; renamePeq = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim()
                    if (clean.isNotEmpty()) {
                        when {
                            renamePoweramp -> AppSettings.setPowerampEqState(poweramp, clean)
                            renameMseb -> AppSettings.setHibyMsebState(mseb.copy(presetName = clean))
                            renamePeq -> AppSettings.setHibyPeqState(peq.copy(presetName = clean))
                        }
                    }
                    renamePoweramp = false; renameMseb = false; renamePeq = false
                }) { Text("Save") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReferenceLauncherTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    tuneIcon: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = "Share $title preset",
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (tuneIcon) Icons.Rounded.Tune else Icons.Rounded.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Open $title")
        }
    }
}

@Composable
private fun EditorStatus(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun PowerampEqState.toPeqPreset(name: String) = PeqPreset(
    name = name,
    description = "Poweramp EQ Hub export. Balance, tempo, volume and reverb are not PEQ fields.",
    preampDb = preampDb.toDouble().coerceIn(-24.0, 24.0),
    bands = normalized().bands.map { band ->
        WirePeqBand(0, band.frequencyHz.toDouble(), band.enabled, band.gainDb.toDouble(), band.q.toDouble())
    },
)

private fun PeqState.toWirePreset() = PeqPreset(
    name = presetName,
    preampDb = preampDb.toDouble(),
    bands = normalized().bands.map { band ->
        WirePeqBand(
            when (band.type) {
                PeqFilterType.PEAK -> 0
                PeqFilterType.LOW_SHELF -> 1
                PeqFilterType.HIGH_SHELF -> 2
                PeqFilterType.LOW_PASS -> 3
                PeqFilterType.HIGH_PASS -> 4
                PeqFilterType.NOTCH -> 5
                PeqFilterType.BAND_PASS -> 6
            },
            band.frequencyHz.toDouble(),
            band.enabled,
            band.gainDb.toDouble(),
            band.q.toDouble(),
        )
    },
)

private fun MsebState.toWirePreset() = MsebPreset(
    datalist = normalized().values.map { it.toInt().coerceIn(-100, 100) },
    range = range,
    result = normalized().values.map { it.toInt().coerceIn(-100, 100) },
    name = presetName,
)

private fun MsebPreset.toEditorState(enabled: Boolean) = MsebState(
    enabled = enabled,
    presetName = name?.takeIf { it.isNotBlank() } ?: "Imported MSEB",
    // MSEB v1 deliberately has no preamp field; never invent one on import.
    preampDb = 0f,
    range = range,
    values = datalist.map { it.toFloat().coerceIn(-100f, 100f) },
).normalized()

private fun PeqPreset.toEditorState(enabled: Boolean) = PeqState(
    enabled = enabled,
    presetName = name.ifBlank { "Imported PEQ" },
    preampDb = preampDb.toFloat(),
    bands = bands.map { band ->
        PeqBand(
            frequencyHz = band.frequencyHz.toFloat(),
            gainDb = band.gainDb.toFloat(),
            q = band.q.toFloat(),
            enabled = band.enabled,
            type = when (band.type) {
                1 -> PeqFilterType.LOW_SHELF
                2 -> PeqFilterType.HIGH_SHELF
                3 -> PeqFilterType.LOW_PASS
                4 -> PeqFilterType.HIGH_PASS
                5 -> PeqFilterType.NOTCH
                6 -> PeqFilterType.BAND_PASS
                else -> PeqFilterType.PEAK
            },
        )
    },
).normalized()

private fun PeqPreset.toPowerampState(current: PowerampEqState) = current.copy(
    parametricMode = true,
    preampDb = preampDb.toFloat(),
    bands = bands.map { band ->
        com.music.bitchord.ui.reference.PowerampEqBand(
            frequencyHz = band.frequencyHz.toFloat(),
            gainDb = band.gainDb.toFloat(),
            q = band.q.toFloat(),
            enabled = band.enabled,
        )
    },
).normalized()
