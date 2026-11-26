/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.playback.audio.analysis.AudioAnalysisResult
import com.music.bitchord.playback.audio.analysis.AudioAnalyzer
import com.music.bitchord.playback.audio.analysis.PcmFormat
import com.music.bitchord.playback.audio.analysis.PcmFrameSource
import com.music.bitchord.playback.audio.analysis.PipelineLinkTarget
import com.music.bitchord.ui.screens.audiophile.AudiophileSection
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

/**
 * Rendering of the analysis surface and its in-pipeline hyperlinks.
 *
 * The content is driven with a real [AudioAnalysisResult] produced by the
 * engine over a generated tone, so this exercises the labels, units and the
 * spectrogram raster path rather than a hand-built stub.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class AudioAnalysisRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun measuredResult(): AudioAnalysisResult {
        val sampleRate = 44_100
        val frames = sampleRate
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val value = (0.5 * sin(2.0 * PI * 1_000.0 * i / sampleRate)).toFloat()
            samples[2 * i] = value
            samples[2 * i + 1] = value
        }
        val source = object : PcmFrameSource {
            private var position = 0
            override val format = PcmFormat(
                sampleRateHz = sampleRate,
                channels = 2,
                bitsPerSample = 16,
                isFloat = false,
                encodingLabel = "PCM16",
                mimeType = "audio/flac",
                bitrateKbps = 1_411,
            )
            override val durationMs = 1_000L
            override fun read(buffer: FloatArray, frames: Int): Int {
                val available = samples.size / 2 - position
                val count = minOf(frames, available)
                if (count <= 0) return 0
                System.arraycopy(samples, position * 2, buffer, 0, count * 2)
                position += count
                return count
            }
        }
        return AudioAnalyzer(
            AudioAnalyzer.Config(fftSize = 1024, fftHop = 512, spectrogramColumns = 32, spectrogramRows = 32),
        ).analyze(source)
    }

    @Test
    fun measuredAnalysisRendersItsSections() {
        val result = measuredResult()
        compose.setContent {
            BitChordTheme(darkTheme = true) {
                AudioAnalysisContent(
                    state = AnalysisUiState.Done(result),
                    onRun = {},
                    onCancel = {},
                    onOpenLink = {},
                )
            }
        }

        compose.onNodeWithText("Spectrogram").assertIsDisplayed()
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Peak").assertExists()
        compose.onNodeWithText("True Peak").assertExists()
        compose.onNodeWithText("RMS").assertExists()
        compose.onNodeWithText("Dynamic Range").assertExists()
        compose.onNodeWithText("Per Channel").assertExists()
        compose.onNodeWithText("Bass").assertExists()
        compose.onNodeWithText("Subsonic").assertExists()
        compose.onNodeWithText("Leading Silence").assertExists()
        compose.onNodeWithText("Internal Gaps").assertExists()
        compose.onNodeWithText("Loudness").assertExists()
        compose.onNodeWithText("Replay Gain", substring = true).assertExists()
    }

    @Test
    fun failedAnalysisShowsTheReferenceErrorAndRetry() {
        compose.setContent {
            BitChordTheme(darkTheme = true) {
                AudioAnalysisContent(
                    state = AnalysisUiState.Failed(com.music.bitchord.playback.audio.analysis.AnalysisLabels.ERR_CANT_OPEN),
                    onRun = {},
                    onCancel = {},
                    onOpenLink = {},
                )
            }
        }
        compose.onNodeWithText("Can't open the file").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun referenceHrefRowRendersAsAWorkingLink() {
        var opened: PipelineLinkTarget? = null
        compose.setContent {
            BitChordTheme(darkTheme = true) {
                PipelineLinkRow(
                    label = "DVC settings",
                    text = "Open >",
                    href = "settings/audio_dvc#compensate_dvc_vol",
                    onOpenLink = { opened = it },
                )
            }
        }
        compose.onNodeWithText("DVC settings: Open >").performClick()
        assertEquals(PipelineLinkTarget.DVC, opened)
    }

    @Test
    fun everyPipelineTargetMapsToAnInCardPanel() {
        assertEquals(PipelinePanel.Section(AudiophileSection.DVC), PipelineLinkTarget.DVC.toPanel())
        assertEquals(PipelinePanel.Section(AudiophileSection.BIT_PERFECT), PipelineLinkTarget.BIT_PERFECT.toPanel())
        assertEquals(PipelinePanel.Section(AudiophileSection.AUDIO_OUTPUTS), PipelineLinkTarget.AUDIO_OUTPUTS.toPanel())
        assertEquals(PipelinePanel.Section(AudiophileSection.RESAMPLER), PipelineLinkTarget.RESAMPLER.toPanel())
        assertEquals(PipelinePanel.Section(AudiophileSection.EQUALIZER), PipelineLinkTarget.EQUALIZER.toPanel())
        assertEquals(PipelinePanel.Section(AudiophileSection.ADVANCED), PipelineLinkTarget.ADVANCED.toPanel())
        assertEquals(PipelinePanel.Section(AudiophileSection.AUDIO_FOCUS), PipelineLinkTarget.AUDIO_FOCUS.toPanel())
        assertEquals(PipelinePanel.Section(null), PipelineLinkTarget.AUDIO_SETTINGS.toPanel())
        assertEquals(PipelinePanel.Mseb, PipelineLinkTarget.MSEB.toPanel())
        assertEquals(PipelinePanel.Peq, PipelineLinkTarget.PEQ.toPanel())
        assertEquals(PipelinePanel.Analysis, PipelineLinkTarget.ANALYSIS.toPanel())
    }
}
