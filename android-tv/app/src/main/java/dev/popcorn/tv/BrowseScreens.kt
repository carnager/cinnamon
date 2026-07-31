package dev.popcorn.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun railAlphabetLetter(title: String): String {
    val ch = title.withoutLeadingArticleForRail().firstOrNull() ?: return "#"
    return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
}

private fun String.withoutLeadingArticleForRail(): String {
    val trimmed = trim()
    val lowered = trimmed.lowercase()
    val articles = listOf("the ", "a ", "an ", "der ", "die ", "das ", "ein ", "eine ")
    val article = articles.firstOrNull { lowered.startsWith(it) } ?: return trimmed
    return trimmed.drop(article.length).trimStart()
}

@Composable
fun HomeView(
    session: Session?,
    libraries: List<Library>,
    sections: List<HomeSection>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    error: String,
    loading: Boolean,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onPlayItem: (PopItem) -> Unit,
    onPlayShow: (ShowSummary) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onMore: (HomeSection) -> Unit,
    onSurprise: () -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var restoreContentFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val sideNavigationFocus = remember { FocusRequester() }
    AppChrome(
        session,
        libraries,
        selected = "home",
        showUpdate = showUpdate,
        onHome = onHome,
        onLibrary = onLibrary,
        onWatchlist = onWatchlist,
        onHistory = onHistory,
        onSearch = onSearch,
        onUpdates = onUpdates,
        onScan = onScan,
        onLogout = onLogout,
        navFocusRequester = sideNavigationFocus,
        onSideNavigationExit = {
            val target = restoreContentFocus
            if (target != null) {
                target.requestFocus()
                true
            } else {
                false
            }
        },
        headerActions = {
            Pill(
                text = "Surprise Me",
                selected = false,
                onClick = onSurprise,
            )
        },
    ) {
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        } else {
            CuratedLanding(
                session = session,
                sections = sections,
                completedItems = completedItems,
                completedShows = completedShows,
                watchlistItems = watchlistItems,
                watchlistShows = watchlistShows,
                onPlayItem = onPlayItem,
                onPlayShow = onPlayShow,
                onItem = onItem,
                onShow = onShow,
                onMore = onMore,
                onItemMenu = onItemMenu,
                onShowMenu = onShowMenu,
                onContentFocus = { restoreContentFocus = it },
                onHeroLeft = { runCatching { sideNavigationFocus.requestFocus() }.isSuccess },
            )
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
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    navFocusRequester: FocusRequester? = null,
    onSideNavigationExit: (() -> Boolean)? = null,
    suppressSideNavigationExpansion: Boolean = false,
    backShortcutEnabled: Boolean = true,
    headerActions: (@Composable () -> Unit)? = null,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val fallbackNavFocusRequester = remember { FocusRequester() }
    val resolvedNavFocusRequester = navFocusRequester ?: fallbackNavFocusRequester
    var sideNavigationHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = backShortcutEnabled) {
        if (sideNavigationHasFocus) {
            val restored = onSideNavigationExit?.invoke() == true
            if (!restored) {
                runCatching { resolvedNavFocusRequester.requestFocus() }
            }
        } else {
            runCatching { resolvedNavFocusRequester.requestFocus() }
        }
    }
    DisposableEffect(backShortcutEnabled, onSearch) {
        if (backShortcutEnabled) {
            BrowseBackBridge.handler = { event ->
                val longBack = event.keyCode == AndroidKeyEvent.KEYCODE_BACK &&
                    event.action == AndroidKeyEvent.ACTION_DOWN &&
                    (event.repeatCount > 0 || event.isLongPress)
                if (longBack) {
                    BrowseBackBridge.consumeNextBackUp()
                    onSearch()
                    true
                } else {
                    false
                }
            }
        }
        onDispose {
            if (BrowseBackBridge.handler != null) {
                BrowseBackBridge.handler = null
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            SideNavigation(
                session = session,
                libraries = libraries,
                selected = selected,
                showUpdate = showUpdate,
                onHome = onHome,
                onLibrary = onLibrary,
                onWatchlist = onWatchlist,
                onHistory = onHistory,
                onSearch = onSearch,
                onUpdates = onUpdates,
                onScan = onScan,
                onLogout = onLogout,
                firstFocusRequester = resolvedNavFocusRequester,
                onExit = onSideNavigationExit,
                expansionSuppressed = suppressSideNavigationExpansion,
                onFocusChange = { sideNavigationHasFocus = it },
            )
            Column(Modifier.fillMaxSize()) {
                PageTopActions(headerActions)
                topBar?.invoke()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds(),
                ) {
                    content()
                }
            }
        }
        CinnamonBrand(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 24.dp),
            markSize = 42,
            fontSize = 21,
        )
    }
}

@Composable
fun SideNavigation(
    session: Session?,
    libraries: List<Library>,
    selected: String,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    firstFocusRequester: FocusRequester,
    onExit: (() -> Boolean)? = null,
    expansionSuppressed: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
) {
    val movieLibrary = libraries.firstOrNull { it.type == "movies" || it.type == "movie" }
    val tvLibrary = libraries.firstOrNull { it.type == "tv" }
    Column(
        Modifier
            .width(76.dp)
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .12f))
            .onFocusChanged {
                onFocusChange(it.hasFocus)
            }
            .padding(horizontal = 10.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(64.dp))
        SideNavigationItem(icon = Icons.Filled.Home, label = "Home", selected = selected == "home", focusRequester = firstFocusRequester, onRight = onExit, onClick = onHome)
        if (movieLibrary != null) {
            SideNavigationItem(icon = Icons.Filled.Movie, label = movieLibrary.name, selected = selected == movieLibrary.id, onRight = onExit, onClick = { onLibrary(movieLibrary) })
        }
        if (tvLibrary != null) {
            SideNavigationItem(icon = Icons.Filled.LiveTv, label = tvLibrary.name, selected = selected == tvLibrary.id, onRight = onExit, onClick = { onLibrary(tvLibrary) })
        }
        SideNavigationItem(icon = Icons.Filled.Bookmark, label = "Watchlist", selected = selected == "watchlist", onRight = onExit, onClick = onWatchlist)
        SideNavigationItem(icon = Icons.Filled.Search, label = "Search", selected = selected == "search", onRight = onExit, onClick = onSearch)
        Spacer(Modifier.weight(1f))
        UserMenuButton(session, showUpdate, onHistory, onUpdates, onScan, onLogout)
    }
}

@Composable
fun SideNavigationItem(icon: ImageVector, label: String, selected: Boolean, focusRequester: FocusRequester? = null, onRight: (() -> Boolean)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = Color.Transparent
    val contentColor = if (selected || focused) Accent else TextColor.copy(alpha = .82f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, if (focused || selected) Accent.copy(alpha = if (focused) .95f else .60f) else Color.Transparent, RoundedCornerShape(10.dp))
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
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp).width(28.dp),
        )
    }
}

@Composable
fun PageTopActions(headerActions: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(70.dp)
            .padding(start = 26.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(1f))
        headerActions?.invoke()
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
        CinnamonBrand(markSize = 30, fontSize = 19)
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
    onHistory: () -> Unit,
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
    var restoreContentFocus by remember { mutableStateOf<FocusRequester?>(null) }
    AppChrome(
        session,
        libraries,
        selected = "watchlist",
        showUpdate = showUpdate,
        onHome = onHome,
        onLibrary = onLibrary,
        onWatchlist = onWatchlist,
        onHistory = onHistory,
        onSearch = onSearch,
        onUpdates = onUpdates,
        onScan = onScan,
        onLogout = onLogout,
        onSideNavigationExit = {
            val target = restoreContentFocus
            if (target != null) {
                target.requestFocus()
                true
            } else {
                false
            }
        },
    ) {
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
                    val requester = remember { FocusRequester() }
                    val focusNow = initialFocusPending && index == 0
                    ItemCard(
                        session = session,
                        item = item,
                        watched = completedItems.contains(item.id),
                        watchlisted = watchlistItems.contains(item.id),
                        autoFocus = focusNow,
                        focusRequester = requester,
                        onFocus = {
                            restoreContentFocus = requester
                            if (focusNow) initialFocusPending = false
                        },
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
                    val requester = remember { FocusRequester() }
                    val focusNow = initialFocusPending && movies.isEmpty() && index == 0
                    ShowCard(
                        session = session,
                        show = show,
                        watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                        watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                        autoFocus = focusNow,
                        focusRequester = requester,
                        onFocus = {
                            restoreContentFocus = requester
                            if (focusNow) initialFocusPending = false
                        },
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
fun HistoryView(
    session: Session?,
    libraries: List<Library>,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
) {
    var history by remember(session?.token) { mutableStateOf<WatchHistory?>(null) }
    var error by remember(session?.token) { mutableStateOf("") }
    var restoreContentFocus by remember { mutableStateOf<FocusRequester?>(null) }

    LaunchedEffect(session?.token) {
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).watchHistory() }
            .onSuccess {
                history = it
                error = ""
            }
            .onFailure { error = it.message ?: "Failed to load watch history" }
    }

    AppChrome(
        session = session,
        libraries = libraries,
        selected = "history",
        showUpdate = showUpdate,
        onHome = onHome,
        onLibrary = onLibrary,
        onWatchlist = onWatchlist,
        onHistory = onHistory,
        onSearch = onSearch,
        onUpdates = onUpdates,
        onScan = onScan,
        onLogout = onLogout,
        onSideNavigationExit = {
            restoreContentFocus?.let {
                it.requestFocus()
                true
            } ?: false
        },
    ) {
        when {
            error.isNotBlank() -> EmptyState(error, error = true)
            history == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            history!!.items.isEmpty() -> EmptyState("No watch history yet")
            else -> {
                val result = history!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        GridSectionHeader(
                            "Watch History",
                            if (result.source == "trakt") "${result.items.size} plays · Trakt" else "${result.items.size} plays · This server",
                        )
                    }
                    itemsIndexed(result.items, key = { index, entry -> "${entry.id}:$index" }) { index, entry ->
                        val requester = remember { FocusRequester() }
                        HistoryListRow(
                            entry = entry,
                            autoFocus = index == 0,
                            focusRequester = requester,
                            onFocus = { restoreContentFocus = requester },
                            onClick = { entry.item?.let(onItem) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryListRow(
    entry: WatchHistoryEntry,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(160)
            focusRequester.requestFocus()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .border(1.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (entry.kind == "movie") Icons.Filled.Movie else Icons.Filled.LiveTv,
            contentDescription = entry.kind,
            tint = if (entry.kind == "movie") Accent else Teal,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                entry.subtitle.takeIf { it.isNotBlank() },
                entry.year.takeIf { it > 0 }?.toString(),
            ).joinToString(" · ")
            if (details.isNotBlank()) {
                Text(details, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(historyTimestamp(entry.watchedAt), color = Muted, fontSize = 10.sp)
            if (entry.item == null) Text("Trakt", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun historyTimestamp(value: String): String {
    val normalized = value.trim().replace('T', ' ')
    if (normalized.length < 16) return normalized
    return "${normalized.substring(0, 10)} · ${normalized.substring(11, 16)}"
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
    selectedDecades: String,
    selectedSort: String,
    selectedMinRating: Double,
    selectedSeenStatus: String,
    genres: List<String>,
    decades: List<Int>,
    alphabet: List<AlphabetEntry>,
    initialFocusKey: Any?,
    showUpdate: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onFilters: (String, String, Double, String, String) -> Unit,
    onAlphabet: (AlphabetEntry) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var restoreGridFocus by remember(activeLibrary.id, pageIndex, selectedGenre) { mutableStateOf<FocusRequester?>(null) }
    var focusedBackdrop by remember(activeLibrary.id) { mutableStateOf<BrowseBackdropPreview?>(null) }
    var focusedAlphabetLetter by remember(activeLibrary.id, pageIndex, selectedGenre, selectedSort) { mutableStateOf<String?>(null) }
    var suppressSideNavigationExpansion by remember { mutableStateOf(false) }
    var refocusAlphabetAfterJump by remember { mutableStateOf(false) }
    var filterMenuOpen by remember(activeLibrary.id) { mutableStateOf(false) }
    var gridEntryFocusPending by remember(activeLibrary.id) { mutableStateOf(true) }
    val navFocusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    val alphabetFocusRequester = remember { FocusRequester() }
    LaunchedEffect(suppressSideNavigationExpansion) {
        if (suppressSideNavigationExpansion && !filterMenuOpen) {
            delay(900)
            suppressSideNavigationExpansion = false
        }
    }
    LaunchedEffect(loading, refocusAlphabetAfterJump, alphabet.size, selectedSort) {
        if (refocusAlphabetAfterJump && !loading && selectedSort.isBlank() && alphabet.isNotEmpty()) {
            delay(120)
            runCatching { alphabetFocusRequester.requestFocus() }
            refocusAlphabetAfterJump = false
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
            onHistory = onHistory,
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
            backShortcutEnabled = !filterMenuOpen,
            topBar = {
                LibraryFilterBar(
                    activeLibrary = activeLibrary,
                    count = if (activeLibrary.type == "tv") shows.size else items.size,
                    loading = loading,
                    selectedGenre = selectedGenre,
                    selectedDecades = selectedDecades,
                    selectedSort = selectedSort,
                    selectedMinRating = selectedMinRating,
                    selectedSeenStatus = selectedSeenStatus,
                    genres = genres,
                    decades = decades,
                    firstFocusRequester = filterFocusRequester,
                    onApply = onFilters,
                    onMenuOpenChange = { open ->
                        filterMenuOpen = open
                        suppressSideNavigationExpansion = open
                    },
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
                        autoFocusOnEntry = gridEntryFocusPending,
                        alphabetTitle = { it.title },
                        alphabetEntries = alphabet.takeIf { selectedSort.isBlank() } ?: emptyList(),
                        onAlphabet = { entry ->
                            suppressSideNavigationExpansion = true
                            refocusAlphabetAfterJump = true
                            onAlphabet(entry)
                        },
                        alphabetFocusRequester = alphabetFocusRequester,
                        selectedAlphabetLetter = focusedAlphabetLetter,
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
                                gridEntryFocusPending = false
                                restoreGridFocus = focusRequester
                                focusedAlphabetLetter = railAlphabetLetter(show.title)
                                focusedBackdrop = show.backdropPreview()
                            },
                            onLeftEdge = if (column == 0) {
                                {
                                    suppressSideNavigationExpansion = true
                                    navFocusRequester.requestFocus()
                                    true
                                }
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
                        autoFocusOnEntry = gridEntryFocusPending,
                        alphabetTitle = { it.title },
                        alphabetEntries = alphabet.takeIf { selectedSort.isBlank() } ?: emptyList(),
                        onAlphabet = { entry ->
                            suppressSideNavigationExpansion = true
                            refocusAlphabetAfterJump = true
                            onAlphabet(entry)
                        },
                        alphabetFocusRequester = alphabetFocusRequester,
                        selectedAlphabetLetter = focusedAlphabetLetter,
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
                                gridEntryFocusPending = false
                                restoreGridFocus = focusRequester
                                focusedAlphabetLetter = railAlphabetLetter(item.title)
                                focusedBackdrop = item.backdropPreview()
                            },
                            onLeftEdge = if (column == 0) {
                                {
                                    suppressSideNavigationExpansion = true
                                    navFocusRequester.requestFocus()
                                    true
                                }
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
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = .14f },
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
    activeLibrary: Library,
    count: Int,
    loading: Boolean,
    selectedGenre: String,
    selectedDecades: String,
    selectedSort: String,
    selectedMinRating: Double,
    selectedSeenStatus: String,
    genres: List<String>,
    decades: List<Int>,
    firstFocusRequester: FocusRequester? = null,
    onApply: (String, String, Double, String, String) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    var drawerPage by remember(activeLibrary.id) { mutableStateOf<LibraryFilterPage?>(null) }
    var restoreFocus by remember(activeLibrary.id) { mutableStateOf<FocusRequester?>(null) }
    var filterReloadPending by remember(activeLibrary.id) { mutableStateOf(false) }
    val sortFocus = firstFocusRequester ?: remember { FocusRequester() }
    val seenFocus = remember { FocusRequester() }
    val ratingFocus = remember { FocusRequester() }
    val genreFocus = remember { FocusRequester() }
    val decadeFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun openDrawer(page: LibraryFilterPage, requester: FocusRequester) {
        restoreFocus = requester
        drawerPage = page
    }
    fun closeDrawer(restoreImmediately: Boolean = true) {
        drawerPage = null
        if (restoreImmediately && !filterReloadPending) {
            scope.launch {
                delay(60)
                restoreFocus?.let { runCatching { it.requestFocus() } }
            }
        }
    }
    fun applyingFilter() {
        filterReloadPending = true
    }
    LaunchedEffect(drawerPage) { onMenuOpenChange(drawerPage != null) }
    LaunchedEffect(loading, drawerPage, filterReloadPending) {
        if (filterReloadPending && !loading && drawerPage == null) {
            delay(80)
            if (loading || drawerPage != null) return@LaunchedEffect
            restoreFocus?.let { runCatching { it.requestFocus() } }
            filterReloadPending = false
        }
    }
    val countLabel = if (activeLibrary.type == "tv") "$count shows" else "$count movies"
    val selectedGenres = splitSelectedGenres(selectedGenre)
    val genreLabel = when (selectedGenres.size) {
        0 -> "Genre: All"
        1 -> "Genre: ${selectedGenres.first()}"
        else -> "Genre: ${selectedGenres.size} selected"
    }
    val selectedDecadeList = splitSelectedGenres(selectedDecades)
    val decadeLabel = when (selectedDecadeList.size) {
        0 -> "Decade: All"
        1 -> "Decade: ${selectedDecadeList.first()}s"
        else -> "Decade: ${selectedDecadeList.size} selected"
    }
    Box(
        Modifier
            .fillMaxWidth()
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
            Pill(text = "Sort: ${sortLabel(selectedSort)}", selected = selectedSort.isNotBlank(), modifier = Modifier.focusRequester(sortFocus), onClick = { openDrawer(LibraryFilterPage.Sort, sortFocus) })
            Pill(text = seenLabel(selectedSeenStatus), selected = selectedSeenStatus.isNotBlank(), modifier = Modifier.focusRequester(seenFocus), onClick = { openDrawer(LibraryFilterPage.Seen, seenFocus) })
            Pill(text = ratingLabel(selectedMinRating), selected = selectedMinRating > 0.0, modifier = Modifier.focusRequester(ratingFocus), onClick = { openDrawer(LibraryFilterPage.Rating, ratingFocus) })
            Pill(text = genreLabel, selected = selectedGenres.isNotEmpty(), modifier = Modifier.focusRequester(genreFocus), onClick = { openDrawer(LibraryFilterPage.Genre, genreFocus) })
            Pill(text = decadeLabel, selected = selectedDecadeList.isNotEmpty(), modifier = Modifier.focusRequester(decadeFocus), onClick = { openDrawer(LibraryFilterPage.Decade, decadeFocus) })
        }
        drawerPage?.let { page ->
            LibraryFilterDrawer(
                page = page,
                sort = selectedSort,
                seen = selectedSeenStatus,
                rating = selectedMinRating,
                selectedGenres = selectedGenres,
                selectedDecades = selectedDecadeList,
                genres = genres,
                decades = decades,
                onSort = { value ->
                    applyingFilter()
                    onApply(value, selectedSeenStatus, selectedMinRating, selectedGenre, selectedDecades)
                    closeDrawer(restoreImmediately = false)
                },
                onSeen = { value ->
                    applyingFilter()
                    onApply(selectedSort, value, selectedMinRating, selectedGenre, selectedDecades)
                    closeDrawer(restoreImmediately = false)
                },
                onRating = { value ->
                    applyingFilter()
                    onApply(selectedSort, selectedSeenStatus, value, selectedGenre, selectedDecades)
                    closeDrawer(restoreImmediately = false)
                },
                onGenres = { values ->
                    applyingFilter()
                    onApply(selectedSort, selectedSeenStatus, selectedMinRating, values.joinToString(","), selectedDecades)
                },
                onDecades = { values ->
                    applyingFilter()
                    onApply(selectedSort, selectedSeenStatus, selectedMinRating, selectedGenre, values.joinToString(","))
                },
                onClose = { closeDrawer() },
            )
        }
    }
}

private enum class LibraryFilterPage { Sort, Seen, Rating, Genre, Decade }

@Composable
private fun LibraryFilterDrawer(
    page: LibraryFilterPage,
    sort: String,
    seen: String,
    rating: Double,
    selectedGenres: List<String>,
    selectedDecades: List<String>,
    genres: List<String>,
    decades: List<Int>,
    onSort: (String) -> Unit,
    onSeen: (String) -> Unit,
    onRating: (Double) -> Unit,
    onGenres: (List<String>) -> Unit,
    onDecades: (List<String>) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val entries = when (page) {
        LibraryFilterPage.Sort -> sortDropdownOptions().map { option ->
            UserDrawerEntry(option.label, Icons.AutoMirrored.Filled.Sort, active = sort == option.sort) { onSort(option.sort) }
        }
        LibraryFilterPage.Seen -> seenOptions().map { option ->
            UserDrawerEntry(option.second, Icons.Filled.Visibility, active = seen == option.first) { onSeen(option.first) }
        }
        LibraryFilterPage.Rating -> ratingOptions().map { option ->
            UserDrawerEntry(option.second, Icons.Filled.Star, active = rating == option.first) { onRating(option.first) }
        }
        LibraryFilterPage.Genre ->
            listOf(UserDrawerEntry("All genres", Icons.Filled.Category, active = selectedGenres.isEmpty()) { onGenres(emptyList()) }) +
            genres.map { genre ->
                UserDrawerEntry(genre, Icons.Filled.Category, active = selectedGenres.any { it.equals(genre, ignoreCase = true) }) {
                    onGenres(toggleGenre(selectedGenres, genre))
                }
            }
        LibraryFilterPage.Decade ->
            listOf(UserDrawerEntry("All decades", Icons.Filled.DateRange, active = selectedDecades.isEmpty()) { onDecades(emptyList()) }) +
            decades.sortedDescending().map { decade ->
                val value = decade.toString()
                UserDrawerEntry("${decade}s", Icons.Filled.DateRange, active = value in selectedDecades) {
                    onDecades(toggleGenre(selectedDecades, value))
                }
            }
    }
    BackHandler(onBack = onClose)
    LaunchedEffect(page) {
        delay(45)
        runCatching { firstFocus.requestFocus() }
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).onKeyEvent {
                it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.DirectionRight)
            },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier.fillMaxHeight().width(356.dp).background(Bg.copy(alpha = .98f)).border(1.dp, Color.White.copy(alpha = .10f)).padding(horizontal = 28.dp, vertical = 42.dp),
            ) {
                Text("FILTERS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(7.dp))
                Text(
                    when (page) {
                        LibraryFilterPage.Sort -> "Sort"
                        LibraryFilterPage.Seen -> "Seen status"
                        LibraryFilterPage.Rating -> "IMDb rating"
                        LibraryFilterPage.Genre -> "Genres"
                        LibraryFilterPage.Decade -> "Decades"
                    },
                    color = TextColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(18.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(entries) { index, entry ->
                        UserDrawerAction(entry, if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }
            }
        }
    }
}

private data class SortOption(val sort: String, val label: String)

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
private fun seenOptions(): List<Pair<String, String>> = listOf("" to "All", "started" to "Started", "seen" to "Seen", "unseen" to "Unseen")
private fun ratingOptions(): List<Pair<Double, String>> = listOf(0.0 to "All", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+")
private fun seenLabel(status: String): String = "Seen: " + (seenOptions().firstOrNull { it.first == status }?.second ?: "All")
private fun ratingLabel(rating: Double): String = "IMDb: " + (ratingOptions().firstOrNull { it.first == rating }?.second ?: "All")
private fun splitSelectedGenres(value: String): List<String> = value.split(",", "|").map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
private fun Session?.userDisplayName(): String = this?.displayName?.ifBlank { username }?.ifBlank { "User" } ?: "User"
private fun toggleGenre(selected: List<String>, genre: String): List<String> {
    return if (selected.any { it.equals(genre, ignoreCase = true) }) {
        selected.filterNot { it.equals(genre, ignoreCase = true) }
    } else {
        selected + genre
    }
}

private fun tvExclusionSizeLabel(bytes: Long): String {
    if (bytes <= 0) return ""
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1) "%.1f GB".format(gib) else "%.0f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
}

@Composable
private fun UserMenuButton(
    session: Session?,
    showUpdate: Boolean,
    onHistory: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(UserMenuPage.Root) }
    val buttonFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun close(restoreFocus: Boolean = true) {
        open = false
        page = UserMenuPage.Root
        if (restoreFocus) {
            scope.launch {
                delay(60)
                runCatching { buttonFocus.requestFocus() }
            }
        }
    }
    Box {
        val avatar = avatarUrl(session)
        if (avatar.isNotBlank()) {
            AvatarButton(url = avatar, session = session, selected = open, focusRequester = buttonFocus, onClick = { open = true })
        } else {
            Pill(text = session.userDisplayName(), selected = open, modifier = Modifier.focusRequester(buttonFocus), onClick = { open = true })
        }
        if (open) {
            UserMenuDrawer(
                session = session,
                page = page,
                showUpdate = showUpdate,
                onPage = { page = it },
                onHistory = {
                    close(restoreFocus = false)
                    onHistory()
                },
                onUpdates = {
                    close(restoreFocus = false)
                    onUpdates()
                },
                onScan = {
                    close()
                    onScan()
                },
                onLogout = {
                    close(restoreFocus = false)
                    onLogout()
                },
                onClose = { close() },
            )
        }
    }
}

@Composable
private fun AvatarButton(url: String, session: Session?, selected: Boolean, focusRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val border = when {
        focused -> Color.White.copy(alpha = .9f)
        selected -> Accent.copy(alpha = .6f)
        else -> Color.White.copy(alpha = .25f)
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(2.dp, border, CircleShape)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick),
    ) {
        SizedAsyncImage(
            model = url,
            contentDescription = session.userDisplayName(),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            widthPx = 96,
            heightPx = 96,
            authToken = session?.token.orEmpty(),
        )
    }
}

private enum class UserMenuPage { Root, Audio, Subtitles, PhoneLogin, NotInterested }

data class UserDrawerEntry(
    val label: String,
    val icon: ImageVector,
    val detail: String = "",
    val active: Boolean = false,
    val action: () -> Unit,
)

@Composable
private fun UserMenuDrawer(
    session: Session?,
    page: UserMenuPage,
    showUpdate: Boolean,
    onPage: (UserMenuPage) -> Unit,
    onHistory: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioOptions = listOf("" to "Track default") + prefLanguageChoices
    val subtitleOptions = subtitlePrefChoices
    val firstFocus = remember { FocusRequester() }
    var phoneQrPayload by remember { mutableStateOf("") }
    var phoneQrError by remember { mutableStateOf("") }
    var phoneQrRefresh by remember { mutableIntStateOf(0) }
    var exclusions by remember { mutableStateOf<List<RecommendationExclusion>>(emptyList()) }
    var exclusionsLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val entries = when (page) {
        UserMenuPage.Root -> buildList {
            add(UserDrawerEntry("Watch history", Icons.Filled.History, action = onHistory))
            add(UserDrawerEntry("Not interested", Icons.Filled.Block, action = { onPage(UserMenuPage.NotInterested) }))
            add(UserDrawerEntry("Sign in a phone", Icons.Filled.QrCode2, "QR code", action = { onPage(UserMenuPage.PhoneLogin) }))
            add(UserDrawerEntry("Preferred audio", Icons.AutoMirrored.Filled.VolumeUp, audioOptions.firstOrNull { it.first == PlaybackPrefs.audioLang }?.second.orEmpty(), action = { onPage(UserMenuPage.Audio) }))
            add(UserDrawerEntry("Preferred subtitles", Icons.Filled.Subtitles, subtitleOptions.firstOrNull { it.first == PlaybackPrefs.subtitleLang }?.second.orEmpty(), action = { onPage(UserMenuPage.Subtitles) }))
            if (session?.isAdmin == true) add(UserDrawerEntry("Update libraries", Icons.Filled.Refresh, action = onScan))
            if (showUpdate) add(UserDrawerEntry("Update app", Icons.Filled.SystemUpdate, action = onUpdates))
            add(UserDrawerEntry("Logout", Icons.AutoMirrored.Filled.Logout, action = onLogout))
        }
        UserMenuPage.Audio -> listOf(UserDrawerEntry("Back", Icons.AutoMirrored.Filled.ArrowBack, action = { onPage(UserMenuPage.Root) })) +
            audioOptions.map { option ->
                UserDrawerEntry(option.second, Icons.AutoMirrored.Filled.VolumeUp, active = PlaybackPrefs.audioLang == option.first) {
                    PlaybackPrefs.setAudio(context, option.first)
                    onPage(UserMenuPage.Root)
                }
            }
        UserMenuPage.Subtitles -> listOf(UserDrawerEntry("Back", Icons.AutoMirrored.Filled.ArrowBack, action = { onPage(UserMenuPage.Root) })) +
            subtitleOptions.map { option ->
                UserDrawerEntry(option.second, Icons.Filled.Subtitles, active = PlaybackPrefs.subtitleLang == option.first) {
                    PlaybackPrefs.setSubtitle(context, option.first)
                    onPage(UserMenuPage.Root)
                }
            }
        UserMenuPage.PhoneLogin -> emptyList()
        UserMenuPage.NotInterested -> listOf(
            UserDrawerEntry("Back", Icons.AutoMirrored.Filled.ArrowBack, action = { onPage(UserMenuPage.Root) }),
        ) + exclusions.map { entry ->
            val title = entry.item?.title ?: entry.show?.title ?: "Unavailable title"
            val detail = listOf(entry.path, tvExclusionSizeLabel(entry.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
            UserDrawerEntry(title, Icons.Filled.Block, detail = detail) {
                val active = session ?: return@UserDrawerEntry
                scope.launch {
                    runCatching {
                        val api = Api(active)
                        entry.item?.let { api.restoreItemRecommendation(it.id) }
                            ?: entry.show?.let { api.restoreShowRecommendation(it.libraryId, it.title) }
                    }.onSuccess { exclusions = exclusions.filterNot { it.key == entry.key } }
                }
            }
        }
    }

    LaunchedEffect(page, session?.token) {
        if (page != UserMenuPage.NotInterested || session == null) return@LaunchedEffect
        exclusionsLoading = true
        exclusions = runCatching { Api(session).recommendationExclusions() }.getOrDefault(emptyList())
        exclusionsLoading = false
    }

    LaunchedEffect(page, phoneQrRefresh, session?.token) {
        if (page != UserMenuPage.PhoneLogin || session == null) return@LaunchedEffect
        phoneQrPayload = ""
        phoneQrError = ""
        runCatching {
            val api = Api(session)
            val started = api.startQrLogin("Cinnamon Android")
            api.approveQrLogin(started.code)
            JSONObject()
                .put("type", "popcorn-login")
                .put("server", session.server.trimEnd('/'))
                .put("code", started.code)
                .toString()
        }.onSuccess { phoneQrPayload = it }
            .onFailure { phoneQrError = it.message ?: "Could not create phone sign-in code" }
    }
    BackHandler {
        if (page == UserMenuPage.Root) onClose() else onPage(UserMenuPage.Root)
    }
    LaunchedEffect(page, entries.size, phoneQrPayload, phoneQrError) {
        delay(45)
        runCatching { firstFocus.requestFocus() }
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .32f))
                .onKeyEvent {
                    it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.DirectionRight)
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(356.dp)
                    .background(Bg.copy(alpha = .98f))
                    .border(1.dp, Color.White.copy(alpha = .10f))
                    .padding(horizontal = 28.dp, vertical = 42.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    when (page) {
                        UserMenuPage.Root -> "USER MENU"
                        UserMenuPage.Audio -> "PREFERRED AUDIO"
                        UserMenuPage.Subtitles -> "PREFERRED SUBTITLES"
                        UserMenuPage.PhoneLogin -> "SIGN IN A PHONE"
                        UserMenuPage.NotInterested -> "NOT INTERESTED"
                    },
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    when (page) {
                        UserMenuPage.Root -> session.userDisplayName()
                        UserMenuPage.PhoneLogin -> "Cinnamon Android"
                        UserMenuPage.NotInterested -> if (exclusionsLoading) "Loading…" else "${exclusions.size} excluded"
                        else -> "Playback languages"
                    },
                    color = TextColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(18.dp))
                if (page == UserMenuPage.PhoneLogin) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (phoneQrPayload.isNotBlank()) {
                            QRCode(phoneQrPayload, Modifier.size(218.dp))
                            Text("Scan this code from the signed-out Cinnamon Android app.", color = TextColor, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
                            Text("The code signs the phone in as ${session.userDisplayName()}, expires in ten minutes, and works once.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                        } else if (phoneQrError.isBlank()) {
                            Box(Modifier.size(218.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                            }
                            Text("Creating a secure sign-in code…", color = Muted, fontSize = 12.sp)
                        } else {
                            Box(Modifier.size(218.dp).clip(RoundedCornerShape(8.dp)).background(Surface2).padding(20.dp), contentAlignment = Alignment.Center) {
                                Text(phoneQrError, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        UserDrawerAction(
                            UserDrawerEntry("Create a new code", Icons.Filled.Refresh) { phoneQrRefresh++ },
                        )
                        UserDrawerAction(
                            UserDrawerEntry("Back", Icons.AutoMirrored.Filled.ArrowBack) { onPage(UserMenuPage.Root) },
                            Modifier.focusRequester(firstFocus),
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(entries) { index, entry ->
                            UserDrawerAction(
                                entry = entry,
                                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserDrawerAction(entry: UserDrawerEntry, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) TextColor else if (entry.active) Accent else Muted
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Accent.copy(alpha = .08f) else Color.Transparent)
            .border(1.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(entry.action)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(entry.icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(entry.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (entry.detail.isNotBlank()) Text(entry.detail, color = Muted, fontSize = 10.sp, maxLines = 1)
        if (entry.active) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Accent, modifier = Modifier.size(16.dp))
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

/* ── Home hero (rotating suggestion) ──
   Mirrors the web client's pickHeroItem: the hero cycles through suggestions
   with a reason (resume prompts, "because you watched X" genre matches, fresh
   arrivals, watchlist reminders, hidden gems) instead of pinning one item. */
private data class HeroPick(
    val key: String,
    val kick: String,
    val heading: String,
    val meta: String,
    val overview: String,
    val rating: Double,
    val backdropId: Long,
    val backdropVersion: Long,
    val item: PopItem?,
    val show: ShowSummary?,
)

private fun heroGenres(genres: String): List<String> =
    genres.split(",", "/", "|").map { it.trim() }.filter { it.isNotBlank() }

private fun heroItemPick(item: PopItem, kick: String): HeroPick? {
    if (item.backdropPath.isBlank()) return null
    val heading = if (item.kind == "episode") item.showTitle.ifBlank { item.title } else item.title
    val meta = if (item.kind == "episode") {
        listOfNotNull(
            "S%02dE%02d".format(item.seasonNumber, item.episodeNumber),
            item.episodeTitle.ifBlank { null },
        ).joinToString(" · ")
    } else {
        listOfNotNull(
            item.year.takeIf { it > 0 }?.toString(),
            fmtDuration(item.durationMs).ifBlank { null },
            heroGenres(item.genres).take(2).joinToString(", ").ifBlank { null },
        ).joinToString(" · ")
    }
    return HeroPick("item:${item.id}", kick, heading, meta, item.overview, item.rating, item.id, item.backdropMtimeUnix, item, null)
}

private fun heroShowPick(show: ShowSummary, kick: String): HeroPick? {
    if (show.backdropItemId <= 0) return null
    val meta = listOfNotNull(
        show.yearsLabel().ifBlank { null },
        "${show.seasonCount} " + if (show.seasonCount == 1) "season" else "seasons",
        heroGenres(show.genres).take(2).joinToString(", ").ifBlank { null },
    ).joinToString(" · ")
    return HeroPick("show:${show.libraryId}:${show.title}", kick, show.title, meta, show.overview, show.rating, show.backdropItemId, show.backdropMtimeUnix, null, show)
}

@Composable
private fun HomeHero(
    session: Session?,
    picks: List<HeroPick>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    autoFocus: Boolean,
    onPlayItem: (PopItem) -> Unit,
    onPlayShow: (ShowSummary) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onContentFocus: (FocusRequester) -> Unit,
    onDpadLeft: (() -> Boolean)? = null,
    onDpadDown: (() -> Boolean)? = null,
) {
    val heroRequester = remember { FocusRequester() }
    var heroFocused by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf(0) }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            runCatching { heroRequester.requestFocus() }
        }
    }
    LaunchedEffect(picks, index) {
        if (picks.size > 1) {
            delay(12_000)
            onIndexChange((index + 1) % picks.size)
        }
    }
    val pick = picks.getOrNull(index) ?: picks.firstOrNull() ?: return
    Box(
        Modifier
            .padding(start = 24.dp, end = 28.dp, top = 5.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(324.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .border(2.dp, Color.Transparent, RoundedCornerShape(14.dp))
            .focusRequester(heroRequester)
            .onFocusChanged {
                heroFocused = it.isFocused
                if (it.isFocused) onContentFocus(heroRequester)
            }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (selectedAction > 0) {
                            selectedAction = 0
                            true
                        } else {
                            onDpadLeft?.invoke() ?: false
                        }
                    }
                    Key.DirectionRight -> {
                        selectedAction = 1
                        true
                    }
                    Key.DirectionDown -> onDpadDown?.invoke() ?: false
                    else -> false
                }
            }
            .tvActivate {
                if (selectedAction == 0) {
                    pick.item?.let(onPlayItem) ?: pick.show?.let(onPlayShow)
                } else {
                    pick.item?.let(onItem) ?: pick.show?.let(onShow)
                }
            },
    ) {
        Crossfade(targetState = pick, animationSpec = tween(700), label = "homeHero") { current ->
            Box(Modifier.fillMaxSize()) {
                if (session != null) {
                    SizedAsyncImage(
                        model = imageUrl(session, current.backdropId, "backdrop", current.backdropVersion),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        widthPx = 1280,
                        heightPx = 720,
                        authToken = session.token,
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            colors = listOf(Bg.copy(alpha = .98f), Bg.copy(alpha = .82f), Bg.copy(alpha = .24f), Color.Transparent),
                        ),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Bg.copy(alpha = .08f), Bg.copy(alpha = .62f)),
                        ),
                    ),
                )
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(.58f)
                        .padding(start = 32.dp, end = 32.dp, top = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        current.kick,
                        color = Teal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Teal.copy(alpha = .10f))
                            .border(1.dp, Teal.copy(alpha = .36f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        current.heading,
                        color = TextColor,
                        fontSize = 32.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (current.rating > 0) Text("★ %.1f".format(current.rating), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (current.meta.isNotBlank()) {
                            Text(current.meta, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (current.overview.isNotBlank()) {
                        Text(
                            current.overview,
                            color = TextColor.copy(alpha = .82f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroActionButton(
                            label = "Play",
                            icon = Icons.Filled.PlayArrow,
                            primary = true,
                            selected = heroFocused && selectedAction == 0,
                        )
                        HeroActionButton(
                            label = "More Info",
                            primary = false,
                            selected = heroFocused && selectedAction == 1,
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    picks.take(6).forEachIndexed { dotIndex, _ ->
                        Box(
                            Modifier
                                .size(if (dotIndex == index) 7.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (dotIndex == index) Accent else Color.White.copy(alpha = .58f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroActionButton(
    label: String,
    icon: ImageVector? = null,
    primary: Boolean,
    selected: Boolean,
) {
    val background = when {
        selected && primary -> Accent
        primary -> AccentDim
        else -> Color.Transparent
    }
    val outline = when {
        selected && primary -> Color(0xFFFFA66A)
        selected -> Accent.copy(alpha = .98f)
        primary -> Accent.copy(alpha = .48f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .border(1.dp, outline, RoundedCornerShape(11.dp))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CuratedLanding(
    session: Session?,
    sections: List<HomeSection>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    onPlayItem: (PopItem) -> Unit,
    onPlayShow: (ShowSummary) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onMore: (HomeSection) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
    onContentFocus: (FocusRequester) -> Unit = {},
    onHeroLeft: (() -> Boolean)? = null,
) {
    // Layouts this build can draw. Anything else is a section type from a newer
    // server: skip it rather than render a blank row.
    val drawable = remember(sections) { sections.filter { it.layout in setOf("hero", "poster", "progress") } }
    if (drawable.isEmpty()) {
        EmptyState("No media found")
        return
    }
    val heroSection = drawable.firstOrNull { it.layout == "hero" }
    val shelves = remember(drawable) { drawable.filter { it !== heroSection && it.hasContent() } }
    val heroEntries = remember(heroSection) {
        heroSection?.entries.orEmpty().mapNotNull { recommendation ->
            recommendation.item?.let { heroItemPick(it, recommendation.reason) }
                ?: recommendation.show?.let { heroShowPick(it, recommendation.reason) }
        }
    }
    val heroKeys = remember(heroEntries) { heroEntries.map { it.key } }
    var heroIndex by remember(heroKeys) { mutableStateOf(0) }
    var initialFocusPending by remember { mutableStateOf(true) }
    // DOWN from the full-width hero would otherwise focus whatever card sits
    // under its center (and drift as the row scrolls); route it to the first
    // card of the first shelf instead.
    val firstShelfCardRequester = remember { FocusRequester() }
    val initialFocusTarget = when {
        heroEntries.isNotEmpty() -> "hero"
        else -> shelves.firstOrNull()?.id.orEmpty()
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
        contentPadding = PaddingValues(top = 10.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (heroEntries.isEmpty()) {
                BrowserHeader("Home", "")
            } else {
                HomeHero(
                    session = session,
                    picks = heroEntries,
                    index = heroIndex.coerceIn(0, heroEntries.lastIndex),
                    onIndexChange = { heroIndex = it },
                    autoFocus = initialFocusPending && initialFocusTarget == "hero",
                    onPlayItem = onPlayItem,
                    onPlayShow = onPlayShow,
                    onItem = onItem,
                    onShow = onShow,
                    onContentFocus = { requester ->
                        if (initialFocusTarget == "hero") initialFocusPending = false
                        onContentFocus(requester)
                    },
                    onDpadLeft = onHeroLeft,
                    // Falls back to default focus search when the first card is
                    // not composed (shelf scrolled far right, empty shelves).
                    onDpadDown = { runCatching { firstShelfCardRequester.requestFocus() }.isSuccess },
                )
            }
        }
        itemsIndexed(shelves, key = { _, section -> section.id }) { index, section ->
            val firstShelf = index == 0
            val moreVisible = section.more.isNotBlank() && section.contentSize() > HomeShelfLimit
            if (section.shows.isNotEmpty()) {
                PosterShelf(
                    section.title,
                    section.subtitle,
                    section.shows.take(HomeShelfLimit),
                    key = { it.title },
                    autoFocusFirst = firstShelf,
                    moreVisible = moreVisible,
                    onMore = { onMore(section) },
                ) { show, autoFocus ->
                    val fallback = remember { FocusRequester() }
                    val requester = if (autoFocus && firstShelf) firstShelfCardRequester else fallback
                    val focusNow = shouldInitialFocus(section.id, autoFocus)
                    val key = "${show.libraryId}\n${show.title.lowercase()}"
                    ShowCard(session, show, watched = completedShows.contains(key), watchlisted = watchlistShows.contains(key), autoFocus = focusNow, focusRequester = requester, onFocus = { onContentFocus(requester); consumeInitialFocus(section.id, autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            } else {
                PosterShelf(
                    section.title,
                    section.subtitle,
                    section.items.take(HomeShelfLimit),
                    key = { it.id },
                    autoFocusFirst = firstShelf,
                    moreVisible = moreVisible,
                    onMore = { onMore(section) },
                ) { item, autoFocus ->
                    val fallback = remember { FocusRequester() }
                    val requester = if (autoFocus && firstShelf) firstShelfCardRequester else fallback
                    val focusNow = shouldInitialFocus(section.id, autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, focusRequester = requester, onFocus = { onContentFocus(requester); consumeInitialFocus(section.id, autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
    }
}

private fun HomeSection.hasContent(): Boolean = items.isNotEmpty() || shows.isNotEmpty() || entries.isNotEmpty()

private fun HomeSection.contentSize(): Int = maxOf(items.size, shows.size, entries.size)

private const val HomeShelfLimit = 10

@Composable
fun ItemShelfView(
    session: Session?,
    title: String,
    items: List<PopItem>,
    completedItems: Set<Long>,
    watchlistItems: Set<Long>,
    onBack: () -> Unit,
    onItem: (PopItem) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
) {
    var initialFocusPending by remember(items.firstOrNull()?.id) { mutableStateOf(true) }
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
            Text(title, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${items.size} items", color = Muted, fontSize = 12.sp)
        }
        if (items.isEmpty()) {
            EmptyState("No items")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(122.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            gridItemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                val requester = remember { FocusRequester() }
                val focusNow = initialFocusPending && index == 0
                ItemCard(
                    session = session,
                    item = item,
                    watched = completedItems.contains(item.id),
                    watchlisted = watchlistItems.contains(item.id),
                    autoFocus = focusNow,
                    focusRequester = requester,
                    onFocus = { if (focusNow) initialFocusPending = false },
                    onClick = { onItem(item) },
                    onLongClick = { onItemMenu(item, requester) },
                )
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
    moreVisible: Boolean = false,
    onMore: (() -> Unit)? = null,
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
            if (moreVisible && onMore != null) {
                item(key = "more-$title") {
                    Box(Modifier.width(122.dp)) {
                        MoreCard(onClick = onMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreCard(onClick: () -> Unit) {
    CardShell(onClick = onClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(183.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceColor)
                .border(1.dp, Line, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("More", color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text("\u2192", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("Open full view", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
