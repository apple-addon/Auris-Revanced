package com.music.bitchord.ui.reference

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
internal fun PeqResponseGraph(
    state: PeqState,
    selectedBand: Int,
    onBandSelected: (Int) -> Unit,
    onStateChange: (PeqState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.height(220.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("+12", style = MaterialTheme.typography.labelSmall)
                Text("0", style = MaterialTheme.typography.labelSmall)
                Text("−12", style = MaterialTheme.typography.labelSmall)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(220.dp)
                    .padding(start = 6.dp),
            ) {
                PeqGraphCanvas(
                    state = state,
                    selectedBand = selectedBand,
                    onBandSelected = onBandSelected,
                    onStateChange = onStateChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        PeqFrequencyAxis(modifier = Modifier.padding(start = 32.dp))
        Text(
            text = "Hz · drag a point to change frequency and gain",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeqFrequencyAxis(modifier: Modifier = Modifier) {
    val labels = listOf(20f to "20", 100f to "100", 1_000f to "1k", 10_000f to "10k", 20_000f to "20k")
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            labels.forEach { (_, label) ->
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val measured = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val all = measured.indices.toList()
        val sparse = listOf(0, 2, 4)
        val minimal = listOf(0, 4)
        fun List<Int>.fits() = sumOf { measured[it].width } <= width
        val chosen = when {
            all.fits() -> all
            sparse.fits() -> sparse
            else -> minimal
        }
        val height = chosen.maxOfOrNull { measured[it].height } ?: 0
        layout(width, height) {
            chosen.forEach { index ->
                val placeable = measured[index]
                val center = peqFrequencyToFraction(labels[index].first) * width
                val maxX = (width - placeable.width).coerceAtLeast(0)
                val x = (center - placeable.width / 2f).toInt().coerceIn(0, maxX)
                placeable.place(x, 0)
            }
        }
    }
}

@Composable
private fun PeqGraphCanvas(
    state: PeqState,
    selectedBand: Int,
    onBandSelected: (Int) -> Unit,
    onStateChange: (PeqState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val latestOnBandSelected by rememberUpdatedState(onBandSelected)
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val zero = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val curve = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val selectedIndex = selectedBand.coerceIn(state.bands.indices)

    Canvas(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                val band = state.bands[selectedIndex]
                contentDescription = "Parametric EQ graph, band ${selectedIndex + 1}"
                stateDescription = "${formatFrequency(band.frequencyHz)}, ${formatDb(band.gainDb)}"
                progressBarRangeInfo = ProgressBarRangeInfo(band.gainDb, -12f..12f)
                setProgress { gain ->
                    if (state.enabled && band.enabled) {
                        latestOnStateChange(
                            PeqReducer.updateBand(latestState, selectedIndex) { it.copy(gainDb = gain) },
                        )
                        true
                    } else {
                        false
                    }
                }
            }
            .pointerInput(state.enabled, state.bands.any { it.enabled }) {
                if (!state.enabled || state.bands.none { it.enabled }) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val x = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val draggedBand = latestState.bands.indices
                        .filter { latestState.bands[it].enabled }
                        .minByOrNull { index ->
                            abs(peqFrequencyToFraction(latestState.bands[index].frequencyHz) - x)
                        } ?: return@awaitEachGesture
                    latestOnBandSelected(draggedBand)

                    fun report(x: Float, y: Float) {
                        latestOnStateChange(
                            PeqReducer.graphDrag(
                                state = latestState,
                                index = draggedBand,
                                xFraction = x / size.width.toFloat(),
                                yFraction = y / size.height.toFloat(),
                            ),
                        )
                    }

                    report(down.position.x, down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.positionChanged()) {
                            change.consume()
                            report(change.position.x, change.position.y)
                        }
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                    }
                }
            },
    ) {
        val frequencies = listOf(20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f)
        frequencies.forEach { frequency ->
            val x = peqFrequencyToFraction(frequency) * size.width
            drawLine(color = grid, start = Offset(x, 0f), end = Offset(x, size.height))
        }
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { gain ->
            val y = peqGainToFraction(gain) * size.height
            drawLine(color = if (gain == 0f) zero else grid, start = Offset(0f, y), end = Offset(size.width, y))
        }
        val points = state.bands.map { band ->
            Offset(
                peqFrequencyToFraction(band.frequencyHz) * size.width,
                peqGainToFraction(band.gainDb) * size.height,
            )
        }
        val ordered = state.bands.indices.sortedBy { state.bands[it].frequencyHz }
        for (position in 1 until ordered.size) {
            val previous = ordered[position - 1]
            val current = ordered[position]
            if (state.bands[previous].enabled && state.bands[current].enabled) {
                drawLine(
                    color = curve,
                    start = points[previous],
                    end = points[current],
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        state.bands.indices.forEach { index ->
            if (state.bands[index].enabled) {
                if (index == selectedIndex) {
                    drawCircle(color = secondary.copy(alpha = 0.25f), radius = 11.dp.toPx(), center = points[index])
                }
                drawCircle(
                    color = if (index == selectedIndex) secondary else curve,
                    radius = if (index == selectedIndex) 6.dp.toPx() else 4.dp.toPx(),
                    center = points[index],
                )
            }
        }
    }
}
