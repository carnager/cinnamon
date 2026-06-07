package dev.popcorn.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ActorView(
    session: Session?,
    actor: Actor,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    onBack: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var detail by remember(actor.name) { mutableStateOf<ActorDetail?>(null) }
    var loading by remember(actor.name) { mutableStateOf(true) }
    var error by remember(actor.name) { mutableStateOf("") }

    LaunchedEffect(actor.name, session?.token) {
        loading = true
        error = ""
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).actorDetail(actor.name) }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "Failed to load actor" }
        loading = false
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 52.dp),
    ) {
        item {
            Spacer(Modifier.height(42.dp))
            FocusButton("Back", primary = false, onClick = onBack)
            Spacer(Modifier.height(24.dp))
        }

        when {
            loading -> item {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            error.isNotBlank() -> item {
                Text(error, color = ErrorRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            detail != null -> {
                val actorDetail = detail!!
                item {
                    ActorHero(session, actorDetail)
                }
                if (actorDetail.movies.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(30.dp))
                        ActorSectionHeader("Movies", actorDetail.movies.size)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(actorDetail.movies) { _, item ->
                                Box(Modifier.width(130.dp).padding(horizontal = 4.dp)) {
                                    ItemCard(
                                        session = session,
                                        item = item,
                                        watched = completedItems.contains(item.id),
                                        watchlisted = watchlistItems.contains(item.id),
                                        onClick = { onItem(item) },
                                        onLongClick = { requester -> onItemMenu(item, requester) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (actorDetail.shows.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(30.dp))
                        ActorSectionHeader("Shows", actorDetail.shows.size)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(actorDetail.shows) { _, show ->
                                Box(Modifier.width(130.dp).padding(horizontal = 4.dp)) {
                                    ShowCard(
                                        session = session,
                                        show = show,
                                        watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                                        watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                                        onClick = { onShow(show) },
                                        onLongClick = { requester -> onShowMenu(show, requester) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(70.dp)) }
    }
}

@Composable
private fun ActorHero(session: Session?, detail: ActorDetail) {
    val profileUrl = actorImageUrl(session, detail.profileUrl)
    Row(
        Modifier.widthIn(max = 980.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(172.dp)
                .clip(CircleShape)
                .background(Surface2)
                .border(2.dp, Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (profileUrl.isNotBlank()) {
                SizedAsyncImage(
                    model = profileUrl,
                    contentDescription = detail.actor.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    widthPx = 344,
                    heightPx = 344,
                    authToken = if (detail.profileUrl.startsWith("/")) session?.token.orEmpty() else "",
                )
            } else {
                Text(actorInitials(detail.actor.name), color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.widthIn(max = 720.dp)) {
            Text(detail.actor.name, color = TextColor, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOf(
                detail.info.knownForDepartment,
                detail.info.birthday,
                detail.info.placeOfBirth,
            ).filter { it.isNotBlank() }.joinToString("  •  ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(meta, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (detail.actor.role.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("as ${detail.actor.role}", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                detail.info.biography.ifBlank { "No biography available." },
                color = TextColor.copy(alpha = .82f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActorSectionHeader(title: String, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("$count", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
    }
    Spacer(Modifier.height(12.dp))
}

private fun actorInitials(name: String): String {
    return name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString("")
        .ifBlank { "?" }
}
