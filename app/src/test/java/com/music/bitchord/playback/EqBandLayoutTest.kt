package com.music.bitchord.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Variable band counts, custom layouts, and gain remapping across relayouts. */
class EqBandLayoutTest {
    @Test
    fun referenceCountsAndLabelsMatchPawsExactly() {
        assertArrayEquals(intArrayOf(5, 10, 12, 15, 16, 24, 31, 32), EqBandLayout.BAND_COUNTS)
        assertEquals(
            listOf("5", "10 (ISO)", "12", "15 (ISO)", "16", "24", "31 (ISO)", "32"),
            EqBandLayout.BAND_COUNTS.map(EqBandLayout::labelFor),
        )
        assertEquals("10 (ISO)", EqBandLayout.labelFor(10))
        assertEquals("31 (ISO)", EqBandLayout.labelFor(31))
        assertEquals("7", EqBandLayout.labelFor(7)) // non-standard prints as-is
        assertEquals(10, EqBandLayout.DEFAULT_COUNT)
    }

    @Test
    fun standardCountTenKeepsTheClassicPowerampCentres() {
        assertArrayEquals(EqLayout.MANUAL_BANDS_HZ, EqBandLayout.frequenciesFor(10), 0f)
    }

    @Test
    fun countThirtyOneIsTheFullIsoSeries() {
        val freqs = EqBandLayout.frequenciesFor(31)
        assertEquals(31, freqs.size)
        assertArrayEquals(EqBandLayout.ISO_SERIES_HZ, freqs, 0f)
        assertTrue(freqs.first() == 20f && freqs.last() == 20_000f)
    }

    @Test
    fun everyCountLandsBetween20HzAnd20kHzAndIsSorted() {
        for (count in 5..32) {
            val freqs = EqBandLayout.frequenciesFor(count)
            assertEquals(count, freqs.size)
            assertTrue(freqs.all { it in 20f..20_000f })
            assertTrue(freqs.toList().zipWithNext().all { (a, b) -> a < b })
        }
    }

    @Test
    fun customBandsAreLogSpacedBetweenFirstAndLastAndClamped() {
        val freqs = EqBandLayout.customFrequencies(10, 20f, 16_000f)
        assertEquals(10, freqs.size)
        assertEquals(20f, freqs.first())
        assertEquals(16_000f, freqs.last())
        // Out-of-range custom bounds are clamped to the reference ranges.
        val clamped = EqBandLayout.customFrequencies(64, 1f, 99_000f)
        assertEquals(32, clamped.size)
        assertEquals(5f, clamped.first())
        assertEquals(20_000f, clamped.last())
    }

    @Test
    fun remapGainsFollowsNearestCentreWhenAddingAndDeletingBands() {
        val oldFreqs = EqBandLayout.frequenciesFor(10)
        val oldGains = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        val newFreqs = EqBandLayout.frequenciesFor(12)
        val mapped = EqBandLayout.remapGains(oldFreqs, oldGains, newFreqs)
        assertEquals(12, mapped.size)
        // Every mapped gain must come from the old list.
        assertTrue(mapped.all { it in oldGains })
        // 31 Hz was +1 dB and stays on the band that lands nearest 31 Hz.
        val index31 = newFreqs.indexOfFirst { it == 31.5f || it == 31f }
        if (index31 >= 0) assertEquals(1f, mapped[index31])
    }

    @Test
    fun configureManualBandsResizesActiveSlotsAndKeepsEndsAsShelves() {
        EqLayout.configureManualBands(EqBandLayout.frequenciesFor(24))
        try {
            assertEquals(24, EqLayout.manualCount)
            assertEquals(24, EqLayout.manualFrequenciesHz().size)
            assertEquals(FilterKind.LOW_SHELF, EqLayout.slots[0].kind)
            assertEquals(FilterKind.HIGH_SHELF, EqLayout.slots[23].kind)
            assertEquals(FilterKind.BELL, EqLayout.slots[12].kind)
            // A 24-band curve leaves slots 24..31 at zero.
            val curve = manualCurve(List(24) { 3f }, 24)
            assertEquals(3f, curve.gainsDb[23])
            assertEquals(0f, curve.gainsDb[24])
            assertEquals(0f, curve.gainsDb[31])
        } finally {
            EqLayout.configureManualBands(EqLayout.MANUAL_BANDS_HZ.copyOf())
        }
        assertEquals(10, EqLayout.manualCount)
    }

    @Test
    fun toneCurveRetunesTheTiltPairCorners() {
        val before = EqLayout.slots[EqLayout.TONE_LOW].frequencyHz
        toneCurve(2, 0, focused = false, bassHz = 100f, trebleHz = 8_000f, bassQ = 1.4f, trebleQ = 2.0f)
        try {
            assertEquals(100f, EqLayout.slots[EqLayout.TONE_LOW].frequencyHz)
            assertEquals(8_000f, EqLayout.slots[EqLayout.TONE_HIGH].frequencyHz)
        } finally {
            toneCurve(0, 0, focused = false)
        }
        assertEquals(before, EqLayout.slots[EqLayout.TONE_LOW].frequencyHz)
    }
}
