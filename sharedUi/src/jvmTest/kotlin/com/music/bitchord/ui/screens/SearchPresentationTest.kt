package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SearchPresentationTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = id,
        artist = "Artist",
        thumbnailUrl = null,
    )

    private fun browse(id: String, type: BrowseType) = SearchResult.Browse(
        BrowseItem(
            browseId = id,
            title = id,
            subtitle = "",
            thumbnailUrl = null,
            type = type,
        ),
    )

    @Test
    fun `all results are partitioned without losing their order`() {
        val top = SearchResult.TopTrack(song("top"))
        val first = SearchResult.Track(song("first"))
        val second = SearchResult.Track(song("second"))
        val artist = browse("artist", BrowseType.ARTIST)
        val album = browse("album", BrowseType.ALBUM)
        val playlist = browse("playlist", BrowseType.PLAYLIST)
        val other = browse("other", BrowseType.OTHER)
        val podcast = browse("podcast", BrowseType.PODCAST)
        val rows = listOf(top, artist, first, album, second, playlist, other, podcast)

        val presentation = searchPresentation(rows, SearchFilter.ALL)

        assertEquals(listOf("top", "first", "second"), presentation.tracks.map(Song::videoId))
        assertSame(top, presentation.topResult)
        assertEquals(listOf("Songs", "Artists", "Albums", "Playlists", "More"), presentation.sections.map { it.title })
        assertEquals(listOf(first, second), presentation.sections[0].rows)
        assertEquals(listOf(artist), presentation.sections[1].rows)
        assertEquals(listOf(album), presentation.sections[2].rows)
        assertEquals(listOf(playlist), presentation.sections[3].rows)
        assertEquals(listOf(other), presentation.sections[4].rows)
    }

    @Test
    fun `a filtered result snapshot keeps every original row in one section`() {
        val top = SearchResult.TopTrack(song("top"))
        val podcast = browse("podcast", BrowseType.PODCAST)
        val track = SearchResult.Track(song("track"))
        val rows = listOf(top, podcast, track)

        val presentation = searchPresentation(rows, SearchFilter.PODCASTS)

        assertEquals(listOf(SearchSection(null, rows)), presentation.sections)
        assertEquals(listOf("top", "track"), presentation.tracks.map(Song::videoId))
        assertSame(top, presentation.topResult)
    }

    @Test
    fun `empty all sections stay absent`() {
        val track = SearchResult.Track(song("track"))

        val presentation = searchPresentation(listOf(track), SearchFilter.ALL)

        assertEquals(listOf(SearchSection("Songs", listOf(track))), presentation.sections)
    }
}
