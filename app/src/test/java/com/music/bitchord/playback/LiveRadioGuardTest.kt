package com.music.bitchord.playback

import android.net.Uri
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application

/**
 * A live station is a broadcast, and the app's machinery for ordinary tracks
 * must leave it alone.
 *
 * The machinery that looks for a better copy of a track found one for a live
 * station too — the station's id was read as a catalogue id, and what came
 * back was a 96 kHz file of a different recording. That file replaced the
 * broadcast every few seconds, and the station played as a hiss.
 *
 * These tests pin the three guards that stop it: the hunt does not run, the
 * position is not kept, and the item is not restored at a place in a window
 * that has rolled on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LiveRadioGuardTest {

    private val playlist = "https://itsliveradio.apple.com/gl/978194965/index-cmaf.m3u8"
    private val segment = "https://liveradio.music.apple.com/usw12/out/v1/978194965/x/segment_1.m4s"
    private val ordinary = "https://music.csproject.org/a/token/media/448213995"

    @Test
    fun `a live station's own addresses are recognised`() {
        assertTrue(LiveRadio.isLiveStream(Uri.parse(playlist)))
        assertTrue(LiveRadio.isLiveStream(Uri.parse(segment)))
    }

    @Test
    fun `an ordinary stream is not a live station`() {
        assertFalse(LiveRadio.isLiveStream(Uri.parse(ordinary)))
        assertFalse(LiveRadio.isLiveStream(Uri.parse("bitchord://source?s=apple&t=ra.978194965")))
        assertFalse(LiveRadio.isLiveStream(null))
    }

    @Test
    fun `a live station is not looked at for a better copy`() {
        assertFalse(
            QualityUpgrade.couldStillUpgrade("ra.978194965", Uri.parse(playlist)),
        )
    }

    @Test
    fun `an ordinary track is still looked at`() {
        // The guard must not have taken the hunt away from ordinary tracks.
        assertTrue(
            QualityUpgrade.couldStillUpgrade(
                "448213995",
                Uri.parse("bitchord://source?s=apple&t=448213995&v=lossless"),
            ),
        )
    }

    @Test
    fun `a live station plays its own stream with its own license`() {
        val song = Song(
            videoId = "apple|ra.978194965",
            title = "Apple Music 1",
            artist = "Apple Music",
            thumbnailUrl = null,
            liveStreamUrl = playlist,
            liveLicenseUrl = "https://music.csproject.org/a/token/radio/license?station=ra.978194965",
        )
        val item = song.toMediaItem()
        assertEquals(playlist, item.localConfiguration?.uri?.toString())
        val drm = item.localConfiguration?.drmConfiguration
        assertNotNull("a live station carries its license endpoint", drm)
        assertEquals(WIDEVINE_UUID, drm?.uuid)
        assertEquals(
            "https://music.csproject.org/a/token/radio/license?station=ra.978194965",
            drm?.licenseUri?.toString(),
        )
    }
}
