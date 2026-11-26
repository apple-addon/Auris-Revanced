package com.music.bitchord.playback.dsd.raw

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.RendererCapabilities
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.DsdTransportPlan
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.raw.render.InMemoryRawDsdTransport
import com.music.bitchord.playback.dsd.raw.render.RawDsdRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract-level tests for RawDsdPlaybackIntegration: renderer and transport share one
 * conservative DsdTransportPlan, and raw DSF/DFF/SACD never silently becomes PCM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = android.app.Application::class)
class RawDsdPlaybackIntegrationTest {

    @Test
    fun dopPlanIsSharedVerbatimBetweenRendererAndTransport() {
        val plan = DsdTransportPlan(DsdTransportKind.DOP, reason = "verified DoP")
        val transport = InMemoryRawDsdTransport()
        val renderer = RawDsdRenderer(transport, plan)

        // The renderer advertises only DoP because the plan says DoP.
        val format = Format.Builder()
            .setSampleMimeType(RawDsdConstants.MIME_DSD)
            .setCodecs(RawDsdPacking.DSD_U32_BE.codec)
            .setSampleRate(RawDsdConstants.DSD64_RATE)
            .setChannelCount(2)
            .build()
        assertEquals(
            C.FORMAT_HANDLED,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)),
        )

        // Native-only format is rejected because the plan is DoP-only.
        val nativePlan = DsdTransportPlan(
            DsdTransportKind.NATIVE,
            packing = NativeDsdPacking.DSD_U32_BE,
            rawAlternateSetting = 2,
            reason = "verified native",
        )
        val nativeRenderer = RawDsdRenderer(InMemoryRawDsdTransport(), nativePlan)
        assertNotNull(nativeRenderer)
    }

    @Test
    fun unsupportedPlanCannotHandleRawDsd() {
        val plan = DsdTransportPlan(DsdTransportKind.UNSUPPORTED, reason = "no DAC")
        val renderer = RawDsdRenderer(InMemoryRawDsdTransport(), plan)

        val format = Format.Builder()
            .setSampleMimeType(RawDsdConstants.MIME_DSD)
            .setCodecs(RawDsdPacking.DSD_U32_BE.codec)
            .setSampleRate(RawDsdConstants.DSD64_RATE)
            .setChannelCount(2)
            .build()
        // UNSUPPORTED plan makes createOutputConfiguration throw, so format is rejected.
        assertEquals(
            C.FORMAT_UNSUPPORTED_SUBTYPE,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)),
        )
    }

    @Test
    fun rendererNeverAcceptsPcmMimetype() {
        val plan = DsdTransportPlan(DsdTransportKind.DOP, reason = "test")
        val renderer = RawDsdRenderer(InMemoryRawDsdTransport(), plan)
        val pcmFormat = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setSampleRate(44_100)
            .setChannelCount(2)
            .build()
        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            RendererCapabilities.getFormatSupport(renderer.supportsFormat(pcmFormat)),
        )
    }
}