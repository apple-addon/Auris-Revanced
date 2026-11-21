package com.music.bitchord

import com.music.bitchord.data.apple.AppleDestination
import com.music.bitchord.data.apple.AppleEditorialItem
import com.music.bitchord.data.apple.AppleEditorialLink
import com.music.bitchord.data.apple.AppleEditorialShelf
import com.music.bitchord.data.apple.AppleItem
import com.music.bitchord.data.apple.AppleSources
import com.music.bitchord.data.apple.shelfItem
import com.music.bitchord.data.apple.toEditorialElement
import com.music.bitchord.data.apple.toEditorialHero
import com.music.bitchord.data.apple.toHomeShelf
import com.music.bitchord.data.apple.toShelfItem
import com.music.bitchord.data.sources.addon.AddonManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppleEditorialMappingTest {
    private val handle = AppleSources.Handle(
        configId = "apple-test",
        baseUrl = "http://localhost",
        manifest = AddonManifest(),
    )

    @Test
    fun `Apple resource plurals retain every kind of genre shelf`() {
        val shelves = listOf(
            "Playlists" to "playlists",
            "Best New Songs" to "songs",
            "New Releases" to "albums",
            "Stations" to "stations",
            "Artists We Love" to "artists",
        ).map { (title, type) ->
            AppleEditorialShelf(
                title = title,
                resourceTypes = listOf(type),
                items = listOf(AppleEditorialItem(id = "id-$type", type = type, name = title)),
            )
        }

        val mapped = shelves.mapNotNull { it.toHomeShelf(handle) }

        assertEquals(shelves.map { it.title }, mapped.map { it.title })
        assertEquals(AppleSources.CPAGE_PREFIX + "id-playlists", mapped[0].items.single().browseId)
        assertNotNull(mapped[1].items.single().videoId)
        assertNull(mapped[1].items.single().browseId)
        assertEquals(AppleSources.ALBUM_PREFIX + "id-albums", mapped[2].items.single().browseId)
        assertEquals(AppleSources.STATION_PREFIX + "id-stations", mapped[3].items.single().browseId)
        assertEquals(AppleSources.ARTIST_PREFIX + "id-artists", mapped[4].items.single().browseId)
    }

    @Test
    fun `singular resource names remain valid for older addon responses`() {
        listOf(
            "song", "album", "playlist", "station", "artist", "curator",
            "apple-curator", "music-video", "uploaded-video", "music-movie",
        ).forEach { type ->
            assertNotNull(
                type,
                AppleEditorialItem(id = "id", type = type, name = "Name").toShelfItem(handle),
            )
        }
    }

    @Test
    fun `video shelves keep Apple destinations instead of fake song ids`() {
        listOf("music-videos", "uploaded-videos", "music-movies").forEach { type ->
            val url = "https://music.apple.com/in/video/$type/123"
            val item = AppleEditorialItem(
                id = "123",
                type = type,
                name = "Video",
                artistName = "Artist",
                url = url,
            ).toShelfItem(handle)

            assertNotNull(type, item)
            assertNull(item?.videoId)
            assertNull(item?.browseId)
            assertEquals(url, item?.externalUrl)
        }
    }

    @Test
    fun `uploaded video without public URL stays visible but has no fake destination`() {
        val item = AppleEditorialItem(
            id = "123",
            type = "uploaded-videos",
            name = "Apple interview",
            url = "",
        ).toShelfItem(handle)

        assertNotNull(item)
        assertNull(item?.videoId)
        assertNull(item?.browseId)
        assertNull(item?.externalUrl)
    }

    @Test
    fun `curator kinds use their distinct Apple page routes`() {
        val ordinary = AppleEditorialItem(id = "10", type = "curators", name = "Curator")
            .toShelfItem(handle)
        val editorial = AppleEditorialItem(id = "20", type = "apple-curators", name = "Editorial")
            .toShelfItem(handle)

        assertEquals(AppleSources.CURATOR_PREFIX + "10", ordinary?.browseId)
        assertEquals(AppleSources.GENRE_PREFIX + "20", editorial?.browseId)
    }

    @Test
    fun `mixed shelves preserve every item in Apple order`() {
        val types = listOf("songs", "music-videos", "uploaded-videos", "playlists", "curators")
        val shelf = AppleEditorialShelf(
            title = "Mixed",
            resourceTypes = types,
            items = types.mapIndexed { index, type ->
                AppleEditorialItem(id = "$index", type = type, name = "Item $index")
            },
        )

        val mapped = shelf.toHomeShelf(handle)

        assertEquals(types.size, mapped?.items?.size)
        assertEquals(shelf.items.map { it.name }, mapped?.items?.map { it.title })
    }

    @Test
    fun `feature cards retain metadata for every resource kind`() {
        listOf("playlists", "music-videos", "curators", "apple-curators").forEach { type ->
            val hero = AppleEditorialItem(
                id = "id",
                type = type,
                name = "Feature",
                badge = "Apple badge",
                description = "Apple editorial note",
            ).toEditorialHero(handle)

            assertNotNull(type, hero)
            assertEquals("Apple badge", hero?.badge)
            assertEquals("Apple editorial note", hero?.description)
        }
    }

    @Test
    fun `Home stations and editorial destinations retain their real routes`() {
        val station = AppleItem(
            type = "station",
            id = "ra.q-test",
            title = "Love",
            artist = "Radio Station",
            isLive = true,
        ).shelfItem(handle.configId)
        val concert = AppleItem(
            type = "external",
            id = "editorial-id",
            title = "Find Concerts Nearby",
            artist = "Upcoming shows will appear here.",
            url = "https://music.apple.com/concerts?route=set-location",
        ).shelfItem(handle.configId)

        assertEquals(AppleSources.STATION_PREFIX + "ra.q-test", station?.browseId)
        assertEquals("ra.q-test", station?.stationId)
        assertEquals(true, station?.isLive)
        assertEquals("station", station?.editorialType)
        assertNull(station?.videoId)
        assertEquals("https://music.apple.com/concerts?route=set-location", concert?.externalUrl)
        assertNull(concert?.browseId)
        assertNull(concert?.videoId)
    }

    @Test
    fun `Home feature metadata survives wire to card mapping`() {
        val item = AppleItem(
            type = "playlist",
            id = "pl.test",
            title = "Feel Good",
            heroArtworkURL = "https://img/hero.jpg",
            badge = "Mood for You",
            description = "The most uplifting tracks to brighten your day.",
        ).shelfItem(handle.configId)

        assertEquals("https://img/hero.jpg", item?.heroArtworkUrl)
        assertEquals("Mood for You", item?.badge)
        assertEquals("The most uplifting tracks to brighten your day.", item?.description)
    }

    @Test
    fun `ordered elements retain upstream layout links and empty sections`() {
        val directory = AppleEditorialShelf(
            id = "6745339928",
            kind = "391",
            title = "More to Explore",
            destination = AppleDestination(kind = "room", id = "6456176470"),
            links = listOf(
                AppleEditorialLink(
                    label = "Charts",
                    url = "https://music.apple.com/in/charts",
                    target = "charts",
                    destination = AppleDestination(kind = "charts", id = "34", popId = "11"),
                ),
                AppleEditorialLink(label = "Invalid", url = "not-a-url"),
            ),
        ).toEditorialElement(handle)
        val empty = AppleEditorialShelf(
            id = "6738239144",
            kind = "326",
            displayStyle = "compact",
            title = "Local Broadcasters",
        ).toEditorialElement(handle)

        assertEquals("6745339928", directory.id)
        assertEquals("391", directory.kind)
        assertEquals("Charts", directory.links.single().label)
        assertEquals("https://music.apple.com/in/charts", directory.links.single().url)
        assertEquals("charts", directory.links.single().target)
        assertEquals("charts", directory.links.single().destination?.kind)
        assertEquals("34", directory.links.single().destination?.id)
        assertEquals("11", directory.links.single().destination?.popId)
        assertEquals("room", directory.destination?.kind)
        assertEquals("6456176470", directory.destination?.id)
        assertEquals("6738239144", empty.id)
        assertEquals("compact", empty.displayStyle)
        assertEquals(emptyList<Nothing>(), empty.items)
    }

    @Test
    fun `ordered station items retain live and seeded playback identity`() {
        val station = AppleEditorialShelf(
            id = "on-air",
            kind = "488",
            title = "On Air Now",
            items = listOf(
                AppleEditorialItem(
                    id = "ra.978194965",
                    type = "stations",
                    name = "Apple Music 1",
                    provider = "Apple Music",
                    isLive = true,
                ),
            ),
        ).toEditorialElement(handle).items.single()

        assertEquals("ra.978194965", station.stationId)
        assertEquals("Apple Music", station.subtitle)
        assertEquals(true, station.isLive)
        assertEquals(AppleSources.STATION_PREFIX + "ra.978194965", station.browseId)
        assertNull(station.videoId)
    }

    @Test
    fun `mapping never truncates a genre shelf`() {
        val shelf = AppleEditorialShelf(
            title = "Best New Songs",
            resourceTypes = listOf("songs"),
            items = List(37) { index ->
                AppleEditorialItem(id = "$index", type = "songs", name = "Song $index")
            },
        )

        assertEquals(37, shelf.toHomeShelf(handle)?.items?.size)
    }
}
