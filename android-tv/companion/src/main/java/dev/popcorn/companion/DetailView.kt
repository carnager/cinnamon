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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetailPage(
    session: Session,
    api: Api,
    item: PopItem,
    playbackTarget: PlaybackTarget,
    selectedDeviceName: String?,
    onBack: () -> Unit,
    onPlay: (PopItem, Int?, Int?) -> Unit,
    onPlayLocal: (PopItem, Int?, Int?) -> Unit,
) {
    var streams by remember(item.id) { mutableStateOf<List<StreamInfo>>(emptyList()) }
    var ratings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }
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
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Surface1), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    PosterImage(imageUrl(session, item.id, item.posterMtimeUnix), Modifier.width(132.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(displayTitle(item), color = TextColor, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        val meta = listOf(
                            item.year.takeIf { it > 0 }?.toString(),
                            fmtDuration(item.durationMs),
                            if (item.kind == "episode") "S%02d E%02d".format(item.seasonNumber, item.episodeNumber) else null,
                        ).filterNotNull().joinToString(" \u00b7 ")
                        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        RatingBadges(item, ratings)
                    }
                }
            }
        }
        item {
            val targetLabel = if (playbackTarget == PlaybackTarget.Phone) "Phone" else cleanDeviceName(selectedDeviceName ?: "") ?: "TV"
            Button(
                onClick = {
                    if (playbackTarget == PlaybackTarget.Phone) onPlayLocal(item, selectedAudio, selectedSubtitle)
                    else onPlay(item, selectedAudio, selectedSubtitle)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Play on $targetLabel", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
        if (item.overview.isNotBlank()) {
            item {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Overview", color = TextColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(item.overview, color = TextColor, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            item { Text(error, color = ErrorRed, fontSize = 12.sp) }
        }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Surface1), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TrackSection("Audio", audioTracks, selectedAudio, emptyLabel = "Default", onSelect = { selectedAudio = it })
                    TrackSection("Subtitles", subtitleTracks, selectedSubtitle, emptyLabel = "Off", onSelect = { selectedSubtitle = it })
                }
            }
        }
    }
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
