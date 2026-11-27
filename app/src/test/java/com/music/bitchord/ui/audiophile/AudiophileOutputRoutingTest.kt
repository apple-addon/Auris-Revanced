package com.music.bitchord.ui.audiophile

import com.music.bitchord.playback.audio.drivers.OutputDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiophileOutputRoutingTest {
    @Test
    fun defaultsSelectCanonicalAudioTrackExactlyOnce() {
        val state = AudiophileAudioState()

        assertEquals(OutputDriver.AUDIO_TRACK, state.outputDriver)
        assertEquals(
            listOf(AudioOutputRouteId.AUDIO_TRACK),
            state.outputs.filter { it.enabled && it.id != AudioOutputRouteId.CHROMECAST }.map { it.id },
        )
        assertFalse(state.outputs.first { it.id == AudioOutputRouteId.OPEN_SL_ES }.enabled)
    }

    @Test
    fun selectingRouteIsOneHotAndPreservesIndependentOptions() {
        val initialOutputs = AudiophileAudioDefaults.outputRoutes.map { route ->
            when (route.id) {
                AudioOutputRouteId.OPEN_SL_ES -> route.copy(
                    enabled = true,
                    status = OutputConnectionStatus.ACTIVE,
                    statusDetail = "stale active route",
                    profiles = route.profiles.mapIndexed { index, profile ->
                        if (index == 0) {
                            profile.copy(connectionStatus = OutputConnectionStatus.ACTIVE)
                        } else {
                            profile
                        }
                    },
                )
                AudioOutputRouteId.AAUDIO -> route.copy(
                    profiles = route.profiles.map { profile ->
                        profile.copy(enabled = false, noDvc = true)
                    },
                )
                AudioOutputRouteId.CHROMECAST -> route.copy(
                    enabled = true,
                    status = OutputConnectionStatus.CONNECTED,
                )
                else -> route
            }
        }
        val original = AudiophileAudioState(
            replayGain = ReplayGainState(source = ReplayGainSource.SMART),
            outputs = initialOutputs,
            outputDriver = OutputDriver.OPENSL_ES,
        )

        val selected = AudiophileAudioReducer.selectOutputRoute(original, AudioOutputRouteId.HI_RES)

        assertEquals(OutputDriver.HI_RES, selected.outputDriver)
        assertEquals(
            listOf(AudioOutputRouteId.HI_RES),
            selected.outputs.filter { it.enabled && it.id != AudioOutputRouteId.CHROMECAST }.map { it.id },
        )
        assertEquals(OutputConnectionStatus.AVAILABLE, selected.route(AudioOutputRouteId.HI_RES).status)
        assertEquals(OutputConnectionStatus.AVAILABLE, selected.route(AudioOutputRouteId.OPEN_SL_ES).status)
        assertEquals("", selected.route(AudioOutputRouteId.OPEN_SL_ES).statusDetail)
        assertTrue(
            selected.route(AudioOutputRouteId.OPEN_SL_ES).profiles.none {
                it.connectionStatus == OutputConnectionStatus.ACTIVE
            },
        )
        assertTrue(selected.route(AudioOutputRouteId.AAUDIO).profiles.all { !it.enabled && it.noDvc })
        assertTrue(selected.route(AudioOutputRouteId.CHROMECAST).enabled)
        assertEquals(OutputConnectionStatus.CONNECTED, selected.route(AudioOutputRouteId.CHROMECAST).status)
        assertEquals(original.replayGain, selected.replayGain)
    }

    @Test
    fun reconciliationAfterCancellationRestoresCommittedRouteAndClearsPendingBadges() {
        val committed = AudiophileAudioReducer.selectOutputRoute(
            AudiophileAudioState(),
            AudioOutputRouteId.HI_RES,
        )
        val pendingUiProposal = AudiophileAudioReducer.selectOutputRoute(
            committed,
            AudioOutputRouteId.USB_EXCLUSIVE,
        )
        assertEquals(OutputDriver.USB_EXCLUSIVE, pendingUiProposal.outputDriver)

        // AppSettings performs this same reconciliation with the unchanged canonical value when
        // a conflict is requested and again when its confirmation is cancelled.
        val pending = AudiophileOutputRouting.reconcile(pendingUiProposal, committed.outputDriver)
        val cancelled = AudiophileOutputRouting.reconcile(pending, committed.outputDriver)

        assertEquals(OutputDriver.HI_RES, cancelled.outputDriver)
        assertEquals(
            listOf(AudioOutputRouteId.HI_RES),
            cancelled.outputs.filter { it.enabled && it.id != AudioOutputRouteId.CHROMECAST }.map { it.id },
        )
        assertEquals(OutputConnectionStatus.AVAILABLE, cancelled.route(AudioOutputRouteId.HI_RES).status)
        assertFalse(cancelled.route(AudioOutputRouteId.USB_EXCLUSIVE).enabled)
        assertTrue(
            cancelled.outputs.filter { it.id != AudioOutputRouteId.CHROMECAST && !it.enabled }.none {
                it.status == OutputConnectionStatus.ACTIVE ||
                    it.profiles.any { profile -> profile.connectionStatus == OutputConnectionStatus.ACTIVE }
            },
        )
    }

    @Test
    fun usbSystemBitPerfectStaysDistinctFromUserspaceUsbSelection() {
        val reconciled = AudiophileOutputRouting.reconcile(
            AudiophileAudioState(),
            OutputDriver.USB_SYSTEM_BIT_PERFECT,
        )

        assertEquals(OutputDriver.USB_SYSTEM_BIT_PERFECT, reconciled.outputDriver)
        assertEquals(AudioOutputRouteId.USB_EXCLUSIVE, AudiophileOutputRouting.routeFor(reconciled.outputDriver))
        assertEquals(OutputDriver.USB_EXCLUSIVE, AudiophileOutputRouting.driverFor(AudioOutputRouteId.USB_EXCLUSIVE))
        assertEquals(
            listOf(AudioOutputRouteId.USB_EXCLUSIVE),
            reconciled.outputs.filter { it.enabled && it.id != AudioOutputRouteId.CHROMECAST }.map { it.id },
        )

        // Re-tapping the checked USB route must preserve the system backend, not
        // silently switch the user back to userspace USB Exclusive.
        assertSame(
            reconciled,
            AudiophileAudioReducer.selectOutputRoute(reconciled, AudioOutputRouteId.USB_EXCLUSIVE),
        )
        val switched = AudiophileAudioReducer.selectOutputDriver(
            reconciled,
            OutputDriver.USB_EXCLUSIVE,
        )
        assertEquals(OutputDriver.USB_EXCLUSIVE, switched.outputDriver)
        assertEquals(
            listOf(AudioOutputRouteId.USB_EXCLUSIVE),
            switched.outputs.filter { it.enabled && it.id != AudioOutputRouteId.CHROMECAST }.map { it.id },
        )
    }

    @Test
    fun selectionNeverPromotesARouteToActiveButPreservesVerifiedRuntimeActivity() {
        val selected = AudiophileAudioReducer.selectOutputDriver(
            AudiophileAudioState(),
            OutputDriver.AAUDIO,
        )
        assertEquals(OutputConnectionStatus.AVAILABLE, selected.route(AudioOutputRouteId.AAUDIO).status)

        val streaming = AudiophileAudioState(
            outputs = AudiophileAudioDefaults.outputRoutes.map { route ->
                if (route.id == AudioOutputRouteId.AAUDIO) {
                    route.copy(status = OutputConnectionStatus.ACTIVE)
                } else {
                    route
                }
            },
            outputDriver = OutputDriver.AAUDIO,
        )
        val reconciled = AudiophileOutputRouting.reconcile(streaming, OutputDriver.AAUDIO)
        assertEquals(OutputConnectionStatus.ACTIVE, reconciled.route(AudioOutputRouteId.AAUDIO).status)
    }

    @Test
    fun resetAllRestoresAudioTrackButSingleRouteResetKeepsSelection() {
        val custom = AudiophileAudioReducer.selectOutputRoute(
            AudiophileAudioState(),
            AudioOutputRouteId.AAUDIO,
        ).let { selected ->
            AudiophileAudioReducer.updateOutputProfile(
                selected,
                AudioOutputRouteId.AAUDIO,
                OutputDeviceKind.SPEAKER.name.lowercase(),
            ) { it.copy(noDvc = true) }
        }.let { selected ->
            AudiophileAudioReducer.updateOutputRoute(selected, AudioOutputRouteId.CHROMECAST) {
                it.copy(enabled = true)
            }
        }

        val oneRoute = AudiophileAudioReducer.resetOutputRoute(custom, AudioOutputRouteId.OPEN_SL_ES)
        assertEquals(OutputDriver.AAUDIO, oneRoute.outputDriver)
        assertTrue(oneRoute.route(AudioOutputRouteId.AAUDIO).enabled)
        assertTrue(oneRoute.route(AudioOutputRouteId.AAUDIO).profiles.first().noDvc)
        assertTrue(oneRoute.route(AudioOutputRouteId.CHROMECAST).enabled)

        val reset = AudiophileAudioReducer.resetOutputs(custom)
        assertEquals(OutputDriver.AUDIO_TRACK, reset.outputDriver)
        assertEquals(
            listOf(AudioOutputRouteId.AUDIO_TRACK),
            reset.outputs.filter { it.enabled && it.id != AudioOutputRouteId.CHROMECAST }.map { it.id },
        )
        assertFalse(reset.route(AudioOutputRouteId.CHROMECAST).enabled)
        assertTrue(reset.outputs.all { route -> route.profiles.all { !it.noDvc } })
    }

    @Test
    fun castAndUnavailableRoutesCannotBecomeFakeDriverSelections() {
        val original = AudiophileAudioState()
        assertSame(original, AudiophileAudioReducer.selectOutputRoute(original, AudioOutputRouteId.CHROMECAST))
        val directFlagMutation = AudiophileAudioReducer.updateOutputRoute(
            original,
            AudioOutputRouteId.HI_RES,
        ) { it.copy(enabled = true) }
        assertFalse(directFlagMutation.route(AudioOutputRouteId.HI_RES).enabled)
        assertTrue(directFlagMutation.route(AudioOutputRouteId.AUDIO_TRACK).enabled)
        val unavailable = AudiophileAudioState(
            outputs = AudiophileAudioDefaults.outputRoutes.map { route ->
                if (route.id == AudioOutputRouteId.AAUDIO) route.copy(available = false) else route
            },
        )
        assertSame(unavailable, AudiophileAudioReducer.selectOutputRoute(unavailable, AudioOutputRouteId.AAUDIO))
    }

    private fun AudiophileAudioState.route(id: AudioOutputRouteId): AudioOutputRouteState =
        outputs.first { it.id == id }
}
