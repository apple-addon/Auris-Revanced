package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine

/** How long before a line lands the panel starts moving to it. */
private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L

/** The panel's current vocals and the row toward which it is scrolling. */
internal data class LyricPanelFocus(
    val activeRows: List<Int>,
    val scrollLine: Int,
    val leadLine: Int,
) {
    val focusLine: Int get() = if (leadLine >= 0) leadLine else scrollLine

    companion object {
        val None = LyricPanelFocus(emptyList(), -1, -1)
    }
}

/**
 * Indexed timing lookups for one immutable lyric sheet.
 *
 * The frame clock changes much more often than lyric membership. Start and end
 * stamps therefore form a revision: frames within the same revision return the
 * very same active-row list, without walking the sheet or allocating a copy.
 */
internal class LyricFocus(private val lines: List<LyricLine>) {
    /*
     * Ordinary lyric sheets are already ordered, so their current row is an
     * upper-bound search (and normally just the cached row's interval). Keep a
     * small time/index table only for malformed, out-of-order sheets. Its
     * prefix maxima preserve indexOfLast's original-row semantics exactly.
     */
    private val sortedTimes: LongArray?
    private val latestThroughTime: IntArray?
    private val membershipTimes: LongArray
    private val currentRows = ActiveLyricRows()
    private val leadingRows = ActiveLyricRows()

    private var hasCachedCurrent = false
    private var cachedPosition = 0L
    private var cachedCurrent = -1
    private var panelFocus: LyricPanelFocus? = null

    init {
        var ordered = true
        for (index in 1 until lines.size) {
            if (lines[index].timeMs < lines[index - 1].timeMs) {
                ordered = false
                break
            }
        }
        if (ordered) {
            sortedTimes = null
            latestThroughTime = null
        } else {
            val order = lines.indices.sortedBy { lines[it].timeMs }
            val latest = IntArray(order.size)
            for (index in order.indices) {
                latest[index] = maxOf(order[index], if (index == 0) -1 else latest[index - 1])
            }
            sortedTimes = LongArray(order.size) { lines[order[it]].timeMs }
            latestThroughTime = latest
        }

        var eventCount = lines.size
        for (line in lines) {
            if (!line.isGap && line.hasActiveEnd) eventCount++
        }
        membershipTimes = LongArray(eventCount)
        var event = 0
        for (line in lines) {
            membershipTimes[event++] = line.timeMs
            if (!line.isGap && line.hasActiveEnd) membershipTimes[event++] = line.endMs
        }
        membershipTimes.sort()
    }

    /** The last row whose timestamp has passed, or -1 before the first. */
    fun currentLine(positionMs: Long): Int {
        if (hasCachedCurrent) {
            if (positionMs == cachedPosition) return cachedCurrent
            // In an ordered sheet, almost every frame remains in this same
            // interval. The strict next-row comparison is important for equal
            // timestamps: at that instant the last duplicate becomes current.
            if (sortedTimes == null) {
                if (cachedCurrent < 0) {
                    if (lines.isEmpty() || positionMs < lines[0].timeMs) return cachedCurrent
                } else if (positionMs >= lines[cachedCurrent].timeMs &&
                    (cachedCurrent == lines.lastIndex ||
                        lines[cachedCurrent + 1].timeMs > positionMs)
                ) {
                    return cachedCurrent
                }
            }
        }
        return latestLine(positionMs).also {
            hasCachedCurrent = true
            cachedPosition = positionMs
            cachedCurrent = it
        }
    }

    /** Keep every unfinished vocal visible, including overlaps of any length. */
    fun activeRows(positionMs: Long): List<Int> =
        currentRows.at(positionMs, currentLine(positionMs))

    /** Compute the current and look-ahead focus from one reading of the clock. */
    fun panelFocus(positionMs: Long): LyricPanelFocus {
        val current = currentLine(positionMs)
        val active = currentRows.at(positionMs, current)
        val scrollLine = active.firstOrNull() ?: -1
        val leadPosition = positionMs + scrollLead(positionMs, current)
        val leadLine = leadingRows.at(leadPosition, latestLine(leadPosition)).firstOrNull() ?: -1

        panelFocus?.let {
            if (it.activeRows === active && it.scrollLine == scrollLine && it.leadLine == leadLine) {
                return it
            }
        }
        return LyricPanelFocus(active, scrollLine, leadLine).also { panelFocus = it }
    }

    /**
     * The move's run-up is the silence after the current vocal, bounded because
     * that silence can be a held breath or half a verse. Before the first line
     * there is no current end to measure, so use the minimum.
     */
    fun scrollLead(positionMs: Long, current: Int = currentLine(positionMs)): Long {
        if (current < 0) return SCROLL_LEAD_MIN_MS
        val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
        return (next.timeMs - lines[current].endMs)
            .coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
    }

    private fun latestLine(positionMs: Long): Int {
        val times = sortedTimes
        if (times != null) {
            val count = upperBound(times, positionMs)
            return if (count == 0) -1 else latestThroughTime!![count - 1]
        }

        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }

    private inner class ActiveLyricRows {
        private var revision = -1
        private var rows: List<Int> = emptyList()
        private val scratch = ArrayList<Int>()

        fun at(positionMs: Long, latest: Int): List<Int> {
            val nextRevision = upperBound(membershipTimes, positionMs)
            if (nextRevision == revision) return rows

            scratch.clear()
            if (latest >= 0) {
                for (index in 0..latest) {
                    val line = lines[index]
                    if (index == latest || (!line.isGap && line.hasActiveEnd &&
                            line.timeMs <= positionMs && positionMs < line.endMs)
                    ) {
                        scratch += index
                    }
                }
            }
            // An event can be redundant (for example on malformed input).
            // Compare through the reusable scratch list before making a copy.
            if (scratch == rows) {
                revision = nextRevision
                return rows
            }
            rows = if (scratch.isEmpty()) emptyList() else scratch.toList()
            revision = nextRevision
            return rows
        }
    }
}

private val LyricLine.hasActiveEnd: Boolean
    get() = hasKnownEnd || background?.hasKnownEnd == true

/** Stateless convenience for tests and one-off callers; frames share [LyricFocus]. */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> =
    LyricFocus(lines).activeRows(positionMs)

private fun upperBound(values: LongArray, value: Long): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (values[middle] <= value) low = middle + 1 else high = middle
    }
    return low
}
