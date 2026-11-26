package com.music.bitchord.ui.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val REFERENCE_MAX_GAP_SECONDS = 0.75

/**
 * How the waveform is read onto the drawing's columns.
 *
 * The drawing is a lane of pixels; the curve behind it is a list of samples.
 * When the samples are sparser than the pixels, most pixels have no sample of
 * their own — and a run of one pixel is a path with no area in it, which is
 * nothing at all on the screen. These cases pin down that a curve of any
 * resolution the analyser produces fills its columns, and that a stretch
 * nobody measured is still left blank.
 */
class WaveformColumnsTest {

    /** The analyser's energy curve: a quarter of a second between samples. */
    private fun sparseCurve(
        from: Double,
        seconds: Double,
        step: Double = 0.25,
    ): Pair<List<Float>, List<Double>> {
        val times = generateSequence(from) { it + step }
            .takeWhile { it < from + seconds }
            .toList()
        return times.map { 0.5f } to times
    }

    /** The analyser's peaks: twenty milliseconds between samples. */
    private fun denseCurve(
        from: Double,
        seconds: Double,
        step: Double = 0.02,
    ): Pair<List<Float>, List<Double>> {
        val times = generateSequence(from) { it + step }
            .takeWhile { it < from + seconds }
            .toList()
        return times.map { 0.5f } to times
    }

    @Test
    fun aCurveSparserThanTheDrawingStillFillsItsColumns() {
        // The case that read as an empty lane: the restored analysis has no
        // peaks, so the panel drew the energy curve, whose samples are a
        // quarter of a second apart against a pixel far finer than that.
        val (peaks, times) = sparseCurve(from = 100.0, seconds = 40.0)
        val columns = 800

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 100.0,
            span = 40.0,
            columns = columns,
            mapToAxis = { it },
        )

        // Every pixel of the stretch the curve covers has something in it.
        val covered = grid.filled.count { it }
        assertTrue(covered > columns * 0.9, "only $covered of $columns columns were filled")
        // And the shape is the curve's own: a flat half-height, not nothing.
        assertTrue(grid.tops.any { it > 0.4f })
    }

    @Test
    fun aDenseCurveFillsItsColumnsToo() {
        val (peaks, times) = denseCurve(from = 0.0, seconds = 30.0)
        val columns = 600

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 0.0,
            span = 30.0,
            columns = columns,
            mapToAxis = { it },
        )

        assertEquals(columns, grid.filled.count { it })
    }

    @Test
    fun aStretchNobodyMeasuredIsStillBlank() {
        // The head pass measures the opening and the tail pass the ending.
        // The stretch between them was never measured, and drawing across it
        // would invent a silence that is not in the music.
        val peaks = mutableListOf<Float>()
        val times = mutableListOf<Double>()
        for (time in generateSequence(0.0) { it + 0.25 }.takeWhile { it <= 5.0 }) {
            peaks += 0.5f
            times += time
        }
        for (time in generateSequence(100.0) { it + 0.25 }.takeWhile { it <= 105.0 }) {
            peaks += 0.5f
            times += time
        }

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 0.0,
            span = 105.0,
            columns = 420,
            mapToAxis = { it },
        )

        // The opening and the ending are drawn.
        assertTrue(grid.filled[0])
        assertTrue(grid.filled[20])
        // The middle is not.
        assertFalse(grid.filled[100])
        assertFalse(grid.filled[200])
        assertFalse(grid.filled[300])
    }

    @Test
    fun samplesOutsideTheWindowAreLeftOut() {
        // A curve that ends before the view begins has nothing to say about
        // it: the lane is blank rather than drawn with the wrong stretch.
        val (peaks, times) = sparseCurve(from = 0.0, seconds = 50.0)

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 100.0,
            span = 40.0,
            columns = 400,
            mapToAxis = { it },
        )

        assertEquals(0, grid.filled.count { it })
    }

    @Test
    fun aCurveThatCoversPartOfTheWindowIsDrawnOnlyThere() {
        val (peaks, times) = sparseCurve(from = 100.0, seconds = 10.0)

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 100.0,
            span = 40.0,
            columns = 400,
            mapToAxis = { it },
        )

        // The first quarter of the view, and no further.
        assertTrue(grid.filled[0])
        assertTrue(grid.filled[90])
        assertFalse(grid.filled[110])
        assertFalse(grid.filled[399])
    }

    @Test
    fun theArrivingLanesCurveIsReadOnTheOutgoingTracksClock() {
        // The arriving lane is laid on the outgoing track's clock: its own
        // seconds are shifted by the lane's origin. A column is where the
        // shifted time lands, not where the arriving track's own time does.
        val (peaks, times) = sparseCurve(from = 0.0, seconds = 60.0)

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 60.0,
            span = 40.0,
            columns = 400,
            mapToAxis = { time -> 60.0 + time },
        )

        // The arriving track's first second sits on the outgoing track's
        // minute, so the view from 60 s shows the start of the curve.
        assertTrue(grid.filled[0])
        assertTrue(grid.filled.count { it } > 350)
    }

    @Test
    fun anEmptyCurveFillsNothing() {
        val grid = waveformColumns(
            peaks = emptyList(),
            times = emptyList(),
            from = 0.0,
            span = 30.0,
            columns = 100,
            mapToAxis = { it },
        )

        assertEquals(0, grid.filled.count { it })
    }

    // -----------------------------------------------------------------------
    // Differential coverage for the cached, visible-range implementation
    // -----------------------------------------------------------------------

    @Test
    fun optimizedColumnsMatchTheReferenceForNormalAndDenseCurves() {
        val (sparsePeaks, sparseTimes) = sparseCurve(from = 100.0, seconds = 40.0)
        val varyingPeaks = sparsePeaks.mapIndexed { index, value ->
            if (index % 3 == 0) value + 0.2f else value
        }
        for (columns in listOf(1, 2, 7, 64, 800)) {
            for ((from, span) in listOf(100.0 to 40.0, 99.75 to 0.5, 105.0 to 3.25, 120.0 to 30.0)) {
                assertMatchesReference(
                    peaks = varyingPeaks,
                    times = sparseTimes,
                    from = from,
                    span = span,
                    columns = columns,
                    mapToAxis = { it },
                )
            }
        }

        val (densePeaks, denseTimes) = denseCurve(from = 0.0, seconds = 2.0, step = 0.002)
        assertMatchesReference(
            peaks = densePeaks.mapIndexed { index, _ -> (index % 11) / 10f },
            times = denseTimes,
            from = 0.13,
            span = 0.17,
            columns = 5,
            mapToAxis = { it },
        )
    }

    @Test
    fun optimizedColumnsMatchTheReferenceOutsideTheViewport() {
        val peaks = listOf(0.2f, 0.4f, 0.8f, 0.3f)
        val times = listOf(0.0, 0.2, 0.4, 0.6)
        for ((from, span) in listOf(
            10.0 to 2.0, // Entirely after the curve.
            -10.0 to 2.0, // Entirely before it.
            0.61 to 4.0, // Starts just after its final sample.
            -4.0 to 3.99, // Ends just before its first sample.
            0.7 to 0.0, // An empty viewport is still empty.
        )) {
            val grid = assertMatchesReference(
                peaks = peaks,
                times = times,
                from = from,
                span = span,
                columns = 37,
                mapToAxis = { it },
            )
            assertFalse(grid.filled.any { it })
        }
    }

    @Test
    fun optimizedColumnsPreserveMeasuredGapsAndInterpolationLimits() {
        val times = listOf(0.0, 0.25, 0.5, 2.0, 2.25, 2.5)
        val peaks = times.mapIndexed { index, _ -> index / 5f }
        val grid = assertMatchesReference(
            peaks = peaks,
            times = times,
            from = 0.0,
            span = 2.5,
            columns = 100,
            mapToAxis = { it },
        )
        assertTrue(grid.filled[0])
        assertFalse(grid.filled[50], "the unmeasured middle must remain blank")
        assertTrue(grid.filled[99])

        // No sample lands in this narrow viewport. Both interpolation neighbours
        // are outside it, and must still be found without scanning the track.
        val neighboursOnly = assertMatchesReference(
            peaks = listOf(0.0f, 1.0f),
            times = listOf(0.0, 1.0),
            from = 0.4,
            span = 0.2,
            columns = 8,
            mapToAxis = { it },
            maxGapSeconds = 2.0,
        )
        assertTrue(neighboursOnly.filled.all { it })

        // Exactly at the limit is interpolated; beyond it is a measurement gap.
        assertMatchesReference(
            peaks = listOf(0.1f, 0.9f),
            times = listOf(1.0, 1.75),
            from = 1.0,
            span = 0.75,
            columns = 30,
            mapToAxis = { it },
        )
        assertMatchesReference(
            peaks = listOf(0.1f, 0.9f),
            times = listOf(1.0, 1.750001),
            from = 1.0,
            span = 0.750001,
            columns = 30,
            mapToAxis = { it },
        )
        for (maxGap in listOf(0.0, -1.0, Double.NaN)) {
            assertMatchesReference(
                peaks = peaks,
                times = times,
                from = 0.0,
                span = 2.5,
                columns = 31,
                mapToAxis = { it },
                maxGapSeconds = maxGap,
            )
        }
    }

    @Test
    fun optimizedColumnsPreserveInvalidSampleFilteringAndClamping() {
        val peaks = listOf(
            Float.NaN,
            0.2f,
            Float.POSITIVE_INFINITY,
            0.4f,
            Float.NEGATIVE_INFINITY,
            0.8f,
            -2f,
            2f,
        )
        val times = listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
        val mapping: (Double) -> Double = { time ->
            when (time) {
                0.3 -> Double.NaN
                0.5 -> Double.POSITIVE_INFINITY
                else -> time
            }
        }
        assertMatchesReference(
            peaks = peaks,
            times = times,
            from = 0.0,
            span = 0.75,
            columns = 47,
            mapToAxis = mapping,
        )
        // Missing times are skipped, rather than shifting later peaks left.
        assertMatchesReference(
            peaks = peaks,
            times = times.dropLast(3),
            from = 0.0,
            span = 0.75,
            columns = 47,
            mapToAxis = mapping,
        )
        // A mapping decides whether a non-finite source time is usable, just as
        // it did before caching; invalid values never reach that mapping.
        assertMatchesReference(
            peaks = listOf(0.2f, 0.4f, 0.6f, Float.NaN),
            times = listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1.0),
            from = 0.0,
            span = 1.0,
            columns = 19,
            mapToAxis = { time ->
                when {
                    time.isNaN() -> 0.25
                    time == Double.NEGATIVE_INFINITY -> 0.5
                    else -> time
                }
            },
        )
    }

    @Test
    fun optimizedColumnsMatchTheReferenceForReversedAndReorderedMappings() {
        val peaks = listOf(0.1f, 0.9f, 0.3f, 0.7f, 0.5f, 0.2f, 0.8f)
        val reorderedTimes = listOf(1.0, 0.0, 0.75, 0.25, 0.5, 0.25, 0.9)
        val mappings = listOf<(Double) -> Double>(
            { it }, // Reordered source samples.
            { -it }, // A monotonic source mapped in reverse.
            { time -> (time - 0.4) * (time - 0.4) }, // A non-monotonic mapping.
        )
        for (mapping in mappings) {
            for (columns in listOf(1, 9, 83)) {
                assertMatchesReference(
                    peaks = peaks,
                    times = reorderedTimes,
                    from = -1.2,
                    span = 2.5,
                    columns = columns,
                    mapToAxis = mapping,
                    maxGapSeconds = 2.0,
                )
            }
        }
    }

    @Test
    fun optimizedColumnsMatchTheReferenceForEmptyAndBoundaryCases() {
        val identity: (Double) -> Double = { it }
        assertMatchesReference(emptyList(), listOf(1.0), 0.0, 2.0, 10, identity)
        assertMatchesReference(listOf(0.5f), emptyList(), 0.0, 2.0, 10, identity)
        assertMatchesReference(listOf(Float.NaN), listOf(1.0), 0.0, 2.0, 10, identity)
        for (columns in listOf(-4, 0, 1)) {
            for (span in listOf(0.0, -1.0)) {
                assertMatchesReference(listOf(0.5f), listOf(1.0), 0.0, span, columns, identity)
            }
        }

        // Both inclusive edges, samples immediately outside them, one isolated
        // sample, duplicate axes, and a column count that puts several samples
        // in the same average.
        val edgeTimes = listOf(9.999999, 10.0, 10.0, 10.5, 11.999999, 12.0, 12.000001)
        val edgePeaks = edgeTimes.mapIndexed { index, _ -> index / 6f }
        for (columns in listOf(1, 2, 3, 20, 200)) {
            assertMatchesReference(edgePeaks, edgeTimes, 10.0, 2.0, columns, identity)
        }
        assertMatchesReference(listOf(0.5f), listOf(10.0), 10.0, 2.0, 100, identity)
        assertMatchesReference(
            peaks = listOf(0.2f, 0.8f, 0.4f),
            times = listOf(1.0, 1.0, 1.0),
            from = 0.5,
            span = 1.0,
            columns = 12,
            mapToAxis = identity,
        )

        // Unusual viewport arithmetic deliberately takes the compatibility
        // fallback rather than a binary-search path.
        for ((from, span) in listOf(
            Double.NaN to 1.0,
            0.0 to Double.NaN,
            0.0 to Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY to Double.POSITIVE_INFINITY,
            Double.MAX_VALUE to Double.MAX_VALUE,
        )) {
            assertMatchesReference(edgePeaks, edgeTimes, from, span, 7, identity)
        }
    }

    @Test
    fun aLaneCacheReusesMappingAcrossFramesAndInvalidatesWithItsInputs() {
        val (peaks, times) = sparseCurve(from = 0.0, seconds = 20.0)
        val cache = WaveformSampleCache()
        var mappings = 0

        fun draw(origin: Double, sourcePeaks: List<Float> = peaks, from: Double = 5.0): WaveformColumns =
            waveformColumns(
                peaks = sourcePeaks,
                times = times,
                from = from,
                span = 4.0,
                columns = 80,
                mapToAxis = { time -> mappings++; origin + time },
                sampleCache = cache,
                mappingKey = origin,
            )

        val first = draw(origin = 10.0)
        val mappingsAfterFirst = mappings
        assertTrue(mappingsAfterFirst > 0)
        val second = draw(origin = 10.0, from = 7.0)
        assertEquals(mappingsAfterFirst, mappings, "a viewport change must not remap the track")
        assertSameColumns(
            referenceWaveformColumns(peaks, times, 5.0, 4.0, 80, { 10.0 + it }, REFERENCE_MAX_GAP_SECONDS),
            first,
        )
        assertSameColumns(
            referenceWaveformColumns(peaks, times, 7.0, 4.0, 80, { 10.0 + it }, REFERENCE_MAX_GAP_SECONDS),
            second,
        )

        // The same source with a new lane origin must be remapped, or the
        // arriving waveform would lag behind the listener's drag.
        val moved = draw(origin = 11.0)
        assertTrue(mappings > mappingsAfterFirst)
        assertSameColumns(
            referenceWaveformColumns(peaks, times, 5.0, 4.0, 80, { 11.0 + it }, REFERENCE_MAX_GAP_SECONDS),
            moved,
        )

        // Equal lists with a new identity are a new digest snapshot. They are
        // safe to reuse only after their contents have actually been mapped.
        val mappingsBeforeReplacement = mappings
        draw(origin = 11.0, sourcePeaks = peaks.toList())
        assertTrue(mappings > mappingsBeforeReplacement)
    }

    @Test
    fun deterministicMixedCurvesMatchTheReferenceWithAndWithoutCaching() {
        val random = Random(0xA017)
        repeat(40) { case ->
            val size = random.nextInt(0, 60)
            val peaks = MutableList(size) {
                when (random.nextInt(12)) {
                    0 -> Float.NaN
                    1 -> Float.POSITIVE_INFINITY
                    2 -> -random.nextFloat()
                    3 -> 1f + random.nextFloat()
                    else -> random.nextFloat()
                }
            }
            var clock = -2.0 + random.nextDouble(4.0)
            val times = MutableList(size) { index ->
                val time = if (index % 3 == 0 && random.nextBoolean()) {
                    random.nextDouble(-5.0, 25.0)
                } else {
                    clock += random.nextDouble(0.005, 1.2)
                    clock
                }
                when (random.nextInt(40)) {
                    0 -> Double.NaN
                    1 -> Double.POSITIVE_INFINITY
                    else -> time
                }
            }
            val sourceTimes = if (size > 2 && random.nextBoolean()) {
                times.dropLast(random.nextInt(1, 3))
            } else {
                times
            }
            val mappingKind = case % 5
            val mapping: (Double) -> Double = { time ->
                when (mappingKind) {
                    0 -> time
                    1 -> time + 7.25
                    2 -> -time
                    3 -> (time - 3.0) * (time - 3.0)
                    else -> if (time > 8.0) Double.NaN else time
                }
            }
            val cache = WaveformSampleCache()
            val from = random.nextDouble(-10.0, 20.0)
            val span = random.nextDouble(0.001, 20.0)
            val columns = listOf(-2, 1, 3, 17, 128)[case % 5]
            assertMatchesReference(
                peaks = peaks,
                times = sourceTimes,
                from = from,
                span = span,
                columns = columns,
                mapToAxis = mapping,
                maxGapSeconds = if (case % 2 == 0) 0.75 else 1.5,
                sampleCache = cache,
                mappingKey = mappingKind,
            )
            assertMatchesReference(
                peaks = peaks,
                times = sourceTimes,
                from = from + 0.37,
                span = span * 0.83,
                columns = columns + 2,
                mapToAxis = mapping,
                maxGapSeconds = if (case % 2 == 0) 0.75 else 1.5,
                sampleCache = cache,
                mappingKey = mappingKind,
            )
        }
    }

    private fun assertMatchesReference(
        peaks: List<Float>,
        times: List<Double>,
        from: Double,
        span: Double,
        columns: Int,
        mapToAxis: (Double) -> Double,
        maxGapSeconds: Double = REFERENCE_MAX_GAP_SECONDS,
        sampleCache: WaveformSampleCache? = null,
        mappingKey: Any? = mapToAxis,
    ): WaveformColumns {
        val expected = referenceWaveformColumns(
            peaks, times, from, span, columns, mapToAxis, maxGapSeconds,
        )
        val actual = waveformColumns(
            peaks = peaks,
            times = times,
            from = from,
            span = span,
            columns = columns,
            mapToAxis = mapToAxis,
            maxGapSeconds = maxGapSeconds,
            sampleCache = sampleCache,
            mappingKey = mappingKey,
        )
        assertSameColumns(expected, actual)
        return actual
    }

    private fun assertSameColumns(expected: WaveformColumns, actual: WaveformColumns) {
        assertContentEquals(expected.filled, actual.filled, "filled columns")
        assertContentEquals(expected.tops, actual.tops, "column peaks")
        assertContentEquals(expected.bodies, actual.bodies, "column bodies")
    }

    /**
     * Test-only copy of the original full-scan algorithm. Production has one
     * implementation; this reference makes the optimization prove every part
     * of the old output contract instead of merely checking a few filled pixels.
     */
    private fun referenceWaveformColumns(
        peaks: List<Float>,
        times: List<Double>,
        from: Double,
        span: Double,
        columns: Int,
        mapToAxis: (Double) -> Double,
        maxGapSeconds: Double,
    ): WaveformColumns {
        val count = columns.coerceAtLeast(1)
        val tops = FloatArray(count)
        val bodies = FloatArray(count)
        val filled = BooleanArray(count)
        if (peaks.isEmpty() || times.isEmpty() || span <= 0.0) return WaveformColumns(tops, bodies, filled)

        val axes = DoubleArray(peaks.size)
        val values = FloatArray(peaks.size)
        var samples = 0
        var ordered = true
        var lastAxis = Double.NEGATIVE_INFINITY
        for (index in peaks.indices) {
            val time = times.getOrNull(index) ?: continue
            val value = peaks[index]
            if (!value.isFinite()) continue
            val axis = mapToAxis(time)
            if (!axis.isFinite()) continue
            if (axis < lastAxis) ordered = false
            lastAxis = axis
            axes[samples] = axis
            values[samples] = value.coerceIn(0f, 1f)
            samples++
        }
        if (samples == 0) return WaveformColumns(tops, bodies, filled)
        val order: Array<Int>? = if (ordered) {
            null
        } else {
            IntArray(samples) { it }.toList().sortedBy { axes[it] }.toTypedArray()
        }
        fun axisAt(position: Int): Double = axes[order?.get(position) ?: position]
        fun valueAt(position: Int): Float = values[order?.get(position) ?: position]

        val sums = DoubleArray(count)
        val counts = IntArray(count)
        for (sample in 0 until samples) {
            val index = order?.get(sample) ?: sample
            val axis = axes[index]
            if (axis < from || axis > from + span) continue
            val column = (((axis - from) / span) * count).toInt().coerceIn(0, count - 1)
            val value = values[index]
            if (value > tops[column]) tops[column] = value
            sums[column] += value
            counts[column] += 1
        }
        for (column in 0 until count) {
            if (counts[column] <= 0) continue
            bodies[column] = (sums[column] / counts[column]).toFloat()
            filled[column] = true
        }

        var index = 0
        for (column in 0 until count) {
            if (filled[column]) continue
            val position = from + (column + 0.5) / count * span
            while (index < samples - 1 && axisAt(index + 1) <= position) index++
            if (index + 1 >= samples) continue
            val left = axisAt(index)
            val right = axisAt(index + 1)
            if (position < left || position > right) continue
            val gap = right - left
            if (gap <= 0.0 || gap > maxGapSeconds) continue
            val ratio = ((position - left) / gap).toFloat()
            val value = valueAt(index) + (valueAt(index + 1) - valueAt(index)) * ratio
            tops[column] = value
            bodies[column] = value
            filled[column] = true
        }
        return WaveformColumns(tops, bodies, filled)
    }
}
