package com.music.bitchord.playback.audio

import java.nio.ByteBuffer

/** Direct-buffer JNI contract for the self-contained 64-bit PCM engine. */
object AurisDspNative {
    const val PARAM_COUNT = 94

    val available: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { System.loadLibrary("bitchord_aurisdsp") }.isSuccess
    }

    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeConfigure(handle: Long, sampleRate: Int, channels: Int, maxFrames: Int): Boolean
    @JvmStatic external fun nativeProcess(handle: Long, directFloatBuffer: ByteBuffer, frames: Int)
    @JvmStatic external fun nativeSetParams(handle: Long, values: FloatArray)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeGetVersionHash(): String
}
