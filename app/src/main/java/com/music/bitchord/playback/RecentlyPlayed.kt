package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The tracks that played lately, kept on this device only.
 *
 * The search page opens on what the listener was doing, the way Apple's and
 * Spotify's do. The account's own history is not the right source for it: it
 * needs a signed-in round trip, it is wrong for a signed-out listener, and it
 * does not exist for locally played files. A small local ring is instant and
 * survives a restart.
 *
 * Only what a row needs is stored — the identity fields, not the whole song —
 * so the file stays small and a changed model cannot break an old list.
 */
object RecentlyPlayed {

    private const val MAX_ENTRIES = 40
    private const val KEY = "recently_played_v1"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Entry.serializer())

    @Serializable
    private data class Entry(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val isrc: String? = null,
        val albumName: String? = null,
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
    }

    /** Records one start. The same track twice in a row is one entry, moved up. */
    fun record(song: Song) {
        if (!this::prefs.isInitialized) return
        if (song.videoId.isBlank() || song.title.isBlank()) return
        val entry = Entry(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText,
            isrc = song.isrc,
            albumName = song.albumName,
        )
        val next = (listOf(entry) + load().filterNot { it.videoId == entry.videoId })
            .take(MAX_ENTRIES)
        runCatching { prefs.edit().putString(KEY, json.encodeToString(serializer, next)).apply() }
    }

    /** Newest first. Empty before the first track, and after a cleared install. */
    private fun load(): List<Entry> {
        if (!this::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    /** The last [limit] tracks, as the page draws them. */
    fun recent(limit: Int): List<Song> =
        load().take(limit).map { entry ->
            Song(
                videoId = entry.videoId,
                title = entry.title,
                artist = entry.artist,
                thumbnailUrl = entry.thumbnailUrl,
                durationText = entry.durationText,
                albumName = entry.albumName,
                isrc = entry.isrc,
            )
        }
}
