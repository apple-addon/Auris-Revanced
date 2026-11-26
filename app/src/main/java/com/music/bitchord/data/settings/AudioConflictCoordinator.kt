package com.music.bitchord.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The only native-setting conflict that requires user confirmation. */
enum class PendingAudioConflict {
    NATIVE_BIT_PERFECT,
    QUALITY_OPTIONS,
}

data class QualityAudioState(
    val qualityOptions: Boolean,
    val configuredQualityDsp: Boolean,
    val nativeBitPerfect: Boolean,
)

/** Pure conflict rules, kept separate from Android preferences for JVM tests. */
object QualityOptionsPolicy {
    fun needsBitPerfectConfirmation(state: QualityAudioState): Boolean =
        state.qualityOptions && state.configuredQualityDsp

    fun needsQualityConfirmation(state: QualityAudioState): Boolean =
        state.nativeBitPerfect && state.configuredQualityDsp
}

/**
 * Central entry point for every Quality Options / native Bit-Perfect toggle.
 * Requests never mutate state when a dialog is required. Cancel therefore
 * restores nothing—it simply drops the request. Confirm performs the ordered,
 * atomic preference transaction in [AppSettings].
 */
object AudioConflictCoordinator {
    private val _pending = MutableStateFlow<PendingAudioConflict?>(null)
    val pending: StateFlow<PendingAudioConflict?> = _pending.asStateFlow()

    @Synchronized
    fun requestNativeBitPerfect(enabled: Boolean) {
        if (!enabled) {
            AppSettings.setBitPerfect(false)
            return
        }
        val state = currentState()
        if (QualityOptionsPolicy.needsBitPerfectConfirmation(state)) {
            _pending.value = PendingAudioConflict.NATIVE_BIT_PERFECT
        } else {
            AppSettings.setBitPerfect(true)
        }
    }

    @Synchronized
    fun requestQualityOptions(enabled: Boolean) {
        if (!enabled) {
            AppSettings.setQualityOptions(false)
            return
        }
        val state = currentState()
        if (QualityOptionsPolicy.needsQualityConfirmation(state)) {
            _pending.value = PendingAudioConflict.QUALITY_OPTIONS
        } else {
            AppSettings.setQualityOptions(true)
        }
    }

    @Synchronized
    fun cancel() {
        _pending.value = null
    }

    @Synchronized
    fun confirm() {
        when (_pending.value) {
            PendingAudioConflict.NATIVE_BIT_PERFECT,
            PendingAudioConflict.QUALITY_OPTIONS,
            -> AppSettings.confirmNativeBitPerfectConflict()
            null -> return
        }
        _pending.value = null
    }

    private fun currentState() = QualityAudioState(
        qualityOptions = AppSettings.qualityOptions.value,
        configuredQualityDsp = AppSettings.hasConfiguredQualityDsp(),
        nativeBitPerfect = AppSettings.bitPerfect.value,
    )
}
