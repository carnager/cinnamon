package dev.popcorn.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri

@Composable
fun DetailPage(
    session: Session,
    api: Api,
    item: PopItem,
    playbackTarget: PlaybackTarget,
    selectedDeviceName: String?,
    watched: Boolean,
    onSetWatched: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (PopItem, Int?, Int?) -> Unit,
    onPlayLocal: (PopItem, Int?, Int?) -> Unit,
    onOpenSimilar: (PopItem) -> Unit,
) {
    val context = LocalContext.current
    var streams by remember(item.id) { mutableStateOf<List<StreamInfo>>(emptyList()) }
    var ratings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }
    var similar by remember(item.id) { mutableStateOf<List<PopItem>>(emptyList()) }
    var selectedAudio by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember(item.id) { mutableStateOf<Int?>(null) }
    var error by remember(item.id) { mutableStateOf("") }
    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }

    LaunchedEffect(item.id) {
        runCatching { api.streams(item.id) }
            .onSuccess { loaded ->
                streams = loaded
                selectedAudio = loaded.firstOrNull { it.type == "audio" && it.default }?.index ?: loaded.firstOrNull { it.type == "audio" }?.index
                selectedSubtitle = loaded.firstOrNull { it.type == "subtitle" && it.default }?.index
            }
            .onFailure { error = it.message ?: "Could not load streams" }
        runCatching { api.ratings(item.id) }
            .onSuccess { ratings = it }
        if (item.kind == "movie") {
            similar = runCatching { api.similar(item.id) }.getOrDefault(emptyList())
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DetailHero(session, item, ratings, streams) }
        item {
            val targetLabel = if (playbackTarget == PlaybackTarget.Phone) "Phone" else cleanDeviceName(selectedDeviceName ?: "") ?: "TV"
            Button(
                onClick = {
                    if (playbackTarget == PlaybackTarget.Phone) onPlayLocal(item, selectedAudio, selectedSubtitle)
                    else onPlay(item, selectedAudio, selectedSubtitle)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Play on $targetLabel", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    val q = Uri.encode(listOf(displayTitle(item), item.year.takeIf { it > 0 }?.toString(), "trailer").filterNotNull().joinToString(" "))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("▶  Trailer", color = TextColor, fontWeight = FontWeight.Bold)
            }
        }
        item {
            OutlinedButton(
                onClick = { onSetWatched(!watched) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (watched) "✓  Seen — tap to unmark" else "Mark as seen",
                    color = if (watched) Accent else TextColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (item.overview.isNotBlank()) {
            item {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Overview", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(item.overview, color = TextColor, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
        if (item.actors.isNotEmpty()) {
            item { CastStrip(session, item.actors) }
        }
        if (similar.isNotEmpty()) {
            item { SimilarRow(session, similar, onOpenSimilar) }
        }
        if (error.isNotBlank()) {
            item { Text(error, color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrackSection("Audio", audioTracks, selectedAudio, emptyLabel = "Default", onSelect = { selectedAudio = it })
                    TrackSection("Subtitles", subtitleTracks, selectedSubtitle, emptyLabel = "Off", onSelect = { selectedSubtitle = it })
                }
            }
        }
    }
}

// Full-bleed backdrop hero with a gradient scrim fading into the page, with the
// poster, title, meta, genres, ratings and tech chips layered on top.
@Composable
fun DetailHero(session: Session, item: PopItem, ratings: ExternalRatings?, streams: List<StreamInfo>) {
    val backdropUrl = if (item.backdropMtimeUnix > 0) imageUrl(session, item.id, item.backdropMtimeUnix, "backdrop") else ""
    val genres = item.genres.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() }.take(3)
    Column {
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
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                PosterImage(session, imageUrl(session, item.id, item.posterMtimeUnix), Modifier.width(96.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(displayTitle(item), color = TextColor, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOf(
                        item.year.takeIf { it > 0 }?.toString(),
                        fmtDuration(item.durationMs),
                        fmtEndsAround(item.durationMs),
                        if (item.kind == "episode") "S%02d E%02d".format(item.seasonNumber, item.episodeNumber) else null,
                    ).filterNotNull().filter { it.isNotBlank() }.joinToString(" \u00b7 ")
                    if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    genres.forEach { DetailChip(it.uppercase(), Surface2, Muted) }
                }
            }
            RatingBadges(item, ratings)
            val tech = techChips(streams)
            if (tech.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    tech.forEach { DetailChip(it, Surface3, TextColor) }
                }
            }
        }
    }
}

@Composable
fun CastStrip(session: Session, actors: List<Actor>) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cast", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(actors.take(20), key = { it.name }) { actor ->
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                        AuthAsyncImage(session, "${session.server}/api/actors/image?name=${Uri.encode(actor.name)}", contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(actor.name, color = TextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (actor.role.isNotBlank()) Text(actor.role, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun SimilarRow(session: Session, items: List<PopItem>, onOpen: (PopItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("More like this", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { m ->
                MovieCard(session, m, Modifier.width(120.dp), onClick = { onOpen(m) })
            }
        }
    }
}

private fun techChips(streams: List<StreamInfo>): List<String> = buildList {
    streams.firstOrNull { it.type == "video" }?.codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
    streams.firstOrNull { it.type == "audio" }?.codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
}

@Composable
fun DetailChip(label: String, bg: Color, fg: Color) {
    Text(
        label,
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
fun RatingBadges(item: PopItem, ratings: ExternalRatings?) {
    val imdbId = ratings?.imdbId?.takeIf { it.isNotBlank() } ?: item.imdbId
    val tmdbId = ratings?.tmdbId?.takeIf { it.isNotBlank() } ?: item.tmdbId
    val badges = buildList {
        ratings?.imdbRating?.takeIf { it > 0 }?.let { add("IMDb" to "%.1f".format(it)) }
        ratings?.tmdbRating?.takeIf { it > 0 }?.let { add("TMDb" to "%.1f".format(it)) }
        ratings?.rottenTomatoesRating?.takeIf { it > 0 }?.let { add("RT" to "$it%") }
        ratings?.metacriticRating?.takeIf { it > 0 }?.let { add("MC" to it.toString()) }
        if (isEmpty()) {
            item.rating.takeIf { it > 0 }?.let { add("NFO" to "%.1f".format(it)) }
            imdbId.takeIf { it.isNotBlank() }?.let { add("IMDb" to it.removePrefix("tt")) }
            tmdbId.takeIf { it.isNotBlank() }?.let { add("TMDb" to it) }
        }
    }
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        badges.forEach { (label, value) ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (label == "IMDb") Color(0xFFF5C518) else if (label == "TMDb") Color(0xFF01B4E4) else Surface3)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(value, color = Color.Black.copy(alpha = .78f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TrackSection(title: String, tracks: List<StreamInfo>, selected: Int?, emptyLabel: String, onSelect: (Int?) -> Unit) {
    var expanded by remember(title, tracks, selected) { mutableStateOf(false) }
    val selectedLabel = tracks.firstOrNull { it.index == selected }?.label() ?: emptyLabel
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(selectedLabel, color = TextColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("\u2304", color = Muted, fontSize = 20.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface2),
            ) {
                DropdownMenuItem(
                    text = { Text(emptyLabel, color = TextColor, fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
                tracks.forEach { track ->
                    DropdownMenuItem(
                        text = { Text(track.label(), color = TextColor, fontWeight = if (selected == track.index) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            onSelect(track.index)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun TrackChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent else Surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Color.Black else TextColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Text("Selected", color = Color.Black.copy(alpha = .65f), fontSize = 12.sp)
    }
}
