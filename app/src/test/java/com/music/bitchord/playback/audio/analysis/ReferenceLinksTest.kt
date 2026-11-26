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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hyperlink mapping: every settings href the pipeline rows are written
 * against — reference-style (`settings/audio_dvc#compensate_dvc_vol`,
 * `settings/peq_equ`, `settings/audio_outputs2`, ...) and our own logical
 * hrefs — must land on an in-app target, never on a dead link.
 */
class ReferenceLinksTest {

    @Test
    fun everyKnownReferenceHrefMaps() {
        ReferenceLinks.KNOWN_REFERENCE_HREFS.forEach { (href, expected) ->
            assertEquals("href=$href", expected, ReferenceLinks.map(href))
        }
    }

    @Test
    fun referencePagesMapToAudiophileSections() {
        assertEquals(PipelineLinkTarget.AUDIO_INFO, ReferenceLinks.map("settings/audio#audio_info"))
        assertEquals(PipelineLinkTarget.DVC, ReferenceLinks.map("settings/audio_dvc"))
        assertEquals(PipelineLinkTarget.DVC, ReferenceLinks.map("settings/audio_dvc#no_dvc_bt_absvol"))
        assertEquals(PipelineLinkTarget.BIT_PERFECT, ReferenceLinks.map("settings/audio_bit_perfect"))
        assertEquals(
            PipelineLinkTarget.BIT_PERFECT,
            ReferenceLinks.map("settings/audio_bit_perfect#global_follow_sr"),
        )
        assertEquals(PipelineLinkTarget.AUDIO_OUTPUTS, ReferenceLinks.map("settings/audio_outputs2"))
        assertEquals(
            PipelineLinkTarget.AUDIO_OUTPUTS,
            ReferenceLinks.map("settings/audio_output_device_opts?device=bt#set_codec_sr"),
        )
        assertEquals(PipelineLinkTarget.AUDIO_FOCUS, ReferenceLinks.map("settings/audio_focus#resume_on_focus"))
        assertEquals(PipelineLinkTarget.PEQ, ReferenceLinks.map("settings/peq_equ#peq_blocksize"))
        assertEquals(PipelineLinkTarget.ADVANCED, ReferenceLinks.map("settings/audio_tweaks#mod_interpolation"))
        assertEquals(PipelineLinkTarget.ANALYSIS, ReferenceLinks.map("msg/bus_data_cmd/cmd_data_info_tags?arg1=1"))
    }

    @Test
    fun logicalHrefsMapToOurOwnSurfaces() {
        assertEquals(PipelineLinkTarget.EQUALIZER, ReferenceLinks.map("settings/equalizer"))
        assertEquals(PipelineLinkTarget.RESAMPLER, ReferenceLinks.map("settings/audio_resampler"))
        assertEquals(PipelineLinkTarget.MSEB, ReferenceLinks.map("settings/mseb"))
        assertEquals(PipelineLinkTarget.PEQ, ReferenceLinks.map("settings/peq"))
        assertEquals(PipelineLinkTarget.REPLAY_GAIN, ReferenceLinks.map("settings/replay_gain"))
        assertEquals(PipelineLinkTarget.FADES, ReferenceLinks.map("settings/fades"))
        assertEquals(PipelineLinkTarget.REFERENCE_MODE, ReferenceLinks.map("settings/reference_mode"))
        assertEquals(PipelineLinkTarget.ANALYSIS, ReferenceLinks.map("analysis"))
        assertEquals(PipelineLinkTarget.AUDIO_SETTINGS, ReferenceLinks.map("settings/audio"))
    }

    @Test
    fun anchorsAndQueriesAreIgnored() {
        assertEquals(PipelineLinkTarget.DVC, ReferenceLinks.map("settings/audio_dvc#compensate_dvc_vol"))
        assertEquals(PipelineLinkTarget.DVC, ReferenceLinks.map("settings/audio_dvc?foo=bar#compensate_dvc_vol"))
        assertEquals(PipelineLinkTarget.AUDIO_OUTPUTS, ReferenceLinks.map("settings/audio_outputs2#x"))
    }

    @Test
    fun unknownAndBlankHrefsDoNotPretendToBeLinks() {
        assertNull(ReferenceLinks.map(null))
        assertNull(ReferenceLinks.map(""))
        assertNull(ReferenceLinks.map("   "))
        assertNull(ReferenceLinks.map("settings/vis#vis_keep_screen"))
        assertNull(ReferenceLinks.map("https://example.com/settings/audio_dvc"))
    }

    @Test
    fun labelsArePresentForEveryTarget() {
        PipelineLinkTarget.entries.forEach { target ->
            assertTrue("label for $target", ReferenceLinks.label(target).isNotBlank())
        }
        assertEquals("DVC", ReferenceLinks.label(PipelineLinkTarget.DVC))
        assertEquals("MSEB", ReferenceLinks.label(PipelineLinkTarget.MSEB))
        assertEquals("Parametric EQ", ReferenceLinks.label(PipelineLinkTarget.PEQ))
        assertEquals("Audio Analysis", ReferenceLinks.label(PipelineLinkTarget.ANALYSIS))
    }
}
