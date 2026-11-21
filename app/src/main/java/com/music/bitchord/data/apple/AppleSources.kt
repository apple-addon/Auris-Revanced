package com.music.bitchord.data.apple

import com.music.bitchord.data.model.AppleEditorialElement
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EditorialHero
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.RadioStation
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonManifest
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

internal data class AppleEditorialDetails(
    val heroes: List<EditorialHero>,
    val shelves: List<HomeShelf>,
)

internal data class AppleBrowseContent(
    val shelves: List<HomeShelf>,
    val elements: List<AppleEditorialElement>,
)

internal data class AppleDestinationContent(
    val title: String,
    val elements: List<AppleEditorialElement>,
    val concertHub: com.music.bitchord.data.model.AppleConcertHub?,
)

internal data class AppleShelfPage(
    val items: List<ShelfItem>,
    val nextOffset: Int?,
)

internal data class AppleSongPage(
    val items: List<Song>,
    val nextOffset: Int?,
)

/**
 * The Apple Music catalogue, as the app's own rows.
 *
 * [AppleCatalogue] speaks the addon's wire; this speaks the app's. A song
 * read from Apple becomes a [Song] whose id is packed for the addon that
 * holds the account, so pressing play asks that addon for the audio by the
 * very catalogue id it published — no search, no guessing.
 *
 * The rows carry Apple's own metadata whatever serves the audio later. That
 * is what the *source* axis means: Apple's spelling of a song, Apple's
 * cover, Apple's catalogue id. Where the bytes come from is a separate
 * question, and [SourceResolver] asks it separately.
 */
internal object AppleSources {

    /** A library playlist, opened as a page of its own. */
    const val PAGE_PREFIX = "apple:page:"
    /**
     * A playlist of Apple's catalogue, as opposed to one of the listener's.
     *
     * The two are read from different routes — the catalogue's by id, the
     * library's through the library — and an id from one is not an id in the
     * other. They were once the same prefix, which opened a catalogue
     * playlist as a library one and found nothing.
     */
    const val CPAGE_PREFIX = "apple:cpage:"

    /** An album, opened as a page of its own. */
    const val ALBUM_PREFIX = "apple:album:"

    /** An artist, opened as a page of its own. */
    const val ARTIST_PREFIX = "apple:artist:"

    /** A radio station, opened as a page of its own. */
    const val STATION_PREFIX = "apple:station:"

    /** An Apple editorial curator, including the genre tiles on Search. */
    const val GENRE_PREFIX = "apple:genre:"

    /** An ordinary curator and the complete playlist catalogue it publishes. */
    const val CURATOR_PREFIX = "apple:curator:"


    /**
     * An album in the listener's own library.
     *
     * A library album is not a catalogue album: it is the listener's own
     * copy, and Apple knows it by a library id rather than a catalogue one.
     * It gets its own prefix so the two are never confused — opening a
     * library album has to ask the library, not the catalogue.
     */
    const val LIB_ALBUM_PREFIX = "apple:libalbum:"

    /**
     * The installed Apple Music manifest, as the two things the app needs
     * from it: where to ask, and which source its ids belong to.
     */
    data class Handle(
        /** The addon's own id in the source registry. */
        val configId: String,
        /** Where the addon lives. */
        val baseUrl: String,
        /** What the manifest says it publishes. */
        val manifest: AddonManifest,
    )

    /** The installed Apple Music manifest, or null when there is none. */
    suspend fun handle(): Handle? {
        val baseUrl = AppleManifestLocator.baseUrl() ?: return null
        val config = SourceRegistry.configs.value.firstOrNull { it.baseUrl == baseUrl } ?: return null
        val manifest = AppleManifestLocator.manifest()?.second ?: return null
        return Handle(configId = config.id, baseUrl = baseUrl, manifest = manifest)
    }

    /**
     * Apple's home, as the shelves this app draws.
     *
     * Apple's own order is kept. It is the order Apple's app draws, and the
     * order it draws it in is a large part of what it looks like.
     */
    suspend fun home(handle: Handle): Result<List<HomeShelf>> =
        AppleCatalogue.home(handle.baseUrl).map { home ->
            home.shelves.mapNotNull { shelf ->
                val items = shelf.items.mapNotNull { item -> item.shelfItem(handle.configId) }
                if (items.isEmpty()) null
                else HomeShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle,
                    items = items,
                    layout = shelf.layout,
                )
            }
        }

    /**
     * One library collection — playlists, albums or songs — as rows.
     */
    suspend fun library(
        handle: Handle,
        collection: String,
        offset: Int = 0,
        limit: Int = 50,
    ): Result<List<AppleItem>> =
        AppleCatalogue.library(handle.baseUrl, collection, offset, limit).map { it.tracks }

    /**
     * The tracks of one library collection.
     */
    suspend fun libraryTracks(
        handle: Handle,
        collection: String,
        collectionId: String,
    ): Result<List<AppleItem>> =
        AppleCatalogue.libraryTracksAll(handle.baseUrl, collection, collectionId)

    /**
     * Apple's album, playlist or artist page, as rows.
     */
    suspend fun catalogTracks(
        handle: Handle,
        kind: String,
        id: String,
    ): Result<List<AppleItem>> =
        AppleCatalogue.catalogTracks(handle.baseUrl, kind, id)

    /**
     * Apple's own search, as this app's rows.
     *
     * Apple's songs are playable rows: the id published is the id the
     * addon streams. Apple's albums, playlists and artists are pages.
     */
    suspend fun search(
        handle: Handle,
        query: String,
        types: String = "songs,albums,playlists,artists",
        limit: Int = 25,
        offset: Int = 0,
    ): Result<List<SearchResult>> =
        AppleCatalogue.search(handle.baseUrl, query, types, limit, offset).map { response ->
            buildList {
                response.tracks.forEach { item ->
                    add(SearchResult.Track(item.song(handle.configId)))
                }
                response.albums.forEach { item ->
                    add(SearchResult.Browse(item.browseItem()))
                }
                response.playlists.forEach { item ->
                    add(SearchResult.Browse(item.browseItem()))
                }
                response.artists.forEach { item ->
                    add(SearchResult.Browse(item.browseItem()))
                }
            }
        }

    /**
     * Apple's stations: the listener's own, and the ones Apple builds.
     */
    suspend fun stations(handle: Handle): Result<List<AppleStation>> =
        AppleCatalogue.stations(handle.baseUrl)

    /** Apple's live stations — continuous streams rather than song queues. */
    suspend fun liveStations(handle: Handle): Result<List<AppleStation>> =
        AppleCatalogue.liveStations(handle.baseUrl)

    /** Apple's station search. */
    suspend fun searchStations(
        handle: Handle,
        query: String,
    ): Result<List<AppleStation>> =
        AppleCatalogue.searchStations(handle.baseUrl, query)

    /** The tracks of one station — what plays after the seed. */
    suspend fun stationTracks(
        handle: Handle,
        stationId: String,
    ): Result<List<AppleItem>> =
        AppleCatalogue.stationTracks(handle.baseUrl, stationId)

    /**
     * Apple's Radio page, as the sections of rows it draws.
     *
     * A station plays from here; a playlist opens as a page of its own, the
     * way it does in Apple's own app.
     */
    suspend fun radioShelves(handle: Handle): Result<List<com.music.bitchord.data.model.AppleRadioShelf>> =
        AppleCatalogue.radioSections(handle.baseUrl).map { shelves ->
            shelves.map { shelf ->
                com.music.bitchord.data.model.AppleRadioShelf(
                    title = shelf.title,
                    items = shelf.items.map { row -> row.station() },
                )
            }
        }

    /** Apple's ordered Radio grouping, including videos, links and empty rows. */
    suspend fun radioElements(
        handle: Handle,
        refresh: Boolean = false,
    ): Result<List<AppleEditorialElement>> =
        AppleCatalogue.editorialPage(handle.baseUrl, "radio", refresh).map { page ->
            page.orderedElements(handle)
        }

    /**
     * The stations other broadcasters run, as the rows the Radio page draws.
     *
     * They are Apple's own page's "International Broadcasters" row: live
     * streams from other people, with the broadcaster's own name under the
     * station's.
     */
    suspend fun internationalStations(handle: Handle, limit: Int = 12): Result<List<RadioStation>> =
        AppleCatalogue.internationalStations(handle.baseUrl, limit).map { rows ->
            rows.map { it.station() }
        }

    /**
     * Apple's catalogue, as the New page browses it.
     *
     * Home is the listener's own things — what was played, what was made for
     * them. New is the catalogue itself: Apple's own playlists and albums,
     * its charts, its sessions and its mixes, and the directory of what else
     * there is to browse.
     *
     * The rows come from Apple's own catalogue, so a row here is a row of
     * Apple's, opened the way Apple's own page opens it.
     */
    /** New's complete ordered page, plus the flat shelves older screens use. */
    suspend fun browseContent(
        handle: Handle,
        refresh: Boolean = false,
    ): Result<AppleBrowseContent> =
        AppleCatalogue.editorialPage(handle.baseUrl, "new", refresh).map { editorial ->
            AppleBrowseContent(
                shelves = editorial.shelves.mapNotNull { it.toHomeShelf(handle) },
                elements = editorial.orderedElements(handle),
            )
        }

    /**
     * A genre's own page — every section Apple draws on it.
     *
     * A tile is an apple-curator and the curator names the grouping that is
     * the page, so the addon resolves one from the other. The rows are the
     * vendor's own: its playlists, its new releases, its essential albums.
     */
    suspend fun genrePage(handle: Handle, curatorId: String): Result<AppleEditorialDetails> =
        AppleCatalogue.genrePage(handle.baseUrl, curatorId).map { it.toDetails(handle) }

    /** An ordinary curator's page — every playlist Apple publishes for it. */
    suspend fun curatorPage(handle: Handle, curatorId: String): Result<AppleEditorialDetails> =
        AppleCatalogue.curatorPage(handle.baseUrl, curatorId).map { it.toDetails(handle) }

    private fun AppleEditorialPage.toDetails(handle: Handle): AppleEditorialDetails =
        AppleEditorialDetails(
            heroes = heroes.mapNotNull { it.toEditorialHero(handle) },
            shelves = shelves.mapNotNull { it.toHomeShelf(handle) },
        )

    private fun AppleEditorialPage.orderedElements(handle: Handle): List<AppleEditorialElement> {
        if (elements.isNotEmpty()) return elements.map { it.toEditorialElement(handle) }
        // Older addons expose separate heroes and flat shelves. Preserve that
        // contract without discarding feature cards or inventing directories.
        return buildList {
            val featureItems = heroes.mapNotNull { it.toShelfItem(handle) }
            if (featureItems.isNotEmpty()) {
                add(
                    AppleEditorialElement(
                        id = "legacy-heroes",
                        kind = "316",
                        displayStyle = "compact",
                        title = "",
                        items = featureItems,
                    ),
                )
            }
            shelves.forEachIndexed { index, shelf -> add(shelf.toEditorialElement(handle, index)) }
        }
    }

    /** Opens one of Apple's typed More to Explore destinations. */
    suspend fun editorialDestination(
        handle: Handle,
        destination: com.music.bitchord.data.model.AppleEditorialDestination,
    ): Result<AppleDestinationContent> =
        AppleCatalogue.editorialDestination(handle.baseUrl, destination).map { page ->
            AppleDestinationContent(
                title = page.title,
                elements = page.orderedElements(handle),
                concertHub = page.concertHub?.toModel(
                    locationRequired = page.locationRequired,
                    geoHashLocation = page.geoHashLocation,
                ),
            )
        }

    /** Apple's search-page genres; empty on an addon without the route. */
    suspend fun genres(handle: Handle): Result<List<AppleGenre>> =
        AppleCatalogue.genres(handle.baseUrl)

    /**
     * An artist's albums as the shelves its page draws, titled by the caller
     * because the heading is localised and this layer has no resources.
     */
    suspend fun artistShelves(
        handle: Handle,
        artistId: String,
        albumsTitle: String,
    ): Result<List<HomeShelf>> =
        AppleCatalogue.artistAlbums(handle.baseUrl, artistId).map { items ->
            val rows = items.mapNotNull { it.shelfItem(handle.configId) }
            if (rows.isEmpty()) {
                emptyList()
            } else {
                listOf(HomeShelf(title = albumsTitle, subtitle = "", items = rows))
            }
        }

    /** One page of the listener's playlists. Library ids open library tracks. */
    suspend fun libraryPlaylistPage(
        handle: Handle,
        offset: Int = 0,
        limit: Int = 100,
    ): Result<AppleShelfPage> = libraryShelfPage(handle, "playlists", offset, limit) {
        it.libraryPlaylistRow()
    }

    /** One page of the listener's albums. */
    suspend fun libraryAlbumPage(
        handle: Handle,
        offset: Int = 0,
        limit: Int = 100,
    ): Result<AppleShelfPage> = libraryShelfPage(handle, "albums", offset, limit) {
        it.libraryAlbumRow()
    }

    /** One page of artists, including artists whose catalogue id is unresolved. */
    suspend fun libraryArtistPage(
        handle: Handle,
        offset: Int = 0,
        limit: Int = 100,
    ): Result<AppleShelfPage> = libraryShelfPage(handle, "artists", offset, limit) {
        it.libraryArtistRow()
    }

    /** One page of songs, published immediately rather than after a full walk. */
    suspend fun librarySongPage(
        handle: Handle,
        offset: Int = 0,
        limit: Int = 100,
    ): Result<AppleSongPage> = AppleCatalogue.library(handle.baseUrl, "songs", offset, limit).map { page ->
        AppleSongPage(
            items = page.tracks.mapNotNull { item ->
                val id = item.catalogId?.takeIf { it.isNotBlank() } ?: item.id
                if (id.isBlank() || item.title.isBlank()) null else item.song(handle.configId)
            },
            nextOffset = page.followingOffset(offset),
        )
    }

    private suspend fun libraryShelfPage(
        handle: Handle,
        collection: String,
        offset: Int,
        limit: Int,
        mapper: (AppleItem) -> ShelfItem?,
    ): Result<AppleShelfPage> =
        AppleCatalogue.library(handle.baseUrl, collection, offset, limit).map { page ->
            AppleShelfPage(
                items = page.tracks.mapNotNull(mapper),
                nextOffset = page.followingOffset(offset),
            )
        }

    // ── Rows ──────────────────────────────────────────────────────────────
}

/** The next real page, or null at a terminal, empty, or non-advancing answer. */
internal fun AppleLibraryPage.followingOffset(requestedOffset: Int): Int? {
    if (!hasMore || tracks.isEmpty()) return null
    val next = nextOffset ?: (requestedOffset + tracks.size)
    return next.takeIf { it > requestedOffset }
}

internal fun AppleItem.libraryPlaylistRow(): ShelfItem? =
    libraryRow(AppleSources.PAGE_PREFIX + id)

internal fun AppleItem.libraryAlbumRow(): ShelfItem? =
    libraryRow(AppleSources.LIB_ALBUM_PREFIX + id)

/**
 * A library artist is not a catalogue artist. Without a resolved catalogue id
 * the row remains visible but has no destination, rather than a fake route
 * that would send its library id to the catalogue.
 */
internal fun AppleItem.libraryArtistRow(): ShelfItem? = libraryRow(
    catalogId?.takeIf { it.isNotBlank() }?.let { AppleSources.ARTIST_PREFIX + it },
    includeCredit = false,
)

private fun AppleItem.libraryRow(
    destination: String?,
    includeCredit: Boolean = true,
): ShelfItem? {
    if (title.isBlank()) return null
    return ShelfItem(
        title = title,
        subtitle = if (includeCredit) artist else "",
        thumbnailUrl = artworkURL,
        videoId = null,
        browseId = destination,
        dateAdded = dateAdded,
    )
}

/** Apple's mixed Recently Added order; undated rows keep their upstream order last. */
internal fun appleRecentlyAdded(
    playlists: List<ShelfItem>,
    albums: List<ShelfItem>,
): List<ShelfItem> = (playlists + albums).sortedByDescending { it.dateAdded.orEmpty() }

/** The song as this app's player knows it. */
internal fun AppleItem.song(configId: String): Song = Song(
    // A library id identifies the library row, not the recording's audio.
    videoId = SourceRegistry.trackKey(configId, catalogId?.takeIf { it.isNotBlank() } ?: id),
    title = title,
    artist = artist,
    thumbnailUrl = artworkURL,
    durationText = runtimeMs.takeIf { it > 0 }?.let(::durationText),
    albumName = album.takeIf { it.isNotBlank() },
    // The recording's identity across catalogues, for a cross-source match.
    isrc = isrc?.trim()?.takeIf { it.isNotEmpty() },
    sourceQuality = audioQuality,
    // What the player needs to know to offer the rendition switch: whether
    // Apple holds an Atmos mix of this recording, and whether it holds a
    // Hi-Res master.
    atmosAvailable = hasAtmos,
    hiResAvailable = hasHiRes,
    catalogueDateAddedMs = appleDateAddedMillis(dateAdded),
)

internal fun appleDateAddedMillis(value: String?): Long? = value?.let { timestamp ->
    runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()
}

/** The card this app draws for the row. */
internal fun AppleItem.shelfItem(
    configId: String,
    albumBrowsePrefix: String = AppleSources.ALBUM_PREFIX,
): ShelfItem? {
    if (title.isBlank()) return null
    val item = when (type.lowercase()) {
        "song" -> ShelfItem(
            title = title,
            subtitle = artist,
            thumbnailUrl = artworkURL,
            videoId = SourceRegistry.trackKey(configId, id),
            browseId = null,
        )
        "album" -> ShelfItem(
            title = title,
            subtitle = artist,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = albumBrowsePrefix + id,
        )
        "external" -> ShelfItem(
            title = title,
            subtitle = artist,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = null,
            externalUrl = url?.takeIf { it.startsWith("https://music.apple.com/") },
        )
        else -> ShelfItem(
            title = title,
            subtitle = artist.ifBlank { album },
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = browseIdOf(),
        )
    }
    return item.copy(
        heroArtworkUrl = heroArtworkURL,
        badge = badge,
        description = description,
        stationId = id.takeIf { type.equals("station", true) || type.equals("stations", true) },
        isLive = isLive,
        editorialType = type,
    )
}

/** The page this row opens, when it is a page and not a track. */
internal fun AppleItem.browseIdOf(): String? = when (type.lowercase()) {
    "album" -> AppleSources.ALBUM_PREFIX + id
    "playlist" -> AppleSources.CPAGE_PREFIX + id
    "artist" -> AppleSources.ARTIST_PREFIX + id
    "station" -> AppleSources.STATION_PREFIX + id
    else -> null
}

/**
 * One lockup of an editorial shelf, as the card this app draws.
 *
 * A song lockup carries the catalogue's own key, so tapping it plays the
 * recording; the rest carry the page their kind opens.
 */
internal fun AppleEditorialItem.toShelfItem(handle: AppleSources.Handle): ShelfItem? {
    if (name.isBlank()) return null
    val item = when (type.lowercase()) {
        "song", "songs" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = SourceRegistry.trackKey(handle.configId, id),
            browseId = null,
        )
        "album", "albums" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = AppleSources.ALBUM_PREFIX + id,
        )
        "playlist", "playlists" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = AppleSources.CPAGE_PREFIX + id,
        )
        "station", "stations" -> ShelfItem(
            title = name,
            subtitle = artistName.ifBlank { provider.orEmpty() },
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = AppleSources.STATION_PREFIX + id,
            stationId = id,
        )
        "artist", "artists" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = AppleSources.ARTIST_PREFIX + id,
        )
        "curator", "curators" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = AppleSources.CURATOR_PREFIX + id,
        )
        "apple-curator", "apple-curators" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = AppleSources.GENRE_PREFIX + id,
        )
        "music-video", "music-videos",
        "uploaded-video", "uploaded-videos",
        "music-movie", "music-movies" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = null,
            // Open Apple's url/postUrl destination. With neither, the lockup
            // stays visible but deliberately has no action.
            externalUrl = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        )
        "external" -> ShelfItem(
            title = name,
            subtitle = artistName,
            thumbnailUrl = artworkURL,
            videoId = null,
            browseId = null,
            externalUrl = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        )
        else -> null
    }
    return item?.copy(
        heroArtworkUrl = heroArtworkURL,
        badge = badge,
        description = description,
        isLive = isLive,
        editorialType = type,
        heroKind = heroKind,
        designTag = designTag,
        footerTitle = footerTitle,
        footerArtworkUrl = footerArtworkURL,
    )
}

/** Apple's feature-card metadata stays attached to its real destination. */
internal fun AppleEditorialItem.toEditorialHero(handle: AppleSources.Handle): EditorialHero? =
    toShelfItem(handle)?.let { item ->
        EditorialHero(item = item, badge = badge, description = description)
    }

/** One editorial shelf as a row of the page, or null when it holds nothing. */
internal fun AppleEditorialShelf.toHomeShelf(handle: AppleSources.Handle): HomeShelf? {
    val cards = items.mapNotNull { it.toShelfItem(handle) }
    if (cards.isEmpty()) return null
    return HomeShelf(title = title, subtitle = "", items = cards)
}

internal fun AppleEditorialShelf.toEditorialElement(
    handle: AppleSources.Handle,
    legacyIndex: Int = 0,
): AppleEditorialElement = AppleEditorialElement(
    id = id.ifBlank { "legacy-$legacyIndex-$title" },
    kind = kind.ifBlank { if (resourceTypes.any { it.equals("songs", true) }) "327" else "326" },
    displayStyle = displayStyle,
    title = title,
    items = items.mapNotNull { it.toShelfItem(handle) },
    links = links.mapNotNull { link ->
        if (link.label.isBlank() ||
            !(link.url.startsWith("https://") || link.url.startsWith("http://"))
        ) {
            null
        } else {
            com.music.bitchord.data.model.AppleEditorialLink(
                label = link.label,
                url = link.url,
                target = link.target,
                destination = link.destination?.toModel(),
            )
        }
    },
    itemIds = itemIds,
    destination = destination?.toModel(),
)

internal fun AppleDestination.toModel(): com.music.bitchord.data.model.AppleEditorialDestination =
    com.music.bitchord.data.model.AppleEditorialDestination(
        kind = kind,
        id = id,
        popId = popId,
        geo = geo,
        route = route,
    )

private fun AppleConcertHubWire.toModel(
    locationRequired: Boolean,
    geoHashLocation: String?,
): com.music.bitchord.data.model.AppleConcertHub =
    com.music.bitchord.data.model.AppleConcertHub(
        locationRequired = locationRequired,
        geoHashLocation = geoHashLocation,
        containers = containers.map { container ->
            com.music.bitchord.data.model.AppleConcertContainer(
                id = container.id,
                title = container.title,
                href = container.href,
                events = container.data.mapNotNull { event ->
                    val attrs = event.attributes
                    if (event.id.isBlank() || attrs.name.isBlank()) return@mapNotNull null
                    val artist = event.relationships.artists.data.firstOrNull()?.attributes
                    val venue = event.relationships.venues.data.firstOrNull()?.attributes
                    val ticket = attrs.tickets.firstOrNull {
                        it.url.startsWith("https://") || it.url.startsWith("http://")
                    }
                    com.music.bitchord.data.model.AppleConcertEvent(
                        id = event.id,
                        name = attrs.name,
                        startIso = attrs.startIso,
                        endIso = attrs.endIso,
                        timezone = attrs.timezone,
                        artistName = artist?.name.orEmpty(),
                        artworkUrl = artist?.artwork?.url?.takeIf { it.isNotBlank() }
                            ?: artist?.hero?.url?.takeIf { it.isNotBlank() },
                        venueName = venue?.name.orEmpty(),
                        address = venue?.structuredAddress.displayAddress(),
                        ticketVendor = ticket?.vendor.orEmpty(),
                        ticketUrl = ticket?.url,
                    )
                },
            )
        },
    )

private fun kotlinx.serialization.json.JsonObject?.displayAddress(): String {
    if (this == null) return ""
    return entries.mapNotNull { (key, value) ->
        if (key.contains("latitude", true) || key.contains("longitude", true)) {
            null
        } else {
            (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        }
    }.joinToString(", ")
}

/** The page this row opens, as the model the detail stack holds. */
internal fun AppleItem.browseItem(): BrowseItem = BrowseItem(
    browseId = browseIdOf() ?: "",
    title = title,
    subtitle = artist.ifBlank { album },
    thumbnailUrl = artworkURL,
    type = when (type.lowercase()) {
        "album" -> BrowseType.ALBUM
        "playlist" -> BrowseType.PLAYLIST
        "artist" -> BrowseType.ARTIST
        else -> BrowseType.OTHER
    },
)

/** A runtime as a display string, the way the rows show it. */
internal fun durationText(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

    /**
     * The station, as the radio page draws it.
 *
 * A live station carries Apple's own address rather than audio: its stream
 * is Widevine-protected and only Apple's player plays it, so the page opens
 * Apple's page for it and says so with the LIVE mark.
 */
internal fun AppleStation.station(): RadioStation = RadioStation(
    id = id,
    name = name,
    subtitle = subtitle,
    artworkUrl = artworkURL,
    isLive = isLive == true,
    deepLink = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
    provider = provider,
)

/** One row of Apple's Radio page, as the page draws it. */
internal fun AppleRadioRow.station(): com.music.bitchord.data.model.AppleRadioRow =
    com.music.bitchord.data.model.AppleRadioRow(
        id = id,
        name = name,
        kind = kind,
        isLive = isLive,
        subtitle = subtitle,
        artworkUrl = artworkURL,
        provider = provider,
        // A playlist opens as a page of its own; a station plays from here.
        browseId = when (kind.lowercase()) {
            "playlists" -> AppleSources.PAGE_PREFIX + id
            "albums" -> AppleSources.ALBUM_PREFIX + id
            "artists" -> AppleSources.ARTIST_PREFIX + id
            else -> null
        },
    )

/**
 * A live station, as a row this app can play.
 *
 * The row carries the stream itself: a live station is one continuous
 * playlist, not a song to be looked up, and its audio is Widevine-protected,
 * so the device needs the playlist and somewhere to get the license. The
 * license endpoint is the addon's, because the license can only be bought
 * with the listener's own Apple account — which never leaves the server.
 */
internal suspend fun AppleLiveStream.song(
    handle: AppleSources.Handle,
    station: RadioStation,
): Song {
    // The key the station's own playlist publishes, read by the device: the
    // stream rotates its key as it goes, so the key the server would read
    // itself may not be the one this device is playing. The license request
    // therefore carries the key with it.
    val key = mediaURL?.let { AppleLiveKeys.read(it) }
    return song(handle, station, key)
}

private fun AppleLiveStream.song(
    handle: AppleSources.Handle,
    station: RadioStation,
    key: AppleLiveKey?,
): Song = Song(
    videoId = SourceRegistry.trackKey(handle.configId, station.id),
    title = station.name,
    artist = station.subtitle?.takeIf { it.isNotBlank() } ?: "Apple Music",
    thumbnailUrl = station.artworkUrl,
    durationText = null,
    radioName = station.name,
    playbackSource = station.name,
    playbackSourceType = PlaybackSourceType.RADIO,
    // The playlist comes from the addon, cleaned: the key the stream's own
    // playlist names is not the key the audio is encrypted under, and the
    // addon publishes the right one.
    liveStreamUrl = playlistUrl(handle.baseUrl, station.id),
    liveLicenseUrl = licenseUrl(handle.baseUrl, station.id, key),
)

/** Where the device asks the addon for a live station's playlist. */
internal fun playlistUrl(baseUrl: String, stationId: String): String =
    baseUrl.trimEnd('/') + "/radio/playlist/" +
        java.net.URLEncoder.encode(stationId, "UTF-8") + "/master.m3u8"

/** Where the device asks the addon for a live stream's license. */
internal fun licenseUrl(
    baseUrl: String,
    stationId: String,
    key: AppleLiveKey? = null,
): String {
    val url = StringBuilder(baseUrl.trimEnd('/'))
    url.append("/radio/license?station=")
    url.append(java.net.URLEncoder.encode(stationId, "UTF-8"))
    if (key != null) {
        // The key the device is playing, named the way the playlist names it,
        // so the server asks Apple about this key and not about another one.
        url.append("&pssh=")
        url.append(java.net.URLEncoder.encode(key.widevineUri, "UTF-8"))
        if (!key.skd.isNullOrBlank()) {
            url.append("&skd=")
            url.append(java.net.URLEncoder.encode(key.skd, "UTF-8"))
        }
    }
    return url.toString()
}
