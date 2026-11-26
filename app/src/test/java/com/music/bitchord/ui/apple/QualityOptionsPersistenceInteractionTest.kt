/*
 * Copyright (C) 2026 BitChord
 *
 * Quality Options ON/OFF persistence across a simulated process recreation.
 *
 * Robolectric cannot run on an aarch64 host — Conscrypt, which it loads by
 * JNI, publishes no linux-aarch64 build — so this class is named to match the
 * exclusion in `app/build.gradle.kts` and runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.data.settings.PendingAudioConflict
import com.music.bitchord.testing.AppSettingsTestAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Quality Options ON/OFF must survive the process dying between the write and
 * the read. The write goes through [AudioConflictCoordinator] into the real
 * `bitchord_settings` preferences; the "process recreation" is
 * [AppSettings.reload], the same re-read-from-disk path the backup import
 * uses — on a real device the next process would construct the same object
 * over the same file and land on the same values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class QualityOptionsPersistenceInteractionTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() =
        context.getSharedPreferences(AppSettingsTestAccess.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val keyQualityOptions get() = AppSettingsTestAccess.keyConst("KEY_QUALITY_OPTIONS")
    private val keyBitPerfect get() = AppSettingsTestAccess.keyConst("KEY_BIT_PERFECT")

    @Before
    fun setUp() {
        AppSettingsTestAccess.installPrefs(context)
        AppSettingsTestAccess.installAuthStore(context)
        AppSettings.reload()
        AudioConflictCoordinator.cancel()
        AppSettings.setQualityOptions(false)
        AppSettings.setBitPerfect(false)
        AppSettings.setMsebEnabled(false)
        AppSettings.setPeqEnabled(false)
    }

    @After
    fun tearDown() {
        AudioConflictCoordinator.cancel()
        AppSettings.setQualityOptions(false)
        AppSettings.setBitPerfect(false)
        AppSettings.setMsebEnabled(false)
        AppSettings.setPeqEnabled(false)
    }

    @Test
    fun `quality options on then off round-trips through a process recreation`() {
        AudioConflictCoordinator.requestQualityOptions(true)
        assertTrue(AppSettings.qualityOptions.value)
        assertTrue("the write must have reached the preference file", prefs.getBoolean(keyQualityOptions, false))

        AppSettings.reload()
        assertTrue("ON must survive re-reading the disk", AppSettings.qualityOptions.value)

        AudioConflictCoordinator.requestQualityOptions(false)
        assertFalse(AppSettings.qualityOptions.value)
        AppSettings.reload()
        assertFalse("OFF must survive re-reading the disk", AppSettings.qualityOptions.value)
        assertFalse(prefs.getBoolean(keyQualityOptions, true))
    }

    @Test
    fun `confirming a conflict persists both halves across a process recreation`() {
        AppSettings.setBitPerfect(true)
        AppSettings.setMsebEnabled(true)

        AudioConflictCoordinator.requestQualityOptions(true)
        assertEquals(PendingAudioConflict.QUALITY_OPTIONS, AudioConflictCoordinator.pending.value)
        assertFalse(AppSettings.qualityOptions.value)

        AudioConflictCoordinator.confirm()
        assertNull(AudioConflictCoordinator.pending.value)

        AppSettings.reload()
        assertFalse("quality options off must persist", AppSettings.qualityOptions.value)
        assertTrue("bit perfect on must persist", AppSettings.bitPerfect.value)
        assertFalse(prefs.getBoolean(keyQualityOptions, true))
        assertTrue(prefs.getBoolean(keyBitPerfect, false))
    }

    @Test
    fun `cancelling a conflict persists nothing across a process recreation`() {
        AppSettings.setBitPerfect(true)
        AppSettings.setMsebEnabled(true)

        AudioConflictCoordinator.requestQualityOptions(true)
        AudioConflictCoordinator.cancel()

        AppSettings.reload()
        assertFalse(AppSettings.qualityOptions.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertFalse("a cancelled request must leave no trace on disk", prefs.contains(keyQualityOptions))
    }
}
