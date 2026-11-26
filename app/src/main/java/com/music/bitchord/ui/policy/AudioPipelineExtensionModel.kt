package com.music.bitchord.ui.policy

import com.music.bitchord.data.settings.ReferenceEngineMode
import com.music.bitchord.playback.audio.AudioFidelity
import com.music.bitchord.playback.audio.drivers.OutputDriverTelemetry
import com.music.bitchord.playback.output.NativeOutputSharing
import com.music.bitchord.playback.output.NativeOutputTelemetry
import com.music.bitchord.playback.reference.NativeLoadState
import com.music.bitchord.playback.reference.NativeEngineStatus
import com.music.bitchord.playback.reference.ReferenceEngineSnapshot
import com.music.bitchord.playback.reference.referenceTelemetrySummary
import java.util.Locale

/** Status of a claim, not of the preference which requested it. */
enum class AudioPipelineStatus {
    ACTIVE,
    READY,
    BYPASSED,
    FAILED,
    REPORTED,
    RELEASED,
    UNKNOWN,
}

enum class NativeEngineBadgeOwner { PAWS, HIBY }

data class NativeEngineBadge(
    val owner: NativeEngineBadgeOwner,
    val label: String,
    val status: AudioPipelineStatus,
    /** The loader's own detail, preserved verbatim. */
    val detail: String,
    val capabilities: List<String> = emptyList(),
)

/** A fidelity score deduction, or a native limitation with [points] = null. */
data class AudioPipelineDeduction(
    val label: String,
    val points: Int? = null,
    val detail: String? = null,
)

enum class AudioPipelineTimelineStage { DSP, OUTPUT }

data class AudioPipelineStageModel(
    val stage: AudioPipelineTimelineStage,
    val title: String,
    val status: AudioPipelineStatus,
    val detail: String,
)

enum class AudioPipelineOutputHealth { REPORTED, FAILED, RELEASED }

/**
 * Measured output facts. Null always means "not reported", never zero or an
 * assumed hardware value.
 */
data class AudioPipelineOutputModel(
    val driver: String,
    val sharing: String?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val xruns: Long?,
    val bufferMs: Int? = null,
    val bufferSizeFrames: Int? = null,
    val detail: String? = null,
    val failure: String? = null,
    val released: Boolean = false,
) {
    val health: AudioPipelineOutputHealth
        get() = when {
            failure != null -> AudioPipelineOutputHealth.FAILED
            released -> AudioPipelineOutputHealth.RELEASED
            else -> AudioPipelineOutputHealth.REPORTED
        }

    val driverText: String get() = driver.ifBlank { "Not reported" }
    val sharingText: String get() = sharing?.ifBlank { null } ?: "Not reported"
    val sampleRateText: String get() = sampleRateHz?.let(::pipelineRateText) ?: "Not reported"
    val bitsText: String get() = bitsPerSample?.let { "$it-bit" } ?: "Not reported"
    val xrunsText: String get() = xruns?.toString() ?: "Not reported"
}

data class AudioPipelineExtensionModel(
    /** Exact output of referenceTelemetrySummary, including INIT FAILED text. */
    val summary: String,
    val summaryStatus: AudioPipelineStatus,
    val badges: List<NativeEngineBadge>,
    val deductions: List<AudioPipelineDeduction>,
    val timeline: List<AudioPipelineStageModel>,
    val output: AudioPipelineOutputModel?,
)

/**
 * Builds the optional native extension for the existing AudioPipelineDialog.
 * It does not replace the source/decoder/resampler rows already in that modal.
 */
fun buildAudioPipelineExtension(
    qualityOptions: Boolean,
    mode: ReferenceEngineMode,
    engines: ReferenceEngineSnapshot,
    fidelityDeductions: List<AudioFidelity.Deduction> = emptyList(),
    nativeOutput: NativeOutputTelemetry? = null,
): AudioPipelineExtensionModel {
    val summary = referenceTelemetrySummary(qualityOptions, mode, engines)
    val summaryStatus = pipelineSummaryStatus(summary)
    val output = nativeOutput?.toAudioPipelineOutput()
        ?: engines.driverTelemetry?.toAudioPipelineOutput()
    val referenceOutputFailure = engines.outputDetail?.takeIf { it.startsWith("INIT FAILED: ") }
    val nativeDeduction = summary.takeIf { it.startsWith("INIT FAILED: ") }?.let {
        AudioPipelineDeduction(label = it, detail = "Native reference path is not processing ExoPlayer PCM")
    }
    val outputDeduction = (output?.failure ?: referenceOutputFailure)?.let {
        AudioPipelineDeduction(label = it, detail = "Native output reported a failure")
    }
    val deductions = buildList {
        fidelityDeductions.forEach { deduction ->
            add(AudioPipelineDeduction(label = deduction.label, points = deduction.points))
        }
        nativeDeduction?.let(::add)
        outputDeduction?.let(::add)
    }.distinct()

    return AudioPipelineExtensionModel(
        summary = summary,
        summaryStatus = summaryStatus,
        badges = listOf(
            nativeEngineBadge(
                owner = NativeEngineBadgeOwner.PAWS,
                qualityOptions = qualityOptions,
                mode = mode,
                status = engines.paws,
                hibyOutputActive = false,
            ),
            nativeEngineBadge(
                owner = NativeEngineBadgeOwner.HIBY,
                qualityOptions = qualityOptions,
                mode = mode,
                status = engines.hiby,
                hibyOutputActive = engines.hibyOutputActive,
            ),
        ),
        deductions = deductions,
        timeline = listOf(
            AudioPipelineStageModel(
                stage = AudioPipelineTimelineStage.DSP,
                title = "Reference DSP",
                status = summaryStatus,
                detail = summary,
            ),
            AudioPipelineStageModel(
                stage = AudioPipelineTimelineStage.OUTPUT,
                title = "Native output",
                status = when {
                    output?.health == AudioPipelineOutputHealth.FAILED || referenceOutputFailure != null ->
                        AudioPipelineStatus.FAILED
                    output?.health == AudioPipelineOutputHealth.RELEASED -> AudioPipelineStatus.RELEASED
                    output?.health == AudioPipelineOutputHealth.REPORTED -> AudioPipelineStatus.REPORTED
                    else -> AudioPipelineStatus.UNKNOWN
                },
                detail = output?.let(::outputTimelineDetail)
                    ?: referenceOutputFailure
                    ?: "No native output telemetry reported",
            ),
        ),
        output = output,
    )
}

fun OutputDriverTelemetry.toAudioPipelineOutput(): AudioPipelineOutputModel =
    AudioPipelineOutputModel(
        driver = driver,
        sharing = sharing.ifBlank { null },
        sampleRateHz = sampleRateHz.takeIf { it > 0 },
        bitsPerSample = bitDepth.takeIf { it > 0 },
        xruns = xruns?.takeIf { it >= 0 },
        bufferMs = bufferMs.takeIf { it > 0 },
        detail = detail,
        failure = detail?.takeIf { it.startsWith("INIT FAILED: ") },
    )

fun NativeOutputTelemetry.toAudioPipelineOutput(): AudioPipelineOutputModel =
    AudioPipelineOutputModel(
        driver = driverName,
        sharing = when (sharing) {
            NativeOutputSharing.UNKNOWN -> null
            NativeOutputSharing.SHARED -> "Shared"
            NativeOutputSharing.EXCLUSIVE -> "Exclusive"
        },
        sampleRateHz = sampleRateHz?.takeIf { it > 0 },
        bitsPerSample = bitsPerSample?.takeIf { it > 0 },
        xruns = xruns?.takeIf { it >= 0 },
        bufferSizeFrames = bufferSizeFrames?.takeIf { it > 0 },
        detail = detailOrNull(),
        failure = failure,
        released = released,
    )

private fun NativeOutputTelemetry.detailOrNull(): String? = buildList {
    bufferCapacityFrames?.takeIf { it > 0 }?.let { add("Capacity $it frames") }
    channelCount?.takeIf { it > 0 }?.let { add("$it channels") }
}.joinToString(" · ").ifBlank { null }

private fun nativeEngineBadge(
    owner: NativeEngineBadgeOwner,
    qualityOptions: Boolean,
    mode: ReferenceEngineMode,
    status: NativeEngineStatus,
    hibyOutputActive: Boolean,
): NativeEngineBadge {
    val selected = when (owner) {
        NativeEngineBadgeOwner.PAWS -> mode == ReferenceEngineMode.POWERAMP || mode == ReferenceEngineMode.HYBRID
        NativeEngineBadgeOwner.HIBY -> mode == ReferenceEngineMode.HIBY || mode == ReferenceEngineMode.HYBRID
    }
    val name = if (owner == NativeEngineBadgeOwner.PAWS) "PAWS" else "HIBY"
    if (!qualityOptions) {
        return NativeEngineBadge(owner, "Quality Options OFF", AudioPipelineStatus.BYPASSED, status.detail)
    }
    if (mode == ReferenceEngineMode.BIT_PERFECT) {
        return NativeEngineBadge(owner, "BIT-PERFECT DIRECT", AudioPipelineStatus.BYPASSED, status.detail)
    }
    if (!selected) {
        return NativeEngineBadge(
            owner = owner,
            label = "$name NOT SELECTED",
            status = AudioPipelineStatus.BYPASSED,
            detail = "Not selected by ${mode.label}",
        )
    }
    return when (status.state) {
        NativeLoadState.FAILED -> NativeEngineBadge(
            owner = owner,
            label = "INIT FAILED: ${status.detail}",
            status = AudioPipelineStatus.FAILED,
            detail = status.detail,
            capabilities = status.capabilities.toList(),
        )
        NativeLoadState.BYPASSED -> NativeEngineBadge(
            owner = owner,
            label = "$name BYPASSED: ${status.detail}",
            status = AudioPipelineStatus.BYPASSED,
            detail = status.detail,
            capabilities = status.capabilities.toList(),
        )
        NativeLoadState.LOADED_ACTIVE -> when (owner) {
            NativeEngineBadgeOwner.PAWS -> NativeEngineBadge(
                owner = owner,
                // A loaded library is not a PCM processor. In particular this
                // never says PAWS NATIVE ACTIVE.
                label = "PAWS LIBRARY LOADED",
                status = AudioPipelineStatus.READY,
                detail = status.detail,
                capabilities = status.capabilities.toList(),
            )
            NativeEngineBadgeOwner.HIBY -> NativeEngineBadge(
                owner = owner,
                label = if (hibyOutputActive) "HIBY NATIVE ACTIVE" else "HIBY NATIVE READY",
                status = if (hibyOutputActive) AudioPipelineStatus.ACTIVE else AudioPipelineStatus.READY,
                detail = status.detail,
                capabilities = status.capabilities.toList(),
            )
        }
    }
}

private fun pipelineSummaryStatus(summary: String): AudioPipelineStatus = when {
    summary.startsWith("INIT FAILED: ") -> AudioPipelineStatus.FAILED
    summary == "HIBY NATIVE ACTIVE" -> AudioPipelineStatus.ACTIVE
    summary == "HIBY NATIVE READY" -> AudioPipelineStatus.READY
    summary == "Quality Options OFF" ||
        summary == "BIT-PERFECT DIRECT" ||
        summary.contains(" BYPASSED: ") -> AudioPipelineStatus.BYPASSED
    else -> AudioPipelineStatus.UNKNOWN
}

private fun outputTimelineDetail(output: AudioPipelineOutputModel): String {
    output.failure?.let { return it }
    output.detail?.let { return it }
    if (output.released) return "Released"
    return listOf(output.driverText, output.sharingText, output.sampleRateText, output.bitsText)
        .joinToString(" · ")
}

fun pipelineRateText(rateHz: Int): String {
    require(rateHz > 0) { "Sample rate must be positive" }
    val khz = rateHz / 1_000.0
    val text = if (rateHz % 1_000 == 0) {
        (rateHz / 1_000).toString()
    } else {
        String.format(Locale.ROOT, "%.3f", khz).trimEnd('0').trimEnd('.')
    }
    return "$text kHz"
}
