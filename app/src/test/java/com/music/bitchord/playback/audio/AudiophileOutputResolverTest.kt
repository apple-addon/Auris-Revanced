package com.music.bitchord.playback.audio

import com.music.bitchord.ui.screens.audiophile.AudioOutputRoute
import com.music.bitchord.ui.screens.audiophile.AudioOutputTarget
import com.music.bitchord.ui.screens.audiophile.AudiophileSettings
import com.music.bitchord.ui.screens.audiophile.DitherMode
import com.music.bitchord.ui.screens.audiophile.OutputDriver
import com.music.bitchord.ui.screens.audiophile.OutputProfile
import com.music.bitchord.ui.screens.audiophile.ResamplerQuality
import com.music.bitchord.ui.screens.audiophile.StoredAudioOutputProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiophileOutputResolverTest {
    @Test
    fun noProfilePreservesStockDriverAndPcmMode() {
        val resolved = resolve()

        assertEquals(AudiophileProfileSource.STOCK_DEFAULTS, resolved.source)
        assertEquals(OutputDriver.AAUDIO, resolved.effective.driver)
        assertTrue(resolved.effective.float32)
        assertEquals(AudioOutputTarget.route(AudioOutputRoute.USB), resolved.target)
    }

    @Test
    fun namedDeviceBeatsRouteAndMatchesProductNameCaseInsensitively() {
        val namedTarget = AudioOutputTarget(
            AudioOutputRoute.BLUETOOTH,
            "BLUETOOTH:reference headphones",
            "Reference Headphones",
        )
        val routeTarget = AudioOutputTarget.route(AudioOutputRoute.BLUETOOTH)
        val resolved = resolve(
            route = AudioOutputRoute.BLUETOOTH,
            routeKindName = "BLUETOOTH",
            deviceName = "Reference Headphones",
            profiles = mapOf(
                "route" to StoredAudioOutputProfile(routeTarget, OutputProfile(driver = OutputDriver.AUDIO_TRACK)),
                "named" to StoredAudioOutputProfile(namedTarget, OutputProfile(driver = OutputDriver.OPENSL_ES)),
            ),
        )

        assertEquals(AudiophileProfileSource.NAMED_DEVICE, resolved.source)
        assertEquals(OutputDriver.OPENSL_ES, resolved.effective.driver)
        assertTrue(resolved.effective.float32)
    }

    @Test
    fun routeProfileAppliesOnlyWhenNamedProfileIsAbsent() {
        val target = AudioOutputTarget.route(AudioOutputRoute.WIRED)
        val resolved = resolve(
            route = AudioOutputRoute.WIRED,
            routeKindName = "WIRED",
            deviceName = "DAC",
            profiles = mapOf(
                "route" to StoredAudioOutputProfile(target, OutputProfile(driver = OutputDriver.HI_RES)),
            ),
        )

        assertEquals(AudiophileProfileSource.ROUTE, resolved.source)
        assertEquals(OutputDriver.HI_RES, resolved.effective.driver)
    }

    @Test
    fun perfectBitPerfectSuppressesProcessingWithoutChangingStoredChoices() {
        val global = AudiophileSettings.DEFAULTS.copy(
            resampler = AudiophileSettings.DEFAULTS.resampler.copy(
                quality = ResamplerQuality.VERY_HIGH,
                dither = DitherMode.TRIANGULAR,
            ),
        )
        val resolved = resolve(globalPerfectBitPerfect = true, global = global)

        assertTrue(resolved.effective.perfectBitPerfect)
        assertFalse(resolved.effective.dvcEnabled)
        assertFalse(resolved.effective.equalizerEnabled)
        assertFalse(resolved.effective.toneEnabled)
        assertFalse(resolved.effective.replayGainEnabled)
        assertFalse(resolved.effective.crossfadeEnabled)
        assertFalse(resolved.effective.duckEnabled)
        assertEquals(ResamplerQuality.OFF, resolved.effective.resamplerQuality)
        assertEquals(DitherMode.NONE, resolved.effective.dither)
        assertEquals(ResamplerQuality.VERY_HIGH, global.resampler.quality)
        assertEquals(DitherMode.TRIANGULAR, global.resampler.dither)
    }

    private fun resolve(
        route: AudioOutputRoute = AudioOutputRoute.USB,
        routeKindName: String = "USB",
        deviceName: String = "USB DAC",
        global: AudiophileSettings = AudiophileSettings.DEFAULTS,
        profiles: Map<String, StoredAudioOutputProfile> = emptyMap(),
        globalPerfectBitPerfect: Boolean = false,
    ) = resolveAudiophileOutput(
        route = route,
        routeKindName = routeKindName,
        deviceName = deviceName,
        global = global,
        profiles = profiles,
        baselineDriver = OutputDriver.AAUDIO,
        baselineFloat32 = true,
        globalPerfectBitPerfect = globalPerfectBitPerfect,
        equalizerRequested = true,
        toneRequested = true,
        replayGainRequested = true,
        crossfadeRequested = true,
    )

}
