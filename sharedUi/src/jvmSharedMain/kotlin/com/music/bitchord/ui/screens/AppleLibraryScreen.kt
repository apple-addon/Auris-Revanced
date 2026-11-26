package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.AppleLibraryContent
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.apple_albums
import com.music.bitchord.sharedui.resources.apple_playlists
import com.music.bitchord.sharedui.resources.apple_recently_added
import com.music.bitchord.sharedui.resources.apple_songs
import com.music.bitchord.sharedui.resources.artists
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.components.longPressMenuClickable
import org.jetbrains.compose.resources.stringResource

/**
 * Apple's Library landing page: Playlists, Artists, Albums, Songs, followed
 * only by a mixed Recently Added grid. Category navigation remains available
 * while recents load or fail; each category opens a complete page of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleLibraryScreen(
    state: UiState<AppleLibraryContent>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onCategoryClick: (String) -> Unit = {},
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String? = null,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            if (title != null) {
                item(key = "apple-library:title") {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(
                            start = PAGE_GUTTER,
                            end = PAGE_GUTTER,
                            top = 12.dp,
                            bottom = 14.dp,
                        ),
                    )
                }
            }
            item(key = "apple-library:categories") {
                Column {
                    AppleLibraryCategoryRow(
                        label = stringResource(Res.string.apple_playlists),
                        onClick = { onCategoryClick("playlists") },
                    )
                    AppleLibraryCategoryRow(
                        label = stringResource(Res.string.artists),
                        onClick = { onCategoryClick("artists") },
                    )
                    AppleLibraryCategoryRow(
                        label = stringResource(Res.string.apple_albums),
                        onClick = { onCategoryClick("albums") },
                    )
                    AppleLibraryCategoryRow(
                        label = stringResource(Res.string.apple_songs),
                        onClick = { onCategoryClick("songs") },
                    )
                }
            }
            when (val page = state) {
                is UiState.Loading -> feedSkeleton(firstIsHero = false)
                is UiState.Error -> item(key = "apple-library:error") {
                    MessageState(
                        message = page.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = onRetry,
                    )
                }
                is UiState.Success -> {
                    val content = page.data
                    if (content.recentlyAdded.isNotEmpty()) {
                        item(key = "apple-library:recent-header") {
                            AppleSectionHeader(stringResource(Res.string.apple_recently_added))
                        }
                        itemsIndexed(
                            items = content.recentlyAdded.chunked(2),
                            key = { index, row ->
                                "apple-library:recent:$index:${row.first().browseId}"
                            },
                        ) { _, row ->
                            AppleTileRow(
                                items = row,
                                onItemClick = onItemClick,
                                onItemLongPress = onItemLongPress,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleLibraryCategoryRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 13.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppleSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = 18.dp,
            bottom = 8.dp,
        ),
    )
}

/** Exactly two columns; an odd final row retains its empty second column. */
@Composable
private fun AppleTileRow(
    items: List<ShelfItem>,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEach { item ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .longPressMenuClickable(
                        onClick = { onItemClick(item) },
                        onLongClick = onItemLongPress?.let { { it(item) } },
                        enabled = item.browseId != null,
                    ),
            ) {
                AsyncImage(
                    model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        repeat((2 - items.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}
