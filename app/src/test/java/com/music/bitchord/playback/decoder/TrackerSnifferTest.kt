package com.music.bitchord.playback.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerSnifferTest {
    @Test
    fun sniffsItS3mAndXmByStructure() {
        val it = ByteArray(192)
        "IMPM".toByteArray().copyInto(it)
        assertEquals(TrackerFormat.IT, TrackerSniffer.sniff(it))

        val s3m = ByteArray(96)
        "SCRM".toByteArray().copyInto(s3m, 44)
        assertEquals(TrackerFormat.S3M, TrackerSniffer.sniff(s3m))

        val xm = ByteArray(336)
        "Extended Module: ".toByteArray().copyInto(xm)
        xm[17] = 0x1A
        assertEquals(TrackerFormat.XM, TrackerSniffer.sniff(xm))
    }

    @Test
    fun sniffsClassicAndMultichannelModSignatures() {
        for (signature in listOf("M.K.", "M!K!", "FLT4", "FLT8", "6CHN", "16CH", "OKTA")) {
            val mod = ByteArray(TrackerSniffer.PROBE_BYTES)
            signature.toByteArray().copyInto(mod, 1_080)
            assertEquals(signature, TrackerFormat.MOD, TrackerSniffer.sniff(mod))
        }
    }

    @Test
    fun rejectsTruncatedAndUnrelatedData() {
        assertNull(TrackerSniffer.sniff(ByteArray(0)))
        assertNull(TrackerSniffer.sniff("IMP".toByteArray()))
        assertNull(TrackerSniffer.sniff(ByteArray(1_084)))
        val id3 = ByteArray(1_084)
        "ID3".toByteArray().copyInto(id3)
        assertNull(TrackerSniffer.sniff(id3))
        val invalidMod = ByteArray(1_084)
        "0CHN".toByteArray().copyInto(invalidMod, 1_080)
        assertNull(TrackerSniffer.sniff(invalidMod))
    }

    @Test
    fun mimeIdentificationDoesNotImplyADecoder() {
        assertEquals("audio/x-mod", TrackerFormat.MOD.mimeType)
        assertEquals("audio/x-it", TrackerFormat.IT.mimeType)
        assertEquals("audio/x-s3m", TrackerFormat.S3M.mimeType)
        assertEquals("audio/x-xm", TrackerFormat.XM.mimeType)
    }
}
