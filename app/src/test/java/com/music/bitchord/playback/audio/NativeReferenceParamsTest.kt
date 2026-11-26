package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-level checks for the 94-float native ABI packing: MSEB raw sliders
 * are normalized exactly once, range stays verbatim metadata, and PEQ bands
 * land in their stride-five slots using the native BiquadType enum order.
 */
class NativeReferenceParamsTest {

    @Test
    fun `mseb sliders are normalized exactly once and range stays verbatim metadata`() {
        val values = floatArrayOf(-100f, -50f, -25f, 0f, 25f, 50f, 75f, 100f, 12f, -12f)

        val packed = packNativeReferenceSettings(
            baseSettings(msebValues = values, msebRange = 40, msebPreampDb = -6f),
        )

        assertEquals(AurisDspNative.PARAM_COUNT, packed.size)
        assertEquals(2f, packed[0]) // Mode.HIBY
        assertEquals(1f, packed[1]) // MSEB enabled
        assertEquals(-6f, packed[2]) // preamp
        assertEquals(40f, packed[3]) // range metadata, never rescaled
        for (index in 0 until 10) {
            assertEquals(values[index] / 100f, packed[4 + index])
        }
        assertEquals(-1f, packed[4])
        assertEquals(1f, packed[11])
    }

    @Test
    fun `codec mseb result reaches the abi as normalized raw sliders with range intact`() {
        val uri = HiByMsebV1Codec.URI_PREFIX +
            """{"datalist":[0,0,0,0,0,0,0,0,0,0],"range":40,"result":[-100,-50,-25,0,25,50,75,100,12,-12]}"""
        val data = HiByMsebV1Codec.decode(uri)

        val packed = packNativeReferenceSettings(
            baseSettings(
                msebValues = FloatArray(10) { data.result[it].toFloat() },
                msebRange = data.range,
            ),
        )

        assertEquals(data.range.toFloat(), packed[3])
        for (index in 0 until 10) {
            assertEquals(data.result[index].toFloat() / 100f, packed[4 + index])
        }
    }

    @Test
    fun `peq bands pack into stride-five slots in native enum order`() {
        val bands = listOf(
            PeqBand(650f, 0.4041f, 12f, BiquadKind.PEAKING, enabled = true),
            PeqBand(70f, 0.7289f, -6f, BiquadKind.LOW_SHELF, enabled = true),
            PeqBand(10000f, 0.8571f, 3f, BiquadKind.HIGH_SHELF, enabled = false),
        )

        val packed = packNativeReferenceSettings(
            baseSettings(peqEnabled = true, peqBands = bands, peqPreampDb = -3f),
        )

        assertEquals(1f, packed[14])
        assertEquals(-3f, packed[15])
        val first = 16
        assertEquals(1f, packed[first]) // enabled flag
        assertEquals(0f, packed[first + 1]) // PEAKING == 0 in the native enum
        assertEquals(650f, packed[first + 2])
        assertEquals(0.4041f, packed[first + 3])
        assertEquals(12f, packed[first + 4])
        val second = 16 + 5
        assertEquals(1f, packed[second])
        assertEquals(1f, packed[second + 1]) // LOW_SHELF == 1
        assertEquals(70f, packed[second + 2])
        val third = 16 + 10
        assertEquals(0f, packed[third]) // disabled band
        assertEquals(2f, packed[third + 1]) // HIGH_SHELF == 2
        assertEquals(10000f, packed[third + 2])
        // Untouched bands fall back to the disabled defaults.
        assertEquals(0f, packed[16 + 15])
        assertEquals(16_000f, packed[16 + 9 * 5 + 2])
    }

    @Test
    fun `hiby mode packs mseb and peq enable flags independently`() {
        val packed = packNativeReferenceSettings(
            baseSettings(msebEnabled = false, peqEnabled = true, msebPreampDb = -9f),
        )

        assertEquals(0f, packed[1])
        assertEquals(-9f, packed[2]) // preamp is packed even when the stage is off
        assertEquals(1f, packed[14])
    }

    @Test
    fun `bit-perfect and disabled quality options pack an exact all-zero snapshot`() {
        val off = packNativeReferenceSettings(
            baseSettings(qualityOptions = false, msebEnabled = true),
        )
        val bitPerfect = packNativeReferenceSettings(
            baseSettings(bitPerfect = true, msebEnabled = true),
        )
        val mode = packNativeReferenceSettings(
            baseSettings(mode = ReferenceMode.BIT_PERFECT, msebEnabled = true),
        )

        assertEquals(AurisDspNative.PARAM_COUNT, off.size)
        assertTrue(off.all { it == 0f })
        assertTrue(bitPerfect.all { it == 0f })
        assertTrue(mode.all { it == 0f })
    }

    // ── DVC compensation (compensate_dvc_vol) ─────────────────────────────

    @Test
    fun `no compensation keeps the stock packing`() {
        val packed = packNativeReferenceSettings(
            baseSettings(mode = ReferenceMode.POWERAMP).copy(dvcEnabled = true, dvcGainDb = -6f, headroomDb = -6f),
        )
        assertEquals(1f, packed[92]) // DVC stage on
        assertEquals(-12f, packed[93], 0.0001f) // dvcGainDb + headroom
        assertEquals(0f, packed[81]) // limiter left as configured (off here)
        assertEquals(-1f, packed[82], 0.0001f)
    }

    @Test
    fun `compensation while DVC off keeps the gain stage on and never clips silently`() {
        val packed = packNativeReferenceSettings(
            baseSettings(mode = ReferenceMode.POWERAMP).copy(dvcEnabled = false, dvcCompensateDb = 6f),
        )
        assertEquals(1f, packed[92]) // gain stage stays on so the boost is applied
        // Volume offset is NOT applied (DVC off); only the bounded boost is.
        assertEquals(6f, packed[93], 0.0001f)
        // Limiter forced on, ceiling lowered so ceiling + compensate <= -0.3 dB.
        assertEquals(1f, packed[81])
        assertEquals(-6.3f, packed[82], 0.0001f)
        assertTrue(packed[82] + packed[93] <= -0.3f + 1e-4f)
    }

    @Test
    fun `compensation is clamped to the hard bound and never negative`() {
        val packed = packNativeReferenceSettings(
            baseSettings(mode = ReferenceMode.POWERAMP).copy(dvcEnabled = false, dvcCompensateDb = 99f),
        )
        assertEquals(DVC_COMPENSATE_MAX_DB, packed[93], 0.0001f)
        assertEquals(1f, packed[81])
        assertTrue(packed[82] >= -24f)

        val negative = packNativeReferenceSettings(
            baseSettings(mode = ReferenceMode.POWERAMP).copy(dvcEnabled = false, dvcCompensateDb = -50f),
        )
        assertEquals(0f, negative[93], 0.0001f)
        assertEquals(0f, negative[81])
    }

    @Test
    fun `compensation composes with the volume offset only when DVC is on`() {
        val packed = packNativeReferenceSettings(
            baseSettings(mode = ReferenceMode.POWERAMP).copy(dvcEnabled = true, dvcGainDb = -6f, headroomDb = -6f, dvcCompensateDb = 3f),
        )
        assertEquals(-6f + -6f + 3f, packed[93], 0.0001f)
    }

    private fun baseSettings(
        qualityOptions: Boolean = true,
        bitPerfect: Boolean = false,
        mode: ReferenceMode = ReferenceMode.HIBY,
        msebEnabled: Boolean = true,
        msebValues: FloatArray = FloatArray(10),
        msebPreampDb: Float = 0f,
        msebRange: Int = 100,
        peqEnabled: Boolean = false,
        peqBands: List<PeqBand> = emptyList(),
        peqPreampDb: Float = 0f,
    ) = NativeReferenceSettings(
        qualityOptions = qualityOptions,
        bitPerfect = bitPerfect,
        mode = mode,
        msebEnabled = msebEnabled,
        msebValues = msebValues,
        msebPreampDb = msebPreampDb,
        msebRange = msebRange,
        peqEnabled = peqEnabled,
        peqBands = peqBands,
        peqPreampDb = peqPreampDb,
        powerampEqEnabled = false,
        powerampPreampDb = 0f,
        powerampGainsDb = FloatArray(10),
        toneEnabled = false,
        bassDb = 0f,
        trebleDb = 0f,
        limiterEnabled = false,
        reverb = ReverbTuning(),
        dvcGainDb = 0f,
    )
}
