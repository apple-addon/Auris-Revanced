package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.EditorialHero
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.topBarContentPadding
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.theme.ArtworkPalette

private const val SONGS_PER_GENRE_COLUMN = 4

/** Apple's genre title and feature carousel, rather than a release's huge cover. */
@Composable
internal fun EditorialGenreHeader(
    title: String,
    heroes: List<EditorialHero>,
    palette: ArtworkPalette,
    onItemClick: (ShelfItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = palette.onBackground,
            modifier = Modifier.padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = topBarContentPadding() + 8.dp,
                bottom = 18.dp,
            ),
        )
        if (heroes.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val heroWidth = minOf(maxWidth * 0.82f, 360.dp)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(
                        items = heroes,
                        key = { index, hero -> "$index:${hero.item.browseId}:${hero.item.title}" },
                    ) { _, hero ->
                        EditorialGenreHero(
                            hero = hero,
                            palette = palette,
                            onClick = { onItemClick(hero.item) },
                            modifier = Modifier.width(heroWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialGenreHero(
    hero: EditorialHero,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clickable(
            enabled = hero.item.videoId != null || hero.item.browseId != null || hero.item.externalUrl != null,
            onClick = onClick,
        ),
    ) {
        if (hero.badge.isNotBlank()) {
            Text(
                text = hero.badge.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = hero.item.title,
            style = MaterialTheme.typography.titleLarge,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hero.item.subtitle.isNotBlank()) {
            Text(
                text = hero.item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.65f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.elevated),
        ) {
            AsyncImage(
                model = hero.item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (hero.description.isNotBlank()) {
                Text(
                    text = hero.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** One genre shelf: paged track rows, circular artists, or catalogue cards. */
@Composable
internal fun EditorialGenreShelf(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    currentSong: Song?,
    isPlaying: Boolean,
    onItemClick: (ShelfItem) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    onShowAll: () -> Unit,
) {
    val songShelf = shelf.items.isNotEmpty() && shelf.items.all { it.videoId != null }
    Column(Modifier.padding(top = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onShowAll)
                .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shelf.title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackgroundVariant,
            )
        }
        if (songShelf) {
            EditorialGenreSongs(
                items = shelf.items,
                palette = palette,
                currentSong = currentSong,
                isPlaying = isPlaying,
                onItemClick = onItemClick,
                onSongLongPress = onSongLongPress,
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(
                    items = shelf.items,
                    key = { index, item -> "$index:${item.browseId}:${item.title}" },
                ) { _, item ->
                    EditorialGenreCard(
                        item = item,
                        palette = palette,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorialGenreSongs(
    items: List<ShelfItem>,
    palette: ArtworkPalette,
    currentSong: Song?,
    isPlaying: Boolean,
    onItemClick: (ShelfItem) -> Unit,
    onSongLongPress: (Song) -> Unit,
) {
    val columns = remember(items) { items.chunked(SONGS_PER_GENRE_COLUMN) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = trackColumnWidth(maxWidth)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = columns,
                key = { index, column -> "$index:${column.firstOrNull()?.videoId}" },
            ) { _, column ->
                Column(Modifier.width(columnWidth)) {
                    column.forEach { item ->
                        EditorialGenreSongRow(
                            item = item,
                            palette = palette,
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            onClick = { onItemClick(item) },
                            onLongPress = onSongLongPress,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialGenreSongRow(
    item: ShelfItem,
    palette: ArtworkPalette,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongPress: (Song) -> Unit,
) {
    val videoId = item.videoId ?: return
    val song = remember(item) {
        Song(
            videoId = videoId,
            title = item.title,
            artist = item.subtitle,
            thumbnailUrl = item.thumbnailUrl,
        )
    }
    val current = currentSong?.videoId == videoId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(
                onClick = onClick,
                onLongClick = { onLongPress(song) },
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            AsyncImage(
                model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .thumbnailBorder(RoundedCornerShape(6.dp))
                    .background(palette.elevated),
            )
            if (current && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) palette.accent else palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowMoreButton(
            onClick = { onLongPress(song) },
            tint = palette.onBackgroundVariant,
        )
    }
}

@Composable
private fun EditorialGenreCard(
    item: ShelfItem,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val artist = item.browseId?.startsWith("apple:artist:") == true
    val video = item.externalUrl != null
    val shape = if (artist) CircleShape else RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            // Apple draws video bricks at 16:9; keep their height level with
            // the square album cards rather than cropping them into squares.
            .width(if (video) SHELF_CARD_WIDTH * 16f / 9f else SHELF_CARD_WIDTH)
            .longPressMenuClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                enabled = item.videoId != null || item.browseId != null || item.externalUrl != null,
            ),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (video) 16f / 9f else 1f)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(palette.elevated),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = if (artist) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (artist) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}
