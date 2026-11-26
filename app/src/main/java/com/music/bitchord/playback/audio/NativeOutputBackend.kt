package com.music.bitchord.playback.audio

enum class NativeOutputBackend(val stored: String, val label: String) {
    AUDIO_TRACK("audio_track", "AudioTrack"),
    AAUDIO("aaudio", "AAudio"),
    OPENSL_ES("opensl_es", "OpenSL ES");

    companion object {
        fun fromStored(value: String?): NativeOutputBackend =
            entries.firstOrNull { it.stored == value } ?: AUDIO_TRACK
    }
}
