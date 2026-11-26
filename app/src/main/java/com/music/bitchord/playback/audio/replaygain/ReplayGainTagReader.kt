package com.music.bitchord.playback.audio.replaygain

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/** Reads ReplayGain keys exposed by Android's extractor without blocking audio threads. */
object ReplayGainTagReader {
    private val KEYS = listOf(
        ReplayGainMetadata.TRACK_GAIN_KEY,
        ReplayGainMetadata.ALBUM_GAIN_KEY,
        ReplayGainMetadata.TRACK_PEAK_KEY,
        ReplayGainMetadata.ALBUM_PEAK_KEY,
    )

    fun read(context: Context, uri: Uri): ReplayGainMetadata {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val tags = mutableMapOf<String, Any>()
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                for (key in KEYS) {
                    if (format.containsKey(key)) {
                        runCatching { format.getString(key) }.getOrNull()?.let { tags[key] = it }
                    }
                }
            }
            ReplayGainMetadata.fromMetadata(tags)
        } catch (_: Throwable) {
            ReplayGainMetadata.EMPTY
        } finally {
            runCatching { extractor.release() }
        }
    }
}
