package com.music.bitchord

import com.music.bitchord.data.apple.AppleItem
import com.music.bitchord.data.apple.AppleSources
import com.music.bitchord.data.apple.appleDateAddedMillis
import com.music.bitchord.data.apple.appleRecentlyAdded
import com.music.bitchord.data.apple.libraryAlbumRow
import com.music.bitchord.data.apple.libraryArtistRow
import com.music.bitchord.data.apple.libraryPlaylistRow
import com.music.bitchord.data.apple.song
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.SongSort
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.ui.screens.matchingAppleLibrary
import com.music.bitchord.ui.screens.sortedForDetail
import com.music.bitchord.ui.updateAppleRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleLibraryMappingTest {

    @Test
    fun `collection tracks use catalogue playback identity and retain quality`() {
        val song = AppleItem(
            id = "library-song-id",
            catalogId = "catalog-song-id",
            title = "Recording",
            dateAdded = "2026-11-20T12:00:00Z",
            audioQuality = "HI-RES LOSSLESS",
            audioMode = "DOLBY_ATMOS",
        ).song("apple-test")

        assertEquals("apple-test" to "catalog-song-id", SourceRegistry.parseTrackKey(song.videoId))
        assertEquals("HI-RES LOSSLESS", song.sourceQuality)
        assertEquals(true, song.atmosAvailable)
        assertEquals(true, song.hiResAvailable)
        assertEquals(appleDateAddedMillis("2026-11-20T12:00:00Z"), song.catalogueDateAddedMs)
    }

    @Test
    fun `missing catalogue identity falls back only to the library id`() {
        val missing = AppleItem(id = "library-id", title = "Song").song("apple-test")
        val blank = AppleItem(id = "library-id", catalogId = " ", title = "Song")
            .song("apple-test")

        assertEquals("library-id", SourceRegistry.parseTrackKey(missing.videoId)?.second)
        assertEquals("library-id", SourceRegistry.parseTrackKey(blank.videoId)?.second)
    }

    @Test
    fun `unresolved artists remain visible without fake catalogue routes`() {
        val unresolved = AppleItem(id = "library-artist", title = "Artist").libraryArtistRow()
        val resolved = AppleItem(
            id = "library-artist",
            catalogId = "catalog-artist",
            title = "Artist",
        ).libraryArtistRow()

        assertEquals("Artist", unresolved?.title)
        assertNull(unresolved?.browseId)
        assertEquals(AppleSources.ARTIST_PREFIX + "catalog-artist", resolved?.browseId)
    }

    @Test
    fun `recently added mixes real dates and retains library destinations`() {
        val oldPlaylist = AppleItem(
            id = "p.old",
            title = "Old playlist",
            dateAdded = "2026-01-01T00:00:00Z",
        ).libraryPlaylistRow()!!
        val newestAlbum = AppleItem(
            id = "a.new",
            title = "New album",
            dateAdded = "2026-11-01T00:00:00Z",
        ).libraryAlbumRow()!!
        val undatedPlaylist = AppleItem(id = "p.undated", title = "Undated")
            .libraryPlaylistRow()!!

        val recent = appleRecentlyAdded(
            playlists = listOf(oldPlaylist, undatedPlaylist),
            albums = listOf(newestAlbum),
        )

        assertEquals(listOf("New album", "Old playlist", "Undated"), recent.map { it.title })
        assertEquals(AppleSources.LIB_ALBUM_PREFIX + "a.new", recent[0].browseId)
        assertEquals(AppleSources.PAGE_PREFIX + "p.old", recent[1].browseId)
    }

    @Test
    fun `category filter covers every title and subtitle without truncation`() {
        val rows = List(12) { index ->
            ShelfItem(
                title = if (index == 10) "Taylor's Playlist" else "Playlist $index",
                subtitle = if (index == 11) "Taylor Swift" else "Someone else",
                thumbnailUrl = null,
                videoId = null,
                browseId = "row-$index",
            )
        }

        assertEquals(12, rows.matchingAppleLibrary("").size)
        assertEquals(listOf("row-10", "row-11"), rows.matchingAppleLibrary("taylor").map { it.browseId })
        assertEquals(emptyList<ShelfItem>(), rows.matchingAppleLibrary("missing"))
    }

    @Test
    fun `apple song sorts use real dates and artist names`() {
        val songs = listOf(
            Song("1", "First", "Zed", null, catalogueDateAddedMs = 10),
            Song("2", "Second", "Amy", null, catalogueDateAddedMs = 20),
            Song("3", "Undated", "Middle", null),
        )

        assertEquals(
            listOf("2", "1", "3"),
            songs.sortedForDetail(SongSort.DATE_ADDED_DESC, appleSongLibrary = true).map { it.videoId },
        )
        assertEquals(
            listOf("2", "3", "1"),
            songs.sortedForDetail(SongSort.ARTIST_ASC, appleSongLibrary = true).map { it.videoId },
        )
        assertEquals(
            listOf("1", "2", "3"),
            songs.sortedForDetail(SongSort.DATE_ADDED_ASC, appleSongLibrary = true).map { it.videoId },
        )
    }

    @Test
    fun `apple response updates only its own page opening`() {
        val pages = listOf(
            DetailPage("apple:library:songs", "First", "", null, UiState.Loading, requestId = 1),
            DetailPage("apple:library:songs", "Second", "", null, UiState.Loading, requestId = 2),
        )

        val updated = pages.updateAppleRequest("apple:library:songs", 1) {
            it.copy(songs = UiState.Success(emptyList()))
        }

        assertEquals(UiState.Success(emptyList<Song>()), updated[0].songs)
        assertEquals(UiState.Loading, updated[1].songs)
        assertEquals(pages, pages.updateAppleRequest("apple:library:songs", 99) { it })
    }
}
