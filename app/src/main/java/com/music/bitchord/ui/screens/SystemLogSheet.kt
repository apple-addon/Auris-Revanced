package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.diagnostics.SystemLog
import androidx.compose.ui.res.stringResource

/**
 * The report, shown and sent.
 *
 * The listener is asked to send this when something is wrong, so the sheet
 * does three things and no more: it shows what will be sent, it lets the
 * system log be left out, and it copies or shares the whole thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemLogSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var withSystemLog by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(true) }

    LaunchedEffect(withSystemLog) {
        working = true
        text = runCatching { SystemLog.build(context, withSystemLog) }
            .getOrElse { "The report could not be built: ${it.message}" }
        working = false
    }

    // Read once, here: a composable call cannot be made from inside the
    // coroutine that copies or shares the report.
    val copyLabel = stringResource(R.string.system_log_copy)
    val shareLabel = stringResource(R.string.system_log_share)
    val withoutSystemLabel = stringResource(R.string.system_log_without_system)
    val withSystemLabel = stringResource(R.string.system_log_with_system)
    val shareChooser = stringResource(R.string.system_log_share)
    val reportSubject = stringResource(R.string.system_log_title)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.system_log_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.system_log_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setText(AnnotatedString(text))
                        onDismiss()
                    }
                }) {
                    Text(copyLabel)
                }
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, reportSubject)
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(send, shareChooser)
                        )
                    }
                }) {
                    Text(shareLabel)
                }
                TextButton(onClick = { withSystemLog = !withSystemLog }) {
                    Text(if (withSystemLog) withoutSystemLabel else withSystemLabel)
                }
            }
            Text(
                text = if (working) stringResource(R.string.system_log_working) else text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 40,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}
