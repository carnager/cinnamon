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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    autoFocusOnEntry: Boolean = false,
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
    // Alphabet jumps scroll the list already loaded and focus the first title
    // of the letter. They used to reload the library from the letter's
    // offset: titles before it vanished and every jump cost four requests.
    var jumpFocusIndex by remember { mutableStateOf<Int?>(null) }
    val gridScope = rememberCoroutineScope()
    // While moving along the alphabet rail: a large letter over the grid,
    // and a short glow on the letter's first title, which usually shares a
    // row with the end of the previous letter.
    var previewLetter by remember { mutableStateOf<String?>(null) }
    var highlightIndex by remember { mutableStateOf<Int?>(null) }
    var previewClearJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val alphabetIndex = remember(entries, alphabetTitle, alphabetEntries, onAlphabet) {
        // Once the whole list is loaded, letters are located in the grid
        // itself: the server's offsets came from its own ordering, and for TV
        // shows that differed from the grid's ("The 100" under T there, first
        // here), so jumps landed on the wrong titles. Until then, the server's.
        val serverTotal = alphabetEntries.sumOf { it.count }
        val gridIsComplete = alphabetEntries.isNotEmpty() && entries.size >= serverTotal
        if (alphabetEntries.isNotEmpty() && !(gridIsComplete && alphabetTitle != null)) {
            alphabetEntries.associateBy { it.letter }
        } else if (alphabetTitle == null || (onAlphabet != null && alphabetEntries.isEmpty())) {
            emptyMap()
        } else {
            val out = linkedMapOf<String, AlphabetEntry>()
            entries.forEachIndexed { index, item ->
                val letter = alphabetRailLetter(alphabetTitle.invoke(item), "")
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
            contentPadding = PaddingValues(start = 32.dp, end = if (alphabetIndex.isEmpty()) 32.dp else 74.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItemsIndexed(entries, key = { _, item -> key(item) }) { index, item ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = autoFocusOnEntry && initialFocusPending && targetKey != null && key(item) == targetKey
                val column = index % columns
                val firstRow = index < columns
                val rightEdge = column == columns - 1 || index == entries.lastIndex
                if (focusNow) {
                    LaunchedEffect(key(item)) {
                        delay(450)
                        initialFocusPending = false
                    }
                }
                if (index == jumpFocusIndex) {
                    LaunchedEffect(jumpFocusIndex) {
                        delay(60)
                        runCatching { focusRequester.requestFocus() }
                        jumpFocusIndex = null
                    }
                }
                Box {
                    content(item, focusNow, column, firstRow, rightEdge, focusRequester)
                    if (index == highlightIndex) {
                        Box(Modifier.matchParentSize().border(3.dp, Accent, CardShape))
                    }
                }
            }
        }
        if (alphabetIndex.isNotEmpty()) {
            AlphabetRail(
                index = alphabetIndex,
                // Moving along the letters scrolls the grid behind the rail;
                // focus stays on the rail until OK or Left.
                onLetter = { letter ->
                    previewLetter = letter
                    previewClearJob?.cancel()
                    previewClearJob = gridScope.launch {
                        delay(700)
                        previewLetter = null
                    }
                },
                onPreview = { offset ->
                    val target = offset.coerceAtMost(entries.lastIndex).coerceAtLeast(0)
                    gridScope.launch {
                        gridState.scrollToItem(target)
                        highlightIndex = target
                        delay(1_400)
                        if (highlightIndex == target) highlightIndex = null
                    }
                },
                onJump = { offset ->
                    // Background paging may still be filling the list.
                    val target = offset.coerceAtMost(entries.lastIndex).coerceAtLeast(0)
                    gridScope.launch {
                        gridState.scrollToItem(target)
                        jumpFocusIndex = target
                    }
                },
                focusRequester = alphabetFocusRequester,
                requestedFocusLetter = selectedAlphabetLetter,
                restoreFocus = restoreFocus,
                availableHeight = maxHeight,
                // Inside the TV overscan margin (was 10dp from the screen edge).
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 26.dp, top = 8.dp),
            )
        }
        previewLetter?.let { letter ->
            Text(
                letter,
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xD0080C12))
                    .padding(horizontal = 40.dp, vertical = 8.dp),
            )
        }
    }
}


@Composable
private fun AlphabetRail(
    index: Map<String, AlphabetEntry>,
    onLetter: (String) -> Unit,
    onPreview: (Int) -> Unit,
    onJump: (Int) -> Unit,
    focusRequester: FocusRequester? = null,
    requestedFocusLetter: String? = null,
    restoreFocus: (() -> Boolean)? = null,
    availableHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val letters = listOf("#") + ('A'..'Z').map { it.toString() }
    val firstEnabledLetter = letters.firstOrNull { index[it] != null }
    val focusLetter = requestedFocusLetter?.takeIf { index[it] != null } ?: firstEnabledLetter
    // Fit all 27 entries above the bottom overscan margin; the rail used to
    // run off the screen with "Z" cut off.
    val letterHeight = ((availableHeight - 6.dp - 44.dp) / letters.size).coerceIn(10.dp, 16.dp)
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Up/Down step to the next letter that has titles. Letters without any
    // can't take focus, and the general focus search then picked whatever
    // was nearest — often a grid card — so focus fell out of the rail where
    // letters were missing (and at the ends).
    val letterFocus = remember { letters.map { FocusRequester() } }
    fun stepLetter(from: Int, down: Boolean): Boolean {
        val range = if (down) (from + 1..letters.lastIndex) else (from - 1 downTo 0)
        val next = range.firstOrNull { index[letters[it]] != null } ?: return true
        runCatching { letterFocus[next].requestFocus() }
        return true
    }
    Column(
        modifier
            .width(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = .48f))
            .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(10.dp))
            .padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { letterIndex, letter ->
            var focused by remember { mutableStateOf(false) }
            val target = index[letter]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(letterHeight)
                    .then(if (letter == focusLetter && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .focusRequester(letterFocus[letterIndex])
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused && target != null) Accent else Color.Transparent)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused && target != null) {
                            onLetter(letter)
                            previewJob?.cancel()
                            previewJob = scope.launch {
                                delay(220)
                                onPreview(target.offset)
                            }
                        }
                    }
                    .focusable(enabled = target != null)
                    .onKeyEvent {
                        when {
                            it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown -> stepLetter(letterIndex, down = true)
                            it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp -> stepLetter(letterIndex, down = false)
                            it.type != KeyEventType.KeyDown || it.key != Key.DirectionLeft -> false
                            // Into the grid at this letter, where it now shows.
                            target != null -> {
                                previewJob?.cancel()
                                onJump(target.offset)
                                true
                            }
                            restoreFocus != null -> restoreFocus()
                            else -> false
                        }
                    }
                    .tvActivate {
                        if (target != null) onJump(target.offset)
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
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
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
            PosterCornerMarks(watched, watchlisted, show.rating, notInterested = notInterestedKey(show) in LocalNotInterested.current)
            val newCount = LocalNewEpisodes.current[newEpisodesKey(show)] ?: 0
            if (newCount > 0) {
                // Episodes added since you last watched this show.
                Text(
                    "$newCount NEW",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(show.title, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${show.seasonCount}S \u00b7 ${show.episodeCount}E", color = Muted, fontSize = 12.sp)
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
            PosterCornerMarks(watched, watchlisted, item.rating, notInterested = notInterestedKey(item)?.let { it in LocalNotInterested.current } == true)
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
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 12.sp)
        if (item.kind == "movie" && item.genres.isNotBlank()) {
            Text(
                item.genres.split(",", "/", "|").map { it.trim() }.filter { it.isNotBlank() }.take(2).joinToString(", "),
                color = Teal.copy(alpha = .86f),
                fontSize = 12.sp,
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

// PosterCornerMarks puts state and rating in the poster's corners: watchlist
// top left, seen bottom left, rating top right. A bar across the top was one
// solid stripe of artwork lost on every card. A mark takes only its corner and
// carries no backing at all — a dark halo around the glyph itself keeps it
// readable on white or busy artwork, where a scrim would have shown as a
// smudge. It sits bottom right, the one corner
// nothing else claims: stills carry their episode number bottom left and
// season posters their title there.
@Composable
fun BoxScope.PosterCornerMarks(watched: Boolean, watchlisted: Boolean, rating: Double = 0.0, notInterested: Boolean = false) {
    if (watchlisted) {
        // A ribbon off the top edge, carrying no glyph: the silhouette is the
        // bookmark, which is why it survives at any size and on any artwork
        // where a thin white outline would not. Cool against the seen wedge's
        // warm accent, so the card says colour temperature before it says
        // shape. Kept clear of the poster's corner radius so its top edge is
        // not nicked by the rounding.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp)
                .size(width = 18.dp, height = 28.dp)
                .clip(WatchlistRibbon)
                .background(Teal),
        )
    }
    if (notInterested) {
        // Not Interested takes the seen wedge's corner, in a cold grey that
        // reads as set aside rather than done. It replaces the seen wedge
        // because the choice is the stronger statement about the title.
        Box(Modifier.matchParentSize().clip(CardShape)) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(46.dp)
                    .clip(SeenWedge)
                    .background(NotInterestedGrey.copy(alpha = .9f)),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    Icons.Filled.Block,
                    "Not interested",
                    Modifier.padding(end = 7.dp, bottom = 7.dp).size(15.dp),
                    tint = Color.White,
                )
            }
        }
    } else if (watched) {
        // Seen is the one mark that says something happened, so it fills its
        // corner rather than sitting in it as a glyph: a wedge of accent is
        // found at a glance scanning a grid, where a check has to be looked
        // for. The wrapper carries the poster's own shape so the wedge's outer
        // corner is rounded with the artwork instead of poking past it.
        Box(Modifier.matchParentSize().clip(CardShape)) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(46.dp)
                    .clip(SeenWedge)
                    .background(Accent.copy(alpha = .82f)),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    Icons.Filled.Check,
                    "Seen",
                    Modifier.padding(end = 7.dp, bottom = 7.dp).size(15.dp),
                    tint = Color.White,
                )
            }
        }
    }
    if (rating > 0.0) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The star keeps the accent so a score still reads as a score at a
            // glance; the number stays white, since orange on pale artwork does
            // not.
            HaloIcon(Icons.Filled.Star, null, 12.dp, tint = Accent)
            Text(
                formatRating(rating),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 0f), blurRadius = 4f)),
            )
        }
    }
}

private val NotInterestedGrey = Color(0xFF55606A)

// A tab with a notch cut out of its bottom edge — a bookmark hanging from the
// top of the poster.
private val WatchlistRibbon = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.74f)
    lineTo(0f, size.height)
    close()
}

// A triangle filling the bottom right corner of its box.
private val SeenWedge = GenericShape { size, _ ->
    moveTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

// Compose has no drop-shadow filter for a vector glyph, so the halo is a
// slightly larger black copy drawn behind the real one.
@Composable
private fun HaloIcon(icon: ImageVector, description: String?, size: Dp, tint: Color = Color.White) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size + 3.dp), tint = Color.Black.copy(alpha = .62f))
        Icon(icon, description, Modifier.size(size), tint = tint)
    }
}

@Composable
private fun PosterStateBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    filled: Boolean = false,
) {
    Box(
        Modifier
            .size(20.dp)
            .then(
                if (filled) {
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(tint)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (filled) Bg else tint,
            modifier = Modifier.size(if (filled) 13.dp else 17.dp),
        )
    }
}

@Composable
fun WatchActionOverlay(
    title: String,
    watched: Boolean,
    watchlisted: Boolean?,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onAddWatchlist: (() -> Unit)?,
    onRemoveWatchlist: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val secondFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        delay(40)
        firstFocus.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .32f))
            .onKeyEvent {
                it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.DirectionRight)
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(356.dp)
                .background(Bg.copy(alpha = .98f))
                .border(1.dp, Color.White.copy(alpha = .10f))
                .padding(horizontal = 28.dp, vertical = 42.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(if (watchlisted == null) "SEASON OPTIONS" else "QUICK ACTIONS", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(7.dp))
            Text(title, color = TextColor, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(24.dp))
            WatchContextAction(
                label = if (watched) "Mark as unwatched" else "Mark as watched",
                icon = Icons.Filled.Check,
                active = watched,
                modifier = Modifier.focusRequester(firstFocus),
                onUp = {
                    (if (watchlisted == null) firstFocus else secondFocus).requestFocus()
                    true
                },
                onDown = {
                    (if (watchlisted == null) firstFocus else secondFocus).requestFocus()
                    true
                },
                onClick = if (watched) onMarkUnwatched else onMarkWatched,
            )
            if (watchlisted != null && onAddWatchlist != null && onRemoveWatchlist != null) {
                Spacer(Modifier.height(8.dp))
                WatchContextAction(
                    label = if (watchlisted) "Remove from watchlist" else "Add to watchlist",
                    icon = Icons.Filled.Bookmark,
                    active = watchlisted,
                    modifier = Modifier.focusRequester(secondFocus),
                    onUp = { firstFocus.requestFocus(); true },
                    onDown = { firstFocus.requestFocus(); true },
                    onClick = if (watchlisted) onRemoveWatchlist else onAddWatchlist,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text("Press Back to close", color = Muted.copy(alpha = .72f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun WatchContextAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) TextColor else if (active) Accent else Muted
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Accent.copy(alpha = .08f) else Color.Transparent)
            .border(1.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(12.dp))
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
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // Readable from the sofa: the old 1.025 scale and 1dp border were hard to
    // spot on a poster wall three metres away.
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
                    // MENU opens the quick actions too; a 650ms OK hold was the
                    // only, undiscoverable way in.
                    it.key == Key.Menu && onLongClick != null -> {
                        if (it.type == KeyEventType.KeyUp) onLongClick.invoke(requester)
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
            // No ripple or second focus target: keys are handled above.
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Accent else Color.White.copy(alpha = .12f), CardShape)
            .background(if (focused) Accent.copy(alpha = .12f) else Color.Black.copy(alpha = .10f))
            .padding(3.dp),
        content = content,
    )
}
