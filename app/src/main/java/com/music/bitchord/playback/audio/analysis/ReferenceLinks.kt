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
 * Where a settings hyperlink points inside Auris ReVanced.
 *
 * The reference app addresses its own settings with hrefs like
 * `settings/audio_dvc#compensate_dvc_vol` or `settings/peq_equ`. Those pages
 * do not exist here; [ReferenceLinks.map] translates both those reference
 * hrefs and our own logical hrefs onto the Audiophile settings sections this
 * app actually has, so a link in the pipeline opens the matching page in-app
 * instead of leaving the dialog.
 */
enum class PipelineLinkTarget {
    AUDIO_SETTINGS,
    AUDIO_INFO,
    EQUALIZER,
    RESAMPLER,
    DVC,
    BIT_PERFECT,
    AUDIO_OUTPUTS,
    ADVANCED,
    AUDIO_FOCUS,
    FADES,
    REPLAY_GAIN,
    REFERENCE_MODE,
    MSEB,
    PEQ,
    ANALYSIS,
}

object ReferenceLinks {

    /** Display label for a link row, e.g. "DVC" / "Parametric EQ". */
    fun label(target: PipelineLinkTarget): String = when (target) {
        PipelineLinkTarget.AUDIO_SETTINGS -> "Audio settings"
        PipelineLinkTarget.AUDIO_INFO -> "Audio Info"
        PipelineLinkTarget.EQUALIZER -> "Equalizer"
        PipelineLinkTarget.RESAMPLER -> "Resampler"
        PipelineLinkTarget.DVC -> "DVC"
        PipelineLinkTarget.BIT_PERFECT -> "Bit Perfect"
        PipelineLinkTarget.AUDIO_OUTPUTS -> "Audio Outputs"
        PipelineLinkTarget.ADVANCED -> "Advanced Tweaks"
        PipelineLinkTarget.AUDIO_FOCUS -> "Audio Focus"
        PipelineLinkTarget.FADES -> "Crossfade, Fade, and Gapless"
        PipelineLinkTarget.REPLAY_GAIN -> "Replay Gain"
        PipelineLinkTarget.REFERENCE_MODE -> "Reference Mode"
        PipelineLinkTarget.MSEB -> "MSEB"
        PipelineLinkTarget.PEQ -> "Parametric EQ"
        PipelineLinkTarget.ANALYSIS -> "Audio Analysis"
    }

    /**
     * Maps a reference-style href (`settings/audio_dvc#compensate_dvc_vol`,
     * `settings/audio#audio_info`, `msg/bus_data_cmd/...`) or one of our own
     * logical hrefs (`settings/mseb`, `analysis`) to a [PipelineLinkTarget].
     * Returns null for hrefs with no in-app counterpart.
     */
    fun map(href: String?): PipelineLinkTarget? {
        if (href.isNullOrBlank()) return null
        val trimmed = href.trim()
        // External URLs are not in-app settings links.
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return null
        // `settings/audio#audio_info` is the Audio Info page, not the audio
        // home: read the anchor before the path is cut down to its last segment.
        if (trimmed.contains("audio_info", true)) return PipelineLinkTarget.AUDIO_INFO
        // Normalise: strip scheme-ish prefixes, query and anchor.
        var h = trimmed.substringAfterLast('/')
        h = h.substringBefore('#').substringBefore('?').lowercase()
        if (h.isEmpty()) {
            // "settings/audio" itself -> the audio settings home.
            return if (href.trim().lowercase().endsWith("/audio") || href.trim().lowercase() == "settings/audio") {
                PipelineLinkTarget.AUDIO_SETTINGS
            } else null
        }
        return when {
            h == "analysis" || h == "audio_analysis" || h.contains("cmd_data_info") ->
                PipelineLinkTarget.ANALYSIS
            h.contains("mseb") -> PipelineLinkTarget.MSEB
            h.contains("peq") -> PipelineLinkTarget.PEQ
            h.contains("audio_dvc") || h == "dvc" -> PipelineLinkTarget.DVC
            h.contains("audio_bit_perfect") || h.contains("bit_perfect") -> PipelineLinkTarget.BIT_PERFECT
            h.contains("audio_outputs") || h.contains("audio_output") -> PipelineLinkTarget.AUDIO_OUTPUTS
            h.contains("audio_focus") -> PipelineLinkTarget.AUDIO_FOCUS
            h.contains("audio_tweaks") || h.contains("advanced") -> PipelineLinkTarget.ADVANCED
            h.contains("resampler") -> PipelineLinkTarget.RESAMPLER
            h.contains("replay") || h.contains("replaygain") -> PipelineLinkTarget.REPLAY_GAIN
            h.contains("reference_mode") -> PipelineLinkTarget.REFERENCE_MODE
            h.contains("fade") || h.contains("crossfade") || h.contains("gapless") -> PipelineLinkTarget.FADES
            h.contains("equalizer") || h.contains("eq_equ") || h == "eq" -> PipelineLinkTarget.EQUALIZER
            h.contains("audio_info") -> PipelineLinkTarget.AUDIO_INFO
            h == "audio" -> PipelineLinkTarget.AUDIO_SETTINGS
            else -> null
        }
    }

    /** The reference hrefs the pipeline rows are written against, for tests. */
    val KNOWN_REFERENCE_HREFS: List<Pair<String, PipelineLinkTarget>> = listOf(
        "settings/audio_dvc#compensate_dvc_vol" to PipelineLinkTarget.DVC,
        "settings/audio_dvc#no_dvc_bt_absvol" to PipelineLinkTarget.DVC,
        "settings/audio_bit_perfect" to PipelineLinkTarget.BIT_PERFECT,
        "settings/audio_bit_perfect#global_follow_sr" to PipelineLinkTarget.BIT_PERFECT,
        "settings/audio_bit_perfect#global_perfect_bit_perfect" to PipelineLinkTarget.BIT_PERFECT,
        "settings/audio_bit_perfect#global_pipeline64" to PipelineLinkTarget.BIT_PERFECT,
        "settings/audio_bit_perfect#global_dsd_remaster" to PipelineLinkTarget.BIT_PERFECT,
        "settings/peq_equ#peq_blocksize" to PipelineLinkTarget.PEQ,
        "settings/audio_outputs2" to PipelineLinkTarget.AUDIO_OUTPUTS,
        "settings/audio_outputs_tweaks#usb_activity" to PipelineLinkTarget.AUDIO_OUTPUTS,
        "settings/audio_output_device_opts?device=bt#set_codec_sr" to PipelineLinkTarget.AUDIO_OUTPUTS,
        "settings/audio_focus#af_short_duck" to PipelineLinkTarget.AUDIO_FOCUS,
        "settings/audio_focus#resume_on_focus" to PipelineLinkTarget.AUDIO_FOCUS,
        "settings/audio_tweaks#mod_interpolation" to PipelineLinkTarget.ADVANCED,
        "settings/audio#audio_info" to PipelineLinkTarget.AUDIO_INFO,
        "msg/bus_data_cmd/cmd_data_info_tags?arg1=1" to PipelineLinkTarget.ANALYSIS,
        "settings/audio" to PipelineLinkTarget.AUDIO_SETTINGS,
    )
}
