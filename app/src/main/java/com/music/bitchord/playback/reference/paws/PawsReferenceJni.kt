package com.music.bitchord.playback.reference.paws

import java.nio.ByteBuffer

/** JNI entry points implemented only by libpaws_reference.so. */
internal object PawsReferenceJni {
    external fun nativeOpen(
        hostClassLoader: ClassLoader,
        msgBusHost: Any,
        sampleRate: Int,
        channels: Int,
        wavFormatTag: Int,
        bytesPerSample: Int,
        pipeCapacityBytes: Int,
    ): Long

    external fun nativeClose(handle: Long)
    external fun nativeFlush(handle: Long): Int
    external fun nativePluginManifest(handle: Long): String
    external fun nativeReadOutputInfo(handle: Long, output: LongArray)
    external fun nativeReadEvidence(handle: Long, output: LongArray)

    external fun nativeProcess(
        handle: Long,
        input: ByteBuffer?,
        inputPosition: Int,
        inputByteCount: Int,
        output: ByteBuffer,
        outputPosition: Int,
        outputCapacity: Int,
        endOfInput: Boolean,
        result: PawsProcessResult,
    ): Int

    external fun nativeRouteMessage(
        handle: Long,
        pluginId: Int,
        messageId: Int,
        flags: Int,
        p1: Int,
        p2: Int,
        p3: Int,
        p4: Int,
        p5: Int,
        p6: Int,
        argumentCount: Int,
    ): Boolean

    external fun nativeRouteBytes(handle: Long, message: ByteArray): Boolean
}
