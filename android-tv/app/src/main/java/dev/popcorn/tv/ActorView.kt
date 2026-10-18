package dev.popcorn.tv
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState

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

@OptIn(ExperimentalFoundationApi::class)
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

    // Same page model as detail and show pages: one column shifted to keep
    // the focused element on screen (preferring the top), no scroll
    // container to jitter, focus starting on the first title. The old
    // on-screen Back button is gone — the remote has one.
    var viewportPx by remember { mutableStateOf(0) }
    var contentPx by remember { mutableStateOf(0) }
    var targetShift by remember(actor.name) { mutableStateOf(0) }
    val shift by animateIntAsState(
        targetValue = targetShift.coerceIn(0, (contentPx - viewportPx).coerceAtLeast(0)),
        animationSpec = tween(durationMillis = 220),
        label = "actorShift",
    )
    var columnCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    fun followFocus(focused: LayoutCoordinates?) {
        val column = columnCoords ?: return
        if (focused == null || !focused.isAttached || !column.isAttached) return
        val rect = column.localBoundingBoxOf(focused, clipBounds = false)
        val topMargin = with(density) { 24.dp.toPx() }
        val bottomMargin = with(density) { 40.dp.toPx() }
        val viewport = viewportPx.toFloat()
        targetShift = if (rect.bottom + bottomMargin <= viewport) {
            0
        } else {
            minOf(rect.bottom + bottomMargin - viewport, rect.top - topMargin).coerceAtLeast(0f).toInt()
        }
    }
    var bioOpen by remember(actor.name) { mutableStateOf(false) }
    val bioFocus = remember { FocusRequester() }
    val firstTitleFocus = remember { FocusRequester() }
    val loaded = detail
    LaunchedEffect(loaded) {
        if (loaded == null) return@LaunchedEffect
        delay(200)
        runCatching { firstTitleFocus.requestFocus() }.onFailure { runCatching { bioFocus.requestFocus() } }
    }
    BackHandler(enabled = !bioOpen, onBack = onBack)

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .clipToBounds()
            .onSizeChanged { viewportPx = it.height },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { contentPx = it.height }
                .offset { IntOffset(0, -shift) }
                .onGloballyPositioned { columnCoords = it }
                .onFocusedBoundsChanged { followFocus(it) }
                .padding(horizontal = 52.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                error.isNotBlank() -> Text(error, color = ErrorRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                loaded != null -> {
                    ActorHero(session, loaded, bioFocus = bioFocus, onBio = { bioOpen = true }, onBioDown = {
                        runCatching { firstTitleFocus.requestFocus() }.isSuccess
                    })
                    if (loaded.movies.isNotEmpty()) {
                        Spacer(Modifier.height(28.dp))
                        ActorSectionHeader("Movies", loaded.movies.size)
                        LazyRow(
                            // Vertical room for the focused card's scale-up.
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(loaded.movies, key = { _, item -> item.id }) { index, item ->
                                Box(Modifier.width(130.dp)) {
                                    ItemCard(
                                        session = session,
                                        item = item,
                                        watched = completedItems.contains(item.id),
                                        watchlisted = watchlistItems.contains(item.id),
                                        focusRequester = if (index == 0) firstTitleFocus else null,
                                        onClick = { onItem(item) },
                                        onLongClick = { requester -> onItemMenu(item, requester) },
                                    )
                                }
                            }
                        }
                    }
                    if (loaded.shows.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        ActorSectionHeader("Shows", loaded.shows.size)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(loaded.shows, key = { _, show -> "${show.libraryId}:${show.title}" }) { index, show ->
                                Box(Modifier.width(130.dp)) {
                                    ShowCard(
                                        session = session,
                                        show = show,
                                        watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                                        watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                                        focusRequester = if (index == 0 && loaded.movies.isEmpty()) firstTitleFocus else null,
                                        onClick = { onShow(show) },
                                        onLongClick = { requester -> onShowMenu(show, requester) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (bioOpen && loaded != null) {
        ShowFullTextDialog(
            title = loaded.actor.name,
            subtitle = loaded.info.knownForDepartment,
            overview = loaded.info.biography,
            facts = listOfNotNull(
                loaded.info.birthday.takeIf { it.isNotBlank() }?.let { "Born" to it },
                loaded.info.placeOfBirth.takeIf { it.isNotBlank() }?.let { "Place of birth" to it },
            ),
            onDismiss = { bioOpen = false },
        )
    }
}

@Composable
private fun ActorHero(session: Session?, detail: ActorDetail, bioFocus: FocusRequester, onBio: () -> Unit, onBioDown: () -> Boolean) {
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
            Spacer(Modifier.height(10.dp))
            if (detail.info.biography.isNotBlank()) {
                // Focusable: OK opens the whole biography.
                FocusableDescriptionPanel(
                    overview = detail.info.biography,
                    maxLines = 7,
                    maxWidth = 760.dp,
                    focusRequester = bioFocus,
                    onDown = onBioDown,
                    onClick = onBio,
                )
            } else {
                Text("No biography available.", color = Muted, fontSize = 15.sp)
            }
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
