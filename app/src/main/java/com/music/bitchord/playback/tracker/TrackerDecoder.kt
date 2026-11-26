package com.music.bitchord.playback.tracker

import java.nio.ByteBuffer

/**
 * A single-play libopenmpt module decoder producing 48 kHz stereo Float32 PCM.
 *
 * Both input and output must be direct buffers. Native creation copies/loads
 * the module, so [input] can be reused immediately after construction. Reads
 * write at [ByteBuffer.position] without changing Java buffer positions and
 * allocate no wrapper-side memory.
 */
class TrackerDecoder(input: ByteBuffer) : AutoCloseable {
    private var handle: Long

    /** Duration in microseconds, or [TIME_UNSET] when libopenmpt cannot estimate it. */
    val durationUs: Long

    var positionFrame: Long = 0
        private set

    val positionUs: Long
        get() = framesToUs(positionFrame)

    init {
        require(input.isDirect) { "Tracker input must be a direct ByteBuffer." }
        require(input.remaining() > 0) { "Tracker input is empty." }
        handle = nativeCreate(input, input.position(), input.remaining())
        require(handle != 0L) { "libopenmpt could not load this tracker module." }
        durationUs = nativeDurationUs(handle)
    }

    /**
     * Renders at most [frameCount] frames into [output]. Returns zero at EOS.
     * The output's position must be four-byte aligned.
     */
    fun read(output: ByteBuffer, frameCount: Int): Int {
        check(handle != 0L) { "Tracker decoder is closed." }
        require(output.isDirect) { "Tracker output must be a direct ByteBuffer." }
        require(frameCount >= 0) { "Negative tracker frame count." }
        require((output.position() and 3) == 0) { "Float32 output position is not aligned." }
        require(frameCount <= output.remaining() / BYTES_PER_FRAME) { "Tracker output is too small." }
        if (frameCount == 0) return 0
        val produced = nativeRead(handle, output, output.position(), frameCount)
        check(produced >= 0) { "Invalid native tracker output buffer." }
        positionFrame += produced.toLong()
        return produced
    }

    /** Seeks and returns the approximate sample-aligned position in microseconds. */
    fun seek(timeUs: Long): Long {
        check(handle != 0L) { "Tracker decoder is closed." }
        require(timeUs >= 0) { "Negative tracker seek position." }
        positionFrame = nativeSeek(handle, timeUs)
        check(positionFrame >= 0) { "Native tracker seek failed." }
        return positionUs
    }

    fun reset() {
        check(handle != 0L) { "Tracker decoder is closed." }
        nativeReset(handle)
        positionFrame = 0
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    private external fun nativeCreate(input: ByteBuffer, offset: Int, length: Int): Long
    private external fun nativeDurationUs(handle: Long): Long
    private external fun nativeRead(handle: Long, output: ByteBuffer, offset: Int, frameCount: Int): Int
    /** Returns the new position as a frame index. */
    private external fun nativeSeek(handle: Long, timeUs: Long): Long
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 4
        const val BYTES_PER_FRAME = CHANNEL_COUNT * BYTES_PER_SAMPLE
        const val TIME_UNSET = -1L

        @JvmStatic
        fun framesToUs(frames: Long): Long {
            if (frames <= 0) return 0
            val seconds = frames / SAMPLE_RATE
            val remainder = frames % SAMPLE_RATE
            if (seconds > Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE
            return seconds * 1_000_000L + remainder * 1_000_000L / SAMPLE_RATE
        }

        init {
            System.loadLibrary("bitchord_tracker")
        }
    }
}
