package com.music.bitchord.playback.audio.audiophile

import com.music.bitchord.playback.audio.ResolvedAudiophileOutput
import com.music.bitchord.ui.screens.audiophile.AudioOutputTarget

/**
 * Visual-only timing telemetry. The configured delay compensates visuals; it never changes audio
 * timestamps. Buffer duration is reported separately and is not added automatically, avoiding
 * double compensation when the player position already includes output latency.
 */
data class VisualDelayTelemetry(
    val target: AudioOutputTarget,
    val configuredDelayMs: Int,
    val bufferSizeFrames: Int?,
    val outputSampleRateHz: Int?,
) {
    val bufferDelayMs: Double?
        get() {
            val frames = bufferSizeFrames ?: return null
            val rate = outputSampleRateHz ?: return null
            if (frames <= 0 || rate <= 0) return null
            return frames * 1_000.0 / rate
        }

    /** Positive delay holds visuals back; negative delay advances them. */
    fun visualPositionMs(audioPositionMs: Long, durationMs: Long? = null): Long =
        VisualDelayPolicy.visualPositionMs(audioPositionMs, configuredDelayMs, durationMs)
}

object VisualDelayPolicy {
    fun telemetry(
        output: ResolvedAudiophileOutput,
        bufferSizeFrames: Int?,
        outputSampleRateHz: Int?,
    ): VisualDelayTelemetry = VisualDelayTelemetry(
        target = output.target,
        configuredDelayMs = output.profile.visualDelayMs,
        bufferSizeFrames = bufferSizeFrames,
        outputSampleRateHz = outputSampleRateHz,
    )

    fun visualPositionMs(
        audioPositionMs: Long,
        configuredDelayMs: Int,
        durationMs: Long? = null,
    ): Long {
        val shifted = audioPositionMs - configuredDelayMs.toLong()
        val nonNegative = shifted.coerceAtLeast(0L)
        return if (durationMs != null && durationMs > 0L) {
            nonNegative.coerceAtMost(durationMs)
        } else {
            nonNegative
        }
    }
}
