package com.music.bitchord.playback.dsd.raw

/** Entry point for raw, timestamped DSF and DFF packet readers. */
object RawDsdReaders {
    @JvmOverloads
    fun open(
        input: RawDsdInput,
        packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE,
        dstDecoderFactory: RawDstDecoderFactory = NativeRawDstDecoderFactory,
    ): RawDsdPacketReader = when (String(input.readBytes(0, 4), Charsets.US_ASCII)) {
        "DSD " -> openDsf(input, packing)
        "FRM8" -> openDff(input, packing, dstDecoderFactory)
        else -> throw IllegalArgumentException("Not a DSF or DFF bitstream.")
    }

    @JvmOverloads
    fun openDsf(input: RawDsdInput, packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE): RawDsdPacketReader =
        DsfRawDsdReader.open(input, packing)

    @JvmOverloads
    fun openDff(
        input: RawDsdInput,
        packing: RawDsdPacking = RawDsdPacking.DSD_U32_BE,
        dstDecoderFactory: RawDstDecoderFactory = NativeRawDstDecoderFactory,
    ): RawDsdPacketReader = DffRawDsdReader.open(input, packing, dstDecoderFactory)
}
