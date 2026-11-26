package com.music.bitchord.playback.reference.paws

/**
 * Recovered ABI constants for the exact packaged ARM64 libauriscore.so.
 *
 * This is declaration-only metadata, not a PCM implementation. Function addresses are
 * deliberately not used at runtime. See docs/native_binary_audit.md for the binary hash.
 */
object PawsAbi {
    const val AURISCORE_SHA256 = "b48e094d1f2adb371e3d461b62fe75bfc21656a1157c29126ceebaa7ac5d1524"

    // pa_alloc_plugin_context(plugin, byteCount): calloc(1, byteCount), then these writes.
    const val PA_PLUGIN_CONTEXT_MAGIC = 0x54505344
    const val PA_PLUGIN_CONTEXT_MAGIC_OFFSET = 8

    // pa_register_decoder(decoder) sets arg1=0 and tail-calls internal_register_decoder.
    const val PA_DECODER_TYPE_OFFSET = 4
    const val PA_PLUGIN_TYPE_DECODER = 1
    const val PA_PLUGIN_TYPE_DSP = 2
    const val PA_PLUGIN_TYPE_OUTPUT = 3
    const val PA_REGISTER_DECODER_EXTERNAL_ARG1 = 0
    const val INTERNAL_REGISTER_DECODER_BUILTIN_ARG1 = 1

    /** Offsets checked for non-null callbacks by internal_register_decoder, not callback names. */
    val PA_DECODER_REQUIRED_NON_NULL_OFFSETS = longArrayOf(0x48, 0x50, 0x60, 0x68, 0x80)

    const val PA_PLUGIN_PACKAGE = "com.auris.audioplayer"

    /**
     * Short names from the five built-in descriptor tables. Their internal_register_decoder
     * call sites are 0x308120, 0x30e44c, 0x30ffe0, 0x310a00 and 0x3124dc respectively.
     */
    val BUILTIN_DECODER_SHORT_NAMES = listOf(
        "decoder.ffmpeg",
        "decoder.wv",
        "decoder.mpc",
        "decoder.mod",
        "decoder.amedia",
    )

    const val PA_PLUGIN_TYPE_INTERNAL_SUBSYSTEM = Int.MIN_VALUE
    const val PA_PLUGIN_TYPE_INTERNAL_DSP = -0x7ffffffe
    const val PA_PLUGIN_TYPE_INTERNAL_DSPI = -0x7ffffff8
    const val PA_PLUGIN_TYPE_INTERNAL_OUTPUT = -0x7ffffffd
    const val PA_PLUGIN_TYPE_INTERNAL_RESAMPLER = -0x7ffffff9
    const val PA_PLUGIN_TYPE_INTERNAL_DECODER = -0x7fffffff
    const val PA_PLUGIN_TYPE_UNKNOWN = -1

    const val NATIVE_GET_PLUGINS_SIGNATURE =
        "()[Lcom/auris/audioplayer/plugin/NativePluginInfo;"

    // 32-byte plugin message prefix, represented as eight little-endian ints.
    const val PA_PLUGIN_MSG_HEADER_BYTES = 32
    const val PA_PLUGIN_MSG_HEADER_INTS = 8
    const val PA_PLUGIN_MSG_TAG = 0xF1F2F3F4.toInt()
    const val PA_PLUGIN_MSG_PLUGIN_ID_INDEX = 0
    const val PA_PLUGIN_MSG_TAG_INDEX = 4
    const val PA_PLUGIN_MSG_ID_INDEX = 5
    const val PA_PLUGIN_MSG_FLAGS_INDEX = 6
    const val PA_PLUGIN_MSG_DATA_SIZE_INDEX = 7
    const val PA_PLUGIN_MSG_DATA_INDEX = 8
    const val PA_PLUGIN_MSG_MAX_DATA_BYTES = 4096
    const val PA_PLUGIN_MSG_MAX_DATA_INTS = 1024

    val PIPELINE2_NATIVE_SIGNATURES = mapOf(
        "native_test_mode" to listOf("(J)V"),
        "native_create" to listOf("()J"),
        "native_build" to listOf("(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;I)I"),
        "native_release" to listOf("(J)I"),
        "native_request_reconfigure" to listOf("(J)I"),
        "native_request_open" to listOf("(JLjava/lang/String;I[BIII)I"),
        "native_resume" to listOf("(JZ)I"),
        "native_pause" to listOf("(JZ)I"),
        "native_pause_hard" to listOf("(J)I"),
        "native_stop" to listOf("(JZZ)I"),
        "native_seek" to listOf("(JIII)V"),
        "native_get_state" to listOf("(J)I"),
        "native_sleep_until_java_cmd" to listOf("(J)I"),
        "native_post_cmd" to listOf("(JII)V"),
        "native_get_position_ms" to listOf("(JI)I"),
        "native_set_end_event_position_ms" to listOf("(JII)V"),
        "native_get_int_param" to listOf("(JIII)I"),
        "native_get_long_param" to listOf("(JIIJ)J"),
        "native_route_msg" to listOf(
            "(J[BI)Z",
            "(J[II)Z",
            "(JIIII)Z",
            "(JIIIII)Z",
            "(JIIIIIII)Z",
            "(JIIIIIIII)Z",
            "(JIIIIIIIII)Z",
        ),
    )

    const val CC_NATIVE_GET_SAMPLES_SIGNATURE = "(J[B)I"
    const val CC_NATIVE_REGISTER_SIGNATURE =
        "(JLcom/auris/audioplayer/output/cc/CcPluginService;)V"
    const val CC_NATIVE_REF_RELEASE_SIGNATURE = "(J)V"

    const val PIPELINE_OUTPUT_SUBSYSTEM = 3
    const val CC_PARAM_OUTPUT_CONTEXT = 0x1000
    const val CC_PARAM_NATIVE_REF = 0x1001
    const val CC_PARAM_PACKET_FRAMES = 0x1003
    const val CC_PARAM_PACKET_BYTES = 0x1004
    const val CC_PARAM_OUTPUT_BUFFER_MS = 0x1005

    const val DIRECT_STATE_BLOB_BYTES = 16
    const val PIPELINE_INFO_BLOB_BYTES = 68
    const val PIPELINE_INFO_OUTPUT_CAPS_OFFSET = 0
    const val PIPELINE_INFO_OUTPUT_SAMPLE_RATE_OFFSET = 32
    const val PIPELINE_INFO_OUTPUT_FORMAT_OFFSET = 36

    const val PCM_S16 = 1
    const val PCM_S32 = 2
    const val PCM_FLOAT32 = 3
    const val PCM_FLOAT64 = 4
    const val PCM_S24_PACKED = 20
    const val PCM_U8 = 30

    /** Exact formats accepted by CcPluginService.handlePipelineLoad. */
    val CC_SUPPORTED_PCM_FORMATS = setOf(PCM_S32, PCM_S24_PACKED, PCM_FLOAT32)

    fun bytesPerSample(format: Int): Int = when (format) {
        PCM_S16 -> 2
        PCM_S32, PCM_FLOAT32 -> 4
        PCM_FLOAT64 -> 8
        PCM_S24_PACKED -> 3
        PCM_U8 -> 1
        else -> 0
    }
}
