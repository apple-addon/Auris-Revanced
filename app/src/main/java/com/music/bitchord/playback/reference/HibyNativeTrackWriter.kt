package com.music.bitchord.playback.reference

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Single owner of HiBy's process-global OtHiByTrackUtils native output. */
object HibyNativeTrackWriter {
    private const val BUFFER_BYTES = 256 * 1024
    private val bytesWritten = AtomicLong(0)
    private val framesWritten = AtomicLong(0)
    private val framesConsumed = AtomicLong(0)
    private var sampleRate = 0
    private var channels = 0
    private var configured = false
    private var failed = false
    private var owner = 0

    val isConfigured: Boolean get() = configured && !failed
    fun owns(ownerToken: Int): Boolean = configured && owner == ownerToken

    @Synchronized
    fun configure(sampleRateHz: Int, channelCount: Int, ownerToken: Int): Boolean {
        if (configured && owner != ownerToken) return false
        close(ownerToken)
        require(sampleRateHz > 0 && channelCount > 0) { "Invalid PCM format" }
        ReferenceNativeBridge.ensureLoaded()
        check(ReferenceNativeBridge.nativePrepareHibyBuffer(BUFFER_BYTES) == 0) {
            "nativePrepareHibyBuffer failed"
        }
        val result = ReferenceNativeBridge.nativeCreateHibyTrack(sampleRateHz, channelCount, 16, 0)
        if (result != 0) {
            failed = true
            ReferenceEngineLoader.markHibyOutput(
                NativeStreamState.FAILED,
                "INIT FAILED: OtHiByTrackUtils.createTrack=$result",
            )
            return false
        }
        sampleRate = sampleRateHz
        channels = channelCount
        owner = ownerToken
        bytesWritten.set(0)
        framesWritten.set(0)
        framesConsumed.set(0)
        failed = false
        configured = true
        ReferenceEngineLoader.markHibyOutput(
            NativeStreamState.OPEN,
            "HIBY NATIVE READY · waiting for PCM16 / $sampleRateHz Hz / $channelCount ch",
            producedFrames = 0,
        )
        return true
    }

    /** Returns the native result: zero=all consumed, positive=remaining bytes, negative=failure. */
    @Synchronized
    fun write(buffer: ByteBuffer, ownerToken: Int): Int {
        if (!isConfigured || owner != ownerToken) return Int.MIN_VALUE
        val count = buffer.remaining().coerceAtMost(BUFFER_BYTES)
        val result = ReferenceNativeBridge.nativeWriteHibyPcm(buffer, count)
        val consumed = HibyPcmContract.consumedBytes(count, result)
        if (consumed > 0) {
            buffer.position(buffer.position() + consumed)
            val totalBytes = bytesWritten.addAndGet(consumed.toLong())
            framesWritten.set(totalBytes / (channels * 2))
        }
        if (result < 0) {
            failed = true
            ReferenceEngineLoader.markHibyOutput(
                NativeStreamState.FAILED,
                "PCM FAILED: OtHiByTrackUtils.write=$result",
                producedFrames = framesConsumed.get(),
            )
        }
        return result
    }

    @Synchronized
    fun start(ownerToken: Int) {
        if (!isConfigured || owner != ownerToken) return
        val result = ReferenceNativeBridge.nativeHibyStreamStart()
        if (result != 0) {
            failed = true
            ReferenceEngineLoader.markHibyOutput(
                NativeStreamState.FAILED,
                "PCM FAILED: OtHiByTrackUtils.streamStart=$result",
                producedFrames = framesConsumed.get(),
            )
            error("OtHiByTrackUtils.streamStart=$result")
        }
    }

    @Synchronized
    fun stop(ownerToken: Int) {
        if (configured && owner == ownerToken) ReferenceNativeBridge.nativeHibyStreamEnd()
    }

    @Synchronized
    fun positionUs(): Long {
        if (!isConfigured || sampleRate <= 0) return 0
        updateConsumedFrames()
        // A failed query retains only the last verified position; it never
        // substitutes the number of frames merely written to the endpoint.
        return HibyPcmContract.framesToUs(framesConsumed.get(), sampleRate)
    }

    @Synchronized
    fun hasPendingData(): Boolean {
        if (!isConfigured) return false
        val consumed = updateConsumedFrames()
        // Unknown consumption cannot be allowed to block end-of-stream forever.
        return HibyConsumptionContract.hasPendingData(consumed, framesWritten.get())
    }

    private fun updateConsumedFrames(): Long? {
        val nativePosition = LongArray(1)
        val nativeResult = runCatching {
            ReferenceNativeBridge.nativeHibyNowConsumed(nativePosition)
        }.getOrElse { error ->
            markConsumptionUnknown(error.message ?: error.javaClass.simpleName)
            return null
        }
        val consumed = HibyConsumptionContract.monotonicFrames(
            nativeResult,
            previousConsumed = framesConsumed.get(),
            framesWritten = framesWritten.get(),
        )
        if (consumed == null) {
            markConsumptionUnknown("nowConsumed=$nativeResult")
            return null
        }
        framesConsumed.set(consumed)
        if (consumed > 0) {
            ReferenceEngineLoader.markHibyOutput(
                NativeStreamState.ACTIVE,
                "HIBY NATIVE ACTIVE · consumed PCM16 / $sampleRate Hz / $channels ch",
                producedFrames = consumed,
            )
        } else {
            ReferenceEngineLoader.markHibyOutput(
                NativeStreamState.OPEN,
                "HIBY NATIVE READY · waiting for consumed PCM16 / $sampleRate Hz / $channels ch",
                producedFrames = 0,
            )
        }
        return consumed
    }

    private fun markConsumptionUnknown(reason: String) {
        ReferenceEngineLoader.markHibyOutput(
            NativeStreamState.OPEN,
            "HIBY NATIVE READY · consumption UNKNOWN · $reason",
            producedFrames = framesConsumed.get(),
        )
    }

    @Synchronized
    fun close(ownerToken: Int? = null) {
        if (ownerToken != null && configured && owner != ownerToken) return
        if (!configured && !failed) return
        val failure = ReferenceEngineLoader.status.value.hibyOutput.detail.takeIf { failed }
        runCatching { ReferenceNativeBridge.nativeHibyStreamEnd() }
        runCatching { ReferenceNativeBridge.nativeHibyRelease() }
        runCatching { ReferenceNativeBridge.nativeHibyDestruct() }
        ReferenceNativeBridge.nativeCloseHiby()
        configured = false
        failed = false
        sampleRate = 0
        channels = 0
        owner = 0
        bytesWritten.set(0)
        framesWritten.set(0)
        framesConsumed.set(0)
        if (failure == null) {
            ReferenceEngineLoader.markHibyOutput(NativeStreamState.CLOSED, producedFrames = 0)
        } else {
            ReferenceEngineLoader.markHibyOutput(NativeStreamState.FAILED, failure, producedFrames = 0)
        }
    }
}

/** Consumption evidence is valid only for non-negative native return values. */
internal object HibyConsumptionContract {
    fun monotonicFrames(nativeResult: Long, previousConsumed: Long, framesWritten: Long): Long? {
        val written = framesWritten.coerceAtLeast(0)
        // A counter outside this stream's write domain is not safe evidence;
        // clamping it to written would fabricate playout of unverified frames.
        val previous = previousConsumed.coerceIn(0, written)
        if (nativeResult < previous || nativeResult > written) return null
        return nativeResult
    }

    fun hasPendingData(consumedFrames: Long?, framesWritten: Long): Boolean =
        consumedFrames != null && framesWritten > consumedFrames
}
