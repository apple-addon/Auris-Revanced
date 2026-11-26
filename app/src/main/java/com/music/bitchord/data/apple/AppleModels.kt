package com.music.bitchord.data.apple

import com.music.bitchord.data.sources.addon.AddonManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Apple Music catalogue, as the app sees it.
 *
 * Apple's own catalogue is not read from inside the app: it is read from an
 * addon the listener installs, which holds the account and the developer
 * token. So every Apple answer in this package comes from an installed
 * manifest — see [AppleManifest.detect].
 *
 * The shapes here are the addon's, not Apple's: the addon already speaks
 * Apple's own catalogue and republishes it in the shape this app reads.
 */
@Serializable
data class AppleShelf(
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String = "",
    @SerialName("artworkURL") val artworkURL: String? = null,
    /** Apple's presentation for this group: hero, concert, or an ordinary shelf. */
    @SerialName("layout") val layout: String = "",
    @SerialName("items") val items: List<AppleItem> = emptyList(),
)

/**
 * One row in an Apple shelf.
 *
 * [type] is Apple's own word for the row: `song`, `album` or `playlist`. A
 * song row can be played directly; an album and a playlist are pages, opened
 * on a tap.
 */
@Serializable
data class AppleItem(
    @SerialName("type") val type: String = "song",
    @SerialName("id") val id: String = "",
    /**
     * The catalogue id of a library row, when the addon published one.
     *
     * A row in the listener's own library is identified by a library id,
     * which is not the id the catalogue knows it by — and the catalogue id
     * is the one the audio is asked for. Null on a row that came from the
     * catalogue in the first place, where the two are the same.
     */
    @SerialName("catalogId") val catalogId: String? = null,
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    /** Apple's library-added timestamp; never inferred from row position. */
    @SerialName("dateAdded") val dateAdded: String? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("durationMs") val durationMs: Long = 0,
    /**
     * The runtime in seconds, as the addon publishes it.
     *
     * Two shapes reach this app — the addons that publish milliseconds and
     * the ones that publish seconds — and only one of them can be the
     * declared field. Read both, and prefer the declared one.
     */
    @SerialName("duration") val durationSeconds: Double? = null,
    @SerialName("isrc") val isrc: String? = null,
    @SerialName("hasDrm") val hasDrm: Boolean? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("heroArtworkURL") val heroArtworkURL: String? = null,
    @SerialName("badge") val badge: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("isLive") val isLive: Boolean = false,
    /**
     * Whether Apple holds a Dolby Atmos mix of this recording.
     *
     * The player's switch needs to know before the stream is opened, because
     * the cue it draws depends on whether there is another rendition to ask
     * for.
     */
    @SerialName("atmos") val atmos: Boolean? = null,
    /**
     * The addon's own word for the audio flavour — `LOSSLESS`,
     * `HI-RES LOSSLESS` or `AAC`.
     */
    @SerialName("audioQuality") val audioQuality: String? = null,
    /** The audio mode — `DOLBY_ATMOS` or `STEREO`. */
    @SerialName("audioMode") val audioMode: String? = null,
) {
    /** The runtime in milliseconds, whichever shape the addon published. */
    val runtimeMs: Long
        get() = when {
            durationMs > 0 -> durationMs
            durationSeconds != null && durationSeconds > 0 -> (durationSeconds * 1000).toLong()
            else -> 0L
        }

    /** Whether this recording has a Dolby Atmos mix. */
    val hasAtmos: Boolean
        get() = atmos == true ||
            audioMode?.contains("atmos", ignoreCase = true) == true ||
            audioQuality?.contains("atmos", ignoreCase = true) == true

    /** Whether this recording has a Hi-Res lossless master (96/192 kHz). */
    val hasHiRes: Boolean
        get() = audioQuality?.contains("hi-res", ignoreCase = true) == true ||
            audioQuality?.contains("hires", ignoreCase = true) == true
}

/** The addon's Apple home page: a list of shelves, in Apple's own order. */
@Serializable
data class AppleHome(
    @SerialName("shelves") val shelves: List<AppleShelf> = emptyList(),
)

/**
 * A page of the listener's Apple Music library.
 *
 * [tracks] holds the rows; [offset] and [total] are the page window, so the
 * library can be paged without loading all of it at once.
 */
@Serializable
data class AppleLibraryPage(
    @SerialName("tracks") val tracks: List<AppleItem> = emptyList(),
    @SerialName("offset") val offset: Int = 0,
    @SerialName("total") val total: Int = 0,
    @SerialName("hasMore") val hasMore: Boolean = false,
    @SerialName("nextOffset") val nextOffset: Int? = null,
)

/**
 * One Apple Music station.
 *
 * The kinds are Apple's own names for them: `user` is the listener's personal
 * station, `songSeeded` and `playlistSeeded` are the stations Apple builds
 * from a song or a playlist, and `streaming` is a continuous live stream.
 *
 * A live station's audio is a Widevine-protected stream that only Apple's
 * own player plays, so [url] is Apple's page for it rather than a stream
 * address.
 */
@Serializable
data class AppleStation(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("kind") val kind: String? = null,
    @SerialName("isLive") val isLive: Boolean? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("url") val url: String? = null,
    /** Who broadcasts it: "Apple Music" for Apple's own, the broadcaster's own name otherwise. */
    @SerialName("provider") val provider: String? = null,
)

/** A list of Apple stations. */
@Serializable
data class AppleStationList(
    @SerialName("stations") val stations: List<AppleStation> = emptyList(),
)

/** One Apple station's own metadata, when the addon published it. */
@Serializable
data class AppleStationResponse(
    @SerialName("station") val station: AppleStation? = null,
)

/**
 * A live station's playable stream.
 *
 * The playlist is Apple's own address and is public; the license is not, and
 * is not here either — it has to be bought with the account, so the addon
 * does that when the device asks with its challenge.
 */
@Serializable
data class AppleLiveStream(
    @SerialName("stationId") val stationId: String = "",
    @SerialName("adamId") val adamId: String? = null,
    @SerialName("streamURL") val streamURL: String = "",
    @SerialName("mediaURL") val mediaURL: String? = null,
    @SerialName("pssh") val pssh: String? = null,
    @SerialName("skd") val skd: String? = null,
    @SerialName("contentId") val contentId: String? = null,
    @SerialName("widevineUuid") val widevineUuid: String? = null,
    @SerialName("keySystem") val keySystem: String? = null,
)

/**
 * One row of Apple's Radio page.
 *
 * A station or a playlist: Apple's page carries both, and the row says
 * which it is.
 */
@Serializable
data class AppleRadioRow(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("kind") val kind: String = "stations",
    @SerialName("isLive") val isLive: Boolean = false,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("provider") val provider: String? = null,
)

/** One section of Apple's Radio page. */
@Serializable
data class AppleRadioShelf(
    @SerialName("title") val title: String = "",
    @SerialName("items") val items: List<AppleRadioRow> = emptyList(),
)

/** The addon's answer for Apple's Radio page. */
@Serializable
data class AppleRadioShelves(
    @SerialName("shelves") val shelves: List<AppleRadioShelf> = emptyList(),
)

/** The addon's answer for one live station's stream. */
@Serializable
data class AppleLiveStreamResponse(
    @SerialName("live") val live: AppleLiveStream? = null,
)

/**
 * The manifest fields this app reads to know an addon is Apple's.
 *
 * The addon publishes `catalogueProvider: "apple-music"` and a `library`
 * block; an addon that publishes neither is an ordinary addon and is left
 * alone.
 */
@Serializable
data class AppleManifest(
    @SerialName("catalogueProvider") val catalogueProvider: String? = null,
    @SerialName("library") val library: AppleLibraryBlock? = null,
) {
    /** Whether this manifest names Apple Music as its catalogue. */
    val isApple: Boolean get() = catalogueProvider == APPLE_CATALOGUE

    /** The library collections this addon publishes, when it publishes any. */
    val collections: List<String> get() = library?.collections.orEmpty()

    companion object {
        /** The catalogue name Apple Music's manifest uses. */
        const val APPLE_CATALOGUE = "apple-music"

        /**
         * Whether [manifest] is an Apple Music manifest.
         *
         * Read from the manifest itself rather than from the addon's name or
         * its address: a listener's own Apple Music server is a perfectly
         * ordinary addon that happens to serve Apple's catalogue, and it is
         * found by what it says, not by where it lives.
         */
        fun detect(manifest: AddonManifest): Boolean {
            val raw = manifest.rawCatalogueProvider ?: return false
            return raw == APPLE_CATALOGUE
        }
    }
}

/** The `library` block of an Apple Music manifest. */
@Serializable
data class AppleLibraryBlock(
    @SerialName("provider") val provider: String? = null,
    @SerialName("collections") val collections: List<String> = emptyList(),
)
