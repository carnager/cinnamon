package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private sealed interface SearchResult {
    data class Show(val show: ShowSummary) : SearchResult
    data class Movie(val item: PopItem) : SearchResult
}

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
    val searchFocus = remember { FocusRequester() }
    val combinedResults: List<SearchResult> = showResults.map { SearchResult.Show(it) } + movieResults.map { SearchResult.Movie(it) }
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        delay(150)
        searchFocus.requestFocus()
    }

    LaunchedEffect(query, session) {
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
            api.searchShows(q) to api.searchMovies(q)
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
            Modifier
                .fillMaxWidth()
                .background(SurfaceColor)
                .padding(horizontal = 28.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Pill("Back", selected = false, onClick = onBack)
            TvTextField(
                value = query,
                label = "Search movies and shows",
                modifier = Modifier.weight(1f).focusRequester(searchFocus),
                onChange = { query = it },
            )
        }

        when {
            query.trim().length < 2 -> EmptyState("Type at least 2 characters")
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            error.isNotBlank() -> EmptyState(error, error = true)
            combinedResults.isEmpty() -> EmptyState("No results for \"${query.trim()}\"")
            else -> {
                BrowserHeader("Search", "${combinedResults.size} results for \"${query.trim()}\"")
                PosterGrid(
                    entries = combinedResults,
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
