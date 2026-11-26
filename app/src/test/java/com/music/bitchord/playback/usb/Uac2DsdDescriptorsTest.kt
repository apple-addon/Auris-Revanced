package com.music.bitchord.playback.usb

import com.decent.usbaudio.DacQuirkProfile
import com.decent.usbaudio.DacQuirks
import com.decent.usbaudio.Uac2DsdDescriptors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Uac2DsdDescriptorsTest {
    @After
    fun resetQuirks() = DacQuirks.clear()

    @Test
    fun `parses native raw DSD alt and async feedback`() {
        val alts = Uac2DsdDescriptors.parse(rawDsdAlt() + dopAlt())
        assertEquals(2, alts.size)
        val raw = Uac2DsdDescriptors.selectRawDsd(alts, 2_822_400, 2)
        assertNotNull(raw)
        checkNotNull(raw)
        assertTrue(raw.rawDsd)
        assertEquals(32, raw.bitResolution)
        assertEquals(4, raw.subframeBytes)
        assertEquals(0x83, raw.feedbackEndpoint)
        assertEquals(4, raw.feedbackPacketSize)
        assertEquals(2_822_400, raw.sampleRatesHz.single())
    }

    @Test
    fun `DoP selection never mistakes raw DSD for PCM24`() {
        val alts = Uac2DsdDescriptors.parse(rawDsdAlt() + dopAlt())
        val dop = Uac2DsdDescriptors.selectDop(alts, 176_400, 2)
        assertNotNull(dop)
        checkNotNull(dop)
        assertFalse(dop.rawDsd)
        assertEquals(24, dop.bitResolution)
        assertEquals(3, dop.subframeBytes)
        assertNull(Uac2DsdDescriptors.selectDop(alts, 2_822_400, 2))
    }

    @Test
    fun `raw DSD selection respects channels and clock`() {
        val alts = Uac2DsdDescriptors.parse(rawDsdAlt())
        assertNull(Uac2DsdDescriptors.selectRawDsd(alts, 2_822_400, 1))
        assertNull(Uac2DsdDescriptors.selectRawDsd(alts, 5_644_800, 2))
    }

    @Test
    fun `malformed descriptor fails closed`() {
        val truncated = rawDsdAlt().copyOf(19)
        assertTrue(Uac2DsdDescriptors.parse(truncated).isEmpty())
    }

    @Test
    fun `unknown DAC uses conservative quirk defaults`() {
        val profile = DacQuirks.forDevice(0x1234, 0x5678)
        assertEquals(DacQuirkProfile(), profile)
        assertFalse(profile.continuousFeedback)
        assertFalse(profile.allowUnverifiedClock)
    }

    @Test
    fun `per DAC quirk can enable continuous feedback`() {
        DacQuirks.install(0x1234, 0x5678, DacQuirkProfile(continuousFeedback = true, keepAlive = true))
        assertTrue(DacQuirks.forDevice(0x1234, 0x5678).continuousFeedback)
        assertTrue(DacQuirks.forDevice(0x1234, 0x5678).keepAlive)
    }

    private fun rawDsdAlt(): ByteArray = interfaceAlt(1) + formatAlt(
        raw = true,
        channels = 2,
        subframe = 4,
        bits = 32,
        rate = 2_822_400,
    ) + endpoint(0x01, 0x400, 1) + endpoint(0x83, 4, 1)

    private fun dopAlt(): ByteArray = interfaceAlt(2) + formatAlt(
        raw = false,
        channels = 2,
        subframe = 3,
        bits = 24,
        rate = 176_400,
    ) + endpoint(0x02, 0x400, 1)

    private fun interfaceAlt(alt: Int): ByteArray = byteArrayOf(
        9, 0x04, 2, alt.toByte(), 2, 1, 2, 0x20, 0,
    )

    private fun formatAlt(raw: Boolean, channels: Int, subframe: Int, bits: Int, rate: Int): ByteArray {
        val format = if (raw) 0x80000000L else 0L
        return byteArrayOf(
            15, 0x24, 0x02, 0x01,
            format.toByte(), (format shr 8).toByte(), (format shr 16).toByte(), (format shr 24).toByte(),
            channels.toByte(), subframe.toByte(), bits.toByte(), 1,
            rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(),
        )
    }

    private fun endpoint(address: Int, maxPacket: Int, interval: Int): ByteArray = byteArrayOf(
        7, 0x05, address.toByte(), 0x01,
        maxPacket.toByte(), (maxPacket shr 8).toByte(), interval.toByte(),
    )
}
