package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.apple.AppleManifestLocator
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.MusicProvider
import com.music.bitchord.data.settings.ProviderAxis

/**
 * What somebody might type looking for the catalogue switch.
 *
 * The service names first, because that is what people look for: they want
 * "Spotify" or "YouTube" and do not know the app calls the choice a source.
 */
internal val PROVIDER_SOURCE_KEYWORDS = arrayOf(
    "source", "catalogue", "metadata", "lyrics", "artwork",
    "spotify", "youtube", "youtube music", "apple", "apple music",
)

/**
 * The Provider category: the three axes, and the master switch over them.
 *
 * The axes are separate because they answer separate questions:
 *
 *  - **UI** — what the app looks like. Apple's layout, Spotify's, or the
 *    default.
 *  - **Recommendation** — what the app suggests. Home, Flow and Autoplay.
 *  - **Source** — where songs, metadata, lyrics and artwork come from.
 *
 * The master switch at the top points all three at Apple Music in one tap,
 * because "make this app Apple Music" is one request. The three rows below
 * are for the mixed cases — Apple's look over Spotify's catalogue, Apple's
 * recommendations over YouTube's songs — which are legal and which the
 * master switch cannot express.
 *
 * Apple needs an installed Apple Music manifest: the app reads Apple's
 * catalogue through an addon, not from inside itself. The row says so when
 * there is none, rather than offering a switch that cannot do anything.
 */
@Composable
internal fun ProviderSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
) {
    val axes by AppSettings.providerAxes.collectAsStateWithLifecycle()
    val appleOnlyCatalogue by AppSettings.appleOnlyCatalogue.collectAsStateWithLifecycle()

    // Whether an Apple Music manifest is installed. Asked once per visit to
    // the page: the answer decides whether the Apple rows can promise
    // anything, and it is a network question.
    var appleInstalled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        appleInstalled = AppleManifestLocator.isInstalled()
    }

    SearchableSettingsGroup(
        search = search,
        category = SettingsCategory.PROVIDER,
        footer = stringResource(R.string.provider_footer),
    ) {
        val masterTitle = stringResource(R.string.provider_apple_master)
        // The master switch is Apple's alone, and it only works with an
        // Apple Music manifest installed: without one there is no Apple
        // catalogue to read, so the switch is off and says so.
        val appleReady = appleInstalled != false
        row(masterTitle, "apple", "master", "provider", "everything") {
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = masterTitle,
                subtitle = stringResource(R.string.provider_apple_master_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = axes.allApple,
                        enabled = appleReady,
                        onCheckedChange = { wanted ->
                            AppSettings.setAllProviders(
                                if (wanted) MusicProvider.APPLE else MusicProvider.YOUTUBE,
                            )
                        },
                    )
                },
                onClick = {
                    if (!appleReady) return@SettingsRow
                    AppSettings.setAllProviders(
                        if (axes.allApple) MusicProvider.YOUTUBE else MusicProvider.APPLE,
                    )
                },
            )
        }

        // Only Apple's own catalogue, when the app is Apple's.
        //
        // The switch is under the master toggle because it is a rule about
        // the whole app, not about one axis: a track that is not in Apple's
        // catalogue is not played and not offered, whatever else could have
        // played it.
        if (axes.allApple) {
            val onlyTitle = stringResource(R.string.provider_apple_only_catalogue)
            row(onlyTitle, "apple", "catalogue", "only", "apple music", "exclusive") {
                SettingsRow(
                    icon = Icons.Rounded.LibraryMusic,
                    title = onlyTitle,
                    subtitle = stringResource(R.string.provider_apple_only_catalogue_subtitle),
                    trailing = {
                        SettingsSwitch(
                            checked = appleOnlyCatalogue,
                            onCheckedChange = { wanted ->
                                AppSettings.setAppleOnlyCatalogue(wanted)
                            },
                        )
                    },
                    onClick = {
                        AppSettings.setAppleOnlyCatalogue(!appleOnlyCatalogue)
                    },
                )
            }
        }

        val uiTitle = stringResource(R.string.provider_axis_ui)
        row(uiTitle, "ui", "look", "layout", "skin", "theme", "design") {
            ProviderAxisRow(
                axis = ProviderAxis.UI,
                icon = Icons.Rounded.Palette,
                title = uiTitle,
                subtitle = stringResource(R.string.provider_axis_ui_subtitle),
                current = axes.ui,
                onPick = { sheets.pickingProviderAxis = ProviderAxis.UI },
            )
        }

        val recTitle = stringResource(R.string.provider_axis_recommendation)
        row(recTitle, "recommendation", "algorithm", "suggest", "home", "flow", "autoplay") {
            ProviderAxisRow(
                axis = ProviderAxis.RECOMMENDATION,
                icon = Icons.Rounded.AutoAwesome,
                title = recTitle,
                subtitle = stringResource(R.string.provider_axis_recommendation_subtitle),
                current = axes.recommendation,
                onPick = { sheets.pickingProviderAxis = ProviderAxis.RECOMMENDATION },
            )
        }

        val sourceTitle = stringResource(R.string.provider_axis_source)
        // Apple, Spotify and YouTube are named in the keywords because the
        // catalogue switch used to sit on the settings index, where searching
        // "Spotify" found it. It lives here now, and typing a service name
        // should still bring its answer to the top.
        row(sourceTitle, *PROVIDER_SOURCE_KEYWORDS) {
            ProviderAxisRow(
                axis = ProviderAxis.SOURCE,
                icon = Icons.Rounded.LibraryMusic,
                title = sourceTitle,
                subtitle = stringResource(R.string.provider_axis_source_subtitle),
                current = axes.source,
                onPick = { sheets.pickingProviderAxis = ProviderAxis.SOURCE },
            )
        }

        // Only when there is no Apple Music manifest installed. The Apple
        // axes are still selectable — the listener may install one next —
        // but the page says plainly that nothing will answer until they do.
        if (appleInstalled == false) {
            val noteTitle = stringResource(R.string.provider_apple_manifest_missing_title)
            row(noteTitle, "apple", "manifest", "install", "addon", "missing") {
                SettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    title = noteTitle,
                    subtitle = stringResource(R.string.provider_apple_manifest_missing_subtitle),
                )
            }
        }
    }
}

/**
 * One axis's row: the provider it points at, and a tap that changes it.
 *
 * The picker is a sheet rather than a dropdown because the three providers
 * each carry a line of explanation, and the explanation is the point: the
 * axes are easy to confuse and the app should not be the reason they are.
 */
@Composable
private fun ProviderAxisRow(
    axis: ProviderAxis,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    current: MusicProvider,
    onPick: () -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        value = current.localizedLabel(),
        onClick = onPick,
    )
}

/** The picker for one axis: the three providers, and what each one owns. */
@Composable
internal fun ProviderAxisSheet(
    axis: ProviderAxis,
    selected: MusicProvider,
    onSelect: (MusicProvider) -> Unit,
    appleAvailable: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(
                        when (axis) {
                            ProviderAxis.UI -> R.string.provider_axis_ui
                            ProviderAxis.RECOMMENDATION -> R.string.provider_axis_recommendation
                            ProviderAxis.SOURCE -> R.string.provider_axis_source
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(
                        when (axis) {
                            ProviderAxis.UI -> R.string.provider_axis_ui_subtitle
                            ProviderAxis.RECOMMENDATION -> R.string.provider_axis_recommendation_subtitle
                            ProviderAxis.SOURCE -> R.string.provider_axis_source_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            MusicProvider.entries.forEach { provider ->
                val chosen = provider == selected
                // Apple is only offered when there is an Apple Music manifest
                // installed: without one there is no Apple catalogue to read,
                // so the axis could be pointed at nothing.
                val available = provider != MusicProvider.APPLE || appleAvailable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = available) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(provider)
                        }
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = provider.localizedLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (available) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = stringResource(
                                when (provider) {
                                    MusicProvider.YOUTUBE -> R.string.provider_youtube_subtitle
                                    MusicProvider.SPOTIFY -> R.string.provider_spotify_subtitle
                                    MusicProvider.APPLE -> R.string.provider_apple_subtitle
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (chosen) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The provider's name, as the settings row shows it. */
@Composable
internal fun MusicProvider.localizedLabel(): String = stringResource(
    when (this) {
        MusicProvider.YOUTUBE -> R.string.home_provider_youtube
        MusicProvider.SPOTIFY -> R.string.spotify
        MusicProvider.APPLE -> R.string.provider_apple
    },
)
