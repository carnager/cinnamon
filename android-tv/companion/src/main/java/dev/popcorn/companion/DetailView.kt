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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
    watched: Boolean,
    watchlisted: Boolean,
    onSetWatched: (Boolean) -> Unit,
    onSetWatchlisted: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPlay: (PopItem, Int?, Int?, Long) -> Unit,
    onPlayLocal: (PopItem, Int?, Int?, Long) -> Unit,
    onOpenSimilar: (PopItem) -> Unit,
    onActor: (Actor) -> Unit,
) {
    val context = LocalContext.current
    var detailItem by remember(item.id) { mutableStateOf(item) }
    var progress by remember(item.id) { mutableStateOf<PlaybackProgress?>(null) }
    var streams by remember(item.id) { mutableStateOf<List<StreamInfo>>(emptyList()) }
    var ratings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }
    var similar by remember(item.id) { mutableStateOf<List<PopItem>>(emptyList()) }
    var selectedAudio by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember(item.id) { mutableStateOf<Int?>(null) }
    var error by remember(item.id) { mutableStateOf("") }
    var userRating by remember(item.id) { mutableStateOf(0) }
    var ratingOpen by remember(item.id) { mutableStateOf(false) }
    var audioOpen by remember(item.id) { mutableStateOf(false) }
    var subtitleOpen by remember(item.id) { mutableStateOf(false) }
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
        runCatching { api.progress(item.id) }.onSuccess { progress = it }
        if (item.kind == "movie") similar = runCatching { api.similar(item.id) }.getOrDefault(emptyList())
    }

    val resumeMs = progress?.takeIf { progressResumable(it.positionMs, it.durationMs) }?.positionMs ?: 0L

    fun start(positionMs: Long) {
        if (playbackTarget == PlaybackTarget.Phone) {
            onPlayLocal(detailItem, selectedAudio, selectedSubtitle, positionMs)
        } else {
            onPlay(detailItem, selectedAudio, selectedSubtitle, positionMs)
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { DetailHero(session, detailItem, ratings, streams, resumeFraction(resumeMs, progress?.durationMs ?: 0), onBack) }
        if (error.isNotBlank()) item { Text(error, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { start(resumeMs) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(if (resumeMs > 0) "  Resume" else "  Play", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                    if (resumeMs > 0) {
                        OutlinedButton(
                            onClick = { start(0) },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, tint = TextColor, modifier = Modifier.size(19.dp))
                            Text("  Start over", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
                // Timing only. Where playback lands, and changing it, is the
                // top bar's cast chip — which is pinned and visible from here,
                // so repeating it under the button was just clutter.
                val remainingMs = (detailItem.durationMs - resumeMs).coerceAtLeast(0)
                val endsAround = fmtEndsAround(remainingMs)
                val timing = buildString {
                    if (resumeMs > 0) append("Resumes at ${formatTime(resumeMs)}")
                    if (endsAround.isNotBlank()) {
                        if (isEmpty()) append(endsAround) else append(" · ${endsAround.replaceFirstChar { it.lowercase() }}")
                    }
                }
                if (timing.isNotBlank()) {
                    Text(timing, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Track choices belong with Play, not below the overview: they
                // are decisions made in the same breath as starting playback.
                if (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackChip(
                            icon = Icons.Default.MusicNote,
                            label = audioTracks.firstOrNull { it.index == selectedAudio }?.label() ?: "Default audio",
                            modifier = Modifier.weight(1f),
                        ) { audioOpen = true }
                        TrackChip(
                            icon = Icons.Default.Subtitles,
                            label = subtitleTracks.firstOrNull { it.index == selectedSubtitle }?.label() ?: "Subtitles off",
                            modifier = Modifier.weight(1f),
                        ) { subtitleOpen = true }
                    }
                }
                Spacer(Modifier.height(2.dp))
                // Secondary to Play: no borders unless active, so the primary
                // action keeps the visual weight it deserves.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailAction("Seen", Icons.Filled.CheckCircle, Modifier.weight(1f), watched, Accent) { onSetWatched(!watched) }
                    DetailAction("Watchlist", Icons.Filled.Bookmark, Modifier.weight(1f), watchlisted, Teal) { onSetWatchlisted(!watchlisted) }
                    DetailAction(if (userRating > 0) "$userRating/10" else "Rate", Icons.Filled.Star, Modifier.weight(1f), userRating > 0, Gold) { ratingOpen = !ratingOpen }
                    DetailAction(
                        label = "Trailer",
                        icon = Icons.Filled.PlayCircleOutline,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val query = Uri.encode(listOf(displayTitle(detailItem), detailItem.year.takeIf { it > 0 }?.toString(), "trailer").filterNotNull().joinToString(" "))
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))) }
                        },
                    )
                }
            }
        }
        if (ratingOpen) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (userRating > 0) "Your rating · $userRating/10" else "Rate this", color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (star in 1..10) {
                            // The glyph is 23sp but the target is 40dp: picking
                            // 7 versus 8 was otherwise a guess.
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable {
                                    val value = if (star == userRating) 0 else star
                                    ratingScope.launch {
                                        runCatching {
                                            if (value > 0) api.setItemRating(item.id, value) else api.deleteItemRating(item.id)
                                        }.onSuccess { userRating = value }
                                            .onFailure { error = it.message ?: "Failed to save rating" }
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("★", color = if (star <= userRating) Gold else Muted.copy(alpha = .38f), fontSize = 23.sp)
                            }
                        }
                    }
                }
            }
        }
        if (detailItem.overview.isNotBlank() || detailFacts(detailItem).isNotEmpty()) item { DetailOverview(detailItem) }
        if (detailItem.actors.isNotEmpty()) item { CastStrip(session, detailItem.actors, onActor) }
        if (similar.isNotEmpty()) item { SimilarRow(session, similar, onOpenSimilar) }
    }
    if (audioOpen) {
        TrackDialog("Audio", audioTracks, selectedAudio, "Default", { audioOpen = false }) {
            selectedAudio = it
            audioOpen = false
        }
    }
    if (subtitleOpen) {
        TrackDialog("Subtitles", subtitleTracks, selectedSubtitle, "Off", { subtitleOpen = false }) {
            selectedSubtitle = it
            subtitleOpen = false
        }
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
    val tint = if (active) activeColor else Muted
    // Borderless unless active. These sit under the primary Play button, and
    // four equally-bordered boxes outweighed it.
    Column(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = .10f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun DetailHero(session: Session, item: PopItem, ratings: ExternalRatings?, streams: List<StreamInfo>, resumeProgress: Float, onBack: () -> Unit) {
    val backdropUrl = if (item.backdropMtimeUnix > 0) imageUrl(session, item.id, item.backdropMtimeUnix, "backdrop", ArtworkFull) else ""
    val genres = item.genres.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() }.take(3)
    Column {
        // The backdrop keeps its native 16:9 rather than being cropped to a
        // fixed height, and the cover overlays it at the bottom left. Keeping
        // the poster inside the hero costs no extra height and leaves the title
        // below it the full width.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (backdropUrl.isNotBlank()) {
                AuthAsyncImage(session, backdropUrl, null, Modifier.fillMaxSize(), ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Surface2, Bg))))
            }
            // The image fades in from the left, so the cover sits on settled
            // background rather than on competing artwork and the backdrop
            // reads from the right where nothing overlaps it.
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, .30f to Bg.copy(alpha = .82f), .62f to Bg.copy(alpha = .34f), .88f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .06f), .5f to Bg.copy(alpha = .38f), .82f to Bg.copy(alpha = .9f), 1f to Bg)))
            // The hero is 9/16 of the screen width and a 2:3 cover is 1.5x its
            // own width tall, so the cover's height eats the hero fast: past
            // ~110dp on a 400dp-wide phone its top reaches the back button.
            PosterImage(
                session,
                imageUrl(session, item.id, item.posterMtimeUnix, width = ArtworkCard),
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 6.dp).width(110.dp),
                progress = resumeProgress,
            )
            // Smaller and tighter into the corner to buy the cover room, and
            // drawn last so it stays on top and tappable if a narrow screen
            // still brings the two together.
            Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = .58f))
                    .border(1.dp, Color.White.copy(alpha = .13f), CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayTitle(item), color = TextColor, fontSize = 29.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black, letterSpacing = (-.4).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (item.originalTitle.isNotBlank() && item.originalTitle != displayTitle(item)) {
                    Text(item.originalTitle, color = Muted, fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val meta = detailMetadata(item)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.officialRating.isNotBlank()) ContentRatingChip(item.officialRating)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (genres.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        genres.forEach { DetailChip(it.uppercase(Locale.US), Color.Transparent, Teal.copy(alpha = .86f)) }
                    }
                }
                // Ratings and technical details share one scrolling row: three
                // stacked scrollers read as clutter and it was easy to miss
                // that any of them scrolled at all.
                val badges = ratingBadges(item, ratings)
                val tech = techChips(item, streams)
                if (badges.isNotEmpty() || tech.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        badges.forEach { (label, value) -> SourceRatingBadge(label, value) }
                        tech.forEach { DetailChip(it, Color.Transparent, TextColor.copy(alpha = .85f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentRatingChip(text: String) {
    Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

private fun detailMetadata(item: PopItem): String {
    val parts = mutableListOf<String>()
    if (item.kind == "episode") {
        if (item.showTitle.isNotBlank()) parts.add(item.showTitle)
        if (item.seasonNumber > 0 || item.episodeNumber > 0) parts.add("S%02dE%02d".format(item.seasonNumber, item.episodeNumber))
    } else if (item.year > 0) parts.add(item.year.toString())
    // Runtime only. "Ends around …" belongs next to Play, where it can account
    // for the resume position and is read at the moment it is true — here it
    // was computed from the full runtime and went stale as the page sat open.
    if (item.durationMs > 0) parts.add(fmtDuration(item.durationMs))
    return parts.joinToString(" · ")
}

private fun techChips(item: PopItem, streams: List<StreamInfo>): List<String> = buildList {
    // Width, not height: a 2.39:1 scope encode is ~1248x520, and keying off
    // height labelled that "520p" when it is 720p-class content. Height is
    // only the fallback for items scanned before width was recorded.
    when {
        item.width >= 3600 -> add("4K")
        item.width >= 1800 -> add("1080p")
        item.width >= 1200 -> add("720p")
        item.width > 0 -> add("SD")
        item.height >= 2000 -> add("4K")
        item.height >= 1000 -> add("1080p")
        item.height >= 700 -> add("720p")
        item.height > 0 -> add("SD")
    }
    (item.videoCodec.ifBlank { streams.firstOrNull { it.type == "video" }?.codec.orEmpty() }).takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
    (item.audioCodec.ifBlank { streams.firstOrNull { it.type == "audio" }?.codec.orEmpty() }).takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.US)) }
}

@Composable
private fun DetailOverview(item: PopItem) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Overview", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        if (item.tagline.isNotBlank()) Text(item.tagline, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (item.overview.isNotBlank()) Text(item.overview, color = TextColor.copy(alpha = .84f), fontSize = 15.sp, lineHeight = 22.sp)
        detailFacts(item).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(label, color = Accent.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(66.dp))
                Text(value, color = TextColor.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
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
        Text("Cast", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(actors.take(20), key = { it.name }) { actor ->
                Column(Modifier.width(84.dp).clickable { onActor(actor) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Surface2).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                        Text(actorInitials(actor.name), color = Accent, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        AuthAsyncImage(session, actorImageUrl(session, actor), actor.name, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                    Text(actor.name, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (actor.role.isNotBlank()) Text(actor.role, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
                            if (meta.isNotBlank()) Text(meta, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (person.info.biography.isNotBlank()) Text(person.info.biography, color = TextColor.copy(alpha = .8f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
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
        Text("More like this", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { movie -> MovieCard(session, movie, Modifier.width(150.dp), onClick = { onOpen(movie) }) }
        }
    }
}

@Composable
fun DetailChip(label: String, bg: Color, fg: Color) {
    Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

private fun ratingBadges(item: PopItem, ratings: ExternalRatings?): List<Pair<String, String>> = buildList {
    ratings?.imdbRating?.takeIf { it > 0 }?.let { add("IMDb" to "%.1f".format(Locale.US, it)) }
    ratings?.tmdbRating?.takeIf { it > 0 }?.let { add("TMDb" to "%.1f".format(Locale.US, it)) }
    ratings?.rottenTomatoesRating?.takeIf { it > 0 }?.let { add("RT" to "$it%") }
    ratings?.metacriticRating?.takeIf { it > 0 }?.let { add("MC" to it.toString()) }
    if (isEmpty() && item.rating > 0) add("NFO" to "%.1f".format(Locale.US, item.rating))
}.take(4)

@Composable
private fun SourceRatingBadge(label: String, value: String) {
    Row(
        Modifier.background(Surface2, RoundedCornerShape(4.dp)).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Teal, fontWeight = FontWeight.Black, fontSize = 11.sp)
        Text(value, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
