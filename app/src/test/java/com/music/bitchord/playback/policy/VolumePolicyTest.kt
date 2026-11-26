package com.music.bitchord.playback.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAbsoluteVolumePolicyTest {
    @Test
    fun a2dpSupportProbeDetectsEffectiveAbsoluteVolume() {
        assertEquals(
            AbsoluteVolumeStatus.ENABLED,
            BluetoothAbsoluteVolumePolicy.detect(
                OutputRoute.BLUETOOTH_A2DP,
                AbsoluteVolumeProbe(supported = true),
            ),
        )
        assertEquals(
            AbsoluteVolumeStatus.DISABLED,
            BluetoothAbsoluteVolumePolicy.detect(
                OutputRoute.BLUETOOTH_A2DP,
                AbsoluteVolumeProbe(supported = false),
            ),
        )
    }

    @Test
    fun explicitEnabledProbeOverridesSupportHint() {
        assertEquals(
            AbsoluteVolumeStatus.DISABLED,
            BluetoothAbsoluteVolumePolicy.detect(
                OutputRoute.BLUETOOTH_A2DP,
                AbsoluteVolumeProbe(supported = true, enabled = false),
            ),
        )
        assertEquals(
            AbsoluteVolumeStatus.ENABLED,
            BluetoothAbsoluteVolumePolicy.detect(
                OutputRoute.BLUETOOTH_A2DP,
                AbsoluteVolumeProbe(supported = false, enabled = true),
            ),
        )
    }

    @Test
    fun failedDetectionIsUnknownNotDisabled() {
        assertEquals(
            AbsoluteVolumeStatus.UNKNOWN,
            BluetoothAbsoluteVolumePolicy.detect(OutputRoute.BLUETOOTH_A2DP, AbsoluteVolumeProbe()),
        )
    }

    @Test
    fun nonA2dpRoutesAreNotMislabeled() {
        for (route in listOf(OutputRoute.NON_BLUETOOTH, OutputRoute.BLUETOOTH_LE_AUDIO)) {
            assertEquals(
                AbsoluteVolumeStatus.NOT_APPLICABLE,
                BluetoothAbsoluteVolumePolicy.detect(route, AbsoluteVolumeProbe(supported = true)),
            )
        }
    }

    @Test
    fun reflectorReadsAospHiddenMethodWithoutAndroidDependency() {
        val probe = AbsoluteVolumeReflector.probe(A2dpSupportProbe(true))

        assertEquals(true, probe.supported)
        assertNull(probe.enabled)
        assertTrue(probe.reflectionAttempted)
    }

    @Test
    fun reflectorReadsOemEnabledAlias() {
        val probe = AbsoluteVolumeReflector.probe(OemEnabledProbe(false))

        assertNull(probe.supported)
        assertEquals(false, probe.enabled)
        assertTrue(probe.reflectionAttempted)
    }

    @Test
    fun reflectorFallsBackAcrossTargets() {
        val probe = AbsoluteVolumeReflector.probe(WrongReturnProbe(), A2dpSupportProbe(false))

        assertEquals(false, probe.supported)
        assertTrue(probe.reflectionAttempted)
    }

    @Test
    fun deniedReflectionDegradesToUnknown() {
        val probe = AbsoluteVolumeReflector.probe(ThrowingProbe())

        assertNull(probe.supported)
        assertNull(probe.enabled)
        assertTrue(probe.reflectionAttempted)
        assertEquals(
            AbsoluteVolumeStatus.UNKNOWN,
            BluetoothAbsoluteVolumePolicy.detect(OutputRoute.BLUETOOTH_A2DP, probe),
        )
    }

    @Test
    fun missingMethodsAreNotAClaimThatAbsoluteVolumeIsOff() {
        val probe = AbsoluteVolumeReflector.probe(Any(), null)

        assertNull(probe.supported)
        assertNull(probe.enabled)
        assertFalse(probe.reflectionAttempted)
    }
}

class DvcVolumePolicyTest {
    @Test
    fun activeDvcOwnsUserVolumeAndNeedsNoNoDvcTrim() {
        val plan = DvcVolumePolicy.plan(OutputRoute.NON_BLUETOOTH)

        assertEquals(DvcMode.ACTIVE, plan.dvcMode)
        assertEquals(VolumeOwner.PLAYER_DVC, plan.volumeOwner)
        assertTrue(plan.dvcActive)
        assertEquals(0.0, plan.headroomGainDb, 0.0)
        assertEquals(1.0, plan.headroomGain, 0.0)
        assertFalse(plan.applyStreamVolume)
        assertTrue(plan.applySoftwareUserVolume)
        assertFalse(plan.applyRemoteAbsoluteVolume)
    }

    @Test
    fun disabledDvcUsesDefaultMinusSixDbHeadroom() {
        val plan = DvcVolumePolicy.plan(
            OutputRoute.NON_BLUETOOTH,
            settings = DvcSettings(requested = false),
        )

        assertEquals(DvcMode.DISABLED, plan.dvcMode)
        assertEquals(VolumeOwner.SYSTEM_STREAM, plan.volumeOwner)
        assertEquals(-6.0, plan.headroomGainDb, 0.0)
        assertEquals(0.5011872336, plan.headroomGain, 1e-9)
        assertEquals(plan.headroomGain.toFloat(), plan.headroomSampleGain)
        assertTrue(plan.applyStreamVolume)
        assertFalse(plan.applySoftwareUserVolume)
    }

    @Test
    fun unsupportedDvcAlsoUsesSafeSystemOwnership() {
        val plan = DvcVolumePolicy.plan(
            OutputRoute.NON_BLUETOOTH,
            settings = DvcSettings(supported = false),
        )

        assertEquals(DvcMode.UNSUPPORTED, plan.dvcMode)
        assertEquals(-6.0, plan.headroomGainDb, 0.0)
        assertTrue(plan.applyStreamVolume)
    }

    @Test
    fun bluetoothAbsoluteVolumeBlocksDvcAndOwnsRemoteVolume() {
        val plan = DvcVolumePolicy.plan(
            OutputRoute.BLUETOOTH_A2DP,
            AbsoluteVolumeProbe(supported = true),
        )

        assertEquals(AbsoluteVolumeStatus.ENABLED, plan.absoluteVolume)
        assertEquals(DvcMode.BLOCKED_BY_ABSOLUTE_VOLUME, plan.dvcMode)
        assertEquals(VolumeOwner.BLUETOOTH_ABSOLUTE, plan.volumeOwner)
        assertEquals(-6.0, plan.headroomGainDb, 0.0)
        assertTrue(plan.applyStreamVolume)
        assertFalse(plan.applySoftwareUserVolume)
        assertTrue(plan.applyRemoteAbsoluteVolume)
    }

    @Test
    fun disabledAbsoluteVolumeAllowsDvcOnA2dp() {
        val plan = DvcVolumePolicy.plan(
            OutputRoute.BLUETOOTH_A2DP,
            AbsoluteVolumeProbe(supported = false),
        )

        assertEquals(DvcMode.ACTIVE, plan.dvcMode)
        assertEquals(VolumeOwner.PLAYER_DVC, plan.volumeOwner)
        assertEquals(0.0, plan.headroomGainDb, 0.0)
        assertTrue(plan.applySoftwareUserVolume)
        assertFalse(plan.applyStreamVolume)
    }

    @Test
    fun unknownA2dpStateConservativelyAvoidsDoubleAttenuation() {
        val plan = DvcVolumePolicy.plan(OutputRoute.BLUETOOTH_A2DP)

        assertEquals(AbsoluteVolumeStatus.UNKNOWN, plan.absoluteVolume)
        assertEquals(DvcMode.UNVERIFIED_ABSOLUTE_VOLUME, plan.dvcMode)
        assertEquals(VolumeOwner.SYSTEM_STREAM, plan.volumeOwner)
        assertTrue(plan.applyStreamVolume)
        assertFalse(plan.applySoftwareUserVolume)
        assertFalse(plan.applyRemoteAbsoluteVolume)
    }

    @Test
    fun leAudioDoesNotTriggerA2dpAbsoluteVolumeBlock() {
        val plan = DvcVolumePolicy.plan(OutputRoute.BLUETOOTH_LE_AUDIO)

        assertEquals(AbsoluteVolumeStatus.NOT_APPLICABLE, plan.absoluteVolume)
        assertEquals(DvcMode.ACTIVE, plan.dvcMode)
    }

    @Test
    fun customNoDvcHeadroomUsesTwentyLogScaling() {
        val plan = DvcVolumePolicy.plan(
            OutputRoute.NON_BLUETOOTH,
            settings = DvcSettings(requested = false, noDvcHeadroomGainDb = -12.0),
        )

        assertEquals(-12.0, plan.headroomGainDb, 0.0)
        assertEquals(0.2511886432, plan.headroomGain, 1e-9)
    }

    @Test
    fun absoluteRouteStillHasSingleOwnerWhenDvcRequestIsOff() {
        val plan = DvcVolumePolicy.plan(
            OutputRoute.BLUETOOTH_A2DP,
            AbsoluteVolumeProbe(enabled = true),
            DvcSettings(requested = false),
        )

        assertEquals(DvcMode.DISABLED, plan.dvcMode)
        assertEquals(VolumeOwner.BLUETOOTH_ABSOLUTE, plan.volumeOwner)
        assertTrue(plan.applyRemoteAbsoluteVolume)
        assertFalse(plan.applySoftwareUserVolume)
    }

    @Test(expected = IllegalArgumentException::class)
    fun positiveNoDvcGainIsNotHeadroom() {
        DvcSettings(noDvcHeadroomGainDb = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteNoDvcGainIsRejected() {
        DvcSettings(noDvcHeadroomGainDb = Double.NaN)
    }
}

class A2dpSupportProbe(private val value: Boolean) {
    fun isAvrcpAbsoluteVolumeSupported(): Boolean = value
}

class OemEnabledProbe(private val value: Boolean) {
    fun isAbsoluteVolumeEnabled(): Boolean = value
}

class WrongReturnProbe {
    fun isAvrcpAbsoluteVolumeSupported(): String = "true"
}

class ThrowingProbe {
    fun isAvrcpAbsoluteVolumeSupported(): Boolean = throw SecurityException("denied")
    fun isAbsoluteVolumeEnabled(): Boolean = throw SecurityException("denied")
}
