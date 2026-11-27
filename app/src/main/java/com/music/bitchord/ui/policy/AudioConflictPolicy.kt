package com.music.bitchord.ui.policy

import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.drivers.OutputDriver

/**
 * The settings involved in audio-path exclusion decisions.
 *
 * This is deliberately a snapshot rather than a reference to AppSettings. A
 * request, its confirmation and the resulting transaction therefore all see
 * the same inputs, and a host can commit [AudioPolicyTransaction.after] as one
 * synchronized settings update.
 */
data class AudioPolicySettings(
    val bitPerfect: Boolean = false,
    val qualityOptions: Boolean = false,
    val referenceEngineMode: ReferenceEngineMode = ReferenceEngineMode.POWERAMP,
    val outputDriver: OutputDriver = OutputDriver.AUDIO_TRACK,
) {
    /**
     * Whether reference DSP can actually process the current path.
     *
     * Quality Options being switched on is not enough: Bit-perfect and the
     * userspace USB driver both bypass that DSP. Treating the preference alone
     * as active would show a conflict where no mutual exclusion exists.
     */
    val qualityDspActive: Boolean
        get() = qualityOptions &&
            referenceEngineMode != ReferenceEngineMode.BIT_PERFECT &&
            !directPcmOwnsSamples

    /**
     * Output modes that make the app bypass DSP or hand the endpoint to a
     * non-mixed path. They own the samples for as long as they are selected.
     */
    val directPcmOwnsSamples: Boolean
        get() = bitPerfect ||
            outputDriver == OutputDriver.USB_EXCLUSIVE ||
            outputDriver == OutputDriver.USB_SYSTEM_BIT_PERFECT

    /** The sole userspace/native USB owner, or null when neither owns it. */
    val exclusiveUsbSink: ExclusiveUsbSink?
        get() = when {
            outputDriver == OutputDriver.USB_EXCLUSIVE || bitPerfect ->
                ExclusiveUsbSink.AURIS_USBDEVFS
            qualityOptions &&
                (referenceEngineMode == ReferenceEngineMode.HIBY ||
                    referenceEngineMode == ReferenceEngineMode.HYBRID) &&
                outputDriver == OutputDriver.AUDIO_TRACK ->
                ExclusiveUsbSink.HIBY_NATIVE
            else -> null
        }
}

/** USB paths which cannot own the same endpoint at once. */
enum class ExclusiveUsbSink(val label: String) {
    AURIS_USBDEVFS("Auris USB Exclusive"),
    HIBY_NATIVE("HiBy native USB"),
}

/** A user request before any conflict resolution is applied. */
sealed interface AudioPolicyChange {
    data class SetBitPerfect(val enabled: Boolean) : AudioPolicyChange
    data class SetQualityOptions(val enabled: Boolean) : AudioPolicyChange
    data class SetReferenceEngineMode(val mode: ReferenceEngineMode) : AudioPolicyChange
    data class SetOutputDriver(val driver: OutputDriver) : AudioPolicyChange
}

enum class AudioConflictKind {
    BIT_PERFECT_VS_QUALITY_DSP,
    COMPETING_EXCLUSIVE_USB_SINKS,
}

/** A pending request. No setting has changed while this exists. */
data class PendingAudioConflict(
    val kind: AudioConflictKind,
    val request: AudioPolicyChange,
    val title: String,
    val message: String,
    val confirmLabel: String,
)

data class AudioPolicyUiState(
    val settings: AudioPolicySettings = AudioPolicySettings(),
    val pendingConflict: PendingAudioConflict? = null,
)

sealed interface AudioPolicyIntent {
    data class Request(val change: AudioPolicyChange) : AudioPolicyIntent
    data object ConfirmConflict : AudioPolicyIntent
    data object CancelConflict : AudioPolicyIntent

    /** Reconciles an external settings snapshot without losing a pending request. */
    data class SyncSettings(val settings: AudioPolicySettings) : AudioPolicyIntent
}

/** Ordered writes for hosts which persist through the existing setting setters. */
sealed interface AudioPolicyOperation {
    data class SetBitPerfect(val enabled: Boolean) : AudioPolicyOperation
    data class SetQualityOptions(val enabled: Boolean) : AudioPolicyOperation
    data class SetReferenceEngineMode(val mode: ReferenceEngineMode) : AudioPolicyOperation
    data class SetOutputDriver(val driver: OutputDriver) : AudioPolicyOperation
}

/**
 * One atomic decision, plus a safe ordering for the existing setters.
 *
 * [before] and [after] are complete snapshots, not a mutable settings alias.
 * A host should verify that its current snapshot is still [before], then hold
 * its settings/output lock while applying [operations]. In particular, a
 * Bit-perfect confirmation writes Quality Options OFF before enabling the
 * requested path. No operation is emitted for an unrelated setting.
 */
data class AudioPolicyTransaction(
    val before: AudioPolicySettings,
    val after: AudioPolicySettings,
    val request: AudioPolicyChange,
    val resolvedConflict: AudioConflictKind?,
    val operations: List<AudioPolicyOperation>,
)

data class AudioPolicyReduction(
    val state: AudioPolicyUiState,
    val transaction: AudioPolicyTransaction? = null,
)

/** Central, side-effect-free reducer for every audio-path conflict dialog. */
object AudioConflictPolicy {
    fun reduce(state: AudioPolicyUiState, intent: AudioPolicyIntent): AudioPolicyReduction =
        when (intent) {
            is AudioPolicyIntent.SyncSettings -> AudioPolicyReduction(
                state.copy(settings = intent.settings),
            )
            AudioPolicyIntent.CancelConflict -> {
                // In particular, do not call apply() on the pending request.
                AudioPolicyReduction(state.copy(pendingConflict = null))
            }
            AudioPolicyIntent.ConfirmConflict -> confirm(state)
            is AudioPolicyIntent.Request -> request(state, intent.change)
        }

    private fun request(state: AudioPolicyUiState, change: AudioPolicyChange): AudioPolicyReduction {
        val before = state.settings
        val requested = change.applyTo(before)
        if (requested == before) {
            return AudioPolicyReduction(state.copy(pendingConflict = null))
        }
        val kind = conflictFor(before, change)
        if (kind != null) {
            return AudioPolicyReduction(
                state.copy(
                    // Settings are untouched until ConfirmConflict.
                    pendingConflict = pendingConflict(kind, before, change),
                ),
            )
        }
        return committed(state, before, requested, change, resolvedConflict = null)
    }

    private fun confirm(state: AudioPolicyUiState): AudioPolicyReduction {
        val pending = state.pendingConflict ?: return AudioPolicyReduction(state)
        val before = state.settings
        // Re-evaluate against the latest synchronized snapshot. A route or
        // settings change while the dialog was open must not be overwritten by
        // a stale copy captured when the request was first made.
        val requested = pending.request.applyTo(before)
        val currentConflict = conflictFor(before, pending.request)
        if (requested == before) {
            return AudioPolicyReduction(state.copy(pendingConflict = null))
        }
        val resolved = if (currentConflict == null) {
            requested
        } else {
            resolve(before, pending.request, currentConflict)
        }
        return committed(
            state = state,
            before = before,
            after = resolved,
            request = pending.request,
            resolvedConflict = currentConflict,
        )
    }

    private fun committed(
        state: AudioPolicyUiState,
        before: AudioPolicySettings,
        after: AudioPolicySettings,
        request: AudioPolicyChange,
        resolvedConflict: AudioConflictKind?,
    ): AudioPolicyReduction {
        val transaction = AudioPolicyTransaction(
            before = before,
            after = after,
            request = request,
            resolvedConflict = resolvedConflict,
            operations = orderedOperations(before, after, request),
        )
        return AudioPolicyReduction(
            state = AudioPolicyUiState(settings = after),
            transaction = transaction,
        )
    }

    private fun conflictFor(
        before: AudioPolicySettings,
        change: AudioPolicyChange,
    ): AudioConflictKind? {
        val after = change.applyTo(before)
        val enablesAurisBitPerfect = change is AudioPolicyChange.SetBitPerfect && change.enabled
        val enablesDirectDriver = change is AudioPolicyChange.SetOutputDriver &&
            change.driver in DIRECT_PCM_DRIVERS
        val enablesAurisUsb = change is AudioPolicyChange.SetOutputDriver &&
            change.driver == OutputDriver.USB_EXCLUSIVE
        val activatesDsp = after.qualityOptions &&
            after.referenceEngineMode != ReferenceEngineMode.BIT_PERFECT &&
            when (change) {
                is AudioPolicyChange.SetQualityOptions -> change.enabled
                is AudioPolicyChange.SetReferenceEngineMode -> true
                else -> false
            }
        val activatesHiby = activatesDsp &&
            (after.referenceEngineMode == ReferenceEngineMode.HIBY ||
                after.referenceEngineMode == ReferenceEngineMode.HYBRID)

        return when {
            // Enabling either direct path must first retire a genuinely active
            // reference DSP graph. The HiBy case is also a USB ownership change.
            (enablesAurisBitPerfect || enablesDirectDriver) && before.qualityDspActive ->
                if (enablesAurisUsb && before.exclusiveUsbSink == ExclusiveUsbSink.HIBY_NATIVE) {
                    AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS
                } else {
                    AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP
                }

            enablesAurisUsb && before.exclusiveUsbSink == ExclusiveUsbSink.HIBY_NATIVE ->
                AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS

            // Selecting HiBy while userspace USB owns the endpoint is a USB
            // ownership transfer, not merely a DSP enable.
            activatesHiby && before.outputDriver == OutputDriver.USB_EXCLUSIVE ->
                AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS

            // The reverse request is real too: enabling DSP while any other
            // direct path owns the samples cannot leave the switch inert.
            activatesDsp && before.directPcmOwnsSamples ->
                AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP

            else -> null
        }
    }

    private fun resolve(
        before: AudioPolicySettings,
        request: AudioPolicyChange,
        kind: AudioConflictKind,
    ): AudioPolicySettings {
        val requested = request.applyTo(before)
        return when (kind) {
            AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP -> when (request) {
                is AudioPolicyChange.SetBitPerfect,
                is AudioPolicyChange.SetOutputDriver,
                -> requested.copy(qualityOptions = false)
                // Enabling DSP retires every direct owner, not just the one
                // boolean: a selected USB direct sound is still direct.
                else -> requested.copy(
                    bitPerfect = false,
                    outputDriver = OutputDriver.AUDIO_TRACK,
                )
            }
            AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS -> when (request) {
                is AudioPolicyChange.SetBitPerfect,
                is AudioPolicyChange.SetOutputDriver,
                -> requested.copy(qualityOptions = false)
                else -> requested.copy(
                    bitPerfect = false,
                    outputDriver = OutputDriver.AUDIO_TRACK,
                )
            }
        }
    }

    private fun orderedOperations(
        before: AudioPolicySettings,
        after: AudioPolicySettings,
        request: AudioPolicyChange,
    ): List<AudioPolicyOperation> {
        val operations = mutableListOf<AudioPolicyOperation>()

        // Release the old path before enabling its replacement. These are
        // deliberately explicit rather than a map iteration: ordering is part
        // of the conflict contract.
        if (before.qualityOptions && !after.qualityOptions) {
            operations += AudioPolicyOperation.SetQualityOptions(false)
        }
        if (before.bitPerfect && !after.bitPerfect) {
            operations += AudioPolicyOperation.SetBitPerfect(false)
        }
        if (before.outputDriver in DIRECT_PCM_DRIVERS &&
            after.outputDriver != before.outputDriver
        ) {
            operations += AudioPolicyOperation.SetOutputDriver(after.outputDriver)
        }

        val requestedOperation = request.toOperation()
        if (requestedOperation !in operations) operations += requestedOperation

        // A resolution can require one additional write (for example, moving
        // from USB Exclusive to AudioTrack before selecting HiBy). Preserve it
        // without touching any unrelated preference.
        if (before.qualityOptions != after.qualityOptions) {
            operations += AudioPolicyOperation.SetQualityOptions(after.qualityOptions)
        }
        if (before.bitPerfect != after.bitPerfect) {
            operations += AudioPolicyOperation.SetBitPerfect(after.bitPerfect)
        }
        if (before.referenceEngineMode != after.referenceEngineMode) {
            operations += AudioPolicyOperation.SetReferenceEngineMode(after.referenceEngineMode)
        }
        if (before.outputDriver != after.outputDriver) {
            operations += AudioPolicyOperation.SetOutputDriver(after.outputDriver)
        }
        return operations.distinct()
    }

    private fun pendingConflict(
        kind: AudioConflictKind,
        before: AudioPolicySettings,
        request: AudioPolicyChange,
    ): PendingAudioConflict {
        val requestedLabel = request.label()
        return when (kind) {
            AudioConflictKind.BIT_PERFECT_VS_QUALITY_DSP -> {
                val enablingDirectPath = request is AudioPolicyChange.SetBitPerfect ||
                    request is AudioPolicyChange.SetOutputDriver
                val directOwner = when {
                    before.bitPerfect &&
                        before.outputDriver in DIRECT_PCM_DRIVERS -> "Bit-perfect and USB direct output"
                    before.bitPerfect -> "Bit-perfect"
                    before.outputDriver == OutputDriver.USB_SYSTEM_BIT_PERFECT ->
                        "USB System Bit-Perfect"
                    before.outputDriver == OutputDriver.USB_EXCLUSIVE ->
                        "USB Exclusive"
                    else -> "The direct output"
                }
                PendingAudioConflict(
                    kind = kind,
                    request = request,
                    title = if (enablingDirectPath) "Enable $requestedLabel?" else "Enable Quality Options DSP?",
                    message = if (enablingDirectPath) {
                        "$requestedLabel and active Quality Options DSP cannot process the same samples. " +
                            "Quality Options will turn off before $requestedLabel is enabled."
                    } else {
                        "$directOwner and active Quality Options DSP cannot process the same samples. " +
                            "$directOwner will turn off before Quality Options is applied."
                    },
                    confirmLabel = if (enablingDirectPath) {
                        "Turn off DSP & enable"
                    } else {
                        "Turn off direct output"
                    },
                )
            }
            AudioConflictKind.COMPETING_EXCLUSIVE_USB_SINKS -> {
                val current = before.exclusiveUsbSink?.label ?: "The current USB sink"
                val enablingAuris = request is AudioPolicyChange.SetBitPerfect ||
                    request is AudioPolicyChange.SetOutputDriver
                PendingAudioConflict(
                    kind = kind,
                    request = request,
                    title = "Switch exclusive USB output?",
                    message = if (enablingAuris) {
                        "$current and Auris USB Exclusive cannot own the same USB device. " +
                            "Quality Options will turn off before the Auris sink is enabled."
                    } else {
                        "$current and HiBy native USB cannot own the same USB device. " +
                            "The Auris sink will be released before HiBy native output is enabled."
                    },
                    confirmLabel = "Switch USB output",
                )
            }
        }
    }
}

private val DIRECT_PCM_DRIVERS = setOf(
    OutputDriver.USB_EXCLUSIVE,
    OutputDriver.USB_SYSTEM_BIT_PERFECT,
)

fun AudioPolicyChange.applyTo(settings: AudioPolicySettings): AudioPolicySettings = when (this) {
    is AudioPolicyChange.SetBitPerfect -> settings.copy(bitPerfect = enabled)
    is AudioPolicyChange.SetQualityOptions -> settings.copy(qualityOptions = enabled)
    is AudioPolicyChange.SetReferenceEngineMode -> settings.copy(referenceEngineMode = mode)
    is AudioPolicyChange.SetOutputDriver -> settings.copy(outputDriver = driver)
}

private fun AudioPolicyChange.toOperation(): AudioPolicyOperation = when (this) {
    is AudioPolicyChange.SetBitPerfect -> AudioPolicyOperation.SetBitPerfect(enabled)
    is AudioPolicyChange.SetQualityOptions -> AudioPolicyOperation.SetQualityOptions(enabled)
    is AudioPolicyChange.SetReferenceEngineMode -> AudioPolicyOperation.SetReferenceEngineMode(mode)
    is AudioPolicyChange.SetOutputDriver -> AudioPolicyOperation.SetOutputDriver(driver)
}

private fun AudioPolicyChange.label(): String = when (this) {
    is AudioPolicyChange.SetBitPerfect -> if (enabled) "Bit-perfect" else "Bit-perfect off"
    is AudioPolicyChange.SetQualityOptions -> if (enabled) "Quality Options" else "Quality Options off"
    is AudioPolicyChange.SetReferenceEngineMode -> mode.label
    is AudioPolicyChange.SetOutputDriver -> driver.label
}
