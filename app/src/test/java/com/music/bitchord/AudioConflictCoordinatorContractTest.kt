package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.data.settings.PendingAudioConflict
import com.music.bitchord.testing.AppSettingsTestAccess
import com.music.bitchord.testing.RecordingSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The centralized conflict contract, at the coordinator and at the disk.
 *
 * Every Quality Options / Perfect Bit Perfect toggle goes through
 * [AudioConflictCoordinator]. The rules under test:
 *
 *  - a request that conflicts never mutates state — it only raises
 *    [AudioConflictCoordinator.pending], so **cancel** has nothing to undo:
 *    it drops the request and both the flows and the preference file are
 *    exactly as before;
 *  - **confirm** performs the ordered, atomic transaction: Quality Options
 *    off and Bit Perfect on, written in *one* editor transaction;
 *  - turning either switch off never asks, and a conflict-free enable never
 *    asks either — those apply directly.
 *
 * The fake preferences record each committed transaction separately, so
 * "atomic" is asserted as "the last transaction contains both keys", not as
 * "the two keys ended up with the right values" (which would also pass if
 * they had been written by two separate commits).
 */
class AudioConflictCoordinatorContractTest {

    private lateinit var prefs: RecordingSharedPreferences

    private val keyQualityOptions get() = AppSettingsTestAccess.keyConst("KEY_QUALITY_OPTIONS")
    private val keyBitPerfect get() = AppSettingsTestAccess.keyConst("KEY_BIT_PERFECT")

    @Before
    fun setUp() {
        prefs = RecordingSharedPreferences()
        AppSettingsTestAccess.installPrefs(prefs)
        AppSettings.qualityOptions.value = false
        AppSettings.bitPerfect.value = false
        AppSettings.msebEnabled.value = false
        AppSettings.peqEnabled.value = false
        AudioConflictCoordinator.cancel()
    }

    @After
    fun tearDown() {
        AudioConflictCoordinator.cancel()
        AppSettings.qualityOptions.value = false
        AppSettings.bitPerfect.value = false
        AppSettings.msebEnabled.value = false
        AppSettings.peqEnabled.value = false
        AppSettingsTestAccess.installPrefs(RecordingSharedPreferences())
    }

    @Test
    fun `a conflicting quality-options request asks first and mutates nothing`() {
        AppSettings.bitPerfect.value = true
        AppSettings.msebEnabled.value = true
        val writesBefore = prefs.transactions.size

        AudioConflictCoordinator.requestQualityOptions(true)

        assertEquals(PendingAudioConflict.QUALITY_OPTIONS, AudioConflictCoordinator.pending.value)
        assertFalse("the request must not be applied behind the dialog", AppSettings.qualityOptions.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertEquals(
            "a request that requires confirmation must not write anything",
            writesBefore,
            prefs.transactions.size,
        )
    }

    @Test
    fun `a conflicting bit-perfect request asks first and mutates nothing`() {
        AppSettings.qualityOptions.value = true
        AppSettings.peqEnabled.value = true
        val writesBefore = prefs.transactions.size

        AudioConflictCoordinator.requestNativeBitPerfect(true)

        assertEquals(PendingAudioConflict.NATIVE_BIT_PERFECT, AudioConflictCoordinator.pending.value)
        assertFalse(AppSettings.bitPerfect.value)
        assertTrue(AppSettings.qualityOptions.value)
        assertEquals(writesBefore, prefs.transactions.size)
    }

    @Test
    fun `cancel drops the request and leaves state and disk untouched`() {
        AppSettings.bitPerfect.value = true
        AppSettings.msebEnabled.value = true
        AudioConflictCoordinator.requestQualityOptions(true)
        val writesBefore = prefs.transactions.size

        AudioConflictCoordinator.cancel()

        assertNull(AudioConflictCoordinator.pending.value)
        assertFalse(AppSettings.qualityOptions.value)
        assertTrue("cancel must not switch bit-perfect either", AppSettings.bitPerfect.value)
        assertEquals(writesBefore, prefs.transactions.size)
        assertFalse(prefs.contains(keyQualityOptions))
    }

    @Test
    fun `confirm on a bit-perfect conflict writes both keys in one atomic transaction`() {
        AppSettings.qualityOptions.value = true
        AppSettings.msebEnabled.value = true
        AudioConflictCoordinator.requestNativeBitPerfect(true)
        val writesBefore = prefs.transactions.size

        AudioConflictCoordinator.confirm()

        assertNull(AudioConflictCoordinator.pending.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertFalse(AppSettings.qualityOptions.value)
        assertEquals("confirm is exactly one preference transaction", writesBefore + 1, prefs.transactions.size)
        val transaction = prefs.transactions.last()
        assertEquals(true, transaction[keyBitPerfect])
        assertEquals(false, transaction[keyQualityOptions])
        // …and the transaction actually reached the "disk".
        assertTrue(prefs.getBoolean(keyBitPerfect, false))
        assertFalse(prefs.getBoolean(keyQualityOptions, true))
    }

    @Test
    fun `confirm on a quality-options conflict resolves the same way in one transaction`() {
        AppSettings.bitPerfect.value = true
        AppSettings.msebEnabled.value = true
        AudioConflictCoordinator.requestQualityOptions(true)
        val writesBefore = prefs.transactions.size

        AudioConflictCoordinator.confirm()

        assertNull(AudioConflictCoordinator.pending.value)
        assertFalse("quality options stay off: bit-perfect wins the conflict", AppSettings.qualityOptions.value)
        assertTrue(AppSettings.bitPerfect.value)
        assertEquals(writesBefore + 1, prefs.transactions.size)
        val transaction = prefs.transactions.last()
        assertEquals(true, transaction[keyBitPerfect])
        assertEquals(false, transaction[keyQualityOptions])
    }

    @Test
    fun `confirm with nothing pending is a no-op`() {
        val writesBefore = prefs.transactions.size

        AudioConflictCoordinator.confirm()

        assertNull(AudioConflictCoordinator.pending.value)
        assertEquals(writesBefore, prefs.transactions.size)
    }

    @Test
    fun `turning either switch off never asks and applies directly`() {
        AppSettings.bitPerfect.value = true
        AppSettings.msebEnabled.value = true
        AppSettings.qualityOptions.value = true

        AudioConflictCoordinator.requestQualityOptions(false)
        assertNull(AudioConflictCoordinator.pending.value)
        assertFalse(AppSettings.qualityOptions.value)

        AudioConflictCoordinator.requestNativeBitPerfect(false)
        assertNull(AudioConflictCoordinator.pending.value)
        assertFalse(AppSettings.bitPerfect.value)
    }

    @Test
    fun `a conflict-free enable applies directly without a dialog`() {
        AudioConflictCoordinator.requestQualityOptions(true)

        assertNull(AudioConflictCoordinator.pending.value)
        assertTrue(AppSettings.qualityOptions.value)
        assertEquals(true, prefs.getBoolean(keyQualityOptions, false))

        AudioConflictCoordinator.requestNativeBitPerfect(true)
        assertNull(AudioConflictCoordinator.pending.value)
        assertTrue(AppSettings.bitPerfect.value)
    }

    @Test
    fun `a configured DSP stage only conflicts while quality options is on`() {
        // msebEnabled is the "configured" flag; with quality options off and
        // bit-perfect off there is no conflict in either direction.
        AppSettings.msebEnabled.value = true

        AudioConflictCoordinator.requestQualityOptions(true)
        assertNull(AudioConflictCoordinator.pending.value)
        assertTrue(AppSettings.qualityOptions.value)

        AudioConflictCoordinator.requestNativeBitPerfect(true)
        // quality options is now on with DSP configured, so this one asks.
        assertEquals(PendingAudioConflict.NATIVE_BIT_PERFECT, AudioConflictCoordinator.pending.value)
        assertFalse(AppSettings.bitPerfect.value)
    }
}
