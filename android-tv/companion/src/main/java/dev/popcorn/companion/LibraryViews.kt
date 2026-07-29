package dev.popcorn.companion

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.abs

// Minimum poster column width, shared by every poster grid so they all break
// to the same number of columns. Deliberately under 120dp: at 120 a typical
// 411dp-wide phone fits three columns without the alphabet gutter but only two
// with it, so reserving the rail cost a column.
val PosterColumnWidth = 110.dp

@Composable
fun BottomNavigation(page: Page, onHome: () -> Unit, onMovies: () -> Unit, onShows: () -> Unit, onSearch: () -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Accent,
        selectedTextColor = TextColor,
        indicatorColor = Accent.copy(alpha = .14f),
        unselectedIconColor = Muted,
        unselectedTextColor = Muted,
    )
    NavigationBar(
        containerColor = Surface1,
        contentColor = TextColor,
        tonalElevation = 0.dp,
        modifier = Modifier.animateContentSize(tween(180)).border(width = 0.5.dp, color = Line.copy(alpha = .45f)),
    ) {
        NavigationBarItem(
            selected = page is Page.Home,
            onClick = onHome,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") },
            colors = colors,
        )
        NavigationBarItem(
            selected = page is Page.Movies || page is Page.Detail && page.from is Page.Movies,
            onClick = onMovies,
            icon = { Icon(Icons.Default.Movie, contentDescription = null) },
            label = { Text("Movies") },
            colors = colors,
        )
        NavigationBarItem(
            selected = page is Page.Shows || page is Page.Show || page is Page.Season || page is Page.Detail && page.from !is Page.Movies,
            onClick = onShows,
            icon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
            label = { Text("TV") },
            colors = colors,
        )
        NavigationBarItem(
            selected = page is Page.Search,
            onClick = onSearch,
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Search") },
            colors = colors,
        )
    }
}

@Composable
fun HomePage(
    session: Session,
    continueMovies: List<PopItem>,
    continueEpisodes: List<PopItem>,
    resume: Map<Long, Float>,
    recentMovies: List<PopItem>,
    recentShows: List<ShowSummary>,
    topMovies: List<PopItem>,
    topShows: List<ShowSummary>,
    watchlistMovies: List<PopItem>,
    watchlistTvShows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    onMovie: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onEpisode: (PopItem) -> Unit,
) {
    val heroPicks = remember(continueMovies, continueEpisodes, recentMovies, recentShows, topMovies, topShows, watchlistMovies, watchlistTvShows) {
        buildList {
            (continueMovies + continueEpisodes).take(4).forEach { add(HomeHeroPick.fromItem(it, "Continue watching")) }
            watchlistMovies.take(2).forEach { add(HomeHeroPick.fromItem(it, "On your watchlist")) }
            watchlistTvShows.take(2).forEach { add(HomeHeroPick.fromShow(it, "On your watchlist")) }
            recentMovies.take(2).forEach { add(HomeHeroPick.fromItem(it, "New in your library")) }
            recentShows.take(2).forEach { add(HomeHeroPick.fromShow(it, "New in your library")) }
            topMovies.firstOrNull()?.let { add(HomeHeroPick.fromItem(it, "Maybe you missed this")) }
            topShows.firstOrNull()?.let { add(HomeHeroPick.fromShow(it, "Maybe you missed this")) }
        }.filter { it.backdropId > 0 && it.backdropVersion > 0 }.distinctBy { it.key }
    }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (heroPicks.isNotEmpty()) item { MobileHomeHero(session, heroPicks, onMovie, onShow, onEpisode) }
        if (continueMovies.isNotEmpty()) item { ContinueShelf("Continue Movies", "${continueMovies.size} in progress", session, continueMovies, resume, onMovie) }
        if (continueEpisodes.isNotEmpty()) item { ContinueShelf("Continue TV", "${continueEpisodes.size} episodes", session, continueEpisodes, resume, onEpisode) }
        if (recentMovies.isNotEmpty()) item { MovieShelf("Recently Added Movies", "${recentMovies.size} new", session, recentMovies, completedItems, watchlistItems, onMovie) }
        if (recentShows.isNotEmpty()) item { ShowShelf("Recently Added TV", "${recentShows.size} shows", session, recentShows, completedShows, watchlistShows, onShow) }
    }
}

private data class HomeHeroPick(
    val key: String,
    val kick: String,
    val title: String,
    val meta: String,
    val overview: String,
    val rating: Double,
    val backdropId: Long,
    val backdropVersion: Long,
    val item: PopItem? = null,
    val show: ShowSummary? = null,
) {
    companion object {
        fun fromItem(item: PopItem, kick: String): HomeHeroPick {
            val episode = item.kind == "episode"
            val title = if (episode) item.showTitle.ifBlank { item.title } else item.title
            val meta = if (episode) {
                listOf("S%02dE%02d".format(item.seasonNumber, item.episodeNumber), item.episodeTitle).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs), firstGenre(item.genres)?.lowercase()?.replaceFirstChar { it.uppercase() }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            }
            return HomeHeroPick("item:${item.id}", kick, title, meta, item.overview, item.rating, item.id, item.backdropMtimeUnix, item = item)
        }

        fun fromShow(show: ShowSummary, kick: String): HomeHeroPick {
            val meta = listOfNotNull(show.yearsLabel(), "${show.seasonCount} ${if (show.seasonCount == 1) "season" else "seasons"}", firstGenre(show.genres)?.lowercase()?.replaceFirstChar { it.uppercase() }).filter { it.isNotBlank() }.joinToString(" · ")
            return HomeHeroPick("show:${show.libraryId}:${show.title}", kick, show.title, meta, show.overview, show.rating, show.backdropItemId, show.backdropMtimeUnix, show = show)
        }
    }
}

@Composable
private fun MobileHomeHero(
    session: Session,
    picks: List<HomeHeroPick>,
    onMovie: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onEpisode: (PopItem) -> Unit,
) {
    var index by remember(picks.map { it.key }) { mutableStateOf(0) }
    LaunchedEffect(picks, index) {
        if (picks.size > 1) {
            delay(12_000)
            index = (index + 1) % picks.size
        }
    }
    val pick = picks[index.coerceIn(0, picks.lastIndex)]
    Crossfade(targetState = pick, animationSpec = tween(650), label = "homeHero") { current ->
        Box(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).background(Surface2)
                .clickable {
                    current.show?.let(onShow) ?: current.item?.let { if (it.kind == "episode") onEpisode(it) else onMovie(it) }
                },
        ) {
            AuthAsyncImage(session, imageUrl(session, current.backdropId, current.backdropVersion, "backdrop", ArtworkFull), null, Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Bg.copy(alpha = .96f), Bg.copy(alpha = .74f), Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = .12f), Bg.copy(alpha = .72f)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(.78f).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(current.kick, color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Teal.copy(alpha = .10f)).border(1.dp, Teal.copy(alpha = .34f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                Text(current.title, color = TextColor, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current.rating > 0) Text("★ %.1f".format(current.rating), color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (current.meta.isNotBlank()) Text(current.meta, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current.overview.isNotBlank()) Text(current.overview, color = TextColor.copy(alpha = .78f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("More info  ›", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Accent).padding(horizontal = 13.dp, vertical = 8.dp))
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                picks.take(6).forEachIndexed { dot, _ -> Box(Modifier.size(if (dot == index) 7.dp else 5.dp).clip(CircleShape).background(if (dot == index) Accent else Color.White.copy(alpha = .55f))) }
            }
        }
    }
}

@Composable
fun MediaGrid(
    title: String,
    session: Session,
    items: List<PopItem>,
    completedItems: Set<Long>,
    watchlistItems: Set<Long>,
    genres: List<String>,
    decades: List<Int>,
    alphabet: List<AlphabetEntry>,
    filters: LibraryFilters,
    loadingMore: Boolean,
    loadingPrevious: Boolean,
    hasMore: Boolean,
    hasPrevious: Boolean,
    scrollToken: Int,
    scrollIndex: Int,
    onFilters: (LibraryFilters) -> Unit,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    onAlphabet: (AlphabetEntry) -> Unit,
    onOpen: (PopItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val loadMore by remember(items.size, hasMore, loadingMore) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            hasMore && !loadingMore && items.isNotEmpty() && lastVisible >= items.lastIndex - 10
        }
    }
    val loadPrevious by remember(items.size, hasPrevious, loadingPrevious) {
        derivedStateOf {
            hasPrevious && !loadingPrevious && items.isNotEmpty() && gridState.firstVisibleItemIndex <= 8
        }
    }
    val visibleLetter by remember(items, gridState) {
        derivedStateOf { items.getOrNull(gridState.firstVisibleItemIndex)?.title?.alphabetLetter() }
    }
    LaunchedEffect(loadMore) { if (loadMore) onLoadMore() }
    LaunchedEffect(loadPrevious) { if (loadPrevious) onLoadPrevious() }
    LaunchedEffect(scrollToken) { if (items.isNotEmpty()) gridState.scrollToItem(scrollIndex.coerceIn(0, items.lastIndex)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LibraryTitle(title)
        LibraryFilterBar(genres, decades, filters, onFilters)
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterColumnWidth),
                state = gridState,
                contentPadding = PaddingValues(start = 0.dp, end = if (filters.reservesAlphabetRail()) 32.dp else 0.dp, top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.id }) { item -> MovieCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), onClick = { onOpen(item) }) }
                if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { LoadingMoreRow() }
            }
            if (filters.reservesAlphabetRail() && alphabet.isNotEmpty()) {
                AlphabetRail(
                    alphabet = alphabet,
                    currentLetter = visibleLetter,
                    onAlphabet = onAlphabet,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            if (loadingPrevious) PreviousLoadingIndicator(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
fun ShowGrid(
    title: String,
    session: Session,
    shows: List<ShowSummary>,
    completedShows: Set<String>,
    watchlistShows: Set<String>,
    genres: List<String>,
    decades: List<Int>,
    alphabet: List<AlphabetEntry>,
    filters: LibraryFilters,
    loadingMore: Boolean,
    loadingPrevious: Boolean,
    hasMore: Boolean,
    hasPrevious: Boolean,
    scrollToken: Int,
    scrollIndex: Int,
    onFilters: (LibraryFilters) -> Unit,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    onAlphabet: (AlphabetEntry) -> Unit,
    onShow: (ShowSummary) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val loadMore by remember(shows.size, hasMore, loadingMore) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            hasMore && !loadingMore && shows.isNotEmpty() && lastVisible >= shows.lastIndex - 10
        }
    }
    val loadPrevious by remember(shows.size, hasPrevious, loadingPrevious) {
        derivedStateOf {
            hasPrevious && !loadingPrevious && shows.isNotEmpty() && gridState.firstVisibleItemIndex <= 8
        }
    }
    val visibleLetter by remember(shows, gridState) {
        derivedStateOf { shows.getOrNull(gridState.firstVisibleItemIndex)?.title?.alphabetLetter() }
    }
    LaunchedEffect(loadMore) { if (loadMore) onLoadMore() }
    LaunchedEffect(loadPrevious) { if (loadPrevious) onLoadPrevious() }
    LaunchedEffect(scrollToken) { if (shows.isNotEmpty()) gridState.scrollToItem(scrollIndex.coerceIn(0, shows.lastIndex)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LibraryTitle(title)
        LibraryFilterBar(genres, decades, filters, onFilters)
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(PosterColumnWidth),
                state = gridState,
                contentPadding = PaddingValues(start = 0.dp, end = if (filters.reservesAlphabetRail()) 32.dp else 0.dp, top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shows, key = { it.libraryId + it.title }) { show -> ShowCard(session, show, watched = completedShows.contains(showMarkerKey(show)), watchlisted = watchlistShows.contains(showMarkerKey(show)), onClick = { onShow(show) }) }
                if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) { LoadingMoreRow() }
            }
            if (filters.reservesAlphabetRail() && alphabet.isNotEmpty()) {
                AlphabetRail(
                    alphabet = alphabet,
                    currentLetter = visibleLetter,
                    onAlphabet = onAlphabet,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            if (loadingPrevious) PreviousLoadingIndicator(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun LibraryTitle(title: String) {
    Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun LoadingMoreRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PreviousLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 10.dp).size(28.dp).clip(CircleShape).background(Bg.copy(alpha = .88f)), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AlphabetRail(
    alphabet: List<AlphabetEntry>,
    currentLetter: String?,
    onAlphabet: (AlphabetEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(alphabet) { alphabet.associateBy { it.letter.uppercase() } }
    val letters = remember { listOf("#") + ('A'..'Z').map { it.toString() } }
    val haptics = LocalHapticFeedback.current
    var railHeightPx by remember { mutableStateOf(0f) }
    var touchY by remember { mutableStateOf<Float?>(null) }
    var activeLetter by remember { mutableStateOf<String?>(null) }
    var activeEntry by remember { mutableStateOf<AlphabetEntry?>(null) }
    var lastDispatchedLetter by remember(alphabet) { mutableStateOf<String?>(null) }

    fun updateTouch(y: Float) {
        if (railHeightPx <= 0f) return
        val clampedY = y.coerceIn(0f, railHeightPx - 1f)
        val index = ((clampedY / railHeightPx) * letters.size).toInt().coerceIn(letters.indices)
        val letter = letters[index]
        if (letter != activeLetter) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        touchY = clampedY
        activeLetter = letter
        activeEntry = entries[letter]
    }

    fun dispatch(entry: AlphabetEntry?) {
        if (entry != null && entry.letter != lastDispatchedLetter) {
            lastDispatchedLetter = entry.letter
            onAlphabet(entry)
        }
    }

    LaunchedEffect(currentLetter) {
        if (touchY == null && currentLetter != lastDispatchedLetter) lastDispatchedLetter = null
    }

    Box(
        modifier
            .fillMaxHeight(.96f)
            .width(30.dp)
            .background(Surface1.copy(alpha = .94f), RoundedCornerShape(10.dp))
            .border(1.dp, Line.copy(alpha = .8f), RoundedCornerShape(10.dp))
            .onSizeChanged { railHeightPx = it.height.toFloat() }
            .pointerInput(alphabet, railHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    try {
                        updateTouch(down.position.y)
                        down.consume()
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                            if (pressed) updateTouch(change.position.y)
                            change.consume()
                        }
                        dispatch(activeEntry)
                    } finally {
                        touchY = null
                        activeLetter = null
                        activeEntry = null
                    }
                }
            }
            .padding(vertical = 3.dp, horizontal = 2.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End) {
            letters.forEachIndexed { index, letter ->
                val entry = entries[letter]
                val selected = (activeLetter ?: currentLetter) == letter
                val itemCenter = if (railHeightPx > 0f) railHeightPx * (index + .5f) / letters.size else 0f
                val distanceInRows = touchY?.let { abs(itemCenter - it) / (railHeightPx / letters.size).coerceAtLeast(1f) }
                val influence = distanceInRows?.let { (1f - it / 3.4f).coerceIn(0f, 1f) } ?: 0f
                val animatedInfluence by animateFloatAsState(
                    targetValue = influence,
                    animationSpec = spring(dampingRatio = .68f, stiffness = Spring.StiffnessMedium),
                    label = "alphabetBand",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset(x = (-27f * animatedInfluence).dp)
                        .clip(RoundedCornerShape(if (selected) 8.dp else 5.dp))
                        .background(
                            when {
                                selected && entry != null -> Accent
                                animatedInfluence > .08f -> Surface2.copy(alpha = .72f * animatedInfluence)
                                else -> Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter,
                        color = when {
                            entry == null -> Muted.copy(alpha = .26f)
                            selected -> Color.Black
                            else -> TextColor.copy(alpha = .86f)
                        },
                        fontSize = (8f + 6f * animatedInfluence).sp,
                        lineHeight = (8f + 6f * animatedInfluence).sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

private fun String.alphabetLetter(): String {
    val trimmed = trim()
    val lower = trimmed.lowercase()
    val article = listOf("the ", "a ", "an ", "der ", "die ", "das ", "ein ", "eine ").firstOrNull { lower.startsWith(it) }
    val first = (article?.let { trimmed.drop(it.length).trimStart() } ?: trimmed).firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

@Composable
fun SeasonList(
    session: Session,
    show: ShowSummary,
    seasons: List<SeasonSummary>,
    completedItems: Set<Long>,
    showWatched: Boolean,
    onBack: () -> Unit,
    onSeason: (SeasonSummary) -> Unit,
    onSetShowWatched: (Boolean) -> Unit,
    onSetSeasonWatched: (SeasonSummary, Boolean) -> Unit,
    onActor: (Actor) -> Unit,
) {
    var actors by remember(show.libraryId, show.title) { mutableStateOf<List<Actor>>(emptyList()) }
    var allEpisodes by remember(show.libraryId, show.title) { mutableStateOf<List<PopItem>>(emptyList()) }
    LaunchedEffect(show.libraryId, show.title) {
        actors = runCatching { Api(session).showActors(show.libraryId, show.title) }.getOrDefault(emptyList())
        allEpisodes = runCatching { Api(session).episodes(show.libraryId, show.title, -1) }.getOrDefault(emptyList())
    }
    val watchedSeasons = remember(allEpisodes, completedItems) {
        allEpisodes.groupBy { it.seasonNumber }
            .filterValues { eps -> eps.all { completedItems.contains(it.id) } }
            .keys
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ShowHero(session, show, onBack) }
        if (show.overview.isNotBlank()) {
            item {
                Column(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Overview", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(show.overview, color = TextColor, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        if (actors.isNotEmpty()) {
            item { CastStrip(session, actors, onActor) }
        }
        item {
            OutlinedButton(
                onClick = { onSetShowWatched(!showWatched) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (showWatched) "✓  Seen — tap to unmark" else "Mark show as seen",
                    color = if (showWatched) Accent else TextColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item { Text("Seasons", color = TextColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp)) }
        items(seasons, key = { it.seasonNumber }) { season ->
            val seasonWatched = watchedSeasons.contains(season.seasonNumber)
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).clickable { onSeason(season) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PosterImage(session, imageUrl(session, season.posterItemId, season.posterMtimeUnix, width = ArtworkCard), Modifier.width(72.dp))
                Column(Modifier.weight(1f)) {
                    Text(season.title.ifBlank { "Season ${season.seasonNumber}" }, color = TextColor, fontWeight = FontWeight.Bold)
                    Text("${season.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                    if (season.overview.isNotBlank()) Text(season.overview, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                SeenToggle(seasonWatched, onToggle = { onSetSeasonWatched(season, !seasonWatched) })
            }
        }
    }
}

// Small round check button used to mark seasons/episodes seen without opening them.
@Composable
fun SeenToggle(watched: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (watched) Accent else Surface2)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = if (watched) Color.Black else Muted, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

// ShowHero mirrors the movie DetailHero: full-bleed backdrop with a gradient
// scrim, poster, title, run years, season/episode counts and genre chips.
@Composable
fun ShowHero(session: Session, show: ShowSummary, onBack: () -> Unit) {
    val backdropUrl = if (show.backdropItemId > 0) imageUrl(session, show.backdropItemId, show.backdropMtimeUnix, "backdrop", ArtworkFull) else ""
    val genres = show.genres.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() }.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(322.dp)) {
            if (backdropUrl.isNotBlank()) {
                AuthAsyncImage(session, backdropUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Surface2, Bg))))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = .05f), 0.42f to Bg.copy(alpha = .28f), .78f to Bg.copy(alpha = .86f), 1f to Bg),
                ),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = .58f))
                    .border(1.dp, Color.White.copy(alpha = .13f), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                PosterImage(session, imageUrl(session, show.posterItemId, show.posterMtimeUnix, width = ArtworkCard), Modifier.width(104.dp), rating = show.rating)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(show.title, color = TextColor, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-.35).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(
                        show.yearsLabel().takeIf { it.isNotBlank() },
                        "${show.seasonCount} seasons",
                        "${show.episodeCount} episodes",
                    ).joinToString(" · ")
                    Text(meta, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (genres.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                genres.forEach { DetailChip(it.uppercase(), Surface2, Muted) }
            }
        }
    }
}

@Composable
fun EpisodeList(session: Session, show: ShowSummary, season: SeasonSummary, episodes: List<PopItem>, completedItems: Set<Long>, watchlistItems: Set<Long>, onBack: () -> Unit, onOpen: (PopItem) -> Unit, onSetWatched: (PopItem, Boolean) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { HeaderBack("${show.title} \u00b7 ${season.title.ifBlank { "Season ${season.seasonNumber}" }}", onBack) }
        items(episodes, key = { it.id }) { episode ->
            val watched = completedItems.contains(episode.id)
            EpisodeCard(session, episode, watched = watched, watchlisted = watchlistItems.contains(episode.id), onOpen = { onOpen(episode) }, onToggleWatched = { onSetWatched(episode, !watched) })
        }
    }
}

@Composable
fun EpisodeCard(session: Session, episode: PopItem, watched: Boolean, watchlisted: Boolean, onOpen: () -> Unit, onToggleWatched: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EpisodeThumb(session, episode, watched, watchlisted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                episode.episodeTitle.ifBlank { episode.title },
                color = TextColor,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(
                "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber),
                fmtDuration(episode.durationMs).ifBlank { null },
                episode.rating.takeIf { it > 0 }?.let { "%.1f".format(it) },
            ).filterNotNull().joinToString(" \u00b7 ")
            Text(meta, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (episode.overview.isNotBlank()) {
                Text(episode.overview, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onToggleWatched != null) {
            SeenToggle(watched, onToggle = onToggleWatched, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
fun EpisodeThumb(session: Session, episode: PopItem, watched: Boolean, watchlisted: Boolean) {
    Box(
        Modifier
            .width(118.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2),
        contentAlignment = Alignment.BottomStart,
    ) {
        val thumbUrl = if (episode.backdropMtimeUnix > 0) imageUrl(session, episode.id, episode.backdropMtimeUnix, "backdrop", ArtworkCard) else ""
        if (thumbUrl.isNotBlank()) {
            AuthAsyncImage(session, thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        PosterStatusBadges(watched, watchlisted)
        Text(
            "%02d".format(episode.episodeNumber),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = .68f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun HistoryPage(session: Session, onBack: () -> Unit, onOpen: (PopItem) -> Unit) {
    var history by remember(session.token) { mutableStateOf<WatchHistory?>(null) }
    var error by remember(session.token) { mutableStateOf("") }
    LaunchedEffect(session.token) {
        runCatching { Api(session).watchHistory() }
            .onSuccess { history = it; error = "" }
            .onFailure { error = it.message ?: "Failed to load watch history" }
    }

    when {
        error.isNotBlank() -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            HeaderBack("Watch history", onBack)
            StatusMessage(error, success = false, modifier = Modifier.fillMaxWidth())
        }
        history == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        else -> {
            val result = history!!
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                        HeaderBack("Watch history", onBack)
                        Text(
                            if (result.source == "trakt") "${result.items.size} plays · Trakt" else "${result.items.size} plays · This server",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (result.items.isEmpty()) {
                    item { Text("No watch history yet", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 28.dp)) }
                } else {
                    items(result.items, key = { it.id }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(66.dp)
                                .clickable(enabled = entry.item != null) { entry.item?.let(onOpen) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                if (entry.kind == "movie") Icons.Default.Movie else Icons.Default.LiveTv,
                                contentDescription = entry.kind,
                                tint = if (entry.kind == "movie") Accent else Teal,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val details = listOfNotNull(entry.subtitle.takeIf { it.isNotBlank() }, entry.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                                if (details.isNotBlank()) Text(details, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(historyTimestamp(entry.watchedAt), color = Muted, fontSize = 10.sp)
                                if (entry.item == null) Text("Trakt", color = Teal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun historyTimestamp(value: String): String {
    val normalized = value.trim().replace('T', ' ')
    if (normalized.length < 16) return normalized
    return "${normalized.substring(0, 10)} · ${normalized.substring(11, 16)}"
}

private sealed interface MobileSearchResult {
    data class Movie(val item: PopItem) : MobileSearchResult
    data class Show(val show: ShowSummary) : MobileSearchResult
}

@Composable
fun SearchPage(
    session: Session,
    query: String,
    onQuery: (String) -> Unit,
    scope: String,
    onScope: (String) -> Unit,
    fields: Set<String>,
    onFields: (Set<String>) -> Unit,
    movies: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    loading: Boolean,
    error: String,
    onMovie: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
) {
    val results: List<MobileSearchResult> = shows.map { MobileSearchResult.Show(it) } + movies.map { MobileSearchResult.Movie(it) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LibraryTitle("Search")
        PopTextField(query, onQuery, "Search movies and TV shows")
        SearchOptionStrip("SCOPE", listOf("both" to "Movies & TV", "movies" to "Movies", "tv" to "TV shows"), scope) { onScope(it) }
        SearchOptionStrip(
            "SEARCH IN",
            listOf("title" to "Title", "original" to "Original title", "people" to "People", "description" to "Description"),
            selected = "",
            selectedValues = fields,
        ) { value ->
            val updated = if (value in fields) fields - value else fields + value
            if (updated.isNotEmpty()) onFields(updated)
        }
        Spacer(Modifier.height(6.dp))
        when {
            query.trim().length < 2 -> SearchState("Search your library", "Titles are searched by default. Add other fields when you need them.")
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp)) }
            error.isNotBlank() -> SearchState("Search failed", error)
            results.isEmpty() -> SearchState("Nothing found for \"${query.trim()}\"", "Try another phrase or include another search field.")
            else -> {
                Text("${results.size} results", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(PosterColumnWidth),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = {
                        when (it) {
                            is MobileSearchResult.Movie -> "movie:${it.item.id}"
                            is MobileSearchResult.Show -> "show:${it.show.libraryId}:${it.show.title}"
                        }
                    }) { result ->
                        when (result) {
                            is MobileSearchResult.Movie -> MovieCard(session, result.item, watched = result.item.id in completedItems, watchlisted = result.item.id in watchlistItems) { onMovie(result.item) }
                            is MobileSearchResult.Show -> ShowCard(session, result.show, watched = showMarkerKey(result.show) in completedShows, watchlisted = showMarkerKey(result.show) in watchlistShows) { onShow(result.show) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchOptionStrip(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    selectedValues: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach { (value, text) ->
                val active = value == selected || value in selectedValues
                Row(
                    Modifier.clip(RoundedCornerShape(99.dp)).background(if (active) Accent.copy(alpha = .15f) else Surface2)
                        .border(1.dp, if (active) Accent.copy(alpha = .62f) else Line, RoundedCornerShape(99.dp))
                        .clickable { onSelect(value) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (active) Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(13.dp))
                    Text(text, color = if (active) TextColor else Muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SearchState(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Teal.copy(alpha = .72f), modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

private enum class LibraryFilterPage { Sort, Seen, Rating, Genre, Decade }

@Composable
fun LibraryFilterBar(genres: List<String>, decades: List<Int>, filters: LibraryFilters, onFilters: (LibraryFilters) -> Unit) {
    var page by remember { mutableStateOf<LibraryFilterPage?>(null) }
    val selectedGenres = splitFilterValues(filters.genre)
    val selectedDecades = splitFilterValues(filters.decades)
    val genreLabel = when (selectedGenres.size) {
        0 -> "Genre: All"
        1 -> "Genre: ${selectedGenres.first()}"
        else -> "Genre: ${selectedGenres.size} selected"
    }
    val decadeLabel = when (selectedDecades.size) {
        0 -> "Decade: All"
        1 -> "Decade: ${selectedDecades.first()}s"
        else -> "Decade: ${selectedDecades.size} selected"
    }
    val active = filters != LibraryFilters()

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterPill("Sort: ${librarySortLabel(filters.sort)}", filters.sort.isNotBlank()) { page = LibraryFilterPage.Sort }
        FilterPill(seenFilterLabel(filters.seenStatus), filters.seenStatus.isNotBlank()) { page = LibraryFilterPage.Seen }
        FilterPill(ratingFilterLabel(filters.minRating), filters.minRating > 0) { page = LibraryFilterPage.Rating }
        FilterPill(genreLabel, selectedGenres.isNotEmpty()) { page = LibraryFilterPage.Genre }
        FilterPill(decadeLabel, selectedDecades.isNotEmpty()) { page = LibraryFilterPage.Decade }
        if (active) FilterPill("Clear", selected = false) { onFilters(LibraryFilters()) }
    }

    page?.let { selectedPage ->
        LibraryFilterSheet(
            page = selectedPage,
            genres = genres,
            decades = decades,
            filters = filters,
            onDismiss = { page = null },
            onFilters = onFilters,
        )
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) TextColor else Muted,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) Accent.copy(alpha = .16f) else Surface2)
            .border(1.dp, if (selected) Accent.copy(alpha = .62f) else Line, RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterSheet(
    page: LibraryFilterPage,
    genres: List<String>,
    decades: List<Int>,
    filters: LibraryFilters,
    onDismiss: () -> Unit,
    onFilters: (LibraryFilters) -> Unit,
) {
    val selectedGenres = splitFilterValues(filters.genre)
    val selectedDecades = splitFilterValues(filters.decades)
    val title = when (page) {
        LibraryFilterPage.Sort -> "Sort"
        LibraryFilterPage.Seen -> "Seen status"
        LibraryFilterPage.Rating -> "IMDb rating"
        LibraryFilterPage.Genre -> "Genres"
        LibraryFilterPage.Decade -> "Decades"
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Bg,
        contentColor = TextColor,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(top = 11.dp, bottom = 5.dp).size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(99.dp)).background(Line))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 26.dp)) {
            Text("FILTERS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(7.dp))
            Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (page) {
                    LibraryFilterPage.Sort -> items(librarySortOptions(), key = { "sort_${it.first}" }) { option ->
                        FilterSheetRow(option.second, filters.sort == option.first) {
                            onDismiss()
                            onFilters(filters.copy(sort = option.first))
                        }
                    }
                    LibraryFilterPage.Seen -> items(seenFilterOptions(), key = { "seen_${it.first}" }) { option ->
                        FilterSheetRow(option.second, filters.seenStatus == option.first) {
                            onDismiss()
                            onFilters(filters.copy(seenStatus = option.first))
                        }
                    }
                    LibraryFilterPage.Rating -> items(ratingFilterOptions(), key = { "rating_${it.first}" }) { option ->
                        FilterSheetRow(option.second, filters.minRating == option.first) {
                            onDismiss()
                            onFilters(filters.copy(minRating = option.first))
                        }
                    }
                    LibraryFilterPage.Genre -> {
                        item(key = "genre_all") {
                            FilterSheetRow("All genres", selectedGenres.isEmpty()) { onFilters(filters.copy(genre = "")) }
                        }
                        items(genres, key = { "genre_$it" }) { genre ->
                            FilterSheetRow(genre, selectedGenres.any { it.equals(genre, ignoreCase = true) }) {
                                onFilters(filters.copy(genre = toggleFilterValue(selectedGenres, genre).joinToString(",")))
                            }
                        }
                    }
                    LibraryFilterPage.Decade -> {
                        item(key = "decade_all") {
                            FilterSheetRow("All decades", selectedDecades.isEmpty()) { onFilters(filters.copy(decades = "")) }
                        }
                        items(decades.sortedDescending(), key = { "decade_$it" }) { decade ->
                            val value = decade.toString()
                            FilterSheetRow("${decade}s", value in selectedDecades) {
                                onFilters(filters.copy(decades = toggleFilterValue(selectedDecades, value).joinToString(",")))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = .13f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) TextColor else Muted, fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Accent, modifier = Modifier.size(20.dp))
    }
}

private fun librarySortOptions() = listOf(
    "" to "Name A-Z",
    "title_desc" to "Name Z-A",
    "mtime" to "File date newest",
    "mtime_asc" to "File date oldest",
    "year_desc" to "Release year newest",
    "year" to "Release year oldest",
    "rating" to "IMDb rating highest",
    "rating_asc" to "IMDb rating lowest",
)

private fun seenFilterOptions() = listOf("" to "All", "started" to "Started", "seen" to "Seen", "unseen" to "Unseen")
private fun ratingFilterOptions() = listOf(0.0 to "All", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+")
private fun librarySortLabel(sort: String) = librarySortOptions().firstOrNull { it.first == sort }?.second ?: "Name A-Z"
private fun seenFilterLabel(status: String) = "Seen: ${seenFilterOptions().firstOrNull { it.first == status }?.second ?: "All"}"
private fun ratingFilterLabel(rating: Double) = "IMDb: ${ratingFilterOptions().firstOrNull { it.first == rating }?.second ?: "All"}"
private fun splitFilterValues(value: String) = value.split(",", "|").map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
private fun toggleFilterValue(selected: List<String>, value: String): List<String> =
    if (selected.any { it.equals(value, ignoreCase = true) }) selected.filterNot { it.equals(value, ignoreCase = true) } else selected + value

@Composable
fun HeaderBack(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Text(title, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MovieShelf(title: String, subtitle: String = "", session: Session, items: List<PopItem>, completedItems: Set<Long>, watchlistItems: Set<Long>, onClick: (PopItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShelfHeading(title, subtitle)
        if (items.isEmpty()) {
            Text("Nothing here yet", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item ->
                    MovieCard(session, item, Modifier.width(150.dp), watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), onClick = { onClick(item) })
                }
            }
        }
    }
}

@Composable
fun ShowShelf(title: String, subtitle: String = "", session: Session, shows: List<ShowSummary>, completedShows: Set<String>, watchlistShows: Set<String>, onClick: (ShowSummary) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShelfHeading(title, subtitle)
        if (shows.isEmpty()) {
            Text("Nothing here yet", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shows, key = { it.libraryId + it.title }) { show ->
                    ShowCard(session, show, Modifier.width(150.dp), watched = completedShows.contains(showMarkerKey(show)), watchlisted = watchlistShows.contains(showMarkerKey(show)), onClick = { onClick(show) })
                }
            }
        }
    }
}

@Composable
fun MovieCard(session: Session, item: PopItem, modifier: Modifier = Modifier, watched: Boolean = false, watchlisted: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        PosterImage(session, imageUrl(session, item.id, item.posterMtimeUnix, width = ArtworkCard), Modifier.fillMaxWidth(), watched = watched, watchlisted = watchlisted, rating = item.rating)
        Spacer(Modifier.height(7.dp))
        Text(item.title, color = TextColor, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" \u00b7 "), color = Muted, fontSize = 13.sp, maxLines = 1)
        firstGenre(item.genres)?.let { Text(it, color = Teal.copy(alpha = .86f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun ShowCard(session: Session, show: ShowSummary, modifier: Modifier = Modifier, watched: Boolean = false, watchlisted: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        PosterImage(session, imageUrl(session, show.posterItemId, show.posterMtimeUnix, width = ArtworkCard), Modifier.fillMaxWidth(), watched = watched, watchlisted = watchlisted, rating = show.rating)
        Spacer(Modifier.height(7.dp))
        Text(show.title, color = TextColor, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(show.yearsLabel().takeIf { it.isNotBlank() }, "${show.seasonCount} seasons").joinToString(" · "),
            color = Muted, fontSize = 13.sp, maxLines = 1,
        )
        firstGenre(show.genres)?.let { Text(it, color = Teal.copy(alpha = .86f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

fun firstGenre(genres: String): String? = genres.split(Regex("[,;/]")).map { it.trim() }.firstOrNull { it.isNotBlank() }?.uppercase()

@Composable
fun PosterImage(session: Session, url: String, modifier: Modifier, watched: Boolean = false, watchlisted: Boolean = false, progress: Float = 0f, rating: Double = 0.0) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(Surface2).border(1.dp, Line.copy(alpha = .65f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AuthAsyncImage(session, url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("?", color = Muted)
        PosterCornerMarks(watched, watchlisted, rating)
        if (progress in 0.01f..0.999f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(5.dp).height(4.dp).clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = .58f))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(RoundedCornerShape(99.dp)).background(Accent))
            }
        }
    }
}

// PosterCornerMarks puts state and rating in the poster's corners: watchlist
// top left, seen bottom left, rating top right. A bar across the top was one
// solid stripe of artwork lost on every card. A mark takes only its corner and
// carries no backing at all — a dark halo around the glyph itself keeps it
// readable on white or busy artwork, where a scrim would have shown as a
// smudge.
@Composable
fun androidx.compose.foundation.layout.BoxScope.PosterCornerMarks(watched: Boolean, watchlisted: Boolean, rating: Double = 0.0) {
    if (watchlisted) {
        // A ribbon off the top edge, carrying no glyph: the silhouette is the
        // bookmark, which is why it survives at any size and on any artwork
        // where a thin white outline would not. Cool against the seen wedge's
        // warm accent, so the card says colour temperature before it says
        // shape. Kept clear of the poster's corner radius so its top edge is
        // not nicked by the rounding.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp)
                .size(width = 18.dp, height = 28.dp)
                .clip(WatchlistRibbon)
                .background(Teal),
        )
    }
    if (watched) {
        // Seen is the one mark that says something happened, so it fills its
        // corner rather than sitting in it as a glyph: a wedge of accent is
        // found at a glance scanning a grid, where a check has to be looked
        // for. The poster Box holding these marks is already clipped to its
        // rounded shape, so the wedge's outer corner rounds with the artwork.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(46.dp)
                .clip(SeenWedge)
                .background(Accent.copy(alpha = .82f)),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Icon(
                Icons.Filled.Check,
                "Seen",
                Modifier.padding(end = 7.dp, bottom = 7.dp).size(15.dp),
                tint = Color.White,
            )
        }
    }
    if (rating > 0.0) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The star keeps the accent so a score still reads as a score at a
            // glance; the number stays white, since orange on pale artwork does
            // not.
            HaloIcon(Icons.Filled.Star, null, 12.dp, tint = Accent)
            Text(
                "%.1f".format(rating),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 0f), blurRadius = 4f)),
            )
        }
    }
}

// A tab with a notch cut out of its bottom edge — a bookmark hanging from the
// top of the poster.
private val WatchlistRibbon = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.74f)
    lineTo(0f, size.height)
    close()
}

// A triangle filling the bottom right corner of its box.
private val SeenWedge = GenericShape { size, _ ->
    moveTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

// Compose has no drop-shadow filter for a vector glyph, so the halo is a
// slightly larger black copy drawn behind the real one.
@Composable
private fun HaloIcon(icon: ImageVector, description: String?, size: Dp, tint: Color = Color.White) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size + 3.dp), tint = Color.Black.copy(alpha = .62f))
        Icon(icon, description, Modifier.size(size), tint = tint)
    }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.PosterStatusBadges(watched: Boolean, watchlisted: Boolean) {
    if (!watched && !watchlisted) return
    Column(
        Modifier.align(Alignment.TopStart).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (watched) PosterStateBadge(Icons.Filled.Check, "Seen", Accent, filled = true)
        if (watchlisted) PosterStateBadge(Icons.Filled.Bookmark, "Watchlist", Teal)
    }
}

@Composable
private fun PosterStateBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: Color, filled: Boolean = false) {
    Box(
        Modifier.size(20.dp).then(
            if (filled) Modifier.clip(CircleShape).background(tint) else Modifier,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (filled) Bg else tint, modifier = Modifier.size(if (filled) 13.dp else 17.dp))
    }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.PosterRating(rating: Double) {
    Text(
        "★ %.1f".format(rating),
        color = Gold,
        fontWeight = FontWeight.Black,
        fontSize = 11.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
fun ContinueShelf(title: String, subtitle: String = "", session: Session, items: List<PopItem>, resume: Map<Long, Float>, onClick: (PopItem) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShelfHeading(title, subtitle)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                ContinueCard(session, item, resume[item.id] ?: 0f, Modifier.width(150.dp)) { onClick(item) }
            }
        }
    }
}

@Composable
private fun ShelfHeading(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, color = TextColor, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.2).sp)
        if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ContinueCard(session: Session, item: PopItem, progress: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isEpisode = item.kind == "episode"
    Column(modifier.clickable(onClick = onClick)) {
        PosterImage(session, imageUrl(session, item.id, item.posterMtimeUnix, width = ArtworkCard), Modifier.fillMaxWidth(), progress = progress)
        Spacer(Modifier.height(6.dp))
        Text(
            if (isEpisode) item.showTitle.ifBlank { item.title } else item.title,
            color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (isEpisode) "S%02dE%02d".format(item.seasonNumber, item.episodeNumber)
            else listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" · "),
            color = Muted, fontSize = 13.sp, maxLines = 1,
        )
    }
}

@Composable
fun MarkerBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        label,
        color = Color.Black,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .padding(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = .94f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

fun showMarkerKey(show: ShowSummary): String = "${show.libraryId}\n${show.title.lowercase()}"

@Composable
fun AuthAsyncImage(session: Session, url: String, contentDescription: String?, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val request = remember(url, session.token) {
        val builder = ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
        if (session.token.isNotBlank() && url.startsWith(session.server)) {
            builder.addHeader("Authorization", "Bearer ${session.token}")
        }
        builder.build()
    }
    AsyncImage(model = request, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}

@Composable
fun SearchRow(title: String, meta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick).padding(12.dp)) {
        Text(title, color = TextColor, fontWeight = FontWeight.Bold)
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun NavButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Accent else Surface2, contentColor = if (selected) Color.Black else TextColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier,
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}
