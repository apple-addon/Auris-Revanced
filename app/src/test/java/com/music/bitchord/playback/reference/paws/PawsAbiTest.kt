package com.music.bitchord.playback.reference.paws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PawsAbiTest {
    @Test
    fun `decoder registration ABI matches native checks`() {
        assertEquals(1, PawsAbi.PA_PLUGIN_TYPE_DECODER)
        assertEquals(4, PawsAbi.PA_DECODER_TYPE_OFFSET)
        assertEquals(0, PawsAbi.PA_REGISTER_DECODER_EXTERNAL_ARG1)
        assertEquals(1, PawsAbi.INTERNAL_REGISTER_DECODER_BUILTIN_ARG1)
        assertEquals(
            listOf(0x48L, 0x50L, 0x60L, 0x68L, 0x80L),
            PawsAbi.PA_DECODER_REQUIRED_NON_NULL_OFFSETS.toList(),
        )
        assertEquals(8, PawsAbi.PA_PLUGIN_CONTEXT_MAGIC_OFFSET)
        assertEquals(0x54505344, PawsAbi.PA_PLUGIN_CONTEXT_MAGIC)
    }

    @Test
    fun `built in decoder descriptor names are exact and ordered`() {
        assertEquals(
            listOf("decoder.ffmpeg", "decoder.wv", "decoder.mpc", "decoder.mod", "decoder.amedia"),
            PawsAbi.BUILTIN_DECODER_SHORT_NAMES,
        )
        assertEquals(5, PawsAbi.BUILTIN_DECODER_SHORT_NAMES.distinct().size)
    }

    @Test
    fun `plugin message header has native tag and exact layout`() {
        assertEquals("f1f2f3f4", Integer.toHexString(PawsAbi.PA_PLUGIN_MSG_TAG))
        assertEquals(-0x0e0d0c0c, PawsAbi.PA_PLUGIN_MSG_TAG)
        assertEquals(32, PawsAbi.PA_PLUGIN_MSG_HEADER_BYTES)
        assertEquals(8, PawsAbi.PA_PLUGIN_MSG_HEADER_INTS)
        assertEquals(8, PawsAbi.PA_PLUGIN_MSG_DATA_INDEX)
        assertEquals(44, PawsAbi.PA_PLUGIN_MSG_HEADER_BYTES + 3 * Int.SIZE_BYTES)
        assertEquals(PawsAbi.PA_PLUGIN_MSG_DATA_INDEX, PawsAbi.PA_PLUGIN_MSG_DATA_SIZE_INDEX + 1)
    }

    @Test
    fun `pipeline and CC signatures preserve JNI descriptors`() {
        val methods = PawsAbi.PIPELINE2_NATIVE_SIGNATURES
        assertEquals(19, methods.size)
        assertEquals(25, methods.values.sumOf { it.size })
        assertEquals(listOf("()J"), methods.getValue("native_create"))
        assertEquals(
            listOf("(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;I)I"),
            methods.getValue("native_build"),
        )
        assertEquals(listOf("(JLjava/lang/String;I[BIII)I"), methods.getValue("native_request_open"))
        assertEquals(listOf("(JIII)I"), methods.getValue("native_get_int_param"))
        assertEquals(listOf("(JIIJ)J"), methods.getValue("native_get_long_param"))
        assertEquals(7, methods.getValue("native_route_msg").size)
        assertTrue("(J[BI)Z" in methods.getValue("native_route_msg"))
        assertTrue("(J[II)Z" in methods.getValue("native_route_msg"))
        assertEquals("(J[B)I", PawsAbi.CC_NATIVE_GET_SAMPLES_SIGNATURE)
        assertEquals("(J)V", PawsAbi.CC_NATIVE_REF_RELEASE_SIGNATURE)
        assertEquals(
            "(JLcom/auris/audioplayer/output/cc/CcPluginService;)V",
            PawsAbi.CC_NATIVE_REGISTER_SIGNATURE,
        )
    }

    @Test
    fun `CC params and info offsets match the recovered readers`() {
        assertEquals(3, PawsAbi.PIPELINE_OUTPUT_SUBSYSTEM)
        assertEquals(0x1000, PawsAbi.CC_PARAM_OUTPUT_CONTEXT)
        assertEquals(0x1001, PawsAbi.CC_PARAM_NATIVE_REF)
        assertEquals(0x1003, PawsAbi.CC_PARAM_PACKET_FRAMES)
        assertEquals(0x1004, PawsAbi.CC_PARAM_PACKET_BYTES)
        assertEquals(0x1005, PawsAbi.CC_PARAM_OUTPUT_BUFFER_MS)
        assertEquals(68, PawsAbi.PIPELINE_INFO_BLOB_BYTES)
        assertEquals(32, PawsAbi.PIPELINE_INFO_OUTPUT_SAMPLE_RATE_OFFSET)
        assertEquals(36, PawsAbi.PIPELINE_INFO_OUTPUT_FORMAT_OFFSET)
    }

    @Test
    fun `PCM IDs and widths are exact and CC rejects other formats`() {
        assertEquals(setOf(2, 20, 3), PawsAbi.CC_SUPPORTED_PCM_FORMATS)
        assertEquals(2, PawsAbi.bytesPerSample(PawsAbi.PCM_S16))
        assertEquals(4, PawsAbi.bytesPerSample(PawsAbi.PCM_S32))
        assertEquals(4, PawsAbi.bytesPerSample(PawsAbi.PCM_FLOAT32))
        assertEquals(8, PawsAbi.bytesPerSample(PawsAbi.PCM_FLOAT64))
        assertEquals(3, PawsAbi.bytesPerSample(PawsAbi.PCM_S24_PACKED))
        assertEquals(1, PawsAbi.bytesPerSample(PawsAbi.PCM_U8))
        assertEquals(0, PawsAbi.bytesPerSample(-1))
        assertTrue(PawsAbi.PCM_S32 in PawsAbi.CC_SUPPORTED_PCM_FORMATS)
        assertFalse(PawsAbi.PCM_FLOAT64 in PawsAbi.CC_SUPPORTED_PCM_FORMATS)
    }
}
