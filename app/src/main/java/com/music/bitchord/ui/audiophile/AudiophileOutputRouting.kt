package com.music.bitchord.ui.audiophile

import com.music.bitchord.playback.audio.drivers.OutputDriver

/**
 * Maps the single canonical output driver to its derived route presentation.
 *
 * Chromecast is deliberately absent from the driver mapping: it is an independent Cast route,
 * not another local output backend. USB_SYSTEM_BIT_PERFECT shares the USB route family in the
 * hierarchy, but remains a distinct scalar value so the UI can report the actual Android system
 * path instead of claiming that the userspace USB-exclusive driver is active.
 */
object AudiophileOutputRouting {
    fun driverFor(routeId: AudioOutputRouteId): OutputDriver? = when (routeId) {
        AudioOutputRouteId.OPEN_SL_ES -> OutputDriver.OPENSL_ES
        AudioOutputRouteId.HI_RES -> OutputDriver.HI_RES
        AudioOutputRouteId.AAUDIO -> OutputDriver.AAUDIO
        AudioOutputRouteId.USB_EXCLUSIVE -> OutputDriver.USB_EXCLUSIVE
        AudioOutputRouteId.AUDIO_TRACK -> OutputDriver.AUDIO_TRACK
        AudioOutputRouteId.CHROMECAST -> null
    }

    fun routeFor(driver: OutputDriver): AudioOutputRouteId = when (driver) {
        OutputDriver.OPENSL_ES -> AudioOutputRouteId.OPEN_SL_ES
        OutputDriver.HI_RES -> AudioOutputRouteId.HI_RES
        OutputDriver.AAUDIO -> AudioOutputRouteId.AAUDIO
        OutputDriver.USB_EXCLUSIVE,
        OutputDriver.USB_SYSTEM_BIT_PERFECT,
        -> AudioOutputRouteId.USB_EXCLUSIVE
        OutputDriver.AUDIO_TRACK -> AudioOutputRouteId.AUDIO_TRACK
    }

    /**
     * Rebuilds all local route flags from [driver], including after a cancelled policy request.
     * Runtime ACTIVE markers on routes which are no longer selected are retired; independent
     * device configuration and the separate Cast route are left intact.
     */
    fun reconcile(
        state: AudiophileAudioState,
        driver: OutputDriver = state.outputDriver,
    ): AudiophileAudioState {
        val selectedRouteId = routeFor(driver)
        val selectionChanged = routeFor(state.outputDriver) != selectedRouteId
        return state.copy(
            outputDriver = driver,
            outputs = state.outputs.map { route ->
                if (route.id == AudioOutputRouteId.CHROMECAST) return@map route
                val selected = route.id == selectedRouteId
                val wasActive = route.status == OutputConnectionStatus.ACTIVE
                route.copy(
                    enabled = selected,
                    // Selection is a request, not playout evidence. A newly
                    // selected route is available at most; only runtime output
                    // telemetry may promote it to ACTIVE.
                    status = when {
                        selected && !route.available -> OutputConnectionStatus.UNSUPPORTED
                        selected && wasActive -> OutputConnectionStatus.ACTIVE
                        selected -> OutputConnectionStatus.AVAILABLE
                        wasActive && route.available -> OutputConnectionStatus.AVAILABLE
                        wasActive -> OutputConnectionStatus.UNSUPPORTED
                        else -> route.status
                    },
                    statusDetail = if (!selected && wasActive) "" else route.statusDetail,
                    profiles = route.profiles.map { profile ->
                        val retireActive = profile.connectionStatus == OutputConnectionStatus.ACTIVE &&
                            (!selected || selectionChanged)
                        if (!retireActive) {
                            profile
                        } else {
                            profile.copy(
                                connectionStatus = OutputConnectionStatus.AVAILABLE,
                                statusDetail = "",
                                actualSampleRateHz = null,
                                actualSampleFormat = null,
                            )
                        }
                    },
                )
            },
        )
    }
}
