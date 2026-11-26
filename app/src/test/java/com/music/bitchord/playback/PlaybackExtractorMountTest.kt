package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import com.music.bitchord.playback.dsd.DsdExtractor as PcmDsdExtractor
import com.music.bitchord.playback.tracker.TrackerExtractor
import com.music.bitchord.playback.tracker.TrackerExtractorsFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Regression test for the live `mediaSourceFactory` extractor mount. The raw-DSD
 * layer is injected as a marker so this checks the ordering PlaybackService builds
 * without requiring a USB device: raw -> tracker -> PCM DSD -> stock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@UnstableApi
class PlaybackExtractorMountTest {

    private class RawMarkerExtractor : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = false
        override fun init(output: ExtractorOutput) {}
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private class StockMarkerExtractor : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = false
        override fun init(output: ExtractorOutput) {}
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private class PrefixExtractorsFactory(
        private val prefix: Extractor,
        private val delegate: ExtractorsFactory,
    ) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> =
            arrayOf(prefix, *delegate.createExtractors())

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>,
        ): Array<Extractor> = arrayOf(prefix, *delegate.createExtractors(uri, responseHeaders))
    }

    private class SingleStockExtractorsFactory : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = arrayOf(StockMarkerExtractor())
        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>,
        ): Array<Extractor> = arrayOf(StockMarkerExtractor())
    }

    private class ByteArrayExtractorInput(private val data: ByteArray) : ExtractorInput {
        private var peekPosition = 0

        override fun read(target: ByteArray, offset: Int, length: Int): Int = throw IOException("read")
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEOF: Boolean): Boolean =
            throw IOException("readFully")
        override fun readFully(target: ByteArray, offset: Int, length: Int) =
            throw IOException("readFully")
        override fun skip(length: Int): Int = throw IOException("skip")
        override fun skipFully(length: Int, allowEOF: Boolean): Boolean = throw IOException("skipFully")
        override fun skipFully(length: Int) = throw IOException("skipFully")
        override fun peek(target: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, (data.size - peekPosition).coerceAtLeast(0))
            if (count > 0) System.arraycopy(data, peekPosition, target, offset, count)
            return count
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEOF: Boolean): Boolean {
            val count = peek(target, offset, length)
            return allowEOF || count == length
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) = throw IOException("peekFully")
        override fun advancePeekPosition(length: Int, allowEOF: Boolean): Boolean =
            throw IOException("advancePeekPosition")
        override fun advancePeekPosition(length: Int) = throw IOException("advancePeekPosition")
        override fun resetPeekPosition() {
            peekPosition = 0
        }
        override fun getPeekPosition(): Long = peekPosition.toLong()
        override fun getPosition(): Long = 0L
        override fun getLength(): Long = data.size.toLong()
        override fun <E : Throwable> setRetryPosition(position: Long, cause: E) {}
    }

    @Test
    fun `live extractor mount orders raw tracker pcm dsd then stock`() {
        val factory = playbackExtractorsFactory(
            rawDsdLayer = { delegate -> PrefixExtractorsFactory(RawMarkerExtractor(), delegate) },
            stock = SingleStockExtractorsFactory(),
        )

        val extractors = factory.createExtractors(
            Uri.parse("file:///music/song.mod"),
            emptyMap(),
        )

        assertEquals(4, extractors.size)
        assertTrue(extractors[0] is RawMarkerExtractor)
        assertTrue(extractors[1] is TrackerExtractor)
        assertTrue(extractors[2] is PcmDsdExtractor)
        assertTrue(extractors[3] is StockMarkerExtractor)
    }

    @Test
    fun `tracker extension hint makes a dot mod URI sniff without reading its bytes`() {
        val tracker = TrackerExtractorsFactory(SingleStockExtractorsFactory())
            .createExtractors(Uri.parse("file:///music/song.mod"), emptyMap())
            .first() as TrackerExtractor

        // Garbage is enough: an extension-known module short-circuits before content sniffing.
        assertTrue(tracker.sniff(ByteArrayExtractorInput(ByteArray(1084))))
    }

    @Test
    fun `tracker content sniff still recognizes an extensionless XM module`() {
        val tracker = TrackerExtractorsFactory(SingleStockExtractorsFactory())
            .createExtractors(Uri.parse("file:///music/module"), emptyMap())
            .first() as TrackerExtractor

        val xm = ByteArray(1084)
        "Extended Module: ".toByteArray(Charsets.US_ASCII).copyInto(xm)
        assertTrue(tracker.sniff(ByteArrayExtractorInput(xm)))

        val other = TrackerExtractorsFactory(SingleStockExtractorsFactory())
            .createExtractors(Uri.parse("file:///music/module"), emptyMap())
            .first() as TrackerExtractor
        assertFalse(other.sniff(ByteArrayExtractorInput(ByteArray(1084))))
    }
}
