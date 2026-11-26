package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.HomeProvider
import com.music.bitchord.data.settings.MusicProvider
import com.music.bitchord.data.settings.ProviderAxes
import com.music.bitchord.data.settings.ProviderAxis
import com.music.bitchord.testing.AppSettingsTestAccess
import com.music.bitchord.testing.RecordingSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The theme-source level of provider switching: the UI axis is the only input
 * that decides whether the screens wear Apple's look
 * ([AppSettings.appleUiSelected], and with it the Apple palette in
 * `BitChordTheme`), and each axis is stored and changed independently.
 *
 * No pixel claims here — the palette itself is pinned in
 * `ProviderThemeInteractionTest`, where the theme composable can run. What
 * this file pins is the mapping from the stored axes to that decision.
 */
class ProviderThemeSourceTest {

    private lateinit var prefs: RecordingSharedPreferences

    private val keyProviderUi get() = AppSettingsTestAccess.keyConst("KEY_PROVIDER_UI")

    @Before
    fun setUp() {
        prefs = RecordingSharedPreferences()
        AppSettingsTestAccess.installPrefs(prefs)
        AppSettings.providerAxes.value = ProviderAxes()
    }

    @After
    fun tearDown() {
        AppSettings.providerAxes.value = ProviderAxes()
        AppSettings.homeProvider.value = HomeProvider.YOUTUBE
        AppSettingsTestAccess.installPrefs(RecordingSharedPreferences())
    }

    @Test
    fun `the UI axis alone decides the Apple look`() {
        for (provider in MusicProvider.entries) {
            AppSettings.providerAxes.value = ProviderAxes(
                ui = provider,
                recommendation = MusicProvider.YOUTUBE,
                source = MusicProvider.YOUTUBE,
            )
            assertEquals(
                "appleUiSelected must follow the UI axis and nothing else",
                provider == MusicProvider.APPLE,
                AppSettings.appleUiSelected,
            )
        }
    }

    @Test
    fun `the master switch needs all three axes on Apple`() {
        AppSettings.providerAxes.value = ProviderAxes(
            ui = MusicProvider.APPLE,
            recommendation = MusicProvider.APPLE,
            source = MusicProvider.SPOTIFY,
        )
        assertFalse(AppSettings.appleMasterOn)
        assertTrue(AppSettings.appleUiSelected)

        AppSettings.providerAxes.value = ProviderAxes(
            ui = MusicProvider.APPLE,
            recommendation = MusicProvider.APPLE,
            source = MusicProvider.APPLE,
        )
        assertTrue(AppSettings.appleMasterOn)
    }

    @Test
    fun `pointing the UI axis at another provider changes only that axis and persists it`() {
        AppSettings.setProviderOn(ProviderAxis.UI, MusicProvider.SPOTIFY)

        assertEquals(MusicProvider.SPOTIFY, AppSettings.providerOn(ProviderAxis.UI))
        assertEquals(MusicProvider.YOUTUBE, AppSettings.providerOn(ProviderAxis.RECOMMENDATION))
        assertEquals(MusicProvider.YOUTUBE, AppSettings.providerOn(ProviderAxis.SOURCE))
        assertFalse(AppSettings.appleUiSelected)
        assertEquals(MusicProvider.SPOTIFY.id, prefs.getString(keyProviderUi, null))
    }

    @Test
    fun `the catalogue switch moves recommendation and source together and leaves the look alone`() {
        AppSettings.setCatalogue(MusicProvider.SPOTIFY)

        assertEquals(MusicProvider.SPOTIFY, AppSettings.providerOn(ProviderAxis.RECOMMENDATION))
        assertEquals(MusicProvider.SPOTIFY, AppSettings.providerOn(ProviderAxis.SOURCE))
        assertEquals(MusicProvider.YOUTUBE, AppSettings.providerOn(ProviderAxis.UI))
        assertEquals(HomeProvider.SPOTIFY, AppSettings.homeProvider.value)
    }

    @Test
    fun `provider ids round-trip including the legacy enum-name spelling`() {
        assertEquals(MusicProvider.YOUTUBE, MusicProvider.of("youtube"))
        assertEquals(MusicProvider.SPOTIFY, MusicProvider.of("spotify"))
        assertEquals(MusicProvider.APPLE, MusicProvider.of("apple"))
        assertEquals(MusicProvider.YOUTUBE, MusicProvider.of("YOUTUBE"))
        assertEquals(MusicProvider.SPOTIFY, MusicProvider.of("SPOTIFY"))
        assertEquals(MusicProvider.APPLE, MusicProvider.of("APPLE"))
        assertEquals(MusicProvider.YOUTUBE, MusicProvider.of("nonsense"))
        assertEquals(MusicProvider.YOUTUBE, MusicProvider.of(null))
    }

    @Test
    fun `axes with changes one axis and reads back through on`() {
        val axes = ProviderAxes()
        val changed = axes.with(ProviderAxis.UI, MusicProvider.APPLE)

        assertEquals(MusicProvider.APPLE, changed.on(ProviderAxis.UI))
        assertEquals(MusicProvider.YOUTUBE, changed.on(ProviderAxis.RECOMMENDATION))
        assertEquals(MusicProvider.YOUTUBE, changed.on(ProviderAxis.SOURCE))
        assertFalse(changed.allApple)
        assertTrue(changed.with(ProviderAxis.RECOMMENDATION, MusicProvider.APPLE)
            .with(ProviderAxis.SOURCE, MusicProvider.APPLE).allApple)
    }
}
