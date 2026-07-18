package dev.popcorn.companion

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun BottomNavigation(page: Page, onHome: () -> Unit, onMovies: () -> Unit, onShows: () -> Unit, onSearch: () -> Unit, onRemote: () -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.animateContentSize(tween(180)),
    ) {
        NavigationBarItem(
            selected = page is Page.Home,
            onClick = onHome,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = page is Page.Movies || page is Page.Detail && page.from is Page.Movies,
            onClick = onMovies,
            icon = { Icon(Icons.Default.Movie, contentDescription = null) },
            label = { Text("Movies") },
        )
        NavigationBarItem(
            selected = page is Page.Shows || page is Page.Show || page is Page.Season || page is Page.Detail && page.from !is Page.Movies,
            onClick = onShows,
            icon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
            label = { Text("TV") },
        )
        NavigationBarItem(
            selected = page is Page.Search,
            onClick = onSearch,
            icon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Search") },
        )
        NavigationBarItem(
            selected = page is Page.Remote,
            onClick = onRemote,
            icon = { Icon(Icons.Default.SettingsRemote, contentDescription = null) },
            label = { Text("Remote") },
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
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    onMovie: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onEpisode: (PopItem) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (continueMovies.isNotEmpty()) item { ContinueShelf("Continue Watching", session, continueMovies, resume, onMovie) }
        if (continueEpisodes.isNotEmpty()) item { ContinueShelf("Continue Watching · TV", session, continueEpisodes, resume, onEpisode) }
        item { MovieShelf("Recently Added Movies", session, recentMovies, completedItems, watchlistItems, onMovie) }
        item { ShowShelf("Recently Added TV", session, recentShows, completedShows, watchlistShows, onShow) }
        if (topMovies.isNotEmpty()) item { MovieShelf("Top Rated Movies", session, topMovies, completedItems, watchlistItems, onMovie) }
        if (topShows.isNotEmpty()) item { ShowShelf("Top Rated TV", session, topShows, completedShows, watchlistShows, onShow) }
    }
}

@Composable
fun MediaGrid(title: String, session: Session, items: List<PopItem>, completedItems: Set<Long>, watchlistItems: Set<Long>, pageIndex: Int, genres: List<String>, filters: LibraryFilters, onFilters: (LibraryFilters) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onOpen: (PopItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        PagingHeader(title, pageIndex, onPrev, onNext)
        FilterBar(genres, filters, onFilters)
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.id }) { item -> MovieCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), onClick = { onOpen(item) }) }
        }
    }
}

@Composable
fun ShowGrid(title: String, session: Session, shows: List<ShowSummary>, completedShows: Set<String>, watchlistShows: Set<String>, pageIndex: Int, genres: List<String>, filters: LibraryFilters, onFilters: (LibraryFilters) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onShow: (ShowSummary) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        PagingHeader(title, pageIndex, onPrev, onNext)
        FilterBar(genres, filters, onFilters)
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shows, key = { it.libraryId + it.title }) { show -> ShowCard(session, show, watched = completedShows.contains(showMarkerKey(show)), watchlisted = watchlistShows.contains(showMarkerKey(show)), onClick = { onShow(show) }) }
        }
    }
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
            item { CastStrip(session, actors) }
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
                PosterImage(session, imageUrl(session, season.posterItemId, season.posterMtimeUnix), Modifier.width(72.dp))
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
    val backdropUrl = if (show.backdropItemId > 0) imageUrl(session, show.backdropItemId, show.backdropMtimeUnix, "backdrop") else ""
    val genres = show.genres.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() }.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (backdropUrl.isNotBlank()) {
                AuthAsyncImage(session, backdropUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Surface2, Bg))))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.45f to Bg.copy(alpha = 0.25f), 1f to Bg),
                ),
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("Back") }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                PosterImage(session, imageUrl(session, show.posterItemId, show.posterMtimeUnix), Modifier.width(96.dp), rating = show.rating)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(show.title, color = TextColor, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
        val thumbUrl = if (episode.backdropMtimeUnix > 0) imageUrl(session, episode.id, episode.backdropMtimeUnix, "backdrop") else ""
        if (thumbUrl.isNotBlank()) {
            AuthAsyncImage(session, thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (watched) MarkerBadge("Seen", Accent, Modifier.align(Alignment.TopStart))
        if (watchlisted) MarkerBadge("List", Color(0xFFFFD166), Modifier.align(Alignment.TopEnd))
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
fun SearchPage(session: Session, query: String, onQuery: (String) -> Unit, genres: List<String>, filters: LibraryFilters, onFilters: (LibraryFilters) -> Unit, movies: List<PopItem>, shows: List<ShowSummary>, onMovie: (PopItem) -> Unit, onShow: (ShowSummary) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PopTextField(query, onQuery, "Search movies and shows") }
        item { FilterBar(genres, filters, onFilters) }
        if (shows.isNotEmpty()) item { Text("Shows", color = TextColor, fontWeight = FontWeight.Bold) }
        items(shows, key = { it.libraryId + it.title }) { show -> SearchRow(title = show.title, meta = listOfNotNull(show.yearsLabel().takeIf { it.isNotBlank() }, "${show.seasonCount} seasons \u00b7 ${show.episodeCount} episodes").joinToString(" \u00b7 "), onClick = { onShow(show) }) }
        if (movies.isNotEmpty()) item { Text("Movies", color = TextColor, fontWeight = FontWeight.Bold) }
        items(movies, key = { it.id }) { item -> SearchRow(title = item.title, meta = listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" \u00b7 "), onClick = { onMovie(item) }) }
    }
}

@Composable
fun FilterBar(genres: List<String>, filters: LibraryFilters, onFilters: (LibraryFilters) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterMenu(
            label = "Sort",
            value = sortLabel(filters.sort),
            options = listOf("" to "Title", "mtime" to "File date", "rating" to "Rating", "recent" to "Added"),
            onSelect = { onFilters(filters.copy(sort = it)) },
        )
        FilterMenu(
            label = "Genre",
            value = filters.genre.ifBlank { "All" },
            options = listOf("" to "All") + genres.map { it to it },
            onSelect = { onFilters(filters.copy(genre = it)) },
        )
        FilterMenu(
            label = "Rating",
            value = if (filters.minRating > 0) "${filters.minRating.toInt()}+" else "All",
            options = listOf(0.0 to "All", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+", 9.0 to "9+"),
            onSelect = { onFilters(filters.copy(minRating = it)) },
        )
    }
}

@Composable
fun <T> FilterMenu(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

fun sortLabel(sort: String): String = when (sort) {
    "mtime" -> "File date"
    "rating" -> "Rating"
    "recent" -> "Added"
    else -> "Title"
}

@Composable
fun PagingHeader(title: String, pageIndex: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onPrev, enabled = pageIndex > 0) { Text("Prev") }
        Text("${pageIndex + 1}", color = Muted)
        OutlinedButton(onClick = onNext) { Text("Next") }
    }
}

@Composable
fun HeaderBack(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack) { Text("Back") }
        Text(title, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MovieShelf(title: String, session: Session, items: List<PopItem>, completedItems: Set<Long>, watchlistItems: Set<Long>, onClick: (PopItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        if (items.isEmpty()) {
            Text("Nothing here yet", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { item ->
                    MovieCard(session, item, Modifier.width(128.dp), watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), onClick = { onClick(item) })
                }
            }
        }
    }
}

@Composable
fun ShowShelf(title: String, session: Session, shows: List<ShowSummary>, completedShows: Set<String>, watchlistShows: Set<String>, onClick: (ShowSummary) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        if (shows.isEmpty()) {
            Text("Nothing here yet", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shows, key = { it.libraryId + it.title }) { show ->
                    ShowCard(session, show, Modifier.width(128.dp), watched = completedShows.contains(showMarkerKey(show)), watchlisted = watchlistShows.contains(showMarkerKey(show)), onClick = { onClick(show) })
                }
            }
        }
    }
}

@Composable
fun MovieCard(session: Session, item: PopItem, modifier: Modifier = Modifier, watched: Boolean = false, watchlisted: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick).padding(6.dp)) {
        PosterImage(session, imageUrl(session, item.id, item.posterMtimeUnix), Modifier.fillMaxWidth(), watched = watched, watchlisted = watchlisted, rating = item.rating)
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" \u00b7 "), color = Muted, fontSize = 11.sp, maxLines = 1)
        firstGenre(item.genres)?.let { Text(it, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun ShowCard(session: Session, show: ShowSummary, modifier: Modifier = Modifier, watched: Boolean = false, watchlisted: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick).padding(6.dp)) {
        PosterImage(session, imageUrl(session, show.posterItemId, show.posterMtimeUnix), Modifier.fillMaxWidth(), watched = watched, watchlisted = watchlisted, rating = show.rating)
        Spacer(Modifier.height(6.dp))
        Text(show.title, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(show.yearsLabel().takeIf { it.isNotBlank() }, "${show.seasonCount} seasons").joinToString(" · "),
            color = Muted, fontSize = 11.sp, maxLines = 1,
        )
        firstGenre(show.genres)?.let { Text(it, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

fun firstGenre(genres: String): String? = genres.split(Regex("[,;/]")).map { it.trim() }.firstOrNull { it.isNotBlank() }?.uppercase()

@Composable
fun PosterImage(session: Session, url: String, modifier: Modifier, watched: Boolean = false, watchlisted: Boolean = false, progress: Float = 0f, rating: Double = 0.0) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AuthAsyncImage(session, url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("?", color = Muted)
        if (watched) MarkerBadge("Seen", Accent, Modifier.align(Alignment.TopStart))
        if (watchlisted) MarkerBadge("List", Color(0xFFFFD166), Modifier.align(Alignment.TopEnd))
        if (rating > 0 && !watched) {
            Text(
                "★ %.1f".format(rating),
                color = Color.Black,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xFFE5A00D).copy(alpha = .94f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (progress in 0.01f..0.999f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = .45f))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Accent))
            }
        }
    }
}

@Composable
fun ContinueShelf(title: String, session: Session, items: List<PopItem>, resume: Map<Long, Float>, onClick: (PopItem) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { item ->
                ContinueCard(session, item, resume[item.id] ?: 0f, Modifier.width(128.dp)) { onClick(item) }
            }
        }
    }
}

@Composable
fun ContinueCard(session: Session, item: PopItem, progress: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isEpisode = item.kind == "episode"
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick).padding(6.dp)) {
        PosterImage(session, imageUrl(session, item.id, item.posterMtimeUnix), Modifier.fillMaxWidth(), progress = progress)
        Spacer(Modifier.height(6.dp))
        Text(
            if (isEpisode) item.showTitle.ifBlank { item.title } else item.title,
            color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (isEpisode) "S%02dE%02d".format(item.seasonNumber, item.episodeNumber)
            else listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" · "),
            color = Muted, fontSize = 11.sp, maxLines = 1,
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
        if (session.token.isNotBlank()) {
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
