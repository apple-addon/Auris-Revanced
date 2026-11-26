package com.music.bitchord.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.audio.BiquadKind
import com.music.bitchord.playback.audio.HiByMsebData
import com.music.bitchord.playback.audio.HiByMsebV1Codec
import com.music.bitchord.playback.audio.HiByPeqBand
import com.music.bitchord.playback.audio.HiByPeqData
import com.music.bitchord.playback.audio.HiByPeqV1Codec
import com.music.bitchord.playback.audio.ParametricEqProcessor
import com.music.bitchord.playback.audio.PeqBand
import com.music.bitchord.playback.audio.ReverbTuning
import com.music.bitchord.ui.components.AurisSlider
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt

/** A serializable choice for the preset overlay shared by the quality surfaces. */
data class QualityPresetOption(
    val id: String,
    val label: String,
)

/**
 * Preset picker kept public so the parent quality surface can offer the same
 * picker without duplicating its layout. Selection and dismissal are separate
 * so callers can validate or apply a choice before closing their own UI.
 */
@Composable
fun QualityPresetOverlay(
    title: String,
    options: List<QualityPresetOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) {
                Text("No presets saved yet.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEachIndexed { index, option ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option.id) }
                                .padding(horizontal = 4.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (option.id == selectedId) {
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun QualityScreenHeader(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onReset) {
            Text("Reset")
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
internal fun QualityPresetSelector(
    currentLabel: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Preset",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = currentLabel,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Rounded.ArrowDropDown,
            contentDescription = "Choose preset",
        )
    }
}

@Composable
internal fun QualityPresetActions(
    onSave: () -> Unit,
    onImport: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TextButton(onClick = onSave, modifier = Modifier.weight(1f)) {
            Text("Save")
        }
        TextButton(onClick = onImport, modifier = Modifier.weight(1f)) {
            Text("Import")
        }
        TextButton(onClick = onShare, modifier = Modifier.weight(1f)) {
            Text("Share")
        }
    }
}

@Composable
internal fun QualitySlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AurisSlider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SaveQualityPresetDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                label = { Text("Preset name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun ImportQualityPresetDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 16_384) text = it },
                    label = { Text("Share text") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

internal const val FLAT_QUALITY_PRESET_ID = "builtin:flat"

/** Reference preset-selector labels: the flat preset reads "None", anything else "Customize". */
internal const val NONE_PRESET_LABEL = "None"
internal const val CUSTOM_PRESET_LABEL = "Customize"
internal fun savedQualityPresetId(name: String): String = "saved:$name"

internal fun formatQualityDb(value: Float): String =
    String.format(Locale.getDefault(), "%+.1f dB", value)

internal fun encodeMsebShare(values: FloatArray, range: Int): String {
    val integers = List(MSEB_DIMENSION_COUNT) { index ->
        values.getOrElse(index) { 0f }.roundToInt().coerceIn(-100, 100)
    }
    return HiByMsebV1Codec.encode(
        HiByMsebData(
            datalist = integers,
            range = range,
            result = integers,
        ),
    )
}

internal fun encodePeqShare(name: String, preampDb: Float, bands: List<PeqBand>): String =
    HiByPeqV1Codec.encode(
        HiByPeqData(
            name = name,
            device = "",
            description = "Auris parametric EQ preset",
            preamp = preampDb,
            bands = List(ParametricEqProcessor.BAND_COUNT) { index ->
                bands.getOrElse(index) { ParametricEqProcessor.DEFAULT_BANDS[index] }.toHiByPeqBand()
            },
        ),
    )

internal fun PeqBand.toHiByPeqBand(): HiByPeqBand = HiByPeqBand(
    filter = when (kind) {
        BiquadKind.LOW_SHELF -> 0
        BiquadKind.PEAKING -> 1
        BiquadKind.HIGH_SHELF -> 2
    },
    frequency = frequencyHz,
    on = if (enabled) 1 else 0,
    gain = gainDb,
    q = q,
)

internal fun HiByPeqBand.toPeqBand(): PeqBand {
    val importedKind = when (filter) {
        0 -> BiquadKind.LOW_SHELF
        1 -> BiquadKind.PEAKING
        2 -> BiquadKind.HIGH_SHELF
        else -> throw IllegalArgumentException("Unsupported PEQ filter type $filter")
    }
    require(on in 0..1) { "PEQ band on flag must be 0 or 1" }
    return PeqBand(
        frequencyHz = frequency,
        q = q,
        gainDb = gain,
        kind = importedKind,
        enabled = on == 1,
    )
}

internal fun shareQualityText(
    context: Context,
    chooserTitle: String,
    content: () -> String,
) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content())
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (error: Exception) {
        qualityError(context, "Could not share preset", error)
    }
}

internal fun qualityMessage(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

internal fun qualityError(context: Context, action: String, error: Exception) {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    Toast.makeText(context, "$action: $detail", Toast.LENGTH_LONG).show()
}

/** Persistent, app-private named presets. The share codecs remain the on-disk format where possible. */
internal class QualityPresetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "quality_dsp_presets",
        Context.MODE_PRIVATE,
    )

    fun loadMseb(): List<SavedMsebPreset> = prefs.all.mapNotNull { (key, raw) ->
        if (!key.startsWith(MSEB_KEY_PREFIX) || raw !is String) return@mapNotNull null
        runCatching {
            val json = JSONObject(raw)
            val name = json.getString("name").trim()
            require(name.isNotEmpty())
            val data = HiByMsebV1Codec.decode(json.getString("payload"))
            val preamp = json.getDouble("preamp").toFloat()
            require(preamp.isFinite())
            SavedMsebPreset(name, data, preamp)
        }.getOrNull()
    }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    fun saveMseb(
        name: String,
        values: FloatArray,
        range: Int,
        preampDb: Float,
    ) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Preset name is empty" }
        val payload = encodeMsebShare(values, range)
        val stored = JSONObject()
            .put("name", cleanName)
            .put("payload", payload)
            .put("preamp", preampDb.toDouble())
            .toString()
        put(MSEB_KEY_PREFIX + cleanName.storageKey(), stored)
    }

    fun loadPeq(): List<SavedPeqPreset> = prefs.all.mapNotNull { (key, raw) ->
        if (!key.startsWith(PEQ_KEY_PREFIX) || raw !is String) return@mapNotNull null
        runCatching {
            val data = HiByPeqV1Codec.decode(raw)
            require(data.name.isNotBlank())
            data.bands.forEach { it.toPeqBand() }
            SavedPeqPreset(data)
        }.getOrNull()
    }.sortedBy { it.data.name.lowercase(Locale.getDefault()) }

    fun savePeq(name: String, preampDb: Float, bands: List<PeqBand>) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Preset name is empty" }
        put(
            PEQ_KEY_PREFIX + cleanName.storageKey(),
            encodePeqShare(cleanName, preampDb, bands),
        )
    }

    fun loadReverb(): List<SavedReverbPreset> = prefs.all.mapNotNull { (key, raw) ->
        if (!key.startsWith(REVERB_KEY_PREFIX) || raw !is String) return@mapNotNull null
        runCatching {
            val json = JSONObject(raw)
            val name = json.getString("name").trim()
            require(name.isNotEmpty())
            SavedReverbPreset(
                name = name,
                tuning = ReverbTuning(
                    enabled = json.getBoolean("enabled"),
                    damp = json.getDouble("damp").toFloat(),
                    filter = json.getDouble("filter").toFloat(),
                    fade = json.getDouble("fade").toFloat(),
                    preDelay = json.getDouble("preDelay").toFloat(),
                    preDelayMix = json.getDouble("preDelayMix").toFloat(),
                    size = json.getDouble("size").toFloat(),
                    mix = json.getDouble("mix").toFloat(),
                ),
            )
        }.getOrNull()
    }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    fun saveReverb(name: String, tuning: ReverbTuning) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Preset name is empty" }
        val stored = JSONObject()
            .put("name", cleanName)
            .put("enabled", tuning.enabled)
            .put("damp", tuning.damp.toDouble())
            .put("filter", tuning.filter.toDouble())
            .put("fade", tuning.fade.toDouble())
            .put("preDelay", tuning.preDelay.toDouble())
            .put("preDelayMix", tuning.preDelayMix.toDouble())
            .put("size", tuning.size.toDouble())
            .put("mix", tuning.mix.toDouble())
            .toString()
        put(REVERB_KEY_PREFIX + cleanName.storageKey(), stored)
    }

    // ---- Graphic EQ presets and AutoEq assignments -------------------------

    /** Saved user graphic-EQ presets (bands + preamp), name-keyed. */
    fun loadEqPresets(): List<SavedEqPreset> = prefs.all.mapNotNull { (key, raw) ->
        if (!key.startsWith(EQ_KEY_PREFIX) || raw !is String) return@mapNotNull null
        runCatching {
            val json = JSONObject(raw)
            val name = json.getString("name").trim()
            require(name.isNotEmpty())
            val bands = json.getJSONArray("bands").let { arr ->
                List(arr.length()) { index -> arr.getDouble(index).toFloat() }
            }
            SavedEqPreset(name, bands, json.getDouble("preamp").toFloat())
        }.getOrNull()
    }.sortedBy { it.name.lowercase(Locale.getDefault()) }

    fun saveEqPreset(name: String, bands: List<Float>, preampDb: Float) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Preset name is empty" }
        val json = JSONObject()
            .put("name", cleanName)
            .put("bands", bands.map { it.toDouble() })
            .put("preamp", preampDb.toDouble())
            .toString()
        put(EQ_KEY_PREFIX + cleanName.storageKey(), json)
    }

    fun deleteEqPreset(name: String) {
        prefs.edit().remove(EQ_KEY_PREFIX + name.trim().storageKey()).apply()
    }

    /**
     * AutoEq assignments: output device key -> preset name. Queryable via
     * [autoEqPresetFor], which is what "Suggest AutoEq Presets" reads when a
     * device connects.
     */
    fun loadAutoEqAssignments(): Map<String, String> = runCatching {
        val raw = prefs.getString(AUTOEQ_KEY, null) ?: return emptyMap()
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    fun autoEqPresetFor(deviceKey: String): String? =
        loadAutoEqAssignments()[deviceKey.trim().lowercase(Locale.getDefault())]

    fun assignAutoEq(deviceKey: String, presetName: String) {
        val next = loadAutoEqAssignments().toMutableMap()
        next[deviceKey.trim().lowercase(Locale.getDefault())] = presetName
        put(AUTOEQ_KEY, JSONObject(next as Map<*, *>).toString())
    }

    /** "Reset the Disabled AutoEq Suggestions": drops every assignment. */
    fun clearAutoEqSuggestions() {
        prefs.edit().remove(AUTOEQ_KEY).apply()
    }

    /** Export format for AutoEq presets: one JSON line per assignment. */
    fun exportAutoEq(): String = loadAutoEqAssignments()
        .toSortedMap()
        .entries
        .joinToString("\n") { entry ->
            JSONObject().put("device", entry.key).put("preset", entry.value).toString()
        }

    /** Imports the [exportAutoEq] text format; returns how many assignments landed. */
    fun importAutoEq(text: String): Int {
        var imported = 0
        val next = loadAutoEqAssignments().toMutableMap()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            runCatching {
                val json = JSONObject(line)
                val device = json.getString("device").trim().lowercase(Locale.getDefault())
                val preset = json.getString("preset").trim()
                if (device.isNotEmpty() && preset.isNotEmpty()) {
                    next[device] = preset
                    imported++
                }
            }
        }
        if (imported > 0) put(AUTOEQ_KEY, JSONObject(next as Map<*, *>).toString())
        return imported
    }

    private fun put(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { "Storage is unavailable" }
    }

    private fun String.storageKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        toByteArray(StandardCharsets.UTF_8),
    )

    private companion object {
        const val MSEB_KEY_PREFIX = "mseb:"
        const val PEQ_KEY_PREFIX = "peq:"
        const val REVERB_KEY_PREFIX = "reverb:"
        const val EQ_KEY_PREFIX = "eq:"
        const val AUTOEQ_KEY = "autoeq_map"
    }
}

internal data class SavedEqPreset(
    val name: String,
    val bands: List<Float>,
    val preampDb: Float,
)

internal data class SavedMsebPreset(
    val name: String,
    val data: HiByMsebData,
    val preampDb: Float,
)

internal data class SavedPeqPreset(
    val data: HiByPeqData,
)

internal data class SavedReverbPreset(
    val name: String,
    val tuning: ReverbTuning,
)

private const val MSEB_DIMENSION_COUNT = 10
