package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

// The pieces the arrange screen shares: a checklist popup and the edits it
// makes to the layout draft.

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

// LazyColumn keys have to be unique or it throws, and a layout can arrive with
// blank or repeated ids. Normalise once, when the draft is loaded.
fun withUniqueIds(sections: List<HomeLayoutSection>): List<HomeLayoutSection> {
    val seen = mutableSetOf<String>()
    return sections.map { section ->
        var id = section.id.ifBlank { section.type }
        var suffix = 2
        while (!seen.add(id)) {
            id = "${section.id.ifBlank { section.type }}-$suffix"
            suffix += 1
        }
        section.copy(id = id)
    }
}

// ── Per-shelf options ──

data class TvOptionRow(
    val key: String,
    val label: String,
    val value: String,
    val onCycle: () -> Unit,
)

// One row per parameter the catalog declares, OK steps to the next value. A
// D-pad has no room for pickers, and every parameter here is a short list.
@Composable
fun TvOptionsShelf(
    title: String,
    subtitle: String,
    rows: List<TvOptionRow>,
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
                        TvOptionRowItem(row, if (index == 0) firstFocus else null)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("OK steps through the values", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TvOptionRowItem(row: TvOptionRow, focusRequester: FocusRequester?) {
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
            .tvActivate(row.onCycle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(row.label, color = TextColor.copy(alpha = .85f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(row.value, color = if (focused) Accent else TextColor, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text("›", color = if (focused) Accent else Muted, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

// The values a parameter can step through come from the catalog: enums list
// their options, numbers list their choices. Nothing here knows what a
// particular parameter means.
fun paramChoices(param: HomeSectionParam): List<String> = when {
    param.choices.isNotEmpty() -> param.choices
    param.type == "enum" -> param.options
    else -> emptyList()
}

fun cycleParam(section: HomeLayoutSection, param: HomeSectionParam): HomeLayoutSection {
    val choices = paramChoices(param)
    if (choices.isEmpty()) return section
    val current = section.params[param.name] ?: param.default
    val next = choices[(choices.indexOf(current).takeIf { it >= 0 }?.plus(1) ?: 0) % choices.size]
    val params = if (next.isBlank()) section.params - param.name else section.params + (param.name to next)
    return section.copy(params = params)
}

// "90 min", "7.5 and up", "24 items" — or "Any" when the parameter is unset.
fun paramValueLabel(param: HomeSectionParam, value: String): String {
    if (value.isBlank()) return "Any"
    if (param.suffix.isBlank()) return value
    return "$value ${param.suffix}"
}

fun updateSection(
    sections: List<HomeLayoutSection>,
    index: Int,
    transform: (HomeLayoutSection) -> HomeLayoutSection,
): List<HomeLayoutSection> {
    if (index !in sections.indices) return sections
    return sections.mapIndexed { position, section -> if (position == index) transform(section) else section }
}
