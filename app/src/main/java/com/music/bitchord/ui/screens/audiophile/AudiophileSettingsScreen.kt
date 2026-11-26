package com.music.bitchord.ui.screens.audiophile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/**
 * Complete mountable hierarchy. Parent code only supplies a back action and optional runtime hooks;
 * no navigation-library or existing EQ Hub / Audio Output sheet changes are required.
 */
@Composable
fun AudiophileSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    runtime: AudiophileRuntime = AudiophileRuntime.NONE,
    stores: AudiophileStores? = null,
    initialSection: AudiophileSection? = null,
) {
    val context = LocalContext.current
    val mountedStores = remember(context, stores) { stores ?: AudiophileStores(context) }
    DisposableEffect(mountedStores, stores) {
        onDispose {
            if (stores == null) mountedStores.close()
        }
    }
    var section by remember(initialSection) { mutableStateOf(initialSection) }
    val backToRoot: () -> Unit = { section = null }
    val fadeState = mountedStores.fades.state.collectAsStateWithLifecycle().value

    when (section) {
        null -> AudiophileHomeScreen(
            modifier = modifier,
            onBack = onBack,
            contentPadding = contentPadding,
            onOpen = { section = it },
        )
        AudiophileSection.AUDIO_INFO -> AudioInfoScreen(modifier, backToRoot, contentPadding)
        AudiophileSection.FADES -> CrossfadeFadeGaplessScreen(
            store = mountedStores.fades,
            modifier = modifier,
            runtime = runtime,
            onBack = backToRoot,
            contentPadding = contentPadding,
        )
        AudiophileSection.REPLAY_GAIN -> ReplayGainScreen(modifier, runtime, backToRoot, contentPadding)
        AudiophileSection.AUDIO_FOCUS -> AudioFocusScreen(mountedStores.settings, modifier, runtime, backToRoot, contentPadding)
        AudiophileSection.EQUALIZER -> AudiophileEqualizerScreen(modifier, runtime, backToRoot, contentPadding)
        AudiophileSection.RESAMPLER -> ResamplerScreen(mountedStores.settings, modifier, runtime, backToRoot, contentPadding)
        AudiophileSection.DVC -> DvcScreen(mountedStores.settings, modifier, runtime, backToRoot, contentPadding)
        AudiophileSection.AUDIO_OUTPUTS -> AudioOutputsScreen(
            store = mountedStores.outputs,
            globalStore = mountedStores.settings,
            modifier = modifier,
            runtime = runtime,
            crossfadeRequested = (fadeState.crossfadeAutoAdvance && fadeState.crossfadeLengthMs > 0L) ||
                (fadeState.fadeManualAdvance && fadeState.shortManualCrossfadeMs > 0L),
            onBack = backToRoot,
            contentPadding = contentPadding,
        )
        AudiophileSection.BIT_PERFECT -> BitPerfectScreen(
            store = mountedStores.settings,
            modifier = modifier,
            runtime = runtime,
            onOpenAudioOutputs = { section = AudiophileSection.AUDIO_OUTPUTS },
            onBack = backToRoot,
            contentPadding = contentPadding,
        )
        AudiophileSection.ADVANCED -> AdvancedTweaksScreen(mountedStores.settings, modifier, runtime, backToRoot, contentPadding)
        AudiophileSection.REFERENCE_MODE -> ReferenceModeScreen(modifier, runtime, backToRoot, contentPadding)
    }
}

@Composable
private fun AudiophileHomeScreen(
    modifier: Modifier,
    onBack: (() -> Unit)?,
    contentPadding: PaddingValues,
    onOpen: (AudiophileSection) -> Unit,
) {
    val referenceMode by AppSettings.referenceMode.collectAsStateWithLifecycle()
    AudiophilePage("Audiophile Section", modifier, onBack, contentPadding) {
        AudiophileNote("Live signal information, persisted playback policy and independent profiles for every output.")
        AudiophileSectionLabel("Playback")
        AudiophileCard {
            AudiophileSection.entries.take(7).forEachIndexed { index, item ->
                AudiophileNavigationRow(item.title, item.subtitle, { onOpen(item) })
                if (index != 6) AudiophileDivider()
            }
        }
        AudiophileSectionLabel("Output & Processing")
        AudiophileCard {
            AudiophileSection.entries.drop(7).forEachIndexed { index, item ->
                AudiophileNavigationRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    onClick = { onOpen(item) },
                    value = if (item == AudiophileSection.REFERENCE_MODE) referenceMode.label else null,
                )
                if (index != AudiophileSection.entries.drop(7).lastIndex) AudiophileDivider()
            }
        }
    }
}
