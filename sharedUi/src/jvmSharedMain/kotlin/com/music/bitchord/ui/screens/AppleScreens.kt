package com.music.bitchord.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.AppleEditorialElement
import com.music.bitchord.data.model.AppleEditorialLink
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.AppleTokens
import com.music.bitchord.ui.components.LazyContent
import com.music.bitchord.ui.components.uniqueKeys
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.feedSkeleton
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.*
import androidx.compose.foundation.shape.CircleShape

/**
 * Apple's Home page: every Listen Now group in Apple's own order.
 *
 * A group marked as a feature row keeps all of its large cards; ordinary
 * groups remain horizontal shelves. No item is consumed as a decorative hero
 * and then omitted from the page, and Apple Home deliberately has no pull
 * gesture.
 */
@Composable
fun AppleHomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    currentSong: Song?,
    isPlaying: Boolean,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String? = null,
) {
    // Apple's Home has no pull-to-refresh. A reload keeps the last valid page
    // in the ViewModel until Apple supplies a complete replacement.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (title != null) {
            item(key = "apple:title") {
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
            is UiState.Error -> item(key = "apple:error") {
                MessageState(
                    message = page.message,
                    actionLabel = stringResource(Res.string.retry),
                    onAction = onRetry,
                )
            }
            is UiState.Success -> {
                page.data.forEachIndexed { index, shelf ->
                    appleHomeShelf(
                        index = index,
                        shelf = shelf,
                        onItemClick = onItemClick,
                        onItemLongPress = onItemLongPress,
                    )
                }
            }
        }
    }
}

/** A vertical song shelf belongs to the page's outer list, not to one eagerly composed shelf item. */
private fun LazyListScope.appleHomeShelf(
    index: Int,
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    val shelfKey = "apple:shelf:$index:${shelf.title}"
    val songList = shelf.layout != "hero" && shelf.layout != "concert" &&
        shelf.items.isNotEmpty() && shelf.items.all { it.videoId != null }
    if (songList) {
        item(key = shelfKey, contentType = LazyContent.HEADER) {
            AppleShelfHeading(shelf = shelf, onShelfHeaderClick = null)
        }
        val keys = uniqueKeys(shelf.items) { it.videoId ?: it.title }
        itemsIndexed(
            items = shelf.items,
            key = { itemIndex, _ -> "$shelfKey:song:${keys[itemIndex]}" },
            contentType = { _, _ -> LazyContent.SONG_ROW },
        ) { _, item ->
            AppleSongRow(item = item, shelfTitle = shelf.title, onItemClick = onItemClick)
        }
        return
    }
    item(
        key = shelfKey,
        contentType = if (shelf.layout == "hero") LazyContent.HERO_SHELF else LazyContent.SHELF,
    ) {
        when (shelf.layout) {
            "hero" -> AppleHeroCarousel(
                shelf = shelf,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
            )
            "concert" -> AppleConcertShelf(
                shelf = shelf,
                onItemClick = onItemClick,
            )
            else -> AppleShelfCarousel(
                shelf = shelf,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
            )
        }
    }
}

/**
 * The New page, in Apple's own shape.
 *
 * Apple's New tab keeps the ordered editorial elements and their own layout
 * hints: feature cards, track pages, compact or expanded shelves, videos and
 * real directory links. No group is reconstructed from a catalogue search.
 *
 * The content comes from Apple's catalogue through the installed manifest,
 * independently of the recommendation source used by Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleBrowseScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    /** Apple's ordered elements; legacy flat shelves are mapped into these. */
    elements: List<AppleEditorialElement>,
    onLinkClick: (AppleEditorialLink) -> Unit = { },
    /**
     * Tapping a group's heading. The chevron after the title says the row is
     * a way into the whole group, so the heading has to answer a tap.
     */
    onShelfHeaderClick: ((HomeShelf) -> Unit)? = null,
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
                item(key = "apple-browse:title") {
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
                is UiState.Error -> item(key = "apple-browse:error") {
                    MessageState(
                        message = page.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = onRetry,
                    )
                }
                is UiState.Success -> {
                    itemsIndexed(
                        elements,
                        key = { index, element -> "apple-browse:element:$index:${element.id}" },
                    ) { _, element ->
                        AppleNewEditorialElement(
                            element = element,
                            onItemClick = onItemClick,
                            onItemLongPress = onItemLongPress,
                            onShelfHeaderClick = { shelf -> onShelfHeaderClick?.invoke(shelf) },
                            onLinkClick = onLinkClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleShelfHeading(
    shelf: HomeShelf,
    onShelfHeaderClick: ((HomeShelf) -> Unit)?,
) {
    Text(
        // Apple's headings carry a chevron, because a heading over a row
        // is a way into more of it rather than a label — and it is the
        // heading that is tapped, not just the arrow after it.
        text = shelf.title + "  \u203A",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onShelfHeaderClick != null) {
                onShelfHeaderClick?.invoke(shelf)
            }
            .padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 18.dp,
                bottom = 8.dp,
            ),
    )
}

/** One song in a vertical shelf, kept as an item of the page's own lazy list. */
@Composable
private fun AppleSongRow(
    item: ShelfItem,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick(item, shelfTitle) }
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AppleTokens.CornerSmall))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (item.thumbnailUrl != null) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/**
 * One group of Apple's, as its own row.
 *
 * A heading over a horizontal run of square cards — the shape every group
 * on Apple's own page has. The heading carries the chevron Apple's headings
 * carry, because the row is a way into the group and not a dead end.
 */
@Composable
private fun AppleShelfCarousel(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onShelfHeaderClick: ((HomeShelf) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppleShelfHeading(shelf = shelf, onShelfHeaderClick = onShelfHeaderClick)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                shelf.items,
                key = { index, item -> "apple:card:$index:${item.videoId ?: item.title}" },
            ) { _, item ->
                Column(
                    modifier = Modifier.width(150.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .longPressMenuClickable(
                                onClick = { onItemClick(item, shelf.title) },
                                onLongClick = onItemLongPress?.let { { it(item) } },
                                enabled = item.videoId != null || item.browseId != null || item.externalUrl != null,
                            ),
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
        }
    }
}

/**
 * The run of large cards at the head of the page.
 *
 * Apple's own page opens with portrait cards: the cover, the title over it
 * and the artist under it. They are a run rather than one card, because the
 * page leads with several of the listener's own things.
 */
@Composable
private fun AppleHeroCarousel(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 8.dp,
                bottom = 10.dp,
            ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                shelf.items,
                key = { index, item -> "apple:hero:$index:${item.videoId ?: item.browseId ?: item.title}" },
            ) { _, item ->
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .longPressMenuClickable(
                            onClick = { onItemClick(item, shelf.title) },
                            onLongClick = onItemLongPress?.let { { it(item) } },
                            enabled = item.videoId != null || item.browseId != null || item.externalUrl != null,
                        ),
                ) {
                    AsyncImage(
                        model = (item.heroArtworkUrl ?: item.thumbnailUrl)?.artworkAt(HEADER_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp),
                    ) {
                        if (item.badge.isNotBlank()) {
                            Text(
                                text = item.badge,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.description.isNotBlank()) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Apple's full-width editorial destination row, used by Concerts. */
@Composable
private fun AppleConcertShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 18.dp,
                bottom = 8.dp,
            ),
        )
        shelf.items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = item.externalUrl != null) { onItemClick(item, shelf.title) }
                    .padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (item.description.isNotBlank() || item.subtitle.isNotBlank()) {
                        Text(
                            text = item.description.ifBlank { item.subtitle },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.badge.isNotBlank()) {
                        Text(
                            text = item.badge,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The large card at the head of the page.
 *
 * A cover with the title laid over it, the way Apple's own head card is
 * drawn — the artwork is the card, not a picture inside a card.
 */
/**
 * The two-column grid of cards.
 *
 * A row of two columns rather than a nested lazy grid: the page is a lazy
 * column already, and a grid inside one is a second scrolling surface for no
 * gain — there are four cards here, and they all fit.
 */
@Composable
private fun AppleShelfGrid(
    shelves: List<HomeShelf>,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        shelves.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                pair.forEach { shelf ->
                    val item = shelf.items.firstOrNull()
                    AppleGridCard(
                        shelf = shelf,
                        item = item,
                        onClick = { item -> onItemClick(item, shelf.title) },
                        onLongPress = onItemLongPress,
                        modifier = Modifier.weight(1f),
                    )
                }
                // A row of one card, when the number of them is odd, still
                // has to be two columns wide.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One card of the grid: a cover with the shelf's own name under it. */
@Composable
private fun AppleGridCard(
    shelf: HomeShelf,
    item: ShelfItem?,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (item != null) {
                        Modifier.longPressMenuClickable(
                            onClick = { onClick(item) },
                            onLongClick = onLongPress?.let { { it(item) } },
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (item != null) {
                AsyncImage(
                    model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!shelf.subtitle.isBlank()) {
            Text(
                text = shelf.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One row of the "More to Explore" list. */
@Composable
private fun AppleExploreRow(
    item: ShelfItem,
    shelfTitle: String,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(
                onClick = { onClick(item) },
                onLongClick = onLongPress?.let { { it(item) } },
            )
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl?.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle.ifBlank { shelfTitle },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
