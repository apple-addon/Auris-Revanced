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
    private val input = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val inputFloat = input.asFloatBuffer()
    private val pending = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val pendingFloat = pending.asFloatBuffer()
    private val readBuffer = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var pendingRead = 0
    private var pendingWrite = 0
    private var channels = 0

    override fun configure(sampleRateHz: Int, channelCount: Int) {
        require(sampleRateHz > 0 && channelCount in 1..8)
        if (!enabled()) return
        val host = ReferenceEngineLoader.pawsHost ?: error("PAWS native host is not initialized")
        host.openPcm(sampleRateHz, channelCount)
        channels = channelCount
        clearPending()
    }

    override fun process(block: AudioBlock) {
        if (!enabled() || block.frameCount == 0) return
        val host = ReferenceEngineLoader.pawsHost ?: error("PAWS native host is not initialized")
        check(channels == block.channelCount) { "PAWS channel configuration changed without configure" }
        val samples = block.sampleCount
        input.clear()
        inputFloat.clear()
        inputFloat.put(block.samples, 0, samples)
        writeAll(host, samples * 4)
        drain(host, block.samples, samples)
        block.reset(samples / channels)
    }

    private fun writeAll(host: PawsHostRuntime, byteCount: Int) {
        var written = 0
        val deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS
        while (written < byteCount) {
            val accepted = host.writePcm(input, written, byteCount - written)
            if (accepted < 0) error("PAWS input ring error=$accepted")
            if (accepted > 0) {
                written += accepted
                continue
            }
            if (SystemClock.elapsedRealtime() >= deadline) error("PAWS input ring stalled")
            Thread.yield()
        }
    }

    private fun drain(host: PawsHostRuntime, destination: FloatArray, sampleCount: Int) {
        var emitted = 0
        val deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS
        while (emitted < sampleCount) {
            if (pendingRead < pendingWrite) {
                val bytes = minOf(sampleCount - emitted, (pendingWrite - pendingRead) / 4)
                pendingFloat.position(pendingRead / 4)
                pendingFloat.get(destination, emitted, bytes)
                pendingRead += bytes * 4
                emitted += bytes
                if (pendingRead == pendingWrite) clearPending()
                continue
            }
            val result = host.readPcm(readBuffer)
            if (result < 0) error("PAWS output.cc read error=$result")
            if (result == 0) {
                if (SystemClock.elapsedRealtime() >= deadline) error("PAWS output.cc stalled")
                Thread.yield()
                continue
            }
            check(result % 4 == 0) { "PAWS output is not Float32 aligned: $result" }
            check(result <= pending.capacity() - pendingWrite) { "PAWS output ring overflow" }
            readBuffer.position(0).limit(result)
            pending.position(pendingWrite)
            pending.put(readBuffer)
            pendingWrite += result
        }
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
    }

    override fun reset() {
        ReferenceEngineLoader.pawsHost?.closePcm()
        channels = 0
        clearPending()
    }

    private fun enabled(): Boolean = AppSettings.qualityOptions.value &&
        AppSettings.referenceEngineMode.value == ReferenceEngineMode.POWERAMP

    companion object {
        private const val FRAME_BYTES = 256 * 1024
        private const val STALL_TIMEOUT_MS = 2_000L
    }
}
