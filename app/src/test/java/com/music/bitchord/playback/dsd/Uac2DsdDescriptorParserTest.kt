package com.music.bitchord.playback.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Uac2DsdDescriptorParserTest {
    @Test
    fun locatesOnlyTypeIRawDataAltAndReportsItsFormat() {
        val id = Uac2AltSettingId(2, 3)
        val ranges = listOf(
            Uac2SampleRateRange(44_100, 176_400, 44_100),
            Uac2SampleRateRange(48_000, 192_000, 1),
        )
        val descriptors = concat(
            interfaceDescriptor(2, 1, protocol = 0x20),
            asGeneral(formats = 1, channels = 2),
            formatType(subslotBytes = 3, bitResolution = 24),
            interfaceDescriptor(2, 3, protocol = 0x20),
            asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2),
            formatType(subslotBytes = 4, bitResolution = 32),
            endpointDescriptor(),
        )

        val alts = Uac2DsdDescriptorParser.parse(descriptors, mapOf(id to ranges))

        assertEquals(1, alts.size)
        val raw = alts.single()
        assertEquals(id, raw.id)
        assertEquals(2, raw.channels)
        assertEquals(Uac2DsdSlotFormat(4, 32), raw.slotFormat)
        assertEquals(UAC2_FORMAT_TYPE_I_RAW_DATA, raw.bmFormats)
        assertEquals(listOf(44_100, 88_200, 132_300, 176_400), raw.sampleRatesHz)
        assertEquals(ranges, raw.sampleRateRanges)
    }

    @Test
    fun standardDescriptorsWithoutClockRangesDoNotInventRates() {
        val alts = Uac2DsdDescriptorParser.parse(rawDescriptors())
        assertEquals(1, alts.size)
        assertTrue(alts.single().sampleRatesHz.isEmpty())
        assertTrue(alts.single().sampleRateRanges.isEmpty())
    }

    @Test
    fun pcmAndUac1AlternatesAreIgnored() {
        val pcmOnly = concat(
            interfaceDescriptor(1, 1),
            asGeneral(formats = 1, channels = 2),
            formatType(3, 24),
        )
        assertTrue(Uac2DsdDescriptorParser.parse(pcmOnly).isEmpty())
        val uac1 = concat(
            interfaceDescriptor(1, 2, protocol = 0),
            asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2),
            formatType(4, 32),
        )
        assertTrue(Uac2DsdDescriptorParser.parse(uac1).isEmpty())
    }

    @Test
    fun parsesDiscreteAndContinuousGetRangeTriplets() {
        val payload = rangePayload(
            Uac2SampleRateRange(44_100, 176_400, 44_100),
            Uac2SampleRateRange(48_000, 192_000, 1),
        )
        val ranges = Uac2SampleRateRangeParser.parse(payload)
        assertEquals(2, ranges.size)
        assertEquals(44_100, ranges[0].minimumHz)
        assertEquals(192_000, ranges[1].maximumHz)
        val raw = Uac2DsdDescriptorParser.parse(rawDescriptors(), mapOf(Uac2AltSettingId(1, 2) to ranges)).single()
        assertEquals(listOf(44_100, 88_200, 132_300, 176_400), raw.sampleRatesHz)
    }

    @Test
    fun duplicateAndMissingRawDescriptorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(
                concat(
                    interfaceDescriptor(1, 2),
                    asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(
                concat(
                    interfaceDescriptor(1, 2),
                    asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2),
                    asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2),
                    formatType(4, 32),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(
                concat(
                    interfaceDescriptor(1, 2),
                    asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2),
                    formatType(4, 32),
                    formatType(4, 32),
                ),
            )
        }
    }

    @Test
    fun invalidRawChannelSlotAndEndpointMetadataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(rawDescriptors(channels = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(rawDescriptors(subslotBytes = 3, bitResolution = 24))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(rawDescriptors(subslotBytes = 4, bitResolution = 24))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(rawDescriptors(endpoints = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(rawDescriptors(alternateSetting = 0))
        }
    }

    @Test
    fun malformedDescriptorLengthsAndTypeIRecordsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Uac2DsdDescriptorParser.parse(bytes(0, 4)) }
        assertThrows(IllegalArgumentException::class.java) { Uac2DsdDescriptorParser.parse(bytes(1)) }
        assertThrows(IllegalArgumentException::class.java) { Uac2DsdDescriptorParser.parse(bytes(5, 4, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(concat(interfaceDescriptor(1, 2), bytes(2, 0x24)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(concat(interfaceDescriptor(1, 2), ByteArray(14).also {
                it[0] = 14; it[1] = 0x24; it[2] = 1
            }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2DsdDescriptorParser.parse(concat(interfaceDescriptor(1, 2), bytes(5, 0x24, 2, 1, 4)))
        }
        assertTrue(
            Uac2DsdDescriptorParser.parse(
                concat(
                    interfaceDescriptor(1, 2),
                    asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = 2, formatType = 2),
                    formatType(4, 32),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun malformedRateRangePayloadsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Uac2SampleRateRangeParser.parse(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { Uac2SampleRateRangeParser.parse(bytes(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Uac2SampleRateRangeParser.parse(bytes(1, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2SampleRateRangeParser.parse(rangePayload(44_100, 48_000, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2SampleRateRangeParser.parse(rangePayload(48_000, 44_100, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2SampleRateRangeParser.parse(rangePayload(0, 48_000, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Uac2SampleRateRangeParser.parse(rangePayload(1, Int.MAX_VALUE, 2))
        }
        val unsignedOverflow = ByteArray(14).also {
            it[0] = 1
            it[2] = 1
            it[13] = 0xff.toByte()
        }
        assertThrows(IllegalArgumentException::class.java) { Uac2SampleRateRangeParser.parse(unsignedOverflow) }
    }

    private fun rawDescriptors(
        channels: Int = 2,
        subslotBytes: Int = 4,
        bitResolution: Int = 32,
        endpoints: Int = 1,
        alternateSetting: Int = 2,
    ): ByteArray = concat(
        interfaceDescriptor(1, alternateSetting, endpoints = endpoints),
        asGeneral(formats = UAC2_FORMAT_TYPE_I_RAW_DATA, channels = channels),
        formatType(subslotBytes, bitResolution),
    )

    private fun interfaceDescriptor(
        interfaceNumber: Int,
        alternateSetting: Int,
        endpoints: Int = 1,
        protocol: Int = 0x20,
    ): ByteArray = bytes(9, 4, interfaceNumber, alternateSetting, endpoints, 1, 2, protocol, 0)

    private fun asGeneral(formats: Long, channels: Int, formatType: Int = 1): ByteArray =
        ByteArray(15).also {
            it[0] = 15
            it[1] = 0x24
            it[2] = 1
            it[3] = 7
            it[5] = formatType.toByte()
            putU32(it, 6, formats)
            putU32(it, 10, if (channels == 2) 3 else 1)
            it[14] = channels.toByte()
        }

    private fun formatType(subslotBytes: Int, bitResolution: Int): ByteArray =
        bytes(6, 0x24, 2, 1, subslotBytes, bitResolution)

    private fun endpointDescriptor(): ByteArray = bytes(7, 5, 1, 5, 0x40, 0, 1)

    private fun rangePayload(vararg values: Int): ByteArray = ByteArray(14).also {
        it[0] = 1
        putU32(it, 2, values[0].toLong())
        putU32(it, 6, values[1].toLong())
        putU32(it, 10, values[2].toLong())
    }

    private fun rangePayload(vararg ranges: Uac2SampleRateRange): ByteArray =
        ByteArray(2 + ranges.size * 12).also { payload ->
            payload[0] = ranges.size.toByte()
            payload[1] = (ranges.size ushr 8).toByte()
            for ((index, range) in ranges.withIndex()) {
                putU32(payload, 2 + index * 12, range.minimumHz.toLong())
                putU32(payload, 6 + index * 12, range.maximumHz.toLong())
                putU32(payload, 10 + index * 12, range.resolutionHz.toLong())
            }
        }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val result = ByteArray(arrays.sumOf { it.size })
        var offset = 0
        for (array in arrays) {
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
