package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun <T> PosterGrid(entries: List<T>, key: (T) -> Any, content: @Composable (T, Boolean, Int, FocusRequester) -> Unit) {
    val firstKey = entries.firstOrNull()?.let { key(it) }
    var initialFocusPending by remember(firstKey) { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = 32f * 2f
        val minCellWidth = 120f
        val spacing = 10f
        val columns = max(1, ((maxWidth.value - horizontalPadding + spacing) / (minCellWidth + spacing)).toInt())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItemsIndexed(entries, key = { _, item -> key(item) }) { index, item ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = initialFocusPending && index == 0
                if (focusNow) {
                    LaunchedEffect(key(item)) {
                        delay(450)
                        initialFocusPending = false
                    }
                }
                content(item, focusNow, index % columns, focusRequester)
            }
        }
    }
}

@Composable
fun ShowCard(
    session: Session?,
    show: ShowSummary,
    watched: Boolean = false,
    watchlisted: Boolean = false,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onLeftEdge = onLeftEdge, onClick = onClick, onLongClick = onLongClick) {
        Box {
            Poster(session, show.posterItemId, Modifier.fillMaxWidth(), show.posterMtimeUnix)
            if (show.rating > 0) PosterRating(show.rating)
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Spacer(Modifier.height(4.dp))
        Text(show.title, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${show.seasonCount}S \u00b7 ${show.episodeCount}E", color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun ItemCard(
    session: Session?,
    item: PopItem,
    watched: Boolean = false,
    watchlisted: Boolean = false,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onLeftEdge = onLeftEdge, onClick = onClick, onLongClick = onLongClick) {
        Box {
            Poster(session, item.id, Modifier.fillMaxWidth(), item.posterMtimeUnix)
            if (item.rating > 0) PosterRating(item.rating)
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title,
            color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.year.takeIf { it > 0 }?.toString(),
            fmtDuration(item.durationMs).ifBlank { null },
        ).joinToString(" \u00b7 ")
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun BoxScope.SeenBadge() {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Accent.copy(alpha = .92f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text("Seen", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun BoxScope.WatchlistBadge() {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Gold.copy(alpha = .94f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text("List", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun EpisodeRow(session: Session?, item: PopItem, watched: Boolean = false, watchlisted: Boolean = false, autoFocus: Boolean = false, onFocus: (() -> Unit)? = null, onClick: () -> Unit, onLongClick: ((FocusRequester) -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    var longPressReady by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            focusRequester.requestFocus()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp)
            .clip(CardShape)
            .background(if (focused) Surface2 else SurfaceColor)
            .border(1.dp, if (focused) FocusGlow else Color.Transparent, CardShape)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    longPressJob = null
                    longPressReady = false
                }
            }
            .focusable()
            .onKeyEvent {
                when {
                    isActivationKey(it.key) && it.type == KeyEventType.KeyDown -> {
                        if (onLongClick != null) {
                            if (longPressJob == null) {
                                longPressReady = false
                                longPressJob = scope.launch {
                                    delay(650)
                                    longPressReady = true
                                }
                            }
                        }
                        true
                    }
                    isActivationKey(it.key) && it.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressReady) {
                            longPressReady = false
                            onLongClick?.invoke(focusRequester)
                        } else {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.width(100.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Surface2),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (session != null) {
                val thumbUrl = if (item.backdropPath.isNotBlank()) {
                    imageUrl(session, item.id, "backdrop", item.backdropMtimeUnix)
                } else if (item.posterPath.isNotBlank()) {
                    imageUrl(session, item.id, "poster", item.posterMtimeUnix)
                } else null
                if (thumbUrl != null) {
                    SizedAsyncImage(model = thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 150)
                }
            }
            Text(
                "%02d".format(item.episodeNumber),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(4.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Column(Modifier.weight(1f)) {
            Text(item.episodeTitle.ifBlank { item.title }, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.overview, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val dur = fmtDuration(item.durationMs)
            if (dur.isNotBlank()) Text(dur, color = Muted, fontSize = 10.sp)
            if (item.rating > 0) {
                Spacer(Modifier.height(2.dp))
                RatingBadge(item.rating, small = true)
            }
        }
    }
}

@Composable
fun WatchActionOverlay(
    title: String,
    watched: Boolean,
    watchlisted: Boolean,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onAddWatchlist: () -> Unit,
    onRemoveWatchlist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        delay(80)
        firstFocus.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .58f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(320.dp)
                .clip(CardShape)
                .background(SurfaceColor)
                .border(1.dp, Line, CardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    if (watched) "Watched" else "Unwatched",
                    if (watchlisted) "In watchlist" else "Not in watchlist",
                ).joinToString(" \u00b7 "),
                color = Muted,
                fontSize = 12.sp,
            )
            FocusButton("Mark watched", primary = !watched, modifier = Modifier.focusRequester(firstFocus)) { onMarkWatched() }
            FocusButton("Mark unwatched", primary = watched) { onMarkUnwatched() }
            FocusButton("Add to watchlist", primary = !watchlisted) { onAddWatchlist() }
            FocusButton("Remove from watchlist", primary = watchlisted) { onRemoveWatchlist() }
            FocusButton("Cancel", primary = false) { onDismiss() }
        }
    }
}

@Composable
fun CardShell(
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressReady by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val requester = focusRequester ?: remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            requester.requestFocus()
        }
    }
    Column(
        Modifier
            .clip(CardShape)
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, CardShape)
            .background(if (focused) Surface2 else Color.Transparent)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    longPressJob = null
                    longPressReady = false
                }
            }
            .focusable()
            .onKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && onLeftEdge != null -> onLeftEdge()
                    isActivationKey(it.key) && it.type == KeyEventType.KeyDown -> {
                        if (onLongClick != null) {
                            if (longPressJob == null) {
                                longPressReady = false
                                longPressJob = scope.launch {
                                    delay(650)
                                    longPressReady = true
                                }
                            }
                        }
                        true
                    }
                    isActivationKey(it.key) && it.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressReady) {
                            longPressReady = false
                            onLongClick?.invoke(requester)
                        } else {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(if (focused) 5.dp else 4.dp),
        content = content,
    )
}
