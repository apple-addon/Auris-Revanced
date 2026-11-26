package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchBackPolicyTest {
    @Test
    fun `back unwinds keyboard results recents and genre wall in order`() {
        assertEquals(
            SearchBackStep.HIDE_KEYBOARD,
            searchBackStep(keyboardActive = true, hasQuery = true, historyPageActive = true),
        )
        assertEquals(
            SearchBackStep.CLEAR_RESULTS,
            searchBackStep(keyboardActive = false, hasQuery = true, historyPageActive = true),
        )
        assertEquals(
            SearchBackStep.SHOW_GENRES,
            searchBackStep(keyboardActive = false, hasQuery = false, historyPageActive = true),
        )
        assertEquals(
            SearchBackStep.EXIT_SEARCH,
            searchBackStep(keyboardActive = false, hasQuery = false, historyPageActive = false),
        )
    }

    @Test
    fun `return tab must still be visible and cannot be Search itself`() {
        assertEquals(1, validSearchReturnTab(1, listOf(0, 1, 2, 3, 4)))
        assertEquals(0, validSearchReturnTab(1, listOf(0, 2, 3, 4)))
        assertEquals(0, validSearchReturnTab(4, listOf(0, 1, 2, 3, 4)))
        assertEquals(0, validSearchReturnTab(99, listOf(0, 1, 2, 3, 4)))
    }
}
