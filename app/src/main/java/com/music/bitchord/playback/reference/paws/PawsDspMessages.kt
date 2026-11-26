package com.music.bitchord.playback.reference.paws

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact DSPI 0x14/0x15 payloads recovered from the original serializer. */
object PawsDspMessages {
    fun eqStructure(pluginId: Int, bands: List<PawsEqBand>): ByteBuffer {
        val buffer = ByteBuffer.allocate(40 + bands.size * 20).order(ByteOrder.nativeOrder())
        buffer.putInt(pluginId)
        buffer.putInt(0).putInt(0).putInt(0)
        buffer.putInt(PawsAbi.PA_PLUGIN_MSG_TAG)
        buffer.putInt(0x14)
        buffer.putInt(0)
        buffer.putInt(8 + bands.size * 20)
        buffer.putInt(if (bands.any { it.parametric }) 1 else 0)
        buffer.putInt(bands.size)
        bands.forEachIndexed { index, band ->
            buffer.putInt(band.type)
            buffer.putInt(band.frequencyHz)
            buffer.putFloat(band.q)
            buffer.put(if (band.enabled) 1.toByte() else 0)
            buffer.put(3)
            buffer.put(0).put(0)
            buffer.putInt(index)
        }
        return buffer
    }

    fun eqGains(pluginId: Int, bands: List<PawsEqBand>): IntArray {
        val result = IntArray(9 + bands.size * 2)
        result[0] = pluginId
        result[4] = PawsAbi.PA_PLUGIN_MSG_TAG
        result[5] = 0x15
        result[6] = 0
        result[7] = bands.size * 8
        result[8] = bands.size
        bands.forEachIndexed { index, band ->
            result[9 + index * 2] = index
            result[10 + index * 2] = band.gainDb.toRawBits()
        }
        return result
    }
}
