package com.music.bitchord.ui.screens.audiophile

import com.music.bitchord.testing.RecordingSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two audiophile stores already present — [AudiophileSettingsStore] and
 * [AudioOutputProfilesStore] — promise atomic persistence: every setter
 * writes the complete snapshot in one SharedPreferences transaction, and a
 * fresh store over the same file (the process-recreation case, since the
 * stores take the preferences as a constructor argument) reads the same
 * state back.
 *
 * The recording fake proves the "one transaction" half by counting commits;
 * the second store over the same file proves the "survives recreation" half.
 */
class AudiophileStoresPersistenceTest {

    @Test
    fun `one settings update is one transaction and survives a process recreation`() {
        val prefs = RecordingSharedPreferences()
        val changed = AudiophileSettings.DEFAULTS.copy(
            dvc = DvcSettings(
                enabled = false,
                headroomDb = -3f,
                bluetoothPolicy = DvcBluetoothPolicy.FORCE_OFF,
                compensateVolume = true,
            ),
            advanced = AdvancedTweaks.DEFAULTS.copy(audioDiagnostics = true),
        )

        AudiophileSettingsStore(prefs).use { store ->
            assertEquals(AudiophileSettings.DEFAULTS.normalized(), store.state.value)
            val transactionsBefore = prefs.transactions.size
            store.update { current ->
                current.copy(dvc = changed.dvc, advanced = changed.advanced)
            }
            assertEquals(
                "one update must be exactly one preference transaction",
                transactionsBefore + 1,
                prefs.transactions.size,
            )
            val transaction = prefs.transactions.last()
            assertTrue(
                "the transaction carries the whole snapshot, not just the touched field",
                transaction.size > 20,
            )
            assertEquals(changed.normalized(), store.state.value)
        }

        // Process recreation: a brand-new store over the same file.
        AudiophileSettingsStore(prefs).use { restored ->
            assertEquals(changed.normalized(), restored.state.value)
        }
    }

    @Test
    fun `restoring defaults survives a process recreation too`() {
        val prefs = RecordingSharedPreferences()
        AudiophileSettingsStore(prefs).use { store ->
            store.updateDvc(DvcSettings(enabled = false, headroomDb = -9f, compensateVolume = true))
            store.restoreDefaults()
            assertEquals(AudiophileSettings.DEFAULTS.normalized(), store.state.value)
        }

        AudiophileSettingsStore(prefs).use { restored ->
            assertEquals(AudiophileSettings.DEFAULTS.normalized(), restored.state.value)
        }
    }

    @Test
    fun `output profiles survive recreation and a named device still overrides its route`() {
        val prefs = RecordingSharedPreferences()
        val route = AudioOutputRoute.BLUETOOTH
        val routeProfile = OutputProfile.DEFAULTS.copy(noDvc = true, bufferSizeFrames = 4096)
        val deviceTarget = AudioOutputTarget(route = route, deviceKey = "dev-1", deviceName = "Buds")
        val deviceProfile = OutputProfile.DEFAULTS.copy(float32 = true, visualDelayMs = 12)

        AudioOutputProfilesStore(prefs).use { store ->
            store.updateProfile(AudioOutputTarget.route(route), routeProfile)
            store.updateProfile(deviceTarget, deviceProfile)
            assertEquals(routeProfile.normalizedFor(route), store.resolveProfile(route))
            assertEquals(deviceProfile.normalizedFor(route), store.resolveProfile(route, "dev-1"))
        }

        AudioOutputProfilesStore(prefs).use { restored ->
            assertEquals(
                "the route profile must come back after recreation",
                routeProfile.normalizedFor(route),
                restored.resolveProfile(route),
            )
            assertEquals(
                "the named-device profile must come back after recreation",
                deviceProfile.normalizedFor(route),
                restored.resolveProfile(route, "dev-1"),
            )
            assertNotEquals(
                "the device override must still win over the route",
                restored.resolveProfile(route),
                restored.resolveProfile(route, "dev-1"),
            )
        }
    }

    @Test
    fun `removing a profile survives recreation as a return to stock defaults`() {
        val prefs = RecordingSharedPreferences()
        val route = AudioOutputRoute.USB
        val custom = OutputProfile.DEFAULTS.copy(noHeadroom = true)

        AudioOutputProfilesStore(prefs).use { store ->
            store.updateProfile(AudioOutputTarget.route(route), custom)
            assertEquals(custom.normalizedFor(route), store.resolveProfile(route))
            store.restoreDefaults(AudioOutputTarget.route(route))
            assertEquals(OutputProfile.DEFAULTS.normalizedFor(route), store.resolveProfile(route))
        }

        AudioOutputProfilesStore(prefs).use { restored ->
            assertEquals(OutputProfile.DEFAULTS.normalizedFor(route), restored.resolveProfile(route))
        }
    }
}
