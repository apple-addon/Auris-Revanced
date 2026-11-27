package com.music.bitchord.ui.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val PowerampEditorColors = darkColorScheme(
    primary = Color(0xFF9DFF6A),
    onPrimary = Color(0xFF10220A),
    primaryContainer = Color(0xFF27461B),
    onPrimaryContainer = Color(0xFFD9FFC8),
    secondary = Color(0xFF9DFF6A),
    onSecondary = Color(0xFF10220A),
    secondaryContainer = Color(0xFF27461B),
    onSecondaryContainer = Color(0xFFD9FFC8),
    tertiary = Color(0xFF9DFF6A),
    onTertiary = Color(0xFF10220A),
    tertiaryContainer = Color(0xFF27461B),
    onTertiaryContainer = Color(0xFFD9FFC8),
    background = Color(0xFF030504),
    onBackground = Color(0xFFF0F5ED),
    surface = Color(0xFF030504),
    onSurface = Color(0xFFF0F5ED),
    surfaceVariant = Color(0xFF1B211B),
    onSurfaceVariant = Color(0xFFB5C0B1),
    outline = Color(0xFF667261),
).copy(
    surfaceContainer = Color(0xFF0B100C),
    surfaceContainerLow = Color(0xFF101610),
    surfaceContainerHigh = Color(0xFF182018),
)

@Composable
internal fun PowerampFullScreenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PowerampEditorColors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

/**
 * Poweramp-style three-page content. Deliberately has no title or vertical scroller: the caller's
 * full-window editor owns both, while [state] and transient UI state remain hoisted in that caller.
 */
@Composable
fun PowerampEqHub(
    state: PowerampEqState,
    uiState: PowerampEqUiState,
    presets: List<AudioPreset>,
    selectedPresetId: String?,
    onStateChange: (PowerampEqState) -> Unit,
    onUiStateChange: (PowerampEqUiState) -> Unit,
    onPresetSelected: (AudioPreset) -> Unit,
    onPresetAction: (ReferencePresetAction, AudioPreset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.normalized()
    val ui = uiState.normalized()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReferenceChoiceChips(
            options = PowerampEqPage.entries.map { it.label },
            selectedIndex = PowerampEqPage.entries.indexOf(ui.page),
            onSelected = { index -> onUiStateChange(ui.copy(page = PowerampEqPage.entries[index])) },
        )
        when (ui.page) {
            PowerampEqPage.EQUALIZER -> PowerampEqualizerPage(
                state = model,
                selectedBand = ui.selectedBand,
                presets = presets,
                selectedPresetId = selectedPresetId,
                onStateChange = onStateChange,
                onSelectedBandChange = { onUiStateChange(ui.copy(selectedBand = it)) },
                onOpenPresets = { onUiStateChange(ui.copy(presetsVisible = true)) },
                onPresetAction = onPresetAction,
            )
            PowerampEqPage.PLAYBACK -> PowerampPlaybackPage(model, onStateChange)
            PowerampEqPage.REVERB -> PowerampReverbPage(model, onStateChange)
        }
    }

    if (ui.presetsVisible) {
        PowerampPresetBrowser(
            uiState = ui,
            presets = presets,
            selectedPresetId = selectedPresetId,
            onUiStateChange = onUiStateChange,
            onPresetSelected = onPresetSelected,
            onPresetAction = onPresetAction,
            onDismiss = { onUiStateChange(ui.copy(presetsVisible = false, contextPresetId = null)) },
        )
    }
}

/** Small live EQ curve; it can also be used as the sheet's collapsed equalizer summary. */
@Composable
fun PowerampCompactStrip(
    state: PowerampEqState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val model = state.normalized()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(clickModifier),
    ) {
        drawRoundRect(
            color = grid,
            topLeft = Offset(0f, size.height / 2f),
            size = Size(size.width, 1.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val step = if (model.bands.size <= 1) size.width else size.width / (model.bands.size - 1)
        val usableHeight = size.height * 0.42f
        val points = model.bands.mapIndexed { index, band ->
            Offset(index * step, size.height / 2f - band.gainDb / 12f * usableHeight)
        }
        for (index in 1 until points.size) {
            drawLine(color = line, start = points[index - 1], end = points[index], strokeWidth = 2.dp.toPx())
        }
        points.forEach { point -> drawCircle(color = line, radius = 2.5.dp.toPx(), center = point) }
    }
}

/**
 * One unchanged-size tile: tap opens the hub, hold opens presets. combinedClickable consumes the
 * completed hold, so release never falls through as a hub tap. No press scaling or content swap is
 * applied, and the tile therefore keeps identical constraints and layout during either gesture.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PowerampEqLauncherTile(
    state: PowerampEqState,
    selectedPresetName: String,
    onOpenHub: () -> Unit,
    onOpenPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.normalized()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .combinedClickable(
                onClick = onOpenHub,
                onLongClick = onOpenPresets,
                onLongClickLabel = "Open equalizer presets",
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
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Poweramp EQ Hub", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = buildString {
                        append(selectedPresetName)
                        append(" · Equ ")
                        append(if (model.equEnabled) "on" else "off")
                        append(" · Tone ")
                        append(if (model.toneEnabled) "on" else "off")
                        append(" · Limit ")
                        append(if (model.limiterEnabled) "on" else "off")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open equalizer",
            )
        }
    }
}
