package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.LyricFocus
import com.music.bitchord.ui.player.activeLyricRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class LyricFocusTest {
    private val overlap = listOf(
        LyricLine(1_000, "upper", sungUntilMs = 4_000),
        LyricLine(2_000, "middle", sungUntilMs = 5_000),
        LyricLine(3_000, "lower", sungUntilMs = 6_000),
    )

    @Test fun upperLineKeepsAnchorAcrossThreeOverlappingVocals() {
        assertEquals(listOf(0, 1, 2), activeLyricRows(overlap, 3_500))
    }

    @Test fun anchorAdvancesExactlyWhenUpperVocalEnds() {
        assertEquals(listOf(1, 2), activeLyricRows(overlap, 4_000))
        assertEquals(listOf(2), activeLyricRows(overlap, 5_000))
    }

    @Test fun finishedMiddleRowDoesNotCutOffLongUpperVocal() {
        val lines = overlap.toMutableList()
        lines[1] = lines[1].copy(sungUntilMs = 2_900)
        assertEquals(listOf(0, 2), activeLyricRows(lines, 3_500))
    }

    @Test fun backgroundVocalKeepsItsParentRowAnchored() {
        val lines = listOf(
            LyricLine(1_000, "lead", sungUntilMs = 1_900,
                background = LyricLine(1_500, "echo", sungUntilMs = 3_000)),
            LyricLine(2_000, "next", sungUntilMs = 4_000),
        )
        assertEquals(listOf(0, 1), activeLyricRows(lines, 2_500))
        assertEquals(listOf(1), activeLyricRows(lines, 3_000))
    }

    @Test fun plainLrcAdvancesWithoutInventingAnOverlap() {
        val lines = overlap.map { it.copy(sungUntilMs = null) }
        assertEquals(listOf(1), activeLyricRows(lines, 2_500))
    }

    @Test fun backwardSeekRecomputesTheAnchor() {
        assertEquals(listOf(2), activeLyricRows(overlap, 5_500))
        assertEquals(listOf(0), activeLyricRows(overlap, 1_500))
        assertEquals(emptyList<Int>(), activeLyricRows(overlap, 500))
    }

    @Test fun currentLineObservesExactBoundariesAndDuplicateTimestamps() {
        val focus = LyricFocus(listOf(
            LyricLine(1_000, "first"),
            LyricLine(1_000, "duplicate"),
            LyricLine(2_000, "next"),
        ))

        assertEquals(-1, focus.currentLine(999))
        assertEquals(1, focus.currentLine(1_000))
        assertEquals(1, focus.currentLine(1_999))
        assertEquals(2, focus.currentLine(2_000))
        assertEquals(1, focus.currentLine(1_500))
    }

    @Test fun outOfOrderInputKeepsLastStartedOriginalRowSemantics() {
        val lines = listOf(
            LyricLine(2_000, "later timestamp, earlier row", sungUntilMs = 3_000),
            LyricLine(1_000, "earlier timestamp, later row", sungUntilMs = 3_500),
            LyricLine(4_000, "not started"),
        )
        val focus = LyricFocus(lines)

        assertEquals(-1, focus.currentLine(999))
        assertEquals(1, focus.currentLine(1_000))
        // indexOfLast also chooses row 1 here, despite row 0's later timestamp.
        assertEquals(1, focus.currentLine(2_500))
        assertEquals(listOf(0, 1), focus.activeRows(2_500))
        assertEquals(listOf(1), focus.activeRows(3_000))
        assertEquals(2, focus.currentLine(4_000))
    }

    @Test fun duplicateStartsPublishAllAndOnlyTheMatchingActiveRows() {
        val lines = listOf(
            LyricLine(1_000, "upper", sungUntilMs = 2_000),
            LyricLine(1_000, "latest", sungUntilMs = 3_000),
            LyricLine(2_000, "next", sungUntilMs = 4_000),
        )
        val focus = LyricFocus(lines)

        assertEquals(listOf(0, 1), focus.activeRows(1_000))
        assertEquals(listOf(1, 2), focus.activeRows(2_000))
        assertEquals(listOf(2), focus.activeRows(3_000))
    }

    @Test fun unchangedMembershipReusesActiveRowsAcrossFrames() {
        val focus = LyricFocus(overlap)
        val active = focus.activeRows(3_500)

        assertSame(active, focus.activeRows(3_501))
        assertSame(active, focus.activeRows(3_999))
        val afterUpperEnds = focus.activeRows(4_000)
        assertEquals(listOf(1, 2), afterUpperEnds)
        assertNotSame(active, afterUpperEnds)
        assertSame(afterUpperEnds, focus.activeRows(4_001))

        val backward = focus.activeRows(3_500)
        assertEquals(active, backward)
        assertNotSame(afterUpperEnds, backward)
    }

    @Test fun redundantTimingEventDoesNotAllocateAnotherActiveList() {
        val focus = LyricFocus(listOf(
            LyricLine(2_000, ""),
            LyricLine(1_000, "still current"),
        ))
        val active = focus.activeRows(1_500)

        assertEquals(listOf(1), active)
        assertSame(active, focus.activeRows(2_000))
    }

    @Test fun panelFocusSharesStableRowsBetweenCurrentAndLookAheadReads() {
        val focus = LyricFocus(listOf(
            LyricLine(1_000, "first", sungUntilMs = 2_000),
            LyricLine(10_000, "next", sungUntilMs = 11_000),
        ))
        val beforeLead = focus.panelFocus(9_000)
        assertEquals(listOf(0), beforeLead.activeRows)
        assertEquals(0, beforeLead.scrollLine)
        assertEquals(0, beforeLead.leadLine)

        val leading = focus.panelFocus(9_500)
        assertEquals(listOf(0), leading.activeRows)
        assertEquals(0, leading.scrollLine)
        assertEquals(1, leading.leadLine)
        assertEquals(1, leading.focusLine)
        assertSame(leading, focus.panelFocus(9_501))
        assertSame(leading.activeRows, focus.panelFocus(9_502).activeRows)

        val handover = focus.panelFocus(10_000)
        assertEquals(listOf(1), handover.activeRows)
        assertEquals(1, handover.scrollLine)
        assertEquals(1, handover.leadLine)
    }
}
