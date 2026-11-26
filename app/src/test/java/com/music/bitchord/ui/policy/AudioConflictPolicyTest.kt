package com.music.bitchord.ui.policy

import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.drivers.OutputDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConflictPolicyTest {
    @Test
    fun `request and cancel leave every setting untouched`() {
        val settings = AudioPolicySettings(
            bitPerfect = false,
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.HYBRID,
            outputDriver = OutputDriver.HI_RES,
        )
        val requested = AudioConflictPolicy.reduce(
            AudioPolicyUiState(settings),
            AudioPolicyIntent.Request(AudioPolicyChange.SetBitPerfect(true)),
        )

        assertSame(settings, requested.state.settings)
        assertEquals(AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP, requested.state.pendingConflict?.kind)
        assertNull(requested.transaction)

        val cancelled = AudioConflictPolicy.reduce(requested.state, AudioPolicyIntent.CancelConflict)
        assertSame(settings, cancelled.state.settings)
        assertEquals(settings, cancelled.state.settings)
        assertNull(cancelled.state.pendingConflict)
        assertNull(cancelled.transaction)
    }

    @Test
    fun `confirm disables Quality Options before atomically enabling Bit-perfect`() {
        val before = AudioPolicySettings(
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.HIBY,
            outputDriver = OutputDriver.HI_RES,
        )
        val pending = request(before, AudioPolicyChange.SetBitPerfect(true))

        val confirmed = AudioConflictPolicy.reduce(pending.state, AudioPolicyIntent.ConfirmConflict)
        val transaction = confirmed.transaction!!
        assertEquals(before, transaction.before)
        assertEquals(
            AudioPolicySettings(
                bitPerfect = true,
                qualityOptions = false,
                referenceEngineMode = ReferenceEngineMode.HIBY,
                outputDriver = OutputDriver.HI_RES,
            ),
            transaction.after,
        )
        assertEquals(
            listOf(
                AudioPolicyOperation.SetQualityOptions(false),
                AudioPolicyOperation.SetBitPerfect(true),
            ),
            transaction.operations,
        )
        assertEquals(AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP, transaction.resolvedConflict)
        assertEquals(transaction.after, confirmed.state.settings)
        assertNull(confirmed.state.pendingConflict)
    }

    @Test
    fun `inactive Quality Options does not invent a conflict`() {
        val bitPerfectMode = AudioPolicySettings(
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.BIT_PERFECT,
        )
        val direct = request(bitPerfectMode, AudioPolicyChange.SetBitPerfect(true))
        assertNull(direct.state.pendingConflict)
        assertEquals(bitPerfectMode.copy(bitPerfect = true), direct.state.settings)
        assertEquals(
            listOf(AudioPolicyOperation.SetBitPerfect(true)),
            direct.transaction?.operations,
        )

        val alreadyUsbBypassed = AudioPolicySettings(
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.HIBY,
            outputDriver = OutputDriver.USB_EXCLUSIVE,
        )
        val usb = request(alreadyUsbBypassed, AudioPolicyChange.SetBitPerfect(true))
        assertNull(usb.state.pendingConflict)
        assertEquals(alreadyUsbBypassed.copy(bitPerfect = true), usb.state.settings)
    }

    @Test
    fun `reverse DSP request releases Bit-perfect before enabling DSP`() {
        val before = AudioPolicySettings(
            bitPerfect = true,
            referenceEngineMode = ReferenceEngineMode.POWERAMP,
        )
        val pending = request(before, AudioPolicyChange.SetQualityOptions(true))
        assertEquals(AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP, pending.state.pendingConflict?.kind)
        assertEquals(before, pending.state.settings)

        val confirmed = AudioConflictPolicy.reduce(pending.state, AudioPolicyIntent.ConfirmConflict)
        assertEquals(
            listOf(
                AudioPolicyOperation.SetBitPerfect(false),
                AudioPolicyOperation.SetQualityOptions(true),
            ),
            confirmed.transaction?.operations,
        )
        assertEquals(before.copy(bitPerfect = false, qualityOptions = true), confirmed.state.settings)
    }

    @Test
    fun `exclusive USB confirmation retires HiBy before opening Auris sink`() {
        val before = AudioPolicySettings(
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.HIBY,
            outputDriver = OutputDriver.AUDIO_TRACK,
        )
        val pending = request(before, AudioPolicyChange.SetOutputDriver(OutputDriver.USB_EXCLUSIVE))
        assertEquals(AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS, pending.state.pendingConflict?.kind)
        assertEquals(before, pending.state.settings)

        val confirmed = AudioConflictPolicy.reduce(pending.state, AudioPolicyIntent.ConfirmConflict)
        assertEquals(
            listOf(
                AudioPolicyOperation.SetQualityOptions(false),
                AudioPolicyOperation.SetOutputDriver(OutputDriver.USB_EXCLUSIVE),
            ),
            confirmed.transaction?.operations,
        )
        assertEquals(
            before.copy(qualityOptions = false, outputDriver = OutputDriver.USB_EXCLUSIVE),
            confirmed.state.settings,
        )
    }

    @Test
    fun `HiBy request releases Auris USB before native sink selection`() {
        val before = AudioPolicySettings(
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.POWERAMP,
            outputDriver = OutputDriver.USB_EXCLUSIVE,
        )
        val pending = request(before, AudioPolicyChange.SetReferenceEngineMode(ReferenceEngineMode.HIBY))
        assertEquals(AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS, pending.state.pendingConflict?.kind)

        val confirmed = AudioConflictPolicy.reduce(pending.state, AudioPolicyIntent.ConfirmConflict)
        assertEquals(
            listOf(
                AudioPolicyOperation.SetOutputDriver(OutputDriver.AUDIO_TRACK),
                AudioPolicyOperation.SetReferenceEngineMode(ReferenceEngineMode.HIBY),
            ),
            confirmed.transaction?.operations,
        )
        assertEquals(
            before.copy(
                referenceEngineMode = ReferenceEngineMode.HIBY,
                outputDriver = OutputDriver.AUDIO_TRACK,
            ),
            confirmed.state.settings,
        )
        assertEquals(ExclusiveUsbSink.HIBY_NATIVE, confirmed.state.settings.exclusiveUsbSink)
    }

    @Test
    fun `Bit-perfect and USB driver sharing one Auris sink do not conflict`() {
        val before = AudioPolicySettings(bitPerfect = true)
        val result = request(before, AudioPolicyChange.SetOutputDriver(OutputDriver.USB_EXCLUSIVE))
        assertNull(result.state.pendingConflict)
        assertEquals(ExclusiveUsbSink.AURIS_USBDEVFS, before.exclusiveUsbSink)
        assertEquals(ExclusiveUsbSink.AURIS_USBDEVFS, result.state.settings.exclusiveUsbSink)
        assertEquals(
            listOf(AudioPolicyOperation.SetOutputDriver(OutputDriver.USB_EXCLUSIVE)),
            result.transaction?.operations,
        )
    }

    @Test
    fun `confirm uses latest synchronized state and cannot replay stale writes`() {
        val before = AudioPolicySettings(
            qualityOptions = true,
            referenceEngineMode = ReferenceEngineMode.POWERAMP,
        )
        val pending = request(before, AudioPolicyChange.SetBitPerfect(true))
        val externallyResolved = before.copy(qualityOptions = false)
        val synced = AudioConflictPolicy.reduce(
            pending.state,
            AudioPolicyIntent.SyncSettings(externallyResolved),
        )

        val confirmed = AudioConflictPolicy.reduce(synced.state, AudioPolicyIntent.ConfirmConflict)
        assertNull(confirmed.transaction?.resolvedConflict)
        assertEquals(
            listOf(AudioPolicyOperation.SetBitPerfect(true)),
            confirmed.transaction?.operations,
        )
        assertEquals(externallyResolved.copy(bitPerfect = true), confirmed.state.settings)

        val repeated = AudioConflictPolicy.reduce(confirmed.state, AudioPolicyIntent.ConfirmConflict)
        assertNull(repeated.transaction)
        assertEquals(confirmed.state, repeated.state)
    }

    @Test
    fun `non conflicting and disabling changes never open dialog`() {
        val states = listOf(
            AudioPolicySettings() to AudioPolicyChange.SetQualityOptions(true),
            AudioPolicySettings(bitPerfect = true) to AudioPolicyChange.SetBitPerfect(false),
            AudioPolicySettings(qualityOptions = true) to AudioPolicyChange.SetQualityOptions(false),
            AudioPolicySettings() to AudioPolicyChange.SetOutputDriver(OutputDriver.AAUDIO),
            AudioPolicySettings() to AudioPolicyChange.SetReferenceEngineMode(ReferenceEngineMode.HIBY),
        )
        states.forEach { (settings, change) ->
            val result = request(settings, change)
            assertNull(result.state.pendingConflict)
            assertTrue(result.transaction != null || change.applyTo(settings) == settings)
        }
    }

    private fun request(settings: AudioPolicySettings, change: AudioPolicyChange): AudioPolicyReduction =
        AudioConflictPolicy.reduce(
            AudioPolicyUiState(settings),
            AudioPolicyIntent.Request(change),
        )
}
