package dev.popcorn.tv

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
import kotlinx.coroutines.delay

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
    onDone: () -> Unit,
) {
    val types = remember(catalog) { catalog.associateBy { it.type } }
    val byId = remember(rendered) { rendered.associateBy { it.id } }
    val railState = rememberLazyListState()
    val railFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val current = draft.getOrNull(focusedIndex)

    // The rail only scrolls for very long layouts, but when it does the focused
    // row has to come with it.
    LaunchedEffect(focusedIndex, draft.size) {
        runCatching { railState.animateScrollToItem(focusedIndex.coerceIn(0, maxOf(draft.size, 1))) }
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
                            onClick = onToggleGrab,
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
                            onRight = {
                                if (grabbed) true else requestArrangeFocus(actionFocus)
                            },
                        )
                    }
                    item(key = "arrange-add") {
                        AddShelfRow(enabled = !grabbed, onClick = onAdd)
                    }
                }

                ArrangePreview(
                    session = session,
                    section = current,
                    definition = current?.let { types[it.type] },
                    content = current?.let { byId[it.id] },
                    grabbed = grabbed,
                    actionFocus = actionFocus,
                    onBackToRail = { requestArrangeFocus(railFocus) },
                    onRemove = { onRemove(focusedIndex) },
                    onOptions = { onOptions(focusedIndex) },
                    onDone = onDone,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (grabbed) {
                    "Moving — up/down to place it, OK to drop, Back to cancel"
                } else {
                    "OK to pick a shelf up  •  ▶ for its options  •  Back to save and return"
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
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onRight: () -> Boolean,
) {
    var focused by remember { mutableStateOf(false) }
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
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.DirectionUp -> onUp()
                    it.key == Key.DirectionDown -> onDown()
                    it.key == Key.DirectionRight -> onRight()
                    else -> false
                }
            }
            .tvActivate(onClick)
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
private fun AddShelfRow(enabled: Boolean, onClick: () -> Unit) {
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
        Text("+", color = Accent, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(20.dp))
        Text("Add a shelf", color = if (focused) TextColor else TextColor.copy(alpha = .85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArrangePreview(
    session: Session?,
    section: HomeLayoutSection?,
    definition: HomeSectionType?,
    content: HomeSection?,
    grabbed: Boolean,
    actionFocus: FocusRequester,
    onBackToRail: () -> Boolean,
    onRemove: () -> Unit,
    onOptions: () -> Unit,
    onDone: () -> Unit,
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
            fontSize = 28.sp,
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
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { chip ->
                    Text(
                        chip,
                        color = Teal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Teal.copy(alpha = .35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ArrangePosterStrip(session, content)

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ArrangeAction(
                label = "Remove",
                focusRequester = actionFocus,
                enabled = !grabbed,
                onLeft = onBackToRail,
                onClick = onRemove,
            )
            if (definition?.params?.isNotEmpty() == true) {
                ArrangeAction(label = "Options", enabled = !grabbed, onLeft = onBackToRail, onClick = onOptions)
            }
            ArrangeAction(label = "Done", primary = true, enabled = !grabbed, onLeft = onBackToRail, onClick = onDone)
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
                .height(150.dp)
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
        modifier = Modifier.fillMaxWidth().height(190.dp),
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
    val order = definition?.params?.map { it.name } ?: section.params.keys.toList()
    return order.mapNotNull { name ->
        val value = section.params[name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (name == "genre") return@mapNotNull null
        when (name) {
            "limit" -> "$value items"
            "kind" -> if (value == "tv") "TV shows" else "Movies"
            "seen" -> if (value == "unseen") "Unseen" else "Any"
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
