package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.apple.AppleManifestLocator
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.util.AppRelaunch

/**
 * The catalogue picker in its sheet, with the restart that applies a change.
 *
 * Shared by Settings and the account sheet, so both open the same
 * [HomeProviderSheet] and both restart the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeProviderPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val axes by AppSettings.providerAxes.collectAsStateWithLifecycle()
    val catalogue = axes.source
    // Apple is offered only when an Apple Music manifest is installed: the
    // catalogue is read through it, and without it there is nothing to read.
    var appleAvailable by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        appleAvailable = AppleManifestLocator.isInstalled()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        HomeProviderSheet(
            selected = catalogue,
            appleAvailable = appleAvailable,
            onSelect = { provider ->
                onDismiss()
                if (provider != catalogue) {
                    // Written before the restart so the new process reads it,
                    // and the restart is what makes it total: Flow's and
                    // Autoplay's learning were trained against the provider
                    // being left behind. See [HomeProvider] and [AppRelaunch].
                    //
                    // Both axes that name a catalogue are pointed at the
                    // service, and the older single setting follows them.
                    AppSettings.setCatalogue(provider)
                    AppRelaunch.relaunch(context)
                }
            },
        )
    }
}
