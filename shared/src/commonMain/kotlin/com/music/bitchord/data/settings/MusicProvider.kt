package com.music.bitchord.data.settings

/**
 * The three axes a listener chooses a provider on, and what each one owns.
 *
 * They are deliberately separate. A person can want Apple's *look* over
 * Spotify's catalogue, Spotify's *recommendations* over Apple's, and Apple's
 * *metadata* over both — and every combination of that is legal. Collapsing
 * the three into one "provider" switch is what made the earlier builds
 * impossible to configure: choosing Apple for the catalogue silently changed
 * the recommendations, and choosing Spotify for the recommendations silently
 * changed the metadata.
 *
 * The axes:
 *
 *  - [ProviderAxis.UI] — the outer look and layout: which catalogue's design
 *    the screens wear. Apple's layout over YouTube's rows is still YouTube's
 *    rows, drawn differently.
 *  - [ProviderAxis.RECOMMENDATION] — which algorithm builds Home, Flow and
 *    Autoplay. It chooses *what* is suggested, never where the audio comes
 *    from.
 *  - [ProviderAxis.SOURCE] — where songs, metadata, lyrics and artwork come
 *    from. It chooses the *identity* of a track: its catalogue id, its
 *    spelling, its cover.
 *
 * Nothing outside this file should assume a provider is one thing.
 */
enum class ProviderAxis(
    /** Stable identifier used in preferences and in the settings catalogue. */
    val id: String,
) {
    /** The catalogue look: the skin the screens wear. */
    UI("ui"),

    /** The discovery algorithm: what Home, Flow and Autoplay suggest. */
    RECOMMENDATION("recommendation"),

    /** The content source: songs, metadata, lyrics and artwork. */
    SOURCE("source"),
    ;

    companion object {
        /** The axis named [id], or null when there is none. */
        fun fromId(id: String?): ProviderAxis? =
            entries.firstOrNull { it.id == id }
    }
}

/**
 * A provider a listener can point an axis at.
 *
 * Three of them, and each of them can serve any axis — which is the point.
 * The *meaning* of the choice is decided by the axis it is made on, not by
 * the provider:
 *
 *  - On [ProviderAxis.UI] it picks the layout.
 *  - On [ProviderAxis.RECOMMENDATION] it picks the algorithm.
 *  - On [ProviderAxis.SOURCE] it picks the catalogue the audio and metadata
 *    come from.
 *
 * [APPLE] needs an Apple Music manifest to be installed; without one, the
 * axis falls back to what the app can actually read, and says so.
 */
enum class MusicProvider(
    /** Stable identifier used in preferences. */
    val id: String,
) {
    /** YouTube Music, through the app's own innertube session. */
    YOUTUBE("youtube"),

    /** Spotify, through the listener's own Spotify session. */
    SPOTIFY("spotify"),

    /** Apple Music, through an installed Apple Music manifest (addon). */
    APPLE("apple"),
    ;

    /** Whether this provider can only be read by a signed-in listener. */
    val needsSignIn: Boolean get() = this == SPOTIFY

    /**
     * Whether this provider needs an Apple Music manifest installed before it
     * can answer anything.
     *
     * Apple's catalogue is not read from inside the app: it is read from an
     * addon the listener installs, which holds the account. So "Apple" on any
     * axis is only real once that manifest is installed and configured.
     */
    val needsAppleManifest: Boolean get() = this == APPLE

    companion object {
        /** The provider named [id], or null when it names none known. */
        fun fromId(id: String?): MusicProvider? =
            entries.firstOrNull { it.id == id }

        /**
         * The provider named [name], reading either its id or its enum name.
         *
         * Preferences written before the three axes existed stored the enum
         * name; the new ones store the id. Both are read here so an upgrade
         * keeps the listener's choice.
         */
        fun of(name: String?): MusicProvider =
            fromId(name)
                ?: entries.firstOrNull { it.name == name }
                ?: YOUTUBE
    }
}
