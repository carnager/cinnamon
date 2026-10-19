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
import androidx.compose.material.icons.filled.Block
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ColumnScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
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
    recommendationExcluded: Boolean,
    onSetWatchlisted: (Boolean) -> Unit,
    onSetPreference: (MediaPreference) -> Unit,
    onBack: () -> Unit,
    onPlay: (PopItem, Int?, Int?, Long) -> Unit,
    onPlayLocal: (PopItem, Int?, Int?, Long) -> Unit,
    onOpenSimilar: (PopItem) -> Unit,
    onActor: (Actor) -> Unit,
) {
    val context = LocalContext.current
    var detailItem by remember(item.id) { mutableStateOf(item) }
    var progress by remember(item.id) { mutableStateOf<PlaybackProgress?>(null) }
    // Play waits for this: started before the saved position arrives, it would
    // begin from zero and tell the TV to do the same.
    var progressLoaded by remember(item.id) { mutableStateOf(false) }
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
    var preferenceOpen by remember(item.id) { mutableStateOf(false) }
    val ratingScope = rememberCoroutineScope()
    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }

    // The requests are independent, so they go out together: the page is
    // ready when the slowest one answers rather than after all of them in turn.
    LaunchedEffect(item.id, PlaybackPrefs.audioLang, PlaybackPrefs.subtitleLang) {
        launch {
            runCatching { api.progress(item.id) }.onSuccess { progress = it }
            progressLoaded = true
        }
        launch { runCatching { api.item(item.id) }.onSuccess { detailItem = it } }
        launch {
            runCatching { api.streams(item.id) }
                .onSuccess { loaded ->
                    streams = loaded
                    selectedAudio = preferredAudioIndex(loaded.filter { it.type == "audio" })
                    selectedSubtitle = preferredSubtitleIndex(loaded.filter { it.type == "subtitle" })
                }
                .onFailure { error = it.message ?: "Could not load streams" }
        }
        launch { runCatching { api.ratings(item.id) }.onSuccess { ratings = it } }
        launch { runCatching { api.itemRating(item.id) }.onSuccess { userRating = it } }
        if (item.kind == "movie") launch { similar = runCatching { api.similar(item.id) }.getOrDefault(emptyList()) }
    }

    val resumeMs = progress?.takeIf { progressResumable(it.positionMs, it.durationMs) }?.positionMs ?: 0L
    val preference = when {
        recommendationExcluded -> MediaPreference.NotInterested
        watched -> MediaPreference.Seen
        else -> MediaPreference.Unwatched
    }

    fun start(positionMs: Long) {
        if (playbackTarget == PlaybackTarget.Phone) {
            onPlayLocal(detailItem, selectedAudio, selectedSubtitle, positionMs)
        } else {
            onPlay(detailItem, selectedAudio, selectedSubtitle, positionMs)
        }
    }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { DetailHero(session, detailItem, ratings, streams, onBack) }
        if (error.isNotBlank()) item { Text(error, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // With a saved position the main button resumes and
                    // starting over sits beside it: two direct buttons instead
                    // of a sheet asking which one on every press.
                    Button(
                        onClick = { start(resumeMs) },
                        enabled = progressLoaded,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.White,
                            disabledContainerColor = Accent.copy(alpha = .45f),
                            disabledContentColor = Color.White.copy(alpha = .8f),
                        ),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (progressLoaded) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        } else {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            if (resumeMs > 0) "  Resume ${formatTime(resumeMs)}" else "  Play",
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            maxLines = 1,
                        )
                    }
                    if (resumeMs > 0) {
                        OutlinedButton(
                            onClick = { start(0) },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null, tint = TextColor, modifier = Modifier.size(20.dp))
                            Text(" Start over", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
                // Timing only. Where playback lands, and changing it, is the
                // top bar's cast chip — which is pinned and visible from here,
                // so repeating it under the button was just clutter.
                val remainingMs = (detailItem.durationMs - resumeMs).coerceAtLeast(0)
                val endsAround = fmtEndsAround(remainingMs)
                if (endsAround.isNotBlank()) {
                    Text(endsAround, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                // Secondary to Play: no borders unless active, so the primary
                // action keeps the visual weight it deserves.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailAction(
                        label = when (preference) {
                            MediaPreference.Unwatched -> "Watch"
                            MediaPreference.Seen -> "Seen"
                            MediaPreference.NotInterested -> "Not interested"
                        },
                        icon = if (preference == MediaPreference.NotInterested) Icons.Filled.Block else Icons.Filled.CheckCircle,
                        modifier = Modifier.weight(1f),
                        active = preference != MediaPreference.Unwatched,
                        activeColor = if (preference == MediaPreference.NotInterested) ErrorRed else Accent,
                    ) { preferenceOpen = true }
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
        if (detailItem.overview.isNotBlank() || detailFacts(detailItem).isNotEmpty()) item { DetailOverview(detailItem) }
        if (detailItem.actors.isNotEmpty()) item { CastStrip(session, detailItem.actors, onActor) }
        if (similar.isNotEmpty()) item { SimilarRow(session, similar, onOpenSimilar) }
    }
    StatusBarScrim(listState)
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
    if (preferenceOpen) {
        MediaPreferenceSheet(
            selected = preference,
            allowNotInterested = detailItem.kind == "movie",
            onDismiss = { preferenceOpen = false },
            onSelect = {
                preferenceOpen = false
                onSetPreference(it)
            },
        )
    }
    if (ratingOpen) {
        RatingSheet(
            selected = userRating,
            onDismiss = { ratingOpen = false },
            onSelect = { value ->
                ratingScope.launch {
                    runCatching {
                        if (value > 0) api.setItemRating(item.id, value) else api.deleteItemRating(item.id)
                    }.onSuccess {
                        userRating = value
                        ratingOpen = false
                    }.onFailure { error = it.message ?: "Failed to save rating" }
                }
            },
        )
    }
}

@Composable
fun MediaPreferenceSheet(
    selected: MediaPreference,
    allowNotInterested: Boolean,
    onDismiss: () -> Unit,
    onSelect: (MediaPreference) -> Unit,
) {
    PopcornBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WATCH STATUS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(8.dp))
            buildList {
                add(MediaPreference.Unwatched to "Unwatched")
                add(MediaPreference.Seen to "Seen")
                if (allowNotInterested) add(MediaPreference.NotInterested to "Not interested")
            }.forEach { (preference, label) ->
                TrackChoice(label, selected == preference) { onSelect(preference) }
            }
        }
    }
}

@Composable
private fun RatingSheet(
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    PopcornBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RATING", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(8.dp))
            if (selected > 0) TrackChoice("Remove rating", false) { onSelect(0) }
            for (value in 10 downTo 1) {
                TrackChoice("★".repeat(value) + "  $value/10", selected == value) { onSelect(value) }
            }
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

// The top of a show or a film, as streaming apps lay it out on a phone: the
// wide artwork edge to edge, the title's logo (or the title) at its foot, and
// one compact block of facts under it. No cover: on a narrow screen it was a
// second picture of the same thing, and it pushed the facts into a column
// beside it or below it.
@Composable
fun MediaHero(
    session: Session,
    backdropUrl: String,
    logoUrl: String,
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    facts: @Composable ColumnScope.() -> Unit = {},
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdropHeight = (LocalConfiguration.current.screenWidthDp * 9f / 16f).dp + statusTop
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(backdropHeight + 24.dp)) {
            if (backdropUrl.isNotBlank()) {
                AuthAsyncImage(session, backdropUrl, null, Modifier.fillMaxWidth().height(backdropHeight + 24.dp), ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Surface3, Bg))))
            }
            // Clear art above, settling into the page where the title sits.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.4f to Color.Transparent, .78f to Bg.copy(alpha = .72f), 1f to Bg)))
            // Keeps the clock and the two buttons readable on bright art.
            Box(Modifier.fillMaxWidth().height(statusTop + 64.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .5f), Color.Transparent))))
            HeroIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), onClick = onBack)
            LocalPlaybackTarget.current?.let { target ->
                PlaybackTargetSwitcher(target, compact = true, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
            }
            TitleLogo(session, logoUrl, title, Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp).fillMaxWidth(.78f))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            facts()
        }
    }
}

// The title's logo artwork where there is one, the title in type where there
// is not. The space is the same either way, so nothing shifts when the logo
// arrives or turns out to be missing.
@Composable
private fun TitleLogo(session: Session, logoUrl: String, title: String, modifier: Modifier = Modifier) {
    var loaded by remember(logoUrl) { mutableStateOf(false) }
    val context = LocalContext.current
    Box(modifier.height(76.dp), contentAlignment = Alignment.BottomStart) {
        if (!loaded) {
            Text(title, color = TextColor, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-.4).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (logoUrl.isNotBlank()) {
            val request = remember(logoUrl, session.token) {
                ImageRequest.Builder(context).data(logoUrl).apply {
                    if (session.token.isNotBlank() && logoUrl.startsWith(session.server)) addHeader("Authorization", "Bearer ${session.token}")
                }.build()
            }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (loaded) 1f else 0f },
            )
        }
    }
}

@Composable
fun DetailHero(session: Session, item: PopItem, ratings: ExternalRatings?, streams: List<StreamInfo>, onBack: () -> Unit) {
    val backdropUrl = if (item.backdropMtimeUnix > 0) imageUrl(session, item.id, item.backdropMtimeUnix, "backdrop", ArtworkFull) else ""
    val genres = item.genres.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() }.take(3)
    val episode = item.kind == "episode"
    MediaHero(
        session = session,
        backdropUrl = backdropUrl,
        // An episode wears its show's logo, with its own title under it.
        logoUrl = imageUrl(session, item.id, item.posterMtimeUnix.coerceAtLeast(item.backdropMtimeUnix), "clearlogo"),
        title = if (episode) item.showTitle.ifBlank { displayTitle(item) } else displayTitle(item),
        onBack = onBack,
        subtitle = if (episode) displayTitle(item) else "",
    ) {
        // Year, runtime and age rating; then the ratings with the file's
        // format; then the genres.
        val meta = detailMetadata(item)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (item.officialRating.isNotBlank()) ContentRatingChip(item.officialRating)
        }
        val badges = ratingBadges(item, ratings)
        val tech = techChips(item, streams)
        if (badges.isNotEmpty() || tech.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    badges.forEachIndexed { index, (label, value) ->
                        if (index > 0) append("   ")
                        withStyle(SpanStyle(color = Teal.copy(alpha = .9f), fontWeight = FontWeight.Black)) { append(label) }
                        append(" ")
                        withStyle(SpanStyle(color = TextColor, fontWeight = FontWeight.Bold)) { append(value) }
                    }
                    if (tech.isNotEmpty()) {
                        if (badges.isNotEmpty()) append("   ")
                        withStyle(SpanStyle(color = Muted, fontWeight = FontWeight.SemiBold)) { append(tech.joinToString(" · ")) }
                    }
                },
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        if (genres.isNotEmpty()) {
            Text(genres.joinToString(" · "), color = Teal.copy(alpha = .9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        // The show is the logo above; the episode's place in it is the fact.
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
    // Three lines, then everything (with director and writers) on a tap, as
    // streaming apps do: in full it pushed the cast and similar titles a
    // screen down for something most visits only glance at.
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val facts = detailFacts(item)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().animateContentSize(tween(180)).clickable { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (item.tagline.isNotBlank()) Text(item.tagline, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (item.overview.isNotBlank()) {
            Text(
                item.overview,
                color = TextColor.copy(alpha = .84f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            facts.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text(label, color = Accent.copy(alpha = .86f), fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(66.dp))
                    Text(value, color = TextColor.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                }
            }
        } else if (facts.isNotEmpty() || item.overview.length > 160) {
            Text("More", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
fun TrackChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) Accent else Surface1).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Color.Black else TextColor, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Text("Selected", color = Color.Black.copy(alpha = .65f), fontSize = 12.sp)
    }
}
