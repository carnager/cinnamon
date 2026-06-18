package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun <T> PosterGrid(
    entries: List<T>,
    key: (T) -> Any,
    initialFocusKey: Any? = null,
    alphabetTitle: ((T) -> String)? = null,
    alphabetEntries: List<AlphabetEntry> = emptyList(),
    onAlphabet: ((AlphabetEntry) -> Unit)? = null,
    alphabetFocusRequester: FocusRequester? = null,
    selectedAlphabetLetter: String? = null,
    restoreFocus: (() -> Boolean)? = null,
    content: @Composable (T, Boolean, Int, Boolean, Boolean, FocusRequester) -> Unit,
) {
    val firstKey = entries.firstOrNull()?.let { key(it) }
    val targetKey = initialFocusKey?.takeIf { requested -> entries.any { key(it) == requested } } ?: firstKey
    var initialFocusPending by remember(firstKey, targetKey) { mutableStateOf(true) }
    // Start the grid scrolled to the restore target: a lazy grid only composes
    // visible items, so an off-screen target would otherwise never receive its
    // autoFocus request and the grid would open at the top.
    val initialGridIndex = remember(firstKey, targetKey) {
        if (targetKey == null) 0 else entries.indexOfFirst { key(it) == targetKey }.coerceAtLeast(0)
    }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialGridIndex)
    val alphabetIndex = remember(entries, alphabetTitle, alphabetEntries, onAlphabet) {
        if (alphabetEntries.isNotEmpty()) {
            alphabetEntries.associateBy { it.letter }
        } else if (alphabetTitle == null || onAlphabet != null) {
            emptyMap()
        } else {
            val out = linkedMapOf<String, AlphabetEntry>()
            entries.forEachIndexed { index, item ->
                val letter = alphabetLetter(alphabetTitle.invoke(item))
                val existing = out[letter]
                if (existing == null) {
                    out[letter] = AlphabetEntry(letter, index, 1)
                } else {
                    out[letter] = existing.copy(count = existing.count + 1)
                }
            }
            out
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = 32f * 2f
        val minCellWidth = 118f
        val spacing = 12f
        val columns = max(1, ((maxWidth.value - horizontalPadding + spacing) / (minCellWidth + spacing)).toInt())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = if (alphabetIndex.isEmpty()) 32.dp else 58.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItemsIndexed(entries, key = { _, item -> key(item) }) { index, item ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = initialFocusPending && targetKey != null && key(item) == targetKey
                val column = index % columns
                val firstRow = index < columns
                val rightEdge = column == columns - 1 || index == entries.lastIndex
                if (focusNow) {
                    LaunchedEffect(key(item)) {
                        delay(450)
                        initialFocusPending = false
                    }
                }
                content(item, focusNow, column, firstRow, rightEdge, focusRequester)
            }
        }
        if (alphabetIndex.isNotEmpty()) {
            AlphabetRail(
                index = alphabetIndex,
                gridState = gridState,
                onAlphabet = onAlphabet,
                focusRequester = alphabetFocusRequester,
                requestedFocusLetter = selectedAlphabetLetter,
                restoreFocus = restoreFocus,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            )
        }
    }
}

private fun alphabetLetter(title: String): String {
    val ch = title.withoutLeadingArticle().firstOrNull() ?: return "#"
    return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
}

private fun String.withoutLeadingArticle(): String {
    val trimmed = trim()
    val lowered = trimmed.lowercase()
    val articles = listOf("the ", "a ", "an ", "der ", "die ", "das ", "ein ", "eine ")
    val article = articles.firstOrNull { lowered.startsWith(it) } ?: return trimmed
    return trimmed.drop(article.length).trimStart()
}

@Composable
private fun AlphabetRail(
    index: Map<String, AlphabetEntry>,
    gridState: LazyGridState,
    onAlphabet: ((AlphabetEntry) -> Unit)?,
    focusRequester: FocusRequester? = null,
    requestedFocusLetter: String? = null,
    restoreFocus: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val letters = listOf("#") + ('A'..'Z').map { it.toString() }
    val firstEnabledLetter = letters.firstOrNull { index[it] != null }
    val focusLetter = requestedFocusLetter?.takeIf { index[it] != null } ?: firstEnabledLetter
    Column(
        modifier
            .width(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = .48f))
            .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(10.dp))
            .padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            var focused by remember { mutableStateOf(false) }
            val target = index[letter]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .then(if (letter == focusLetter && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused && target != null) Accent else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable(enabled = target != null)
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && restoreFocus != null) {
                            restoreFocus()
                        } else {
                            false
                        }
                    }
                    .tvActivate {
                        if (target != null) {
                            if (onAlphabet != null) {
                                onAlphabet(target)
                                if (focusRequester != null) {
                                    scope.launch {
                                        delay(180)
                                        runCatching { focusRequester.requestFocus() }
                                    }
                                }
                            } else {
                                scope.launch { gridState.scrollToItem(target.offset) }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    color = when {
                        target == null -> Muted.copy(alpha = .3f)
                        focused -> Color.Black
                        else -> TextColor
                    },
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
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
    onRightEdge: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onLeftEdge = onLeftEdge, onRightEdge = onRightEdge, onUp = onUp, onClick = onClick, onLongClick = onLongClick) {
        Box {
            Poster(session, show.posterItemId, Modifier.fillMaxWidth(), show.posterMtimeUnix)
            if (show.rating > 0) PosterRating(show.rating)
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Spacer(Modifier.height(5.dp))
        Text(show.title, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onRightEdge: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onLeftEdge = onLeftEdge, onRightEdge = onRightEdge, onUp = onUp, onClick = onClick, onLongClick = onLongClick) {
        Box {
            Poster(session, item.id, Modifier.fillMaxWidth(), item.posterMtimeUnix)
            if (item.rating > 0) PosterRating(item.rating)
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
            val progress = LocalResumeProgress.current[item.id] ?: 0f
            if (progress > 0f) PosterProgressBar(progress)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title,
            color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.year.takeIf { it > 0 }?.toString(),
            fmtDuration(item.durationMs).ifBlank { null },
        ).joinToString(" \u00b7 ")
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 10.sp)
        if (item.kind == "movie" && item.genres.isNotBlank()) {
            Text(
                item.genres.split(",", "/", "|").map { it.trim() }.filter { it.isNotBlank() }.take(2).joinToString(", "),
                color = Accent.copy(alpha = .78f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Half-transparent resume bar pinned to the bottom of a poster.
@Composable
fun BoxScope.PosterProgressBar(fraction: Float) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(Color.Black.copy(alpha = .55f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                .clip(RoundedCornerShape(99.dp))
                .background(Accent),
        )
    }
}

@Composable
fun BoxScope.SeenBadge() {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Accent.copy(alpha = .95f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
            .background(Color.White.copy(alpha = .90f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
            .widthIn(max = TvDetailMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp)
            .clip(CardShape)
            .background(if (focused) Surface2 else SurfaceColor)
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, CardShape)
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
                    SizedAsyncImage(model = thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 150, authToken = session.token)
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
    val secondFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        delay(80)
        firstFocus.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .54f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(bottom = 48.dp)
                .width(410.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = .78f))
                .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WatchStateChip(if (watched) "Seen" else "Unseen", active = watched)
                WatchStateChip(if (watchlisted) "In watchlist" else "Not listed", active = watchlisted)
            }
            Spacer(Modifier.height(6.dp))
            WatchContextAction(
                label = if (watched) "Mark as unwatched" else "Mark as watched",
                primary = !watched,
                modifier = Modifier.focusRequester(firstFocus),
                onUp = { firstFocus.requestFocus(); true },
                onClick = if (watched) onMarkUnwatched else onMarkWatched,
            )
            WatchContextAction(
                label = if (watchlisted) "Remove from watchlist" else "Add to watchlist",
                primary = !watchlisted,
                modifier = Modifier.focusRequester(secondFocus),
                onClick = if (watchlisted) onRemoveWatchlist else onAddWatchlist,
            )
            WatchContextAction(
                "Cancel",
                primary = false,
                modifier = Modifier.focusRequester(cancelFocus),
                onDown = { cancelFocus.requestFocus(); true },
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun WatchStateChip(label: String, active: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Accent.copy(alpha = .24f) else Color.White.copy(alpha = .08f))
            .border(1.dp, if (active) Accent.copy(alpha = .45f) else Color.White.copy(alpha = .14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, color = if (active) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WatchContextAction(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused && primary -> Accent
        focused -> Color.White.copy(alpha = .16f)
        primary -> Accent.copy(alpha = .28f)
        else -> Color.White.copy(alpha = .08f)
    }
    val border = when {
        focused -> Color.White.copy(alpha = .78f)
        primary -> Accent.copy(alpha = .48f)
        else -> Color.White.copy(alpha = .14f)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
                    else -> false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (primary) "\u25CF" else "\u25CB", color = if (focused && primary) Color.Black else Accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Text(label, color = if (focused && primary) Color.Black else TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// Card container with scale-on-focus animation and white border highlight.
@Composable
fun CardShell(
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onRightEdge: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    focusScale: Float = 1.06f,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressReady by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val requester = focusRequester ?: remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "cardScale",
    )

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            requester.requestFocus()
        }
    }
    Column(
        modifier
            .zIndex(if (focused) 1f else 0f)
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
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && onRightEdge != null -> onRightEdge()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .border(2.dp, if (focused) Color.White.copy(alpha = .88f) else Color.White.copy(alpha = .10f), CardShape)
            .background(if (focused) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .055f))
            .padding(4.dp),
        content = content,
    )
}
