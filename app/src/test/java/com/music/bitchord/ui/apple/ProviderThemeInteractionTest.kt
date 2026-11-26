/*
 * Copyright (C) 2026 BitChord
 *
 * Provider theme switching at the theme-source level: which color scheme
 * `BitChordTheme` installs for a given UI-axis provider. This asserts the
 * theme's *source values* (the ColorScheme it provides), never rendered
 * pixels.
 *
 * Robolectric cannot run on an aarch64 host — Conscrypt, which it loads by
 * JNI, publishes no linux-aarch64 build — so this class is named to match the
 * exclusion in `app/build.gradle.kts` and runs for real on CI's x86_64.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.MusicProvider
import com.music.bitchord.data.settings.ProviderAxes
import com.music.bitchord.data.settings.ProviderAxis
import com.music.bitchord.testing.AppSettingsTestAccess
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ProviderThemeInteractionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var captured: ColorScheme? = null

    @Before
    fun setUp() {
        AppSettings.providerAxes.value = ProviderAxes()
        captured = null
    }

    @After
    fun tearDown() {
        AppSettings.providerAxes.value = ProviderAxes()
    }

    private fun themePrimaryFor(ui: MusicProvider, dark: Boolean): ColorScheme {
        AppSettings.providerAxes.value = ProviderAxes(
            ui = ui,
            recommendation = MusicProvider.YOUTUBE,
            source = MusicProvider.YOUTUBE,
        )
        compose.setContent {
            BitChordTheme(darkTheme = dark) {
                captured = MaterialTheme.colorScheme
                Box {}
            }
        }
        compose.waitForIdle()
        return captured ?: error("theme did not compose")
    }

    @Test
    fun `Apple on the UI axis installs Apple's palette in dark mode`() {
        val scheme = themePrimaryFor(MusicProvider.APPLE, dark = true)
        assertEquals(Color(0xFF0A84FF), scheme.primary)
        assertEquals(Color(0xFF1C1C1E), scheme.surface)
    }

    @Test
    fun `YouTube and Spotify on the UI axis install the app's own palette in dark mode`() {
        for (provider in listOf(MusicProvider.YOUTUBE, MusicProvider.SPOTIFY)) {
            val scheme = themePrimaryFor(provider, dark = true)
            assertEquals(provider.toString(), Color.White, scheme.primary)
            assertEquals(provider.toString(), Color(0xFF0D0D0F), scheme.surface)
        }
    }

    @Test
    fun `the Apple look applies in dark mode only and light mode keeps the light palette`() {
        val light = themePrimaryFor(MusicProvider.APPLE, dark = false)
        assertEquals(Color.Black, light.primary)
        assertEquals(Color.White, light.background)
    }

    @Test
    fun `the UI axis choice persists across a simulated process recreation`() {
        AppSettingsTestAccess.installPrefs(context)
        AppSettingsTestAccess.installAuthStore(context)
        AppSettings.reload()

        AppSettings.setProviderOn(ProviderAxis.UI, MusicProvider.SPOTIFY)
        assertEquals(MusicProvider.SPOTIFY, AppSettings.providerOn(ProviderAxis.UI))
        assertFalse(AppSettings.appleUiSelected)

        AppSettings.reload()
        assertEquals(
            "the UI-axis choice must survive re-reading the preferences",
            MusicProvider.SPOTIFY,
            AppSettings.providerAxes.value.ui,
        )

        // Apple is allowed while the manifest question is unanswered
        // (isInstalledKnown() == null is not a "no").
        AppSettings.setProviderOn(ProviderAxis.UI, MusicProvider.APPLE)
        assertTrue(AppSettings.appleUiSelected)
        AppSettings.reload()
        assertEquals(MusicProvider.APPLE, AppSettings.providerAxes.value.ui)
    }
}
