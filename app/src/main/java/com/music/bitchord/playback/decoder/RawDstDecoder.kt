package com.music.bitchord.playback.decoder

/** Lossless DST frame decoder contract; implementations return raw DSD, never PCM. */
interface DstFrameDecoder : AutoCloseable {
    fun decode(frame: ByteArray): ByteArray
}

/** Real one-to-six-channel DST decoder backed by the app's existing LGPL DST C core. */
class NativeRawDstDecoder(channels: Int) : DstFrameDecoder {
    private val outputSize = SACD_BYTES_PER_CHANNEL_FRAME * channels
    private var handle: Long

    init {
        require(channels in 1..6)
        handle = create(channels)
        check(handle != 0L) { "Native DST decoder could not start." }
    }

    override fun decode(frame: ByteArray): ByteArray {
        check(handle != 0L) { "DST decoder is closed." }
        require(frame.size in 2..65_536) { "Invalid DST frame size." }
        val output = ByteArray(outputSize)
        val result = decode(handle, frame, output)
        if (result < 0) throw SacdFormatException("Native DST decoder rejected a frame (status $result).")
        if (result != outputSize) throw SacdFormatException("Native DST decoder returned $result bytes, expected $outputSize.")
        return output
    }

    override fun close() {
        if (handle != 0L) {
            destroy(handle)
            handle = 0
        }
    }

    private external fun create(channels: Int): Long
    private external fun decode(handle: Long, input: ByteArray, output: ByteArray): Int
    private external fun destroy(handle: Long)

    private companion object {
        init { System.loadLibrary("bitchord_dst") }
    }
}
