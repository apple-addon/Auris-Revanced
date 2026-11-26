package com.music.bitchord.playback.usb

import androidx.media3.common.C
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.DsdTransportPlan
import com.music.bitchord.playback.dsd.NativeDsdPacking
import com.music.bitchord.playback.dsd.raw.render.RawDsdTransportConfiguration
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusiveUsbRawDsdTransportTest {
    @Test
    fun wireFormatsUseExactDop24AndNativeU32RatesWithoutConversion() {
        val dop = RawDsdWireFormats.resolve(configuration(DsdTransportKind.DOP))
        assertEquals(176_400, dop.pcmRateHz)
        assertEquals(24, dop.bitDepth)
        assertEquals(C.ENCODING_PCM_24BIT, dop.rawEncoding)
        assertEquals(16, dop.dsdSamplesPerFrame)
        assertEquals(6, dop.frameBytes)

        val native = RawDsdWireFormats.resolve(configuration(DsdTransportKind.NATIVE))
        assertEquals(88_200, native.pcmRateHz)
        assertEquals(32, native.bitDepth)
        assertEquals(C.ENCODING_PCM_32BIT, native.rawEncoding)
        assertEquals(32, native.dsdSamplesPerFrame)
        assertEquals(8, native.frameBytes)
        assertThrows(IllegalArgumentException::class.java) {
            RawDsdWireFormats.resolve(configuration(DsdTransportKind.DOP, dsdBitRate = 2_822_401))
        }
    }

    @Test
    fun dopBytesAreOpaqueAndPcmGainRemainsRestrictedToPcmFloatPath() {
        val output = FakeOutput()
        val transport = ExclusiveUsbRawDsdTransport(output, dopPlan())
        transport.configure(configuration(DsdTransportKind.DOP))
        val packet = bytes(24)

        assertEquals(packet.size, transport.writeOpaque(packet, 0, packet.size, 123, 64))
        assertArrayEquals(packet, output.writtenBytes)

        val pcm = floatArrayOf(-1f, -0.5f, 0.25f, 1f)
        ExclusivePcmGain.apply(pcm, 0.5f)
        assertArrayEquals(floatArrayOf(-0.5f, -0.25f, 0.125f, 0.5f), pcm, 0f)
        // The raw packet above never entered this float-only PCM operation.
        assertArrayEquals(packet, output.writtenBytes)
    }

    @Test
    fun zeroAndPartialNativeWritesPreserveEveryByteAndContinuationOffset() {
        val output = FakeOutput().apply { acceptWrites = false }
        val transport = ExclusiveUsbRawDsdTransport(output, nativePlan())
        transport.configure(configuration(DsdTransportKind.NATIVE))
        val packet = bytes(70_000) // Complete stereo U32 frames; larger than one adapter chunk.

        assertEquals(0, transport.writeOpaque(packet, 0, packet.size, 777, 8_750 * 32))
        assertTrue(output.writes.isEmpty())
        assertEquals(C.TIME_UNSET, transport.positionUs())

        output.acceptWrites = true
        var offset = 0
        while (offset < packet.size) {
            val accepted = transport.writeOpaque(
                packet,
                offset,
                packet.size - offset,
                777,
                8_750 * 32,
            )
            assertTrue(accepted > 0)
            offset += accepted
        }

        assertEquals(packet.size, offset)
        assertArrayEquals(packet, output.writtenBytes)
        assertEquals(listOf(0, 65_536), output.writes.map { it.offset })
        assertEquals(listOf(65_536, 4_464), output.writes.map { it.bytes.size })
    }

    @Test
    fun finalNativePaddingIsWrittenButNotCountedAsMediaTime() {
        val output = FakeOutput().apply { framesWritten = 100 }
        val transport = ExclusiveUsbRawDsdTransport(output, nativePlan())
        transport.configure(configuration(DsdTransportKind.NATIVE, finalPaddingSamples = 16))
        val packet = bytes(16) // Two stereo U32 frames = 64 DSD samples, only 32 valid.

        assertEquals(16, transport.writeOpaque(packet, 0, 16, 1_000, 32))
        output.framesWritten = 101
        assertEquals(1_011, transport.positionUs())
        output.framesWritten = 102
        assertEquals(1_011, transport.positionUs())
        assertArrayEquals(packet, output.writtenBytes)

        transport.flush()
        assertEquals(1, output.flushCount)
        assertEquals(C.TIME_UNSET, transport.positionUs())
        assertEquals(16, transport.writeOpaque(packet, 0, 16, 5_000, 32))
        output.framesWritten = 103
        assertEquals(5_011, transport.positionUs())
    }

    @Test
    fun backpressureAfterFlushDoesNotCaptureAStaleClockBase() {
        val output = FakeOutput().apply { framesWritten = 10 }
        val transport = ExclusiveUsbRawDsdTransport(output, nativePlan())
        transport.configure(configuration(DsdTransportKind.NATIVE))
        val packet = bytes(16)
        transport.writeOpaque(packet, 0, packet.size, 0, 64)
        transport.flush()

        output.acceptWrites = false
        output.framesWritten = 20 // The pre-flush native write completes before the flush marker.
        assertEquals(0, transport.writeOpaque(packet, 0, packet.size, 1_000, 64))
        output.acceptWrites = true
        assertEquals(16, transport.writeOpaque(packet, 0, packet.size, 1_000, 64))
        output.framesWritten = 21
        assertEquals(1_011, transport.positionUs())
    }

    @Test
    fun finalDopPartialGroupUsesValidSamplesForPosition() {
        val output = FakeOutput()
        val transport = ExclusiveUsbRawDsdTransport(output, dopPlan())
        transport.configure(configuration(DsdTransportKind.DOP))
        val packet = bytes(12) // Two stereo DoP frames = 32 samples, 20 valid.

        transport.writeOpaque(packet, 0, packet.size, 2_000, 20)
        output.framesWritten = 2
        assertEquals(2_007, transport.positionUs())
        assertArrayEquals(packet, output.writtenBytes)
    }

    @Test
    fun finishIsIdempotentSubmitsNoSyntheticBytesAndFlushStartsANewGeneration() {
        val output = FakeOutput()
        val transport = ExclusiveUsbRawDsdTransport(output, nativePlan())
        transport.configure(configuration(DsdTransportKind.NATIVE))
        val residualPacket = bytes(16)

        assertEquals(16, transport.writeOpaque(residualPacket, 0, 16, 900, 64))
        val acceptedBytes = output.writtenBytes
        transport.finish()
        transport.finish()
        assertEquals(1, output.finishCount)
        assertArrayEquals(acceptedBytes, output.writtenBytes)
        assertThrows(IllegalStateException::class.java) {
            transport.writeOpaque(residualPacket, 0, 16, 1_000, 64)
        }

        transport.flush()
        assertEquals(1, output.flushCount)
        assertEquals(1, output.finishCount)
        assertEquals(16, transport.writeOpaque(residualPacket, 0, 16, 2_000, 64))
        transport.finish()
        assertEquals(2, output.finishCount)
        assertArrayEquals(acceptedBytes + acceptedBytes, output.writtenBytes)
    }

    @Test
    fun incompleteFramesFailBeforeNativeCanTruncateThem() {
        val output = FakeOutput()
        val transport = ExclusiveUsbRawDsdTransport(output, nativePlan())
        transport.configure(configuration(DsdTransportKind.NATIVE))

        assertThrows(IllegalArgumentException::class.java) {
            transport.writeOpaque(bytes(16), 0, 15, 0, 32)
        }
        assertTrue(output.writes.isEmpty())
    }

    @Test
    fun configurationFailureThrowsForCallerFallbackAndNeverWritesPcm() {
        val output = FakeOutput().apply {
            configureResult = false
            lastFailureReason = "no exact 24-bit DoP alt"
        }
        val transport = ExclusiveUsbRawDsdTransport(output, dopPlan())

        val failure = assertThrows(IOException::class.java) {
            transport.configure(configuration(DsdTransportKind.DOP))
        }
        assertEquals("no exact 24-bit DoP alt", failure.message)
        assertTrue(output.writes.isEmpty())
        assertEquals(C.TIME_UNSET, transport.positionUs())
        assertThrows(IOException::class.java) {
            transport.writeOpaque(bytes(6), 0, 6, 0, 16)
        }
    }

    @Test
    fun unsupportedAndMismatchedPlansCannotClaimOrOpenNativeDsd() {
        val unsupported = ExclusiveUsbRawDsdTransport(
            FakeOutput(),
            DsdTransportPlan(DsdTransportKind.UNSUPPORTED, reason = "unknown VID/PID"),
        )
        assertTrue(unsupported.capabilities.supportedKinds.isEmpty())
        assertFalse(unsupported.capabilities.supports(configuration(DsdTransportKind.NATIVE)))

        val output = FakeOutput()
        val nativeOnly = ExclusiveUsbRawDsdTransport(output, nativePlan())
        assertEquals(setOf(DsdTransportKind.NATIVE), nativeOnly.capabilities.supportedKinds)
        assertThrows(IllegalArgumentException::class.java) {
            nativeOnly.configure(configuration(DsdTransportKind.DOP))
        }
        assertTrue(output.configurations.isEmpty())
    }

    @Test
    fun releaseClosesOnlyAConfiguredRawSessionAndIsIdempotent() {
        val unusedOutput = FakeOutput()
        val unused = ExclusiveUsbRawDsdTransport(unusedOutput, dopPlan())
        unused.release()
        unused.release()
        assertEquals(0, unusedOutput.resetCount)

        val output = FakeOutput()
        val transport = ExclusiveUsbRawDsdTransport(output, dopPlan())
        transport.configure(configuration(DsdTransportKind.DOP))
        transport.release()
        transport.release()
        assertEquals(1, output.resetCount)
        assertThrows(IllegalStateException::class.java) {
            transport.writeOpaque(bytes(6), 0, 6, 0, 16)
        }
    }

    private class FakeOutput : RawDsdUsbOutput {
        data class Write(val bytes: ByteArray, val offset: Int)

        val configurations = mutableListOf<RawDsdTransportConfiguration>()
        val writes = mutableListOf<Write>()
        var configureResult = true
        var acceptWrites = true
        var framesWritten = 0L
        var finishCount = 0
        var flushCount = 0
        var resetCount = 0
        override var lastFailureReason: String? = null

        val writtenBytes: ByteArray
            get() {
                val result = ByteArray(writes.sumOf { it.bytes.size })
                var destination = 0
                for (write in writes) {
                    write.bytes.copyInto(result, destination)
                    destination += write.bytes.size
                }
                return result
            }

        override fun configureRawDsd(configuration: RawDsdTransportConfiguration): Boolean {
            if (!configureResult) return false
            configurations += configuration
            return true
        }

        override fun writeRawDsd(data: ByteArray, offset: Int, length: Int): Boolean {
            if (!acceptWrites) return false
            writes += Write(data.copyOfRange(offset, offset + length), offset)
            return true
        }

        override fun rawDsdFramesWritten(): Long = framesWritten

        override fun finishRawDsd() {
            finishCount++
        }

        override fun flushRawDsd() {
            flushCount++
        }

        override fun resetRawDsd() {
            resetCount++
        }
    }

    private companion object {
        fun configuration(
            kind: DsdTransportKind,
            dsdBitRate: Int = 2_822_400,
            finalPaddingSamples: Int = 0,
        ) = RawDsdTransportConfiguration(
            dsdBitRate = dsdBitRate,
            channelCount = 2,
            kind = kind,
            nativePacking = if (kind == DsdTransportKind.NATIVE) NativeDsdPacking.DSD_U32_LE else null,
            rawAlternateSetting = if (kind == DsdTransportKind.NATIVE) 2 else null,
            finalPaddingSamples = finalPaddingSamples,
        )

        fun dopPlan() = DsdTransportPlan(DsdTransportKind.DOP, reason = "verified DoP")

        fun nativePlan() = DsdTransportPlan(
            DsdTransportKind.NATIVE,
            packing = NativeDsdPacking.DSD_U32_LE,
            rawAlternateSetting = 2,
            reason = "verified test quirk",
        )

        fun bytes(size: Int): ByteArray = ByteArray(size) { index ->
            (index * 37 + 11).toByte()
        }
    }
}
