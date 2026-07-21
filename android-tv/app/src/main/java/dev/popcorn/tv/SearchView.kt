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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private sealed interface SearchResult {
    data class Show(val show: ShowSummary) : SearchResult
    data class Movie(val item: PopItem) : SearchResult
}

private data class SearchOptions(
    val scope: String = "both",
    val fields: List<String> = listOf("title"),
)

@Composable
fun SearchView(
    session: Session?,
    initialQuery: String,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var movieResults by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var showResults by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(SearchOptions()) }
    val searchFocus = remember { FocusRequester() }
    val fields = options.fields.sorted().joinToString(",")
    val combinedResults: List<SearchResult> =
        showResults.map { SearchResult.Show(it) } + movieResults.map { SearchResult.Movie(it) }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        delay(150)
        searchFocus.requestFocus()
    }

    LaunchedEffect(query, session, fields, options.scope) {
        onQueryChange(query)
        val active = session
        val q = query.trim()
        if (active == null || q.length < 2) {
            movieResults = emptyList()
            showResults = emptyList()
            error = ""
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(250)
        runCatching {
            val api = Api(active)
            val shows = if (options.scope != "movies") api.searchShows(q, fields) else emptyList()
            val movies = if (options.scope != "tv") api.searchMovies(q, fields) else emptyList()
            shows to movies
        }
            .onSuccess { (shows, movies) ->
                showResults = shows
                movieResults = movies
                error = ""
            }
            .onFailure {
                error = it.message ?: "Search failed"
                movieResults = emptyList()
                showResults = emptyList()
            }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CinnamonBrand(markSize = 34, fontSize = 19)
            Spacer(Modifier.width(18.dp))
            Text("Search", color = TextColor, fontSize = 25.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Pill("Back", selected = false, onClick = onBack)
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 62.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SearchInput(
                value = query,
                modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp).focusRequester(searchFocus),
                onChange = { query = it },
            )
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().widthIn(max = 1060.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SearchOptionRow("Scope") {
                    listOf("both" to "Movies & TV", "movies" to "Movies", "tv" to "TV shows").forEach { choice ->
                        SearchChoice(choice.second, active = options.scope == choice.first) {
                            options = options.copy(scope = choice.first)
                        }
                    }
                }
                SearchOptionRow("Search in") {
                    listOf(
                        "title" to "Title",
                        "original" to "Original title",
                        "people" to "People",
                        "description" to "Description",
                    ).forEach { choice ->
                        SearchChoice(choice.second, active = choice.first in options.fields) {
                            val selected = if (choice.first in options.fields) options.fields - choice.first else options.fields + choice.first
                            if (selected.isNotEmpty()) options = options.copy(fields = selected)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            query.trim().length < 2 -> SearchWelcome()
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            error.isNotBlank() -> EmptyState(error, error = true)
            combinedResults.isEmpty() -> SearchEmpty("Nothing found for \"${query.trim()}\"")
            else -> {
                BrowserHeader("Results", "${combinedResults.size} for \"${query.trim()}\"")
                PosterGrid(
                    entries = combinedResults,
                    autoFocusOnEntry = false,
                    key = {
                        when (it) {
                            is SearchResult.Show -> "show:${it.show.libraryId}:${it.show.title}"
                            is SearchResult.Movie -> "movie:${it.item.id}"
                        }
                    },
                ) { result, _, _, _, _, focusRequester ->
                    when (result) {
                        is SearchResult.Show -> ShowCard(
                            session,
                            result.show,
                            watched = completedShows.contains("${result.show.libraryId}\n${result.show.title.lowercase()}"),
                            watchlisted = watchlistShows.contains("${result.show.libraryId}\n${result.show.title.lowercase()}"),
                            autoFocus = false,
                            focusRequester = focusRequester,
                            onClick = { onShow(result.show) },
                            onLongClick = { requester -> onShowMenu(result.show, requester) },
                        )
                        is SearchResult.Movie -> ItemCard(
                            session,
                            result.item,
                            watched = completedItems.contains(result.item.id),
                            watchlisted = watchlistItems.contains(result.item.id),
                            autoFocus = false,
                            focusRequester = focusRequester,
                            onClick = { onItem(result.item) },
                            onLongClick = { requester -> onItemMenu(result.item, requester) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInput(value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = if (focused) Accent else Muted) },
        placeholder = { Text("Search movies and TV shows", color = Muted) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextColor,
            unfocusedTextColor = TextColor,
            cursorColor = Accent,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SearchOptionRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(72.dp))
        content()
    }
}

@Composable
private fun SearchChoice(label: String, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = if (active) Teal else Color.Transparent,
            modifier = Modifier.size(14.dp),
        )
        Text(label, color = if (active || focused) TextColor else Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SearchWelcome() {
    Column(
        Modifier.fillMaxSize().padding(bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Teal.copy(alpha = .75f), modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(14.dp))
        Text("Search your library", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Titles are searched by default. Add other fields only when you need them.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SearchEmpty(message: String) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text("Try another phrase or include another search field.", color = Muted, fontSize = 12.sp)
    }
}
