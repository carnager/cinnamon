package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    recentMovies: List<PopItem>,
    recentShows: List<ShowSummary>,
    watchlistMovies: List<PopItem>,
    watchlistTvShows: List<ShowSummary>,
    error: String,
    loading: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(session, libraries, selected = "home", onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onLogout = onLogout)
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        } else {
            CuratedLanding(session, items, shows, completedItems, completedShows, watchlistItems, watchlistShows, recentMovies, recentShows, watchlistMovies, watchlistTvShows, onItem, onShow, onItemMenu, onShowMenu)
        }
    }
}

@Composable
fun TopBar(session: Session?, libraries: List<Library>, selected: String, onHome: () -> Unit, onLibrary: (Library) -> Unit, onWatchlist: () -> Unit, onSearch: () -> Unit, onLogout: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceColor)
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
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var initialFocusPending by remember(movies.firstOrNull()?.id, shows.firstOrNull()?.title) { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(session, libraries, selected = "watchlist", onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onLogout = onLogout)
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (movies.isEmpty() && shows.isEmpty()) {
            EmptyState("No watchlist items")
            return@Column
        }
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
    genres: List<String>,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onGenre: (String) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var genrePanelOpen by remember(activeLibrary.id) { mutableStateOf(false) }
    val genreFocus = remember { FocusRequester() }
    var restoreGridFocus by remember(activeLibrary.id, pageIndex, selectedGenre) { mutableStateOf<FocusRequester?>(null) }
    fun closeGenrePanel() {
        genrePanelOpen = false
        restoreGridFocus?.requestFocus()
    }
    BackHandler(enabled = genrePanelOpen) {
        closeGenrePanel()
    }
    LaunchedEffect(genrePanelOpen) {
        if (genrePanelOpen) {
            delay(80)
            genreFocus.requestFocus()
        }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(session, libraries, selected = activeLibrary.id, onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onLogout = onLogout)
            if (error.isNotBlank()) {
                Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val genreSuffix = if (selectedGenre.isBlank()) "" else " / $selectedGenre"
                Text("${activeLibrary.name}$genreSuffix", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Pill("Prev", selected = false, onClick = { if (pageIndex > 0) onPreviousPage() })
                Text("Page ${pageIndex + 1}", color = Muted, fontSize = 12.sp)
                Pill("Next", selected = false, onClick = { if (pageHasNext) onNextPage() })
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                } else if (activeLibrary.type == "tv") {
                    PosterGrid(entries = shows, key = { it.title }) { show, autoFocus, column, focusRequester ->
                        ShowCard(
                            session = session,
                            show = show,
                            watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                            watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                            autoFocus = autoFocus,
                            focusRequester = focusRequester,
                            onFocus = { restoreGridFocus = focusRequester },
                            onLeftEdge = {
                                if (column == 0) {
                                    genrePanelOpen = true
                                    true
                                } else {
                                    false
                                }
                            },
                            onClick = { onShow(show) },
                            onLongClick = { requester -> onShowMenu(show, requester) },
                        )
                    }
                } else {
                    PosterGrid(entries = items, key = { it.id }) { item, autoFocus, column, focusRequester ->
                        ItemCard(
                            session = session,
                            item = item,
                            watched = completedItems.contains(item.id),
                            watchlisted = watchlistItems.contains(item.id),
                            autoFocus = autoFocus,
                            focusRequester = focusRequester,
                            onFocus = { restoreGridFocus = focusRequester },
                            onLeftEdge = {
                                if (column == 0) {
                                    genrePanelOpen = true
                                    true
                                } else {
                                    false
                                }
                            },
                            onClick = { onItem(item) },
                            onLongClick = { requester -> onItemMenu(item, requester) },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = genrePanelOpen,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(180)) { -it },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it },
        ) {
            GenreSidePanel(
                genres = genres,
                selectedGenre = selectedGenre,
                focusRequester = genreFocus,
                onGenre = {
                    closeGenrePanel()
                    onGenre(it)
                },
                onClose = ::closeGenrePanel,
            )
        }
    }
}

@Composable
fun GenreSidePanel(
    genres: List<String>,
    selectedGenre: String,
    focusRequester: FocusRequester,
    onGenre: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .45f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionRight || it.key == Key.Back)) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .width(250.dp)
                .fillMaxSize()
                .background(SurfaceColor)
                .border(1.dp, Line)
                .padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 18.dp),
        ) {
            Text("Genres", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "all") {
                    GenreFilterRow(
                        text = "All",
                        selected = selectedGenre.isBlank(),
                        modifier = Modifier.focusRequester(focusRequester),
                        onClick = { onGenre("") },
                    )
                }
                items(genres, key = { it }) { genre ->
                    GenreFilterRow(
                        text = genre,
                        selected = genre.equals(selectedGenre, ignoreCase = true),
                        modifier = Modifier,
                        onClick = { onGenre(genre) },
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
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
        if (watchlistMovies.isNotEmpty()) {
            item {
                PosterShelf("Watchlist Movies", "${watchlistMovies.size} saved", watchlistMovies, key = { it.id }, autoFocusFirst = true) { item, autoFocus ->
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
                PosterShelf("Recently Added Movies", "${recentMovies.size} new", recentMovies, key = { it.id }, autoFocusFirst = watchlistMovies.isEmpty() && watchlistTvShows.isEmpty()) { item, autoFocus ->
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
                .background(SurfaceColor)
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
                modifier = Modifier.width(220.dp).fillMaxSize().background(SurfaceColor.copy(alpha = .55f)),
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
                    PosterGrid(entries = filtered, key = { it.title }) { show, _, _, _ ->
                        ShowCard(session, show, autoFocus = false, onClick = { onShow(show) })
                    }
                } else {
                    val filtered = itemGenrePairs.filter { (_, genres) -> genres.any { it.equals(selectedGenre, ignoreCase = true) } }.map { it.first }
                    BrowserHeader(selectedGenre, "${filtered.size} movies")
                    PosterGrid(entries = filtered, key = { it.id }) { item, _, _, _ ->
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
