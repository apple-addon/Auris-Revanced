package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.music.bitchord.data.model.AppleEditorialElement
import com.music.bitchord.data.model.AppleEditorialLink
import com.music.bitchord.data.model.AppleRadioRow
import com.music.bitchord.data.model.AppleRadioShelf
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.AppleTokens
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.feedSkeleton
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.*

/**
 * Apple's ordered Radio page. No synthetic station sections are added: the
 * legacy shelf list is drawn only when an older addon supplies no elements.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleRadioScreen(
    state: UiState<List<AppleEditorialElement>>,
    shelves: List<AppleRadioShelf> = emptyList(),
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onLinkClick: (AppleEditorialLink) -> Unit = { },
    onRowClick: (AppleRadioRow) -> Unit = { },
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
                item(key = "apple-radio:title") {
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
            when (val page = state) {
                is UiState.Loading -> feedSkeleton(firstIsHero = false)
                is UiState.Error -> item(key = "apple-radio:error") {
                    MessageState(
                        message = page.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = onRetry,
                    )
                }
                is UiState.Success -> {
                    if (page.data.isNotEmpty()) {
                        itemsIndexed(
                            page.data,
                            key = { index, element -> "apple-radio:element:$index:${element.id}" },
                        ) { _, element ->
                            AppleRadioEditorialElement(
                                element = element,
                                onItemClick = onItemClick,
                                onLinkClick = onLinkClick,
                            )
                        }
                    } else {
                        itemsIndexed(
                            shelves,
                            key = { index, shelf -> "apple-radio:legacy:$index:${shelf.title}" },
                        ) { _, shelf ->
                            Column(Modifier.fillMaxWidth()) {
                                AppleRadioHeader(shelf.title)
                                AppleRadioCarousel(rows = shelf.items, onRowClick = onRowClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleRadioHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = 20.dp,
            bottom = 10.dp,
        ),
    )
}

/** The old addon's flat Radio shelf, retained strictly as a fallback. */
@Composable
private fun AppleRadioCarousel(
    rows: List<AppleRadioRow>,
    onRowClick: (AppleRadioRow) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(rows, key = { "apple-radio:carousel:${it.id}" }) { row ->
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onRowClick(row) },
            ) {
                AsyncImage(
                    model = row.artworkUrl?.artworkAt(CARD_ART_PX),
                    contentDescription = row.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(AppleTokens.CornerMedium))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val under = row.provider ?: row.subtitle
                if (!under.isNullOrBlank()) {
                    Text(
                        text = under,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
