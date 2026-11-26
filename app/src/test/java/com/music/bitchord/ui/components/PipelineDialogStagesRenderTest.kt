/*
 * Copyright (C) 2026 BitChord
 *
 * The extended pipeline dialog renders every stage the model declares — the
 * original five plus MSEB & PEQ and Analysis — with its two-digit number,
 * inside the same frosted card. Robolectric-only (needs an inflated manifest
 * and compose test rules), so it is excluded on aarch64 hosts like the other
 * interaction tests and runs on CI's x86_64.
 */

package com.music.bitchord.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.ReverbTuning
import dev.chrisbanes.haze.HazeState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class PipelineDialogStagesRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val hazeState = HazeState()

    @Before
    fun setUp() {
        AppSettings.reduceDynamicBlur.value = true
        NerdStats.forgetLastSession()
        AudioOutputStatus.reset()
        AppSettings.qualityOptions.value = false
        AppSettings.msebEnabled.value = false
        AppSettings.peqEnabled.value = false
        AppSettings.jamesDspEnabled.value = false
        AppSettings.equalizerEnabled.value = false
        AppSettings.reverbTuning.value = ReverbTuning()
        AppSettings.simulatedAtmos.value = false
    }

    @After
    fun tearDown() {
        AppSettings.reduceDynamicBlur.value = false
        NerdStats.forgetLastSession()
        AudioOutputStatus.reset()
    }

    private fun setPipelineContent() {
        compose.setContent {
            MaterialTheme {
                AudioPipelineDialog(hazeState = hazeState, onDismiss = {}, isPlaying = false)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun allSevenStagesRenderWithTheirNumbers() {
        setPipelineContent()

        // The model's order, rendered: 01..07, analysis last.
        PIPELINE_STAGES.forEach { stage ->
            compose.onNodeWithText(stageNumberLabel(stage.index)).assertExists()
        }
        compose.onNodeWithText("TRACK INFO", substring = true).assertExists()
        compose.onNodeWithText("DECODER", substring = true).assertExists()
        compose.onNodeWithText("RESAMPLER", substring = true).assertExists()
        compose.onNodeWithText("JAMESDSP", substring = true).assertExists()
        compose.onNodeWithText("MSEB & PEQ", substring = true).assertExists()
        compose.onNodeWithText("OUTPUT DEVICE", substring = true).assertExists()
        compose.onNodeWithText("ANALYSIS", substring = true).assertExists()

        // The original chrome is preserved: card title and Done action.
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.audio_pipeline)).assertExists()
        compose.onNodeWithText(context.getString(R.string.done)).assertExists()
    }

    @Test
    fun hyperlinkRowsRenderAsLinksForMappedHrefs() {
        setPipelineContent()
        // DVC and Resampler hrefs map in-app, so their rows render underlined
        // and therefore carry a clickable node.
        assertTrue(
            compose.onAllNodesWithText("Open >", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
