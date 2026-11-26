package com.music.bitchord.playback.tracker

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class TrackerExtractorsFactoryTest {
    @Test
    fun `createExtractors with uri puts tracker first and forwards uri and headers to delegate`() {
        for (extension in listOf("mod", "MOD", "it", "s3m", "XM")) {
            val delegate = RecordingExtractorsFactory()
            val factory = TrackerExtractorsFactory(delegate)
            val uri = Uri.parse("content://media/external/audio/42/song.$extension?token=abc")
            val headers = mapOf("Content-Type" to listOf("application/octet-stream"))

            val extractors = factory.createExtractors(uri, headers)

            assertEquals("extension=$extension", 2, extractors.size)
            assertTrue("extension=$extension", extractors[0] is TrackerExtractor)
            assertSame("extension=$extension", delegate.extractor, extractors[1])
            assertSame("extension=$extension", uri, delegate.uri)
            assertEquals("extension=$extension", headers, delegate.headers)
            assertEquals("extension=$extension", 1, delegate.uriCallCount)

            // A tracker extension is a routing hint: it claims the file even when
            // the first 1084 bytes are not a magic-bearing MOD header.
            val input = ByteArrayExtractorInput(ByteArray(1084) { (it * 31).toByte() })
            assertTrue("extension=$extension", extractors[0].sniff(input))
            assertEquals(0L, input.peekPosition)
            assertEquals(0L, input.position)
        }
    }

    @Test
    fun `no-arg factory also puts tracker before the delegate`() {
        val delegate = RecordingExtractorsFactory()
        val extractors = TrackerExtractorsFactory(delegate).createExtractors()
        assertEquals(2, extractors.size)
        assertTrue(extractors[0] is TrackerExtractor)
        assertSame(delegate.extractor, extractors[1])
        assertEquals(1, delegate.noArgCallCount)
    }

    @Test
    fun `sniff recognizes XM IT S3M and MOD signatures without consuming input`() {
        val cases = listOf(
            "XM" to ByteArray(64).apply { putAscii(0, "Extended Module: ") },
            "IT" to ByteArray(64).apply { putAscii(0, "IMPM") },
            "S3M" to ByteArray(64).apply { putAscii(44, "SCRM") },
            "MOD M.K." to ByteArray(1084).apply { putAscii(1080, "M.K.") },
            "MOD 8CHN" to ByteArray(1084).apply { putAscii(1080, "8CHN") },
            "MOD TDZ4" to ByteArray(1084).apply { putAscii(1080, "TDZ4") },
        )
        for ((name, bytes) in cases) {
            val extractor = TrackerExtractor()
            val input = ByteArrayExtractorInput(bytes)
            assertTrue("signature=$name", extractor.sniff(input))
            assertEquals("signature=$name", 0L, input.peekPosition)
            assertEquals("signature=$name", 0L, input.position)
        }
    }

    @Test
    fun `sniff rejects non-trackers and leaves the delegate available`() {
        val factory = TrackerExtractorsFactory(RecordingExtractorsFactory())
        val extractors = factory.createExtractors(
            Uri.parse("file:///music/song.mp3"),
            emptyMap(),
        )

        val mp3 = ByteArray(1084)
        "ID3".toByteArray().copyInto(mp3)
        val tracker = extractors[0]
        val delegate = extractors[1]
        val input = ByteArrayExtractorInput(mp3)

        assertFalse(tracker.sniff(input))
        assertEquals(0L, input.peekPosition)
        assertEquals(0L, input.position)
        assertFalse("delegate remains the fallback candidate", delegate.sniff(input))
    }

    @Test
    fun `truncated inputs do not match a MOD magic and reset the peek position`() {
        val extractor = TrackerExtractor()
        val input = ByteArrayExtractorInput("IMPX".toByteArray())
        assertFalse(extractor.sniff(input))
        assertEquals(0L, input.peekPosition)
    }
}
