package com.music.bitchord.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.audio.BiquadKind
import com.music.bitchord.playback.audio.ParametricEqProcessor
import com.music.bitchord.playback.audio.PeqBand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Live, logarithmic response preview for the current parametric bands. */
@Composable
internal fun PeqResponseGraph(
    bands: List<PeqBand>,
    preampDb: Float,
    active: Boolean,
) {
    val response = remember(bands, preampDb) {
        RbjPeqResponse.calculate(bands = bands, preampDb = preampDb)
    }
    // The reference draws the live response in yellow over the grid.
    val curveColor = Color(0xFFFFC400).let {
        if (active) it else it.copy(alpha = 0.45f)
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val zeroColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelPaint = remember { Paint().apply { isAntiAlias = true } }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(178.dp)
            .semantics { contentDescription = "Live parametric EQ frequency response" },
    ) {
        val left = 34.dp.toPx()
        val right = 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 24.dp.toPx()
        val plotWidth = (size.width - left - right).coerceAtLeast(1f)
        val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)
        val plotBottom = top + plotHeight

        fun xForFrequency(frequencyHz: Float): Float {
            val fraction = ln(frequencyHz / GRAPH_MIN_HZ) / ln(GRAPH_MAX_HZ / GRAPH_MIN_HZ)
            return left + plotWidth * fraction
        }

        fun yForGain(gainDb: Float): Float {
            val clamped = gainDb.coerceIn(GRAPH_MIN_DB, GRAPH_MAX_DB)
            return top + (GRAPH_MAX_DB - clamped) / (GRAPH_MAX_DB - GRAPH_MIN_DB) * plotHeight
        }

        labelPaint.color = labelColor.toArgb()
        labelPaint.textSize = 10.dp.toPx()
        labelPaint.textAlign = Paint.Align.RIGHT
        GRAPH_DB_LINES.forEach { db ->
            val y = yForGain(db)
            drawLine(
                color = if (db == 0f) zeroColor else gridColor,
                start = Offset(left, y),
                end = Offset(left + plotWidth, y),
                strokeWidth = if (db == 0f) 1.dp.toPx() else 0.5.dp.toPx(),
            )
            val label = when {
                db > 0f -> "+${db.toInt()}"
                else -> db.toInt().toString()
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                left - 6.dp.toPx(),
                y + 3.dp.toPx(),
                labelPaint,
            )
        }

        labelPaint.textAlign = Paint.Align.CENTER
        GRAPH_FREQUENCY_LINES.forEach { (frequency, label) ->
            val x = xForFrequency(frequency)
            drawLine(
                color = gridColor,
                start = Offset(x, top),
                end = Offset(x, plotBottom),
                strokeWidth = 0.5.dp.toPx(),
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x,
                plotBottom + 15.dp.toPx(),
                labelPaint,
            )
        }

        if (response.isNotEmpty()) {
            val path = Path()
            response.forEachIndexed { index, gainDb ->
                val x = left + plotWidth * index / (response.size - 1).coerceAtLeast(1)
                val y = yForGain(gainDb)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = curveColor,
                style = Stroke(
                    width = 2.25.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * RBJ cookbook response at 48 kHz, using the same coefficient equations as the
 * playback bank. Each point is the complex cascade response, not a gain sketch.
 */
internal object RbjPeqResponse {
    private const val SAMPLE_RATE = 48_000.0
    private const val POINT_COUNT = 257

    fun calculate(
        bands: List<PeqBand>,
        preampDb: Float,
        minHz: Float = GRAPH_MIN_HZ,
        maxHz: Float = GRAPH_MAX_HZ,
    ): FloatArray {
        val sections = bands
            .take(ParametricEqProcessor.BAND_COUNT)
            .filter { it.enabled && it.gainDb != 0f }
            .map { band ->
                design(
                    kind = band.kind,
                    frequencyHz = band.frequencyHz
                        .coerceIn(ParametricEqProcessor.MIN_HZ, ParametricEqProcessor.MAX_HZ)
                        .toDouble()
                        .coerceAtMost(SAMPLE_RATE * 0.45),
                    q = band.q
                        .coerceIn(ParametricEqProcessor.MIN_Q, ParametricEqProcessor.MAX_Q)
                        .toDouble(),
                    gainDb = band.gainDb
                        .coerceIn(
                            -ParametricEqProcessor.MAX_GAIN_DB,
                            ParametricEqProcessor.MAX_GAIN_DB,
                        )
                        .toDouble(),
                )
            }

        return FloatArray(POINT_COUNT) { index ->
            val fraction = index.toDouble() / (POINT_COUNT - 1)
            val frequencyHz = minHz.toDouble() * (maxHz / minHz).toDouble().pow(fraction)
            var totalDb = preampDb.toDouble()
            sections.forEach { section ->
                totalDb += section.responseDb(frequencyHz)
            }
            totalDb.toFloat()
        }
    }

    private fun design(
        kind: BiquadKind,
        frequencyHz: Double,
        q: Double,
        gainDb: Double,
    ): Coefficients {
        val amplitude = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequencyHz / SAMPLE_RATE
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2.0 * q)
        val sqrtA = sqrt(amplitude)

        val rawB0: Double
        val rawB1: Double
        val rawB2: Double
        val rawA0: Double
        val rawA1: Double
        val rawA2: Double

        when (kind) {
            BiquadKind.PEAKING -> {
                rawB0 = 1.0 + alpha * amplitude
                rawB1 = -2.0 * cosine
                rawB2 = 1.0 - alpha * amplitude
                rawA0 = 1.0 + alpha / amplitude
                rawA1 = -2.0 * cosine
                rawA2 = 1.0 - alpha / amplitude
            }
            BiquadKind.LOW_SHELF -> {
                rawB0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha)
                rawB1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
                rawB2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha)
                rawA0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha
                rawA1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
                rawA2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha
            }
            BiquadKind.HIGH_SHELF -> {
                rawB0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha)
                rawB1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
                rawB2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha)
                rawA0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + 2.0 * sqrtA * alpha
                rawA1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
                rawA2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - 2.0 * sqrtA * alpha
            }
        }

        val inverseA0 = 1.0 / rawA0
        return Coefficients(
            b0 = rawB0 * inverseA0,
            b1 = rawB1 * inverseA0,
            b2 = rawB2 * inverseA0,
            a1 = rawA1 * inverseA0,
            a2 = rawA2 * inverseA0,
        )
    }

    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
        fun responseDb(frequencyHz: Double): Double {
            val theta = -2.0 * PI * frequencyHz / SAMPLE_RATE
            val cos1 = cos(theta)
            val sin1 = sin(theta)
            val cos2 = cos(theta * 2.0)
            val sin2 = sin(theta * 2.0)
            val numeratorReal = b0 + b1 * cos1 + b2 * cos2
            val numeratorImaginary = b1 * sin1 + b2 * sin2
            val denominatorReal = 1.0 + a1 * cos1 + a2 * cos2
            val denominatorImaginary = a1 * sin1 + a2 * sin2
            val numerator = numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary
            val denominator = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
            val magnitudeSquared = numerator / denominator
            if (!magnitudeSquared.isFinite() || magnitudeSquared <= 0.0) return -240.0
            return (10.0 * log10(magnitudeSquared)).coerceIn(-240.0, 240.0)
        }
    }
}

private const val GRAPH_MIN_HZ = 20f
private const val GRAPH_MAX_HZ = 20_000f
private const val GRAPH_MIN_DB = -12f
private const val GRAPH_MAX_DB = 12f
private val GRAPH_DB_LINES = floatArrayOf(12f, 6f, 0f, -6f, -12f)
private val GRAPH_FREQUENCY_LINES = listOf(
    100f to "100",
    1_000f to "1k",
    10_000f to "10k",
)
