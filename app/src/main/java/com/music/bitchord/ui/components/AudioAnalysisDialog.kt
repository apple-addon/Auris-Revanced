/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.playback.audio.analysis.AnalysisCancelledException
import com.music.bitchord.playback.audio.analysis.AnalysisLabels
import com.music.bitchord.playback.audio.analysis.AudioAnalysisResult
import com.music.bitchord.playback.audio.analysis.AudioAnalyzer
import com.music.bitchord.playback.audio.analysis.Band
import com.music.bitchord.playback.audio.analysis.PcmFrameSource
import com.music.bitchord.playback.audio.analysis.PcmSourceException
import com.music.bitchord.playback.audio.analysis.PipelineLinkTarget
import com.music.bitchord.playback.audio.analysis.SourceVerdict
import com.music.bitchord.playback.audio.analysis.Spectrogram
import com.music.bitchord.playback.audio.analysis.dcPercentLabel
import com.music.bitchord.playback.audio.analysis.fmtAt
import com.music.bitchord.playback.audio.analysis.fmtDb
import com.music.bitchord.playback.audio.analysis.fmtDbfs
import com.music.bitchord.playback.audio.analysis.fmtDbtp
import com.music.bitchord.playback.audio.analysis.fmtDr
import com.music.bitchord.playback.audio.analysis.fmtKhz
import com.music.bitchord.playback.audio.analysis.fmtLufs
import com.music.bitchord.playback.audio.analysis.fmtLu
import com.music.bitchord.playback.audio.analysis.fmtSeconds
import com.music.bitchord.playback.audio.analysis.fmtTimecode
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.log10

/** State of one analysis pass. */
internal sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data class Running(val progress: Float) : AnalysisUiState
    data class Done(val result: AudioAnalysisResult) : AnalysisUiState
    data class Failed(val message: String) : AnalysisUiState
}

/**
 * Owns the analysis job so a dialog dismissal (or a back out of the
 * pipeline's embedded panel) cancels decoding and drops the partial pass.
 */
internal class AudioAnalysisController {
    var state by mutableStateOf<AnalysisUiState>(AnalysisUiState.Idle)
        private set

    private var job: Job? = null

    fun startIfIdle(
        scope: kotlinx.coroutines.CoroutineScope,
        source: () -> PcmFrameSource?,
        tagGainDb: () -> Double?,
    ) {
        if (state !is AnalysisUiState.Idle && state !is AnalysisUiState.Failed) return
        start(scope, source, tagGainDb)
    }

    fun start(
        scope: kotlinx.coroutines.CoroutineScope,
        source: () -> PcmFrameSource?,
        tagGainDb: () -> Double?,
    ) {
        job?.cancel()
        state = AnalysisUiState.Running(0f)
        job = scope.launch(Dispatchers.Default) {
            val pcm = try {
                source()
            } catch (error: Exception) {
                null
            }
            // Tag reads can touch the file or the network: keep them on the
            // analysis dispatcher, never on the composition thread.
            val tagGain = runCatching { tagGainDb() }.getOrNull()
            if (pcm == null) {
                state = AnalysisUiState.Failed(AnalysisLabels.ERR_CANT_OPEN)
                return@launch
            }
            try {
                pcm.use { stream ->
                    val analyzer = AudioAnalyzer()
                    val result = analyzer.analyze(
                        source = stream,
                        tagGainDb = tagGain,
                        progress = { fraction ->
                            if (isActive) state = AnalysisUiState.Running(fraction.coerceIn(0f, 1f))
                        },
                        isCancelled = { !isActive },
                    )
                    state = AnalysisUiState.Done(result)
                }
            } catch (cancelled: AnalysisCancelledException) {
                state = AnalysisUiState.Idle
            } catch (error: PcmSourceException) {
                state = AnalysisUiState.Failed(
                    error.message?.takeIf { it.isNotBlank() } ?: AnalysisLabels.ERR_READ,
                )
            } catch (error: OutOfMemoryError) {
                state = AnalysisUiState.Failed(AnalysisLabels.ERR_NO_MEMORY)
            } catch (error: Exception) {
                state = AnalysisUiState.Failed(AnalysisLabels.ERR_READ)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (state is AnalysisUiState.Running) state = AnalysisUiState.Idle
    }

    fun reset() {
        cancel()
        state = AnalysisUiState.Idle
    }

    fun dispose() {
        job?.cancel()
        job = null
    }
}

@Composable
internal fun rememberAudioAnalysisController(): AudioAnalysisController {
    val controller = remember { AudioAnalysisController() }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

/**
 * Standalone "Audio Analysis" surface: a rounded card with the same
 * boundary language as the pipeline dialog, themed with [MaterialTheme].
 */
@Composable
fun AudioAnalysisDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    source: () -> PcmFrameSource?,
    tagGainDb: Double? = null,
    onOpenLink: (PipelineLinkTarget) -> Unit = {},
) {
    val controller = rememberAudioAnalysisController()
    val scope = rememberCoroutineScope()
    BackHandler {
        controller.cancel()
        onDismiss()
    }
    LaunchedEffect(Unit) {
        controller.startIfIdle(scope, source) { tagGainDb }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { controller.cancel(); onDismiss() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(ALERT_CORNER))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    text = AnalysisLabels.TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Decoded PCM, measured in-app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                AudioAnalysisContent(
                    state = controller.state,
                    onRun = { controller.start(scope, source) { tagGainDb } },
                    onCancel = controller::cancel,
                    onOpenLink = onOpenLink,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { controller.cancel(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The measured body, shared by the dialog and the pipeline's embedded panel. */
@Composable
internal fun AudioAnalysisContent(
    state: AnalysisUiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpenLink: (PipelineLinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (state) {
            AnalysisUiState.Idle -> {
                AnalysisActionRow("Analysis", "Run", onClick = onRun)
            }
            is AnalysisUiState.Running -> {
                Text(
                    text = "Analyzing decoded PCM… ${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                AnalysisActionRow("Analysis", "Cancel", onClick = onCancel)
            }
            is AnalysisUiState.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                AnalysisActionRow("Analysis", "Retry", onClick = onRun)
            }
            is AnalysisUiState.Done -> AnalysisResultBody(
                result = state.result,
                onOpenLink = onOpenLink,
                onRerun = onRun,
            )
        }
    }
}

@Composable
private fun AnalysisResultBody(
    result: AudioAnalysisResult,
    onOpenLink: (PipelineLinkTarget) -> Unit,
    onRerun: () -> Unit,
) {
    var hint by remember { mutableStateOf<String?>(null) }
    var channel by remember { mutableStateOf<Int?>(null) }

    val silence = result.silence
    Row(Modifier.fillMaxWidth()) {
        MetricCell(AnalysisLabels.LEADING_SILENCE, fmtSeconds(silence.leadingMs), Modifier.weight(1f))
        MetricCell(AnalysisLabels.TRAILING_SILENCE, fmtSeconds(silence.trailingMs), Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    MetricCell(
        AnalysisLabels.INTERNAL_GAPS,
        if (silence.gapCount == 0) {
            AnalysisLabels.GAPS_NONE
        } else {
            AnalysisLabels.GAPS_D_S_S.format(
                Locale.ROOT,
                silence.gapCount,
                fmtSeconds(silence.longestGapMs),
                fmtTimecode(silence.longestGapAtMs),
            )
        },
    )

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))

    Text(
        text = AnalysisLabels.SPECTROGRAM,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChannelChip(AnalysisLabels.ALL_CHANNELS, channel == null) { channel = null }
        if (result.source.channels >= 2) {
            ChannelChip(AnalysisLabels.CH_L, channel == 0) { channel = 0 }
            ChannelChip(AnalysisLabels.CH_R, channel == 1) { channel = 1 }
        }
    }
    Spacer(Modifier.height(8.dp))
    SpectrogramView(
        spectrogram = result.spectrogram,
        channel = channel,
        durationMs = result.durationMs,
    )
    Spacer(Modifier.height(10.dp))

    // ── Spectral energies ────────────────────────────────────────────
    Row(Modifier.fillMaxWidth()) {
        BandCell(AnalysisLabels.BASS, result, Band.BASS, AnalysisLabels.HINT_BASS, { hint = it }, Modifier.weight(1f))
        BandCell(AnalysisLabels.MID, result, Band.MID, AnalysisLabels.HINT_MID, { hint = it }, Modifier.weight(1f))
        BandCell(AnalysisLabels.TREBLE, result, Band.TREBLE, AnalysisLabels.HINT_TREBLE, { hint = it }, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth()) {
        BandCell(AnalysisLabels.SUBSONIC, result, Band.SUBSONIC, AnalysisLabels.HINT_SUBSONIC, { hint = it }, Modifier.weight(1f))
        BandCell(AnalysisLabels.ULTRASONIC, result, Band.ULTRASONIC, AnalysisLabels.HINT_ULTRASONIC, { hint = it }, Modifier.weight(1f))
        Spacer(Modifier.weight(1f))
    }

    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))

    // ── Levels ───────────────────────────────────────────────────────
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.PEAK,
            "${fmtDbfs(result.peakDbfs)}\n${fmtAt(result.peakAtMs)}",
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_PEAK,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.TRUE_PEAK,
            "${fmtDbtp(result.truePeakDbtp)}\n${fmtAt(result.truePeakAtMs)}",
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_TRUE_PEAK,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.DC_OFFSET,
            dcPercentLabel(result.dcOffsetPercent),
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_DC,
            onHint = { hint = it },
        )
    }
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.RMS,
            fmtDbfs(result.rmsDbfs),
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_RMS,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.DYNAMIC_RANGE,
            fmtDr(result.dynamicRange),
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_DR,
            onHint = { hint = it },
        )
    }

    // ── Per channel ──────────────────────────────────────────────────
    Spacer(Modifier.height(4.dp))
    HintableLabel(AnalysisLabels.PER_CHANNEL, AnalysisLabels.HINT_PER_CHANNEL, { hint = it })
    result.perChannel.forEachIndexed { index, stats ->
        val name = if (index == 0) "L" else if (index == 1) "R" else "Ch ${index + 1}"
        Text(
            text = "${AnalysisLabels.CHANNEL_LINE.format(Locale.ROOT, name, "%.1f".format(Locale.ROOT, stats.peakDbfs), "%.1f".format(Locale.ROOT, stats.rmsDbfs))}\n" +
                AnalysisLabels.CHANNEL_CREST_DR.format(Locale.ROOT, "%.1f".format(Locale.ROOT, stats.crestDb), stats.dr),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 1.dp),
        )
    }

    // ── Clipping ─────────────────────────────────────────────────────
    Spacer(Modifier.height(4.dp))
    HintableLabel(AnalysisLabels.CLIPPING, AnalysisLabels.HINT_CLIPPING, { hint = it })
    Text(
        text = if (result.clipping.any) {
            AnalysisLabels.CLIPPING_D_D.format(
                Locale.ROOT, result.clipping.events, result.clipping.samples,
            ) + "\n" + AnalysisLabels.CLIP_POSITIONS.format(
                Locale.ROOT,
                result.clipping.firstAtMs?.let(::fmtTimecode) ?: "—",
                result.clipping.longestSamples,
                result.clipping.longestAtMs?.let(::fmtTimecode) ?: "—",
            )
        } else {
            AnalysisLabels.GAPS_NONE
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))

    // ── Spectrum ─────────────────────────────────────────────────────
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.SPECTRAL_CUTOFF,
            result.cutoffLabel,
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_CUTOFF,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.NYQUIST,
            fmtKhz(result.nyquistHz),
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_NYQUIST,
            onHint = { hint = it },
        )
    }
    Text(
        text = result.verdictLabel,
        style = MaterialTheme.typography.bodyMedium,
        color = if (result.verdict == SourceVerdict.LOSSY) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )

    // ── Stereo field ─────────────────────────────────────────────────
    if (result.source.channels >= 2) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            MetricCell(
                "Correlation",
                "%.2f".format(Locale.ROOT, result.correlation),
                Modifier.weight(1f),
                hint = AnalysisLabels.HINT_STEREO,
                onHint = { hint = it },
            )
            MetricCell(
                AnalysisLabels.SIDE_MID,
                fmtDb(result.sideMidDb),
                Modifier.weight(1f),
                hint = AnalysisLabels.HINT_SIDE_MID,
                onHint = { hint = it },
            )
        }
        val flags = buildList {
            if (result.identicalChannels) add(AnalysisLabels.IDENTICAL_CHANNELS)
            if (result.reversedPolarity) add(AnalysisLabels.REVERSED_POLARITY)
        }
        if (flags.isNotEmpty()) {
            Text(
                text = flags.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))

    // ── Loudness ─────────────────────────────────────────────────────
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.LOUDNESS,
            fmtLufs(result.loudnessLufs),
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_LOUDNESS,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.LRA,
            fmtLu(result.lraLu),
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_LRA,
            onHint = { hint = it },
        )
    }
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.MAX_MOMENTARY,
            "${fmtLufs(result.maxMomentaryLufs)}\n${fmtAt(result.maxMomentaryAtMs)}",
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_MAX_MOMENTARY,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.MAX_SHORT_TERM,
            "${fmtLufs(result.maxShortTermLufs)}\n${fmtAt(result.maxShortTermAtMs)}",
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_MAX_SHORT_TERM,
            onHint = { hint = it },
        )
    }
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.PLR,
            if (result.plrDb.isFinite()) fmtDb(result.plrDb) else "—",
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_PLR,
            onHint = { hint = it },
        )
        MetricCell(
            AnalysisLabels.HEADROOM,
            if (result.headroomDb.isFinite()) fmtDb(result.headroomDb) else "—",
            Modifier.weight(1f),
            hint = AnalysisLabels.HINT_HEADROOM.format(Locale.ROOT, "0 dBTP"),
            onHint = { hint = it },
        )
    }

    // ── Gain ─────────────────────────────────────────────────────────
    Spacer(Modifier.height(4.dp))
    val gain = result.gain
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.RG_TAG,
            gain.tagGainDb?.let { fmtDb(it) } ?: AnalysisLabels.NO_TAG,
            Modifier.weight(1f),
        )
        MetricCell(
            AnalysisLabels.RG_SUGGESTED,
            fmtDb(gain.suggestedGainDb),
            Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth()) {
        MetricCell(
            AnalysisLabels.AFTER_TAG,
            gain.afterTagDb?.let(::fmtDb) ?: "—",
            Modifier.weight(1f),
        )
        MetricCell(
            AnalysisLabels.AFTER_SUGGESTED,
            if (gain.afterSuggestedDb.isFinite()) fmtDb(gain.afterSuggestedDb) else "—",
            Modifier.weight(1f),
        )
    }
    if (gain.afterTagDb != null && gain.afterTagDb < 0.0) {
        Text(
            text = AnalysisLabels.OVER_CEILING_TAG.format(Locale.ROOT, "0 dBTP"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (gain.afterSuggestedDb.isFinite() && gain.afterSuggestedDb < 0.0) {
        Text(
            text = AnalysisLabels.OVER_CEILING_SUGGESTED.format(Locale.ROOT, "0 dBTP"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    AnalysisLinkRow(
        label = "Replay Gain",
        text = "Open >",
        href = "settings/replay_gain",
        onOpenLink = onOpenLink,
    )

    // ── Format ───────────────────────────────────────────────────────
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(10.dp))
    MetricCell(AnalysisLabels.DURATION, fmtTimecode(result.durationMs) + " (${fmtSeconds(result.durationMs)})")
    MetricCell(
        AnalysisLabels.DECODED_FORMAT,
        "${result.source.sampleRateHz / 1000.0} kHz · ${result.source.channels} ch · ${result.source.decodedFormat}",
    )
    MetricCell(
        AnalysisLabels.BIT_DEPTH,
        if (result.source.isFloat) {
            AnalysisLabels.FLOAT_D.format(Locale.ROOT, result.source.bitDepth)
        } else {
            AnalysisLabels.INT_D.format(Locale.ROOT, result.source.bitDepth)
        },
    )
    MetricCell(
        AnalysisLabels.BITRATE,
        result.source.bitrateKbps?.let { "$it kbps" } ?: "—",
    )
    AnalysisActionRow("Analysis", "Re-run", onClick = onRerun)

    hint?.let { text ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onHint: ((String?) -> Unit)? = null,
) {
    Column(modifier = modifier.padding(vertical = 3.dp, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(12.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onHint?.invoke(hint) },
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BandCell(
    label: String,
    result: AudioAnalysisResult,
    band: Band,
    hint: String,
    onHint: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = result.bandEnergiesDb[band]
    MetricCell(
        label,
        if (value != null && value.isFinite()) fmtDb(value) else "—",
        modifier,
        hint = hint,
        onHint = onHint,
    )
}

@Composable
private fun HintableLabel(label: String, hint: String, onHint: (String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(12.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { onHint(hint) },
        )
    }
}

@Composable
private fun AnalysisActionRow(label: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        )
    }
}

@Composable
private fun AnalysisLinkRow(
    label: String,
    text: String,
    href: String,
    onOpenLink: (PipelineLinkTarget) -> Unit,
) {
    val target = com.music.bitchord.playback.audio.analysis.ReferenceLinks.map(href)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { target?.let(onOpenLink) },
        )
    }
}

@Composable
private fun ChannelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Time/frequency raster drawn with small rectangles, plus log-frequency and
 * time axes and the reference's 0/-60/-120 dB colour key.
 */
@Composable
private fun SpectrogramView(
    spectrogram: Spectrogram,
    channel: Int?,
    durationMs: Long,
) {
    val density = LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val values = remember(spectrogram, channel) { spectrogram.channelDb(channel) }
    val labelPaint = remember(labelColor) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelColor.toArgb()
            textSize = 9f
        }
    }
    labelPaint.textSize = with(density) { 9.sp.toPx() }

    Row(Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(182.dp),
        ) {
            val left = 34.dp.toPx()
            val bottom = 16.dp.toPx()
            val right = size.width
            val top = 0f
            val plotWidth = right - left
            val plotHeight = bottom - top
            if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

            val columns = spectrogram.columns
            val rows = spectrogram.rows
            val cellW = plotWidth / columns
            val cellH = plotHeight / rows

            for (col in 0 until columns) {
                for (row in 0 until rows) {
                    val db = values[col * rows + row]
                    drawRect(
                        color = spectrogramColor(db),
                        topLeft = Offset(left + col * cellW, top + row * cellH),
                        size = Size(cellW + 0.5f, cellH + 0.5f),
                    )
                }
            }

            // Frequency axis (top = high).
            val labels = listOf(20_000.0, 10_000.0, 5_000.0, 2_000.0, 1_000.0, 500.0, 200.0, 100.0, 50.0)
            labels.forEach { hz ->
                if (hz <= spectrogram.maxHz) {
                    val frac = rowFraction(hz, spectrogram.minHz, spectrogram.maxHz)
                    val y = top + (1f - frac) * plotHeight
                    drawContext.canvas.nativeCanvas.drawText(
                        if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString(),
                        0f,
                        y + 3f,
                        labelPaint,
                    )
                }
            }
            // Time axis.
            val ticks = 4
            for (i in 0..ticks) {
                val frac = i / ticks.toFloat()
                val x = left + frac * plotWidth
                drawLine(axisColor, Offset(x, bottom), Offset(x, bottom + 3.dp.toPx()), 1f)
                val ms = (durationMs * frac).toLong()
                drawContext.canvas.nativeCanvas.drawText(
                    fmtTimecode(ms),
                    x.coerceAtMost(size.width - 26.dp.toPx()),
                    size.height,
                    labelPaint,
                )
            }
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), 1f)
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), 1f)
        }

        // Colour key: 0 / -60 / -120 dB.
        Column(
            modifier = Modifier
                .width(34.dp)
                .height(182.dp)
                .padding(start = 4.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val steps = 48
                for (i in 0 until steps) {
                    val db = -120f + (120f * i / (steps - 1))
                    drawRect(
                        color = spectrogramColor(db),
                        topLeft = Offset(0f, size.height * (1f - i / (steps - 1f))),
                        size = Size(size.width * 0.55f, size.height / steps + 1f),
                    )
                }
            }
            Column(Modifier.padding(start = 2.dp)) {
                Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 8.sp)
                Text("-60", style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 8.sp)
                Text("-120", style = MaterialTheme.typography.labelSmall, color = labelColor, fontSize = 8.sp)
            }
        }
    }
}

private fun rowFraction(hz: Double, minHz: Double, maxHz: Double): Float {
    if (hz <= 0.0 || minHz <= 0.0 || maxHz <= minHz) return 0f
    val frac = log10(hz / minHz) / log10(maxHz / minHz)
    return frac.coerceIn(0.0, 1.0).toFloat()
}

/** -120 dB black -> -60 deep purple -> -30 orange -> 0 dB pale yellow. */
private fun spectrogramColor(db: Float): Color {
    val t = ((db + 120f) / 120f).coerceIn(0f, 1f)
    val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    val colors = arrayOf(
        Color(0xFF05030A),
        Color(0xFF2A0A4A),
        Color(0xFF8E1E6E),
        Color(0xFFE8611A),
        Color(0xFFFFF3C4),
    )
    for (i in 0 until stops.size - 1) {
        if (t <= stops[i + 1]) {
            val span = stops[i + 1] - stops[i]
            val local = ((t - stops[i]) / span).coerceIn(0f, 1f)
            return lerpColor(colors[i], colors[i + 1], local)
        }
    }
    return colors.last()
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
