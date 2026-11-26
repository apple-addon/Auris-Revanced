package com.music.bitchord.playback.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ExoPlayer-side owner for the direct-buffer native 64-bit reference chain. */
class NativeReferenceProcessor : FloatAudioProcessor {
    private var handle = 0L
    private var channels = 0
    private var direct: ByteBuffer? = null
    private var floats: java.nio.FloatBuffer? = null

    @Volatile
    private var active = false

    @Volatile
    var runsPowerampStages = false
        private set

    val isEnabled: Boolean
        get() = active && handle != 0L

    fun setParams(values: FloatArray) {
        require(values.size == AurisDspNative.PARAM_COUNT) { "Expected 94 native parameters" }
        val mode = values[0].toInt()
        active = mode != 0 && mode != 4
        runsPowerampStages = active && (mode == 1 || mode == 3)
        if (handle != 0L) AurisDspNative.nativeSetParams(handle, values)
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        if (!AurisDspNative.available) return
        if (handle == 0L) handle = AurisDspNative.nativeCreate()
        if (handle == 0L) return
        if (channels != channelCount || direct == null) {
            channels = channelCount
            direct = ByteBuffer.allocateDirect(AudioBlock.DEFAULT_CAPACITY_FRAMES * channelCount * 4)
                .order(ByteOrder.nativeOrder())
            floats = direct?.asFloatBuffer()
        }
        check(AurisDspNative.nativeConfigure(handle, sampleRate, channelCount, AudioBlock.DEFAULT_CAPACITY_FRAMES))
    }

    override fun process(block: AudioBlock) {
        if (!isEnabled || block.frameCount == 0 || channels != block.channelCount) return
        val buffer = direct ?: return
        val view = floats ?: return
        buffer.clear()
        view.clear()
        view.put(block.samples, 0, block.sampleCount)
        AurisDspNative.nativeProcess(handle, buffer, block.frameCount)
        view.position(0)
        view.get(block.samples, 0, block.sampleCount)
    }

    override fun flush() {
        if (handle != 0L) AurisDspNative.nativeReset(handle)
    }

    override fun reset() = flush()

    fun close() {
        if (handle != 0L) {
            AurisDspNative.nativeDestroy(handle)
            handle = 0L
        }
        direct = null
        floats = null
        channels = 0
        active = false
    }
}
