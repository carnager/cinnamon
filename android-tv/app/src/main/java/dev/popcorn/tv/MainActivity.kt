package dev.popcorn.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        if (BrowseBackBridge.dispatch(event)) return true
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
    var continueMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var continueEpisodes by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var recentMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var recentShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var completedItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var completedShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var watchlistItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchlistShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var watchlistMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var watchlistTvShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var updateAvailable by remember { mutableStateOf(false) }
    var updateDialogOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchReturnScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var loadGeneration by remember { mutableStateOf(0) }
    var libraryFullyLoaded by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableStateOf(0) }
    var pageHasNext by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf("") }
    var selectedSort by remember { mutableStateOf("") }
    var selectedMinRating by remember { mutableStateOf(0.0) }
    var selectedSeenStatus by remember { mutableStateOf("") }
    var libraryGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var libraryAlphabet by remember { mutableStateOf<List<AlphabetEntry>>(emptyList()) }
    var watchMenu by remember { mutableStateOf<WatchMenuState?>(null) }
    var deviceId by remember { mutableStateOf(prefs.getString("remoteDeviceId", "") ?: "") }
    var preferredBandwidthKbps by remember {
        val savedBandwidth = prefs.getInt("preferredBandwidthKbps", -1)
        mutableStateOf(BandwidthOptions.firstOrNull { it.kbps == savedBandwidth }?.kbps)
    }
    var lastRemoteCommandId by remember { mutableStateOf(prefs.getLong("remoteCommandId", 0L)) }
    var pendingPlayerCommand by remember { mutableStateOf<PlayerRemoteCommand?>(null) }
    var lastDetail by remember { mutableStateOf<Screen.Detail?>(null) }
    var lastPlayerReturnScreen by remember { mutableStateOf<Screen?>(null) }
    var lastActorReturnScreen by remember { mutableStateOf<Screen?>(null) }
    var libraryFocusKey by remember { mutableStateOf<Any?>(null) }
    var showFocusSeason by remember { mutableStateOf<Int?>(null) }
    var seasonFocusEpisode by remember { mutableStateOf<Long?>(null) }
    var visibleContentRefresh by remember { mutableStateOf(0L) }

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

    suspend fun loadContinueRows(api: Api): Pair<List<PopItem>, List<PopItem>> {
        val progress = api.progressList()
        val completedItemIds = progress.filter { it.completed }.map { it.itemId }.toSet()
        val resumable = progress
            .filter { progress ->
                val duration = progress.durationMs
                val position = progress.positionMs
                !progress.completed &&
                    duration > 0 &&
                    position >= 30_000 &&
                    position < (duration - 90_000).coerceAtLeast(30_000)
            }
            .take(40)
        val entries = resumable.mapNotNull { progress ->
            runCatching { api.item(progress.itemId) }.getOrNull()
        }
        val resumeMovies = entries.filter { it.kind == "movie" }.take(24)
        val resumeEpisodes = entries.filter { it.kind == "episode" }.toMutableList()
        val resumeEpisodeIds = resumeEpisodes.map { it.id }.toMutableSet()
        val partialShows = api.showProgress().filter { it.completedCount > 0 && !it.completed }.take(40)
        for (show in partialShows) {
            if (resumeEpisodes.size >= 24) break
            val nextEpisode = runCatching {
                api.episodes(show.libraryId, show.showTitle)
                    .firstOrNull { episode -> episode.id !in completedItemIds && episode.id !in resumeEpisodeIds }
            }.getOrNull()
            if (nextEpisode != null && resumeEpisodeIds.add(nextEpisode.id)) {
                resumeEpisodes.add(nextEpisode)
            }
        }
        return resumeMovies to resumeEpisodes.take(24)
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

    fun refreshUpdateAvailable(activeSession: Session) {
        scope.launch {
            updateAvailable = runCatching { Api(activeSession).tvUpdate(BuildConfig.VERSION_CODE).available }.getOrDefault(false)
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
                                lastDetail = Screen.Detail(item, null, fromHome = true)
                                val audio = command.payload.optIntOrNull("audioIndex")
                                val subtitle = command.payload.optIntOrNull("subtitleIndex")
                                screen = Screen.Player(item, audio, subtitle, 0L)
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

    fun scanLibraries() {
        val active = session ?: return
        scope.launch {
            runCatching { Api(active).scanLibraries() }
                .onSuccess { error = "Library scan started" }
                .onFailure { error = it.message ?: "Scan failed" }
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
                val (resumeMovies, resumeEpisodes) = loadContinueRows(api)
                continueMovies = resumeMovies
                continueEpisodes = resumeEpisodes
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

    fun loadLibraryPage(library: Library, activeSession: Session, page: Int = 0, genre: String = selectedGenre, sort: String = selectedSort, minRating: Double = selectedMinRating, seenStatus: String = selectedSeenStatus, resetFiltersOnLibraryChange: Boolean = true) {
        loadGeneration += 1
        val generation = loadGeneration
        scope.launch {
            error = ""
            loading = true
            val previousLibraryID = activeLibrary?.id
            activeLibrary = library
            items = emptyList()
            shows = emptyList()
            val shouldResetFilters = resetFiltersOnLibraryChange && page == 0 && library.id != previousLibraryID
            val activeGenre = if (shouldResetFilters) {
                ""
            } else {
                genre
            }
            val activeSort = if (shouldResetFilters) "" else sort
            val activeMinRating = if (shouldResetFilters) 0.0 else minRating
            val activeSeenStatus = if (shouldResetFilters) "" else seenStatus
            selectedGenre = activeGenre
            selectedSort = activeSort
            selectedMinRating = activeMinRating
            selectedSeenStatus = activeSeenStatus
            pageIndex = page.coerceAtLeast(0)
            libraryFullyLoaded = false
            loadingMore = false
            screen = Screen.LibraryPage(library)
            runCatching {
                refreshProgress(activeSession)
                refreshWatchlist(activeSession)
                val api = Api(activeSession)
                val pageSize = 500
                val offset = pageIndex
                libraryGenres = api.genres(library.id)
                libraryAlphabet = if (activeSort.isBlank()) api.alphabet(library.id, if (library.type == "tv") "tv" else "movie", activeGenre) else emptyList()
                if (library.type == "tv") {
                    val pageItems = api.showsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus)
                    if (generation != loadGeneration) return@launch
                    shows = pageItems
                    pageHasNext = pageItems.size == pageSize
                    libraryFullyLoaded = pageItems.size < pageSize
                } else {
                    val pageItems = api.itemsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus)
                    if (generation != loadGeneration) return@launch
                    items = pageItems
                    pageHasNext = pageItems.size == pageSize
                    libraryFullyLoaded = pageItems.size < pageSize
                }
            }.onFailure { error = it.message ?: "Load failed" }
            loading = false
            if (generation == loadGeneration && !libraryFullyLoaded) {
                scope.launch {
                    loadingMore = true
                    runCatching {
                        val api = Api(activeSession)
                        val pageSize = 500
                        val canUpdateLibraryCache = pageIndex == 0 && activeGenre.isBlank() && activeSort.isBlank() && activeMinRating <= 0.0 && activeSeenStatus.isBlank()
                        var offset = pageIndex + if (library.type == "tv") shows.size else items.size
                        while (true) {
                            if (library.type == "tv") {
                                val pageItems = api.showsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus)
                                if (generation != loadGeneration) return@launch
                                shows = (shows + pageItems).distinctBy { showKey(it) }
                                if (canUpdateLibraryCache) {
                                    AppCache.writeShows(context, activeSession, library.id, shows, pageItems.size < pageSize)
                                }
                                if (pageItems.size < pageSize) break
                                offset += pageSize
                            } else {
                                val pageItems = api.itemsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus)
                                if (generation != loadGeneration) return@launch
                                items = (items + pageItems).distinctBy { it.id }
                                if (canUpdateLibraryCache) {
                                    AppCache.writeItems(context, activeSession, library.id, items, pageItems.size < pageSize)
                                }
                                if (pageItems.size < pageSize) break
                                offset += pageSize
                            }
                            delay(32)
                        }
                        libraryFullyLoaded = true
                    }.onFailure { error = it.message ?: "Load failed" }
                    loadingMore = false
                }
            }
        }
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
                    refreshUpdateAvailable(activeSession)
                    when (val current = screen) {
                        Screen.Home -> if (libraries.isNotEmpty()) loadHome(activeSession, libraries)
                        is Screen.LibraryPage -> loadLibraryPage(current.library, activeSession, pageIndex, selectedGenre, selectedSort, selectedMinRating, selectedSeenStatus)
                        is Screen.Show, is Screen.Season -> visibleContentRefresh += 1
                        else -> Unit
                    }
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        while (true) {
            updateAvailable = runCatching { Api(active).tvUpdate(BuildConfig.VERSION_CODE).available }.getOrDefault(false)
            delay(5 * 60 * 1000)
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
            updateAvailable = runCatching { api.tvUpdate(BuildConfig.VERSION_CODE).available }.getOrDefault(false)
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

    fun returnFromDetail(s: Screen.Detail) {
        lastDetail = null
        screen = if (s.fromActor != null) {
            Screen.Actor(s.fromActor)
        } else if (s.fromSearch && s.fromShow == null) {
            Screen.Search
        } else if (s.fromShow != null) {
            Screen.Show(s.fromShow, fromHome = s.fromHome, fromSearch = s.fromSearch, fromWatchlist = s.fromWatchlist, fromActor = s.fromActor)
        } else if (s.fromWatchlist) {
            Screen.Watchlist
        } else if (s.fromHome) {
            Screen.Home
        } else if (activeLibrary != null) {
            Screen.LibraryPage(activeLibrary!!)
        } else {
            Screen.Home
        }
    }

    fun openExternalTrailer(item: PopItem) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeTrailerSearchUrl(item.title, item.year)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { error = "No app can open trailer search" }
    }

    fun openSearch() {
        if (screen !is Screen.Search && screen !is Screen.Login && screen !is Screen.Loading) {
            searchReturnScreen = screen
        }
        screen = Screen.Search
    }

    fun closeSearch() {
        screen = when (val target = searchReturnScreen) {
            Screen.Search, Screen.Login, Screen.Loading -> Screen.Home
            else -> target
        }
    }

    BackHandler(
        enabled = screen !is Screen.Home &&
            screen !is Screen.Login &&
            screen !is Screen.Watchlist &&
            screen !is Screen.LibraryPage &&
            screen !is Screen.Search,
    ) {
        when (val s = screen) {
            Screen.Updates -> screen = Screen.Home
            is Screen.Show -> screen = if (s.fromActor != null) Screen.Actor(s.fromActor) else if (s.fromSearch) Screen.Search else if (s.fromWatchlist) Screen.Watchlist else if (s.fromHome) Screen.Home else if (activeLibrary?.type == "tv") Screen.LibraryPage(activeLibrary!!) else Screen.Home
            is Screen.Season -> screen = Screen.Show(s.show, fromHome = s.fromHome, fromSearch = s.fromSearch, fromWatchlist = s.fromWatchlist, fromActor = s.fromActor)
            is Screen.Detail -> returnFromDetail(s)
            is Screen.Actor -> screen = lastActorReturnScreen ?: Screen.Home
            is Screen.Player -> {
                screen = lastPlayerReturnScreen ?: lastDetail ?: Screen.Home
                lastPlayerReturnScreen = null
                session?.let { active -> refreshProgress(active) }
            }
            is Screen.SidecarPlayer -> screen = s.returnScreen
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
            continueMovies = continueMovies,
            continueEpisodes = continueEpisodes,
            recentMovies = recentMovies,
            recentShows = recentShows,
            watchlistMovies = watchlistMovies,
            watchlistTvShows = watchlistTvShows,
            error = error,
            loading = loading,
            showUpdate = updateAvailable,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onMoreContinueMovies = {
                val active = session
                val movieLib = libraries.firstOrNull { it.type == "movies" || it.type == "movie" }
                if (active != null && movieLib != null) {
                    loadLibraryPage(movieLib, active, page = 0, genre = "", sort = "", minRating = 0.0, seenStatus = "started", resetFiltersOnLibraryChange = false)
                }
            },
            onMoreContinueTv = {
                val active = session
                val tvLib = libraries.firstOrNull { it.type == "tv" }
                if (active != null && tvLib != null) {
                    loadLibraryPage(tvLib, active, page = 0, genre = "", sort = "", minRating = 0.0, seenStatus = "started", resetFiltersOnLibraryChange = false)
                }
            },
            onMoreRecentMovies = {
                val active = session
                val movieLib = libraries.firstOrNull { it.type == "movies" || it.type == "movie" }
                if (active != null && movieLib != null) {
                    loadLibraryPage(movieLib, active, page = 0, genre = "", sort = "mtime", minRating = 0.0, seenStatus = "", resetFiltersOnLibraryChange = false)
                }
            },
            onMoreRecentTv = {
                val active = session
                val tvLib = libraries.firstOrNull { it.type == "tv" }
                if (active != null && tvLib != null) {
                    loadLibraryPage(tvLib, active, page = 0, genre = "", sort = "mtime", minRating = 0.0, seenStatus = "", resetFiltersOnLibraryChange = false)
                }
            },
            onItem = { screen = Screen.Detail(it, null, fromHome = true) },
            onShow = {
                showFocusSeason = null
                screen = Screen.Show(it, fromHome = true)
            },
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
            showUpdate = updateAvailable,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onItem = { screen = Screen.Detail(it, null, fromWatchlist = true) },
            onShow = {
                showFocusSeason = null
                screen = Screen.Show(it, fromWatchlist = true)
            },
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
            selectedSort = selectedSort,
            selectedMinRating = selectedMinRating,
            selectedSeenStatus = selectedSeenStatus,
            genres = libraryGenres,
            initialFocusKey = libraryFocusKey,
            showUpdate = updateAvailable,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onPreviousPage = { session?.let { loadLibraryPage(current.library, it, pageIndex - 1) } },
            onNextPage = { session?.let { loadLibraryPage(current.library, it, pageIndex + 1) } },
            onGenre = { genre -> session?.let { loadLibraryPage(current.library, it, 0, genre, selectedSort, selectedMinRating, selectedSeenStatus) } },
            onSort = { sort -> session?.let { loadLibraryPage(current.library, it, 0, selectedGenre, sort, selectedMinRating, selectedSeenStatus) } },
            onMinRating = { minRating -> session?.let { loadLibraryPage(current.library, it, 0, selectedGenre, selectedSort, minRating, selectedSeenStatus) } },
            onSeenStatus = { seenStatus -> session?.let { loadLibraryPage(current.library, it, 0, selectedGenre, selectedSort, selectedMinRating, seenStatus) } },
            alphabet = libraryAlphabet,
            onAlphabet = { entry -> session?.let { loadLibraryPage(current.library, it, entry.offset, selectedGenre, "", selectedMinRating, selectedSeenStatus) } },
            onItem = {
                libraryFocusKey = it.id
                screen = Screen.Detail(it, null)
            },
            onShow = {
                libraryFocusKey = showFocusKey(it)
                showFocusSeason = null
                screen = Screen.Show(it)
            },
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
            onBack = ::closeSearch,
            onItem = { screen = Screen.Detail(it, null, fromSearch = true) },
            onShow = {
                showFocusSeason = null
                screen = Screen.Show(it, fromSearch = true)
            },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
            onShowMenu = { show, requester -> openShowWatchMenu(show, requester) },
        )
        Screen.Updates -> UpdateView(session = session, onBack = { screen = Screen.Home })
        is Screen.Show -> ShowView(
            session = session,
            show = current.show,
            initialSeasonFocus = showFocusSeason,
            initialEpisodeFocus = seasonFocusEpisode,
            startWithEpisodes = false,
            completedItems = completedItems,
            watchlistItems = watchlistItems,
            refreshToken = visibleContentRefresh,
            onSeason = { season ->
                showFocusSeason = season.seasonNumber
                seasonFocusEpisode = null
                screen = Screen.Season(current.show, season, fromHome = current.fromHome, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist, fromActor = current.fromActor)
            },
            onEpisodeFocus = {
                seasonFocusEpisode = it.id
            },
            onEpisode = { item ->
                lastDetail = null
                seasonFocusEpisode = item.id
                screen = Screen.Detail(item, current.show, fromHome = current.fromHome, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist, fromActor = current.fromActor)
            },
            onEpisodeMenu = { episode, requester -> openItemWatchMenu(episode, requester) },
            onActor = { actor ->
                lastActorReturnScreen = current
                screen = Screen.Actor(actor)
            },
        )
        is Screen.Season -> ShowView(
            session = session,
            show = current.show,
            initialSeasonFocus = current.season.seasonNumber,
            initialEpisodeFocus = seasonFocusEpisode,
            startWithEpisodes = true,
            completedItems = completedItems,
            watchlistItems = watchlistItems,
            refreshToken = visibleContentRefresh,
            onSeason = { season ->
                showFocusSeason = season.seasonNumber
                seasonFocusEpisode = null
                screen = Screen.Season(current.show, season, fromHome = current.fromHome, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist, fromActor = current.fromActor)
            },
            onEpisodeFocus = {
                seasonFocusEpisode = it.id
            },
            onEpisode = { item ->
                lastDetail = null
                seasonFocusEpisode = item.id
                screen = Screen.Detail(item, current.show, fromHome = current.fromHome, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist, fromActor = current.fromActor)
            },
            onEpisodeMenu = { episode, requester -> openItemWatchMenu(episode, requester) },
            onActor = { actor ->
                lastActorReturnScreen = current
                screen = Screen.Actor(actor)
            },
        )
        is Screen.Detail -> DetailView(
            item = current.item,
            session = session,
            watched = completedItems.contains(current.item.id),
            watchlisted = watchlistItems.contains(current.item.id),
            onPlay = { audioIndex, subtitleIndex, startPositionMs ->
                lastDetail = current
                lastPlayerReturnScreen = current
                screen = Screen.Player(current.item, audioIndex, subtitleIndex, startPositionMs)
            },
	            onTrailer = { localTrailer ->
	                if (localTrailer) {
	                    val title = if (current.item.kind == "episode") current.item.episodeTitle.ifBlank { current.item.title } else current.item.title
	                    screen = Screen.SidecarPlayer(trailerUrl(session, current.item.id), "$title Trailer", current)
	                } else {
	                    openExternalTrailer(current.item)
	                }
            },
            onWatchedChange = { watched ->
                session?.let { setItemWatched(it, current.item, watched) }
            },
            onWatchlistChange = { listed ->
                session?.let { setItemWatchlisted(it, current.item, listed) }
            },
            onBack = { returnFromDetail(current) },
            onHome = { screen = Screen.Home },
            onSearch = ::openSearch,
            onWatchlist = { screen = Screen.Watchlist },
            onActor = { actor ->
                lastActorReturnScreen = current
                screen = Screen.Actor(actor)
            },
        )
        is Screen.Actor -> ActorView(
            session = session,
            actor = current.actor,
            completedItems = completedItems,
            completedShows = completedShows,
            watchlistItems = watchlistItems,
            watchlistShows = watchlistShows,
            onBack = { screen = lastActorReturnScreen ?: Screen.Home },
            onItem = { screen = Screen.Detail(it, null, fromActor = current.actor) },
            onShow = {
                showFocusSeason = null
                screen = Screen.Show(it, fromActor = current.actor)
            },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
            onShowMenu = { show, requester -> openShowWatchMenu(show, requester) },
        )
        is Screen.Player -> PlayerScreen(
            item = current.item,
            session = session,
            deviceId = deviceId,
            remoteCommand = pendingPlayerCommand,
            initialAudioIndex = current.audioIndex,
            initialSubtitleIndex = current.subtitleIndex,
            initialStartPositionMs = current.startPositionMs,
            initialBandwidthKbps = preferredBandwidthKbps,
            onBandwidthSelected = { kbps ->
                preferredBandwidthKbps = kbps
                prefs.edit().putInt("preferredBandwidthKbps", kbps ?: -1).apply()
            },
            onRemoteStop = {
                pendingPlayerCommand = null
                screen = lastPlayerReturnScreen ?: lastDetail ?: Screen.Home
                lastPlayerReturnScreen = null
                session?.let { active -> refreshProgress(active) }
            },
            onRemoteCommandConsumed = { id ->
                if (pendingPlayerCommand?.id == id) pendingPlayerCommand = null
            },
        )
	        is Screen.SidecarPlayer -> SidecarPlayerScreen(
	            url = current.url,
	            title = current.title,
	            session = session,
	            onBack = { screen = current.returnScreen },
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
    if (updateDialogOpen) {
        UpdateApplyDialog(
            session = session,
            onDismiss = { updateDialogOpen = false },
            onUpdateStarted = { updateAvailable = false },
        )
    }
}

// ── Login ──

// ── Home ──
