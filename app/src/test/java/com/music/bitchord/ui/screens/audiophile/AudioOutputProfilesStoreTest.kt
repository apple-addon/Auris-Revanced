package com.music.bitchord.ui.screens.audiophile

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v3 wire format round-trips the per-output EQ preset and no_af flag, and
 * v1/v2 blobs still decode unchanged (eqPreset null, noAf false).
 */
class AudioOutputProfilesStoreTest {
    @Test
    fun v3RoundTripsEqPresetAndNoAfPerRouteAndNamedDevice() {
        val preferences = FakeSharedPreferences()
        val routeTarget = AudioOutputTarget.route(AudioOutputRoute.USB)
        val namedTarget = AudioOutputTarget(AudioOutputRoute.BLUETOOTH, "BLUETOOTH:buds", "Buds")

        AudioOutputProfilesStore(preferences).use { store ->
            store.updateProfile(routeTarget, OutputProfile(driver = OutputDriver.HI_RES, eqPreset = "ROCK", noAf = true))
            store.updateProfile(namedTarget, OutputProfile(noDvc = true, eqPreset = "FLAT", noAf = true))
            assertEquals("ROCK", store.profile(routeTarget).eqPreset)
            assertTrue(store.profile(routeTarget).noAf)
            assertEquals("FLAT", store.profile(namedTarget).eqPreset)
            assertTrue(store.profile(namedTarget).noAf)
        }

        AudioOutputProfilesStore(preferences).use { restored ->
            val route = restored.profile(routeTarget)
            assertEquals(OutputDriver.HI_RES, route.driver)
            assertEquals("ROCK", route.eqPreset)
            assertTrue(route.noAf)
            val named = restored.profile(namedTarget)
            assertEquals("FLAT", named.eqPreset)
            assertTrue(named.noAf)
            assertTrue(named.noDvc)
            // The blob on disk is v3.
            assertTrue((preferences.getString("profiles_v3", "") ?: "").lines().all { it.startsWith("3|") })
        }
    }

    @Test
    fun v2BlobMigratesForwardWithPresetNullAndNoAfFalse() {
        val preferences = FakeSharedPreferences()
        // Hand-write a v2 line in the exact v2 layout (19 fields).
        val key = java.util.Base64.getEncoder().encodeToString("route:usb".toByteArray())
        val line = listOf(
            "2", key, "usb", "~", "~", "hi_res", "1", "512", "40", "1", "0", "1", "0", "0",
            "1", "1", "1", "0", "0",
        ).joinToString("|")
        preferences.edit()
            .putInt("schema_version", 2)
            .putString("profiles_v2", line)
            .apply()

        AudioOutputProfilesStore(preferences).use { store ->
            val profile = store.profile(AudioOutputTarget.route(AudioOutputRoute.USB))
            assertEquals(OutputDriver.HI_RES, profile.driver)
            assertTrue(profile.float32)
            assertEquals(512, profile.bufferSizeFrames)
            assertEquals(40, profile.visualDelayMs)
            assertTrue(profile.noDvc)
            assertTrue(profile.noEquTone)
            assertFalse(profile.noHeadroom)
            assertFalse(profile.noDuck)
            assertNull(profile.bitPerfectOverride)
            // v2 had no preset/no_af: defaults, not garbage.
            assertNull(profile.eqPreset)
            assertFalse(profile.noAf)
        }
        // And the next write re-encodes as v3.
        AudioOutputProfilesStore(preferences).use { store ->
            store.updateProfile(AudioOutputTarget.route(AudioOutputRoute.USB), OutputProfile(eqPreset = "JAZZ", noAf = true))
        }
        AudioOutputProfilesStore(preferences).use { restored ->
            assertEquals("JAZZ", restored.profile(AudioOutputTarget.route(AudioOutputRoute.USB)).eqPreset)
            assertTrue(restored.profile(AudioOutputTarget.route(AudioOutputRoute.USB)).noAf)
        }
    }

    @Test
    fun v1BlobStillDecodes() {
        val preferences = FakeSharedPreferences()
        val key = java.util.Base64.getEncoder().encodeToString("route:wired".toByteArray())
        val line = listOf("1", key, "wired", "~", "~", "audio_track", "0", "0", "0", "1", "0", "0", "1").joinToString("|")
        preferences.edit().putString("profiles_v1", line).apply()

        AudioOutputProfilesStore(preferences).use { store ->
            val profile = store.profile(AudioOutputTarget.route(AudioOutputRoute.WIRED))
            assertEquals(OutputDriver.AUDIO_TRACK, profile.driver)
            assertTrue(profile.noDvc)
            assertTrue(profile.noDuck)
            assertNull(profile.eqPreset)
            assertFalse(profile.noAf)
        }
    }

    @Test
    fun invalidEqPresetNameFallsBackToNull() {
        val preferences = FakeSharedPreferences()
        AudioOutputProfilesStore(preferences).use { store ->
            store.updateProfile(
                AudioOutputTarget.route(AudioOutputRoute.PHONE),
                OutputProfile(eqPreset = "NOT_A_PRESET"),
            )
            assertNull(store.profile(AudioOutputTarget.route(AudioOutputRoute.PHONE)).eqPreset)
        }
    }

    /** In-memory SharedPreferences; same shape as the one in the fades tests. */
    private class FakeSharedPreferences : SharedPreferences, AutoCloseable {
        private val data = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners += listener
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners -= listener
        }
        override fun close() = listeners.clear()

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: Set<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
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
                listeners.toList().forEach { listener -> changed.forEach { key -> listener.onSharedPreferenceChanged(this@FakeSharedPreferences, key) } }
                pending.clear()
                removed.clear()
                clearAll = false
            }
        }
    }
}
