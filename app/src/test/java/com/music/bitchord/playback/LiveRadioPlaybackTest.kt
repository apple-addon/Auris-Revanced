package com.music.bitchord.playback

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A live station has to play *here*.
 *
 * Apple's live radio is one continuous Widevine-protected HLS stream, not a
 * song to be looked up, and the listener asked for it to be heard in this
 * app: no redirect, no browser, no hand-off to Apple's own player. That
 * works only if two things survive into the item the player is given — the
 * stream's own address and the endpoint the device trades its challenge
 * for a license at. Drop either and the row becomes an ordinary track with
 * nothing to play.
 *
 * Robolectric, because a MediaItem's address is an `android.net.Uri`, and the
 * stub android.jar the plain unit tests run against returns nothing for it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LiveRadioPlaybackTest {

    private fun liveSong(
        stream: String = "https://itsliveradio.apple.com/gl/1498155548/index-cmaf.m3u8",
        license: String? = "https://music.example.org/a/token/radio/license?station=ra.1498155548",
    ) = Song(
        videoId = "apple|ra.1498155548",
        title = "Apple Music Hits",
        artist = "Apple Music",
        thumbnailUrl = null,
        liveStreamUrl = stream,
        liveLicenseUrl = license,
    )

    @Test
    fun `a live station plays the stream the row carries`() {
        val item = liveSong().toMediaItem()
        assertEquals(
            "https://itsliveradio.apple.com/gl/1498155548/index-cmaf.m3u8",
            item.localConfiguration?.uri?.toString(),
        )
    }

    @Test
    fun `a live station asks for its license where the row says`() {
        val drm = liveSong().toMediaItem().localConfiguration?.drmConfiguration
        assertNotNull("a live station needs a DRM configuration", drm)
        assertEquals(WIDEVINE_UUID, drm?.uuid)
        assertEquals(
            "https://music.example.org/a/token/radio/license?station=ra.1498155548",
            drm?.licenseUri?.toString(),
        )
    }

    @Test
    fun `an ordinary track has no DRM configuration`() {
        val song = Song(
            videoId = "dQw4w9WgXcQ",
            title = "A track",
            artist = "An artist",
            thumbnailUrl = null,
        )
        assertNull(song.toMediaItem().localConfiguration?.drmConfiguration)
    }

    @Test
    fun `a live station keeps its stream and its license across the session boundary`() {
        val song = liveSong().toMediaItem().toSong()
        assertEquals(
            "https://itsliveradio.apple.com/gl/1498155548/index-cmaf.m3u8",
            song.liveStreamUrl,
        )
        assertEquals(
            "https://music.example.org/a/token/radio/license?station=ra.1498155548",
            song.liveLicenseUrl,
        )
    }
}
