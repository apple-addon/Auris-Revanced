package com.music.bitchord.testing

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] that records every committed transaction.
 *
 * "Atomic persistence" is a claim about *one* editor transaction carrying the
 * whole write, so the fake keeps each commit/apply as a separate entry in
 * [transactions] — a setter that wrote half its keys in one transaction and
 * half in another would show up as two entries and fail the count.
 */
class RecordingSharedPreferences : SharedPreferences {

    private val map = HashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** One entry per editor commit/apply, holding that transaction's staged puts. */
    val transactions = mutableListOf<Map<String, Any?>>()

    override fun getAll(): Map<String, Any?> = map.toMap()

    override fun getString(key: String, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private object Remove

    private inner class Editor : SharedPreferences.Editor {
        private val staged = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            put(key, values)

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            staged[key] = Remove
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun commit(): Boolean {
            applyStaged()
            return true
        }

        override fun apply() {
            applyStaged()
        }

        private fun applyStaged() {
            transactions += staged.toMap()
            if (clear) map.clear()
            staged.forEach { (key, value) ->
                if (value === Remove) map.remove(key) else map[key] = value
            }
            staged.clear()
            clear = false
            listeners.forEach { it.onSharedPreferenceChanged(this@RecordingSharedPreferences, null) }
        }
    }
}
