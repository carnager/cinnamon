package dev.popcorn.tv

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (PlayerOsdBridge.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = Bg) {
                    PopcornApp()
                }
            }
        }
    }
}

private data class WatchMenuState(
    val title: String,
    val watched: Boolean,
    val watchlisted: Boolean,
    val onMarkWatched: () -> Unit,
    val onMarkUnwatched: () -> Unit,
    val onAddWatchlist: () -> Unit,
    val onRemoveWatchlist: () -> Unit,
    val restoreFocus: (() -> Unit)? = null,
)

@Composable
fun PopcornApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("popcorn", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var session by remember {
        mutableStateOf(
            prefs.getString("token", null)?.let {
                Session(
                    prefs.getString("server", "http://10.0.2.2:8097") ?: "http://10.0.2.2:8097",
                    it,
                    prefs.getString("username", "") ?: "",
                )
            }
        )
    }
    var screen by remember { mutableStateOf<Screen>(if (session == null) Screen.Login else Screen.Loading) }
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var activeLibrary by remember { mutableStateOf<Library?>(null) }
    var items by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var shows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var homeMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var homeShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var recentMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var recentShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var completedItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var completedShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var watchlistItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchlistShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var watchlistMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var watchlistTvShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var loadGeneration by remember { mutableStateOf(0) }
    var libraryFullyLoaded by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableStateOf(0) }
    var pageHasNext by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf("") }
    var libraryGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var watchMenu by remember { mutableStateOf<WatchMenuState?>(null) }
    var deviceId by remember { mutableStateOf(prefs.getString("remoteDeviceId", "") ?: "") }
    var lastRemoteCommandId by remember { mutableStateOf(prefs.getLong("remoteCommandId", 0L)) }
    var pendingPlayerCommand by remember { mutableStateOf<PlayerRemoteCommand?>(null) }
    var lastDetail by remember { mutableStateOf<Screen.Detail?>(null) }

    fun showKey(show: ShowSummary): String = "${show.libraryId}\n${show.title.lowercase()}"

    fun refreshProgress(activeSession: Session) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                val progress = api.progressList()
                val showProgress = api.showProgress()
                completedItems = progress.filter { it.completed }.map { it.itemId }.toSet()
                completedShows = showProgress.filter { it.completed }.map { "${it.libraryId}\n${it.showTitle.lowercase()}" }.toSet()
            }
        }
    }

    fun refreshWatchlist(activeSession: Session) {
        scope.launch {
            runCatching {
                val list = Api(activeSession).watchlist()
                watchlistMovies = list.items.filter { it.kind == "movie" }
                watchlistTvShows = list.shows
                watchlistItems = list.items.map { it.id }.toSet()
                watchlistShows = list.shows.map { showKey(it) }.toSet()
            }
        }
    }

    fun setItemWatched(activeSession: Session, item: PopItem, watched: Boolean) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                if (watched) {
                    api.markItemWatched(item)
                } else {
                    api.unmarkItemWatched(item.id)
                }
            }.onSuccess {
                completedItems = if (watched) completedItems + item.id else completedItems - item.id
                refreshProgress(activeSession)
            }.onFailure {
                error = it.message ?: "Failed to update watched state"
            }
        }
    }

    fun setShowWatched(activeSession: Session, show: ShowSummary, watched: Boolean) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                if (watched) {
                    api.markShowWatched(show.libraryId, show.title)
                } else {
                    api.unmarkShowWatched(show.libraryId, show.title)
                }
            }.onSuccess {
                val key = showKey(show)
                completedShows = if (watched) completedShows + key else completedShows - key
                refreshProgress(activeSession)
            }.onFailure {
                error = it.message ?: "Failed to update watched state"
            }
        }
    }

    fun setItemWatchlisted(activeSession: Session, item: PopItem, listed: Boolean) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                if (listed) {
                    api.addItemWatchlist(item.id)
                } else {
                    api.removeItemWatchlist(item.id)
                }
            }.onSuccess {
                watchlistItems = if (listed) watchlistItems + item.id else watchlistItems - item.id
                if (listed && item.kind == "movie") {
                    watchlistMovies = (listOf(item) + watchlistMovies).distinctBy { it.id }
                } else {
                    watchlistMovies = watchlistMovies.filterNot { it.id == item.id }
                }
                refreshWatchlist(activeSession)
            }.onFailure {
                error = it.message ?: "Failed to update watchlist"
            }
        }
    }

    fun setShowWatchlisted(activeSession: Session, show: ShowSummary, listed: Boolean) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                if (listed) {
                    api.addShowWatchlist(show.libraryId, show.title)
                } else {
                    api.removeShowWatchlist(show.libraryId, show.title)
                }
            }.onSuccess {
                val key = showKey(show)
                watchlistShows = if (listed) watchlistShows + key else watchlistShows - key
                watchlistTvShows = if (listed) (listOf(show) + watchlistTvShows).distinctBy { showKey(it) } else watchlistTvShows.filterNot { showKey(it) == key }
                refreshWatchlist(activeSession)
            }.onFailure {
                error = it.message ?: "Failed to update watchlist"
            }
        }
    }

    fun handleRemoteCommand(activeSession: Session, command: RemoteCommand) {
        scope.launch {
            when (command.type) {
                "playItem" -> {
                    val itemId = command.payload.optLong("itemId")
                    if (itemId > 0) {
                        runCatching { Api(activeSession).item(itemId) }
                            .onSuccess { item ->
                                lastDetail = Screen.Detail(item, null)
                                val audio = command.payload.optIntOrNull("audioIndex")
                                val subtitle = command.payload.optIntOrNull("subtitleIndex")
                                screen = Screen.Player(item, audio, subtitle)
                            }
                            .onFailure { error = it.message ?: "Remote play failed" }
                    }
                }
                "pause", "resume", "seek", "stop", "bandwidth" -> pendingPlayerCommand = PlayerRemoteCommand(command.id, command.type, command.payload)
            }
        }
    }

    fun closeWatchMenu(menu: WatchMenuState? = watchMenu) {
        watchMenu = null
        menu?.restoreFocus?.let { restore ->
            scope.launch {
                delay(90)
                runCatching { restore() }
            }
        }
    }

    fun openItemWatchMenu(item: PopItem, focusRequester: FocusRequester?) {
        val active = session ?: return
        val watched = completedItems.contains(item.id)
        val listed = watchlistItems.contains(item.id)
        watchMenu = WatchMenuState(
            title = item.episodeTitle.ifBlank { item.title },
            watched = watched,
            watchlisted = listed,
            onMarkWatched = { setItemWatched(active, item, true) },
            onMarkUnwatched = { setItemWatched(active, item, false) },
            onAddWatchlist = { setItemWatchlisted(active, item, true) },
            onRemoveWatchlist = { setItemWatchlisted(active, item, false) },
            restoreFocus = { focusRequester?.requestFocus() },
        )
    }

    fun openShowWatchMenu(show: ShowSummary, focusRequester: FocusRequester?) {
        val active = session ?: return
        val watched = completedShows.contains(showKey(show))
        val listed = watchlistShows.contains(showKey(show))
        watchMenu = WatchMenuState(
            title = show.title,
            watched = watched,
            watchlisted = listed,
            onMarkWatched = { setShowWatched(active, show, true) },
            onMarkUnwatched = { setShowWatched(active, show, false) },
            onAddWatchlist = { setShowWatchlisted(active, show, true) },
            onRemoveWatchlist = { setShowWatchlisted(active, show, false) },
            restoreFocus = { focusRequester?.requestFocus() },
        )
    }

    DisposableEffect(session) {
        val activeSession = session
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        if (activeSession == null || lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshProgress(activeSession)
                    refreshWatchlist(activeSession)
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(session, deviceId) {
        val active = session ?: return@LaunchedEffect
        val api = Api(active)
        val registered = runCatching {
            api.registerDevice(deviceId.ifBlank { null }, "Shield ${Build.MODEL}".trim())
        }.getOrNull()
        if (registered != null && registered.isNotBlank() && registered != deviceId) {
            deviceId = registered
            prefs.edit().putString("remoteDeviceId", registered).apply()
        }
        val currentDevice = registered ?: deviceId
        if (currentDevice.isBlank()) return@LaunchedEffect
        while (true) {
            delay(700)
            runCatching { api.remoteCommands(currentDevice, lastRemoteCommandId) }
                .onSuccess { commands ->
                    for (command in commands) {
                        if (command.id > lastRemoteCommandId) {
                            lastRemoteCommandId = command.id
                            prefs.edit().putLong("remoteCommandId", lastRemoteCommandId).apply()
                        }
                        handleRemoteCommand(active, command)
                    }
                }
        }
    }

    fun loadHome(activeSession: Session, libs: List<Library>) {
        loadGeneration += 1
        val generation = loadGeneration
        scope.launch {
            loading = true
            error = ""
            screen = Screen.Home
            val movieLib = libs.firstOrNull { it.type == "movies" }
            val tvLib = libs.firstOrNull { it.type == "tv" }
            runCatching {
                refreshProgress(activeSession)
                refreshWatchlist(activeSession)
                val api = Api(activeSession)
                if (movieLib != null) {
                    homeMovies = api.itemsPage(movieLib.id, 150, 0)
                    recentMovies = api.recentItems(movieLib.id, 24)
                    AppCache.writeItems(context, activeSession, movieLib.id, homeMovies, false)
                }
                if (tvLib != null) {
                    homeShows = api.showsPage(tvLib.id, 150, 0)
                    recentShows = api.recentShows(tvLib.id, 24)
                    AppCache.writeShows(context, activeSession, tvLib.id, homeShows, false)
                }
            }.onFailure { error = it.message ?: "Load failed" }
            if (generation == loadGeneration) loading = false
        }
    }

    fun loadLibraryPage(library: Library, activeSession: Session, page: Int = 0, genre: String = selectedGenre) {
        loadGeneration += 1
        val generation = loadGeneration
        scope.launch {
            error = ""
            loading = true
            val previousLibraryID = activeLibrary?.id
            activeLibrary = library
            items = emptyList()
            shows = emptyList()
            val activeGenre = if (page == 0 && library.id != previousLibraryID) {
                ""
            } else {
                genre
            }
            selectedGenre = activeGenre
            pageIndex = page.coerceAtLeast(0)
            libraryFullyLoaded = false
            loadingMore = false
            screen = Screen.LibraryPage(library)
            runCatching {
                refreshProgress(activeSession)
                refreshWatchlist(activeSession)
                val api = Api(activeSession)
                val pageSize = 50
                val offset = pageIndex * pageSize
                libraryGenres = api.genres(library.id)
                if (library.type == "tv") {
                    val pageItems = api.showsPage(library.id, pageSize, offset, activeGenre)
                    if (generation != loadGeneration) return@launch
                    shows = pageItems
                    pageHasNext = pageItems.size == pageSize
                    libraryFullyLoaded = pageItems.size < pageSize
                } else {
                    val pageItems = api.itemsPage(library.id, pageSize, offset, activeGenre)
                    if (generation != loadGeneration) return@launch
                    items = pageItems
                    pageHasNext = pageItems.size == pageSize
                    libraryFullyLoaded = pageItems.size < pageSize
                }
            }.onFailure { error = it.message ?: "Load failed" }
            loading = false
        }
    }

    fun loadRemainingLibrary() {
        val library = activeLibrary ?: return
        val activeSession = session ?: return
        if (libraryFullyLoaded || loadingMore) return
        val generation = loadGeneration
        scope.launch {
            loadingMore = true
            runCatching {
                val api = Api(activeSession)
                val pageSize = 500
                var offset = if (library.type == "tv") shows.size else items.size
                while (true) {
                    if (library.type == "tv") {
                        val page = api.showsPage(library.id, pageSize, offset)
                        if (generation != loadGeneration) return@launch
                        shows = shows + page
                        AppCache.writeShows(context, activeSession, library.id, shows, page.size < pageSize)
                        if (page.size < pageSize) break
                        offset += pageSize
                    } else {
                        val page = api.itemsPage(library.id, pageSize, offset)
                        if (generation != loadGeneration) return@launch
                        items = items + page
                        AppCache.writeItems(context, activeSession, library.id, items, page.size < pageSize)
                        if (page.size < pageSize) break
                        offset += pageSize
                    }
                    delay(32)
                }
                libraryFullyLoaded = true
            }.onFailure { error = it.message ?: "Load failed" }
            loadingMore = false
        }
    }

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        loading = true
        val cachedLibraries = withContext(Dispatchers.IO) { AppCache.readLibraries(context, active) }
        if (cachedLibraries.isNotEmpty()) {
            libraries = cachedLibraries
            homeMovies = cachedLibraries.firstOrNull { it.type == "movies" }?.let { AppCache.readItems(context, active, it.id).entries } ?: emptyList()
            homeShows = cachedLibraries.firstOrNull { it.type == "tv" }?.let { AppCache.readShows(context, active, it.id).entries } ?: emptyList()
            screen = Screen.Home
            loading = false
        }
        runCatching {
            val api = Api(active)
            val me = api.me()
            if (active.username != me.username) {
                val updated = active.copy(username = me.username)
                prefs.edit().putString("username", me.username).apply()
                session = updated
            }
            libraries = api.libraries()
            AppCache.writeLibraries(context, active, libraries)
            refreshProgress(active)
            refreshWatchlist(active)
            loadHome(active, libraries)
        }.onFailure {
            error = it.message ?: "Server unavailable"
            screen = Screen.Login
            loading = false
        }
    }

    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Login) {
        when (val s = screen) {
            Screen.Watchlist -> screen = Screen.Home
            is Screen.LibraryPage -> screen = Screen.Home
            Screen.Search -> screen = Screen.Home
            is Screen.Show -> screen = if (s.fromSearch) Screen.Search else if (s.fromWatchlist) Screen.Watchlist else if (activeLibrary?.type == "tv") Screen.LibraryPage(activeLibrary!!) else Screen.Home
            is Screen.Season -> screen = Screen.Show(s.show, fromSearch = s.fromSearch, fromWatchlist = s.fromWatchlist)
            is Screen.Detail -> {
                lastDetail = null
                screen = if (s.fromSearch) Screen.Search else if (s.fromShow != null) Screen.Show(s.fromShow, fromWatchlist = s.fromWatchlist) else if (s.fromWatchlist) Screen.Watchlist else if (activeLibrary != null) Screen.LibraryPage(activeLibrary!!) else Screen.Home
            }
            is Screen.Player -> {
                screen = if (lastDetail != null) lastDetail!! else Screen.Home
                session?.let { active -> refreshProgress(active) }
            }
            else -> screen = Screen.Home
        }
    }

    when (val current = screen) {
        Screen.Loading -> LoadingView(error)
        Screen.Login -> LoginView(
            initialServer = prefs.getString("server", "http://10.0.2.2:8097") ?: "http://10.0.2.2:8097",
            error = error,
            onLogin = { server, username, password ->
                scope.launch {
                    error = ""
                    loading = true
                    runCatching { Api(Session(server.trimEnd('/'), "")).login(username, password) }
                        .onSuccess {
                            prefs.edit().putString("server", it.server).putString("token", it.token).putString("username", it.username).apply()
                            session = it
                            screen = Screen.Loading
                        }
                        .onFailure { error = it.message ?: "Login failed" }
                    loading = false
                }
            },
            onQrLogin = {
                prefs.edit().putString("server", it.server).putString("token", it.token).putString("username", it.username).apply()
                session = it
                screen = Screen.Loading
            },
        )
        Screen.Home -> HomeView(
            session = session,
            libraries = libraries,
            items = homeMovies,
            shows = homeShows,
            completedItems = completedItems,
            completedShows = completedShows,
            watchlistItems = watchlistItems,
            watchlistShows = watchlistShows,
            recentMovies = recentMovies,
            recentShows = recentShows,
            watchlistMovies = watchlistMovies,
            watchlistTvShows = watchlistTvShows,
            error = error,
            loading = loading,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onSearch = { screen = Screen.Search },
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onItem = { screen = Screen.Detail(it, null, fromWatchlist = true) },
            onShow = { screen = Screen.Show(it, fromWatchlist = true) },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
            onShowMenu = { show, requester -> openShowWatchMenu(show, requester) },
        )
        Screen.Watchlist -> WatchlistView(
            session = session,
            libraries = libraries,
            movies = watchlistMovies,
            shows = watchlistTvShows,
            completedItems = completedItems,
            completedShows = completedShows,
            watchlistItems = watchlistItems,
            watchlistShows = watchlistShows,
            error = error,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onSearch = { screen = Screen.Search },
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onItem = { screen = Screen.Detail(it, null) },
            onShow = { screen = Screen.Show(it) },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
            onShowMenu = { show, requester -> openShowWatchMenu(show, requester) },
        )
        is Screen.LibraryPage -> LibraryPageView(
            session = session,
            libraries = libraries,
            activeLibrary = current.library,
            items = items,
            shows = shows,
            completedItems = completedItems,
            completedShows = completedShows,
            watchlistItems = watchlistItems,
            watchlistShows = watchlistShows,
            error = error,
            loading = loading,
            pageIndex = pageIndex,
            pageHasNext = pageHasNext,
            selectedGenre = selectedGenre,
            genres = libraryGenres,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onSearch = { screen = Screen.Search },
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onPreviousPage = { session?.let { loadLibraryPage(current.library, it, pageIndex - 1) } },
            onNextPage = { session?.let { loadLibraryPage(current.library, it, pageIndex + 1) } },
            onGenre = { genre -> session?.let { loadLibraryPage(current.library, it, 0, genre) } },
            onItem = { screen = Screen.Detail(it, null) },
            onShow = { screen = Screen.Show(it) },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
            onShowMenu = { show, requester -> openShowWatchMenu(show, requester) },
        )
        Screen.Search -> SearchView(
            session = session,
            initialQuery = searchQuery,
            completedItems = completedItems,
            completedShows = completedShows,
            watchlistItems = watchlistItems,
            watchlistShows = watchlistShows,
            onQueryChange = { searchQuery = it },
            onBack = { screen = Screen.Home },
            onItem = { screen = Screen.Detail(it, null, fromSearch = true) },
            onShow = { screen = Screen.Show(it, fromSearch = true) },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
            onShowMenu = { show, requester -> openShowWatchMenu(show, requester) },
        )
        is Screen.Show -> ShowView(
            session = session,
            show = current.show,
            onSeason = { season -> screen = Screen.Season(current.show, season, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist) },
        )
        is Screen.Season -> SeasonView(
            session = session,
            show = current.show,
            season = current.season,
            onEpisode = { screen = Screen.Detail(it, current.show, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist) },
        )
        is Screen.Detail -> DetailView(
            item = current.item,
            session = session,
            onPlay = { audioIndex, subtitleIndex ->
                lastDetail = current
                screen = Screen.Player(current.item, audioIndex, subtitleIndex)
            },
        )
        is Screen.Player -> PlayerScreen(
            item = current.item,
            session = session,
            deviceId = deviceId,
            remoteCommand = pendingPlayerCommand,
            initialAudioIndex = current.audioIndex,
            initialSubtitleIndex = current.subtitleIndex,
            onRemoteStop = {
                pendingPlayerCommand = null
                screen = if (lastDetail != null) lastDetail!! else Screen.Home
                session?.let { active -> refreshProgress(active) }
            },
            onRemoteCommandConsumed = { id ->
                if (pendingPlayerCommand?.id == id) pendingPlayerCommand = null
            },
        )
    }
    watchMenu?.let { menu ->
        WatchActionOverlay(
            title = menu.title,
            watched = menu.watched,
            watchlisted = menu.watchlisted,
            onMarkWatched = {
                closeWatchMenu(menu)
                menu.onMarkWatched()
            },
            onMarkUnwatched = {
                closeWatchMenu(menu)
                menu.onMarkUnwatched()
            },
            onAddWatchlist = {
                closeWatchMenu(menu)
                menu.onAddWatchlist()
            },
            onRemoveWatchlist = {
                closeWatchMenu(menu)
                menu.onRemoveWatchlist()
            },
            onDismiss = { closeWatchMenu(menu) },
        )
    }
}

// ── Login ──

// ── Home ──

@Composable
fun HomeView(
    session: Session?,
    libraries: List<Library>,
    items: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    recentMovies: List<PopItem>,
    recentShows: List<ShowSummary>,
    watchlistMovies: List<PopItem>,
    watchlistTvShows: List<ShowSummary>,
    error: String,
    loading: Boolean,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(session, libraries, selected = "home", onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onLogout = onLogout)
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        } else {
            CuratedLanding(session, items, shows, completedItems, completedShows, watchlistItems, watchlistShows, recentMovies, recentShows, watchlistMovies, watchlistTvShows, onItem, onShow, onItemMenu, onShowMenu)
        }
    }
}

@Composable
fun TopBar(session: Session?, libraries: List<Library>, selected: String, onHome: () -> Unit, onLibrary: (Library) -> Unit, onWatchlist: () -> Unit, onSearch: () -> Unit, onLogout: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SurfaceColor)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Popcorn", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(12.dp))
        Pill(text = "Home", selected = selected == "home", onClick = onHome)
        libraries.forEach { library ->
            Pill(text = library.name, selected = selected == library.id, onClick = { onLibrary(library) })
        }
        Pill(text = "Watchlist", selected = selected == "watchlist", onClick = onWatchlist)
        Spacer(Modifier.weight(1f))
        if (!session?.username.isNullOrBlank()) {
            Text(session?.username.orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Pill("Search", selected = false, onClick = onSearch)
        Pill("Logout", selected = false, onClick = onLogout)
    }
}

@Composable
fun WatchlistView(
    session: Session?,
    libraries: List<Library>,
    movies: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    error: String,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var initialFocusPending by remember(movies.firstOrNull()?.id, shows.firstOrNull()?.title) { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(session, libraries, selected = "watchlist", onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onLogout = onLogout)
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
        }
        if (movies.isEmpty() && shows.isEmpty()) {
            EmptyState("No watchlist items")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GridSectionHeader("Watchlist", "${movies.size} movies · ${shows.size} shows")
            }
            if (movies.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GridSectionHeader("Movies", "${movies.size} saved", compact = true)
                }
                gridItemsIndexed(movies, key = { _, item -> "movie:${item.id}" }) { index, item ->
                    val focusNow = initialFocusPending && index == 0
                    ItemCard(
                        session = session,
                        item = item,
                        watched = completedItems.contains(item.id),
                        watchlisted = watchlistItems.contains(item.id),
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialFocusPending = false },
                        onClick = { onItem(item) },
                        onLongClick = { requester -> onItemMenu(item, requester) },
                    )
                }
            }
            if (shows.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GridSectionHeader("TV Shows", "${shows.size} saved", compact = true)
                }
                gridItemsIndexed(shows, key = { _, show -> "show:${show.libraryId}:${show.title}" }) { index, show ->
                    val focusNow = initialFocusPending && movies.isEmpty() && index == 0
                    ShowCard(
                        session = session,
                        show = show,
                        watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                        watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialFocusPending = false },
                        onClick = { onShow(show) },
                        onLongClick = { requester -> onShowMenu(show, requester) },
                    )
                }
            }
        }
    }
}

@Composable
fun GridSectionHeader(title: String, subtitle: String, compact: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 8.dp else 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = TextColor, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
fun LibraryPageView(
    session: Session?,
    libraries: List<Library>,
    activeLibrary: Library,
    items: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    error: String,
    loading: Boolean,
    pageIndex: Int,
    pageHasNext: Boolean,
    selectedGenre: String,
    genres: List<String>,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onSearch: () -> Unit,
    onLogout: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onGenre: (String) -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    var genrePanelOpen by remember(activeLibrary.id) { mutableStateOf(false) }
    val genreFocus = remember { FocusRequester() }
    var restoreGridFocus by remember(activeLibrary.id, pageIndex, selectedGenre) { mutableStateOf<FocusRequester?>(null) }
    fun closeGenrePanel() {
        genrePanelOpen = false
        restoreGridFocus?.requestFocus()
    }
    BackHandler(enabled = genrePanelOpen) {
        closeGenrePanel()
    }
    LaunchedEffect(genrePanelOpen) {
        if (genrePanelOpen) {
            delay(80)
            genreFocus.requestFocus()
        }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(session, libraries, selected = activeLibrary.id, onHome = onHome, onLibrary = onLibrary, onWatchlist = onWatchlist, onSearch = onSearch, onLogout = onLogout)
            if (error.isNotBlank()) {
                Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp), fontSize = 13.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val genreSuffix = if (selectedGenre.isBlank()) "" else " / $selectedGenre"
                Text("${activeLibrary.name}$genreSuffix", color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Pill("Prev", selected = false, onClick = { if (pageIndex > 0) onPreviousPage() })
                Text("Page ${pageIndex + 1}", color = Muted, fontSize = 12.sp)
                Pill("Next", selected = false, onClick = { if (pageHasNext) onNextPage() })
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                } else if (activeLibrary.type == "tv") {
                    PosterGrid(entries = shows, key = { it.title }) { show, autoFocus, column, focusRequester ->
                        ShowCard(
                            session = session,
                            show = show,
                            watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                            watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"),
                            autoFocus = autoFocus,
                            focusRequester = focusRequester,
                            onFocus = { restoreGridFocus = focusRequester },
                            onLeftEdge = {
                                if (column == 0) {
                                    genrePanelOpen = true
                                    true
                                } else {
                                    false
                                }
                            },
                            onClick = { onShow(show) },
                            onLongClick = { requester -> onShowMenu(show, requester) },
                        )
                    }
                } else {
                    PosterGrid(entries = items, key = { it.id }) { item, autoFocus, column, focusRequester ->
                        ItemCard(
                            session = session,
                            item = item,
                            watched = completedItems.contains(item.id),
                            watchlisted = watchlistItems.contains(item.id),
                            autoFocus = autoFocus,
                            focusRequester = focusRequester,
                            onFocus = { restoreGridFocus = focusRequester },
                            onLeftEdge = {
                                if (column == 0) {
                                    genrePanelOpen = true
                                    true
                                } else {
                                    false
                                }
                            },
                            onClick = { onItem(item) },
                            onLongClick = { requester -> onItemMenu(item, requester) },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = genrePanelOpen,
            enter = fadeIn(tween(140)) + slideInHorizontally(tween(180)) { -it },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it },
        ) {
            GenreSidePanel(
                genres = genres,
                selectedGenre = selectedGenre,
                focusRequester = genreFocus,
                onGenre = {
                    closeGenrePanel()
                    onGenre(it)
                },
                onClose = ::closeGenrePanel,
            )
        }
    }
}

@Composable
fun GenreSidePanel(
    genres: List<String>,
    selectedGenre: String,
    focusRequester: FocusRequester,
    onGenre: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .45f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionRight || it.key == Key.Back)) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .width(250.dp)
                .fillMaxSize()
                .background(SurfaceColor)
                .border(1.dp, Line)
                .padding(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 18.dp),
        ) {
            Text("Genres", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "all") {
                    GenreFilterRow(
                        text = "All",
                        selected = selectedGenre.isBlank(),
                        modifier = Modifier.focusRequester(focusRequester),
                        onClick = { onGenre("") },
                    )
                }
                items(genres, key = { it }) { genre ->
                    GenreFilterRow(
                        text = genre,
                        selected = genre.equals(selectedGenre, ignoreCase = true),
                        modifier = Modifier,
                        onClick = { onGenre(genre) },
                    )
                }
            }
        }
    }
}

@Composable
fun GenreFilterRow(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused && !selected) FocusGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (selected) Color.Black else TextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun BrowserHeader(title: String, subtitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
fun CuratedLanding(
    session: Session?,
    movies: List<PopItem>,
    shows: List<ShowSummary>,
    completedItems: Set<Long>,
    completedShows: Set<String>,
    watchlistItems: Set<Long>,
    watchlistShows: Set<String>,
    recentMovies: List<PopItem>,
    recentShows: List<ShowSummary>,
    watchlistMovies: List<PopItem>,
    watchlistTvShows: List<ShowSummary>,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
    onItemMenu: (PopItem, FocusRequester?) -> Unit,
    onShowMenu: (ShowSummary, FocusRequester?) -> Unit,
) {
    if (movies.isEmpty() && shows.isEmpty()) {
        EmptyState("No media found")
        return
    }
    val topMovies = remember(movies) { movies.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { movies.take(24) } }
    val moviePicks = remember(movies) { movies.shuffledStable().take(24) }
    val topShows = remember(shows) { shows.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { shows.take(24) } }
    val showPicks = remember(shows) { shows.shuffledStableBy { it.title }.take(24) }
    val movieShelves = remember(movies) { movieGenreShelves(movies).take(4) }
    val showShelves = remember(shows) { showGenreShelves(shows).take(4) }
    var initialFocusPending by remember { mutableStateOf(true) }
    val initialFocusTarget = when {
        watchlistMovies.isNotEmpty() -> "watchlistMovies"
        watchlistTvShows.isNotEmpty() -> "watchlistShows"
        recentMovies.isNotEmpty() -> "recentMovies"
        recentShows.isNotEmpty() -> "recentShows"
        movies.isNotEmpty() -> "topMovies"
        shows.isNotEmpty() -> "topShows"
        else -> ""
    }
    fun shouldInitialFocus(target: String, autoFocus: Boolean): Boolean {
        return initialFocusPending && autoFocus && initialFocusTarget == target
    }
    fun consumeInitialFocus(target: String, autoFocus: Boolean) {
        if (shouldInitialFocus(target, autoFocus)) {
            initialFocusPending = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BrowserHeader("Home", "${movies.size} movies \u00b7 ${shows.size} shows loaded") }
        if (watchlistMovies.isNotEmpty()) {
            item {
                PosterShelf("Watchlist Movies", "${watchlistMovies.size} saved", watchlistMovies, key = { it.id }, autoFocusFirst = true) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("watchlistMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = true, autoFocus = focusNow, onFocus = { consumeInitialFocus("watchlistMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (watchlistTvShows.isNotEmpty()) {
            item {
                PosterShelf("Watchlist TV", "${watchlistTvShows.size} shows", watchlistTvShows, key = { it.title }) { show, autoFocus ->
                    val focusNow = shouldInitialFocus("watchlistShows", autoFocus)
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = true, autoFocus = focusNow, onFocus = { consumeInitialFocus("watchlistShows", autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
        if (recentMovies.isNotEmpty()) {
            item {
                PosterShelf("Recently Added Movies", "${recentMovies.size} new", recentMovies, key = { it.id }, autoFocusFirst = watchlistMovies.isEmpty() && watchlistTvShows.isEmpty()) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("recentMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, onFocus = { consumeInitialFocus("recentMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (recentShows.isNotEmpty()) {
            item {
                PosterShelf("Recently Added TV", "${recentShows.size} shows", recentShows, key = { it.title }) { show, autoFocus ->
                    val focusNow = shouldInitialFocus("recentShows", autoFocus)
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = focusNow, onFocus = { consumeInitialFocus("recentShows", autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
        if (movies.isNotEmpty()) {
            item {
                PosterShelf("Top Rated Movies", "${topMovies.size} picks", topMovies, key = { it.id }, autoFocusFirst = true) { item, autoFocus ->
                    val focusNow = shouldInitialFocus("topMovies", autoFocus)
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = focusNow, onFocus = { consumeInitialFocus("topMovies", autoFocus) }, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
            item {
                PosterShelf("Movie Picks", "from your movies", moviePicks, key = { it.id }) { item, autoFocus ->
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = autoFocus, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        if (shows.isNotEmpty()) {
            item {
                PosterShelf("Top Rated TV", "${topShows.size} shows", topShows, key = { it.title }) { show, autoFocus ->
                    val focusNow = shouldInitialFocus("topShows", autoFocus)
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = focusNow, onFocus = { consumeInitialFocus("topShows", autoFocus) }, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
            item {
                PosterShelf("Show Picks", "from your shows", showPicks, key = { it.title }) { show, autoFocus ->
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = autoFocus, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
        movieShelves.forEach { (genre, entries) ->
            item(key = "home-movie-$genre") {
                PosterShelf(genre, "${entries.size} movies", entries, key = { it.id }) { item, autoFocus ->
                    ItemCard(session, item, watched = completedItems.contains(item.id), watchlisted = watchlistItems.contains(item.id), autoFocus = autoFocus, onClick = { onItem(item) }, onLongClick = { requester -> onItemMenu(item, requester) })
                }
            }
        }
        showShelves.forEach { (genre, entries) ->
            item(key = "home-show-$genre") {
                PosterShelf("$genre TV", "${entries.size} shows", entries, key = { it.title }) { show, autoFocus ->
                    ShowCard(session, show, watched = completedShows.contains("${show.libraryId}\n${show.title.lowercase()}"), watchlisted = watchlistShows.contains("${show.libraryId}\n${show.title.lowercase()}"), autoFocus = autoFocus, onClick = { onShow(show) }, onLongClick = { requester -> onShowMenu(show, requester) })
                }
            }
        }
    }
}

@Composable
fun MovieLanding(session: Session?, title: String, items: List<PopItem>, onItem: (PopItem) -> Unit) {
    if (items.isEmpty()) {
        EmptyState("No movies found")
        return
    }
    val topRated = remember(items) {
        items.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { items.take(24) }
    }
    val moviePicks = remember(items) { items.shuffledStable().take(24) }
    val shelves = remember(items) { movieGenreShelves(items) }
    var initialFocusPending by remember(items.firstOrNull()?.id) { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BrowserHeader(title, "${items.size} movies") }
        item {
            PosterShelf(
                title = "Top Rated",
                subtitle = "${topRated.size} picks",
                entries = topRated,
                key = { it.id },
                autoFocusFirst = true,
            ) { item, autoFocus ->
                val focusNow = initialFocusPending && autoFocus
                ItemCard(session, item, autoFocus = focusNow, onFocus = { if (focusNow) initialFocusPending = false }, onClick = { onItem(item) })
            }
        }
        item {
            PosterShelf(
                title = "Movie Picks",
                subtitle = "from your library",
                entries = moviePicks,
                key = { it.id },
            ) { item, autoFocus ->
                ItemCard(session, item, autoFocus = autoFocus, onClick = { onItem(item) })
            }
        }
        shelves.forEach { (genre, entries) ->
            item(key = "movie-genre-$genre") {
                PosterShelf(
                    title = genre,
                    subtitle = "${entries.size} movies",
                    entries = entries,
                    key = { it.id },
                ) { item, autoFocus ->
                    ItemCard(session, item, autoFocus = autoFocus, onClick = { onItem(item) })
                }
            }
        }
    }
}

@Composable
fun TvLanding(session: Session?, title: String, shows: List<ShowSummary>, onShow: (ShowSummary) -> Unit) {
    if (shows.isEmpty()) {
        EmptyState("No shows found")
        return
    }
    val topRated = remember(shows) {
        shows.filter { it.rating > 0 }.sortedByDescending { it.rating }.take(24).ifEmpty { shows.take(24) }
    }
    val showPicks = remember(shows) { shows.shuffledStableBy { it.title }.take(24) }
    val shelves = remember(shows) { showGenreShelves(shows) }
    var initialFocusPending by remember(shows.firstOrNull()?.title) { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BrowserHeader(title, "${shows.size} shows") }
        item {
            PosterShelf(
                title = "Top Rated",
                subtitle = "${topRated.size} shows",
                entries = topRated,
                key = { it.title },
                autoFocusFirst = true,
            ) { show, autoFocus ->
                val focusNow = initialFocusPending && autoFocus
                ShowCard(session, show, autoFocus = focusNow, onFocus = { if (focusNow) initialFocusPending = false }, onClick = { onShow(show) })
            }
        }
        item {
            PosterShelf(
                title = "Show Picks",
                subtitle = "from your library",
                entries = showPicks,
                key = { it.title },
            ) { show, autoFocus ->
                ShowCard(session, show, autoFocus = autoFocus, onClick = { onShow(show) })
            }
        }
        shelves.forEach { (genre, entries) ->
            item(key = "show-genre-$genre") {
                PosterShelf(
                    title = genre,
                    subtitle = "${entries.size} shows",
                    entries = entries,
                    key = { it.title },
                ) { show, autoFocus ->
                    ShowCard(session, show, autoFocus = autoFocus, onClick = { onShow(show) })
                }
            }
        }
    }
}

@Composable
fun <T> PosterShelf(
    title: String,
    subtitle: String,
    entries: List<T>,
    key: (T) -> Any,
    autoFocusFirst: Boolean = false,
    content: @Composable (T, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(entries, key = { _, item -> key(item) }) { index, item ->
                Box(Modifier.width(122.dp)) {
                    content(item, autoFocusFirst && index == 0)
                }
            }
        }
    }
}

@Composable
fun GenreBrowserView(
    session: Session?,
    activeLibrary: Library?,
    items: List<PopItem>,
    shows: List<ShowSummary>,
    loadingMore: Boolean,
    fullyLoaded: Boolean,
    onBack: () -> Unit,
    onItem: (PopItem) -> Unit,
    onShow: (ShowSummary) -> Unit,
) {
    val itemGenrePairs = remember(items) { items.map { it to splitGenres(it.genres) } }
    val showGenrePairs = remember(shows) { shows.map { it to splitGenres(it.genres) } }
    val genres = remember(activeLibrary?.type, itemGenrePairs, showGenrePairs) {
        val values = if (activeLibrary?.type == "tv") showGenrePairs.flatMap { it.second } else itemGenrePairs.flatMap { it.second }
        values.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var selectedGenre by remember(genres) { mutableStateOf(genres.firstOrNull().orEmpty()) }
    var initialGenreFocusPending by remember(genres.firstOrNull()) { mutableStateOf(true) }

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
            Text(activeLibrary?.name ?: "Genres", color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(
                if (fullyLoaded) "Genres" else "Genres loading...",
                color = Muted,
                fontSize = 12.sp,
            )
            if (loadingMore) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }

        if (genres.isEmpty()) {
            EmptyState("No genres found in this library")
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.width(220.dp).fillMaxSize().background(SurfaceColor.copy(alpha = .55f)),
                contentPadding = PaddingValues(start = 18.dp, end = 12.dp, top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(genres, key = { _, genre -> genre }) { index, genre ->
                    val focusNow = initialGenreFocusPending && index == 0
                    GenreRow(
                        text = genre,
                        count = if (activeLibrary?.type == "tv") {
                            showGenrePairs.count { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
                        } else {
                            itemGenrePairs.count { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
                        },
                        selected = genre == selectedGenre,
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialGenreFocusPending = false },
                        onClick = { selectedGenre = genre },
                    )
                }
            }

            Column(Modifier.weight(1f).fillMaxSize()) {
                if (activeLibrary?.type == "tv") {
                    val filtered = showGenrePairs.filter { (_, genres) -> genres.any { it.equals(selectedGenre, ignoreCase = true) } }.map { it.first }
                    BrowserHeader(selectedGenre, "${filtered.size} shows")
                    PosterGrid(entries = filtered, key = { it.title }) { show, _, _, _ ->
                        ShowCard(session, show, autoFocus = false, onClick = { onShow(show) })
                    }
                } else {
                    val filtered = itemGenrePairs.filter { (_, genres) -> genres.any { it.equals(selectedGenre, ignoreCase = true) } }.map { it.first }
                    BrowserHeader(selectedGenre, "${filtered.size} movies")
                    PosterGrid(entries = filtered, key = { it.id }) { item, _, _, _ ->
                        ItemCard(session, item, autoFocus = false, onClick = { onItem(item) })
                    }
                }
            }
        }
    }
}

@Composable
fun GenreRow(text: String, count: Int, selected: Boolean, autoFocus: Boolean, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(180)
            focusRequester.requestFocus()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused && !selected) FocusGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = if (selected) Color.Black else TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count.toString(), color = if (selected) Color.Black.copy(alpha = .7f) else Muted, fontSize = 10.sp)
    }
}

private fun splitGenres(value: String): List<String> {
    return value
        .split(",", "/", "|", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun movieGenreShelves(items: List<PopItem>): List<Pair<String, List<PopItem>>> {
    val pairs = items.map { it to splitGenres(it.genres) }
    val topGenres = pairs
        .flatMap { it.second }
        .groupingBy { it }
        .eachCount()
        .entries
        .filter { it.value >= 4 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
        .take(8)
        .map { it.key }
    return topGenres.mapNotNull { genre ->
        val entries = pairs
            .filter { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
            .map { it.first }
            .sortedWith(compareByDescending<PopItem> { it.rating }.thenBy { it.title })
            .take(24)
        if (entries.isNotEmpty()) genre to entries else null
    }
}

private fun showGenreShelves(shows: List<ShowSummary>): List<Pair<String, List<ShowSummary>>> {
    val pairs = shows.map { it to splitGenres(it.genres) }
    val topGenres = pairs
        .flatMap { it.second }
        .groupingBy { it }
        .eachCount()
        .entries
        .filter { it.value >= 3 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
        .take(8)
        .map { it.key }
    return topGenres.mapNotNull { genre ->
        val entries = pairs
            .filter { (_, genres) -> genres.any { it.equals(genre, ignoreCase = true) } }
            .map { it.first }
            .sortedWith(compareByDescending<ShowSummary> { it.rating }.thenBy { it.title })
            .take(24)
        if (entries.isNotEmpty()) genre to entries else null
    }
}

private fun List<PopItem>.shuffledStable(): List<PopItem> {
    return sortedBy { ((it.id * 1103515245L + 12345L) and 0x7fffffff).toInt() }
}

private fun <T> List<T>.shuffledStableBy(selector: (T) -> String): List<T> {
    return sortedBy { selector(it).fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff } }
}

@Composable
fun EmptyState(text: String, error: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (error) ErrorRed else Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Show View ──

@Composable
fun ShowView(session: Session?, show: ShowSummary, onSeason: (SeasonSummary) -> Unit) {
    val seasons = remember { mutableStateListOf<SeasonSummary>() }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(show.libraryId, show.title) {
        val active = session ?: return@LaunchedEffect
        loading = true
        runCatching { Api(active).seasons(show.libraryId, show.title) }
            .onSuccess {
                seasons.clear()
                seasons.addAll(it)
            }
            .onFailure { error = it.message ?: "Failed to load seasons" }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        ShowHeader(session, show)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Seasons", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${show.seasonCount} seasons · ${show.episodeCount} episodes", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp), fontSize = 12.sp)
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            PosterGrid(seasons, key = { "${it.libraryId}:${it.showTitle}:${it.seasonNumber}" }) { season, autoFocus, _, requester ->
                SeasonCard(
                    session = session,
                    season = season,
                    autoFocus = autoFocus,
                    focusRequester = requester,
                    onClick = { onSeason(season) },
                )
            }
        }
    }
}

@Composable
fun SeasonView(session: Session?, show: ShowSummary, season: SeasonSummary, onEpisode: (PopItem) -> Unit) {
    val episodes = remember { mutableStateListOf<PopItem>() }
    var completedEpisodes by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchlistEpisodes by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchMenu by remember { mutableStateOf<WatchMenuState?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var initialEpisodeFocusPending by remember(season.seasonNumber) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun closeWatchMenu(menu: WatchMenuState? = watchMenu) {
        watchMenu = null
        menu?.restoreFocus?.let { restore ->
            scope.launch {
                delay(90)
                runCatching { restore() }
            }
        }
    }

    fun setEpisodeWatched(item: PopItem, watched: Boolean) {
        val active = session ?: return
        scope.launch {
            runCatching {
                val api = Api(active)
                if (watched) api.markItemWatched(item) else api.unmarkItemWatched(item.id)
            }.onSuccess {
                completedEpisodes = if (watched) completedEpisodes + item.id else completedEpisodes - item.id
            }.onFailure {
                error = it.message ?: "Failed to update watched state"
            }
        }
    }

    fun setEpisodeWatchlisted(item: PopItem, listed: Boolean) {
        val active = session ?: return
        scope.launch {
            runCatching {
                val api = Api(active)
                if (listed) api.addItemWatchlist(item.id) else api.removeItemWatchlist(item.id)
            }.onSuccess {
                watchlistEpisodes = if (listed) watchlistEpisodes + item.id else watchlistEpisodes - item.id
            }.onFailure {
                error = it.message ?: "Failed to update watchlist"
            }
        }
    }

    LaunchedEffect(show.libraryId, show.title, season.seasonNumber) {
        val active = session ?: return@LaunchedEffect
        loading = true
        runCatching {
            val api = Api(active)
            Triple(
                api.episodes(show.libraryId, show.title, season.seasonNumber),
                api.progressList().filter { it.completed }.map { it.itemId }.toSet(),
                api.watchlist().items.map { it.id }.toSet(),
            )
        }.onSuccess {
            episodes.clear()
            episodes.addAll(it.first)
            completedEpisodes = it.second
            watchlistEpisodes = it.third
        }.onFailure {
            error = it.message ?: "Failed to load episodes"
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SeasonHeader(session, show, season)
        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp), fontSize = 12.sp)
        }
        if (loading && episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
            ) {
                itemsIndexed(episodes, key = { _, item -> item.id }) { index, episode ->
                    val watched = completedEpisodes.contains(episode.id)
                    val focusNow = initialEpisodeFocusPending && index == 0
                    EpisodeRow(
                        session,
                        episode,
                        watched = watched,
                        watchlisted = watchlistEpisodes.contains(episode.id),
                        autoFocus = focusNow,
                        onFocus = { if (focusNow) initialEpisodeFocusPending = false },
                        onClick = { onEpisode(episode) },
                        onLongClick = { focusRequester ->
                            val listed = watchlistEpisodes.contains(episode.id)
                            watchMenu = WatchMenuState(
                                title = episode.episodeTitle.ifBlank { episode.title },
                                watched = watched,
                                watchlisted = listed,
                                onMarkWatched = { setEpisodeWatched(episode, true) },
                                onMarkUnwatched = { setEpisodeWatched(episode, false) },
                                onAddWatchlist = { setEpisodeWatchlisted(episode, true) },
                                onRemoveWatchlist = { setEpisodeWatchlisted(episode, false) },
                                restoreFocus = { focusRequester.requestFocus() },
                            )
                        },
                    )
                }
            }
        }
    }
    watchMenu?.let { menu ->
        WatchActionOverlay(
            title = menu.title,
            watched = menu.watched,
            watchlisted = menu.watchlisted,
            onMarkWatched = {
                closeWatchMenu(menu)
                menu.onMarkWatched()
            },
            onMarkUnwatched = {
                closeWatchMenu(menu)
                menu.onMarkUnwatched()
            },
            onAddWatchlist = {
                closeWatchMenu(menu)
                menu.onAddWatchlist()
            },
            onRemoveWatchlist = {
                closeWatchMenu(menu)
                menu.onRemoveWatchlist()
            },
            onDismiss = { closeWatchMenu(menu) },
        )
    }
}

@Composable
fun ShowHeader(session: Session?, show: ShowSummary) {
    Box(Modifier.fillMaxWidth().height(206.dp)) {
        if (show.backdropItemId > 0 && session != null) {
            SizedAsyncImage(
                model = imageUrl(session, show.backdropItemId, "backdrop", show.backdropMtimeUnix),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 900,
                heightPx = 360,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = .18f), Bg), startY = 40f)
            )
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(session, show.posterItemId, Modifier.width(82.dp), show.posterMtimeUnix)
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                Text(show.title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${show.seasonCount} seasons", color = Muted, fontSize = 12.sp)
                    Text("${show.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                    if (show.rating > 0) RatingBadge(show.rating)
                }
                if (show.genres.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(show.genres, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (show.overview.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(show.overview, color = TextColor.copy(alpha = .76f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SeasonHeader(session: Session?, show: ShowSummary, season: SeasonSummary) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 32.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeasonPoster(session, season, Modifier.width(70.dp))
        Column(Modifier.weight(1f)) {
            Text(show.title, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(seasonTitle(season), color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${season.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                fmtDuration(season.durationMs).takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }
                if (season.rating > 0) RatingBadge(season.rating)
            }
            if (season.overview.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(season.overview, color = TextColor.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SeasonCard(
    session: Session?,
    season: SeasonSummary,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onClick = onClick) {
        Box {
            SeasonPoster(session, season, Modifier.fillMaxWidth())
            if (season.rating > 0) PosterRating(season.rating)
        }
        Spacer(Modifier.height(4.dp))
        Text(seasonTitle(season), color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${season.episodeCount} episodes", color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun SeasonPoster(session: Session?, season: SeasonSummary, modifier: Modifier) {
    val url = imageUrl(session, season.posterItemId, "season", season.posterMtimeUnix)
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 390)
        } else {
            Text(seasonTitle(season).take(1), color = Muted, fontSize = 20.sp)
        }
    }
}

private fun seasonTitle(season: SeasonSummary): String {
    if (season.title.isNotBlank()) return season.title
    return if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"
}
