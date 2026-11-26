/*
 * Copyright (C) 2026 BitChord
 *
 * The pipeline stage model, as data. These tests pin the things the timeline
 * relies on: the stage order and count, the "01".."07" numbering the section
 * titles are prefixed with, and that every href a stage links to resolves to
 * a real in-app target instead of rendering as a dead link. Pure JVM — no
 * Compose runtime, no Robolectric — so they run on any host.
 */

package com.music.bitchord.ui.components

import com.music.bitchord.playback.audio.analysis.PipelineLinkTarget
import com.music.bitchord.playback.audio.analysis.ReferenceLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineStageSpecTest {

    @Test
    fun stagesAreInSignalOrderWithAnalysisLast() {
        assertEquals(
            listOf(
                "track_info",
                "decoder",
                "resampler",
                "dsp",
                "mseb_peq",
                "output",
                "analysis",
            ),
            PIPELINE_STAGES.map { it.key },
        )
        assertEquals("analysis", PIPELINE_STAGES.last().key)
        assertTrue("MSEB & PEQ sit in the DSP half, before the output", PIPELINE_STAGES.indexOfFirst { it.key == "mseb_peq" } < PIPELINE_STAGES.indexOfFirst { it.key == "output" })
    }

    @Test
    fun stageIndicesAreSequentialAndMatchTheTimelineCount() {
        assertEquals((0 until PIPELINE_STAGES.size).toList(), PIPELINE_STAGES.map { it.index })
        assertEquals(PIPELINE_STAGES.size, PIPELINE_STAGE_COUNT)
        assertEquals(7, PIPELINE_STAGE_COUNT)
    }

    @Test
    fun stageNumberLabelsAreTwoDigit01Through07() {
        assertEquals("01", stageNumberLabel(0))
        assertEquals("05", stageNumberLabel(4))
        assertEquals("07", stageNumberLabel(6))
        PIPELINE_STAGES.forEach { stage ->
            assertEquals(
                (stage.index + 1).toString().padStart(2, '0'),
                stageNumberLabel(stage.index),
            )
        }
    }

    @Test
    fun everyStageLinkHrefMapsToAnInAppTarget() {
        val hrefs = PIPELINE_STAGES.flatMap { it.linkHrefs }
        assertTrue("the pipeline must link somewhere", hrefs.isNotEmpty())
        hrefs.forEach { href ->
            assertNotNull("unmapped href: $href", ReferenceLinks.map(href))
        }
    }

    @Test
    fun referenceHrefsFromTheTaskMapToOurSections() {
        assertEquals(PipelineLinkTarget.DVC, ReferenceLinks.map("settings/audio_dvc#compensate_dvc_vol"))
        assertEquals(PipelineLinkTarget.BIT_PERFECT, ReferenceLinks.map("settings/audio_bit_perfect#global_follow_sr"))
        assertEquals(PipelineLinkTarget.PEQ, ReferenceLinks.map("settings/peq_equ#peq_blocksize"))
        assertEquals(PipelineLinkTarget.AUDIO_OUTPUTS, ReferenceLinks.map("settings/audio_outputs2"))
        assertEquals(PipelineLinkTarget.AUDIO_FOCUS, ReferenceLinks.map("settings/audio_focus"))
        assertEquals(PipelineLinkTarget.RESAMPLER, ReferenceLinks.map("settings/audio_resampler"))
        assertEquals(PipelineLinkTarget.ANALYSIS, ReferenceLinks.map("analysis"))
    }

    @Test
    fun everyLinkTargetHasAnInCardPanel() {
        PIPELINE_STAGES
            .flatMap { it.linkHrefs }
            .map { ReferenceLinks.map(it) }
            .forEach { target ->
                // toPanel() is the UI-side mapping; it must be total.
                assertNotNull(target)
                assertNotNull(target!!.toPanel())
            }
    }
}
