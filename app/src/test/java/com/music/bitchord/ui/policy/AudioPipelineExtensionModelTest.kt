package com.music.bitchord.ui.policy

import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.AudioFidelity
import com.music.bitchord.playback.audio.drivers.OutputDriverTelemetry
import com.music.bitchord.playback.output.NativeOutputSharing
import com.music.bitchord.playback.output.NativeOutputTelemetry
import com.music.bitchord.playback.reference.NativeEngineStatus
import com.music.bitchord.playback.reference.NativeLoadState
import com.music.bitchord.playback.reference.NativeStreamState
import com.music.bitchord.playback.reference.NativeStreamStatus
import com.music.bitchord.playback.reference.ReferenceEngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineExtensionModelTest {
    @Test
    fun `Quality Options off cannot claim loaded engines are active`() {
        val model = buildAudioPipelineExtension(
            qualityOptions = false,
            mode = ReferenceEngineMode.HIBY,
            engines = ReferenceEngineSnapshot(
                paws = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
                hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
                hibyOutput = activeStream(),
            ),
        )

        assertEquals("Quality Options OFF", model.summary)
        assertEquals(AudioPipelineStatus.BYPASSED, model.summaryStatus)
        assertTrue(model.badges.all { it.status == AudioPipelineStatus.BYPASSED })
        assertFalse(model.badges.any { it.status == AudioPipelineStatus.ACTIVE })
        assertEquals(AudioPipelineStatus.BYPASSED, model.timeline.first().status)
    }

    @Test
    fun `native load failure survives verbatim in every relevant surface`() {
        val reason = "UnsatisfiedLinkError: dlopen failed: libusb.so"
        val expected = "INIT FAILED: $reason"
        val model = buildAudioPipelineExtension(
            qualityOptions = true,
            mode = ReferenceEngineMode.HIBY,
            engines = ReferenceEngineSnapshot(
                hiby = NativeEngineStatus(NativeLoadState.FAILED, reason),
            ),
            fidelityDeductions = listOf(AudioFidelity.Deduction("System mixer is in the path", 12)),
        )

        assertEquals(expected, model.summary)
        assertEquals(AudioPipelineStatus.FAILED, model.summaryStatus)
        val hiby = model.badges.single { it.owner == NativeEngineBadgeOwner.HIBY }
        assertEquals(expected, hiby.label)
        assertEquals(reason, hiby.detail)
        assertEquals(AudioPipelineStatus.FAILED, hiby.status)
        assertEquals(expected, model.timeline.first().detail)
        assertEquals(AudioPipelineStatus.FAILED, model.timeline.first().status)
        assertEquals(12, model.deductions.first().points)
        assertEquals("System mixer is in the path", model.deductions.first().label)
        assertTrue(model.deductions.any { it.label == expected && it.points == null })
    }

    @Test
    fun `PAWS active badge requires real PCM evidence`() {
        val engines = ReferenceEngineSnapshot(
            paws = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "libauriscore.so loaded"),
        )
        val ready = buildAudioPipelineExtension(true, ReferenceEngineMode.POWERAMP, engines)
        val readyPaws = ready.badges.single { it.owner == NativeEngineBadgeOwner.PAWS }
        assertEquals("PAWS NATIVE READY", ready.summary)
        assertEquals("PAWS NATIVE READY", readyPaws.label)
        assertEquals(AudioPipelineStatus.READY, readyPaws.status)
        assertEquals(AudioPipelineStatus.READY, ready.summaryStatus)

        val active = buildAudioPipelineExtension(
            true,
            ReferenceEngineMode.POWERAMP,
            engines.copy(pawsOutput = activeStream()),
        )
        val activePaws = active.badges.single { it.owner == NativeEngineBadgeOwner.PAWS }
        assertEquals("PAWS NATIVE ACTIVE", active.summary)
        assertEquals("PAWS NATIVE ACTIVE", activePaws.label)
        assertEquals(AudioPipelineStatus.ACTIVE, activePaws.status)
        assertEquals(AudioPipelineStatus.ACTIVE, active.summaryStatus)
    }

    @Test
    fun `HiBy active requires loaded engine and live output`() {
        val loaded = ReferenceEngineSnapshot(
            hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
        )
        val ready = buildAudioPipelineExtension(true, ReferenceEngineMode.HIBY, loaded)
        val active = buildAudioPipelineExtension(
            true,
            ReferenceEngineMode.HIBY,
            loaded.copy(hibyOutput = activeStream()),
        )

        assertEquals("HIBY NATIVE READY", ready.summary)
        assertEquals(AudioPipelineStatus.READY, ready.summaryStatus)
        assertEquals("HIBY NATIVE ACTIVE", active.summary)
        assertEquals(AudioPipelineStatus.ACTIVE, active.summaryStatus)
        assertEquals(AudioPipelineStatus.ACTIVE, active.timeline.first().status)

        val failed = buildAudioPipelineExtension(
            true,
            ReferenceEngineMode.HIBY,
            ReferenceEngineSnapshot(
                hiby = NativeEngineStatus(NativeLoadState.FAILED, "open failed"),
                hibyOutput = activeStream(),
            ),
        )
        assertFalse(failed.badges.any { it.status == AudioPipelineStatus.ACTIVE })
        assertEquals("INIT FAILED: open failed", failed.summary)
    }

    @Test
    fun `hybrid active requires both native stages`() {
        val engines = ReferenceEngineSnapshot(
            paws = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
            hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
            hibyOutput = activeStream(),
        )
        val partial = buildAudioPipelineExtension(true, ReferenceEngineMode.HYBRID, engines)
        assertEquals("HYBRID NATIVE READY", partial.summary)
        assertEquals(AudioPipelineStatus.READY, partial.summaryStatus)
        assertFalse(partial.summary.contains("HYBRID NATIVE ACTIVE"))

        val active = buildAudioPipelineExtension(
            true,
            ReferenceEngineMode.HYBRID,
            engines.copy(pawsOutput = activeStream()),
        )
        assertEquals("HYBRID NATIVE ACTIVE", active.summary)
        assertEquals(AudioPipelineStatus.ACTIVE, active.summaryStatus)
        assertTrue(active.badges.all { it.status == AudioPipelineStatus.ACTIVE })
    }

    @Test
    fun `driver failure is exact and unknown xruns are not reported as zero`() {
        val failure = "INIT FAILED: AAudio open returned -895"
        val model = buildAudioPipelineExtension(
            qualityOptions = false,
            mode = ReferenceEngineMode.POWERAMP,
            engines = ReferenceEngineSnapshot(
                driverTelemetry = OutputDriverTelemetry(
                    driver = "AAudio",
                    sharing = "Exclusive / MMAP",
                    sampleRateHz = 48_000,
                    bitDepth = 32,
                    bufferMs = 10,
                    xruns = null,
                    detail = failure,
                ),
            ),
        )
        val output = model.output!!

        assertEquals("AAudio", output.driverText)
        assertEquals("Exclusive / MMAP", output.sharingText)
        assertEquals("48 kHz", output.sampleRateText)
        assertEquals("32-bit", output.bitsText)
        assertEquals("Not reported", output.xrunsText)
        assertNull(output.xruns)
        assertEquals(AudioPipelineOutputHealth.FAILED, output.health)
        assertEquals(failure, output.failure)
        assertEquals(failure, model.timeline.last().detail)
        assertEquals(AudioPipelineStatus.FAILED, model.timeline.last().status)
        assertTrue(model.deductions.any { it.label == failure })
    }

    @Test
    fun `native output preserves zero xruns and exact runtime failure`() {
        val native = NativeOutputTelemetry(
            driverName = "AAudio",
            sharing = NativeOutputSharing.EXCLUSIVE,
            sampleRateHz = 96_000,
            channelCount = 2,
            bitsPerSample = 32,
            bufferSizeFrames = 480,
            bufferCapacityFrames = 960,
            xruns = 0,
            outputFrames = 1_024,
            positionUs = 10_000,
            audioSessionId = 7,
            failure = "route disconnected",
            released = false,
        )
        val output = native.toAudioPipelineOutput()

        assertEquals("Exclusive", output.sharingText)
        assertEquals("96 kHz", output.sampleRateText)
        assertEquals("32-bit", output.bitsText)
        assertEquals("0", output.xrunsText)
        assertEquals(480, output.bufferSizeFrames)
        assertEquals("route disconnected", output.failure)
        assertEquals(AudioPipelineOutputHealth.FAILED, output.health)
    }

    @Test
    fun `released output and unknown facts stay honest`() {
        val output = NativeOutputTelemetry(
            driverName = "OpenSL ES",
            sharing = NativeOutputSharing.UNKNOWN,
            sampleRateHz = null,
            channelCount = null,
            bitsPerSample = null,
            bufferSizeFrames = null,
            bufferCapacityFrames = null,
            xruns = null,
            outputFrames = null,
            positionUs = null,
            audioSessionId = null,
            failure = null,
            released = true,
        ).toAudioPipelineOutput()

        assertEquals(AudioPipelineOutputHealth.RELEASED, output.health)
        assertEquals("Not reported", output.sharingText)
        assertEquals("Not reported", output.sampleRateText)
        assertEquals("Not reported", output.bitsText)
        assertEquals("Not reported", output.xrunsText)
        assertNull(output.failure)
    }

    @Test
    fun `HiBy output failure is not hidden by engine ready state`() {
        val failure = "INIT FAILED: OtHiByTrackUtils.createTrack=-2"
        val model = buildAudioPipelineExtension(
            qualityOptions = true,
            mode = ReferenceEngineMode.HIBY,
            engines = ReferenceEngineSnapshot(
                hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
                hibyOutput = NativeStreamStatus(NativeStreamState.FAILED, failure),
            ),
        )

        assertEquals(failure, model.summary)
        assertEquals(AudioPipelineStatus.FAILED, model.summaryStatus)
        assertEquals(failure, model.timeline.first().detail)
        assertEquals(failure, model.timeline.last().detail)
        assertEquals(AudioPipelineStatus.FAILED, model.timeline.last().status)
        assertTrue(model.deductions.any { it.label == failure && it.points == null })
    }

    @Test
    fun `rate labels do not round source rates`() {
        assertEquals("44.1 kHz", pipelineRateText(44_100))
        assertEquals("88.2 kHz", pipelineRateText(88_200))
        assertEquals("176.4 kHz", pipelineRateText(176_400))
        assertEquals("48 kHz", pipelineRateText(48_000))
    }

    private fun activeStream() = NativeStreamStatus(NativeStreamState.ACTIVE, producedFrames = 48)
}
