package com.music.bitchord.data.apple

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The Apple Music catalogue, as the app reads it.
 *
 * Apple's catalogue is not read from inside the app. It is read from an
 * addon — an installed Apple Music manifest — which holds the listener's
 * account, the developer token and the storefront, and which republishes
 * Apple's own catalogue in the shape this app reads.
 *
 * So the first thing this class does is find that addon. Nothing else is
 * asked of the manifest, and an addon that is not Apple's is never asked at
 * all: a listener with no Apple Music manifest installed has no Apple
 * catalogue, and the app says so rather than pretending.
 *
 * Every answer here is a [Result]. A missing manifest, a signed-out account
 * and a rate-limited Apple are three different failures, and the screens
 * above say different things about each of them.
 */
object AppleCatalogue {

    private const val TAG = "AppleCatalogue"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * The installed Apple Music manifest, if there is one.
     *
     * Found by reading each configured addon's manifest and looking at what
     * it says, not by looking at its name or address. The manifest is cached
     * briefly because this is asked on every screen that draws Apple content,
     * and a manifest does not change often.
     */
    @Volatile
    private var cachedManifest: Pair<String, AddonManifest>? = null

    @Volatile
    private var cachedManifestAt: Long = 0L

    private const val MANIFEST_TTL_MS = 60_000L

    /**
     * The Apple Music manifest among [manifests], if one is installed.
     *
     * The manifests are given as the addon's own address and the manifest it
     * published, so this never makes a request of its own: the caller has
     * already read them.
     */
    fun find(manifests: List<Pair<String, AddonManifest>>): Pair<String, AddonManifest>? {
        val now = System.currentTimeMillis()
        cachedManifest?.let { (base, manifest) ->
            if (now - cachedManifestAt < MANIFEST_TTL_MS) {
                return manifests.firstOrNull { it.first == base }
            }
        }
        val found = manifests.firstOrNull { (_, manifest) ->
            AppleManifest.detect(manifest)
        }
        cachedManifest = found
        cachedManifestAt = now
        return found
    }

    /** Whether an Apple Music manifest is installed, by what it published. */
    fun isInstalled(manifests: List<Pair<String, AddonManifest>>): Boolean =
        find(manifests) != null

    /**
     * The addon's Apple home page: Apple's own shelves, in Apple's order.
     */
    suspend fun home(baseUrl: String, limit: Int = 40): Result<AppleHome> =
        get(baseUrl, listOf("home"), mapOf("limit" to limit.toString()), AppleHome.serializer())

    /**
     * Apple's recommendations for the listener — the rows Home and Autoplay
     * are built from when the recommendation axis points at Apple.
     */
    suspend fun recommendations(baseUrl: String, limit: Int = 40): Result<List<AppleItem>> =
        get(
            baseUrl,
            listOf("recommendations"),
            mapOf("limit" to limit.toString()),
            AppleRecommendationResponse.serializer(),
        ).map { it.tracks }

    /**
     * The rows Apple plays after a seed track — the station queue.
     */
    suspend fun stationTracks(
        baseUrl: String,
        stationId: String,
        limit: Int = 20,
    ): Result<List<AppleItem>> =
        // A catalogue station — the ones on Top Stations and Stations by
        // Genre — is not one of the listener's own. `/me/stations/next-tracks`
        // only answers for a station the account started, so it came back
        // empty for every ra.… tile and the tap did nothing. The addon's
        // station route reads the catalogue station by id.
        get(
            baseUrl,
            listOf("radio", "station", stationId, "tracks"),
            mapOf("limit" to limit.toString()),
            AppleRecommendationResponse.serializer(),
        ).map { it.tracks }

    /**
     * One page of the listener's Apple Music library.
     *
     * [collection] is one of `playlists`, `albums` or `songs` — the names the
     * manifest publishes.
     */
    suspend fun library(
        baseUrl: String,
        collection: String,
        offset: Int = 0,
        limit: Int = 50,
    ): Result<AppleLibraryPage> =
        get(
            baseUrl,
            listOf("library", collection),
            mapOf("offset" to offset.toString(), "limit" to limit.toString()),
            AppleLibraryPage.serializer(),
        )

    /**
     * Every page of one library collection, not just the first.
     *
     * A library page is capped by the service (100 rows); a collection with
     * more than that used to end at the cap with no way to reach the rest.
     * The walk stops when the service says there is no more; a page that
     * does not advance is the only other stop.
     */
    suspend fun libraryAll(
        baseUrl: String,
        collection: String,
        pageSize: Int = 100,
    ): Result<List<AppleItem>> {
        val out = mutableListOf<AppleItem>()
        var offset = 0
        // Until the service says there is no more. The only stop besides that
        // is a page that does not advance — a server bug would otherwise spin
        // here; a real library always moves forward.
        while (true) {
            val page = library(baseUrl, collection, offset, pageSize)
                .getOrElse { failure -> return Result.failure(failure) }
            out += page.tracks
            if (!page.hasMore || page.tracks.isEmpty()) return Result.success(out)
            val next = page.nextOffset ?: (offset + page.tracks.size)
            if (next <= offset) return Result.success(out)
            offset = next
        }
    }

    /**
     * The rows of one library collection — one playlist, one album, or the
     * songs of a page.
     *
     * Paged like any other collection: a long playlist is walked to its end
     * by [libraryTracksAll], not cut at the first page the route returns.
     */
    suspend fun libraryTracks(
        baseUrl: String,
        collection: String,
        collectionId: String,
        offset: Int = 0,
        limit: Int = 100,
    ): Result<AppleLibraryPage> =
        get(
            baseUrl,
            listOf("library", collection, collectionId, "tracks"),
            mapOf("offset" to offset.toString(), "limit" to limit.toString()),
            AppleLibraryPage.serializer(),
        )

    /** Every track of one library playlist or album, not just the first page. */
    suspend fun libraryTracksAll(
        baseUrl: String,
        collection: String,
        collectionId: String,
        pageSize: Int = 100,
    ): Result<List<AppleItem>> {
        val out = mutableListOf<AppleItem>()
        var offset = 0
        while (true) {
            val page = libraryTracks(baseUrl, collection, collectionId, offset, pageSize)
                .getOrElse { failure -> return Result.failure(failure) }
            out += page.tracks
            if (!page.hasMore || page.tracks.isEmpty()) return Result.success(out)
            val next = page.nextOffset ?: (offset + page.tracks.size)
            if (next <= offset) return Result.success(out)
            offset = next
        }
    }

    /**
     * Apple's album, playlist or artist page, as rows.
     */
    suspend fun catalogTracks(
        baseUrl: String,
        kind: String,
        id: String,
    ): Result<List<AppleItem>> =
        get(
            baseUrl,
            listOf("catalog", kind, id),
            emptyMap(),
            AppleRecommendationResponse.serializer(),
        ).map { it.tracks }

    /**
     * Apple's own search, over the catalogue the listener chose.
     */
    suspend fun search(
        baseUrl: String,
        query: String,
        types: String = "songs,albums,playlists,artists",
        limit: Int = 20,
        offset: Int = 0,
    ): Result<AppleSearchResponse> =
        get(
            baseUrl,
            listOf("search"),
            mapOf(
                "q" to query,
                "types" to types,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
            AppleSearchResponse.serializer(),
        )

    /**
     * Apple's album, playlist and artist rows for one search query.
     */
    suspend fun browse(
        baseUrl: String,
        query: String,
        types: String = "albums,playlists,artists",
        limit: Int = 15,
    ): Result<AppleSearchResponse> =
        get(
            baseUrl,
            listOf("search", "browse"),
            mapOf("q" to query, "types" to types, "limit" to limit.toString()),
            AppleSearchResponse.serializer(),
        )

    /** Apple's catalogue genres: the wall its own search page opens with. */
    suspend fun genres(baseUrl: String, limit: Int = 40): Result<List<AppleGenre>> =
        get(
            baseUrl,
            listOf("search", "genres"),
            mapOf("limit" to limit.toString()),
            AppleGenreList.serializer(),
        ).map { it.genres }

    /**
     * The New page's rows and the directory beneath them, as the addon
     * defines them — a row Apple adds is a server edit, not a new build.
     */
    suspend fun browsePage(baseUrl: String): Result<AppleBrowseSectionList> =
        get(
            baseUrl,
            listOf("browse", "sections"),
            emptyMap(),
            AppleBrowseSectionList.serializer(),
        )

    /**
     * One of Apple's own pages, as the shelves it is actually made of.
     *
     * The rows come from Apple's editorial grouping rather than from a search
     * for a label: a shelf is the shelf the vendor draws, and it refreshes
     * with the page instead of with this build.
     */
    suspend fun editorialPage(
        baseUrl: String,
        page: String = "new",
        refresh: Boolean = false,
    ): Result<AppleEditorialPage> =
        get(
            baseUrl,
            listOf("browse", "page"),
            // A nonce bypasses the HTTP cache. refresh=1 also bypasses the
            // addon's short editorial cache for an explicit user refresh.
            buildMap {
                put("page", page)
                put("n", System.currentTimeMillis().toString())
                if (refresh) put("refresh", "1")
            },
            AppleEditorialPage.serializer(),
        )

    /** Opens a typed room, grouping, chart or location-based concert hub. */
    suspend fun editorialDestination(
        baseUrl: String,
        destination: com.music.bitchord.data.model.AppleEditorialDestination,
    ): Result<AppleEditorialPage> =
        get(
            baseUrl,
            listOf("browse", "destination"),
            buildMap {
                put("kind", destination.kind)
                if (destination.id.isNotBlank()) put("id", destination.id)
                if (destination.popId.isNotBlank()) put("popId", destination.popId)
                if (destination.geo.isNotBlank()) put("geo", destination.geo)
            },
            AppleEditorialPage.serializer(),
        )

    /**
     * A genre's own page, as the sections Apple draws on it.
     *
     * The tile is an apple-curator; the addon resolves the grouping behind it
     * and republishes that page's shelves — playlists, new releases, essential
     * albums, artists — without capping any of them.
     */
    suspend fun genrePage(baseUrl: String, curatorId: String): Result<AppleEditorialPage> =
        get(
            baseUrl,
            listOf("browse", "genre"),
            mapOf("id" to curatorId, "n" to System.currentTimeMillis().toString()),
            AppleEditorialPage.serializer(),
        )

    /** An ordinary curator's complete playlist catalogue, not a search for its name. */
    suspend fun curatorPage(baseUrl: String, curatorId: String): Result<AppleEditorialPage> =
        get(
            baseUrl,
            listOf("browse", "curator"),
            mapOf("id" to curatorId, "n" to System.currentTimeMillis().toString()),
            AppleEditorialPage.serializer(),
        )

    /** An Apple artist's albums, as the rows the artist page draws. */
    suspend fun artistAlbums(baseUrl: String, artistId: String): Result<List<AppleItem>> =
        get(
            baseUrl,
            listOf("catalog", "artists", artistId, "albums"),
            emptyMap(),
            AppleItemList.serializer(),
        ).map { it.items }

    /** Apple's stations: the listener's own, and the ones Apple builds. */
    suspend fun stations(baseUrl: String): Result<List<AppleStation>> =
        get(baseUrl, listOf("radio", "stations"), emptyMap(), AppleStationList.serializer())
            .map { it.stations }

    /** Apple's live stations — continuous streams rather than song queues. */
    suspend fun liveStations(baseUrl: String, limit: Int = 25): Result<List<AppleStation>> =
        get(
            baseUrl,
            listOf("radio", "live"),
            mapOf("limit" to limit.toString()),
            AppleStationList.serializer(),
        ).map { it.stations }

    /**
     * Apple's own Radio page, as its sections of stations and playlists.
     */
    suspend fun radioSections(baseUrl: String): Result<List<AppleRadioShelf>> =
        get(baseUrl, listOf("radio", "sections"), emptyMap(), AppleRadioShelves.serializer())
            .map { it.shelves }

    /**
     * Creates a playlist of the listener's own.
     *
     * Apple's own playlists are the ones Apple's own app creates, and they
     * are the only ones an Apple row can be filed into: a playlist of another
     * service holds that service's ids.
     */
    suspend fun createPlaylist(baseUrl: String, name: String): Result<AppleWriteResponse> =
        postForm(baseUrl, listOf("library", "playlists"), mapOf("name" to name.trim()))

    /**
     * The stations other broadcasters run, which Apple lists separately.
     *
     * They are live in the same way Apple's own are, and they come from
     * other people — which is what the row under them says.
     */
    suspend fun internationalStations(
        baseUrl: String,
        limit: Int = 12,
    ): Result<List<AppleStation>> =
        get(
            baseUrl,
            listOf("radio", "international"),
            mapOf("limit" to limit.toString()),
            AppleStationList.serializer(),
        ).map { it.stations }

    /** Apple's own station search. */
    suspend fun searchStations(
        baseUrl: String,
        term: String,
        limit: Int = 25,
    ): Result<List<AppleStation>> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return Result.success(emptyList())
        return get(
            baseUrl,
            listOf("radio", "search"),
            mapOf("q" to trimmed, "limit" to limit.toString()),
            AppleStationList.serializer(),
        ).map { it.stations }
    }

    /**
     * One live station's playable stream.
     *
     * The addon reads Apple's own manifest and key identifiers and publishes
     * them here; the license itself stays on the addon, because it has to be
     * bought with the listener's account.
     */
    suspend fun liveStream(baseUrl: String, stationId: String): Result<AppleLiveStream> {
        if (stationId.isBlank()) return Result.failure(AppleException("That is not a station"))
        return get(
            baseUrl,
            listOf("radio", "station", stationId, "live"),
            emptyMap(),
            AppleLiveStreamResponse.serializer(),
        ).mapCatching { response ->
            response.live ?: throw AppleException("That station has no live stream")
        }
    }

    /** The station Apple built for one song, when it has one. */
    suspend fun stationForSong(baseUrl: String, songId: String): Result<AppleStation?> {
        if (songId.isBlank()) return Result.success(null)
        return get(
            baseUrl,
            listOf("radio", "song", songId, "station"),
            emptyMap(),
            AppleStationResponse.serializer(),
        ).map { it.station }
    }

    /**
     * Whether the listener has rated a track as a favourite.
     *
     * The addon answers from Apple's own ratings, which is the only answer
     * that agrees with the Apple Music app on another device.
     */
    suspend fun isFavourite(baseUrl: String, songId: String): Result<Boolean> =
        get(
            baseUrl,
            listOf("library", "ratings", songId),
            emptyMap(),
            AppleRatingResponse.serializer(),
        ).map { it.liked }

    /**
     * Rates a track as a favourite, or takes the rating back.
     *
     * Taking a rating back is what removes the row from Apple's *Favourite
     * Songs*: Apple keeps the favourites playlist and the rating in step, so
     * the rating is the one that is written.
     */
    suspend fun setFavourite(
        baseUrl: String,
        songId: String,
        liked: Boolean,
    ): Result<Boolean> =
        postForm(
            baseUrl,
            listOf("library", "ratings", songId),
            mapOf("liked" to if (liked) "1" else "0"),
        ).map { true }

    /**
     * Removes tracks from Apple's Favourite Songs outright.
     *
     * Used when a track is *disliked* rather than merely unstarred: a
     * dislike in the player means "not this, ever", and Apple's own answer
     * to that is to take the row out of the favourites playlist.
     */
    suspend fun removeFromFavourites(
        baseUrl: String,
        songIds: List<String>,
    ): Result<Int> =
        postForm(
            baseUrl,
            listOf("library", "favorites", "remove"),
            mapOf("ids" to songIds.joinToString(",")),
        ).map { it.removed }

    /** Adds tracks to one Apple Music playlist. */
    suspend fun addToPlaylist(
        baseUrl: String,
        playlistId: String,
        songIds: List<String>,
    ): Result<AppleWriteResponse> =
        postForm(
            baseUrl,
            listOf("library", "playlists", playlistId, "tracks"),
            mapOf("ids" to songIds.joinToString(",")),
        )

    /** Removes tracks from one Apple Music playlist. */
    suspend fun removeFromPlaylist(
        baseUrl: String,
        playlistId: String,
        songIds: List<String>,
    ): Result<AppleWriteResponse> =
        deleteForm(
            baseUrl,
            listOf("library", "playlists", playlistId, "tracks"),
            mapOf("ids" to songIds.joinToString(",")),
        )

    // ── transport ──────────────────────────────────────────────────────────

    private suspend fun <T> get(
        baseUrl: String,
        segments: List<String>,
        params: Map<String, String>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(endpoint(baseUrl, segments, params))
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AppleException("Apple Music request failed (${response.code})")
                }
                json.decodeFromString(serializer, text)
            }
        }.onFailure { failure ->
            if (failure !is kotlinx.coroutines.CancellationException) {
                TrackLog.d(TAG, "GET ${segments.joinToString("/")} failed: ${failure.message}")
            }
        }
    }

    private suspend fun <T> post(
        baseUrl: String,
        segments: List<String>,
        params: Map<String, String>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(endpoint(baseUrl, segments, params))
                .post("".toRequestBody())
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AppleException("Apple Music request failed (${response.code})")
                }
                json.decodeFromString(serializer, text)
            }
        }
    }

    private suspend fun postForm(
        baseUrl: String,
        segments: List<String>,
        fields: Map<String, String>,
    ): Result<AppleWriteResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fields.entries.joinToString("&") { (key, value) ->
                "${key.urlEncode()}=${value.urlEncode()}"
            }
            val request = Request.Builder()
                .url(endpoint(baseUrl, segments, emptyMap()))
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AppleException("Apple Music request failed (${response.code})")
                }
                json.decodeFromString(AppleWriteResponse.serializer(), text)
            }
        }
    }

    private suspend fun deleteForm(
        baseUrl: String,
        segments: List<String>,
        fields: Map<String, String>,
    ): Result<AppleWriteResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fields.entries.joinToString("&") { (key, value) ->
                "${key.urlEncode()}=${value.urlEncode()}"
            }
            val request = Request.Builder()
                .url(endpoint(baseUrl, segments, emptyMap()))
                .delete(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw AppleException("Apple Music request failed (${response.code})")
                }
                json.decodeFromString(AppleWriteResponse.serializer(), text)
            }
        }
    }

    private fun endpoint(
        baseUrl: String,
        segments: List<String>,
        params: Map<String, String>,
    ): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw AppleException("That is not a usable address")
        val builder = base.newBuilder()
        segments.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}

/** A failure that came from the Apple side rather than from the app. */
class AppleException(message: String) : Exception(message)

/** The addon's answer to a recommendations or tracks request. */
@kotlinx.serialization.Serializable
data class AppleRecommendationResponse(
    @kotlinx.serialization.SerialName("tracks") val tracks: List<AppleItem> = emptyList(),
)

/** The addon's answer to a search. */
@kotlinx.serialization.Serializable
data class AppleSearchResponse(
    @kotlinx.serialization.SerialName("tracks") val tracks: List<AppleItem> = emptyList(),
    @kotlinx.serialization.SerialName("albums") val albums: List<AppleItem> = emptyList(),
    @kotlinx.serialization.SerialName("playlists") val playlists: List<AppleItem> = emptyList(),
    @kotlinx.serialization.SerialName("artists") val artists: List<AppleItem> = emptyList(),
    @kotlinx.serialization.SerialName("stations") val stations: List<AppleStation> = emptyList(),
)

/** The addon's answer to a rating read. */
@kotlinx.serialization.Serializable
data class AppleRatingResponse(
    @kotlinx.serialization.SerialName("liked") val liked: Boolean = false,
)

/** The addon's answer to a write. */
@kotlinx.serialization.Serializable
data class AppleWriteResponse(
    @kotlinx.serialization.SerialName("ok") val ok: Boolean = false,
    /** The id of the playlist that was just created, when one was. */
    @kotlinx.serialization.SerialName("id") val id: String? = null,
    @kotlinx.serialization.SerialName("removed") val removed: Int = 0,
    @kotlinx.serialization.SerialName("added") val added: Int = 0,
)

/** One genre tile on Apple's search page. */
@Serializable
data class AppleGenre(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("artworkURL") val artworkURL: String? = null,
)

@Serializable
data class AppleGenreList(
    @SerialName("genres") val genres: List<AppleGenre> = emptyList(),
)

/** One catalogue row of the New page. */
@Serializable
data class AppleBrowseSection(
    @SerialName("title") val title: String = "",
    @SerialName("term") val term: String = "",
    @SerialName("types") val types: String = "playlists",
)

/** One row of the directory at the foot of the New page. */
@Serializable
data class AppleDirectoryEntry(
    @SerialName("label") val label: String = "",
    @SerialName("term") val term: String = "",
)

@Serializable
data class AppleBrowseSectionList(
    @SerialName("sections") val sections: List<AppleBrowseSection> = emptyList(),
    @SerialName("directory") val directory: List<AppleDirectoryEntry> = emptyList(),
)

/** One lockup of an editorial shelf: an album, playlist or station. */
@Serializable
data class AppleEditorialItem(
    @SerialName("id") val id: String = "",
    @SerialName("type") val type: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("artistName") val artistName: String = "",
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("badge") val badge: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("isLive") val isLive: Boolean = false,
    @SerialName("provider") val provider: String? = null,
    @SerialName("brandType") val brandType: String = "",
    @SerialName("heroArtworkURL") val heroArtworkURL: String? = null,
    @SerialName("heroKind") val heroKind: String = "",
    @SerialName("designTag") val designTag: String = "",
    @SerialName("footerTitle") val footerTitle: String = "",
    @SerialName("footerArtworkURL") val footerArtworkURL: String? = null,
)

@Serializable
data class AppleDestination(
    @SerialName("kind") val kind: String = "",
    @SerialName("id") val id: String = "",
    @SerialName("popId") val popId: String = "",
    @SerialName("geo") val geo: String = "",
    @SerialName("route") val route: String = "",
)

@Serializable
data class AppleEditorialLink(
    @SerialName("label") val label: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("target") val target: String = "",
    @SerialName("destination") val destination: AppleDestination? = null,
)

/** One ordered editorial element; older servers populate only title/items. */
@Serializable
data class AppleEditorialShelf(
    @SerialName("id") val id: String = "",
    @SerialName("kind") val kind: String = "",
    @SerialName("displayStyle") val displayStyle: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("resourceTypes") val resourceTypes: List<String> = emptyList(),
    @SerialName("items") val items: List<AppleEditorialItem> = emptyList(),
    @SerialName("links") val links: List<AppleEditorialLink> = emptyList(),
    @SerialName("itemIds") val itemIds: List<String> = emptyList(),
    @SerialName("destination") val destination: AppleDestination? = null,
)

/** A page Apple publishes — New or Radio — as the shelves it is made of. */
@Serializable
data class AppleEditorialPage(
    @SerialName("page") val page: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("destination") val destination: AppleDestination? = null,
    @SerialName("updatedAt") val updatedAt: String = "",
    @SerialName("heroes") val heroes: List<AppleEditorialItem> = emptyList(),
    @SerialName("elements") val elements: List<AppleEditorialShelf> = emptyList(),
    @SerialName("shelves") val shelves: List<AppleEditorialShelf> = emptyList(),
    @SerialName("locationRequired") val locationRequired: Boolean = false,
    @SerialName("geoHashLocation") val geoHashLocation: String? = null,
    @SerialName("concertHub") val concertHub: AppleConcertHubWire? = null,
)

@Serializable
data class AppleConcertHubWire(
    @SerialName("order") val order: List<String> = emptyList(),
    @SerialName("containers") val containers: List<AppleConcertContainerWire> = emptyList(),
)

@Serializable
data class AppleConcertContainerWire(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("href") val href: String? = null,
    @SerialName("data") val data: List<AppleConcertEventWire> = emptyList(),
)

@Serializable
data class AppleConcertEventWire(
    @SerialName("id") val id: String = "",
    @SerialName("attributes") val attributes: Attributes = Attributes(),
    @SerialName("relationships") val relationships: Relationships = Relationships(),
) {
    @Serializable
    data class Attributes(
        @SerialName("name") val name: String = "",
        @SerialName("startISODateTime") val startIso: String = "",
        @SerialName("endISODateTime") val endIso: String = "",
        @SerialName("timezone") val timezone: String = "",
        @SerialName("tickets") val tickets: List<AppleConcertTicket> = emptyList(),
    )

    @Serializable
    data class Relationships(
        @SerialName("artists") val artists: Related = Related(),
        @SerialName("venues") val venues: Related = Related(),
    )

    @Serializable
    data class Related(
        @SerialName("data") val data: List<RelatedItem> = emptyList(),
    )

    @Serializable
    data class RelatedItem(
        @SerialName("attributes") val attributes: RelatedAttributes = RelatedAttributes(),
    )

    @Serializable
    data class RelatedAttributes(
        @SerialName("name") val name: String = "",
        @SerialName("artwork") val artwork: Artwork? = null,
        @SerialName("hero") val hero: Artwork? = null,
        @SerialName("structuredAddress") val structuredAddress: kotlinx.serialization.json.JsonObject? = null,
    )

    @Serializable
    data class Artwork(
        @SerialName("url") val url: String = "",
    )
}

@Serializable
data class AppleConcertTicket(
    @SerialName("vendor") val vendor: String = "",
    @SerialName("url") val url: String = "",
)

/** Rows of one kind, as a catalogue route answers them. */
@Serializable
data class AppleItemList(
    @SerialName("items") val items: List<AppleItem> = emptyList(),
)
