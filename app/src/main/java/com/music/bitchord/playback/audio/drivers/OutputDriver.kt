package com.music.bitchord.playback.audio.drivers

/** User-selectable physical output backend, independent of reference DSP mode. */
enum class OutputDriver(val label: String, val sharingLabel: String) {
    AUDIO_TRACK("AudioTrack", "Shared"),
    HI_RES("Hi-Res", "Direct"),
    USB_EXCLUSIVE("USB Exclusive", "Exclusive"),
    AAUDIO("AAudio", "Exclusive / MMAP"),
    OPENSL_ES("OpenSL ES", "Shared"),
    USB_SYSTEM_BIT_PERFECT("USB System Bit-Perfect", "Direct"),
}

enum class DsdOutputMode(val label: String, val nativeValue: Int) {
    PCM("PCM", 1),
    DOP("DoP", 2),
    NATIVE_RAW("Native Raw", 4),
}

data class OutputDriverTelemetry(
    val driver: String,
    val sharing: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bufferMs: Int,
    val xruns: Long?,
    val detail: String? = null,
)
