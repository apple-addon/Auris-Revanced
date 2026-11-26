package com.music.bitchord.ui.components

/** One Back step inside Search, after any pushed detail/overlay has closed. */
internal enum class SearchBackStep {
    HIDE_KEYBOARD,
    CLEAR_RESULTS,
    SHOW_GENRES,
    EXIT_SEARCH,
}

internal fun searchBackStep(
    keyboardActive: Boolean,
    hasQuery: Boolean,
    historyPageActive: Boolean,
): SearchBackStep = when {
    keyboardActive -> SearchBackStep.HIDE_KEYBOARD
    hasQuery -> SearchBackStep.CLEAR_RESULTS
    historyPageActive -> SearchBackStep.SHOW_GENRES
    else -> SearchBackStep.EXIT_SEARCH
}

/** A disabled/hidden originating tab can never be restored. */
internal fun validSearchReturnTab(
    candidate: Int,
    visibleTabs: List<Int>,
    homeTab: Int = 0,
    searchTab: Int = 4,
): Int = candidate.takeIf { it != searchTab && it in visibleTabs } ?: homeTab
