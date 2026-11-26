package com.music.bitchord.playback.reference

import com.music.bitchord.data.settings.ReferenceEngineMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceEnginePolicyTest {
    @Test
    fun `quality options off never claims native DSP`() {
        val snapshot = ReferenceEngineSnapshot(
            hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
            hibyOutputActive = true,
        )
        assertEquals(
            "Quality Options OFF",
            referenceTelemetrySummary(false, ReferenceEngineMode.HIBY, snapshot),
        )
    }

    @Test
    fun `HiBy active requires both loaded engine and live output`() {
        val ready = ReferenceEngineSnapshot(
            hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
        )
        assertEquals("HIBY NATIVE READY", referenceTelemetrySummary(true, ReferenceEngineMode.HIBY, ready))
        assertEquals(
            "HIBY NATIVE ACTIVE",
            referenceTelemetrySummary(true, ReferenceEngineMode.HIBY, ready.copy(hibyOutputActive = true)),
        )
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
    fun `PAWS active requires a live native output`() {
        val snapshot = ReferenceEngineSnapshot(
            paws = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
        )
        assertEquals("PAWS NATIVE READY", referenceTelemetrySummary(true, ReferenceEngineMode.POWERAMP, snapshot))
        assertEquals(
            "PAWS NATIVE ACTIVE",
            referenceTelemetrySummary(true, ReferenceEngineMode.POWERAMP, snapshot.copy(pawsOutputActive = true)),
        )
    }

    @Test
    fun `hybrid never fakes a native chain without the PAWS transform`() {
        val snapshot = ReferenceEngineSnapshot(
            paws = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
            hiby = NativeEngineStatus(NativeLoadState.LOADED_ACTIVE, "loaded"),
            hibyOutputActive = true,
        )
        val summary = referenceTelemetrySummary(true, ReferenceEngineMode.HYBRID, snapshot)
        assert(summary.startsWith("INIT FAILED: Hybrid"))
        assert(!summary.contains("HYBRID NATIVE ACTIVE"))
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
                "master_temp", "bass0", "bass1", "thickness", "vocal", "female",
                "male_vocal", "female_vocal", "instruments", "air",
            ),
            HibyHostRuntime.MSEB_KEYS,
        )
    }
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
