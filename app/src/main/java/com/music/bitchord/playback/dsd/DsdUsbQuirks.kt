package com.music.bitchord.playback.dsd

data class UsbDeviceId(val vendorId: Int, val productId: Int) {
    init {
        require(vendorId in 0..0xffff && productId in 0..0xffff) {
            "USB VID and PID must be unsigned 16-bit values."
        }
    }
}

/** Native packing that is known for a VID/PID, optionally only on one raw alt. */
data class DsdNativeQuirk(
    val packing: NativeDsdPacking,
    val rawAlternateSetting: Int? = null,
) {
    init {
        require(rawAlternateSetting == null || rawAlternateSetting in 1..255) {
            "A native DSD quirk must name a non-zero alternate setting."
        }
    }
}

enum class DsdTransportKind { NATIVE, DOP, UNSUPPORTED }

/** A side-effect-free transport choice for the platform integration layer. */
data class DsdTransportPlan(
    val kind: DsdTransportKind,
    val packing: NativeDsdPacking? = null,
    val rawAlternateSetting: Int? = null,
    val reason: String,
) {
    init {
        if (kind == DsdTransportKind.NATIVE) {
            require(packing != null && rawAlternateSetting != null) { "Native DSD plan is incomplete." }
        } else {
            require(packing == null && rawAlternateSetting == null) { "Fallback plan cannot contain native parameters." }
        }
    }
}

/**
 * Conservative native-DSD quirks. A raw descriptor alone does not identify
 * byte order, so unknown devices and mismatched alts fall back to DoP rather
 * than guessing. If DoP was not independently verified, selection is unsafe
 * and [DsdTransportKind.UNSUPPORTED] is returned.
 */
object DsdUsbQuirks {
    val quirks: Map<UsbDeviceId, DsdNativeQuirk> = mapOf(
        UsbDeviceId(0x20b1, 0x3008) to DsdNativeQuirk(NativeDsdPacking.DSD_U32_BE, 2), // iFi micro/nano iDSD
        UsbDeviceId(0x20b1, 0x2008) to DsdNativeQuirk(NativeDsdPacking.DSD_U32_BE, 2), // Matrix X-Sabre
        UsbDeviceId(0x20b1, 0x300a) to DsdNativeQuirk(NativeDsdPacking.DSD_U32_BE, 2), // Matrix Mini-i Pro
        UsbDeviceId(0x22d9, 0x0416) to DsdNativeQuirk(NativeDsdPacking.DSD_U32_BE, 2), // OPPO HA-1
        UsbDeviceId(0x20b1, 0x2009) to DsdNativeQuirk(NativeDsdPacking.DSD_U32_LE), // DIYINHK USB to I2S/DSD
        UsbDeviceId(0x20b1, 0x2023) to DsdNativeQuirk(NativeDsdPacking.DSD_U32_LE), // JLsounds I2SoverUSB
    )

    fun lookup(vendorId: Int, productId: Int): DsdNativeQuirk? = quirks[UsbDeviceId(vendorId, productId)]

    fun select(
        vendorId: Int,
        productId: Int,
        rawAlt: Uac2RawDsdAltSetting?,
        doPAvailable: Boolean,
    ): DsdTransportPlan {
        val id = UsbDeviceId(vendorId, productId)
        val quirk = quirks[id]
        if (quirk != null && rawAlt != null &&
            quirk.packing.matchesSlot(rawAlt.slotFormat.subslotBytes, rawAlt.slotFormat.bitResolution) &&
            (quirk.rawAlternateSetting == null || quirk.rawAlternateSetting == rawAlt.alternateSetting)
        ) {
            return DsdTransportPlan(
                kind = DsdTransportKind.NATIVE,
                packing = quirk.packing,
                rawAlternateSetting = rawAlt.alternateSetting,
                reason = "Verified ${quirk.packing.name} quirk for ${id.hex()}.",
            )
        }
        if (doPAvailable) {
            val why = when {
                quirk == null -> "No native packing quirk for ${id.hex()}"
                rawAlt == null -> "No raw-data alternate setting for ${id.hex()}"
                else -> "Native quirk does not match raw alternate ${rawAlt.alternateSetting}"
            }
            return DsdTransportPlan(DsdTransportKind.DOP, reason = "$why; using verified DoP fallback.")
        }
        return DsdTransportPlan(
            DsdTransportKind.UNSUPPORTED,
            reason = "Native DSD is not verified for ${id.hex()} and DoP is unavailable.",
        )
    }

    private fun UsbDeviceId.hex(): String =
        "%04x:%04x".format(vendorId, productId)
}
