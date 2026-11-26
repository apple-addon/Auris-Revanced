package com.music.bitchord.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AudioConflictCoordinator
import com.music.bitchord.data.settings.PendingAudioConflict

/** Single conflict surface for every Quality Options / Bit-Perfect entry point. */
@Composable
fun AudioConflictDialog() {
    val pending by AudioConflictCoordinator.pending.collectAsStateWithLifecycle()
    val conflict = pending ?: return
    val message = when (conflict) {
        PendingAudioConflict.NATIVE_BIT_PERFECT ->
            "Perfect Bit Perfect bypasses Quality Options DSP. Confirm to turn " +
                "Quality Options off and enable Perfect Bit Perfect."
        PendingAudioConflict.QUALITY_OPTIONS ->
            "Perfect Bit Perfect is active. Confirm to keep it enabled with " +
                "Quality Options and its added DSP turned off."
    }
    AlertDialog(
        onDismissRequest = AudioConflictCoordinator::cancel,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Audio Conflict") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = AudioConflictCoordinator::confirm) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = AudioConflictCoordinator::cancel) {
                Text("Cancel")
            }
        },
    )
}
