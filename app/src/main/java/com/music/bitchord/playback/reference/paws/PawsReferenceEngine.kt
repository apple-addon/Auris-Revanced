package com.music.bitchord.playback.reference.paws

import java.nio.ByteBuffer

/**
 * Live PAWS Pipeline2 PCM bridge.
 *
 * Input is ExoPlayer-decoded PCM written to a host-owned WAV pipe. PAWS FFmpeg,
 * DSPI/reverb/resampler/DVC and output.cc remain inside libauriscore.so. This
 * class never starts a second player and never opens a media URI independently.
 *
 * Threading: open(), flush(), route*(), readOutputInfo(), evidence(), and close()
 * belong on one control thread, and open()/close() must be the same thread.
 * process() may run on the ExoPlayer audio thread. Do not race close()/flush()
 * with process(); stop the audio processor first. process() and its native path
 * allocate no objects, take no JVM/native mutex, and attach no threads.
 */
class PawsReferenceEngine private constructor(
    private var nativeHandle: Long,
    val inputFormat: PawsInputFormat,
    val plugins: List<PawsPluginInfo>,
) : AutoCloseable {
    private val infoScratch = LongArray(INFO_WORDS)
    private val evidenceScratch = LongArray(EVIDENCE_WORDS)

    val isOpen: Boolean
        get() = nativeHandle != 0L

    /**
     * Consumes some/all of [input] and emits any currently available processed PCM.
     * Partial input consumption is normal pipe backpressure. Call again with the
     * same remaining buffer. Pass a null input to drain without ending the stream.
     * Both buffers must be direct and remain alive for the call.
     */
    fun process(
        input: ByteBuffer?,
        output: ByteBuffer,
        result: PawsProcessResult,
        endOfInput: Boolean = false,
    ) {
        val handle = requireOpen()
        require(output.isDirect) { "PAWS output must be a direct ByteBuffer" }
        require(input == null || input.isDirect) { "PAWS input must be a direct ByteBuffer" }
        val inputPosition = input?.position() ?: 0
        val outputPosition = output.position()
        result.reset()
        result.error = PawsReferenceJni.nativeProcess(
            handle,
            input,
            inputPosition,
            input?.remaining() ?: 0,
            output,
            outputPosition,
            output.remaining(),
            endOfInput,
            result,
        )
        PawsBufferPositions.commit(input, inputPosition, output, outputPosition, result)
    }

    /** Drops the current pipe/graph and creates a fresh one. Not an audio-thread call. */
    fun flush(): Int = PawsReferenceJni.nativeFlush(requireOpen())

    fun readOutputInfo(): PawsOutputInfo {
        PawsReferenceJni.nativeReadOutputInfo(requireOpen(), infoScratch)
        return PawsOutputInfo(
            sampleRate = infoScratch[0].toInt(),
            channels = infoScratch[1].toInt(),
            encoding = PawsOutputEncoding.fromNative(infoScratch[2].toInt()),
            pipelineFlags = infoScratch[3],
            pipelineState = infoScratch[4].toInt(),
        )
    }

    fun evidence(): PawsEvidence {
        PawsReferenceJni.nativeReadEvidence(requireOpen(), evidenceScratch)
        return PawsEvidence(
            inputBytesWrittenToPipe = evidenceScratch[0],
            processedBytesReadFromCc = evidenceScratch[1],
            outputBytesDelivered = evidenceScratch[2],
            nativePcmTapVerified = evidenceScratch[1] > 0,
            outputInfo = readOutputInfo(),
        )
    }

    /** Routes an integer PAWS plugin message through Pipeline2.native_route_msg. */
    fun routeMessage(
        pluginId: Int,
        messageId: Int,
        flags: Int = 0,
        p1: Int = 0,
        p2: Int = 0,
        p3: Int = 0,
        p4: Int = 0,
        p5: Int = 0,
        p6: Int = 0,
        argumentCount: Int = 0,
    ): Boolean {
        require(argumentCount in 0..6) { "PAWS route supports 0..6 integer arguments" }
        return PawsReferenceJni.nativeRouteMessage(
            requireOpen(), pluginId, messageId, flags,
            p1, p2, p3, p4, p5, p6, argumentCount,
        )
    }

    /** Routes an exact PAWS wire message (including its normal 32-byte header). */
    fun routeBytes(message: ByteArray): Boolean {
        require(message.size >= MIN_WIRE_MESSAGE_BYTES) { "PAWS wire message is too short" }
        return PawsReferenceJni.nativeRouteBytes(requireOpen(), message)
    }

    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            PawsReferenceJni.nativeClose(handle)
        }
    }

    private fun requireOpen(): Long = nativeHandle.takeIf { it != 0L }
        ?: throw PawsReferenceException("PAWS engine is closed")

    companion object {
        private const val INFO_WORDS = 5
        private const val EVIDENCE_WORDS = 3
        private const val MIN_WIRE_MESSAGE_BYTES = 32

        const val ERROR_INVALID_ARGUMENT = -1001
        const val ERROR_HOST_CLASS = -1002
        const val ERROR_NATIVE_CREATE = -1003
        const val ERROR_NATIVE_BUILD = -1004
        const val ERROR_CC_CONTEXT = -1005
        const val ERROR_CC_PACKET = -1006
        const val ERROR_NATIVE_OPEN = -1007
        const val ERROR_NATIVE_RESUME = -1008
        const val ERROR_PIPE = -1009
        const val ERROR_HOST_EXCEPTION = -1010
        const val ERROR_NOT_ACTIVE = -1011
        const val ERROR_DIRECT_BUFFER = -1012
        const val ERROR_PCM_WRITE = -1013
        const val ERROR_CC_READ = -1014
        const val ERROR_CLOSED = -1015
        const val ERROR_OUTPUT_FORMAT = -1016

        @Volatile private var libraryLoaded = false

        @JvmStatic
        @Synchronized
        fun ensureNativeLibraryLoaded() {
            if (!libraryLoaded) {
                System.loadLibrary("paws_reference")
                libraryLoaded = true
            }
        }

        /** The exact PAWS host DEX and NativeLoader.load() must already be initialized. */
        @JvmStatic
        fun open(hostClassLoader: ClassLoader, inputFormat: PawsInputFormat): PawsReferenceEngine {
            ensureNativeLibraryLoaded()
            // Resolve now so a wrong loader fails before any native graph is created.
            hostClassLoader.loadClass("com.auris.audioplayer.processing.Pipeline2")
            val msgBusHost = PawsHostStubs.createMsgBusHost(hostClassLoader)
            val handle = PawsReferenceJni.nativeOpen(
                hostClassLoader,
                msgBusHost,
                inputFormat.sampleRate,
                inputFormat.channels,
                inputFormat.encoding.wavFormatTag,
                inputFormat.encoding.bytesPerSample,
                inputFormat.pipeCapacityBytes,
            )
            check(handle != 0L) { "nativeOpen returned a null PAWS handle without throwing" }
            return PawsReferenceEngine(
                nativeHandle = handle,
                inputFormat = inputFormat,
                plugins = PawsPluginManifest.parse(PawsReferenceJni.nativePluginManifest(handle)),
            )
        }

    }
}
