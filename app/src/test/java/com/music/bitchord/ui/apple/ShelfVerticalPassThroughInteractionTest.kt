/*
 * Copyright (C) 2026 BitChord
 *
 * A sideways shelf must not keep the page from scrolling vertically while the
 * shelf is still travelling sideways. Fingers do not wait for a row to stop.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.components.ShelfRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class ShelfVerticalPassThroughInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var page: LazyListState
    private lateinit var row: LazyListState

    private fun setPage(rowItems: Int = 30) {
        compose.setContent {
            page = rememberLazyListState()
            row = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().testTag("page"),
                state = page,
            ) {
                item { Box(Modifier.fillMaxWidth().height(120.dp)) }
                item {
                    ShelfRow(
                        contentPadding = PaddingValues(0.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxWidth().height(120.dp).testTag("row"),
                        state = row,
                    ) {
                        items(rowItems) { Box(Modifier.size(100.dp)) }
                    }
                }
                items(40) {
                    Box(Modifier.fillMaxWidth().height(100.dp))
                }
            }
        }
    }

    private fun assertPageMoved() {
        compose.waitForIdle()
        // 350px of travel on a 2x screen is 175dp. The page's first two
        // sections are 120dp each, so this lands on the second section. More
        // than one section of extra travel would mean the row's pass-through
        // and the page's own drag both handled the same movement.
        assertTrue(
            "the page must scroll, but only once: " +
                "index=${page.firstVisibleItemIndex} offset=${page.firstVisibleItemScrollOffset}",
            page.firstVisibleItemIndex == 1 || page.firstVisibleItemScrollOffset > 0,
        )
        assertTrue(
            "the page must not be scrolled twice: " +
                "index=${page.firstVisibleItemIndex} offset=${page.firstVisibleItemScrollOffset}",
            page.firstVisibleItemIndex <= 1,
        )
    }

    /** A plain vertical drag that starts on a shelf stays a plain page drag. */
    @Test
    fun verticalDragOnShelfWithoutHorizontalMovementScrollsOnce() {
        setPage()
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(Offset(0f, -350f))
            up()
        }
        assertPageMoved()
    }

    /** A shelf too short to scroll sideways must not pass vertical twice. */
    @Test
    fun horizontalDragOnShortShelfThenVerticalScrollsOnce() {
        setPage(rowItems = 3)
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(0f, -350f))
            up()
        }
        assertPageMoved()
    }

    /** A vertical drag added while the finger is still sliding the shelf. */
    @Test
    fun verticalDragDuringHorizontalDragAlsoScrollsThePage() {
        setPage()
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(-60f, 0f))
            moveBy(Offset(0f, -350f))
            up()
        }
        assertPageMoved()
    }

    /** A vertical drag begun on a shelf that is still flinging sideways. */
    @Test
    fun verticalDragDuringHorizontalFlingAlsoScrollsThePage() {
        setPage()
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(Offset(-80f, 0f))
            moveBy(Offset(-160f, 0f))
            up()
        }
        compose.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(Offset(0f, -350f))
            up()
        }
        assertPageMoved()
    }
}
