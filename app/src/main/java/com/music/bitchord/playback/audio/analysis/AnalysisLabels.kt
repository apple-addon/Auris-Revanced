/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.audio.analysis

/**
 * UI labels for the analysis surface, kept verbatim from the reference
 * strings so the wording matches what listeners already know. Kept as
 * constants (not string resources) because this lane owns no res files.
 */
object AnalysisLabels {
    const val TITLE = "Audio Analysis"
    const val SHORT = "Analysis"

    const val LEADING_SILENCE = "Leading Silence"
    const val TRAILING_SILENCE = "Trailing Silence"
    const val INTERNAL_GAPS = "Internal Gaps"
    const val GAPS_NONE = "None"
    const val SPECTROGRAM = "Spectrogram"
    const val ALL_CHANNELS = "All"
    const val CH_L = "L"
    const val CH_R = "R"

    const val BASS = "Bass"
    const val MID = "Mid"
    const val TREBLE = "Treble"
    const val SUBSONIC = "Subsonic"
    const val ULTRASONIC = "Ultrasonic"

    const val PEAK = "Peak"
    const val TRUE_PEAK = "True Peak"
    const val DC_OFFSET = "DC Offset"
    const val RMS = "RMS"
    const val CREST = "Crest Factor"
    const val DYNAMIC_RANGE = "Dynamic Range"
    const val PER_CHANNEL = "Per Channel"
    const val CLIPPING = "Clipping"
    const val SPECTRAL_CUTOFF = "Spectral Cutoff"
    const val NYQUIST = "Nyquist"
    const val CORRELATION = "Correlation %.2f"
    const val SIDE_MID = "Side/Mid"
    const val IDENTICAL_CHANNELS = "Identical L and R"
    const val REVERSED_POLARITY = "Likely reversed polarity"
    const val LOUDNESS = "Loudness"
    const val LRA = "Range"
    const val MAX_MOMENTARY = "Max Momentary"
    const val MAX_SHORT_TERM = "Max Short-Term"
    const val PLR = "PLR"
    const val HEADROOM = "Headroom"

    const val DURATION = "Duration"
    const val BIT_DEPTH = "Bit Depth"
    const val BITRATE = "Bitrate"
    const val DECODED_FORMAT = "Decoded Format"

    const val RG_TAG = "Tag Track Gain"
    const val RG_SUGGESTED = "Suggested Track Gain"
    const val AFTER_TAG = "After Tag Gain"
    const val AFTER_SUGGESTED = "After Suggested Gain"
    const val NO_TAG = "No tag"

    const val LOSSLESS = "Lossless"
    const val LOSSY = "Lossy"
    const val LOSSY_SOURCE = "Likely lossy source"
    const val UPSAMPLED = "Likely upsampled"
    const val UPSAMPLED_FROM = "Likely upsampled from %s"
    const val FULL_BAND = "Full band"

    const val ERR_CANT_OPEN = "Can't open the file"
    const val ERR_NO_AUDIO = "No audio decoded"
    const val ERR_NO_MEMORY = "Not enough memory"
    const val ERR_READ = "Read error"
    const val ERR_UNSUPPORTED = "Unsupported format"

    const val CHANNEL_LINE = "%1\$s: peak %2\$s, RMS %3\$s dBFS"
    const val CHANNEL_CREST_DR = "crest %1\$s dB, DR %2\$d"
    const val CLIPPING_D_D = "%1\$d events, %2\$d samples"
    const val CLIP_POSITIONS = "first at %1\$s, longest %2\$d samples at %3\$s"
    const val GAPS_D_S_S = "%1\$d, longest %2\$s at %3\$s"
    const val PADDED_D_D = "Padded: %1\$d-bit content in %2\$d-bit"
    const val INT_D = "%d bit integer"
    const val FLOAT_D = "%d bit float"
    const val OVER_CEILING_TAG = "True peak above %s after the tag gain"
    const val OVER_CEILING_SUGGESTED = "True peak above %s after the suggested gain"
    const val D_OF_D_CHANNELS = "%1\$d of %2\$d channels analyzed"

    // Info hints (reference wording).
    const val HINT_PEAK = "The highest sample level. 0 dBFS is the digital full scale"
    const val HINT_TRUE_PEAK = "The peak between the samples, as a DAC reconstructs it. Above 0 dBTP it may clip on playback"
    const val HINT_RMS = "The average level over all channels"
    const val HINT_CREST = "The peak minus RMS (average level). Lower - more compressed"
    const val HINT_DR = "TT DR: the loudest 20% of the 3 s blocks against the peak. Higher number - less compressed"
    const val HINT_DC = "The average level of the most offset channel, in % of the full scale. Should be near zero"
    const val HINT_CLIPPING = "Runs of 3+ samples at the full scale: the events and their samples, then where the first and the longest start"
    const val HINT_CUTOFF = "The highest frequency where the spectrum stays within 60 dB of its 1-5 kHz level. A low cutoff often means a lossy or upsampled source"
    const val HINT_NYQUIST = "Half the sample rate - the highest frequency this sample rate can hold"
    const val HINT_BASS = "The bass (20-250 Hz) energy relative to the whole 20 Hz-20 kHz energy"
    const val HINT_MID = "The mid (250 Hz-4 kHz) energy relative to the whole 20 Hz-20 kHz energy"
    const val HINT_TREBLE = "The treble (4-20 kHz) energy relative to the whole 20 Hz-20 kHz energy"
    const val HINT_SUBSONIC = "The energy below 20 Hz relative to the 20 Hz-20 kHz energy. Inaudible, but takes headroom"
    const val HINT_ULTRASONIC = "The energy above 20 kHz relative to the 20 Hz-20 kHz energy. Inaudible, often noise or DSD noise shaping"
    const val HINT_LOUDNESS = "The perceived loudness of the whole track"
    const val HINT_LRA = "How much the loudness varies over the track"
    const val HINT_MAX_MOMENTARY = "The loudest 400 ms of the track and where it starts"
    const val HINT_MAX_SHORT_TERM = "The loudest 3 s of the track and where it starts"
    const val HINT_PLR = "Peak to loudness ratio: the true peak minus the loudness. Lower - more compressed or limited"
    const val HINT_HEADROOM = "The room between the true peak and %s. Negative if the peak is above it"
    const val HINT_SIDE_MID = "Stereo width: the side (L-R) energy vs. the mid (L+R) energy. Lower is narrower, mono is identical channels"
    const val HINT_STEREO = "How alike the left and right channels are: 0 - unrelated, 1 - the same (mono), negative - opposite in phase (may cancel out in mono)"
    const val HINT_PER_CHANNEL = "Each channel's peak and RMS (average level), their difference (crest factor) and dynamic range (DR)"
}
