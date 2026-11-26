/*
 * Copyright (C) 2026 BitChord
 *
 * The EQ Hub's two destinations and the tap/long-press split behind them.
 *
 * The row in the player's output drawer is a combinedClickable: a normal tap
 * opens the hub, a long press opens it on the presets. This file pins the two
 * halves that live in this module — the sheet's `showPresets` flag deciding
 * which page the hub opens on, and the combinedClickable contract on a real
 * production row (a preset entry): a long press must not also fire the tap.
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
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.EqualizerPreset
import com.music.bitchord.testing.AppSettingsTestAccess
import com.music.bitchord.ui.screens.PowerampEqualizerPage
import com.music.bitchord.ui.screens.QualityOptionsSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class EqHubDestinationInteractionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Compose's default long-press timeout is 500 ms; hold past it. */
    private companion object {
        const val LONG_PRESS_HOLD_MS = 600L
    }

    @Before
    fun setUp() {
        AppSettingsTestAccess.installPrefs(context)
        AppSettings.setEqualizerBands(EqualizerPreset.FLAT.bands)
    }

    @After
    fun tearDown() {
        AppSettings.setEqualizerBands(EqualizerPreset.FLAT.bands)
    }

    @Test
    fun `a normal tap's destination shows the hub and not the presets`() {
        compose.setContent {
            MaterialTheme {
                QualityOptionsSheet(showPresets = false, onDismiss = {}, onOpenMseb = {}, onOpenPeq = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Preamp").assertIsDisplayed()
        compose.onNodeWithText("Presets").assertIsNotDisplayed()
    }

    @Test
    fun `a long press's destination opens the presets over the hub`() {
        compose.setContent {
            MaterialTheme {
                QualityOptionsSheet(showPresets = true, onDismiss = {}, onOpenMseb = {}, onOpenPeq = {})
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Presets").assertIsDisplayed()
        compose.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test
    fun `a long press on a preset row opens its action without also applying it`() {
        compose.setContent {
            MaterialTheme {
                PowerampEqualizerPage(showPresets = true, onOpenMseb = {}, onOpenPeq = {})
            }
        }
        compose.waitForIdle()
        val bandsBefore = AppSettings.equalizerBands.value
        compose.onNodeWithText("Rock").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Rock").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_HOLD_MS)
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithText("Apply this preset to the synchronized graphic EQ?", substring = true).assertIsDisplayed()
        assertEquals(
            "the long press must not also fire the row's tap",
            bandsBefore,
            AppSettings.equalizerBands.value,
        )
        assertNotEquals(EqualizerPreset.ROCK, AppSettings.equalizerPreset.value)
        compose.onNodeWithText("Presets").assertIsDisplayed()

        // Dismiss the action dialog without applying.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(bandsBefore, AppSettings.equalizerBands.value)
    }

    @Test
    fun `a tap on a preset row applies it and closes the browser`() {
        compose.setContent {
            MaterialTheme {
                PowerampEqualizerPage(showPresets = true, onOpenMseb = {}, onOpenPeq = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Jazz").assertIsDisplayed()

        compose.onNodeWithText("Jazz").performClick()
        compose.waitForIdle()

        assertEquals(EqualizerPreset.JAZZ, AppSettings.equalizerPreset.value)
        assertEquals(EqualizerPreset.JAZZ.bands, AppSettings.equalizerBands.value)
        compose.onNodeWithText("Presets").assertIsNotDisplayed()
    }
}
