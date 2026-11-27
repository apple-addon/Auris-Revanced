package com.music.bitchord.ui.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Full-window editor with a fixed close bar and one scrolling content parent. */
@Composable
internal fun ReferenceFullScreenDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = containerColor,
            contentColor = contentColor,
        ) {
            Column(modifier = modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Close $title",
                        )
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun ReferenceSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
internal fun ReferenceToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun ReferenceSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String = "${value.roundToInt()}",
    enabled: Boolean = true,
    steps: Int = 0,
    nudge: Float? = null,
    supportingText: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (nudge != null) {
                IconButton(
                    onClick = {
                        onValueChange((value - nudge).coerceIn(valueRange.start, valueRange.endInclusive))
                    },
                    enabled = enabled && value > valueRange.start,
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label")
                }
            }
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                valueRange = valueRange,
                steps = steps,
            )
            if (nudge != null) {
                IconButton(
                    onClick = {
                        onValueChange((value + nudge).coerceIn(valueRange.start, valueRange.endInclusive))
                    },
                    enabled = enabled && value < valueRange.endInclusive,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Increase $label")
                }
            }
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReferenceChoiceChips(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            FilterChip(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                label = { Text(option) },
                enabled = enabled,
            )
        }
    }
}

@Composable
internal fun ReferenceVerticalFader(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueText: String = String.format(Locale.US, "%+.1f", value),
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val thumb = MaterialTheme.colorScheme.surface
    val range = valueRange.endInclusive - valueRange.start

    fun report(y: Float, height: Int) {
        if (!enabled || height <= 0 || range <= 0f) return
        val fraction = 1f - (y / height.toFloat()).coerceIn(0f, 1f)
        currentOnChange(valueRange.start + fraction * range)
    }

    Column(
        modifier = modifier.width(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Canvas(
            modifier = Modifier
                .size(width = 44.dp, height = 148.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = "$valueText dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                        range = valueRange,
                    )
                    setProgress { target ->
                        if (enabled) currentOnChange(
                            target.coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                        enabled
                    }
                }
                .pointerInput(enabled, valueRange) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        report(down.position.y, size.height)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.positionChanged()) {
                                change.consume()
                                report(change.position.y, size.height)
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    }
                },
        ) {
            val centerX = size.width / 2f
            val trackWidth = 5.dp.toPx()
            drawRoundRect(
                color = inactive,
                topLeft = Offset(centerX - trackWidth / 2f, 0f),
                size = Size(trackWidth, size.height),
                cornerRadius = CornerRadius(trackWidth / 2f),
            )
            val fraction = if (range <= 0f) 0f else
                ((value.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) / range)
            val thumbY = size.height * (1f - fraction)
            drawRoundRect(
                color = active,
                topLeft = Offset(centerX - trackWidth / 2f, thumbY),
                size = Size(trackWidth, (size.height - thumbY).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(trackWidth / 2f),
            )
            drawCircle(color = active, radius = 10.dp.toPx(), center = Offset(centerX, thumbY))
            drawCircle(color = thumb, radius = 6.dp.toPx(), center = Offset(centerX, thumbY))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ReferenceDial(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String = formatPercent(value),
    enabled: Boolean = true,
    diameter: Dp = 82.dp,
) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val span = valueRange.endInclusive - valueRange.start

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                    setProgress { target ->
                        if (enabled) latestOnChange(
                            target.coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                        enabled
                    }
                }
                .pointerInput(enabled, valueRange) {
                    if (!enabled) return@pointerInput
                    var draggedValue = latestValue
                    detectVerticalDragGestures(
                        onDragStart = { draggedValue = latestValue },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (span > 0f) {
                                draggedValue = (draggedValue - dragAmount / size.height * span)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                                latestOnChange(draggedValue)
                            }
                        },
                    )
                },
        ) {
            val stroke = 6.dp.toPx()
            val arcDiameter = size.minDimension - stroke * 2f
            val arcSize = Size(arcDiameter, arcDiameter)
            val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
            drawArc(
                color = inactive,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val fraction = if (span <= 0f) 0f else
                ((value.coerceIn(valueRange.start, valueRange.endInclusive) - valueRange.start) / span)
            drawArc(
                color = active,
                startAngle = 135f,
                sweepAngle = 270f * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val angle = Math.toRadians((135f + 270f * fraction).toDouble())
            val center = Offset(size.width / 2f, size.height / 2f)
            val inner = arcDiameter * 0.28f
            val outer = arcDiameter * 0.43f
            drawLine(
                color = active,
                start = Offset(
                    center.x + cos(angle).toFloat() * inner,
                    center.y + sin(angle).toFloat() * inner,
                ),
                end = Offset(
                    center.x + cos(angle).toFloat() * outer,
                    center.y + sin(angle).toFloat() * outer,
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        Text(
            valueText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}
