package com.music.bitchord

/**
 * Bounded page-key history used only to choose transition direction.
 * No Compose state: advancing cannot trigger another recomposition.
 */
internal class RootPageMotion(private val maxHistory: Int = 32) {
    private val history = ArrayDeque<String>()

    init {
        require(maxHistory >= 2) { "At least two page keys are needed for Back detection" }
    }

    /** +1 push, -1 immediate-predecessor Back, 0 initial/unchanged page. */
    fun advance(target: String): Int {
        val last = history.lastOrNull() ?: run {
            history.addLast(target)
            return 0
        }
        if (last == target) return 0
        if (history.size > 1 && history[history.size - 2] == target) {
            history.removeLast()
            return -1
        }
        history.addLast(target)
        while (history.size > maxHistory) history.removeFirst()
        return 1
    }
}
