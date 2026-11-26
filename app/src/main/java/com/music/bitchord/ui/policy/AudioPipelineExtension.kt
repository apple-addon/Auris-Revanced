package com.music.bitchord.ui.policy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.MusicProvider

/**
 * Optional content for the existing AudioPipelineDialog, not a replacement for
 * that dialog. Mount it after the existing DSP/output sections and pass the
 * current UI provider. The card owns its background and foreground pairs, so it
 * remains readable even inside the original player-modal dark palette.
 */
@Composable
fun AudioPipelineExtension(
    model: AudioPipelineExtensionModel,
    provider: MusicProvider,
    modifier: Modifier = Modifier,
) {
    ProviderAudioTheme(provider = provider, surface = ProviderAudioSurface.PIPELINE) {
        val tokens = currentProviderAudioTokens
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = tokens.cardShape,
            color = tokens.container,
            contentColor = tokens.onContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Native reference extension",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = model.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = model.summaryStatus.foreground(tokens),
                    )
                }

                HorizontalDivider(color = tokens.outline)
                PipelineSectionTitle("Engine badges")
                model.badges.forEach { badge -> NativeEngineBadgeRow(badge) }

                if (model.deductions.isNotEmpty()) {
                    HorizontalDivider(color = tokens.outline)
                    PipelineSectionTitle("Fidelity deductions & native limitations")
                    model.deductions.forEach { deduction -> PipelineDeductionRow(deduction) }
                }

                HorizontalDivider(color = tokens.outline)
                PipelineSectionTitle("Native timeline")
                PipelineTimeline(model.timeline)

                model.output?.let { output ->
                    HorizontalDivider(color = tokens.outline)
                    PipelineSectionTitle("Output driver facts")
                    PipelineFactRow("Driver", output.driverText)
                    PipelineFactRow("Sharing", output.sharingText)
                    PipelineFactRow("Sample rate", output.sampleRateText)
                    PipelineFactRow("Bits", output.bitsText)
                    PipelineFactRow("XRuns", output.xrunsText)
                    output.bufferMs?.let { PipelineFactRow("Buffer", "$it ms") }
                    output.bufferSizeFrames?.let { PipelineFactRow("Buffer", "$it frames") }
                    output.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.secondaryText,
                        )
                    }
                    output.failure?.let { failure ->
                        if (failure != output.detail) {
                            Text(
                                text = failure,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.failure,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineSectionTitle(text: String) {
    val tokens = currentProviderAudioTokens
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = tokens.secondaryText,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun NativeEngineBadgeRow(badge: NativeEngineBadge) {
    val tokens = currentProviderAudioTokens
    val colors = badge.status.colors(tokens)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = tokens.badgeShape,
            color = colors.first,
            contentColor = colors.second,
        ) {
            Text(
                text = badge.label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (badge.detail.isNotBlank() && badge.detail != badge.label) {
            Text(
                text = badge.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (badge.status == AudioPipelineStatus.FAILED) tokens.failure else tokens.secondaryText,
            )
        }
        if (badge.capabilities.isNotEmpty()) {
            Text(
                text = badge.capabilities.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
        }
    }
}

@Composable
private fun PipelineDeductionRow(deduction: AudioPipelineDeduction) {
    val tokens = currentProviderAudioTokens
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = deduction.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (deduction.points == null) tokens.failure else tokens.onContainer,
            )
            deduction.points?.let { points ->
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "−$points",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.secondaryText,
                )
            }
        }
        deduction.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
        }
    }
}

@Composable
private fun PipelineTimeline(stages: List<AudioPipelineStageModel>) {
    val tokens = currentProviderAudioTokens
    Column {
        stages.forEachIndexed { index, stage ->
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(stage.status.foreground(tokens), CircleShape),
                    )
                    if (index != stages.lastIndex) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(30.dp)
                                .background(tokens.outline),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = if (index == stages.lastIndex) 0.dp else 12.dp)) {
                    Text(
                        text = stage.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stage.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stage.status == AudioPipelineStatus.FAILED) tokens.failure else tokens.secondaryText,
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineFactRow(label: String, value: String) {
    val tokens = currentProviderAudioTokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.secondaryText,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.onContainer,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun AudioPipelineStatus.colors(tokens: ProviderAudioTokens): Pair<Color, Color> = when (this) {
    AudioPipelineStatus.ACTIVE -> tokens.accentContainer to tokens.onAccentContainer
    AudioPipelineStatus.READY -> tokens.positiveContainer to tokens.onPositiveContainer
    AudioPipelineStatus.FAILED -> tokens.failureContainer to tokens.onFailureContainer
    AudioPipelineStatus.BYPASSED,
    AudioPipelineStatus.REPORTED,
    AudioPipelineStatus.RELEASED,
    AudioPipelineStatus.UNKNOWN,
    -> tokens.neutralContainer to tokens.onNeutralContainer
}

private fun AudioPipelineStatus.foreground(tokens: ProviderAudioTokens): Color = when (this) {
    AudioPipelineStatus.ACTIVE -> tokens.accent
    AudioPipelineStatus.READY -> tokens.positive
    AudioPipelineStatus.FAILED -> tokens.failure
    AudioPipelineStatus.BYPASSED,
    AudioPipelineStatus.REPORTED,
    AudioPipelineStatus.RELEASED,
    AudioPipelineStatus.UNKNOWN,
    -> tokens.secondaryText
}
