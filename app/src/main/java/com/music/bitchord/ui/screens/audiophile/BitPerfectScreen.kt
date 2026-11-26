package com.music.bitchord.ui.screens.audiophile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.playback.AudioOutputStatus

@Composable
fun BitPerfectScreen(
    store: AudiophileSettingsStore,
    modifier: Modifier = Modifier,
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    onOpenAudioOutputs: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val settings = store.state.collectAsStateWithLifecycle().value.bitPerfect
    val perfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    val output by AudioOutputStatus.current.collectAsStateWithLifecycle()
    fun update(value: BitPerfectSettings) {
        store.updateBitPerfect(value)
        runtime.onBitPerfectChanged(store.state.value.bitPerfect)
    }
    fun requestPerfect(enabled: Boolean) {
        AudioConflictCoordinator.requestNativeBitPerfect(enabled)
        runtime.onPerfectBitPerfectRequested(enabled)
    }
    AudiophilePage("Bit Perfect", modifier, onBack, contentPadding) {
        AudiophileNote("Each setting on this page can also be overridden individually for the specific output or profile.")
        AudiophileNote("These settings are only supported by compatible drivers. To make an option take effect, switch to a driver that supports it.")
        AudiophileCard {
            AudiophileActionButton(if (settings.showMore) "Show Less" else "Show More") {
                update(settings.copy(showMore = !settings.showMore))
            }
            AudiophileSwitchRow(
                "64-bit Audio Pipeline",
                settings.process64Bit,
                { update(settings.copy(process64Bit = it)) },
                "Enables Float 64-bit audio pipeline from decoder to the output.",
            )
            AudiophileSwitchRow(
                "Follow Track Sample Rate",
                settings.followSampleRate,
                { update(settings.copy(followSampleRate = it)) },
                "Enables track sample rate matching for the compatible outputs. In Follow Track mode, the player will try to match the track sample rate to a rate supported by the device, or upsample to the next higher supported rate.",
            )
            AudiophileSwitchRow(
                "DSD Remastering",
                settings.dsdRemaster,
                { update(settings.copy(dsdRemaster = it)) },
                if (perfect) "Unavailable while Perfect BP is active; the saved choice is preserved." else "Remasters PCM to DSD. Allows playing any format (including DSD) with DSP processing in PCM domain that is remastered to DSD for output.",
                enabled = !perfect,
            )
            AudiophileSwitchRow(
                "Perfect Bit Perfect",
                perfect,
                ::requestPerfect,
                "Direct decoder-to-output path. Enables DSD output for DSD tracks. No Equalizer, no DSP, no software volume control. Intended for DAP and USB DACs with hardware volume control. The player may still convert/resample tracks with sample rate not supported by output; DSD bitstream is directly sent to the output if supported.",
            )
        }
        AudiophileSectionLabel("Per-output Compatibility")
        AudiophileCard {
            listOf(AudioOutputRoute.WIRED, AudioOutputRoute.USB).forEachIndexed { index, route ->
                if (onOpenAudioOutputs != null) {
                    AudiophileNavigationRow(
                        title = route.label,
                        subtitle = "Override 64-bit, source rate, DSD Remastering and Perfect Bit Perfect for this output.",
                        onClick = onOpenAudioOutputs,
                    )
                }
                if (index != 1) AudiophileDivider()
            }
            if (onOpenAudioOutputs == null) {
                AudiophileNote("Per-output overrides are available under Audio Outputs.")
            }
        }
        AudiophileCard {
            AudiophileActionButton("Restore Defaults") {
                store.restoreBitPerfectDefaults()
                requestPerfect(false)
                runtime.onBitPerfectChanged(store.state.value.bitPerfect)
            }
        }
        if (settings.showMore) {
            AudiophileSectionLabel("More")
            AudiophileCard {
                AudiophileInfoRow("Actual Output", AudioOutputStatus.encodingLabel(output))
                AudiophileInfoRow("Measured Rate", formatSampleRate(output.actualSampleRateHz))
                AudiophileInfoRow("DSP Path", if (output.dspBypassed) "Bypassed" else "Configurable")
                AudiophileInfoRow("Bit Exact", if (output.outputExact) "Verified" else "Not verified", output.outputExactDetail)
            }
        }
    }
}
