package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.playback.audio.AudiophileProfileSource
import com.music.bitchord.playback.audio.resolveAudiophileOutput
import com.music.bitchord.ui.screens.audiophile.AdvancedTweaks
import com.music.bitchord.ui.screens.audiophile.AudioOutputRoute
import com.music.bitchord.ui.screens.audiophile.AudiophileSettings
import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.OutputDriver
import com.music.bitchord.ui.screens.audiophile.OutputProfile
import com.music.bitchord.ui.screens.audiophile.ResamplerQuality
import com.music.bitchord.ui.screens.audiophile.StoredAudioOutputProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAndAdvancedPolicyTest {
    @Test
    fun noProfileKeepsStockDriverAndVisualDelayDefaults() {
        val output = resolve()
        assertEquals(AudiophileProfileSource.STOCK_DEFAULTS, output.source)
        assertEquals(OutputDriver.AAUDIO, output.profile.driver)
        assertEquals(0, output.profile.visualDelayMs)
        assertFalse(output.effective.perfectBitPerfect)
    }

    @Test
    fun routeChangeIsSuppressedForTelemetryAndEmittedOnlyWhenIdentityChanges() {
        val usb = resolve(route = AudioOutputRoute.USB, routeKindName = "USB", deviceName = "USB DAC")
        val sameUsb = resolve(route = AudioOutputRoute.USB, routeKindName = "USB", deviceName = "USB DAC")
        val bluetooth = resolve(
            route = AudioOutputRoute.BLUETOOTH,
            routeKindName = "BLUETOOTH",
            deviceName = "Buds",
        )
        val tweaks = AdvancedTweaks(resetEqualizerOnRouteChange = true)

        assertNull(
            RouteEqualizerPolicy.onOutputChanged(
                AudioOutputRouteKey.of("USB", "USB DAC", usb),
                AudioOutputRouteKey.of("USB", "usb dac", sameUsb),
                usb,
                sameUsb,
                tweaks,
            ),
        )

        val change = RouteEqualizerPolicy.onOutputChanged(
            AudioOutputRouteKey.of("USB", "USB DAC", usb),
            AudioOutputRouteKey.of("BLUETOOTH", "Buds", bluetooth),
            usb,
            bluetooth,
            tweaks,
        )
        assertNotNull(change)
        assertTrue(change!!.resetEqualizer)
        assertEquals(usb.target, change.previous.target)
        assertEquals(bluetooth.target, change.current.target)
    }

    @Test
    fun perOutputNoEquToneAndPerfectBPExposeTheirConflictResult() {
        val namedTarget = com.music.bitchord.ui.screens.audiophile.AudioOutputTarget(
            AudioOutputRoute.WIRED,
            "WIRED:dac",
            "DAC",
        )
        val profiles = mapOf(
            "named" to StoredAudioOutputProfile(
                namedTarget,
                OutputProfile(noEquTone = true, noDuck = true, visualDelayMs = 120),
            ),
        )
        val output = resolve(
            route = AudioOutputRoute.WIRED,
            routeKindName = "WIRED",
            deviceName = "DAC",
            profiles = profiles,
        )
        assertEquals(AudiophileProfileSource.NAMED_DEVICE, output.source)
        assertFalse(output.effective.equalizerEnabled)
        assertFalse(output.effective.toneEnabled)
        assertFalse(output.effective.duckEnabled)
        assertEquals(120, output.profile.visualDelayMs)

        val perfect = resolve(globalPerfectBitPerfect = true)
        assertFalse(perfect.effective.equalizerEnabled)
        assertFalse(perfect.effective.duckEnabled)
        assertEquals(ResamplerQuality.OFF, perfect.effective.resamplerQuality)
        assertEquals(DitherMode.NONE, perfect.effective.dither)
    }

    @Test
    fun advancedOffloadIsBlockedByDspFocusDuckAndResamplerConflicts() {
        val idle = resolve(
            global = AudiophileSettings.DEFAULTS.copy(
                resampler = AudiophileSettings.DEFAULTS.resampler.copy(quality = ResamplerQuality.OFF),
                dvc = AudiophileSettings.DEFAULTS.dvc.copy(headroomDb = 0f, compensateVolume = false),
            ),
            equalizerRequested = false,
            toneRequested = false,
            replayGainRequested = false,
            crossfadeRequested = false,
        )
        val tweaks = AdvancedTweaks(allowOffload = true)
        assertTrue(AdvancedOutputPolicyResolver.resolve(tweaks, idle).offloadAllowed)
        assertFalse(AdvancedOutputPolicyResolver.resolve(tweaks, idle, focusDucked = true).offloadAllowed)

        val resampling = resolve()
        val policy = AdvancedOutputPolicyResolver.resolve(tweaks, resampling)
        assertFalse(policy.offloadAllowed)
        assertTrue(policy.decodedPcmRequired)
    }

    @Test
    fun perOutputEqPresetNoAfAndDvcCompensateFoldIntoTheEffectiveOutput() {
        val namedTarget = com.music.bitchord.ui.screens.audiophile.AudioOutputTarget(
            AudioOutputRoute.USB,
            "USB:dac",
            "DAC",
        )
        val profiles = mapOf(
            "named" to StoredAudioOutputProfile(
                namedTarget,
                OutputProfile(eqPreset = "ROCK", noAf = true, noDvc = true),
            ),
        )
        val global = AudiophileSettings.DEFAULTS.copy(
            dvc = AudiophileSettings.DEFAULTS.dvc.copy(enabled = true, compensateVolume = true),
        )
        val output = resolve(
            route = AudioOutputRoute.USB,
            routeKindName = "USB",
            deviceName = "DAC",
            global = global,
            profiles = profiles,
        )
        assertEquals("ROCK", output.effective.equalizerPreset)
        assertTrue(output.effective.ignoreAudioFocus)
        // DVC is off for this profile (noDvc), so the compensation gain applies.
        assertFalse(output.effective.dvcEnabled)
        assertEquals(
            com.music.bitchord.ui.screens.audiophile.DvcSettings.COMPENSATE_DB,
            output.effective.dvcCompensateDb,
        )

        // Without the profile flags: preset follows global (null), focus intact,
        // and with DVC enabled the compensation stays zero.
        val stock = resolve(global = global)
        assertNull(stock.effective.equalizerPreset)
        assertFalse(stock.effective.ignoreAudioFocus)
        assertTrue(stock.effective.dvcEnabled)
        assertEquals(0f, stock.effective.dvcCompensateDb)

        // DVC disabled globally with compensate on: compensation applies even stock.
        val noDvc = resolve(global = global.copy(dvc = global.dvc.copy(enabled = false)))
        assertFalse(noDvc.effective.dvcEnabled)
        assertEquals(
            com.music.bitchord.ui.screens.audiophile.DvcSettings.COMPENSATE_DB,
            noDvc.effective.dvcCompensateDb,
        )
    }

    private fun resolve(
        route: AudioOutputRoute = AudioOutputRoute.USB,
        routeKindName: String = "USB",
        deviceName: String = "USB DAC",
        global: AudiophileSettings = AudiophileSettings.DEFAULTS,
        profiles: Map<String, StoredAudioOutputProfile> = emptyMap(),
        globalPerfectBitPerfect: Boolean = false,
        equalizerRequested: Boolean = true,
        toneRequested: Boolean = true,
        replayGainRequested: Boolean = true,
        crossfadeRequested: Boolean = true,
    ) = resolveAudiophileOutput(
        route = route,
        routeKindName = routeKindName,
        deviceName = deviceName,
        global = global,
        profiles = profiles,
        baselineDriver = OutputDriver.AAUDIO,
        baselineFloat32 = true,
        globalPerfectBitPerfect = globalPerfectBitPerfect,
        equalizerRequested = equalizerRequested,
        toneRequested = toneRequested,
        replayGainRequested = replayGainRequested,
        crossfadeRequested = crossfadeRequested,
    )
}
