package com.music.bitchord.playback.dsd.raw.render

import androidx.media3.common.C
import com.music.bitchord.playback.dsd.DsdTransportKind
import com.music.bitchord.playback.dsd.NativeDsdPacking

/** Recording transport with controllable short writes and backpressure; it performs no audio I/O. */
class InMemoryRawDsdTransport(
    override val capabilities: RawDsdTransportCapabilities = RawDsdTransportCapabilities(
        supportedKinds = setOf(DsdTransportKind.DOP, DsdTransportKind.NATIVE),
        supportedNativePackings = setOf(NativeDsdPacking.DSD_U32_BE, NativeDsdPacking.DSD_U32_LE),
    ),
) : RawDsdTransport {
    data class Write(
        val bytes: ByteArray,
        val packetOffset: Int,
        val presentationTimeUs: Long,
        val validDsdSamples: Int,
    )

    val configurations = mutableListOf<RawDsdTransportConfiguration>()
    val writes = mutableListOf<Write>()
    var maxWriteBytes: Int = Int.MAX_VALUE
        set(value) {
            require(value >= 0) { "Maximum write size cannot be negative." }
            field = value
        }
    var acceptingWrites: Boolean = true
    var reportedPositionUs: Long = C.TIME_UNSET
    var finishCount: Int = 0
        private set
    var flushCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set
    var released: Boolean = false
        private set

    val writtenBytes: ByteArray
        get() {
            val result = ByteArray(writes.sumOf { it.bytes.size })
            var offset = 0
            for (write in writes) {
                write.bytes.copyInto(result, offset)
                offset += write.bytes.size
            }
            return result
        }

    override fun configure(configuration: RawDsdTransportConfiguration) {
        check(!released) { "Transport is released." }
        require(capabilities.supports(configuration)) { "Unsupported test configuration." }
        configurations += configuration
    }

    override fun writeOpaque(
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        validDsdSamples: Int,
    ): Int {
        check(!released) { "Transport is released." }
        check(configurations.isNotEmpty()) { "Transport is not configured." }
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "Invalid write range." }
        require(validDsdSamples > 0) { "A packet must contain valid DSD samples." }
        if (!acceptingWrites || length == 0) return 0
        val count = minOf(length, maxWriteBytes)
        if (count == 0) return 0
        writes += Write(data.copyOfRange(offset, offset + count), offset, presentationTimeUs, validDsdSamples)
        if (reportedPositionUs == C.TIME_UNSET) reportedPositionUs = presentationTimeUs
        return count
    }

    override fun positionUs(): Long = reportedPositionUs

    override fun finish() {
        check(!released) { "Transport is released." }
        finishCount++
    }

    override fun flush() {
        flushCount++
        reportedPositionUs = C.TIME_UNSET
    }

    override fun release() {
        if (!released) {
            released = true
            releaseCount++
        }
    }
}
