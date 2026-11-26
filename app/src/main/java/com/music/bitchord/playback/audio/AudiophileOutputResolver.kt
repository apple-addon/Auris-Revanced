package com.music.bitchord.playback.audio

import com.music.bitchord.ui.screens.audiophile.AudioOutputRoute
import com.music.bitchord.ui.screens.audiophile.AudioOutputTarget
import com.music.bitchord.ui.screens.audiophile.AudiophileConflictPolicy
import com.music.bitchord.ui.screens.audiophile.AudiophileSettings
import com.music.bitchord.ui.screens.audiophile.EffectiveAudiophileOutput
import com.music.bitchord.ui.screens.audiophile.OutputDriver
import com.music.bitchord.ui.screens.audiophile.OutputProfile
import com.music.bitchord.ui.screens.audiophile.StoredAudioOutputProfile
import java.util.Locale

enum class AudiophileProfileSource {
    STOCK_DEFAULTS,
    ROUTE,
    NAMED_DEVICE,
}

/** One fully resolved route policy, including driver-only fields outside the conflict result. */
data class ResolvedAudiophileOutput(
    val target: AudioOutputTarget,
    val profile: OutputProfile,
    val effective: EffectiveAudiophileOutput,
    val source: AudiophileProfileSource,
)

/**
 * Resolves persisted output policy without changing stock behavior when no profile exists.
 * Named-device keys intentionally match AudioOutputsScreen: route kind plus normalized product name.
 */
fun resolveAudiophileOutput(
    route: AudioOutputRoute,
    routeKindName: String,
    deviceName: String,
    global: AudiophileSettings,
    profiles: Map<String, StoredAudioOutputProfile>,
    baselineDriver: OutputDriver,
    baselineFloat32: Boolean,
    globalPerfectBitPerfect: Boolean,
    equalizerRequested: Boolean,
    toneRequested: Boolean,
    replayGainRequested: Boolean,
    crossfadeRequested: Boolean,
): ResolvedAudiophileOutput {
    val routeTarget = AudioOutputTarget.route(route)
    val normalizedName = deviceName.trim()
    val namedTarget = if (normalizedName.isEmpty()) {
        null
    } else {
        AudioOutputTarget(
            route = route,
            deviceKey = "${routeKindName.uppercase(Locale.ROOT)}:${normalizedName.lowercase(Locale.ROOT)}",
            deviceName = normalizedName,
        ).normalized()
    }
    val storedProfiles = profiles.values
    val named = namedTarget?.let { target ->
        storedProfiles.firstOrNull { it.target.normalized() == target }
    }
    val routeProfile = storedProfiles.firstOrNull { it.target.normalized() == routeTarget }
    val stored = named ?: routeProfile
    val target = stored?.target ?: routeTarget
    val profile = stored?.profile ?: OutputProfile(
        driver = baselineDriver,
        float32 = baselineFloat32,
    )
    val source = when {
        named != null -> AudiophileProfileSource.NAMED_DEVICE
        routeProfile != null -> AudiophileProfileSource.ROUTE
        else -> AudiophileProfileSource.STOCK_DEFAULTS
    }
    return ResolvedAudiophileOutput(
        target = target,
        profile = profile.normalizedFor(target.route),
        effective = AudiophileConflictPolicy.resolve(
            global = global,
            target = target,
            profile = profile,
            globalPerfectBitPerfect = globalPerfectBitPerfect,
            equalizerRequested = equalizerRequested,
            toneRequested = toneRequested,
            replayGainRequested = replayGainRequested,
            crossfadeRequested = crossfadeRequested,
        ),
        source = source,
    )
}
