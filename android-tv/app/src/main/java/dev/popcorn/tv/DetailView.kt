package dev.popcorn.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun DetailView(item: PopItem, session: Session?, onPlay: (Int?, Int?) -> Unit) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    var selectedAudio by remember { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember { mutableStateOf<Int?>(null) }
    var streamsLoaded by remember { mutableStateOf(false) }
    var externalRatings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).streams(item.id) }
            .onSuccess { list ->
                streams.clear()
                streams.addAll(list)
                val defAudio = list.firstOrNull { it.type == "audio" && it.default }
                    ?: list.firstOrNull { it.type == "audio" }
                selectedAudio = defAudio?.index
                val defSub = list.firstOrNull { it.type == "subtitle" && it.default }
                selectedSubtitle = defSub?.index
                streamsLoaded = true
            }
            .onFailure { streamsLoaded = true }
    }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        externalRatings = null
        runCatching { Api(active).ratings(item.id) }
            .onSuccess { externalRatings = it }
    }

    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            if (item.backdropPath.isNotBlank() && session != null) {
                SizedAsyncImage(
                    model = imageUrl(session, item.id, "backdrop", item.backdropMtimeUnix),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    widthPx = 900,
                    heightPx = 360,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(colors = listOf(Color.Transparent, Bg), startY = 28f)
                )
            )
        }

        Row(
            Modifier.padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Poster(session, item.id, Modifier.width(90.dp), item.posterMtimeUnix)
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title,
                    color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp,
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val metaParts = mutableListOf<String>()
                    if (item.kind == "episode") {
                        metaParts.add("S%02dE%02d".format(item.seasonNumber, item.episodeNumber))
                        if (item.showTitle.isNotBlank()) metaParts.add(item.showTitle)
                    } else {
                        if (item.year > 0) metaParts.add(item.year.toString())
                    }
                    if (item.durationMs > 0) metaParts.add(fmtDuration(item.durationMs))
                    if (metaParts.isNotEmpty()) Text(metaParts.joinToString(" \u00b7 "), color = Muted, fontSize = 12.sp)
                    val hasSourceRatings = externalRatings?.let { it.imdbRating > 0 || it.tmdbRating > 0 || it.rottenTomatoesRating > 0 } == true
                    if (item.rating > 0 && !hasSourceRatings) RatingBadge(item.rating)
                    externalRatings?.let { ratings ->
                        if (ratings.imdbRating > 0) SourceRatingBadge("IMDb", "%.1f".format(Locale.US, ratings.imdbRating))
                        if (ratings.tmdbRating > 0) SourceRatingBadge("TMDb", "%.1f".format(Locale.US, ratings.tmdbRating))
                        if (ratings.rottenTomatoesRating > 0) SourceRatingBadge("RT", "${ratings.rottenTomatoesRating}%")
                    }
                }
                if (item.genres.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(item.genres, color = Muted, fontSize = 11.sp)
                }
                if (item.overview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(item.overview, color = TextColor.copy(alpha = .75f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                FocusButton(label = "\u25B6  Play", primary = true, onClick = { onPlay(selectedAudio, selectedSubtitle) })
            }
        }

        Spacer(Modifier.height(12.dp))

        if (streamsLoaded && (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty())) {
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (audioTracks.isNotEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio", color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn {
                            items(audioTracks) { track ->
                                TrackRow(
                                    label = track.label(),
                                    selected = track.index == selectedAudio,
                                    onClick = { selectedAudio = track.index },
                                )
                            }
                        }
                    }
                }
                if (subtitleTracks.isNotEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text("Subtitles", color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn {
                            item {
                                TrackRow(label = "Off", selected = selectedSubtitle == null, onClick = { selectedSubtitle = null })
                            }
                            items(subtitleTracks) { track ->
                                TrackRow(
                                    label = track.label(),
                                    selected = track.index == selectedSubtitle,
                                    onClick = { selectedSubtitle = track.index },
                                )
                            }
                        }
                    }
                }
            }
        } else if (!streamsLoaded) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) Accent.copy(alpha = .15f) else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused) FocusGlow else if (selected) Accent.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(5.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (selected) "\u25C9" else "\u25CB",
            color = if (selected) Accent else Muted,
            fontSize = 12.sp,
        )
        Text(label, color = if (selected) TextColor else Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
