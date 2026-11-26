package com.music.bitchord.testing

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import java.lang.reflect.Field

/**
 * Reaches the two `lateinit` fields of the [AppSettings] singleton so a test
 * can give it a [SharedPreferences] without running the full
 * [AppSettings.init] (which opens stores, registers receivers and starts
 * coroutines a unit test does not want).
 *
 * [AppSettings.reload] additionally needs [AppSettings]'s auth store, which
 * is why [installAuthStore] exists; on a plain JVM there is no [Context], so
 * tests that never reload install only [installPrefs].
 */
object AppSettingsTestAccess {

    /** The preference file the production app writes, by name. */
    const val PREFS_NAME = "bitchord_settings"

    fun installPrefs(prefs: SharedPreferences) {
        field("prefs").set(AppSettings, prefs)
    }

    fun installPrefs(context: Context) {
        installPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    fun installAuthStore(context: Context) {
        field("authStore").set(AppSettings, AuthStore(context))
    }

    /** The stored value of a `private const val KEY_…` in [AppSettings], e.g. `"KEY_QUALITY_OPTIONS"`. */
    fun keyConst(name: String): String =
        AppSettings::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null) as String

    private fun field(name: String): Field =
        AppSettings::class.java.getDeclaredField(name).apply { isAccessible = true }
}
