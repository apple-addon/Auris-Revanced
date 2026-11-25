package com.music.bitchord.playback.reference

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Single owner of HiBy's process-global OtHiByTrackUtils native output. */
object HibyNativeTrackWriter {
    private const val BUFFER_BYTES = 256 * 1024
    private val framesWritten = AtomicLong(0)
    private var sampleRate = 0
    private var channels = 0
    private var configured = false
    private var failed = false
    private var owner = 0

    val isConfigured: Boolean get() = configured && !failed
    fun owns(ownerToken: Int): Boolean = configured && owner == ownerToken

    @Synchronized
    fun configure(sampleRateHz: Int, channelCount: Int, ownerToken: Int): Boolean {
        if (configured && owner != ownerToken) {
            ReferenceEngineLoader.markHibyOutput(true, "HiBy native endpoint busy by another sink")
            return false
        }
        close(ownerToken)
        require(sampleRateHz > 0 && channelCount > 0) { "Invalid PCM format" }
        ReferenceNativeBridge.ensureLoaded()
        check(ReferenceNativeBridge.nativePrepareHibyBuffer(BUFFER_BYTES) == 0) {
            "nativePrepareHibyBuffer failed"
        }
        val result = ReferenceNativeBridge.nativeCreateHibyTrack(sampleRateHz, channelCount, 16, 0)
        if (result != 0) {
            ReferenceEngineLoader.markHibyOutput(false, "INIT FAILED: OtHiByTrackUtils.createTrack=$result")
            return false
        }
        sampleRate = sampleRateHz
        channels = channelCount
        owner = ownerToken
        framesWritten.set(0)
        failed = false
        configured = true
        ReferenceEngineLoader.markHibyOutput(true, "HIBY NATIVE ACTIVE · PCM16 / $sampleRateHz Hz / $channelCount ch")
        return true
    }

    /** Returns the native result: zero=all consumed, positive=remaining bytes, negative=failure. */
    fun write(buffer: ByteBuffer, ownerToken: Int): Int {
        if (!isConfigured || owner != ownerToken) return Int.MIN_VALUE
        val count = buffer.remaining().coerceAtMost(BUFFER_BYTES)
        val result = ReferenceNativeBridge.nativeWriteHibyPcm(buffer, count)
        val consumed = HibyPcmContract.consumedBytes(count, result)
        if (consumed > 0) {
            buffer.position(buffer.position() + consumed)
            framesWritten.addAndGet((consumed / (channels * 2)).toLong())
        }
        if (result < 0) {
            failed = true
            ReferenceEngineLoader.markHibyOutput(false, "INIT FAILED: OtHiByTrackUtils.write=$result")
        }
        return result
    }

    fun start(ownerToken: Int) {
        if (isConfigured && owner == ownerToken) ReferenceNativeBridge.nativeHibyStreamStart()
    }

    fun stop(ownerToken: Int) {
        if (configured && owner == ownerToken) ReferenceNativeBridge.nativeHibyStreamEnd()
    }

    fun positionUs(): Long {
        if (sampleRate <= 0) return 0
        val nativePosition = LongArray(1)
        val nativeResult = runCatching { ReferenceNativeBridge.nativeHibyNowConsumed(nativePosition) }.getOrDefault(-1L)
        val consumedFrames = when {
            nativeResult > 0 -> nativeResult
            nativePosition[0] > 0 -> nativePosition[0]
            else -> framesWritten.get()
        }
        return HibyPcmContract.framesToUs(consumedFrames.coerceAtMost(framesWritten.get()), sampleRate)
    }

    fun hasPendingData(): Boolean {
        if (!isConfigured) return false
        val nativePosition = LongArray(1)
        val consumed = runCatching { ReferenceNativeBridge.nativeHibyNowConsumed(nativePosition) }.getOrDefault(0L)
        val framesConsumed = when {
            consumed > 0 -> consumed
            nativePosition[0] > 0 -> nativePosition[0]
            else -> 0L
        }
        return framesWritten.get() > framesConsumed
    }

    @Synchronized
    fun close(ownerToken: Int? = null) {
        if (ownerToken != null && configured && owner != ownerToken) return
        if (!configured && !failed) return
        runCatching { ReferenceNativeBridge.nativeHibyStreamEnd() }
        runCatching { ReferenceNativeBridge.nativeHibyRelease() }
        runCatching { ReferenceNativeBridge.nativeHibyDestruct() }
        ReferenceNativeBridge.nativeCloseHiby()
        configured = false
        failed = false
        sampleRate = 0
        channels = 0
        owner = 0
        framesWritten.set(0)
        ReferenceEngineLoader.markHibyOutput(false, null)
    }
}
