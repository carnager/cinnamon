package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Arrange home ──
//
// A screen of its own rather than editing home in place: the shelves being
// moved are poster rows, so a list of them scrolls, and a moving selection in a
// scrolling list is where the focus went missing. Here the running order is a
// compact rail that fits on screen, and the artwork lives in a preview beside
// it — so nothing moves out from under you, and the screen still looks like the
// rest of the app.

@Composable
fun ArrangeHomeView(
    session: Session?,
    draft: List<HomeLayoutSection>,
    catalog: List<HomeSectionType>,
    rendered: List<HomeSection>,
    focusedIndex: Int,
    grabbed: Boolean,
    onFocusIndex: (Int) -> Unit,
    onToggleGrab: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    onOptions: (Int) -> Unit,
    onOpenActions: (Int) -> Unit,
    onDone: () -> Unit,
) {
    val types = remember(catalog) { catalog.associateBy { it.type } }
    val byId = remember(rendered) { rendered.associateBy { it.id } }
    val railState = rememberLazyListState()
    val railFocus = remember { FocusRequester() }
    val current = draft.getOrNull(focusedIndex)

    // Focus brings its own row into view, so moving the selection needs no help
    // here — scrolling on every focus change pinned the selection to the top of
    // the rail. A held shelf is the exception: it travels without a focus
    // change, so nothing reveals it. Scroll then, and only if it left the
    // viewport, by the smallest amount that puts it back.
    LaunchedEffect(focusedIndex, grabbed) {
        if (!grabbed) return@LaunchedEffect
        val info = railState.layoutInfo
        val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
        val row = info.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
        when {
            row == null || row.offset < info.viewportStartOffset ->
                runCatching { railState.animateScrollToItem(focusedIndex) }
            row.offset + row.size > info.viewportEndOffset ->
                runCatching { railState.animateScrollToItem(focusedIndex, row.size - viewportHeight) }
        }
    }
    LaunchedEffect(Unit) {
        delay(140)
        requestArrangeFocus(railFocus)
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Surface2.copy(alpha = .35f), Bg, Bg))
            )
        )
        Column(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 26.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CinnamonBrand(markSize = 34, fontSize = 17)
                Text("Arrange home", color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text(
                    if (draft.size == 1) "1 shelf" else "${draft.size} shelves",
                    color = Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LazyColumn(
                    state = railState,
                    modifier = Modifier.width(430.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    itemsIndexed(draft, key = { _, section -> section.id }) { index, section ->
                        ArrangeRow(
                            position = index + 1,
                            label = sectionLabel(section, types[section.type]),
                            detail = rowDetail(section, byId[section.id]),
                            grabbed = grabbed && index == focusedIndex,
                            focusRequester = if (index == focusedIndex) railFocus else null,
                            onFocus = { onFocusIndex(index) },
                            onClick = { if (grabbed) onToggleGrab() else onOpenActions(index) },
                            onLongClick = { if (!grabbed) onToggleGrab() },
                            onUp = {
                                when {
                                    grabbed && index > 0 -> {
                                        onMove(index, index - 1)
                                        true
                                    }
                                    grabbed -> true
                                    else -> false
                                }
                            },
                            onDown = {
                                when {
                                    grabbed && index < draft.lastIndex -> {
                                        onMove(index, index + 1)
                                        true
                                    }
                                    grabbed -> true
                                    else -> false
                                }
                            },
                        )
                    }
                    item(key = "arrange-add") {
                        AddShelfRow("+", "Add a shelf", enabled = !grabbed, onClick = onAdd)
                    }
                    item(key = "arrange-done") {
                        // Done lives in the rail so the whole screen is one
                        // column of focus, rather than a panel you arrow into.
                        AddShelfRow("✓", "Done", enabled = !grabbed, onClick = onDone)
                    }
                }

                ArrangePreview(
                    session = session,
                    section = current,
                    definition = current?.let { types[it.type] },
                    content = current?.let { byId[it.id] },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (grabbed) {
                    "Moving — up/down to place it, OK to drop, Back to cancel"
                } else {
                    "OK for what you can do  •  hold OK to move a shelf  •  Back to save"
                },
                color = if (grabbed) Accent else Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ArrangeRow(
    position: Int,
    label: String,
    detail: String,
    grabbed: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressReady by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val border = when {
        grabbed -> Accent
        focused -> Accent.copy(alpha = .65f)
        else -> Color.White.copy(alpha = .07f)
    }
    val background = when {
        grabbed -> Accent.copy(alpha = .18f)
        focused -> Surface2
        else -> SurfaceColor.copy(alpha = .55f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(if (grabbed) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown -> onDown()
                    // Hold to pick a shelf up; a press opens what you can do
                    // with it. Long press means "more with this" everywhere
                    // else in the app, and moving is what you came here for.
                    it.type == KeyEventType.KeyDown && isActivationKey(it.key) -> {
                        if (longPressJob == null) {
                            longPressReady = false
                            longPressJob = scope.launch {
                                delay(600)
                                longPressReady = true
                                onLongClick()
                            }
                        }
                        true
                    }
                    it.type == KeyEventType.KeyUp && isActivationKey(it.key) -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (!longPressReady) onClick()
                        longPressReady = false
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "%d".format(position),
            color = if (grabbed || focused) Accent else Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(20.dp),
        )
        Text(
            label,
            color = TextColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (grabbed) Text("↕", color = Accent, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun AddShelfRow(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Accent.copy(alpha = .14f) else Color.Transparent)
            .border(1.dp, if (focused) Accent else Line, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate { if (enabled) onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(glyph, color = Accent, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(20.dp))
        Text(label, color = if (focused) TextColor else TextColor.copy(alpha = .85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArrangePreview(
    session: Session?,
    section: HomeLayoutSection?,
    definition: HomeSectionType?,
    content: HomeSection?,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceColor.copy(alpha = .5f))
            .border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        if (section == null) {
            Text("Nothing selected", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            return@Column
        }
        Text(
            sectionLabel(section, definition),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            definition?.description?.ifBlank { null } ?: definition?.label ?: section.type,
            color = TextColor.copy(alpha = .72f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val chips = sectionParamChips(section, definition)
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // One line of them: the buttons below must never be pushed off.
                chips.take(4).forEach { chip ->
                    Text(
                        chip,
                        color = Teal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Teal.copy(alpha = .35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            ArrangePosterStrip(session, content)
        }
    }
}

@Composable
private fun ArrangePosterStrip(session: Session?, content: HomeSection?) {
    val posters = remember(content) { arrangePosters(content) }
    if (posters.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2.copy(alpha = .45f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (content == null) "Appears on home once you are done" else "Nothing to show right now",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        itemsIndexed(posters) { _, poster ->
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Surface2),
            ) {
                if (session != null && poster.itemId > 0) {
                    SizedAsyncImage(
                        model = imageUrl(session, poster.itemId, "poster", poster.mtime, ArtworkCard),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        widthPx = 200,
                        heightPx = 300,
                        authToken = session.token,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrangeAction(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onLeft: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                when {
                    focused -> Accent
                    primary -> AccentDim
                    else -> Surface2
                }
            )
            .border(1.dp, if (focused) Color(0xFFFFA66A) else Color.Transparent, RoundedCornerShape(11.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.DirectionLeft && onLeft != null -> onLeft()
                    else -> false
                }
            }
            .tvActivate { if (enabled) onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// What you can do with one shelf, as a panel rather than buttons wedged under
// the artwork — the same right-hand sheet the checklists use, so there is one
// place to look and one way back.
@Composable
fun ArrangeActionsShelf(
    title: String,
    canConfigure: Boolean,
    onMove: () -> Unit,
    onOptions: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(title) {
        delay(60)
        requestArrangeFocus(firstFocus)
    }
    Popup(alignment = Alignment.CenterEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(400.dp)
                    .background(Bg.copy(alpha = .98f))
                    .border(1.dp, Color.White.copy(alpha = .10f))
                    .padding(horizontal = 26.dp, vertical = 38.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("SHELF", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(7.dp))
                Text(title, color = TextColor, fontSize = 21.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                ArrangeActionRow("Move", "Up and down to place it", firstFocus, onMove)
                if (canConfigure) {
                    ArrangeActionRow("Options", "Genre, length, sort, how many", null, onOptions)
                }
                ArrangeActionRow("Remove", "Take it off home", null, onRemove)
            }
        }
    }
}

@Composable
private fun ArrangeActionRow(label: String, detail: String, focusRequester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused) FocusGlow else Color.Transparent, RoundedCornerShape(9.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(label, color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = Muted, fontSize = 11.sp)
    }
}

private data class ArrangePoster(val itemId: Long, val mtime: Long)

private fun arrangePosters(content: HomeSection?): List<ArrangePoster> {
    if (content == null) return emptyList()
    val items = content.items.take(10).map { ArrangePoster(it.id, it.posterMtimeUnix) }
    if (items.isNotEmpty()) return items
    val shows = content.shows.take(10).map { ArrangePoster(it.posterItemId, it.posterMtimeUnix) }
    if (shows.isNotEmpty()) return shows
    return content.entries.take(10).mapNotNull { entry ->
        entry.item?.let { ArrangePoster(it.id, it.posterMtimeUnix) }
            ?: entry.show?.let { ArrangePoster(it.posterItemId, it.posterMtimeUnix) }
    }
}

private fun rowDetail(section: HomeLayoutSection, content: HomeSection?): String {
    if (content == null) return "new"
    val count = maxOf(content.items.size, content.shows.size, content.entries.size)
    return if (count > 0) "$count" else "empty"
}

// The catalog says what a shelf's parameters mean, so the chips read the same
// way whatever section types the server grows next.
fun sectionParamChips(section: HomeLayoutSection, definition: HomeSectionType?): List<String> {
    val params = definition?.params ?: return emptyList()
    return params.mapNotNull { param ->
        if (param.name == "genre") return@mapNotNull null
        val value = section.params[param.name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        when {
            param.suffix.isNotBlank() -> "$value ${param.suffix}"
            param.name == "kind" -> if (value == "tv") "TV shows" else "Movies"
            param.name == "seen" -> if (value == "unseen") "Unseen" else "Any"
            else -> value.replaceFirstChar { it.uppercase() }
        }
    }
}

fun sectionLabel(section: HomeLayoutSection, definition: HomeSectionType?): String {
    val genre = section.params["genre"].orEmpty()
    if (genre.isNotBlank()) return genre
    return section.title.ifBlank { definition?.label ?: section.type }
}

// Focus requests here always run through this: a requester whose row has
// scrolled out of composition throws, and a layout editor is not worth a crash.
private fun requestArrangeFocus(requester: FocusRequester): Boolean {
    return runCatching { requester.requestFocus() }.isSuccess
}
