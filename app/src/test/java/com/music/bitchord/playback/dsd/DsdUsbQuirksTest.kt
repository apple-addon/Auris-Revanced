package com.music.bitchord.playback.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DsdUsbQuirksTest {
    @Test
    fun verifiedBigEndianQuirkSelectsNativeRawAlt() {
        val plan = DsdUsbQuirks.select(0x20b1, 0x3008, rawAlt(alternate = 2), doPAvailable = true)
        assertEquals(DsdTransportKind.NATIVE, plan.kind)
        assertEquals(NativeDsdPacking.DSD_U32_BE, plan.packing)
        assertEquals(2, plan.rawAlternateSetting)
    }

    @Test
    fun verifiedLittleEndianQuirkSelectsNative() {
        val plan = DsdUsbQuirks.select(0x20b1, 0x2009, rawAlt(alternate = 3), doPAvailable = false)
        assertEquals(DsdTransportKind.NATIVE, plan.kind)
        assertEquals(NativeDsdPacking.DSD_U32_LE, plan.packing)
        assertEquals(3, plan.rawAlternateSetting)
    }

    @Test
    fun unknownDeviceNeverGuessesRawByteOrder() {
        val plan = DsdUsbQuirks.select(0x1234, 0x5678, rawAlt(), doPAvailable = true)
        assertEquals(DsdTransportKind.DOP, plan.kind)
        assertNull(plan.packing)
        assertNull(plan.rawAlternateSetting)
    }

    @Test
    fun unknownDeviceWithoutVerifiedDopIsUnsupported() {
        val plan = DsdUsbQuirks.select(0x1234, 0x5678, rawAlt(), doPAvailable = false)
        assertEquals(DsdTransportKind.UNSUPPORTED, plan.kind)
    }

    @Test
    fun mismatchedAltOrSlotFallsBackToDop() {
        val wrongAlt = DsdUsbQuirks.select(0x20b1, 0x3008, rawAlt(alternate = 3), doPAvailable = true)
        assertEquals(DsdTransportKind.DOP, wrongAlt.kind)
        val wrongSlot = DsdUsbQuirks.select(
            0x20b1,
            0x2009,
            rawAlt(alternate = 2, subslotBytes = 2, bitResolution = 16),
            doPAvailable = true,
        )
        assertEquals(DsdTransportKind.DOP, wrongSlot.kind)
    }

    @Test
    fun missingRawAltCanUseDopButCannotInventNative() {
        assertEquals(
            DsdTransportKind.DOP,
            DsdUsbQuirks.select(0x20b1, 0x3008, null, doPAvailable = true).kind,
        )
        assertEquals(
            DsdTransportKind.UNSUPPORTED,
            DsdUsbQuirks.select(0x20b1, 0x3008, null, doPAvailable = false).kind,
        )
    }

    @Test
    fun idsAndNativePlansAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { UsbDeviceId(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { DsdUsbQuirks.lookup(0, 0x10000) }
        assertThrows(IllegalArgumentException::class.java) {
            DsdNativeQuirk(NativeDsdPacking.DSD_U32_BE, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DsdTransportPlan(DsdTransportKind.NATIVE, reason = "incomplete")
        }
    }

    private fun rawAlt(alternate: Int = 2, subslotBytes: Int = 4, bitResolution: Int = 32) =
        Uac2RawDsdAltSetting(
            interfaceNumber = 1,
            alternateSetting = alternate,
            channels = 2,
            slotFormat = Uac2DsdSlotFormat(subslotBytes, bitResolution),
            bmFormats = UAC2_FORMAT_TYPE_I_RAW_DATA,
        )
}
