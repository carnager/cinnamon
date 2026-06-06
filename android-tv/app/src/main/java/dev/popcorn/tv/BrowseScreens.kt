package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable
fun HomeView(
    session: Session?,
    libraries: List<Library>,
    items: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    continueMovies: List<PopItem>,
    continueEpisodes: List<PopItem>,
    recentMovies: List<PopItem>,
    recentShows: List<ShowSummary>,
    watchlistMovies: List<PopItem>,
    watchlistTvShows: List<ShowSummary>,
    error: String,
    loading: Boolean,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    AppChrome(session, libraries, selected = "home", showUpdate = showUpdate, onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onUpdates = onUpdates, onScan = onScan, onLogout = onLogout) {
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        } else {
            CuratedLanding(session, items, shows, completedItems, completedShows, watchlistItems, watchlistShows, continueMovies, continueEpisodes, recentMovies, recentShows, watchlistMovies, watchlistTvShows, onItem, onShow, onItemMenu, onShowMenu)
        }
    }
}

@Composable
fun AppChrome(
    session: Session?,
    libraries: List<Library>,
    selected: String,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    navFocusRequester: FocusRequester? = null,
    onSideNavigationExit: (() -> Boolean)? = null,
    suppressSideNavigationExpansion: Boolean = false,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val fallbackNavFocusRequester = remember { FocusRequester() }
    val resolvedNavFocusRequester = navFocusRequester ?: fallbackNavFocusRequester
    Row(Modifier.fillMaxSize()) {
        SideNavigation(libraries, selected, onHome, onLibrary, onWatchlist, onSearch, resolvedNavFocusRequester, onSideNavigationExit, suppressSideNavigationExpansion)
        Column(Modifier.fillMaxSize()) {
            if (topBar != null) {
                topBar()
            } else {
                PageTopActions(session, showUpdate, onUpdates, onScan, onLogout)
            }
            content()
        }
    }
}

@Composable
fun SideNavigation(libraries: List<Library>, selected: String, onHome: () -> Unit, onLibrary: (Library) -> Unit, onWatchlist: () -> Unit, onSearch: () -> Unit, firstFocusRequester: FocusRequester, onExit: (() -> Boolean)? = null, expansionSuppressed: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val expansionAllowed by rememberUpdatedState(!expansionSuppressed)
    val width by animateDpAsState(if (expanded) 190.dp else 66.dp, label = "nav-width")
    val movieLibrary = libraries.firstOrNull { it.type == "movies" || it.type == "movie" }
    val tvLibrary = libraries.firstOrNull { it.type == "tv" }
    LaunchedEffect(hasFocus) {
        if (hasFocus) {
            delay(140)
            if (expansionAllowed) expanded = true
        } else {
            expanded = false
        }
    }
    LaunchedEffect(expansionSuppressed) {
        if (expansionSuppressed) expanded = false
    }
    Column(
        Modifier
            .width(width)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .52f))
            .border(1.dp, Color.White.copy(alpha = .10f))
            .onFocusChanged { hasFocus = it.hasFocus }
            .padding(horizontal = 10.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("P", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 18.dp))
        SideNavigationItem(icon = Icons.Filled.Home, label = "Home", expanded = expanded, selected = selected == "home", focusRequester = firstFocusRequester, onRight = onExit, onClick = onHome)
        if (movieLibrary != null) {
            SideNavigationItem(icon = Icons.Filled.Movie, label = movieLibrary.name, expanded = expanded, selected = selected == movieLibrary.id, onRight = onExit, onClick = { onLibrary(movieLibrary) })
        }
        if (tvLibrary != null) {
            SideNavigationItem(icon = Icons.Filled.LiveTv, label = tvLibrary.name, expanded = expanded, selected = selected == tvLibrary.id, onRight = onExit, onClick = { onLibrary(tvLibrary) })
        }
        SideNavigationItem(icon = Icons.Filled.Bookmark, label = "Watchlist", expanded = expanded, selected = selected == "watchlist", onRight = onExit, onClick = onWatchlist)
        SideNavigationItem(icon = Icons.Filled.Search, label = "Search", expanded = expanded, selected = selected == "search", onRight = onExit, onClick = onSearch)
    }
}

@Composable
fun SideNavigationItem(icon: ImageVector, label: String, expanded: Boolean, selected: Boolean, focusRequester: FocusRequester? = null, onRight: (() -> Boolean)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused && selected -> Accent.copy(alpha = .94f)
        focused -> Color.White.copy(alpha = .16f)
        selected -> Accent.copy(alpha = .30f)
        else -> Color.Transparent
    }
    val contentColor = if (focused && selected) Color.Black else TextColor
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, if (focused) Color.White.copy(alpha = .78f) else if (selected) Accent.copy(alpha = .48f) else Color.Transparent, RoundedCornerShape(10.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && onRight != null) {
                    onRight()
                } else {
                    false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp).width(28.dp),
        )
        if (expanded) {
            Text(label, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PageTopActions(session: Session?, showUpdate: Boolean, onUpdates: () -> Unit, onScan: () -> Unit, onLogout: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = .46f))
            .padding(horizontal = 26.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Popcorn", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        UserMenuButton(session, showUpdate, onUpdates, onScan, onLogout)
    }
}

@Composable
fun TopBar(session: Session?, libraries: List<Library>, selected: String, showUpdate: Boolean, onHome: () -> Unit, onLibrary: (Library) -> Unit, onWatchlist: () -> Unit, onSearch: () -> Unit, onUpdates: () -> Unit, onLogout: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = .46f))
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Popcorn", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(12.dp))
        Pill(text = "Home", selected = selected == "home", onClick = onHome)
        libraries.forEach { library ->
            Pill(text = library.name, selected = selected == library.id, onClick = { onLibrary(library) })
        }
        Pill(text = "Watchlist", selected = selected == "watchlist", onClick = onWatchlist)
        if (showUpdate) {
            Pill(text = "Update", selected = selected == "updates", onClick = onUpdates)
        }
        Spacer(Modifier.weight(1f))
        if (!session?.username.isNullOrBlank()) {
            Text(session?.username.orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Pill("Search", selected = false, onClick = onSearch)
        Pill("Logout", selected = false, onClick = onLogout)
    }
}

@Composable
fun WatchlistView(
    session: Session?,
    libraries: List<Library>,
    movies: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    error: String,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var initialFocusPending by remember(movies.firstOrNull()?.id, shows.firstOrNull()?.title) { mutableStateOf(true) }
    AppChrome(session, libraries, selected = "watchlist", showUpdate = showUpdate, onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onUpdates = onUpdates, onScan = onScan, onLogout = onLogout) {
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (movies.isEmpty() && shows.isEmpty()) {
            EmptyState("No watchlist items")
        } else {
            LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GridSectionHeader("Watchlist", "${movies.size} movies · ${shows.size} shows")
            }
            if (movies.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GridSectionHeader("Movies", "${movies.size} saved", compact = true)
                }
                gridItemsIndexed(movies, key = { _, item -> "movie:${item.id}" }) { index, item ->
                    val focusNow = initialFocusPending && index == 0
                    ItemCard(
                        session = session,
                        item = item,
                        watched = completedItems.contains(item.id),
                        watchlisted = watchlistItems.contains(item.id),
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialFocusPending = false },
                        onClick = { onItem(item) },
                        onLongClick = { requester -> onItemMenu(item, requester) },
                    )
                }
            }
            if (shows.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GridSectionHeader("TV Shows", "${shows.size} saved", compact = true)
                }
                gridItemsIndexed(shows, key = { _, show -> "show:${show.libraryId}:${show.title}" }) { index, show ->
                    val focusNow = initialFocusPending && movies.isEmpty() && index == 0
                    ShowCard(
                        session = session,
                        show = show,
                        watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                        watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialFocusPending = false },
                        onClick = { onShow(show) },
                        onLongClick = { requester -> onShowMenu(show, requester) },
                    )
                }
            }
        }
        }
    }
}

@Composable
fun GridSectionHeader(title: String, subtitle: String, compact: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 8.dp else 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = TextColor, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
fun LibraryPageView(
    session: Session?,
    libraries: List<Library>,
    activeLibrary: Library,
    items: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    error: String,
    loading: Boolean,
    pageIndex: Int,
    pageHasNext: Boolean,
    selectedGenre: String,
    selectedSort: String,
    selectedMinRating: Double,
    selectedSeenStatus: String,
    genres: List<String>,
    alphabet: List<AlphabetEntry>,
    initialFocusKey: Any?,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onGenre: (String) -> Unit,
    onSort: (String) -> Unit,
    onMinRating: (Double) -> Unit,
    onSeenStatus: (String) -> Unit,
    onAlphabet: (AlphabetEntry) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var restoreGridFocus by remember(activeLibrary.id, pageIndex, selectedGenre) { mutableStateOf<FocusRequester?>(null) }
    var focusedBackdrop by remember(activeLibrary.id) { mutableStateOf<BrowseBackdropPreview?>(null) }
    var suppressSideNavigationExpansion by remember { mutableStateOf(false) }
    val navFocusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    val alphabetFocusRequester = remember { FocusRequester() }
    LaunchedEffect(suppressSideNavigationExpansion) {
        if (suppressSideNavigationExpansion) {
            delay(900)
            suppressSideNavigationExpansion = false
        }
    }
    LaunchedEffect(loading, suppressSideNavigationExpansion, alphabet.size, selectedSort) {
        if (suppressSideNavigationExpansion && !loading && selectedSort.isBlank() && alphabet.isNotEmpty()) {
            delay(120)
            runCatching { alphabetFocusRequester.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        BrowseBackdropLayer(session, focusedBackdrop)
        AppChrome(
            session,
            libraries,
            selected = activeLibrary.id,
            showUpdate = showUpdate,
            onHome = onHome,
            onLibrary = onLibrary,
            onWatchlist = onWatchlist,
            onSearch = onSearch,
            onUpdates = onUpdates,
            onScan = onScan,
            onLogout = onLogout,
            navFocusRequester = navFocusRequester,
            onSideNavigationExit = {
                val target = restoreGridFocus
                if (target != null) {
                    target.requestFocus()
                    true
                } else {
                    false
                }
            },
            suppressSideNavigationExpansion = suppressSideNavigationExpansion,
            topBar = {
                LibraryFilterBar(
                    session = session,
                    showUpdate = showUpdate,
                    activeLibrary = activeLibrary,
                    count = if (activeLibrary.type == "tv") shows.size else items.size,
                    selectedGenre = selectedGenre,
                    selectedSort = selectedSort,
                    selectedMinRating = selectedMinRating,
                    selectedSeenStatus = selectedSeenStatus,
                    genres = genres,
                    firstFocusRequester = filterFocusRequester,
                    onSort = onSort,
                    onGenre = onGenre,
                    onMinRating = onMinRating,
                    onSeenStatus = onSeenStatus,
                    onUpdates = onUpdates,
                    onScan = onScan,
                    onLogout = onLogout,
                )
            },
        ) {
            if (error.isNotBlank()) {
                Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                } else if (activeLibrary.type == "tv") {
                    PosterGrid(
                        entries = shows,
                        key = { showFocusKey(it) },
                        initialFocusKey = initialFocusKey,
                        alphabetTitle = { it.title },
                        alphabetEntries = alphabet.takeIf { selectedSort.isBlank() } ?: emptyList(),
                        onAlphabet = { entry ->
                            suppressSideNavigationExpansion = true
                            onAlphabet(entry)
                        },
                        alphabetFocusRequester = alphabetFocusRequester,
                        restoreFocus = {
                            val target = restoreGridFocus
                            if (target != null) {
                                target.requestFocus()
                                true
                            } else {
                                false
                            }
                        },
                    ) { show, autoFocus, column, firstRow, rightEdge, focusRequester ->
                        ShowCard(
                            session = session,
                            show = show,
                            watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                            watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                            autoFocus = autoFocus,
                            focusRequester = focusRequester,
                            onFocus = {
                                restoreGridFocus = focusRequester
                                focusedBackdrop = show.backdropPreview()
                            },
                            onLeftEdge = if (column == 0) {
                                { navFocusRequester.requestFocus(); true }
                            } else {
                                null
                            },
                            onRightEdge = if (rightEdge && selectedSort.isBlank() && alphabet.isNotEmpty()) {
                                { alphabetFocusRequester.requestFocus(); true }
                            } else {
                                null
                            },
                            onUp = if (firstRow) {
                                { filterFocusRequester.requestFocus(); true }
                            } else {
                                null
                            },
                            onClick = { onShow(show) },
                            onLongClick = { requester -> onShowMenu(show, requester) },
                        )
                    }
                } else {
                    PosterGrid(
                        entries = items,
                        key = { it.id },
                        initialFocusKey = initialFocusKey,
                        alphabetTitle = { it.title },
                        alphabetEntries = alphabet.takeIf { selectedSort.isBlank() } ?: emptyList(),
                        onAlphabet = { entry ->
                            suppressSideNavigationExpansion = true
                            onAlphabet(entry)
                        },
                        alphabetFocusRequester = alphabetFocusRequester,
                        restoreFocus = {
                            val target = restoreGridFocus
                            if (target != null) {
                                target.requestFocus()
                                true
                            } else {
                                false
                            }
                        },
                    ) { item, autoFocus, column, firstRow, rightEdge, focusRequester ->
                        ItemCard(
                            session = session,
                            item = item,
                            watched = completedItems.contains(item.id),
                            watchlisted = watchlistItems.contains(item.id),
                            autoFocus = autoFocus,
                            focusRequester = focusRequester,
                            onFocus = {
                                restoreGridFocus = focusRequester
                                focusedBackdrop = item.backdropPreview()
                            },
                            onLeftEdge = if (column == 0) {
                                { navFocusRequester.requestFocus(); true }
                            } else {
                                null
                            },
                            onRightEdge = if (rightEdge && selectedSort.isBlank() && alphabet.isNotEmpty()) {
                                { alphabetFocusRequester.requestFocus(); true }
                            } else {
                                null
                            },
                            onUp = if (firstRow) {
                                { filterFocusRequester.requestFocus(); true }
                            } else {
                                null
                            },
                            onClick = { onItem(item) },
                            onLongClick = { requester -> onItemMenu(item, requester) },
                        )
                    }
                }
            }
        }
    }
}

fun showFocusKey(show: ShowSummary): String = "${show.libraryId}\n${show.title.lowercase()}"

private data class BrowseBackdropPreview(val itemId: Long, val type: String, val version: Long)

private fun PopItem.backdropPreview(): BrowseBackdropPreview? = when {
    backdropPath.isNotBlank() -> BrowseBackdropPreview(id, "backdrop", backdropMtimeUnix)
    posterPath.isNotBlank() -> BrowseBackdropPreview(id, "poster", posterMtimeUnix)
    else -> null
}

private fun ShowSummary.backdropPreview(): BrowseBackdropPreview? = when {
    backdropItemId > 0 -> BrowseBackdropPreview(backdropItemId, "backdrop", backdropMtimeUnix)
    posterItemId > 0 -> BrowseBackdropPreview(posterItemId, "poster", posterMtimeUnix)
    else -> null
}

@Composable
private fun BrowseBackdropLayer(session: Session?, preview: BrowseBackdropPreview?) {
    if (session != null && preview != null) {
        SizedAsyncImage(
            model = imageUrl(session, preview.itemId, preview.type, preview.version),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            widthPx = if (preview.type == "backdrop") 1920 else 900,
            heightPx = if (preview.type == "backdrop") 1080 else 900,
            authToken = session.token,
        )
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(
                    Bg.copy(alpha = .26f),
                    Bg.copy(alpha = .68f),
                    Bg.copy(alpha = .96f),
                    Bg,
                ),
                startY = 0f,
                endY = 780f,
            ),
        ),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                colors = listOf(
                    Bg.copy(alpha = .36f),
                    Bg.copy(alpha = .76f),
                    Bg.copy(alpha = .94f),
                ),
                startX = 0f,
                endX = 900f,
            ),
        ),
    )
}

@Composable
fun LibraryFilterBar(
    session: Session?,
    showUpdate: Boolean,
    activeLibrary: Library,
    count: Int,
    selectedGenre: String,
    selectedSort: String,
    selectedMinRating: Double,
    selectedSeenStatus: String,
    genres: List<String>,
    firstFocusRequester: FocusRequester? = null,
    onSort: (String) -> Unit,
    onGenre: (String) -> Unit,
    onMinRating: (Double) -> Unit,
    onSeenStatus: (String) -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
) {
    var openDropdown by remember(activeLibrary.id) { mutableStateOf<LibraryDropdown?>(null) }
    BackHandler(enabled = openDropdown != null) { openDropdown = null }
    val countLabel = if (activeLibrary.type == "tv") "$count shows" else "$count movies"
    val selectedGenres = splitSelectedGenres(selectedGenre)
    val genreLabel = when (selectedGenres.size) {
        0 -> "Genre: All"
        1 -> "Genre: ${selectedGenres.first()}"
        else -> "Genre: ${selectedGenres.size} selected"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = .46f))
            .padding(horizontal = 26.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(activeLibrary.name, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(countLabel, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Pill(text = "Sort: ${sortLabel(selectedSort)}", selected = openDropdown == LibraryDropdown.Sort || selectedSort.isNotBlank(), modifier = if (firstFocusRequester != null) Modifier.focusRequester(firstFocusRequester) else Modifier, onClick = { openDropdown = LibraryDropdown.Sort })
            Pill(text = seenLabel(selectedSeenStatus), selected = openDropdown == LibraryDropdown.Seen || selectedSeenStatus.isNotBlank(), onClick = { openDropdown = LibraryDropdown.Seen })
            Pill(text = ratingLabel(selectedMinRating), selected = openDropdown == LibraryDropdown.Rating || selectedMinRating > 0.0, onClick = { openDropdown = LibraryDropdown.Rating })
            Pill(text = genreLabel, selected = openDropdown == LibraryDropdown.Genre || selectedGenres.isNotEmpty(), onClick = { openDropdown = LibraryDropdown.Genre })
            Spacer(Modifier.weight(1f))
            UserMenuButton(session, showUpdate, onUpdates, onScan, onLogout)
        }
        when (openDropdown) {
            LibraryDropdown.Sort -> FilterPopup("Sort", sortDropdownOptions().map { option ->
                FilterOption(option.label, selectedSort == option.sort) {
                    onSort(option.sort)
                    openDropdown = null
                }
            }, onClose = { openDropdown = null })
            LibraryDropdown.Seen -> FilterPopup("Seen status", seenOptions().map { (status, label) ->
                FilterOption(label, selectedSeenStatus == status) {
                    onSeenStatus(status)
                    openDropdown = null
                }
            }, onClose = { openDropdown = null })
            LibraryDropdown.Rating -> FilterPopup("IMDb rating", ratingOptions().map { (rating, label) ->
                FilterOption(label, selectedMinRating == rating) {
                    onMinRating(rating)
                    openDropdown = null
                }
            }, onClose = { openDropdown = null })
            LibraryDropdown.Genre -> FilterPopup(
                title = "Genres",
                options = listOf(FilterOption("All", selectedGenres.isEmpty()) { onGenre("") }) + genres.map { genre ->
                    FilterOption(genre, selectedGenres.any { it.equals(genre, ignoreCase = true) }) {
                        onGenre(toggleGenre(selectedGenres, genre).joinToString(","))
                    }
                },
                onClose = { openDropdown = null },
            )
            null -> Unit
        }
    }
}

private enum class LibraryDropdown { Sort, Seen, Rating, Genre }

private data class SortOption(val sort: String, val label: String)
private data class FilterOption(val label: String, val selected: Boolean, val onClick: () -> Unit)

private fun sortDropdownOptions(): List<SortOption> = listOf(
    SortOption("", "Name A-Z"),
    SortOption("title_desc", "Name Z-A"),
    SortOption("mtime", "File date newest"),
    SortOption("mtime_asc", "File date oldest"),
    SortOption("year_desc", "Release year newest"),
    SortOption("year", "Release year oldest"),
    SortOption("rating", "IMDb rating highest"),
    SortOption("rating_asc", "IMDb rating lowest"),
)

private fun sortLabel(sort: String): String = sortDropdownOptions().firstOrNull { it.sort == sort }?.label ?: "Name A-Z"
private fun seenOptions(): List<Pair<String, String>> = listOf("" to "All", "seen" to "Seen", "unseen" to "Unseen")
private fun ratingOptions(): List<Pair<Double, String>> = listOf(0.0 to "All", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+")
private fun seenLabel(status: String): String = "Seen: " + (seenOptions().firstOrNull { it.first == status }?.second ?: "All")
private fun ratingLabel(rating: Double): String = "IMDb: " + (ratingOptions().firstOrNull { it.first == rating }?.second ?: "All")
private fun splitSelectedGenres(value: String): List<String> = value.split(",", "|").map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
private fun toggleGenre(selected: List<String>, genre: String): List<String> {
    return if (selected.any { it.equals(genre, ignoreCase = true) }) {
        selected.filterNot { it.equals(genre, ignoreCase = true) }
    } else {
        selected + genre
    }
}

@Composable
private fun UserMenuButton(session: Session?, showUpdate: Boolean, onUpdates: () -> Unit, onScan: () -> Unit, onLogout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(text = session?.username?.ifBlank { "User" } ?: "User", selected = open, onClick = { open = true })
        if (open) {
            val options = buildList {
                add(FilterOption("Scan libraries", false) {
                    onScan()
                    open = false
                })
                if (showUpdate) {
                    add(FilterOption("Update app", false) {
                        onUpdates()
                        open = false
                    })
                }
                add(FilterOption("Logout", false) {
                    onLogout()
                    open = false
                })
            }
            FilterPopup(title = session?.username.orEmpty().ifBlank { "User" }, options = options, checkboxes = false, onClose = { open = false })
        }
    }
}

@Composable
private fun FilterPopup(title: String, options: List<FilterOption>, checkboxes: Boolean = true, onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(options.size) {
        delay(80)
        firstFocus.requestFocus()
    }
    Popup(alignment = Alignment.TopEnd, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .padding(top = 58.dp, end = 24.dp)
                .width(340.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = .72f))
                .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(10.dp))
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyUp && (it.key == Key.Back || it.key == Key.DirectionLeft || it.key == Key.DirectionRight)) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            LazyColumn(
                modifier = Modifier.height(if (options.size > 8) 330.dp else ((options.size * 42) + 8).dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(options, key = { index, option -> "$index-${option.label}" }) { index, option ->
                    GenreFilterRow(
                        text = if (checkboxes) "${if (option.selected) "[x]" else "[ ]"} ${option.label}" else option.label,
                        selected = option.selected,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = option.onClick,
                    )
                }
            }
        }
    }
}

@Composable
fun GenreFilterRow(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        selected && focused -> Accent
        selected -> AccentDim
        focused -> Surface3
        else -> Color.Transparent
    }
    val border = when {
        selected && focused -> Color.White
        focused -> FocusGlow
        selected -> Accent.copy(alpha = .55f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (selected) Color.Black else TextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BrowserHeader(title: String, subtitle: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .fillMaxWidth()
                .widthIn(max = TvPageMaxWidth)
                .padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
fun CuratedLanding(
    session: Session?,
    movies: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    continueMovies: List<PopItem>,
    continueEpisodes: List<PopItem>,
    recentMovies: List<PopItem>,
    recentShows: List<ShowSummary>,
    watchlistMovies: List<PopItem>,
    watchlistTvShows: List<ShowSummary>,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    if (movies.isEmpty() && shows.isEmpty()) {
        EmptyState("No media found")
        return
    }
    val topMovies = remember(movies) { movies.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { movies.take(24) } }
    val moviePicks = remember(movies) { movies.shuffledStable().take(24) }
    val topShows = remember(shows) { shows.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { shows.take(24) } }
    val showPicks = remember(shows) { shows.shuffledStableBy { it.title }.take(24) }
    val movieShelves = remember(movies) { movieGenreShelves(movies).take(4) }
    val showShelves = remember(shows) { showGenreShelves(shows).take(4) }
    var initialFocusPending by remember { mutableStateOf(true) }
    val initialFocusTarget = when {
        continueMovies.isNotEmpty() -> "continueMovies"
        continueEpisodes.isNotEmpty() -> "continueEpisodes"
        watchlistMovies.isNotEmpty() -> "watchlistMovies"
        watchlistTvShows.isNotEmpty() -> "watchlistShows"
        recentMovies.isNotEmpty() -> "recentMovies"
        recentShows.isNotEmpty() -> "recentShows"
        movies.isNotEmpty() -> "topMovies"
        shows.isNotEmpty() -> "topShows"
        else -> ""
    }
    fun shouldInitialFocus(target: String, autoFocus: Boolean): Boolean {
        return initialFocusPending && autoFocus && initialFocusTarget == target
    }
    fun consumeInitialFocus(target: String, autoFocus: Boolean) {
        if (shouldInitialFocus(target, autoFocus)) {
            initialFocusPending = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BrowserHeader("Home", "${movies.size} movies \u00b7 ${shows.size} shows loaded") }
        if (continueMovies.isNotEmpty()) {
            item {
                PosterShelf("Continue Movies", "${continueMovies.size} in progress", continueMovies, key = { it.id }, autoFocusFirst = true) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("continueMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, onFocus = { consumeInitialFocus("continueMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (continueEpisodes.isNotEmpty()) {
            item {
                PosterShelf("Continue TV", "${continueEpisodes.size} episodes", continueEpisodes, key = { it.id }, autoFocusFirst = continueMovies.isEmpty()) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("continueEpisodes", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, onFocus = { consumeInitialFocus("continueEpisodes", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (watchlistMovies.isNotEmpty()) {
            item {
                PosterShelf("Watchlist Movies", "${watchlistMovies.size} saved", watchlistMovies, key = { it.id }, autoFocusFirst = continueMovies.isEmpty() && continueEpisodes.isEmpty()) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("watchlistMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = true, autoFocus = focusNow, onFocus = { consumeInitialFocus("watchlistMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (watchlistTvShows.isNotEmpty()) {
            item {
                PosterShelf("Watchlist TV", "${watchlistTvShows.size} shows", watchlistTvShows, key = { it.title }) { show, autoFocus ->
                    val focusNow = shouldInitialFocus("watchlistShows", autoFocus)
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = true, autoFocus = focusNow, onFocus = { consumeInitialFocus("watchlistShows", autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
        if (recentMovies.isNotEmpty()) {
            item {
                PosterShelf("Recently Added Movies", "${recentMovies.size} new", recentMovies, key = { it.id }, autoFocusFirst = continueMovies.isEmpty() && continueEpisodes.isEmpty() && watchlistMovies.isEmpty() && watchlistTvShows.isEmpty()) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("recentMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, onFocus = { consumeInitialFocus("recentMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (recentShows.isNotEmpty()) {
            item {
                PosterShelf("Recently Added TV", "${recentShows.size} shows", recentShows, key = { it.title }) { show, autoFocus ->
                    val focusNow = shouldInitialFocus("recentShows", autoFocus)
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = focusNow, onFocus = { consumeInitialFocus("recentShows", autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
        if (movies.isNotEmpty()) {
            item {
                PosterShelf("Top Rated Movies", "${topMovies.size} picks", topMovies, key = { it.id }, autoFocusFirst = true) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("topMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, onFocus = { consumeInitialFocus("topMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
            item {
                PosterShelf("Movie Picks", "from your movies", moviePicks, key = { it.id }) { item, autoFocus ->
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = autoFocus, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (shows.isNotEmpty()) {
            item {
                PosterShelf("Top Rated TV", "${topShows.size} shows", topShows, key = { it.title }) { show, autoFocus ->
                    val focusNow = shouldInitialFocus("topShows", autoFocus)
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = focusNow, onFocus = { consumeInitialFocus("topShows", autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
            item {
                PosterShelf("Show Picks", "from your shows", showPicks, key = { it.title }) { show, autoFocus ->
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = autoFocus, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
        movieShelves.forEach { (genre, entries) ->
            item(key = "home-movie-$genre") {
                PosterShelf(genre, "${entries.size} movies", entries, key = { it.id }) { item, autoFocus ->
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = autoFocus, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        showShelves.forEach { (genre, entries) ->
            item(key = "home-show-$genre") {
                PosterShelf("$genre TV", "${entries.size} shows", entries, key = { it.title }) { show, autoFocus ->
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = autoFocus, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
    }
}

@Composable
fun MovieLanding(session: Session?, title: String, items: List<PopItem>, onItem: (PopItem) -> Unit) {
    if (items.isEmpty()) {
        EmptyState("No movies found")
        return
    }
    val topRated = remember(items) {
        items.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { items.take(24) }
    }
    val moviePicks = remember(items) { items.shuffledStable().take(24) }
    val shelves = remember(items) { movieGenreShelves(items) }
    var initialFocusPending by remember(items.firstOrNull()?.id) { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BrowserHeader(title, "${items.size} movies") }
        item {
            PosterShelf(
                title = "Top Rated",
                subtitle = "${topRated.size} picks",
                entries = topRated,
                key = { it.id },
                autoFocusFirst = true,
            ) { item, autoFocus ->
                val focusNow = initialFocusPending && autoFocus
                ItemCard(session, item, autoFocus = focusNow, onFocus = { if (focusNow) initialFocusPending = false }, onClick = { onItem(item) })
            }
        }
        item {
            PosterShelf(
                title = "Movie Picks",
                subtitle = "from your library",
                entries = moviePicks,
                key = { it.id },
            ) { item, autoFocus ->
                ItemCard(session, item, autoFocus = autoFocus, onClick = { onItem(item) })
            }
        }
        shelves.forEach { (genre, entries) ->
            item(key = "movie-genre-$genre") {
                PosterShelf(
                    title = genre,
                    subtitle = "${entries.size} movies",
                    entries = entries,
                    key = { it.id },
                ) { item, autoFocus ->
                    ItemCard(session, item, autoFocus = autoFocus, onClick = { onItem(item) })
                }
            }
        }
    }
}

@Composable
fun TvLanding(session: Session?, title: String, shows: List<ShowSummary>, onShow: (ShowSummary) -> Unit) {
    if (shows.isEmpty()) {
        EmptyState("No shows found")
        return
    }
    val topRated = remember(shows) {
        shows.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { shows.take(24) }
    }
    val showPicks = remember(shows) { shows.shuffledStableBy { it.title }.take(24) }
    val shelves = remember(shows) { showGenreShelves(shows) }
    var initialFocusPending by remember(shows.firstOrNull()?.title) { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BrowserHeader(title, "${shows.size} shows") }
        item {
            PosterShelf(
                title = "Top Rated",
                subtitle = "${topRated.size} shows",
                entries = topRated,
                key = { it.title },
                autoFocusFirst = true,
            ) { show, autoFocus ->
                val focusNow = initialFocusPending && autoFocus
                ShowCard(session, show, autoFocus = focusNow, onFocus = { if (focusNow) initialFocusPending = false }, onClick = { onShow(show) })
            }
        }
        item {
            PosterShelf(
                title = "Show Picks",
                subtitle = "from your library",
                entries = showPicks,
                key = { it.title },
            ) { show, autoFocus ->
                ShowCard(session, show, autoFocus = autoFocus, onClick = { onShow(show) })
            }
        }
        shelves.forEach { (genre, entries) ->
            item(key = "show-genre-$genre") {
                PosterShelf(
                    title = genre,
                    subtitle = "${entries.size} shows",
                    entries = entries,
                    key = { it.title },
                ) { show, autoFocus ->
                    ShowCard(session, show, autoFocus = autoFocus, onClick = { onShow(show) })
                }
            }
        }
    }
}

@Composable
fun <T> PosterShelf(
    title: String,
    subtitle: String,
    entries: List<T>,
    key: (T) -> Any,
    autoFocusFirst: Boolean = false,
    content: @Composable (T, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(entries, key = { _, item -> key(item) }) { index, item ->
                Box(Modifier.width(122.dp)) {
                    content(item, autoFocusFirst && index == 0)
                }
            }
        }
    }
}

@Composable
fun GenreBrowserView(
    session: Session?,
    activeLibrary: Library?,
    items: List<PopItem>,
    shows: List<ShowSummary>,
    loadingMore: Boolean,
    fullyLoaded: Boolean,
    onBack: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
) {
    val itemGenrePairs = remember(items) { items.map { it to splitGenres(it.genres) } }
    val showGenrePairs = remember(shows) { shows.map { it to splitGenres(it.genres) } }
    val genres = remember(activeLibrary?.type, itemGenrePairs, showGenrePairs) {
        val values = if (activeLibrary?.type == "tv") showGenrePairs.flatMap { it.second } else itemGenrePairs.flatMap { it.second }
        values.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var selectedGenre by remember(genres) { mutableStateOf(genres.firstOrNull().orEmpty()) }
    var initialGenreFocusPending by remember(genres.firstOrNull()) { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .46f))
                .padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Pill("Back", selected = false, onClick = onBack)
            Text(activeLibrary?.name ?: "Genres", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(
                if (fullyLoaded) "Genres" else "Genres loading...",
                color = Muted,
                fontSize = 12.sp,
            )
            if (loadingMore) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }

        if (genres.isEmpty()) {
            EmptyState("No genres found in this library")
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.width(220.dp).fillMaxSize().background(Color.Black.copy(alpha = .38f)),
                contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(genres, key = { _, genre -> genre }) { index, genre ->
                    val focusNow = initialGenreFocusPending && index == 0
                    GenreRow(
                        text = genre,
                        count = if (activeLibrary?.type == "tv") {
                            showGenrePairs.count { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
                        } else {
                            itemGenrePairs.count { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
                        },
                        selected = genre == selectedGenre,
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialGenreFocusPending = false },
                        onClick = { selectedGenre = genre },
                    )
                }
            }

            Column(Modifier.weight(1f).fillMaxSize()) {
                if (activeLibrary?.type == "tv") {
                    val filtered = showGenrePairs.filter { (_, genres) -> genres.any { it.equals(selectedGenre, ignoreCase = true) } }.map { it.first }
                    BrowserHeader(selectedGenre, "${filtered.size} shows")
                    PosterGrid(entries = filtered, key = { it.title }) { show, _, _, _, _, _ ->
                        ShowCard(session, show, autoFocus = false, onClick = { onShow(show) })
                    }
                } else {
                    val filtered = itemGenrePairs.filter { (_, genres) -> genres.any { it.equals(selectedGenre, ignoreCase = true) } }.map { it.first }
                    BrowserHeader(selectedGenre, "${filtered.size} movies")
                    PosterGrid(entries = filtered, key = { it.id }) { item, _, _, _, _, _ ->
                        ItemCard(session, item, autoFocus = false, onClick = { onItem(item) })
                    }
                }
            }
        }
    }
}

@Composable
fun GenreRow(text: String, count: Int, selected: Boolean, autoFocus: Boolean, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(180)
            focusRequester.requestFocus()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused && !selected) FocusGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = if (selected) Color.Black else TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count.toString(), color = if (selected) Color.Black.copy(alpha = .7f) else Muted, fontSize = 10.sp)
    }
}

private fun splitGenres(value: String): List<String> {
    return value
        .split(",", "/", "|", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun movieGenreShelves(items: List<PopItem>): List<Pair<String, List<PopItem>>> {
    val pairs = items.map { it to splitGenres(it.genres) }
    val topGenres = pairs
        .flatMap { it.second }
        .groupingBy { it }
        .eachCount()
        .entries
        .filter { it.value >= 4 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
        .take(8)
        .map { it.key }
    return topGenres.mapNotNull { genre ->
        val entries = pairs
            .filter { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
            .map { it.first }
            .sortedWith(compareByDescending<PopItem> { it.rating }.thenBy { it.title })
            .take(24)
        if (entries.isNotEmpty()) genre to entries else null
    }
}

private fun showGenreShelves(shows: List<ShowSummary>): List<Pair<String, List<ShowSummary>>> {
    val pairs = shows.map { it to splitGenres(it.genres) }
    val topGenres = pairs
        .flatMap { it.second }
        .groupingBy { it }
        .eachCount()
        .entries
        .filter { it.value >= 3 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
        .take(8)
        .map { it.key }
    return topGenres.mapNotNull { genre ->
        val entries = pairs
            .filter { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
            .map { it.first }
            .sortedWith(compareByDescending<ShowSummary> { it.rating }.thenBy { it.title })
            .take(24)
        if (entries.isNotEmpty()) genre to entries else null
    }
}

private fun List<PopItem>.shuffledStable(): List<PopItem> {
    return sortedBy { ((it.id * 1103515245L + 12345L) and 0x7fffffff).toInt() }
}

private fun <T> List<T>.shuffledStableBy(selector: (T) -> String): List<T> {
    return sortedBy { selector(it).fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff } }
}

@Composable
fun EmptyState(text: String, error: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (error) ErrorRed else Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Show View ──
