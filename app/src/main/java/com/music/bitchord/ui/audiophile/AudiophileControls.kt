package com.music.bitchord.ui.audiophile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun AudiophilePageList(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun AudiophileSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            content()
        }
    }
}

@Composable
internal fun AudiophileSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AudiophileRowText(
            title = label,
            subtitle = subtitle,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
internal fun AudiophileNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AudiophileRowText(
            title = title,
            subtitle = subtitle,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AudiophileValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AudiophileRowText(
            title = label,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AudiophileRowText(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

/** Poweramp-style single-choice row: one-way radio semantics with a selected check. */
@Composable
internal fun AudiophileSelectionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AudiophileRowText(
            title = label,
            subtitle = subtitle,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

@Composable
internal fun <T> AudiophileSelectionGroup(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    optionSubtitle: (T) -> String? = { null },
    optionEnabled: (T) -> Boolean = { true },
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        options.forEach { option ->
            AudiophileSelectionRow(
                label = optionLabel(option),
                subtitle = optionSubtitle(option),
                selected = selected == option,
                onSelect = { onSelect(option) },
                enabled = enabled && optionEnabled(option),
            )
        }
    }
}

@Composable
internal fun AudiophileIntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String = value.toString(),
    step: Int = 1,
    steps: Int? = null,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    require(step > 0) { "step must be positive" }
    val intervals = ((range.last - range.first) / step).coerceAtLeast(1)
    AudiophileSliderFrame(label, valueText, subtitle, modifier) {
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { raw ->
                val snapped = range.first + ((raw - range.first) / step).roundToInt() * step
                onValueChange(snapped.coerceIn(range))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps ?: (intervals - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

@Composable
internal fun AudiophileFloatSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String = String.format(Locale.US, "%.1f", value),
    steps: Int = 0,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    AudiophileSliderFrame(label, valueText, subtitle, modifier) {
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun AudiophileSliderFrame(
    label: String,
    valueText: String,
    subtitle: String?,
    modifier: Modifier,
    slider: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        slider()
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudiophileBadges(
    badges: List<AudioStatusBadge>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        badges.forEach { badge ->
            val container = when (badge.tone) {
                AudioStatusBadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
                AudioStatusBadgeTone.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
                AudioStatusBadgeTone.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
                AudioStatusBadgeTone.WARNING -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                AudioStatusBadgeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
            }
            val content = when (badge.tone) {
                AudioStatusBadgeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                AudioStatusBadgeTone.ACTIVE -> MaterialTheme.colorScheme.onSecondaryContainer
                AudioStatusBadgeTone.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
                AudioStatusBadgeTone.WARNING,
                AudioStatusBadgeTone.ERROR,
                -> MaterialTheme.colorScheme.onErrorContainer
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = container,
            ) {
                Text(
                    text = badge.label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
            }
        }
    }
}

@Composable
internal fun AudiophileRestoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Restore defaults",
) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Restore, contentDescription = null)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}

internal fun formatMilliseconds(value: Int): String = when {
    value >= 1_000 && value % 1_000 == 0 -> "${value / 1_000} s"
    else -> "$value ms"
}

internal fun formatSampleRate(value: Int): String = if (value % 1_000 == 0) {
    String.format(Locale.US, "%.0f kHz", value / 1_000f)
} else {
    String.format(Locale.US, "%.1f kHz", value / 1_000f)
}

internal fun formatDb(value: Float): String = String.format(Locale.US, "%+.1f dB", value)
