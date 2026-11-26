package com.music.bitchord.ui.screens

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.Backup

/** The Data category: Replay, and the backup's export and import. */
@Composable
internal fun DataSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
    onOpenReplay: () -> Unit,
    exportPicker: ActivityResultLauncher<String>,
    exportStatus: String?,
    importStatus: String?,
) {
    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.DATA) {
        val replayTitle = stringResource(R.string.replay)
        row(replayTitle, "stats", "history", "wrapped") {
            SettingsRow(
                icon = Icons.Rounded.BarChart,
                title = replayTitle,
                subtitle = stringResource(R.string.replay_subtitle),
                onClick = onOpenReplay,
            )
        }
        val workOutGenresTitle = stringResource(R.string.work_out_genres)
        row(workOutGenresTitle, "genre", "replay") {
            SettingsRow(
                icon = Icons.Rounded.LocalOffer,
                title = workOutGenresTitle,
                subtitle = if (replayGenres) {
                    stringResource(R.string.replay_genres_enabled_subtitle)
                } else {
                    stringResource(R.string.replay_genres_disabled_subtitle)
                },
                trailing = {
                    SettingsSwitch(
                        checked = replayGenres,
                        onCheckedChange = AppSettings::setReplayGenres,
                    )
                },
                onClick = { AppSettings.setReplayGenres(!replayGenres) },
            )
        }
        // The report a listener can send when something is wrong: the app's
        // own log, what it cost, and the system's own account of it.
        val systemLogTitle = stringResource(R.string.system_log_title)
        var showSystemLog by remember { mutableStateOf(false) }
        row(systemLogTitle, "log", "diagnostics", "bug", "report", "crash") {
            SettingsRow(
                icon = Icons.Rounded.BugReport,
                title = systemLogTitle,
                subtitle = stringResource(R.string.system_log_subtitle),
                onClick = { showSystemLog = true },
            )
        }
        if (showSystemLog) {
            SystemLogSheet(onDismiss = { showSystemLog = false })
        }
        val exportDataTitle = stringResource(R.string.export_data)
        row(exportDataTitle, "backup") {
            SettingsRow(
                icon = Icons.Rounded.FileUpload,
                title = exportDataTitle,
                subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                onClick = { exportPicker.launch(Backup.suggestedName()) },
            )
        }
        val importDataTitle = stringResource(R.string.import_data)
        row(importDataTitle, "backup", "restore") {
            SettingsRow(
                icon = Icons.Rounded.FileDownload,
                title = importDataTitle,
                subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                onClick = { sheets.confirmImport = true },
            )
        }
    }
}
