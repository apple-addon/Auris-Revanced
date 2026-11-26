package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioConflictCoordinator
import kotlinx.coroutines.launch

/** PAWS-style three-page surface opened from the player's Audio Output drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityOptionsSheet(
    showPresets: Boolean,
    onDismiss: () -> Unit,
    onOpenMseb: () -> Unit,
    onOpenPeq: () -> Unit,
) {
    val qualityOptions by AppSettings.qualityOptions.collectAsStateWithLifecycle()
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val titles = listOf("Equalizer & Tone", "Volume, Stereo & Tempo", "Reverb")
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Audiophile Section", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Quality Options", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Switch(checked = qualityOptions, onCheckedChange = AudioConflictCoordinator::requestQualityOptions)
            }
            ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(title) })
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) { page ->
                when (page) {
                    0 -> PowerampEqualizerPage(showPresets = showPresets, onOpenMseb = onOpenMseb, onOpenPeq = onOpenPeq)
                    1 -> PowerampVolumePage()
                    else -> PowerampReverbPage()
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextButton(onClick = onOpenMseb, modifier = Modifier.weight(1f)) { Text("MSEB") }
                TextButton(onClick = onOpenPeq, modifier = Modifier.weight(1f)) { Text("PEQ") }
            }
        }
    }
}
