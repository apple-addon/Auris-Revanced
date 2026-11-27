package com.music.bitchord.playback.reference.paws

import android.os.SystemClock
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.reference.ReferenceEngineLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Float32 ExoPlayer PCM → real PAWS DSPI → output.cc, with no silent fallback. */
class PawsNativeProcessor : FloatAudioProcessor {
    private val ownerToken = System.identityHashCode(this)
    private val input = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val inputFloat = input.asFloatBuffer()
    private val pending = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val pendingFloat = pending.asFloatBuffer()
    private val readBuffer = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var pendingRead = 0
    private var pendingWrite = 0
    private var channels = 0
    private var sampleRate = 0

    override fun configure(sampleRateHz: Int, channelCount: Int) {
        require(sampleRateHz > 0 && channelCount in 1..8)
        if (!enabled()) {
            if (channels > 0) ReferenceEngineLoader.pawsHost?.closePcm(ownerToken)
            channels = 0
            sampleRate = 0
            clearPending()
            return
        }
        val host = ReferenceEngineLoader.pawsHost ?: error("PAWS native host is not initialized")
        host.openPcm(sampleRateHz, channelCount, ownerToken)
        sampleRate = sampleRateHz
        channels = channelCount
        clearPending()
    }

    override fun process(block: AudioBlock) {
        if (!enabled() || block.frameCount == 0) return
        val host = ReferenceEngineLoader.pawsHost ?: error("PAWS native host is not initialized")
        check(channels == block.channelCount) { "PAWS channel configuration changed without configure" }
        try {
            val samples = block.sampleCount
            check(samples * Float.SIZE_BYTES <= input.capacity()) { "PAWS input block exceeds native ring buffer" }
            input.clear()
            inputFloat.clear()
            inputFloat.put(block.samples, 0, samples)
            writeAll(host, samples * Float.SIZE_BYTES)
            drain(host, block.samples, samples)
            block.reset(samples / channels)
        } catch (error: Throwable) {
            host.failPcm(ownerToken, error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    private fun writeAll(host: PawsHostRuntime, byteCount: Int) {
        var written = 0
        var lastProgress = SystemClock.elapsedRealtime()
        while (written < byteCount) {
            val accepted = host.writePcm(ownerToken, input, written, byteCount - written)
            if (accepted < 0) error("PAWS input ring error=$accepted")
            if (accepted > 0) {
                written += accepted
                lastProgress = SystemClock.elapsedRealtime()
                continue
            }
            checkStall(lastProgress, "PAWS input ring stalled")
            Thread.yield()
        }
    }

    private fun drain(host: PawsHostRuntime, destination: FloatArray, sampleCount: Int) {
        var emitted = 0
        var lastProgress = SystemClock.elapsedRealtime()
        while (emitted < sampleCount) {
            if (pendingRead < pendingWrite) {
                val samples = minOf(sampleCount - emitted, (pendingWrite - pendingRead) / Float.SIZE_BYTES)
                pendingFloat.position(pendingRead / Float.SIZE_BYTES)
                pendingFloat.get(destination, emitted, samples)
                pendingRead += samples * Float.SIZE_BYTES
                emitted += samples
                lastProgress = SystemClock.elapsedRealtime()
                if (pendingRead == pendingWrite) clearPending()
                continue
            }
            val result = host.readPcm(readBuffer, ownerToken)
            if (result < 0) error("PAWS output.cc read error=$result")
            if (result == 0) {
                checkStall(lastProgress, "PAWS output.cc stalled")
                Thread.yield()
                continue
            }
            lastProgress = SystemClock.elapsedRealtime()
            check(result % Float.SIZE_BYTES == 0) { "PAWS output is not Float32 aligned: $result" }
            check(result <= pending.capacity() - pendingWrite) { "PAWS output pending buffer overflow" }
            readBuffer.position(0).limit(result)
            pending.position(pendingWrite)
            pending.put(readBuffer)
            pendingWrite += result
        }
    }

    private fun checkStall(lastProgress: Long, message: String) {
        if (SystemClock.elapsedRealtime() - lastProgress >= STALL_TIMEOUT_MS) error(message)
    }

    private fun clearPending() {
        pendingRead = 0
        pendingWrite = 0
        pending.clear()
        pendingFloat.clear()
        readBuffer.clear()
    }

    override fun flush() {
        clearPending()
        if (!enabled() || channels <= 0) return
        // A seek/discontinuity must not emit bytes buffered before the seek.
        val host = ReferenceEngineLoader.pawsHost ?: error("PAWS native host is not initialized")
        host.openPcm(sampleRate, channels, ownerToken)
    }

    override fun reset() {
        ReferenceEngineLoader.pawsHost?.closePcm(ownerToken)
        channels = 0
        sampleRate = 0
        clearPending()
    }

    private fun enabled(): Boolean = AppSettings.qualityOptions.value &&
        (AppSettings.referenceEngineMode.value == ReferenceEngineMode.POWERAMP ||
            AppSettings.referenceEngineMode.value == ReferenceEngineMode.HYBRID)

    companion object {
        private const val FRAME_BYTES = 256 * 1024
        private const val STALL_TIMEOUT_MS = 2_000L
    }
}
