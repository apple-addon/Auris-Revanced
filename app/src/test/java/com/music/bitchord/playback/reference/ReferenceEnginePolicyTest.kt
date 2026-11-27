package com.music.bitchord.playback.reference

import com.music.bitchord.data.settings.ReferenceEngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceEnginePolicyTest {
    @Test
    fun `quality options off never claims native DSP`() {
        val snapshot = ReferenceEngineSnapshot(
            hiby = loadedHiby(),
            hibyOutput = activeStream(),
        )
        assertEquals(
            "Quality Options OFF",
            referenceTelemetrySummary(false, ReferenceEngineMode.HIBY, snapshot),
        )
    }

    @Test
    fun `HiBy active requires both loaded engine and positive PCM evidence`() {
        val ready = ReferenceEngineSnapshot(hiby = loadedHiby())
        assertEquals("HIBY NATIVE READY", referenceTelemetrySummary(true, ReferenceEngineMode.HIBY, ready))
        assertEquals(
            "HIBY OUTPUT READY / WAITING FOR PCM",
            referenceTelemetrySummary(
                true,
                ReferenceEngineMode.HIBY,
                ready.copy(hibyOutput = NativeStreamStatus(NativeStreamState.OPEN)),
            ),
        )
        assertEquals(
            "HIBY NATIVE ACTIVE",
            referenceTelemetrySummary(true, ReferenceEngineMode.HIBY, ready.copy(hibyOutput = activeStream())),
        )
        val zeroFrames = ready.copy(
            hibyOutput = NativeStreamStatus(NativeStreamState.ACTIVE, producedFrames = 0),
        )
        assertFalse(zeroFrames.hibyOutputActive)
        assertEquals("HIBY NATIVE READY", referenceTelemetrySummary(true, ReferenceEngineMode.HIBY, zeroFrames))
    }

    @Test
    fun `load error survives verbatim`() {
        val snapshot = ReferenceEngineSnapshot(
            hiby = NativeEngineStatus(NativeLoadState.FAILED, "UnsatisfiedLinkError: dlopen failed: libusb.so"),
        )
        assertEquals(
            "INIT FAILED: UnsatisfiedLinkError: dlopen failed: libusb.so",
            referenceTelemetrySummary(true, ReferenceEngineMode.HIBY, snapshot),
        )
    }

    @Test
    fun `PAWS open primes silently and first PCM frame activates`() {
        val snapshot = ReferenceEngineSnapshot(paws = loadedPaws())
        assertEquals("PAWS NATIVE READY", referenceTelemetrySummary(true, ReferenceEngineMode.POWERAMP, snapshot))
        assertEquals(
            "PAWS GRAPH READY / WAITING FOR PCM",
            referenceTelemetrySummary(
                true,
                ReferenceEngineMode.POWERAMP,
                snapshot.copy(pawsOutput = NativeStreamStatus(NativeStreamState.OPEN)),
            ),
        )
        assertEquals(
            "PAWS NATIVE ACTIVE",
            referenceTelemetrySummary(true, ReferenceEngineMode.POWERAMP, snapshot.copy(pawsOutput = activeStream())),
        )
    }

    @Test
    fun `PCM failure outranks stale active evidence`() {
        val snapshot = ReferenceEngineSnapshot(
            paws = loadedPaws(),
            pawsOutput = NativeStreamStatus(NativeStreamState.FAILED, "output.cc stalled", producedFrames = 48),
        )
        assertEquals(
            "PCM FAILED: output.cc stalled",
            referenceTelemetrySummary(true, ReferenceEngineMode.POWERAMP, snapshot),
        )
    }

    @Test
    fun `hybrid active requires positive evidence from both native stages`() {
        val loaded = ReferenceEngineSnapshot(paws = loadedPaws(), hiby = loadedHiby())
        assertEquals("HYBRID NATIVE READY", referenceTelemetrySummary(true, ReferenceEngineMode.HYBRID, loaded))
        assertEquals(
            "HYBRID NATIVE READY",
            referenceTelemetrySummary(true, ReferenceEngineMode.HYBRID, loaded.copy(pawsOutput = activeStream())),
        )
        assertEquals(
            "HYBRID NATIVE READY",
            referenceTelemetrySummary(true, ReferenceEngineMode.HYBRID, loaded.copy(hibyOutput = activeStream())),
        )
        val active = loaded.copy(pawsOutput = activeStream(), hibyOutput = activeStream())
        assertTrue(active.pawsOutputActive && active.hibyOutputActive)
        assertEquals("HYBRID NATIVE ACTIVE", referenceTelemetrySummary(true, ReferenceEngineMode.HYBRID, active))

        val failed = active.copy(
            hibyOutput = NativeStreamStatus(NativeStreamState.FAILED, "write=-1", producedFrames = 48),
        )
        val summary = referenceTelemetrySummary(true, ReferenceEngineMode.HYBRID, failed)
        assertEquals("PCM FAILED: write=-1", summary)
        assertFalse(summary.contains("HYBRID NATIVE ACTIVE"))
    }

    @Test
    fun `bit perfect is explicit`() {
        assertEquals(
            "BIT-PERFECT DIRECT",
            referenceTelemetrySummary(true, ReferenceEngineMode.BIT_PERFECT, ReferenceEngineSnapshot()),
        )
    }

    @Test
    fun `MSEB keys preserve native HiBy order`() {
        assertEquals(
            listOf(
                "mseb_master_temp", "mseb_bass0", "mseb_bass1", "mseb_thickness", "mseb_vocal", "mseb_female",
                "mseb_male_vocal", "mseb_female_vocal", "mseb_instruments", "mseb_air",
            ),
            HibyHostRuntime.MSEB_KEYS,
        )
    }

    private fun loadedPaws() = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded")
    private fun loadedHiby() = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded")
    private fun activeStream() = NativeStreamStatus(NativeStreamState.ACTIVE, producedFrames = 48)
}

class HibyPcmContractTest {
    @Test
    fun `full write consumes every byte`() {
        assertEquals(4096, HibyPcmContract.consumedBytes(4096, 0))
    }

    @Test
    fun `backpressure retains exact unconsumed slice`() {
        assertEquals(1536, HibyPcmContract.consumedBytes(4096, 2560))
        assertEquals(0, HibyPcmContract.consumedBytes(4096, 4096))
    }

    @Test
    fun `native failure consumes nothing`() {
        assertEquals(0, HibyPcmContract.consumedBytes(4096, -1))
    }

    @Test
    fun `clock uses emitted sample domain`() {
        assertEquals(1_000_000L, HibyPcmContract.framesToUs(48_000, 48_000))
        assertEquals(500_000L, HibyPcmContract.framesToUs(44_100, 88_200))
        assertEquals(0L, HibyPcmContract.framesToUs(-10, 48_000))
    }
}
