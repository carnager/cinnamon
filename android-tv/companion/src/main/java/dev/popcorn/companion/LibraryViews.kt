package dev.popcorn.companion

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

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
fun HomePage(session: Session, movies: List<PopItem>, shows: List<ShowSummary>, onMovie: (PopItem) -> Unit, onShow: (ShowSummary) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Recently Added Movies", color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        item { PosterRow(session, movies, onMovie) }
        item { Text("Recently Added TV", color = TextColor, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        item { ShowRow(session, shows, onShow) }
    }
}

@Composable
fun MediaGrid(title: String, session: Session, items: List<PopItem>, pageIndex: Int, onPrev: () -> Unit, onNext: () -> Unit, onOpen: (PopItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        PagingHeader(title, pageIndex, onPrev, onNext)
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.id }) { item -> MovieCard(session, item, onClick = { onOpen(item) }) }
        }
    }
}

@Composable
fun ShowGrid(title: String, session: Session, shows: List<ShowSummary>, pageIndex: Int, onPrev: () -> Unit, onNext: () -> Unit, onShow: (ShowSummary) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        PagingHeader(title, pageIndex, onPrev, onNext)
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shows, key = { it.libraryId + it.title }) { show -> ShowCard(session, show, onClick = { onShow(show) }) }
        }
    }
}

@Composable
fun SeasonList(session: Session, show: ShowSummary, seasons: List<SeasonSummary>, onBack: () -> Unit, onSeason: (SeasonSummary) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { HeaderBack(show.title, onBack) }
        items(seasons, key = { it.seasonNumber }) { season ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).clickable { onSeason(season) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PosterImage(imageUrl(session, season.posterItemId, season.posterMtimeUnix), Modifier.width(72.dp))
                Column(Modifier.weight(1f)) {
                    Text(season.title.ifBlank { "Season ${season.seasonNumber}" }, color = TextColor, fontWeight = FontWeight.Bold)
                    Text("${season.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                    if (season.overview.isNotBlank()) Text(season.overview, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun EpisodeList(show: ShowSummary, season: SeasonSummary, episodes: List<PopItem>, onBack: () -> Unit, onOpen: (PopItem) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { HeaderBack("${show.title} \u00b7 ${season.title.ifBlank { "Season ${season.seasonNumber}" }}", onBack) }
        items(episodes, key = { it.id }) { episode ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface1).clickable { onOpen(episode) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${episode.episodeNumber}", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(episode.episodeTitle.ifBlank { episode.title }, color = TextColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtDuration(episode.durationMs), color = Muted, fontSize = 12.sp)
                }
                Button(onClick = { onOpen(episode) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) { Text("Open") }
            }
        }
    }
}

@Composable
fun SearchPage(session: Session, query: String, onQuery: (String) -> Unit, movies: List<PopItem>, shows: List<ShowSummary>, onMovie: (PopItem) -> Unit, onShow: (ShowSummary) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PopTextField(query, onQuery, "Search movies and shows") }
        if (shows.isNotEmpty()) item { Text("Shows", color = TextColor, fontWeight = FontWeight.Bold) }
        items(shows, key = { it.libraryId + it.title }) { show -> SearchRow(title = show.title, meta = "${show.seasonCount} seasons \u00b7 ${show.episodeCount} episodes", onClick = { onShow(show) }) }
        if (movies.isNotEmpty()) item { Text("Movies", color = TextColor, fontWeight = FontWeight.Bold) }
        items(movies, key = { it.id }) { item -> SearchRow(title = item.title, meta = listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" \u00b7 "), onClick = { onMovie(item) }) }
    }
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
fun PosterRow(session: Session, items: List<PopItem>, onClick: (PopItem) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        items.take(3).forEach { MovieCard(session, it, Modifier.weight(1f), onClick = { onClick(it) }) }
    }
}

@Composable
fun ShowRow(session: Session, shows: List<ShowSummary>, onClick: (ShowSummary) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        shows.take(3).forEach { ShowCard(session, it, Modifier.weight(1f), onClick = { onClick(it) }) }
    }
}

@Composable
fun MovieCard(session: Session, item: PopItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick).padding(6.dp)) {
        PosterImage(imageUrl(session, item.id, item.posterMtimeUnix), Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(item.title, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(item.year.takeIf { it > 0 }?.toString(), fmtDuration(item.durationMs)).filterNotNull().joinToString(" \u00b7 "), color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun ShowCard(session: Session, show: ShowSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Surface1).clickable(onClick = onClick).padding(6.dp)) {
        PosterImage(imageUrl(session, show.posterItemId, show.posterMtimeUnix), Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(show.title, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${show.seasonCount} seasons", color = Muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun PosterImage(url: String, modifier: Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("?", color = Muted)
    }
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
