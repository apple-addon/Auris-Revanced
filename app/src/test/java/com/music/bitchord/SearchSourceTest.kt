package com.music.bitchord

import com.music.bitchord.ui.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceTest {
    @Test
    fun `selector puts preference first and Library fourth without duplicates`() {
        val expected = mapOf(
            SearchSource.APPLE to listOf(
                SearchSource.APPLE,
                SearchSource.SPOTIFY,
                SearchSource.YOUTUBE,
                SearchSource.LIBRARY,
            ),
            SearchSource.SPOTIFY to listOf(
                SearchSource.SPOTIFY,
                SearchSource.APPLE,
                SearchSource.YOUTUBE,
                SearchSource.LIBRARY,
            ),
            SearchSource.YOUTUBE to listOf(
                SearchSource.YOUTUBE,
                SearchSource.APPLE,
                SearchSource.SPOTIFY,
                SearchSource.LIBRARY,
            ),
            SearchSource.LIBRARY to listOf(
                SearchSource.APPLE,
                SearchSource.SPOTIFY,
                SearchSource.YOUTUBE,
                SearchSource.LIBRARY,
            ),
        )
        SearchSource.entries.forEach { source ->
            val ordered = SearchSource.ordered(source)
            assertEquals(expected.getValue(source), ordered)
            assertEquals(4, ordered.distinct().size)
            assertEquals(SearchSource.LIBRARY, ordered[3])
        }
    }

    @Test
    fun `navigation cycle reaches every source including Apple and Library`() {
        val seen = mutableListOf<SearchSource>()
        var source = SearchSource.YOUTUBE
        repeat(4) {
            seen += source
            source = SearchSource.next(source)
        }
        assertEquals(
            listOf(
                SearchSource.YOUTUBE,
                SearchSource.LIBRARY,
                SearchSource.APPLE,
                SearchSource.SPOTIFY,
            ),
            seen,
        )
        assertEquals(SearchSource.YOUTUBE, source)
    }
}
