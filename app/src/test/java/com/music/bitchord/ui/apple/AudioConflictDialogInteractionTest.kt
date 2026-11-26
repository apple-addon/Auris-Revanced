/*
 * Copyright (C) 2026 BitChord
 *
 * The single conflict dialog's cancel/confirm semantics, driven by touch.
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.data.settings.PendingAudioConflict
import com.music.bitchord.testing.AppSettingsTestAccess
import com.music.bitchord.ui.components.AudioConflictDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one dialog every Quality Options / Bit Perfect entry point raises.
 * What the user can do with it is the whole contract: **Cancel** drops the
 * pending request (the switches snap back, nothing is written), **Confirm**
 * applies the atomic resolution (bit-perfect on, quality options off) and
 * both survive a re-read of the preferences.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class AudioConflictDialogInteractionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() =
        context.getSharedPreferences(AppSettingsTestAccess.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val keyQualityOptions get() = AppSettingsTestAccess.keyConst("KEY_QUALITY_OPTIONS")
    private val keyBitPerfect get() = AppSettingsTestAccess.keyConst("KEY_BIT_PERFECT")

    @Before
    fun setUp() {
        AppSettingsTestAccess.installPrefs(context)
        AppSettings.setQualityOptions(false)
        AppSettings.setBitPerfect(false)
        AppSettings.setMsebEnabled(false)
        AppSettings.setPeqEnabled(false)
        AudioConflictCoordinator.cancel()
    }

    @After
    fun tearDown() {
        AudioConflictCoordinator.cancel()
        AppSettings.setQualityOptions(false)
        AppSettings.setBitPerfect(false)
        AppSettings.setMsebEnabled(false)
        AppSettings.setPeqEnabled(false)
    }

    private fun setDialogContent() {
        compose.setContent {
            MaterialTheme {
                AudioConflictDialog()
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `cancel on the quality-options conflict drops the request and writes nothing`() {
        AppSettings.setBitPerfect(true)
        AppSettings.setMsebEnabled(true)
        AudioConflictCoordinator.requestQualityOptions(true)
        assertEquals(PendingAudioConflict.QUALITY_OPTIONS, AudioConflictCoordinator.pending.value)

        setDialogContent()
        compose.onNodeWithText("Audio Conflict").assertIsDisplayed()
        compose.onNodeWithText("Perfect Bit Perfect is active", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertNull(AudioConflictCoordinator.pending.value)
        compose.onNodeWithText("Audio Conflict").assertIsNotDisplayed()
        assertFalse("the switch must snap back", AppSettings.qualityOptions.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertFalse("cancel must not write the key at all", prefs.contains(keyQualityOptions))
    }

    @Test
    fun `confirm on the quality-options conflict applies the atomic resolution`() {
        AppSettings.setBitPerfect(true)
        AppSettings.setMsebEnabled(true)
        AudioConflictCoordinator.requestQualityOptions(true)

        setDialogContent()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitForIdle()

        assertNull(AudioConflictCoordinator.pending.value)
        compose.onNodeWithText("Audio Conflict").assertIsNotDisplayed()
        assertFalse(AppSettings.qualityOptions.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertFalse(prefs.getBoolean(keyQualityOptions, true))
        assertTrue(prefs.getBoolean(keyBitPerfect, false))
    }

    @Test
    fun `cancel on the bit-perfect conflict leaves both switches as they were`() {
        AppSettings.setQualityOptions(true)
        AppSettings.setMsebEnabled(true)
        AudioConflictCoordinator.requestNativeBitPerfect(true)
        assertEquals(PendingAudioConflict.NATIVE_BIT_PERFECT, AudioConflictCoordinator.pending.value)

        setDialogContent()
        compose.onNodeWithText("bypasses Quality Options", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertNull(AudioConflictCoordinator.pending.value)
        assertFalse("bit-perfect must stay off after cancel", AppSettings.bitPerfect.value)
        assertTrue("quality options must stay on after cancel", AppSettings.qualityOptions.value)
        assertFalse(prefs.contains(keyBitPerfect))
    }

    @Test
    fun `confirm on the bit-perfect conflict enables bit-perfect and disables quality options`() {
        AppSettings.setQualityOptions(true)
        AppSettings.setMsebEnabled(true)
        AudioConflictCoordinator.requestNativeBitPerfect(true)

        setDialogContent()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitForIdle()

        assertNull(AudioConflictCoordinator.pending.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertFalse(AppSettings.qualityOptions.value)
        assertTrue(prefs.getBoolean(keyBitPerfect, false))
        assertFalse(prefs.getBoolean(keyQualityOptions, true))
    }

    @Test
    fun `no dialog is shown while nothing is pending`() {
        setDialogContent()
        compose.onNodeWithText("Audio Conflict").assertIsNotDisplayed()
    }
}
