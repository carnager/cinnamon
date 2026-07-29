package dev.popcorn.companion

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DetailPage(
    session: Session,
    api: Api,
    item: PopItem,
    playbackTarget: PlaybackTarget,
    devices: List<Device>,
    selectedDevice: Device?,
    watched: Boolean,
    watchlisted: Boolean,
    onSetWatched: (Boolean) -> Unit,
    onSetWatchlisted: (Boolean) -> Unit,
    onSelectPhone: () -> Unit,
    onSelectDevice: (Device) -> Unit,
    onBack: () -> Unit,
    onPlay: (PopItem, Int?, Int?) -> Unit,
    onPlayLocal: (PopItem, Int?, Int?) -> Unit,
    onOpenSimilar: (PopItem) -> Unit,
    onActor: (Actor) -> Unit,
) {
    val context = LocalContext.current
    var detailItem by remember(item.id) { mutableStateOf(item) }
    var streams by remember(item.id) { mutableStateOf<List<StreamInfo>>(emptyList()) }
    var ratings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }
    var similar by remember(item.id) { mutableStateOf<List<PopItem>>(emptyList()) }
    var selectedAudio by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember(item.id) { mutableStateOf<Int?>(null) }
    var error by remember(item.id) { mutableStateOf("") }
    var userRating by remember(item.id) { mutableStateOf(0) }
    var ratingOpen by remember(item.id) { mutableStateOf(false) }
    var targetOpen by remember(item.id) { mutableStateOf(false) }
    val ratingScope = rememberCoroutineScope()
    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }

    LaunchedEffect(item.id, PlaybackPrefs.audioLang, PlaybackPrefs.subtitleLang) {
        runCatching { api.item(item.id) }.onSuccess { detailItem = it }
        runCatching { api.streams(item.id) }
            .onSuccess { loaded ->
                streams = loaded
                selectedAudio = preferredAudioIndex(loaded.filter { it.type == "audio" })
                selectedSubtitle = preferredSubtitleIndex(loaded.filter { it.type == "subtitle" })
            }
            .onFailure { error = it.message ?: "Could not load streams" }
        runCatching { api.ratings(item.id) }.onSuccess { ratings = it }
        runCatching { api.itemRating(item.id) }.onSuccess { userRating = it }
        if (item.kind == "movie") similar = runCatching { api.similar(item.id) }.getOrDefault(emptyList())
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DetailHero(session, detailItem, ratings, streams, onBack) }
        item {
            Button(
                onClick = { targetOpen = true },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Text("  Play", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailAction(
                    label = "Trailer",
                    icon = Icons.Filled.PlayCircleOutline,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val query = Uri.encode(listOf(displayTitle(detailItem), detailItem.year.takeIf { it > 0 }?.toString(), "trailer").filterNotNull().joinToString(" "))
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))) }
                    },
                )
                DetailAction("Seen", Icons.Filled.CheckCircle, Modifier.weight(1f), watched, Accent) { onSetWatched(!watched) }
                DetailAction("Watchlist", Icons.Filled.Bookmark, Modifier.weight(1f), watchlisted, Teal) { onSetWatchlisted(!watchlisted) }
                DetailAction(if (userRating > 0) "$userRating/10" else "Rate", Icons.Filled.Star, Modifier.weight(1f), userRating > 0, Gold) { ratingOpen = !ratingOpen }
            }
        }
        if (ratingOpen) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (userRating > 0) "Your rating · $userRating/10" else "Rate this", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (star in 1..10) {
                            Text(
                                "★",
                                color = if (star <= userRating) Gold else Muted.copy(alpha = .38f),
                                fontSize = 23.sp,
                                modifier = Modifier.clickable {
                                    val value = if (star == userRating) 0 else star
                                    ratingScope.launch {
                                        runCatching {
                                            if (value > 0) api.setItemRating(item.id, value) else api.deleteItemRating(item.id)
                                        }.onSuccess { userRating = value }
                                            .onFailure { error = it.message ?: "Failed to save rating" }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (detailItem.overview.isNotBlank() || detailFacts(detailItem).isNotEmpty()) item { DetailOverview(detailItem) }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Surface1)
                    .border(1.dp, Line, RoundedCornerShape(8.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TrackSection("Audio", audioTracks, selectedAudio, "Default") { selectedAudio = it }
                TrackSection("Subtitles", subtitleTracks, selectedSubtitle, "Off") { selectedSubtitle = it }
            }
        }
        if (detailItem.actors.isNotEmpty()) item { CastStrip(session, detailItem.actors, onActor) }
        if (similar.isNotEmpty()) item { SimilarRow(session, similar, onOpenSimilar) }
        if (error.isNotBlank()) item { Text(error, color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
    if (targetOpen) {
        PlaybackTargetSheet(
            devices = devices,
            selectedDevice = selectedDevice,
            playbackTarget = playbackTarget,
            onDismiss = { targetOpen = false },
            onSelectPhone = {
                targetOpen = false
                onSelectPhone()
                onPlayLocal(detailItem, selectedAudio, selectedSubtitle)
            },
            onSelectDevice = {
                targetOpen = false
                onSelectDevice(it)
                onPlay(detailItem, selectedAudio, selectedSubtitle)
            },
        )
    }
}

@Composable
private fun DetailAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = Accent,
    onClick: () -> Unit,
) {
    val tint = if (active) activeColor else TextColor.copy(alpha = .82f)
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (active) activeColor.copy(alpha = .65f) else Line, RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = .10f) else Color.Transparent).clickable(onClick = onClick).padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Text(label, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun DetailHero(session: Session, item: PopItem, ratings: ExternalRatings?, streams: List<StreamInfo>, onBack: () -> Unit) {
    val backdropUrl = if (item.backdropMtimeUnix > 0) imageUrl(session, item.id, item.backdropMtimeUnix, "backdrop", ArtworkFull) else ""
    val genres = item.genres.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() }.take(3)
    Column {
        Box(Modifier.fillMaxWidth().height(330.dp)) {
            if (backdropUrl.isNotBlank()) {
                AuthAsyncImage(session, backdropUrl, null, Modifier.fillMaxSize(), ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Surface2, Bg))))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .06f), .5f to Bg.copy(alpha = .38f), .82f to Bg.copy(alpha = .9f), 1f to Bg)))
            Box(
                Modifier.align(Alignment.TopStart).padding(14.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = .58f))
                    .border(1.dp, Color.White.copy(alpha = .13f), CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                PosterImage(session, imageUrl(session, item.id, item.posterMtimeUnix, width = ArtworkCard), Modifier.width(104.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(displayTitle(item), color = TextColor, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-.35).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (item.originalTitle.isNotBlank() && item.originalTitle != displayTitle(item)) {
                        Text(item.originalTitle, color = Muted, fontSize = 12.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val meta = detailMetadata(item)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (item.officialRating.isNotBlank()) ContentRatingChip(item.officialRating)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (genres.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    genres.forEach { DetailChip(it.uppercase(Locale.US), Color.Transparent, Teal.copy(alpha = .86f)) }
                }
            }
            RatingBadges(item, ratings)
            val tech = techChips(item, streams)
            if (tech.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    tech.forEach { DetailChip(it, Color.Transparent, TextColor.copy(alpha = .85f)) }
                }
            }
        }
    }
}

@Composable
private fun ContentRatingChip(text: String) {
    Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

private fun detailMetadata(item: PopItem): String {
    val parts = mutableListOf<String>()
    if (item.kind == "episode") {
        if (item.showTitle.isNotBlank()) parts.add(item.showTitle)
        if (item.seasonNumber > 0 || item.episodeNumber > 0) parts.add("S%02dE%02d".format(item.seasonNumber, item.episodeNumber))
    } else if (item.year > 0) parts.add(item.year.toString())
    if (item.durationMs > 0) {
        parts.add(fmtDuration(item.durationMs))
        parts.add(fmtEndsAround(item.durationMs))
    }
    return parts.joinToString(" · ")
}

private fun techChips(item: PopItem, streams: List<StreamInfo>): List<String> = buildList {
    when {
        item.height >= 2000 -> add("4K")
        item.height >= 1000 -> add("1080p")
        item.height >= 700 -> add("720p")
        item.height > 0 -> add("${item.height}p")
    }
    (item.videoCodec.ifBlank { streams.firstOrNull { it.type == "video" }?.codec.orEmpty() }).takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
    (item.audioCodec.ifBlank { streams.firstOrNull { it.type == "audio" }?.codec.orEmpty() }).takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
}

@Composable
private fun DetailOverview(item: PopItem) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Overview", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (item.tagline.isNotBlank()) Text(item.tagline, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (item.overview.isNotBlank()) Text(item.overview, color = TextColor.copy(alpha = .84f), fontSize = 14.sp, lineHeight = 20.sp)
        detailFacts(item).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(label, color = Accent.copy(alpha = .86f), fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(58.dp))
                Text(value, color = TextColor.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun detailFacts(item: PopItem): List<Pair<String, String>> = listOfNotNull(
    item.directors.takeIf { it.isNotBlank() }?.let { "Director" to it },
    item.writers.takeIf { it.isNotBlank() }?.let { "Writers" to it },
    item.studios.takeIf { it.isNotBlank() }?.let { "Studios" to it },
    item.countries.takeIf { it.isNotBlank() }?.let { "Country" to it },
)

@Composable
fun CastStrip(session: Session, actors: List<Actor>, onActor: (Actor) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cast", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(actors.take(20), key = { it.name }) { actor ->
                Column(Modifier.width(76.dp).clickable { onActor(actor) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Surface2).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                        Text(actorInitials(actor.name), color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        AuthAsyncImage(session, actorImageUrl(session, actor), actor.name, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                    Text(actor.name, color = TextColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (actor.role.isNotBlank()) Text(actor.role, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun PersonPage(
    session: Session,
    actor: Actor,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    onBack: () -> Unit,
    onMovie: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
) {
    var detail by remember(actor.name) { mutableStateOf<ActorDetail?>(null) }
    var loading by remember(actor.name) { mutableStateOf(true) }
    var error by remember(actor.name) { mutableStateOf("") }
    LaunchedEffect(actor.name) {
        loading = true
        runCatching { Api(session).actorDetail(actor.name) }
            .onSuccess { detail = it; error = "" }
            .onFailure { error = it.message ?: "Failed to load actor" }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { HeaderBack(actor.name, onBack) }
        when {
            loading -> item { Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent, strokeWidth = 2.dp) } }
            error.isNotBlank() -> item { Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 16.dp)) }
            detail != null -> {
                val person = detail!!
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(116.dp).clip(CircleShape).background(Surface2).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                            Text(actorInitials(person.actor.name), color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            AuthAsyncImage(session, resolveActorImage(session, person.profileUrl, person.actor), person.actor.name, Modifier.fillMaxSize(), ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(person.actor.name, color = TextColor, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black)
                            val meta = listOf(person.info.knownForDepartment, person.info.birthday, person.info.placeOfBirth).filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (person.info.biography.isNotBlank()) Text(person.info.biography, color = TextColor.copy(alpha = .8f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (person.movies.isNotEmpty()) item { MovieShelf("Movies", "${person.movies.size}", session, person.movies, completedItems, watchlistItems, onMovie) }
                if (person.shows.isNotEmpty()) item { ShowShelf("TV Shows", "${person.shows.size}", session, person.shows, completedShows, watchlistShows, onShow) }
            }
        }
    }
}

private fun actorImageUrl(session: Session, actor: Actor): String {
    val thumb = actor.thumb.trim()
    return when {
        thumb.startsWith("http://") || thumb.startsWith("https://") -> thumb
        thumb.startsWith("/") -> "${session.server}$thumb"
        else -> "${session.server}/api/actors/image?name=${Uri.encode(actor.name)}"
    }
}

private fun resolveActorImage(session: Session, profileUrl: String, actor: Actor): String {
    val value = profileUrl.trim()
    return when {
        value.startsWith("http://") || value.startsWith("https://") -> value
        value.startsWith("/") -> "${session.server}$value"
        else -> actorImageUrl(session, actor)
    }
}

private fun actorInitials(name: String): String = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")

@Composable
fun SimilarRow(session: Session, items: List<PopItem>, onOpen: (PopItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("More like this", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { movie -> MovieCard(session, movie, Modifier.width(120.dp), onClick = { onOpen(movie) }) }
        }
    }
}

@Composable
fun DetailChip(label: String, bg: Color, fg: Color) {
    Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
fun RatingBadges(item: PopItem, ratings: ExternalRatings?) {
    val badges = buildList {
        ratings?.imdbRating?.takeIf { it > 0 }?.let { add("IMDb" to "%.1f".format(Locale.US, it)) }
        ratings?.tmdbRating?.takeIf { it > 0 }?.let { add("TMDb" to "%.1f".format(Locale.US, it)) }
        ratings?.rottenTomatoesRating?.takeIf { it > 0 }?.let { add("RT" to "$it%") }
        ratings?.metacriticRating?.takeIf { it > 0 }?.let { add("MC" to it.toString()) }
        if (isEmpty() && item.rating > 0) add("NFO" to "%.1f".format(Locale.US, item.rating))
    }
    if (badges.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.take(4).forEach { (label, value) -> SourceRatingBadge(label, value) }
    }
}

@Composable
private fun SourceRatingBadge(label: String, value: String) {
    Row(
        Modifier.background(Surface2, RoundedCornerShape(4.dp)).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Teal, fontWeight = FontWeight.Black, fontSize = 10.sp)
        Text(value, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
fun TrackSection(title: String, tracks: List<StreamInfo>, selected: Int?, emptyLabel: String, onSelect: (Int?) -> Unit) {
    var expanded by remember(title, tracks, selected) { mutableStateOf(false) }
    val selectedLabel = tracks.firstOrNull { it.index == selected }?.label() ?: emptyLabel
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, color = Muted, fontWeight = FontWeight.Black, fontSize = 10.sp)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Text(selectedLabel, color = TextColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾", color = Muted, fontSize = 14.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Surface2)) {
                DropdownMenuItem(text = { Text(emptyLabel, color = TextColor, fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal) }, onClick = { onSelect(null); expanded = false })
                tracks.forEach { track ->
                    DropdownMenuItem(text = { Text(track.label(), color = TextColor, fontWeight = if (selected == track.index) FontWeight.Bold else FontWeight.Normal) }, onClick = { onSelect(track.index); expanded = false })
                }
            }
        }
    }
}

@Composable
fun TrackChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) Accent else Surface1).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Color.Black else TextColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Text("Selected", color = Color.Black.copy(alpha = .65f), fontSize = 12.sp)
    }
}
