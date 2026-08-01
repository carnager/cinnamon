package dev.popcorn.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// ── Discover ──
//
// Everything the library does not have, in one place — so no surface you press
// play on ever shows something you cannot play. It is built from the same
// cards, spacing and type as the rest of the app: a different destination, not
// a different application. The word Trakt appears nowhere; it is plumbing, like
// TMDb, and lives in settings.

@Composable
fun DiscoverPage(
    session: Session,
    chip: DiscoverChip,
    onChip: (DiscoverChip) -> Unit,
    kind: String,
    onKind: (String) -> Unit,
    entries: List<TraktEntry>,
    loading: Boolean,
    error: String,
    wantedKeys: Set<String>,
    onWant: (TraktEntry) -> Unit,
    onHide: (TraktEntry) -> Unit,
    onArrived: (PopItem) -> Unit,
) {
    var opened by remember { mutableStateOf<TraktEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiscoverChip.entries.forEach { entry ->
                DiscoverPill(entry.label, entry == chip) { onChip(entry) }
            }
        }
        if (chip != DiscoverChip.AiringSoon) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiscoverPill("Movies", kind == "movies", small = true) { onKind("movies") }
                DiscoverPill("Shows", kind == "shows", small = true) { onKind("shows") }
            }
        }

        when {
            loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            // An empty list and a failed request are different things, and
            // saying so is the whole reason this reads live.
            error.isNotBlank() -> DiscoverNotice("Could not reach the service", error)
            entries.isEmpty() -> DiscoverNotice(emptyTitle(chip), emptyBody(chip))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    DiscoverRow(
                        session = session,
                        entry = entry,
                        wanted = wantedKeys.contains(entry.key),
                        showHide = chip == DiscoverChip.ForYou,
                        onWant = { onWant(entry) },
                        onHide = { onHide(entry) },
                        onOpen = { opened = it },
                        onArrived = onArrived,
                    )
                }
            }
        }
    }

    opened?.let { entry ->
        DiscoverSheet(
            session = session,
            entry = entry,
            wanted = wantedKeys.contains(entry.key),
            onWant = {
                onWant(entry)
                opened = null
            },
            onHide = {
                onHide(entry)
                opened = null
            },
            onDismiss = { opened = null },
        )
    }
}

// A synopsis before you decide you want it — the thing a row has no room for.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverSheet(
    session: Session,
    entry: TraktEntry,
    wanted: Boolean,
    onWant: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surface1) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(96.dp)) {
                    PosterImage(session, entry.posterUrl, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        entry.displayTitle,
                        color = TextColor,
                        fontSize = 19.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val facts = listOfNotNull(
                        entry.year.takeIf { it > 0 }?.toString(),
                        entry.runtime.takeIf { it > 0 }?.let { "$it min" },
                        entry.scoreLabel,
                        entry.rottenTomatoes.takeIf { it > 0 }?.let { "RT $it%" },
                    )
                    if (facts.isNotEmpty()) {
                        Text(facts.joinToString(" · "), color = Muted, fontSize = 13.sp)
                    }
                    if (entry.genres.isNotBlank()) {
                        Text(entry.genres, color = Teal.copy(alpha = .86f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (entry.kind == "episode" && entry.season > 0) {
                        Text("S%02dE%02d".format(entry.season, entry.episode), color = Muted, fontSize = 13.sp)
                    }
                }
            }
            Text(
                entry.overview.ifBlank { "No description available." },
                color = TextColor.copy(alpha = .82f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DiscoverAction(
                    if (wanted) "Wanted" else "Want it",
                    if (wanted) Icons.Default.Check else Icons.Default.Add,
                    primary = !wanted,
                    onClick = onWant,
                )
                DiscoverAction("Not for me", Icons.Default.VisibilityOff, onClick = onHide)
            }
        }
    }
}

@Composable
private fun DiscoverRow(
    session: Session,
    entry: TraktEntry,
    wanted: Boolean,
    showHide: Boolean,
    onWant: () -> Unit,
    onHide: () -> Unit,
    onOpen: (TraktEntry) -> Unit,
    onArrived: (PopItem) -> Unit,
) {
    val arrived = entry.item
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1.copy(alpha = .5f))
            .border(1.dp, Line.copy(alpha = .45f), RoundedCornerShape(12.dp))
            .clickable { if (arrived != null) onArrived(arrived) else onOpen(entry) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(74.dp)) {
            val poster = if (arrived != null) {
                imageUrl(session, arrived.id, arrived.posterMtimeUnix, width = ArtworkCard)
            } else {
                entry.posterUrl
            }
            PosterImage(session, poster, Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayTitle,
                color = TextColor,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = discoverDetail(entry)
            if (detail.isNotBlank()) {
                Text(detail, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            entry.scoreLabel?.let { score ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(score, color = Gold, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    if (entry.rottenTomatoes > 0) {
                        Text("RT ${entry.rottenTomatoes}%", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (arrived != null) {
                    // It landed while it was sitting here: the row stops being a
                    // wish and becomes a thing you can watch.
                    DiscoverAction("Watch", Icons.Default.PlayArrow, primary = true) { onArrived(arrived) }
                } else {
                    DiscoverAction(
                        if (wanted) "Wanted" else "Want it",
                        if (wanted) Icons.Default.Check else Icons.Default.Add,
                        primary = !wanted,
                        onClick = onWant,
                    )
                    if (showHide) {
                        DiscoverAction("Not for me", Icons.Default.VisibilityOff, onClick = onHide)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPill(label: String, selected: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) Accent.copy(alpha = .16f) else Surface1)
            .border(1.dp, if (selected) Accent.copy(alpha = .7f) else Line.copy(alpha = .5f), RoundedCornerShape(99.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 6.dp else 8.dp),
    ) {
        Text(
            label,
            color = if (selected) TextColor else Muted,
            fontSize = if (small) 12.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DiscoverAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (primary) Accent.copy(alpha = .18f) else Color.Transparent)
            .border(1.dp, if (primary) Accent.copy(alpha = .55f) else Line.copy(alpha = .6f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) Accent else Muted, modifier = Modifier.size(16.dp))
        Text(label, color = if (primary) TextColor else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DiscoverNotice(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
}

private fun emptyTitle(chip: DiscoverChip): String = when (chip) {
    DiscoverChip.ForYou -> "No suggestions right now"
    DiscoverChip.Wanted -> "Nothing on the list"
    DiscoverChip.AiringSoon -> "Nothing airing"
}

private fun emptyBody(chip: DiscoverChip): String = when (chip) {
    DiscoverChip.ForYou -> "Suggestions are built from what you have watched, and skip anything already in the library."
    DiscoverChip.Wanted -> "Titles you mark as wanted show up here until they turn up in the library."
    DiscoverChip.AiringSoon -> "New episodes of the shows you have will appear here before they land."
}

// "In 3 days · S03E05" or "2019 · watched in March" — whatever the row is for.
private fun discoverDetail(entry: TraktEntry): String {
    val parts = mutableListOf<String>()
    if (entry.kind == "episode" && entry.season > 0) {
        parts.add("S%02dE%02d".format(entry.season, entry.episode))
    } else if (entry.year > 0) {
        parts.add(entry.year.toString())
    }
    airsIn(entry.airedAt)?.let { parts.add(it) }
    return parts.joinToString(" · ")
}

private fun airsIn(airedAt: String): String? {
    if (airedAt.isBlank()) return null
    val aired = runCatching { OffsetDateTime.parse(airedAt) }.getOrNull() ?: return null
    val days = Duration.between(OffsetDateTime.now(), aired).toDays()
    return when {
        days < 0 -> "aired ${aired.format(DateTimeFormatter.ofPattern("d MMM"))}"
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days < 7 -> "in $days days"
        else -> aired.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

@Suppress("unused")
private fun today(): LocalDate = LocalDate.now()
