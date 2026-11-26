package com.music.bitchord.playback.reference.paws

import org.junit.Assert.assertEquals
import org.junit.Test

class PawsDspMessagesTest {
    @Test
    fun `band structure has exact header and twenty byte records`() {
        val bands = listOf(
            PawsEqBand(31, -1.5f, 0.7f, enabled = true),
            PawsEqBand(16_000, 2f, 1.2f, enabled = false, parametric = true),
        )
        val buffer = PawsDspMessages.eqStructure(42, bands)
        buffer.position(0)
        assertEquals(42, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(PawsAbi.PA_PLUGIN_MSG_TAG, buffer.int)
        assertEquals(0x14, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(48, buffer.int)
        assertEquals(1, buffer.int) // parametric because band 1 opts in
        assertEquals(2, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(31, buffer.int)
        assertEquals(0.7f, buffer.float, 0f)
        assertEquals(1, buffer.get().toInt())
        assertEquals(3, buffer.get().toInt())
        assertEquals(0, buffer.get().toInt())
        assertEquals(0, buffer.get().toInt())
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(16_000, buffer.int)
        assertEquals(1.2f, buffer.float, 0f)
        assertEquals(0, buffer.get().toInt())
        assertEquals(3, buffer.get().toInt())
        assertEquals(0, buffer.get().toInt())
        assertEquals(0, buffer.get().toInt())
        assertEquals(1, buffer.int)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun `gain pairs carry index and raw float bits`() {
        val bands = listOf(PawsEqBand(31, -1.5f), PawsEqBand(16_000, 2f))
        val message = PawsDspMessages.eqGains(42, bands)
        assertEquals(42, message[0])
        assertEquals(PawsAbi.PA_PLUGIN_MSG_TAG, message[4])
        assertEquals(0x15, message[5])
        assertEquals(16, message[7])
        assertEquals(2, message[8])
        assertEquals(0, message[9])
        assertEquals((-1.5f).toRawBits(), message[10])
        assertEquals(1, message[11])
        assertEquals(2f.toRawBits(), message[12])
    }
}
