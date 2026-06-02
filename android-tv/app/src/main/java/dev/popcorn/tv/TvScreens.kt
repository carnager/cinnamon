package dev.popcorn.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ShowView(session: Session?, show: ShowSummary, onSeason: (SeasonSummary) -> Unit) {
    val seasons = remember { mutableStateListOf<SeasonSummary>() }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(show.libraryId, show.title) {
        val active = session ?: return@LaunchedEffect
        loading = true
        runCatching { Api(active).seasons(show.libraryId, show.title) }
            .onSuccess {
                seasons.clear()
                seasons.addAll(it)
            }
            .onFailure { error = it.message ?: "Failed to load seasons" }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        ShowHeader(session, show)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Seasons", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${show.seasonCount} seasons · ${show.episodeCount} episodes", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp), fontSize = 12.sp)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            PosterGrid(seasons, key = { "${it.libraryId}:${it.showTitle}:${it.seasonNumber}" }) { season, autoFocus, _, requester ->
                SeasonCard(
                    session = session,
                    season = season,
                    autoFocus = autoFocus,
                    focusRequester = requester,
                    onClick = { onSeason(season) },
                )
            }
        }
    }
}

@Composable
fun SeasonView(session: Session?, show: ShowSummary, season: SeasonSummary, onEpisode: (PopItem) -> Unit) {
    val episodes = remember { mutableStateListOf<PopItem>() }
    var completedEpisodes by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchlistEpisodes by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchMenu by remember { mutableStateOf<WatchMenuState?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var initialEpisodeFocusPending by remember(season.seasonNumber) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun closeWatchMenu(menu: WatchMenuState? = watchMenu) {
        watchMenu = null
        menu?.restoreFocus?.let { restore ->
            scope.launch {
                delay(90)
                runCatching { restore() }
            }
        }
    }

    fun setEpisodeWatched(item: PopItem, watched: Boolean) {
        val active = session ?: return
        scope.launch {
            runCatching {
                val api = Api(active)
                if (watched) api.markItemWatched(item) else api.unmarkItemWatched(item.id)
            }.onSuccess {
                completedEpisodes = if (watched) completedEpisodes + item.id else completedEpisodes - item.id
            }.onFailure {
                error = it.message ?: "Failed to update watched state"
            }
        }
    }

    fun setEpisodeWatchlisted(item: PopItem, listed: Boolean) {
        val active = session ?: return
        scope.launch {
            runCatching {
                val api = Api(active)
                if (listed) api.addItemWatchlist(item.id) else api.removeItemWatchlist(item.id)
            }.onSuccess {
                watchlistEpisodes = if (listed) watchlistEpisodes + item.id else watchlistEpisodes - item.id
            }.onFailure {
                error = it.message ?: "Failed to update watchlist"
            }
        }
    }

    LaunchedEffect(show.libraryId, show.title, season.seasonNumber) {
        val active = session ?: return@LaunchedEffect
        loading = true
        runCatching {
            val api = Api(active)
            Triple(
                api.episodes(show.libraryId, show.title, season.seasonNumber),
                api.progressList().filter { it.completed }.map { it.itemId }.toSet(),
                api.watchlist().items.map { it.id }.toSet(),
            )
        }.onSuccess {
            episodes.clear()
            episodes.addAll(it.first)
            completedEpisodes = it.second
            watchlistEpisodes = it.third
        }.onFailure {
            error = it.message ?: "Failed to load episodes"
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SeasonHeader(session, show, season)
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp), fontSize = 12.sp)
        }
        if (loading && episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
            ) {
                itemsIndexed(episodes, key = { _, item -> item.id }) { index, episode ->
                    val watched = completedEpisodes.contains(episode.id)
                    val focusNow = initialEpisodeFocusPending && index == 0
                    EpisodeRow(
                        session,
                        episode,
                        watched = watched,
                        watchlisted = watchlistEpisodes.contains(episode.id),
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialEpisodeFocusPending = false },
                        onClick = { onEpisode(episode) },
                        onLongClick = { focusRequester ->
                            val listed = watchlistEpisodes.contains(episode.id)
                            watchMenu = WatchMenuState(
                                title = episode.episodeTitle.ifBlank { episode.title },
                                watched = watched,
                                watchlisted = listed,
                                onMarkWatched = { setEpisodeWatched(episode, true) },
                                onMarkUnwatched = { setEpisodeWatched(episode, false) },
                                onAddWatchlist = { setEpisodeWatchlisted(episode, true) },
                                onRemoveWatchlist = { setEpisodeWatchlisted(episode, false) },
                                restoreFocus = { focusRequester.requestFocus() },
                            )
                        },
                    )
                }
            }
        }
    }
    watchMenu?.let { menu ->
        WatchActionOverlay(
            title = menu.title,
            watched = menu.watched,
            watchlisted = menu.watchlisted,
            onMarkWatched = {
                closeWatchMenu(menu)
                menu.onMarkWatched()
            },
            onMarkUnwatched = {
                closeWatchMenu(menu)
                menu.onMarkUnwatched()
            },
            onAddWatchlist = {
                closeWatchMenu(menu)
                menu.onAddWatchlist()
            },
            onRemoveWatchlist = {
                closeWatchMenu(menu)
                menu.onRemoveWatchlist()
            },
            onDismiss = { closeWatchMenu(menu) },
        )
    }
}

@Composable
fun ShowHeader(session: Session?, show: ShowSummary) {
    Box(Modifier.fillMaxWidth().height(206.dp)) {
        if (show.backdropItemId > 0 && session != null) {
            SizedAsyncImage(
                model = imageUrl(session, show.backdropItemId, "backdrop", show.backdropMtimeUnix),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 900,
                heightPx = 360,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = .18f), Bg), startY = 40f)
            )
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(session, show.posterItemId, Modifier.width(82.dp), show.posterMtimeUnix)
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(show.title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${show.seasonCount} seasons", color = Muted, fontSize = 12.sp)
                    Text("${show.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                    if (show.rating > 0) RatingBadge(show.rating)
                }
                if (show.genres.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(show.genres, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (show.overview.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(show.overview, color = TextColor.copy(alpha = .76f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SeasonHeader(session: Session?, show: ShowSummary, season: SeasonSummary) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 32.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeasonPoster(session, season, Modifier.width(70.dp))
        Column(Modifier.weight(1f)) {
            Text(show.title, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(seasonTitle(season), color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${season.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                fmtDuration(season.durationMs).takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }
                if (season.rating > 0) RatingBadge(season.rating)
            }
            if (season.overview.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(season.overview, color = TextColor.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SeasonCard(
    session: Session?,
    season: SeasonSummary,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onClick = onClick) {
        Box {
            SeasonPoster(session, season, Modifier.fillMaxWidth())
            if (season.rating > 0) PosterRating(season.rating)
        }
        Spacer(Modifier.height(4.dp))
        Text(seasonTitle(season), color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${season.episodeCount} episodes", color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun SeasonPoster(session: Session?, season: SeasonSummary, modifier: Modifier) {
    val url = imageUrl(session, season.posterItemId, "season", season.posterMtimeUnix)
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 390)
        } else {
            Text(seasonTitle(season).take(1), color = Muted, fontSize = 20.sp)
        }
    }
}

private fun seasonTitle(season: SeasonSummary): String {
    if (season.title.isNotBlank()) return season.title
    return if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"
}
