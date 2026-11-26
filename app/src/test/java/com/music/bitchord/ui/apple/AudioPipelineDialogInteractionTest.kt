/*
 * Copyright (C) 2026 BitChord
 *
 * The Audio Pipeline surface: the indicator itself (title, five stages, the
 * bit-exact verdict) and the extended DSP rows, which must reflect the live
 * DSP state — `qualityOptions && stageEnabled`, not the raw switch.
 *
 * Robolectric cannot run on an aarch64 host — Conscrypt, which it loads by
 * JNI, publishes no linux-aarch64 build — so this class is named to match the
 * exclusion in `app/build.gradle.kts` and runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.ReverbTuning
import com.music.bitchord.ui.components.AudioPipelineDialog
import dev.chrisbanes.haze.HazeState
import org.junit.After
import org.junit.Assert.assertEquals
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
class AudioPipelineDialogInteractionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val hazeState = HazeState()

    private fun label(id: Int): String = context.getString(id)

    @Before
    fun setUp() {
        // No Haze: the blurred scrim samples a backdrop no test page recorded.
        AppSettings.reduceDynamicBlur.value = true
        NerdStats.forgetLastSession()
        AudioOutputStatus.reset()
        AppSettings.qualityOptions.value = false
        AppSettings.msebEnabled.value = false
        AppSettings.peqEnabled.value = false
        AppSettings.monoEnabled.value = false
        AppSettings.jamesDspEnabled.value = false
        AppSettings.equalizerEnabled.value = false
        AppSettings.reverbTuning.value = ReverbTuning()
        // On by default in the app; pinned off so the rows under test are the
        // only active stages.
        AppSettings.simulatedAtmos.value = false
    }

    @After
    fun tearDown() {
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.qualityOptions.value = false
        AppSettings.msebEnabled.value = false
        AppSettings.peqEnabled.value = false
        AppSettings.monoEnabled.value = false
        AppSettings.jamesDspEnabled.value = false
        AppSettings.equalizerEnabled.value = false
        AppSettings.reverbTuning.value = ReverbTuning()
        AppSettings.simulatedAtmos.value = true
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
    fun `the pipeline surface is intact title five stages bit exact row done action`() {
        setPipelineContent()

        compose.onNodeWithText(label(R.string.audio_pipeline)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pipeline_track_info)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pipeline_decoder)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pipeline_resampler)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pipeline_dsp)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pipeline_output_device)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pipeline_bit_exact)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.done)).assertIsDisplayed()
    }

    @Test
    fun `extended DSP rows show active only when quality options and the stage are both on`() {
        AppSettings.qualityOptions.value = true
        AppSettings.msebEnabled.value = true
        setPipelineContent()

        compose.onNodeWithText("MSEB").assertIsDisplayed()
        assertEquals(
            "exactly the MSEB row reads Active",
            1,
            compose.onAllNodesWithText("Active").fetchSemanticsNodes().size,
        )
        assertEquals(
            "PEQ and Mono read Off",
            2,
            compose.onAllNodesWithText("Off").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("DSP 1 active", substring = true).assertIsDisplayed()

        // Quality options off: the same configured stage now reads Off.
        AppSettings.qualityOptions.value = false
        compose.waitForIdle()

        assertEquals(
            "the stage switch alone must not read as active",
            0,
            compose.onAllNodesWithText("Active").fetchSemanticsNodes().size,
        )
        assertEquals(
            "MSEB, PEQ and Mono all read Off",
            3,
            compose.onAllNodesWithText("Off").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("DSP idle", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the bit-exact verdict names the running DSP stage`() {
        AudioOutputStatus.current.value = AudioOutputStatus.Snapshot(outputExact = true)
        AppSettings.equalizerEnabled.value = true
        setPipelineContent()

        compose.onNodeWithText("No — equalizer", substring = true).assertIsDisplayed()

        AppSettings.equalizerEnabled.value = false
        compose.waitForIdle()

        compose.onNodeWithText("Yes").assertIsDisplayed()
    }
}
