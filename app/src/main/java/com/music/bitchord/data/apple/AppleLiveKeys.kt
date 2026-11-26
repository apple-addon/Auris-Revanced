package com.music.bitchord.data.apple

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The key a live station's playlist publishes.
 *
 * A live station's audio is encrypted, and the key it is encrypted under is
 * named in its playlist. The playlist is public, so the device can read the
 * key itself — and it should, because the key is rotated as the stream goes
 * on: the key the device read a moment ago is not necessarily the key the
 * server would read now.
 *
 * So the device reads the key and hands it over with its license request.
 * The server then asks Apple about the very key the device is playing,
 * rather than about whichever one it happened to read itself.
 */
internal data class AppleLiveKey(
    /** The whole Widevine address, the way the playlist writes it. */
    val widevineUri: String,
    /** Apple's own key identifier for the same stream. */
    val skd: String? = null,
) {
    /** The key id the address carries, for the server to match a challenge against. */
    val pssh: String
        get() = widevineUri.substringAfterLast("base64,", widevineUri).trim()
}

internal object AppleLiveKeys {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Reads the key from the station's own media playlist.
     *
     * Null when the playlist cannot be read or holds no Widevine key: the
     * server then falls back to reading it itself, which is nearly always
     * the same key.
     */
    suspend fun read(mediaUrl: String): AppleLiveKey? {
        if (mediaUrl.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(mediaUrl)
                    .header("Origin", "https://music.apple.com")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val text = response.body?.string().orEmpty()
                    parse(text)
                }
            }.getOrNull()
        }
    }

    /** The Widevine key of one media playlist, if it publishes one. */
    internal fun parse(playlist: String): AppleLiveKey? {
        var widevine = ""
        var skd = ""
        for (line in playlist.lineSequence()) {
            if (!line.startsWith("#EXT-X-KEY")) continue
            val format = attribute(line, "KEYFORMAT")
            val uri = attribute(line, "URI")
            if (format == "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" && widevine.isBlank()) {
                widevine = uri
            } else if (format == "com.apple.streamingkeydelivery" && skd.isBlank()) {
                skd = uri
            }
        }
        if (widevine.isBlank()) return null
        return AppleLiveKey(widevineUri = widevine, skd = skd.takeIf { it.isNotBlank() })
    }

    /** One attribute of a key line, with its quotes removed. */
    private fun attribute(line: String, name: String): String {
        val marker = "$name=\""
        val start = line.indexOf(marker)
        if (start < 0) return ""
        val from = start + marker.length
        val end = line.indexOf('"', from)
        if (end < 0) return ""
        return line.substring(from, end)
    }
}
