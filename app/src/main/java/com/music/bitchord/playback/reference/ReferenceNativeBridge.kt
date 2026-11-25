package com.music.bitchord.playback.reference

import java.nio.ByteBuffer

/** JNI entry points implemented by libauris_native_bridge.so. */
object ReferenceNativeBridge {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("auris_native_bridge")
            loaded = true
        }
    }

    external fun nativeProbe(engine: String): String
    external fun nativePrepareHibyBuffer(capacityBytes: Int): Int
    external fun nativeCreateHibyTrack(sampleRate: Int, channels: Int, sampleBits: Int, formatType: Int): Int
    external fun nativeWriteHibyPcm(buffer: ByteBuffer, byteCount: Int): Int
    external fun nativeHibyStreamStart(): Int
    external fun nativeHibyStreamEnd(): Int
    external fun nativeHibyRelease(): Int
    external fun nativeHibyDestruct(): Int
    external fun nativeHibyLatency(): Int
    external fun nativeHibyNowConsumed(output: LongArray): Long
    external fun nativeCloseHiby()
}
