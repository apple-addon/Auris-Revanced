package com.music.bitchord.ui.policy

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.data.settings.MusicProvider

/**
 * Presentation-only host for [PendingAudioConflict].
 *
 * Both dismissal paths call [onCancel]. The composable never writes a setting;
 * [AudioConflictPolicy.reduce] is the sole owner of Cancel/Confirm behaviour.
 */
@Composable
fun AudioConflictDialog(
    pending: PendingAudioConflict?,
    provider: MusicProvider,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending == null) return
    ProviderAudioTheme(provider = provider, surface = ProviderAudioSurface.CONFLICT) {
        val tokens = currentProviderAudioTokens
        AlertDialog(
            modifier = modifier,
            onDismissRequest = onCancel,
            shape = tokens.dialogShape,
            containerColor = tokens.dialogContainer,
            titleContentColor = tokens.onContainer,
            textContentColor = tokens.secondaryText,
            title = {
                Text(
                    text = pending.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = pending.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(pending.confirmLabel)
                }
            },
        )
    }
}
