package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.contextClick
import androidx.compose.ui.unit.Dp
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.music.bitchord.ui.components.longPressMenuClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.AppleTokens
import com.music.bitchord.ui.components.LazyContent
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.ui.components.verticalShelfPassThrough
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import java.util.Locale

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    currentSong: Song?,
    isPlaying: Boolean,
    onFilterChange: (SearchFilter) -> Unit,
    /**
     * The filters this build offers.
     *
     * All of them by default. An app that can switch podcasts off takes the
     * podcasts filter out of the list rather than leaving a pill that leads
     * to an empty answer.
     */
    availableFilters: List<SearchFilter> = SearchFilter.entries,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    /** Explicit dedicated-search state, independent of keyboard focus. */
    searchActive: Boolean = false,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    /**
     * Holding an album or playlist hit rather than tapping it — the same menu
     * the shelves open, so a release found by searching can go on the queue
     * without a trip through its page.
     */
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    /** Apple's genre wall, shown where the page is otherwise empty. */
    genres: List<GenreTile> = emptyList(),
    onGenreClick: (GenreTile) -> Unit = {},
    /**
     * The catalogue beneath the genre wall, drawn only while the field is at
     * rest. Once the field is tapped the page is about the search — history,
     * the source selector and what was typed — not about browsing.
     */
    catalogue: List<HomeShelf> = emptyList(),
    onCatalogueClick: (ShelfItem) -> Unit = {},
    /** The tracks that played most recently, newest first. */
    recentlyPlayed: List<Song> = emptyList(),
    /** Localised heading for the recently-played rows. */
    recentlyPlayedTitle: String = "",
    /** Long-press handler for typeahead rows — opens the song actions sheet. */
    onTypeaheadLongPress: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /**
     * Room above the search field. The phone's frosted bar floats over the top
     * of the tab, so the field has to clear it; the desktop has no such bar.
     */
    topPadding: Dp,
    /**
     * Whether the page draws its own search field. The desktop's sidebar has
     * the one search box the window needs, so the page there is results only.
     */
    showField: Boolean = true,
    /**
     * Whether the page is searching the device's Local Music folder instead of
     * YouTube. The phone's top bar flips this; the desktop never does.
     */
    searchingLibrary: Boolean = false,
    /** The Library source's tracks for [query]; null while nothing is typed. */
    libraryResults: UiState<List<Song>>? = null,
    /** Files on the device are a running order to play through, not a station seed. */
    onLibrarySongClick: (List<Song>, Int) -> Unit = onSongClick,
    /**
     * The YouTube / Library switcher, full width at the head of the page. Up
     * only while the field is empty: once something is typed the page is
     * about the results, and the switcher would just push them down.
     */
    sourceSwitcher: (@Composable () -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Apple's search page is two pages in one: the wall of genres while the
    // field rests, and the search itself — history, source, results — from the
    // moment the field is tapped. The split is the field's focus, not the
    // presence of a query: an empty field that has been tapped is still a
    // search being started, and a wall that suddenly replaced the history
    // under the cursor would be the wrong page.
    var fieldActive by remember { mutableStateOf(false) }
    val searching = searchActive || fieldActive || query.isNotEmpty()
    // Tapping the search tab from the nav bar sets focusRequested;
    // respond by focusing the field and opening the keyboard.
    LaunchedEffect(focusRequested) {
        if (focusRequested && showField) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onFocusHandled()
        }
    }
    // Search keeps one list state while its contents change. Reset it for each
    // new request so choosing a recent search cannot inherit the history's
    // previous scroll position (or a previous result page's position).
    LaunchedEffect(scrollResetTrigger) {
        if (scrollResetTrigger > 0) listState.scrollToItem(0)
    }
    // A non-empty suggestion list means the field is mid-edit — see
    // MainViewModel.suggestions. Nothing below it is worth showing while it is
    // up: the results are for whatever was searched before this edit began,
    // and so are the filter tabs above them.
    val suggesting = suggestions.isNotEmpty() && !searchingLibrary
    // Live media results arrive from the parallel typeahead pipeline; show
    // them only while the user is still typing (suggestions visible), so they
    // appear as a dropdown beneath the text completions rather than floating
    // after the search has committed.
    val showTypeahead = typeaheadResults.isNotEmpty() && !searchingLibrary
    LaunchedEffect(listState, results, loadingMore, searchingLibrary) {
        if (results !is UiState.Success || searchingLibrary) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (!loadingMore && total > 0 && lastVisible >= total - 4) onLoadMore()
        }
    }
    // Playback order, the promoted hit and section membership belong to the
    // response/filter snapshot, not to each lazy item's composition.
    val resultRows = (results as? UiState.Success)?.data.orEmpty()
    val presentation = remember(resultRows, filter) { searchPresentation(resultRows, filter) }
    Column(modifier = modifier.fillMaxSize()) {
        // Search field and filter tabs stay fixed at the top, outside the
        // scrolling list, so they're always reachable rather than scrolling
        // away with the results or recent searches beneath them.
        // The FrostedTopBar is visible on this tab (showing "Search"), so we
        // clear it fully — status bar inset + bar height + breathing gap — so
        // the search field sits cleanly below the bar instead of overlapping it.
        Column(modifier = Modifier.padding(top = topPadding)) {
            if (showField) {
                Box(
                    Modifier.onFocusChanged { state ->
                        // hasFocus covers the field's own children (the clear
                        // button, the cursor) so the page does not flip back
                        // to the wall while one of them is held.
                        fieldActive = state.isFocused || state.hasFocus
                    },
                ) {
                    SearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmit,
                        focusRequester = focusRequester,
                        placeholder = if (searchingLibrary) {
                            stringResource(Res.string.search_library_hint)
                        } else {
                            stringResource(Res.string.search_hint)
                        },
                        modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
                    )
                }
            }
            if (sourceSwitcher != null) {
                // The source belongs to the search, not to the wall: Apple,
                // Spotify, YouTube and the device's own library are choices
                // about what a query is asked of, so they sit under the field
                // only once the listener has gone to search.
                AnimatedVisibility(
                    visible = searching && query.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Box(Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp)) {
                        sourceSwitcher()
                    }
                }
            }
            // The filters only mean something once there is a result set to narrow;
            // they stay up for an empty or failed search too, or picking a filter
            // that finds nothing would take away the control needed to leave it.
            // They are YouTube's categories, so the Library source has none.
            if (results != null && !suggesting && !searchingLibrary) {
                SearchFilterTabs(
                    filter = filter,
                    onFilterChange = onFilterChange,
                    availableFilters = availableFilters,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            when {
                searchingLibrary -> librarySearchResults(
                    query = query.trim(),
                    results = libraryResults,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = onLibrarySongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                )
                suggesting || showTypeahead -> {
                    if (suggesting) searchSuggestions(
                        suggestions = suggestions,
                        // Picking one is done typing, so the keyboard comes down
                        // with it and the results get the whole screen.
                        onClick = { term ->
                            onSuggestionClick(term)
                            focusManager.clearFocus()
                        },
                        onFill = onQueryChange,
                    )
                    if (showTypeahead) {
                        searchTypeaheadDropdown(
                            typeaheadResults = typeaheadResults,
                            onSongClick = { song -> onTopResultPlay(song) },
                            onSongLongPress = onTypeaheadLongPress,
                            onBrowseClick = { item ->
                                onBrowseClick(item)
                            },
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                        )
                    }
                }
                results == null -> {
                    if (searching) {
                        // Apple's page, once the field is tapped: recently
                        // searched, with a Clear at the heading. No recently
                        // played rows and no second list under it.
                        if (history.isNotEmpty()) {
                            recentSearches(
                                history = history,
                                currentSong = currentSong,
                                isPlaying = isPlaying,
                                onClick = onHistoryClick,
                                onRemove = onHistoryRemove,
                                onClear = onHistoryClear,
                            )
                        }
                        if (history.isEmpty()) {
                            item { MessageState(stringResource(Res.string.search_empty)) }
                        }
                    }
                    if (!searching && genres.isNotEmpty()) {
                        itemsIndexed(
                            items = genres.chunked(2),
                            key = { index, row -> "genre-row:$index:${row.first().id}" },
                        ) { _, row ->
                            GenreGridRow(row = row, onGenreClick = onGenreClick)
                        }
                    }
                    if (genres.isEmpty() && !searching) {
                        item { MessageState(stringResource(Res.string.search_empty)) }
                    }
                }
                results is UiState.Loading -> songListSkeleton(circular = filter == SearchFilter.ARTISTS)
                results is UiState.Error -> item { MessageState(results.message) }
                results is UiState.Success -> {
                    val tracks = presentation.tracks
                    val topResult = presentation.topResult
                    if (filter == SearchFilter.ALL && topResult != null) {
                        item(key = "search:top-result:${topResult.song.videoId}") {
                            TopResultCard(
                                song = topResult.song,
                                onPlay = { onTopResultPlay(topResult.song) },
                                onPlaylist = { onTopResultPlaylist(topResult.song) },
                                onLongPress = { onSongLongPress(topResult.song) },
                            )
                        }
                    }
                    presentation.sections.forEach { section ->
                        section.title?.let { title ->
                            item(key = "search-section:$title") {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(
                                        start = PAGE_GUTTER,
                                        end = PAGE_GUTTER,
                                        top = 16.dp,
                                        bottom = 6.dp,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        itemsIndexed(
                            items = section.rows,
                            key = { index, row ->
                                when (row) {
                                    is SearchResult.TopTrack -> "top_${row.song.videoId}_$index"
                                    is SearchResult.Track -> "track_${row.song.videoId}_$index"
                                    is SearchResult.Browse -> "browse_${row.item.browseId}_$index"
                                }
                            },
                            contentType = { _, row -> row.lazyContentType() },
                        ) { index, row ->
                            when (row) {
                                is SearchResult.TopTrack -> Unit
                                is SearchResult.Track -> SongRow(
                                    song = row.song,
                                    onClick = {
                                        onSongClick(tracks, tracks.indexOf(row.song).coerceAtLeast(0))
                                    },
                                    onLongPress = { onSongLongPress(row.song) },
                                    onSwipeToQueue = { onSongSwipe(row.song) },
                                    isCurrent = row.song.isSameTrackAs(currentSong),
                                    isPlaying = isPlaying && row.song.isSameTrackAs(currentSong),
                                    searchPlayingStyle = true,
                                    activeTint = PlayingAccent,
                                )
                                is SearchResult.Browse -> BrowseRow(
                                    item = row.item,
                                    onClick = { onBrowseClick(row.item) },
                                    onLongPress = onBrowseLongPress?.let { { it(row.item) } },
                                )
                            }
                            if (index < section.rows.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    if (loadingMore) {
                        songListSkeleton(
                            count = 3,
                            keyPrefix = "skeleton:search:more",
                            circular = filter == SearchFilter.ARTISTS,
                        )
                    }
                }
            }
        }
    }
}

internal data class SearchSection(val title: String?, val rows: List<SearchResult>)

internal data class SearchPresentation(
    val tracks: List<Song>,
    val topResult: SearchResult.TopTrack?,
    val sections: List<SearchSection>,
)

/**
 * Everything the result list needs from one snapshot, partitioned in one pass.
 * The unfiltered page is useful only when its mixed result types are readable at a glance.
 */
internal fun searchPresentation(rows: List<SearchResult>, filter: SearchFilter): SearchPresentation {
    val tracks = mutableListOf<Song>()
    var topResult: SearchResult.TopTrack? = null
    val songs = mutableListOf<SearchResult>()
    val artists = mutableListOf<SearchResult>()
    val albums = mutableListOf<SearchResult>()
    val playlists = mutableListOf<SearchResult>()
    val more = mutableListOf<SearchResult>()
    rows.forEach { row ->
        when (row) {
            is SearchResult.TopTrack -> {
                tracks += row.song
                if (topResult == null) topResult = row
            }
            is SearchResult.Track -> {
                tracks += row.song
                songs += row
            }
            is SearchResult.Browse -> when (row.item.type) {
                BrowseType.ARTIST -> artists += row
                BrowseType.ALBUM -> albums += row
                BrowseType.PLAYLIST -> playlists += row
                BrowseType.OTHER -> more += row
                BrowseType.PODCAST -> Unit
            }
        }
    }
    val sections = if (filter == SearchFilter.ALL) {
        listOf(
            SearchSection("Songs", songs),
            SearchSection("Artists", artists),
            SearchSection("Albums", albums),
            SearchSection("Playlists", playlists),
            SearchSection("More", more),
        ).filter { it.rows.isNotEmpty() }
    } else {
        listOf(SearchSection(null, rows))
    }
    return SearchPresentation(tracks = tracks, topResult = topResult, sections = sections)
}

/**
 * The Library source's page: the Local Music folder's tracks that match, as
 * plain song rows. An artist or album hit needs no row of its own — every
 * track under that name matches too, so they are already here.
 */
private fun LazyListScope.librarySearchResults(
    query: String,
    results: UiState<List<Song>>?,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
) {
    when (results) {
        null -> item(key = "library:empty") {
            MessageState(stringResource(Res.string.search_library_empty))
        }
        UiState.Loading -> songListSkeleton(keyPrefix = "skeleton:search:library")
        is UiState.Error -> item(key = "library:error") { MessageState(results.message) }
        is UiState.Success -> if (results.data.isEmpty()) {
            item(key = "library:no-match") {
                MessageState(stringResource(Res.string.search_library_no_results, query))
            }
        } else {
            val tracks = results.data
            itemsIndexed(
                tracks,
                key = { index, song -> "library_${song.videoId}_$index" },
                contentType = { _, _ -> LazyContent.SONG_ROW },
            ) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onSongClick(tracks, index) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    isCurrent = song.isSameTrackAs(currentSong),
                    isPlaying = isPlaying && song.isSameTrackAs(currentSong),
                    searchPlayingStyle = true,
                    activeTint = PlayingAccent,
                )
                if (index < tracks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** The All response carries its highest-confidence music hit as a promoted card. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopResultCard(
    song: Song,
    onPlay: () -> Unit,
    onPlaylist: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 18.dp, bottom = 8.dp)
            .longPressMenuClickable(onClick = onPlay, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        Text(
            text = "Top result",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = song.thumbnailUrl?.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowMoreButton(
                onClick = onLongPress,
                tint = MaterialTheme.colorScheme.onSurface,
                iconSize = 24.dp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onPlay,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.play))
            }
            OutlinedButton(
                onClick = onPlaylist,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.playlist_action))
            }
        }
    }
}

/**
 * What YouTube would complete the half-typed query to, in place of the results
 * while it is being typed.
 *
 * The first row is the text as typed, put there by the view model rather than
 * taken from YouTube's answer, so running exactly what was asked for is always
 * the nearest row to the keyboard rather than something the thumb has to aim
 * past.
 */
private fun LazyListScope.searchSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    // This is a list-level inset rather than padding hidden inside the first
    // row. It keeps the gap under the field stable even when that row changes
    // its text or icon treatment.
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    // Skip the echo of the typed text (element 0) — it's already visible in
    // the search field itself — and cap at N so the list stays compact above
    // the playable-media cards.
    itemsIndexed(
        suggestions.drop(1).take(3),
        key = { _, term -> "suggest:$term" },
        contentType = { _, _ -> "suggestion" },
    ) { _, term ->
        SuggestionRow(
            term = term,
            isQueryAction = false,
            onFill = { onFill(term) },
            onClick = { onClick(term) },
        )
    }
}

/**
 * One typeahead row: tap the text to search it, or the arrow to put it in the
 * field and carry on typing — the pair YouTube, Google and every mobile
 * keyboard's own suggestion strip use, and the reason a longer completion
 * isn't a dead end when it's only nearly right.
 */
@Composable
private fun SuggestionRow(
    term: String,
    isQueryAction: Boolean,
    onFill: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = PAGE_GUTTER,
                end = 8.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            // The first row is the deliberate action to search the exact text
            // in the field, not a server-provided completion. Naming it makes
            // the otherwise duplicated wording read as intentional.
            text = if (isQueryAction) """${stringResource(Res.string.search)} "$term"""" else term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onFill != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.NorthWest,
                    contentDescription = stringResource(Res.string.recent_search_edit, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            // Match the arrow button's full touch target, not only its width.
            // A width-only spacer made the first row shorter than the ones
            // below, so its vertical rhythm looked visibly uneven.
            Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * Hybrid dropdown shown beneath text suggestions while typing: a horizontal
 * divider, then live media rows (cover art + title + subtitle) that play on
 * tap and open the song menu on long-press.
 */
private fun LazyListScope.searchTypeaheadDropdown(
    typeaheadResults: List<SearchResult>,
    onSongClick: (Song) -> Unit,
    onSongLongPress: ((Song) -> Unit)?,
    onBrowseClick: (BrowseItem) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
) {
    item(key = "typeahead:divider") {
        HorizontalDivider(
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }, contentType = { it.lazyContentType() }) { result ->
        when (result) {
            is SearchResult.Track -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = result.song.isSameTrackAs(currentSong),
                isPlaying = isPlaying && result.song.isSameTrackAs(currentSong),
            )
            is SearchResult.Browse -> BrowseRow(
                item = result.item,
                onClick = { onBrowseClick(result.item) },
                onLongPress = onSongLongPress?.let { { /* browse long-press not applicable */ } },
            )
            is SearchResult.TopTrack -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
                isCurrent = result.song.isSameTrackAs(currentSong),
                isPlaying = isPlaying && result.song.isSameTrackAs(currentSong),
            )
        }
    }
}

/**
 * A single media row inside the typeahead dropdown: 52dp cover art, title,
 * and artist/album subtitle. Tap plays the track; long-press opens the menu.
 */
@Composable
private fun TypeaheadSongRow(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(song.artist).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What was searched for before, shown in place of the results while the field
 * is empty — the same spot Spotify and Apple Music put it, and the reason the
 * blank search page isn't just a sentence any more.
 */
private fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntity>,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (SearchHistoryEntity) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "recent:${it.id}" }, contentType = { "recent-search" }) { entity ->
        RecentSearchEntityRow(
            entity = entity,
            isCurrent = entity.entityType == EntityType.TRACK && entity.id == currentSong?.videoId,
            isPlaying = isPlaying,
            onClick = { onClick(entity) },
            onRemove = { onRemove(entity.id) },
        )
    }
}

/**
 * Spotify-style entity row: square thumbnail, bold title, subtitle with type,
 * and a removal button. Tapping navigates to the entity or plays it directly.
 */
@Composable
private fun RecentSearchEntityRow(
    entity: SearchHistoryEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = PAGE_GUTTER, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) {
            AsyncImage(
                model = entity.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .thumbnailBorder(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) {
                SearchPlayingBars(Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entity.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.subtitle.ifBlank { entity.entityType.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.recent_search_remove, entity.title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(item: BrowseItem, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else RoundedCornerShape(8.dp),
                )
                .thumbnailBorder(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else RoundedCornerShape(8.dp),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle.ifBlank { item.type.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected
 * one inverted. They scroll horizontally so a long label set never squeezes the
 * text, and the gutter padding sits inside the scroll so it scrolls with them.
 */
@Composable
private fun SearchFilterTabs(
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    availableFilters: List<SearchFilter> = SearchFilter.entries,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .verticalShelfPassThrough()
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        availableFilters.forEach { entry ->
            val selected = entry == filter
            Box(
                modifier = Modifier
                    .clip(FILTER_PILL_SHAPE)
                    .background(
                        if (selected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // Only the pill that isn't already selected has anything to
                    // report — re-tapping the current filter changes nothing, so
                    // buzzing for it would be feedback for a no-op.
                    .clickable {
                        if (!selected) haptics.play(Haptic.Select)
                        onFilterChange(entry)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Rounded, but well short of a capsule — the corner reads as a cut, not a curve. */
private val FILTER_PILL_SHAPE = RoundedCornerShape(12.dp)

/** Tracks and browse results draw differently, so a slot is only reused within its kind. */
private fun SearchResult.lazyContentType(): String = when (this) {
    is SearchResult.TopTrack -> "top-result"
    is SearchResult.Track -> LazyContent.SONG_ROW
    is SearchResult.Browse -> LazyContent.BROWSE_ROW
}


/**
 * Apple's genre wall: two across, the way Apple's own search page opens.
 *
 * Artwork comes from the addon where it has any. A genre without art gets a
 * stable gradient of its own rather than a hole, so the wall is whole.
 */
@Composable
private fun GenreGridRow(row: List<GenreTile>, onGenreClick: (GenreTile) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = AppleTokens.GridGutter),
    ) {
        row.forEachIndexed { index, genre ->
            GenreCard(
                genre = genre,
                modifier = Modifier.weight(1f),
                onClick = { onGenreClick(genre) },
            )
            if (index != row.lastIndex) Spacer(Modifier.width(AppleTokens.GridGutter))
        }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun GenreCard(genre: GenreTile, modifier: Modifier, onClick: () -> Unit) {
    val art = genre.artworkUrl
    Box(
        modifier = modifier
            .aspectRatio(AppleTokens.SearchTileAspect)
            .clip(RoundedCornerShape(AppleTokens.CornerMedium))
            .clickable(onClick = onClick)
            // A Brush on both branches: an artwork tile sits on a transparent
            // gradient rather than a Color, which would make the argument
            // `Any` and fail to resolve.
            .background(
                if (art.isNullOrBlank()) {
                    genreBrush(genre.name)
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                },
            ),
    ) {
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f)),
                    ),
                ),
        )
        Text(
            text = genre.name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = AppleTokens.TileLabelSize,
            ),
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

/** One genre tile as the search page draws it. */
data class GenreTile(
    val name: String,
    val artworkUrl: String? = null,
    /** The curator id behind the tile, when the addon published one. */
    val id: String = "",
)

/** Apple's tile palette, used when the addon has no artwork for a genre. */
private val GENRE_COLORS = listOf(
    Color(0xFFE8447A),
    Color(0xFF7A5CE0),
    Color(0xFF3C8CE7),
    Color(0xFF22A06B),
    Color(0xFFE07B39),
    Color(0xFFB03CE0),
    Color(0xFF2FB6C4),
    Color(0xFFD0365A),
)

private fun genreBrush(name: String): Brush {
    val hash = name.hashCode()
    val first = GENRE_COLORS[(hash and 0x7fffffff) % GENRE_COLORS.size]
    val second = GENRE_COLORS[((hash / 7) and 0x7fffffff) % GENRE_COLORS.size]
    return Brush.linearGradient(listOf(first, second))
}

/** Recently played rows: the search page's answer to an empty field. */
private fun LazyListScope.recentlyPlayedRows(
    songs: List<Song>,
    title: String,
    currentSong: Song?,
    isPlaying: Boolean,
    onClick: (Int) -> Unit,
) {
    item(key = "recently-played:header") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 14.dp,
                bottom = 4.dp,
            ),
        )
    }
    itemsIndexed(
        songs,
        key = { index, song -> "recently-played:$index:${song.videoId}" },
    ) { index, song ->
        val current = song.isSameTrackAs(currentSong)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(index) }
                .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp)) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (current && isPlaying) {
                    SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (current) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One lockup of the catalogue row under the genre wall.
 *
 * A square tile with its two lines, the shape Apple's browse rows use. The
 * card opens whatever the shelf said the row opens — an album's page, a
 * playlist's page, a station.
 */
@Composable
private fun CatalogueCard(
    item: ShelfItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
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
        Spacer(Modifier.height(4.dp))
    }
}
