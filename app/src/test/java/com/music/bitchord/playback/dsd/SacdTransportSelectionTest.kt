package com.music.bitchord.playback.dsd

import com.music.bitchord.playback.audio.drivers.DsdOutputMode
import com.music.bitchord.playback.decoder.SacdIsoExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class SacdTransportSelectionTest {
    @Test
    fun `exclusive raw request selects the exact requested wire transport`() {
        assertEquals(SacdIsoExtractor.Transport.PCM, sacdTransportFor(DsdOutputMode.PCM, true))
        assertEquals(SacdIsoExtractor.Transport.DOP, sacdTransportFor(DsdOutputMode.DOP, true))
        assertEquals(SacdIsoExtractor.Transport.RAW_UAC2, sacdTransportFor(DsdOutputMode.NATIVE_RAW, true))
    }

    @Test
    fun `non exclusive output always falls back to PCM`() {
        DsdOutputMode.entries.forEach { mode ->
            assertEquals(SacdIsoExtractor.Transport.PCM, sacdTransportFor(mode, false))
        }
    }
}
