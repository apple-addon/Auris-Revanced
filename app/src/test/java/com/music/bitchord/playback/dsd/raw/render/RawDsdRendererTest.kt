package com.music.bitchord.playback.dsd.raw.render

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.DsdTransportPlan
import com.music.bitchord.playback.dsd.NativeDsdPacker
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.raw.RawDsdConstants
import com.music.bitchord.playback.dsd.raw.RawDsdPacking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class RawDsdRendererTest {
    private val stereoDsd = bytes(0x12, 0x34, 0x56, 0x78, 0x9a, 0xbc, 0xde, 0xf0)
    private val stereoDop = bytes(0x34, 0x12, 0x05, 0x78, 0x56, 0x05, 0xbc, 0x9a, 0xfa, 0xf0, 0xde, 0xfa)

    @Test
    fun dopGoldenBytesPreserveEveryDsdBitAndAlternateMarkersAcrossPackets() {
        val transport = InMemoryRawDsdTransport()
        val harness = Harness(transport, dopPlan(), inputPacking = NativeDsdPacking.DSD_U32_BE)
        val input = NativeDsdPacker.pack(stereoDsd, 2, NativeDsdPacking.DSD_U32_BE)
        harness.queueSample(input, timeUs = 0)
        harness.queueSample(input, timeUs = 11)
        harness.finish()

        harness.render()
        assertArrayEquals(stereoDop + stereoDop, transport.writtenBytes)
        assertEquals(listOf(0L, 11L), transport.writes.map { it.presentationTimeUs })
        assertTrue(harness.renderer.isEnded)
        assertEquals(DsdTransportKind.DOP, transport.configurations.single().kind)

        // Independent golden bytes above prove this is DoP framing, not PCM samples or conversion.
        val markers = transport.writtenBytes.filterIndexed { index, _ -> index % 3 == 2 }
        assertEquals(bytes(0x05, 0x05, 0xfa, 0xfa, 0x05, 0x05, 0xfa, 0xfa).toList(), markers)
    }

    @Test
    fun nativeGoldenU32PackingChangesOnlyWordByteOrder() {
        val transport = InMemoryRawDsdTransport()
        val harness = Harness(
            transport,
            nativePlan(NativeDsdPacking.DSD_U32_LE),
            inputPacking = NativeDsdPacking.DSD_U32_BE,
        )
        val canonical = bytes(
            0x00, 0x88, 0x11, 0x99, 0x22, 0xaa, 0x33, 0xbb,
            0x44, 0xcc, 0x55, 0xdd, 0x66, 0xee, 0x77, 0xff,
        )
        harness.queueSample(NativeDsdPacker.pack(canonical, 2, NativeDsdPacking.DSD_U32_BE), 777)
        harness.finish()

        harness.render()
        val expected = bytes(
            0x33, 0x22, 0x11, 0x00, 0xbb, 0xaa, 0x99, 0x88,
            0x77, 0x66, 0x55, 0x44, 0xff, 0xee, 0xdd, 0xcc,
        )
        assertArrayEquals(expected, transport.writtenBytes)
        assertArrayEquals(canonical, NativeDsdPacker.unpack(transport.writtenBytes, 2, NativeDsdPacking.DSD_U32_LE))
        assertEquals(777L, transport.writes.single().presentationTimeUs)
        assertEquals(2, transport.configurations.single().rawAlternateSetting)
    }

    @Test
    fun zeroAndPartialWritesRetainPacketWithoutReadingAheadOrDroppingBytes() {
        val transport = InMemoryRawDsdTransport().apply {
            acceptingWrites = false
            maxWriteBytes = 6
        }
        val harness = Harness(transport, dopPlan(), inputPacking = NativeDsdPacking.DSD_U32_BE)
        val input = NativeDsdPacker.pack(stereoDsd, 2, NativeDsdPacking.DSD_U32_BE)
        harness.queueSample(input, timeUs = 100)
        harness.queueSample(input, timeUs = 200)
        harness.finish()

        harness.render()
        assertTrue(transport.writes.isEmpty())
        assertEquals(1, harness.queue.readIndex)

        transport.acceptingWrites = true
        harness.render()
        assertEquals(6, transport.writtenBytes.size)
        assertEquals(1, harness.queue.readIndex)
        harness.render()
        assertEquals(18, transport.writtenBytes.size)
        assertEquals(2, harness.queue.readIndex)
        harness.render()
        assertArrayEquals(stereoDop + stereoDop, transport.writtenBytes)
        assertTrue(harness.renderer.isEnded)
        assertEquals(listOf(0, 6, 0, 6), transport.writes.map { it.packetOffset })
        assertEquals(listOf(100L, 100L, 200L, 200L), transport.writes.map { it.presentationTimeUs })
    }

    @Test
    fun seekFlushesPendingPacketAndRestartsDopMarkerAt05() {
        val transport = InMemoryRawDsdTransport().apply { acceptingWrites = false }
        val format = rawFormat(NativeDsdPacking.DSD_U32_BE).buildUpon().setEncoderPadding(16).build()
        val harness = Harness(transport, dopPlan(), inputPacking = NativeDsdPacking.DSD_U32_BE, format = format)
        val input = NativeDsdPacker.pack(stereoDsd, 2, NativeDsdPacking.DSD_U32_BE)
        harness.queueSample(input, timeUs = 0)
        harness.finish()
        harness.render()
        val flushesBeforeSeek = transport.flushCount

        harness.reset(positionUs = 500)
        assertEquals(flushesBeforeSeek + 1, transport.flushCount)
        assertEquals(0, transport.finishCount)
        transport.acceptingWrites = true
        harness.queueSample(input, timeUs = 500)
        harness.finish()
        harness.render()

        assertArrayEquals(stereoDop.copyOfRange(0, 6), transport.writtenBytes)
        assertEquals(500L, transport.writes.single().presentationTimeUs)
        assertEquals(16, transport.writes.single().validDsdSamples)
        assertTrue(harness.renderer.isEnded)
    }

    @Test
    fun endOfStreamWaitsForFinalPendingPacketAndFinalPaddingIsExplicit() {
        val transport = InMemoryRawDsdTransport().apply { acceptingWrites = false }
        val format = rawFormat(NativeDsdPacking.DSD_U32_BE).buildUpon().setEncoderPadding(16).build()
        val harness = Harness(transport, dopPlan(), inputPacking = NativeDsdPacking.DSD_U32_BE, format = format)
        val input = NativeDsdPacker.pack(stereoDsd, 2, NativeDsdPacking.DSD_U32_BE)
        harness.queueSample(input, timeUs = 42)
        harness.finish()
        harness.render()
        assertFalse(harness.renderer.isEnded)
        assertEquals(0, transport.finishCount)

        transport.acceptingWrites = true
        harness.render()
        assertTrue(harness.renderer.isEnded)
        assertArrayEquals(stereoDop.copyOfRange(0, 6), transport.writtenBytes)
        assertEquals(16, transport.writes.single().validDsdSamples)
        assertEquals(16, transport.configurations.single().finalPaddingSamples)
        assertEquals(1, transport.finishCount)
        harness.render()
        assertEquals(1, transport.finishCount)
    }

    @Test
    fun disableResetAndReleaseFlushAndCloseTransportAndClockUsesTransportPosition() {
        val transport = InMemoryRawDsdTransport()
        val harness = Harness(transport, dopPlan(), inputPacking = NativeDsdPacking.DSD_U32_BE)
        val input = NativeDsdPacker.pack(stereoDsd, 2, NativeDsdPacking.DSD_U32_BE)
        harness.queueSample(input, timeUs = 1234)
        harness.render()
        transport.reportedPositionUs = 999
        assertEquals(999L, harness.renderer.positionUs)
        harness.renderer.setPlaybackParameters(PlaybackParameters(2f))
        assertSame(PlaybackParameters.DEFAULT, harness.renderer.playbackParameters)

        harness.renderer.stop()
        val beforeDisable = transport.flushCount
        harness.renderer.disable()
        assertEquals(beforeDisable + 1, transport.flushCount)
        harness.renderer.reset()
        assertEquals(beforeDisable + 2, transport.flushCount)
        harness.renderer.release()
        harness.renderer.release()
        assertEquals(1, transport.releaseCount)
        assertTrue(transport.released)
        assertEquals(0, transport.finishCount)
    }

    @Test
    fun formatSupportRequiresRawDsdAndDeclaredPlanCapabilities() {
        val nativeOnly = InMemoryRawDsdTransport(
            RawDsdTransportCapabilities(
                supportedKinds = setOf(DsdTransportKind.NATIVE),
                supportedNativePackings = setOf(NativeDsdPacking.DSD_U32_BE),
            ),
        )
        val renderer = RawDsdRenderer(nativeOnly, dopPlan())
        assertEquals(
            C.FORMAT_UNSUPPORTED_SUBTYPE,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(rawFormat(NativeDsdPacking.DSD_U32_BE))),
        )
        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(Format.Builder().setSampleMimeType("audio/raw").build())),
        )
        val pcmFormat = rawFormat(NativeDsdPacking.DSD_U32_BE).buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build()
        val dopRenderer = RawDsdRenderer(InMemoryRawDsdTransport(), dopPlan())
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(dopRenderer.supportsFormat(pcmFormat)))
    }

    private class Harness(
        val transport: InMemoryRawDsdTransport,
        plan: DsdTransportPlan,
        inputPacking: NativeDsdPacking,
        format: Format = rawFormat(inputPacking),
    ) {
        val renderer = RawDsdRenderer(transport, plan)
        private val sampleQueue = SampleQueue.createWithoutDrm(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
        private var loadingFinished = false
        private val stream = object : SampleStream {
            override fun isReady(): Boolean = sampleQueue.isReady(loadingFinished)
            override fun maybeThrowError() = sampleQueue.maybeThrowError()
            override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
                sampleQueue.read(holder, buffer, readFlags, loadingFinished)

            override fun skipData(positionUs: Long): Int =
                sampleQueue.getSkipCount(positionUs, true).also(sampleQueue::skip)
        }

        val queue: SampleQueue get() = sampleQueue

        init {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false)
            sampleQueue.format(format)
            renderer.init(0, PlayerId.UNSET, Clock.DEFAULT)
            renderer.enable(
                RendererConfiguration.DEFAULT,
                arrayOf(format),
                stream,
                0,
                false,
                true,
                0,
                0,
                MediaSource.MediaPeriodId("raw-dsd"),
            )
            renderer.start()
        }

        fun queueSample(data: ByteArray, timeUs: Long) {
            check(!loadingFinished)
            sampleQueue.sampleData(ParsableByteArray(data), data.size)
            sampleQueue.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, data.size, 0, null)
        }

        fun finish() {
            loadingFinished = true
            renderer.setCurrentStreamFinal()
        }

        fun render() = renderer.render(0, 0)

        fun reset(positionUs: Long) {
            loadingFinished = false
            sampleQueue.reset()
            sampleQueue.format(requireNotNull(sampleQueue.upstreamFormat))
            renderer.resetPosition(positionUs, true)
        }
    }

    private companion object {
        fun rawFormat(inputPacking: NativeDsdPacking): Format {
            val codec = when (inputPacking) {
                NativeDsdPacking.DSD_U32_BE -> RawDsdPacking.DSD_U32_BE.codec
                NativeDsdPacking.DSD_U32_LE -> RawDsdPacking.DSD_U32_LE.codec
                else -> throw IllegalArgumentException("Tests use U32 extractor packets.")
            }
            return Format.Builder()
                .setSampleMimeType(RawDsdConstants.MIME_DSD)
                .setCodecs(codec)
                .setSampleRate(RawDsdConstants.DSD64_RATE)
                .setChannelCount(2)
                .setEncoderDelay(0)
                .setEncoderPadding(0)
                .build()
        }

        fun dopPlan() = DsdTransportPlan(DsdTransportKind.DOP, reason = "test DoP")

        fun nativePlan(packing: NativeDsdPacking) = DsdTransportPlan(
            DsdTransportKind.NATIVE,
            packing = packing,
            rawAlternateSetting = 2,
            reason = "test native",
        )

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}
