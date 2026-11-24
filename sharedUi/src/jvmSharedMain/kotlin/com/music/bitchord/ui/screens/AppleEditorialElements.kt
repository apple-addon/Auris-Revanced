package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.AppleTokens
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.verticalShelfPassThrough

/** One New element, with the same controller kind and display style Apple sent. */
@Composable
fun AppleNewEditorialElement(
    element: AppleEditorialElement,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onShelfHeaderClick: (HomeShelf) -> Unit,
    onLinkClick: (AppleEditorialLink) -> Unit,
) {
    when (element.kind) {
        "316" -> AppleEditorialHeroes(element.items, element.title, onItemClick, onItemLongPress)
        "391" -> AppleEditorialLinks(element.title, element.links, onLinkClick)
        "327" -> Column(Modifier.fillMaxWidth()) {
            AppleEditorialHeader(element, onShelfHeaderClick)
            AppleEditorialTrackPages(element.items, element.title, onItemClick)
        }
        else -> Column(Modifier.fillMaxWidth()) {
            AppleEditorialHeader(element, onShelfHeaderClick)
            if (element.displayStyle == "expanded") {
                AppleNewExpandedPages(element.items, element.title, onItemClick, onItemLongPress)
            } else {
                AppleEditorialCards(
                    items = element.items,
                    shelfTitle = element.title,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    videoCards = true,
                )
            }
        }
    }
}

/** One Radio element; its cards stay square, including its video lockups. */
@Composable
fun AppleRadioEditorialElement(
    element: AppleEditorialElement,
    onItemClick: (ShelfItem, String) -> Unit,
    onLinkClick: (AppleEditorialLink) -> Unit,
) {
    when (element.kind) {
        "316" -> AppleEditorialHeroes(element.items, element.title, onItemClick, null)
        "391" -> AppleEditorialLinks(element.title, element.links, onLinkClick)
        "488" -> AppleRadioOnAir(element, onItemClick)
        else -> Column(Modifier.fillMaxWidth()) {
            // Do not drop an empty element: Local Broadcasters keeps its place.
            AppleEditorialHeading(element.title)
            if (element.displayStyle == "expanded") {
                AppleRadioExpandedPages(element.items, element.title, onItemClick)
            } else if (element.kind == "327") {
                AppleEditorialTrackPages(element.items, element.title, onItemClick)
            } else {
                AppleEditorialCards(
                    items = element.items,
                    shelfTitle = element.title,
                    onItemClick = onItemClick,
                    onItemLongPress = null,
                    videoCards = false,
                )
            }
        }
    }
}

/** A complete editorial room, kept lazy even when Apple supplies hundreds of rows. */
fun LazyListScope.appleRoomEditorialRows(
    element: AppleEditorialElement,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    item(key = "room:header:${element.id}") {
        AppleEditorialHeading(element.title)
    }
    itemsIndexed(
        items = element.items.chunked(2),
        key = { index, row -> "room:row:${element.id}:$index:${row.first().browseId ?: row.first().title}" },
    ) { _, row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            row.forEach { item ->
                AppleEditorialCard(
                    item = item,
                    shelfTitle = element.title,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    videoCard = false,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppleEditorialHeader(
    element: AppleEditorialElement,
    onClick: (HomeShelf) -> Unit,
) {
    Text(
        text = element.title + "  ›",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onClick(HomeShelf(title = element.title, items = element.items))
            }
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
internal fun AppleEditorialHeading(title: String) {
    if (title.isBlank()) return
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = 18.dp,
            bottom = 8.dp,
        ),
    )
}

/** Static feature cards: no timer, autoplay, page dots or consumed hero item. */
@Composable
private fun AppleEditorialHeroes(
    items: List<ShelfItem>,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().verticalShelfPassThrough(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { index, item -> "editorial-hero:$index:${item.browseId ?: item.title}" }) { _, item ->
            Column(Modifier.width(240.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // super_hero_aspect_ratio = 0.75.
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(AppleTokens.CornerHero))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .longPressMenuClickable(
                            onClick = { onItemClick(item, shelfTitle) },
                            onLongClick = onItemLongPress?.let { { it(item) } },
                            enabled = item.hasDestination,
                        ),
                ) {
                    AsyncImage(
                        model = (item.heroArtworkUrl ?: item.thumbnailUrl)?.artworkAt(HEADER_ART_PX),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Color(0x33000000))
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
                            text = item.designTag.ifBlank { item.title },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.description.isNotBlank() && item.description != item.designTag) {
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
                if (item.heroKind == "317" && item.footerTitle.isNotBlank()) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = item.footerArtworkUrl?.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(AppleTokens.CornerSmall)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.footerTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Apple's track swoosh: four rows in each horizontal page. */
@Composable
private fun AppleEditorialTrackPages(
    items: List<ShelfItem>,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().verticalShelfPassThrough(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items.chunked(4), key = { index, _ -> "track-page:$index" }) { _, page ->
            Column(Modifier.width(320.dp)) {
                page.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.hasDestination) { onItemClick(item, shelfTitle) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(AppleTokens.CornerSmall))
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
    }
}

/** New's expanded swoosh: two columns by two rows on every page. */
@Composable
private fun AppleNewExpandedPages(
    items: List<ShelfItem>,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().verticalShelfPassThrough(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items.chunked(4), key = { index, _ -> "new-expanded:$index" }) { _, page ->
            Column(Modifier.width(340.dp)) {
                page.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { item ->
                            AppleEditorialCard(
                                item = item,
                                shelfTitle = shelfTitle,
                                onItemClick = onItemClick,
                                onItemLongPress = onItemLongPress,
                                videoCard = false,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Radio's expanded swoosh: one column of three rows on every page. */
@Composable
private fun AppleRadioExpandedPages(
    items: List<ShelfItem>,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().verticalShelfPassThrough(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items.chunked(3), key = { index, _ -> "radio-expanded:$index" }) { _, page ->
            Column(Modifier.width(320.dp)) {
                page.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.hasDestination) { onItemClick(item, shelfTitle) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(82.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(AppleTokens.CornerMedium))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            AppleCardText(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleEditorialCards(
    items: List<ShelfItem>,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    videoCards: Boolean,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().verticalShelfPassThrough(),
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { index, item -> "editorial-card:$index:${item.browseId ?: item.title}" }) { _, item ->
            val video = videoCards && item.editorialType.lowercase() in VIDEO_TYPES
            AppleEditorialCard(
                item = item,
                shelfTitle = shelfTitle,
                onItemClick = onItemClick,
                onItemLongPress = onItemLongPress,
                videoCard = video,
                modifier = Modifier.width(if (video) 240.dp else 150.dp),
            )
        }
    }
}

@Composable
private fun AppleEditorialCard(
    item: ShelfItem,
    shelfTitle: String,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    videoCard: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AsyncImage(
            model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (videoCard) 16f / 9f else 1f)
                .clip(RoundedCornerShape(AppleTokens.CornerMedium))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .longPressMenuClickable(
                    onClick = { onItemClick(item, shelfTitle) },
                    onLongClick = onItemLongPress?.let { { it(item) } },
                    enabled = item.hasDestination,
                ),
        )
        Spacer(Modifier.height(6.dp))
        AppleCardText(item)
    }
}

@Composable
private fun AppleCardText(item: ShelfItem) {
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

@Composable
private fun AppleEditorialLinks(
    title: String,
    links: List<AppleEditorialLink>,
    onClick: (AppleEditorialLink) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        AppleEditorialHeading(title)
        links.forEach { link ->
            Text(
                text = link.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(link) }
                    .padding(horizontal = PAGE_GUTTER, vertical = 13.dp),
            )
        }
    }
}

/** Kind 488 is both the three-column logo wall and the On Air showcase. */
@Composable
private fun AppleRadioOnAir(
    element: AppleEditorialElement,
    onItemClick: (ShelfItem, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        AppleEditorialHeading(element.title)
        element.items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { item ->
                    AsyncImage(
                        model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(AppleTokens.LiveTileRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = item.hasDestination) { onItemClick(item, element.title) },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).verticalShelfPassThrough(),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(element.items, key = { index, item -> "on-air:$index:${item.stationId ?: item.title}" }) { _, item ->
                Column(
                    Modifier
                        .width(300.dp)
                        .clickable(enabled = item.hasDestination) { onItemClick(item, element.title) },
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(AppleTokens.LiveTileRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.5.dp, Color(0x1A000000), RoundedCornerShape(AppleTokens.LiveTileRadius)),
                    )
                    Spacer(Modifier.height(8.dp))
                    AppleCardText(item)
                }
            }
        }
    }
}

private val ShelfItem.hasDestination: Boolean
    get() = videoId != null || browseId != null || externalUrl != null || stationId != null

private val VIDEO_TYPES = setOf("music-video", "music-videos", "uploaded-video", "uploaded-videos", "music-movie", "music-movies")
