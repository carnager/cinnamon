package dev.popcorn.tv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
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

data class WatchMenuState(
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
            api.registerDevice(deviceId.ifBlank { null }, shieldDeviceName())
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
