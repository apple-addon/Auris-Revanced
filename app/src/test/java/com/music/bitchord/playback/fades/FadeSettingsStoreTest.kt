package com.music.bitchord.playback.fades

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeSettingsStoreTest {
    @Test
    fun defaultsAndPresetValuesMatchThePublicControls() {
        FakeSharedPreferences().use { preferences ->
            FadeSettingsStore(preferences).use { store ->
                assertEquals(FadeSettings.DEFAULTS, store.state.value)
                assertEquals(0L, store.state.value.preloadGaplessTracksMs)
                assertEquals(0L, store.state.value.silenceBetweenTracksMs)
                assertEquals(5_000L, store.state.value.crossfadeLengthMs)
                assertEquals(400L, store.state.value.shortManualCrossfadeMs)
                assertEquals(400L, store.state.value.playPauseStopFadeMs)
                assertEquals(100L, store.state.value.seekFadeMs)
            }
        }

        assertEquals(0L, GaplessPreloadPreset.NONE.durationMs)
        assertEquals(2_500L, GaplessPreloadPreset.NORMAL.durationMs)
        assertEquals(5_000L, GaplessPreloadPreset.MORE.durationMs)
        assertEquals(GaplessPreloadPreset.NORMAL, GaplessPreloadPreset.matching(2_500L))
        assertNull(GaplessPreloadPreset.matching(123L))
    }

    @Test
    fun settersPublishAndPersistOneCoherentSnapshot() {
        val preferences = FakeSharedPreferences()
        val expected = FadeSettings(
            preloadGaplessTracksMs = 5_000L,
            silenceBetweenTracksMs = 750L,
            crossfadeLengthMs = 8_000L,
            shortManualCrossfadeMs = 450L,
            playPauseStopFadeMs = 300L,
            seekFadeMs = 80L,
        )

        FadeSettingsStore(preferences).use { store ->
            store.setPreloadGaplessTracks(GaplessPreloadPreset.MORE)
            store.setSilenceBetweenTracksMs(750L)
            store.setCrossfadeLengthMs(8_000L)
            store.setShortManualCrossfadeMs(450L)
            store.setPlayPauseStopFadeMs(300L)
            store.setSeekFadeMs(80L)
            assertEquals(expected, store.settings.value)
        }

        FadeSettingsStore(preferences).use { restored ->
            assertEquals(expected, restored.state.value)
        }
    }

    @Test
    fun negativeImportedValuesAreDisabledAndExternalWritesReload() {
        val preferences = FakeSharedPreferences()
        FadeSettingsStore(preferences).use { store ->
            store.update(
                FadeSettings(
                    preloadGaplessTracksMs = -1L,
                    silenceBetweenTracksMs = -2L,
                    crossfadeLengthMs = -3L,
                    shortManualCrossfadeMs = -4L,
                    playPauseStopFadeMs = -5L,
                    seekFadeMs = -6L,
                ),
            )
            assertEquals(FadeSettings(0L, 0L, 0L, 0L, 0L, 0L), store.state.value)

            preferences.edit().putLong("seek_fade_ms", 125L).apply()
            assertEquals(125L, store.state.value.seekFadeMs)
        }
    }

    @Test
    fun restoreDefaultsRemovesOnlyFadeKeys() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("unrelated", "keep").apply()
        FadeSettingsStore(preferences).use { store ->
            store.update(FadeSettings(5_000L, 900L, 7_000L, 600L, 300L, 200L))
            store.restoreDefaults()
            assertEquals(FadeSettings.DEFAULTS, store.state.value)
        }
        FadeSettingsStore(preferences).use { restored ->
            assertEquals(FadeSettings.DEFAULTS, restored.state.value)
        }
        assertEquals("keep", preferences.getString("unrelated", null))
    }

    @Test
    fun fadeSwitchesDefaultTruePersistAtomicallyAndReload() {
        val preferences = FakeSharedPreferences()
        FadeSettingsStore(preferences).use { store ->
            assertTrue(store.state.value.fadePlayPause)
            assertTrue(store.state.value.fadeSeek)
            assertTrue(store.state.value.crossfadeAutoAdvance)
            assertTrue(store.state.value.fadeManualAdvance)

            store.setFadePlayPause(false)
            store.setFadeSeek(false)
            store.setCrossfadeAutoAdvance(false)
            store.setFadeManualAdvance(false)
            val current = store.state.value
            assertEquals(false, current.fadePlayPause)
            assertEquals(false, current.fadeSeek)
            assertEquals(false, current.crossfadeAutoAdvance)
            assertEquals(false, current.fadeManualAdvance)
            // The lengths are untouched by the switches.
            assertEquals(400L, current.playPauseStopFadeMs)
            assertEquals(100L, current.seekFadeMs)
            assertEquals(5_000L, current.crossfadeLengthMs)
            assertEquals(400L, current.shortManualCrossfadeMs)
        }
        FadeSettingsStore(preferences).use { restored ->
            val value = restored.state.value
            assertEquals(false, value.fadePlayPause)
            assertEquals(false, value.fadeSeek)
            assertEquals(false, value.crossfadeAutoAdvance)
            assertEquals(false, value.fadeManualAdvance)
            assertEquals(400L, value.playPauseStopFadeMs)
            assertEquals(100L, value.seekFadeMs)
        }
        // Reference key names are what lands in the file.
        assertEquals(false, preferences.getBoolean("fade_play_pause", true))
        assertEquals(false, preferences.getBoolean("fade_seek", true))
        assertEquals(false, preferences.getBoolean("crossfade_auto_advance", true))
        assertEquals(false, preferences.getBoolean("fade_manual_advance", true))
    }

    @Test
    fun legacyInstallWithoutSwitchKeysKeepsFadingEnabled() {
        val preferences = FakeSharedPreferences()
        // A pre-switch install has only the length keys.
        preferences.edit().putLong("seek_fade_ms", 250L).apply()
        FadeSettingsStore(preferences).use { store ->
            assertTrue(store.state.value.fadePlayPause)
            assertTrue(store.state.value.fadeSeek)
            assertTrue(store.state.value.crossfadeAutoAdvance)
            assertTrue(store.state.value.fadeManualAdvance)
            assertEquals(250L, store.state.value.seekFadeMs)
        }
    }
}

/** Small in-memory Android preferences implementation; no device or Robolectric required. */
private class FakeSharedPreferences : SharedPreferences, AutoCloseable {
    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
        data[key] as? Set<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (data[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (data[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (data[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners -= listener
    }

    override fun close() = listeners.clear()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor =
            put(key, values)

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changed = mutableSetOf<String>()
            if (clearAll) {
                changed += data.keys
                data.clear()
            }
            changed += removed
            removed.forEach(data::remove)
            changed += pending.keys
            data.putAll(pending)
            listeners.toList().forEach { listener ->
                changed.forEach { key -> listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
            }
            pending.clear()
            removed.clear()
            clearAll = false
        }
    }
}
