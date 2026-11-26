package com.music.bitchord.playback.tracker

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class TrackerExtractorTest {
    @Test
    fun `decoder requires direct buffers and preserves Java buffer positions`() {
        assumeNativeTrackerLibrary()
        val module = directModule(fixtureBytes())
        val inputPosition = module.position()

        val decoder = TrackerDecoder(module)
        assertEquals(inputPosition, module.position())
        assertTrue(decoder.durationUs > 0L)

        val output = ByteBuffer.allocateDirect(64 * TrackerDecoder.BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.position(8)
        val frames = decoder.read(output, 32)
        assertEquals(32, frames)
        assertEquals(8, output.position())
        assertEquals(32L, decoder.positionFrame)
        for (frame in 0 until frames) {
            assertTrue(output.getFloat(8 + frame * 8).isFinite())
            assertTrue(output.getFloat(8 + frame * 8 + 4).isFinite())
        }

        assertThrows(IllegalArgumentException::class.java) {
            TrackerDecoder(ByteBuffer.wrap(fixtureBytes()))
        }
        val heapOutput = ByteBuffer.allocate(64 * TrackerDecoder.BYTES_PER_FRAME)
        assertThrows(IllegalArgumentException::class.java) { decoder.read(heapOutput, 1) }
        decoder.close()
    }

    @Test
    fun `decoder seek reset and close have the documented behavior`() {
        assumeNativeTrackerLibrary()
        val bytes = fixtureBytes()
        val decoder = TrackerDecoder(directModule(bytes))
        val reference = TrackerDecoder(directModule(bytes))
        val pcm = ByteBuffer.allocateDirect(128 * TrackerDecoder.BYTES_PER_FRAME)
            .order(ByteOrder.LITTLE_ENDIAN)

        val firstFrames = decoder.read(pcm, 128)
        assertTrue(firstFrames > 0)
        val initial = ByteArray(firstFrames * TrackerDecoder.BYTES_PER_FRAME)
        for (index in initial.indices) initial[index] = pcm.get(index)

        val targetUs = decoder.durationUs / 2
        val seekUs = decoder.seek(targetUs)
        assertTrue(seekUs > 0L && seekUs <= decoder.durationUs)
        assertEquals(seekUs, decoder.positionUs)
        assertEquals(seekUs, reference.seek(targetUs))
        assertTrue(decoder.read(pcm, 16) >= 0)

        decoder.reset()
        assertEquals(0L, decoder.positionFrame)
        assertEquals(0L, decoder.positionUs)
        pcm.clear()
        val resetFrames = decoder.read(pcm, 128)
        assertEquals(firstFrames, resetFrames)
        val afterReset = ByteArray(resetFrames * TrackerDecoder.BYTES_PER_FRAME)
        for (index in afterReset.indices) afterReset[index] = pcm.get(index)
        assertArrayEquals(initial, afterReset)

        decoder.close()
        decoder.close()
        assertThrows(IllegalStateException::class.java) { decoder.read(pcm, 1) }
        assertThrows(IllegalStateException::class.java) { decoder.seek(0) }
        assertThrows(IllegalStateException::class.java) { decoder.reset() }
    }

    @Test
    fun `extractor emits 48k stereo Float32 with gapless timestamps and no PCM file reads`() {
        assumeNativeTrackerLibrary()
        val bytes = fixtureBytes()
        val referenceDurationUs = TrackerDecoder(directModule(bytes)).use { it.durationUs }
        val extractor = TrackerExtractor()
        val input = ByteArrayExtractorInput(bytes)
        assertTrue(extractor.sniff(input))

        val output = RecordingExtractorOutput()
        extractor.init(output)
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, PositionHolder()))
        assertEquals(bytes.size.toLong(), input.position)
        assertTrue(output.endedTracks)

        val format = checkNotNull(output.trackOutput.format)
        assertEquals(MimeTypes.AUDIO_RAW, format.sampleMimeType)
        assertEquals("audio/x-mod", format.containerMimeType)
        assertEquals("mod", format.codecs)
        assertEquals(TrackerDecoder.SAMPLE_RATE, format.sampleRate)
        assertEquals(TrackerDecoder.CHANNEL_COUNT, format.channelCount)
        assertEquals(C.ENCODING_PCM_FLOAT, format.pcmEncoding)
        assertEquals(4096 * TrackerDecoder.BYTES_PER_FRAME, format.maxInputSize)
        assertEquals(TrackerDecoder.SAMPLE_RATE * TrackerDecoder.CHANNEL_COUNT * 32, format.averageBitrate)

        val seekMap = checkNotNull(output.seekMap)
        assertTrue(seekMap.isSeekable)
        assertEquals(referenceDurationUs, seekMap.durationUs)
        assertEquals(0L, seekMap.getSeekPoints(-5).first.timeUs)
        assertEquals(0L, seekMap.getSeekPoints(-5).first.position)
        assertEquals(referenceDurationUs, seekMap.getSeekPoints(Long.MAX_VALUE).first.timeUs)
        assertEquals(0L, seekMap.getSeekPoints(Long.MAX_VALUE).first.position)

        val readsAfterModuleLoad = input.readCalls
        var expectedFrame = 0L
        var totalFrames = 0L
        var sawSignal = false
        while (true) {
            val result = extractor.read(input, PositionHolder())
            if (result == Extractor.RESULT_END_OF_INPUT) break
            assertEquals(Extractor.RESULT_CONTINUE, result)
            val sample = output.trackOutput.samples.last()
            assertEquals(
                "timestamp must be derived from absolute frame position",
                TrackerDecoder.framesToUs(expectedFrame),
                sample.timeUs,
            )
            assertEquals(C.BUFFER_FLAG_KEY_FRAME, sample.flags)
            assertEquals(0, sample.offset)
            assertEquals(0, sample.byteCount % TrackerDecoder.BYTES_PER_FRAME)
            assertEquals(readsAfterModuleLoad, input.readCalls)
            if (output.trackOutput.samples.size > 1) {
                assertTrue(output.trackOutput.samples.distinctBy { it.dataReader }.size == 1)
            }
            sawSignal = sawSignal || sample.bytes.any { it != 0.toByte() }
            expectedFrame += sample.byteCount / TrackerDecoder.BYTES_PER_FRAME
            totalFrames = expectedFrame
        }

        assertTrue("fixture rendered silence", sawSignal)
        assertTrue(totalFrames > 0)
        assertTrue(
            "EOS should land within one second of the native duration",
            abs(TrackerDecoder.framesToUs(totalFrames) - referenceDurationUs) <= TrackerDecoder.SAMPLE_RATE,
        )
        assertEquals(Extractor.RESULT_END_OF_INPUT, extractor.read(input, PositionHolder()))
        extractor.release()
    }

    @Test
    fun `extractor seek changes native position and seeking to zero reproduces the first block`() {
        assumeNativeTrackerLibrary()
        val bytes = fixtureBytes()
        val reference = TrackerDecoder(directModule(bytes))
        val extractor = TrackerExtractor()
        val input = ByteArrayExtractorInput(bytes)
        val output = RecordingExtractorOutput()
        extractor.init(output)
        extractor.read(input, PositionHolder())
        extractor.read(input, PositionHolder())
        val first = output.trackOutput.samples.single()

        val targetUs = reference.durationUs / 2
        extractor.seek(12345L, targetUs)
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, PositionHolder()))
        val sought = output.trackOutput.samples.last()
        assertEquals(reference.seek(targetUs), sought.timeUs)
        assertTrue(sought.timeUs > 0L)

        extractor.seek(0L, 0L)
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, PositionHolder()))
        val restarted = output.trackOutput.samples.last()
        assertEquals(0L, restarted.timeUs)
        assertArrayEquals(first.bytes, restarted.bytes)
        extractor.release()
    }

    @Test
    fun `pending seek before the first read is applied to the native decoder`() {
        assumeNativeTrackerLibrary()
        val bytes = fixtureBytes()
        val referenceDurationUs = TrackerDecoder(directModule(bytes)).use { it.durationUs }
        val extractor = TrackerExtractor()
        val output = RecordingExtractorOutput()
        extractor.init(output)
        extractor.seek(0L, referenceDurationUs / 2)
        extractor.read(ByteArrayExtractorInput(bytes), PositionHolder())
        extractor.read(ByteArrayExtractorInput(bytes), PositionHolder())
        assertTrue(output.trackOutput.samples.single().timeUs > 0L)
        extractor.release()
    }

    @Test
    fun `unknown input length is still decoded to EOS`() {
        assumeNativeTrackerLibrary()
        val bytes = fixtureBytes()
        val extractor = TrackerExtractor()
        val input = ByteArrayExtractorInput(bytes, declaredLength = C.LENGTH_UNSET.toLong())
        val output = RecordingExtractorOutput()
        extractor.init(output)
        extractor.read(input, PositionHolder())
        var frames = 0L
        while (extractor.read(input, PositionHolder()) == Extractor.RESULT_CONTINUE) {
            frames += output.trackOutput.samples.last().byteCount / TrackerDecoder.BYTES_PER_FRAME
        }
        assertTrue(frames > 0)
        extractor.release()
    }

    @Test
    fun `non-tracker and malformed modules fail as parser errors rather than being claimed`() {
        assumeNativeTrackerLibrary()
        val random = ByteArrayExtractorInput(ByteArray(1084) { (it * 17 + 3).toByte() })
        val unknown = TrackerExtractor("mp3")
        assertFalse(unknown.sniff(random))
        unknown.init(RecordingExtractorOutput())
        assertThrows(ParserException::class.java) {
            unknown.read(ByteArrayExtractorInput(randomData()), PositionHolder())
        }

        // The .mod hint claims the stream before content validation; libopenmpt
        // must reject the payload as a malformed container, not a raw fallback.
        val malformed = TrackerExtractor("mod")
        assertTrue(malformed.sniff(ByteArrayExtractorInput(randomData())))
        malformed.init(RecordingExtractorOutput())
        assertThrows(ParserException::class.java) {
            malformed.read(ByteArrayExtractorInput(randomData()), PositionHolder())
        }
    }

    @Test
    fun `release drops decoder and track state and is idempotent`() {
        assumeNativeTrackerLibrary()
        val extractor = TrackerExtractor()
        extractor.init(RecordingExtractorOutput())
        extractor.read(ByteArrayExtractorInput(fixtureBytes()), PositionHolder())
        val decoderField = TrackerExtractor::class.java.getDeclaredField("decoder").apply { isAccessible = true }
        val trackField = TrackerExtractor::class.java.getDeclaredField("track").apply { isAccessible = true }
        assertNotNull(decoderField.get(extractor))
        assertNotNull(trackField.get(extractor))

        extractor.release()
        extractor.release()
        assertNull(decoderField.get(extractor))
        assertNull(trackField.get(extractor))
    }

    private fun randomData(): ByteArray = ByteArray(1084) { (it * 17 + 3).toByte() }

    private fun fixtureBytes(): ByteArray {
        val explicit = System.getProperty("tracker.fixture")?.let(::File)
        val candidates = listOfNotNull(
            explicit,
            File("tools/tracker/fixtures/tiny.mod"),
            File("app/tools/tracker/fixtures/tiny.mod"),
            File("../tools/tracker/fixtures/tiny.mod"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("tracker fixture not found; pass -Dtracker.fixture=...")
        return file.readBytes()
    }

    private fun directModule(bytes: ByteArray): ByteBuffer {
        // Start at a nonzero, aligned offset to prove the native wrapper honors
        // position/remaining instead of assuming buffer index zero.
        val padded = ByteBuffer.allocateDirect(bytes.size + 8)
        padded.position(8)
        padded.put(bytes)
        padded.position(8)
        padded.limit(8 + bytes.size)
        return padded
    }

    private fun assumeNativeTrackerLibrary() {
        try {
            // Any non-inlined call forces TrackerDecoder's companion to load the host JNI library.
            TrackerDecoder.framesToUs(1L)
        } catch (failure: Throwable) {
            assumeTrue("host bitchord_tracker library unavailable: ${failure.message}", false)
        }
    }
}
