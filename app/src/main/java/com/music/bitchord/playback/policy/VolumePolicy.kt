package com.music.bitchord.playback.policy

import kotlin.math.pow

enum class OutputRoute {
    NON_BLUETOOTH,
    BLUETOOTH_A2DP,
    BLUETOOTH_LE_AUDIO,
}

enum class AbsoluteVolumeStatus {
    NOT_APPLICABLE,
    ENABLED,
    DISABLED,
    UNKNOWN,
}

/** Nullable values mean that the platform/OEM did not expose a trustworthy answer. */
data class AbsoluteVolumeProbe(
    val supported: Boolean? = null,
    val enabled: Boolean? = null,
    val reflectionAttempted: Boolean = false,
)

/**
 * Small Java-reflection adapter for hidden Bluetooth APIs, without an Android class dependency.
 * Pass the BluetoothA2dp proxy first, followed by any OEM adapter/device objects.
 * AOSP's isAvrcpAbsoluteVolumeSupported is the preferred probe. No hidden-API access flags
 * are changed and missing/denied methods degrade to UNKNOWN rather than crashing playback.
 */
object AbsoluteVolumeReflector {
    private val supportMethods = listOf(
        "isAvrcpAbsoluteVolumeSupported",
        "isAbsoluteVolumeSupported",
        "isBluetoothAbsoluteVolumeSupported",
    )
    private val enabledMethods = listOf(
        "isAvrcpAbsoluteVolumeEnabled",
        "isAbsoluteVolumeEnabled",
        "isBluetoothAbsoluteVolumeEnabled",
    )

    @JvmStatic
    fun probe(vararg targets: Any?): AbsoluteVolumeProbe {
        var supported: Boolean? = null
        var enabled: Boolean? = null
        var attempted = false
        for (target in targets.filterNotNull()) {
            if (supported == null) {
                val read = readBoolean(target, supportMethods)
                attempted = attempted || read.attempted
                supported = read.value
            }
            if (enabled == null) {
                val read = readBoolean(target, enabledMethods)
                attempted = attempted || read.attempted
                enabled = read.value
            }
            if (supported != null && enabled != null) break
        }
        return AbsoluteVolumeProbe(supported, enabled, attempted)
    }

    private fun readBoolean(target: Any, names: List<String>): Read {
        var attempted = false
        for (name in names) {
            try {
                val method = target.javaClass.getMethod(name)
                if (method.parameterTypes.isNotEmpty()) continue
                attempted = true
                val result = method.invoke(target)
                if (result is Boolean) return Read(result, attempted)
            } catch (_: ReflectiveOperationException) {
                // Try the next API spelling/target. Hidden API denial is an unknown, not false.
            } catch (_: SecurityException) {
                // Same policy for OEM permission checks.
            } catch (_: LinkageError) {
                // A stale OEM proxy can lack a method's return type.
            }
        }
        return Read(null, attempted)
    }

    private data class Read(val value: Boolean?, val attempted: Boolean)
}

object BluetoothAbsoluteVolumePolicy {
    /**
     * On A2DP, AOSP's support query reflects the effective remote/developer-option state,
     * so it is sufficient when no separate enabled query exists. LE Audio uses a different
     * volume API and must not be mislabeled as A2DP absolute volume.
     */
    @JvmStatic
    fun detect(route: OutputRoute, probe: AbsoluteVolumeProbe): AbsoluteVolumeStatus = when (route) {
        OutputRoute.NON_BLUETOOTH, OutputRoute.BLUETOOTH_LE_AUDIO -> AbsoluteVolumeStatus.NOT_APPLICABLE
        OutputRoute.BLUETOOTH_A2DP -> when {
            probe.enabled != null -> if (probe.enabled) AbsoluteVolumeStatus.ENABLED else AbsoluteVolumeStatus.DISABLED
            probe.supported != null -> if (probe.supported) AbsoluteVolumeStatus.ENABLED else AbsoluteVolumeStatus.DISABLED
            else -> AbsoluteVolumeStatus.UNKNOWN
        }
    }
}

data class DvcSettings(
    val requested: Boolean = true,
    val supported: Boolean = true,
    /** Fixed attenuation used only when DVC is not active. Poweramp-compatible default: -6 dB. */
    val noDvcHeadroomGainDb: Double = -6.0,
) {
    init {
        require(noDvcHeadroomGainDb.isFinite() && noDvcHeadroomGainDb in -100.0..0.0) {
            "noDvcHeadroomGainDb must be finite and in -100..0 dB"
        }
    }
}

enum class DvcMode {
    ACTIVE,
    DISABLED,
    UNSUPPORTED,
    BLOCKED_BY_ABSOLUTE_VOLUME,
    UNVERIFIED_ABSOLUTE_VOLUME,
}

enum class VolumeOwner {
    PLAYER_DVC,
    SYSTEM_STREAM,
    BLUETOOTH_ABSOLUTE,
}

/** Commands/scalars for the host, not an output-driver implementation. */
data class VolumePolicyPlan(
    val absoluteVolume: AbsoluteVolumeStatus,
    val dvcMode: DvcMode,
    val volumeOwner: VolumeOwner,
    val headroomGainDb: Double,
    val headroomGain: Double,
    val applyStreamVolume: Boolean,
    val applySoftwareUserVolume: Boolean,
    val applyRemoteAbsoluteVolume: Boolean,
) {
    val dvcActive: Boolean get() = dvcMode == DvcMode.ACTIVE
    val headroomSampleGain: Float get() = headroomGain.toFloat()
}

/** DVC, no-DVC headroom and Bluetooth volume ownership policy. */
object DvcVolumePolicy {
    /**
     * Unknown A2DP state conservatively keeps system volume in charge. Enabling DVC on an
     * absolute-volume route without knowing that state can attenuate the signal twice.
     */
    @JvmStatic
    @JvmOverloads
    fun plan(
        route: OutputRoute,
        probe: AbsoluteVolumeProbe = AbsoluteVolumeProbe(),
        settings: DvcSettings = DvcSettings(),
    ): VolumePolicyPlan {
        val absolute = BluetoothAbsoluteVolumePolicy.detect(route, probe)
        val mode = when {
            !settings.requested -> DvcMode.DISABLED
            !settings.supported -> DvcMode.UNSUPPORTED
            absolute == AbsoluteVolumeStatus.ENABLED -> DvcMode.BLOCKED_BY_ABSOLUTE_VOLUME
            route == OutputRoute.BLUETOOTH_A2DP && absolute == AbsoluteVolumeStatus.UNKNOWN ->
                DvcMode.UNVERIFIED_ABSOLUTE_VOLUME
            else -> DvcMode.ACTIVE
        }
        val owner = when {
            mode == DvcMode.ACTIVE -> VolumeOwner.PLAYER_DVC
            absolute == AbsoluteVolumeStatus.ENABLED -> VolumeOwner.BLUETOOTH_ABSOLUTE
            else -> VolumeOwner.SYSTEM_STREAM
        }
        val headroomDb = if (mode == DvcMode.ACTIVE) 0.0 else settings.noDvcHeadroomGainDb
        return VolumePolicyPlan(
            absoluteVolume = absolute,
            dvcMode = mode,
            volumeOwner = owner,
            headroomGainDb = headroomDb,
            headroomGain = 10.0.pow(headroomDb / 20.0),
            applyStreamVolume = owner != VolumeOwner.PLAYER_DVC,
            applySoftwareUserVolume = owner == VolumeOwner.PLAYER_DVC,
            applyRemoteAbsoluteVolume = owner == VolumeOwner.BLUETOOTH_ABSOLUTE,
        )
    }
}
