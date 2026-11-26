package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Accessible-in-spirit, drag-driven rotary control with provider-theme colors. */
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Dial diameter; the reference's main Volume knob is the large one. */
    sizeDp: Dp = 94.dp,
) {
    val current by rememberUpdatedState(value)
    val callback by rememberUpdatedState(onValueChange)
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f)
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
    val span = valueRange.endInclusive - valueRange.start
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .size(sizeDp)
                .pointerInput(enabled, valueRange) {
                    if (!enabled || span <= 0f) return@pointerInput
                    detectDragGestures { change, amount ->
                        change.consume()
                        val delta = (amount.x - amount.y) / 420f * span
                        callback((current + delta).coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                },
        ) {
            val fraction = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
            val stroke = 5.dp.toPx()
            val arcSize = Size(size.width - 18.dp.toPx(), size.height - 18.dp.toPx())
            val topLeft = Offset(9.dp.toPx(), 9.dp.toPx())
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((135f + 270f * fraction).toDouble())
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (size.minDimension - 28.dp.toPx()) / 2f
            drawLine(
                color = color,
                start = center,
                end = Offset(
                    center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius,
                ),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(label, color = ink, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}
