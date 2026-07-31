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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.delay

// ── Home layout editing on the TV ──
//
// Edit mode keeps home looking like home: the shelves stay where they are, each
// one gains a focusable header, and OK picks a shelf up so up/down move it.
// Adding and removing happens in a checklist, because a D-pad has nowhere good
// to put a per-shelf delete button.

@Composable
fun EditableHome(
    session: Session?,
    draft: List<HomeLayoutSection>,
    catalog: List<HomeSectionType>,
    rendered: List<HomeSection>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    onMove: (Int, Int) -> Unit,
    onLeftEdge: (() -> Boolean)? = null,
) {
    val byId = remember(rendered) { rendered.associateBy { it.id } }
    val types = remember(catalog) { catalog.associateBy { it.type } }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }

    if (draft.isEmpty()) {
        EmptyState("No shelves. Press Shelves to add some.")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(draft, key = { _, section -> section.id.ifBlank { section.type } }) { index, section ->
            EditableShelf(
                session = session,
                section = section,
                definition = types[section.type],
                content = byId[section.id],
                selected = selectedIndex == index,
                focusRequester = if (index == 0) firstFocus else null,
                onClick = { onSelect(if (selectedIndex == index) null else index) },
                onUp = {
                    if (selectedIndex == index && index > 0) {
                        onMove(index, index - 1)
                        true
                    } else {
                        false
                    }
                },
                onDown = {
                    if (selectedIndex == index && index < draft.lastIndex) {
                        onMove(index, index + 1)
                        true
                    } else {
                        false
                    }
                },
                onLeftEdge = onLeftEdge,
            )
        }
    }
}

@Composable
private fun EditableShelf(
    session: Session?,
    section: HomeLayoutSection,
    definition: HomeSectionType?,
    content: HomeSection?,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onUp: () -> Boolean,
    onDown: () -> Boolean,
    onLeftEdge: (() -> Boolean)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val border = when {
        selected -> Accent
        focused -> Accent.copy(alpha = .55f)
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = .10f) else Color.Transparent)
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.DirectionUp -> onUp()
                    it.key == Key.DirectionDown -> onDown()
                    it.key == Key.DirectionLeft && onLeftEdge != null -> onLeftEdge()
                    else -> false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                content?.title?.ifBlank { null } ?: sectionLabel(section, definition),
                color = if (selected || focused) TextColor else TextColor.copy(alpha = .85f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val hint = when {
                selected -> "Up/down to move  •  OK to drop"
                content == null -> "Appears once you leave edit mode"
                else -> "${contentCount(content)} items"
            }
            Text(hint, color = if (selected) Accent else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        ShelfPreview(session, content)
    }
}

// A non-focusable strip of what the shelf holds: in edit mode the D-pad belongs
// to the shelves themselves, never to the cards inside them.
@Composable
private fun ShelfPreview(session: Session?, content: HomeSection?) {
    val posters = remember(content) { previewPosters(content) }
    if (posters.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2.copy(alpha = .5f)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Empty for now", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(posters) { _, poster ->
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Surface2),
            ) {
                if (session != null && poster.itemId > 0) {
                    SizedAsyncImage(
                        model = imageUrl(session, poster.itemId, poster.kind, poster.mtime, ArtworkCard),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        widthPx = 120,
                        heightPx = 180,
                        authToken = session.token,
                    )
                }
            }
        }
    }
}

private data class PreviewPoster(val itemId: Long, val kind: String, val mtime: Long)

private fun previewPosters(content: HomeSection?): List<PreviewPoster> {
    if (content == null) return emptyList()
    val fromItems = content.items.take(12).map { PreviewPoster(it.id, "poster", it.posterMtimeUnix) }
    if (fromItems.isNotEmpty()) return fromItems
    val fromShows = content.shows.take(12).map { PreviewPoster(it.posterItemId, "poster", it.posterMtimeUnix) }
    if (fromShows.isNotEmpty()) return fromShows
    return content.entries.take(12).mapNotNull { entry ->
        entry.item?.let { PreviewPoster(it.id, "poster", it.posterMtimeUnix) }
            ?: entry.show?.let { PreviewPoster(it.posterItemId, "poster", it.posterMtimeUnix) }
    }
}

private fun contentCount(content: HomeSection): Int =
    maxOf(content.items.size, content.shows.size, content.entries.size)

fun sectionLabel(section: HomeLayoutSection, definition: HomeSectionType?): String {
    val genre = section.params["genre"].orEmpty()
    if (genre.isNotBlank()) return genre
    return section.title.ifBlank { definition?.label ?: section.type }
}

// ── Add and remove ──

data class TvCheckRow(
    val key: String,
    val label: String,
    val description: String = "",
    val checked: Boolean,
    val onToggle: () -> Unit,
)

@Composable
fun TvCheckListShelf(
    title: String,
    subtitle: String,
    rows: List<TvCheckRow>,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(title) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
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
            ) {
                Text(title.uppercase(), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(7.dp))
                Text(subtitle, color = TextColor, fontSize = 21.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(16.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        TvCheckRowItem(
                            row = row,
                            focusRequester = if (index == 0) firstFocus else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvCheckRowItem(row: TvCheckRow, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused) FocusGlow else Color.Transparent, RoundedCornerShape(7.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .tvActivate(row.onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (row.checked) Accent else Color.Transparent)
                .border(1.dp, if (row.checked) Accent else Muted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (row.checked) Text("✓", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.label,
                color = if (row.checked || focused) TextColor else TextColor.copy(alpha = .8f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.description.isNotBlank()) {
                Text(row.description, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Draft edits ──

fun moveSection(sections: List<HomeLayoutSection>, from: Int, to: Int): List<HomeLayoutSection> {
    if (from !in sections.indices || to !in sections.indices || from == to) return sections
    val out = sections.toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

fun toggleSectionType(sections: List<HomeLayoutSection>, type: HomeSectionType): List<HomeLayoutSection> {
    if (sections.any { it.type == type.type }) {
        return sections.filterNot { it.type == type.type }
    }
    val params = type.params.filter { it.default.isNotBlank() }.associate { it.name to it.default }
    return sections + HomeLayoutSection(id = newSectionId(sections, type.type), type = type.type, params = params)
}

// A genre shelf per checked genre, keyed by genre and media kind so Horror
// movies and Horror shows can both be on home.
fun toggleGenreSection(
    sections: List<HomeLayoutSection>,
    type: HomeSectionType,
    genre: String,
    kind: String,
): List<HomeLayoutSection> {
    val existing = sections.indexOfFirst { it.type == type.type && it.params["genre"] == genre && genreKind(it) == kind }
    if (existing >= 0) return sections.filterIndexed { index, _ -> index != existing }
    val params = buildMap {
        type.params.filter { it.default.isNotBlank() }.forEach { put(it.name, it.default) }
        put("genre", genre)
        put("kind", kind)
    }
    return sections + HomeLayoutSection(id = newSectionId(sections, type.type), type = type.type, params = params)
}

fun genreKind(section: HomeLayoutSection): String = section.params["kind"] ?: "movies"

private fun newSectionId(sections: List<HomeLayoutSection>, type: String): String {
    var index = sections.count { it.type == type } + 1
    while (sections.any { it.id == "$type-$index" }) index += 1
    return "$type-$index"
}
