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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (PlayerBackBridge.dispatch(event)) return true
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

data class WatchMenuState(
    val title: String,
    val watched: Boolean,
    val watchlisted: Boolean? = null,
    val onMarkWatched: () -> Unit,
    val onMarkUnwatched: () -> Unit,
    val onAddWatchlist: (() -> Unit)? = null,
    val onRemoveWatchlist: (() -> Unit)? = null,
    val restoreFocus: (() -> Unit)? = null,
)

@Composable
fun PopcornApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("popcorn", Context.MODE_PRIVATE) }
    remember { PlaybackPrefs.load(context) }
    val scope = rememberCoroutineScope()
    var session by remember {
        mutableStateOf(
            prefs.getString("token", null)?.let {
                Session(
                    prefs.getString("server", "http://10.0.2.2:8097") ?: "http://10.0.2.2:8097",
                    it,
                    prefs.getString("username", "") ?: "",
                    prefs.getBoolean("isAdmin", false),
                    prefs.getLong("userId", 0),
                    prefs.getString("avatar", "") ?: "",
                    prefs.getString("displayName", "") ?: "",
                )
            }
        )
    }
    var screen by remember { mutableStateOf<Screen>(if (session == null) Screen.Login else Screen.Loading) }
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var activeLibrary by remember { mutableStateOf<Library?>(null) }
    var items by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var shows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var homeSections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var homeEditDraft by remember { mutableStateOf<List<HomeLayoutSection>>(emptyList()) }
    var homeCatalog by remember { mutableStateOf<List<HomeSectionType>>(emptyList()) }
    var homeEditIndex by remember { mutableStateOf(0) }
    var homeEditGrabbed by remember { mutableStateOf(false) }
    var homeShelvesOpen by remember { mutableStateOf(false) }
    var homeGenrePicker by remember { mutableStateOf(false) }
    // Set when the picker was opened from a shelf's options: it then changes
    // that shelf's genre instead of adding and removing shelves.
    var homeGenreTarget by remember { mutableStateOf<Int?>(null) }
    var homeOptionsIndex by remember { mutableStateOf<Int?>(null) }
    var homeGenres by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var recommendationExclusionKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var completedItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var resumeFractionById by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    var completedShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var userItemRatings by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
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
    var selectedDecades by remember { mutableStateOf("") }
    var selectedSort by remember { mutableStateOf("") }
    var selectedMinRating by remember { mutableStateOf(0.0) }
    var selectedSeenStatus by remember { mutableStateOf("") }
    var libraryGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var libraryDecades by remember { mutableStateOf<List<Int>>(emptyList()) }
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
    var lastScanSignature by remember { mutableStateOf("") }

    fun showKey(show: ShowSummary): String = "${show.libraryId}\n${show.title.lowercase()}"

    fun refreshProgress(activeSession: Session) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                val progress = api.progressList()
                val showProgress = api.showProgress()
                completedItems = progress.filter { it.completed }.map { it.itemId }.toSet()
                resumeFractionById = resumeFractionMap(progress)
                completedShows = showProgress.filter { it.completed }.map { "${it.libraryId}\n${it.showTitle.lowercase()}" }.toSet()
                userItemRatings = api.userRatings().filter { it.kind != "show" && it.itemId > 0 }.associate { it.itemId to it.rating }
            }
        }
    }

    fun setItemRating(activeSession: Session, item: PopItem, rating: Int) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                if (rating > 0) api.setItemRating(item.id, rating) else api.deleteItemRating(item.id)
            }.onSuccess {
                userItemRatings = if (rating > 0) userItemRatings + (item.id to rating) else userItemRatings - item.id
            }.onFailure {
                error = it.message ?: "Failed to save rating"
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

    fun refreshUpdateAvailable(activeSession: Session) {
        scope.launch {
            updateAvailable = runCatching { Api(activeSession).tvUpdate(appVersionCode(context)).available }.getOrDefault(false)
        }
    }

    fun applyHomePayload(activeSession: Session, payload: HomePayload, generation: Int? = null) {
        if (generation != null && generation != loadGeneration) return
        libraries = payload.libraries
        if (payload.user.username.isNotBlank() && (activeSession.username != payload.user.username || activeSession.displayName != payload.user.displayName || activeSession.isAdmin != payload.user.isAdmin || activeSession.userId != payload.user.id || activeSession.avatar != payload.user.avatar)) {
            val updated = activeSession.copy(username = payload.user.username, displayName = payload.user.displayName, isAdmin = payload.user.isAdmin, userId = payload.user.id, avatar = payload.user.avatar)
            prefs.edit().putString("username", payload.user.username).putString("displayName", payload.user.displayName).putBoolean("isAdmin", payload.user.isAdmin).putLong("userId", payload.user.id).putString("avatar", payload.user.avatar).apply()
            session = updated
        }
        homeSections = payload.sections
        recommendationExclusionKeys = payload.excludedRecommendationKeys
        completedItems = payload.progress.filter { it.completed }.map { it.itemId }.toSet()
        resumeFractionById = resumeFractionMap(payload.progress)
        completedShows = payload.showProgress.filter { it.completed }.map { "${it.libraryId}\n${it.showTitle.lowercase()}" }.toSet()
        watchlistMovies = payload.watchlist.items.filter { it.kind == "movie" }
        watchlistTvShows = payload.watchlist.shows
        watchlistItems = payload.watchlist.items.map { it.id }.toSet()
        watchlistShows = payload.watchlist.shows.map { showKey(it) }.toSet()
        AppCache.writeHomeSections(context, activeSession, payload.sectionsJson)
        AppCache.writeLibraries(context, activeSession, payload.libraries)
        screen = Screen.Home
        loading = false
        error = ""
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

    fun setSeasonWatched(activeSession: Session, season: SeasonSummary, watched: Boolean) {
        scope.launch {
            runCatching {
                val api = Api(activeSession)
                if (watched) {
                    api.markSeasonWatched(season.libraryId, season.showTitle, season.seasonNumber)
                } else {
                    api.unmarkSeasonWatched(season.libraryId, season.showTitle, season.seasonNumber)
                }
            }.onSuccess {
                visibleContentRefresh++
                refreshProgress(activeSession)
            }.onFailure {
                error = it.message ?: "Failed to update season watched state"
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

    // "Not interested" hides the entry straight away; the next home refresh
    // rebuilds the shelf without it.
    fun dropRecommendation(sections: List<HomeSection>, key: String): List<HomeSection> {
        return sections
            .map { section -> section.copy(entries = section.entries.filterNot { it.key == key }) }
            .filter { it.entries.isNotEmpty() || it.items.isNotEmpty() || it.shows.isNotEmpty() }
    }

    fun setItemRecommendationExcluded(activeSession: Session, item: PopItem, excluded: Boolean) {
        scope.launch {
            val key = "item:${item.id}"
            runCatching {
                val api = Api(activeSession)
                if (excluded) api.excludeItemRecommendation(item.id) else api.restoreItemRecommendation(item.id)
            }.onSuccess {
                recommendationExclusionKeys = if (excluded) recommendationExclusionKeys + key else recommendationExclusionKeys - key
                homeSections = dropRecommendation(homeSections, key)
            }.onFailure { error = it.message ?: "Failed to update recommendations" }
        }
    }

    fun setShowRecommendationExcluded(activeSession: Session, show: ShowSummary, excluded: Boolean) {
        scope.launch {
            val key = "show:${show.libraryId.trim().lowercase()}:${show.title.trim().lowercase()}"
            runCatching {
                val api = Api(activeSession)
                if (excluded) api.excludeShowRecommendation(show.libraryId, show.title) else api.restoreShowRecommendation(show.libraryId, show.title)
            }.onSuccess {
                recommendationExclusionKeys = if (excluded) recommendationExclusionKeys + key else recommendationExclusionKeys - key
                homeSections = dropRecommendation(homeSections, key)
            }.onFailure { error = it.message ?: "Failed to update recommendations" }
        }
    }

    fun setItemPreference(activeSession: Session, item: PopItem, preference: MediaPreference) {
        scope.launch {
            val key = "item:${item.id}"
            runCatching {
                val api = Api(activeSession)
                when (preference) {
                    MediaPreference.Unwatched -> {
                        api.restoreItemRecommendation(item.id)
                        api.unmarkItemWatched(item.id)
                    }
                    MediaPreference.Seen -> {
                        api.restoreItemRecommendation(item.id)
                        api.markItemWatched(item)
                    }
                    MediaPreference.NotInterested -> {
                        api.unmarkItemWatched(item.id)
                        api.excludeItemRecommendation(item.id)
                    }
                }
            }.onSuccess {
                completedItems = if (preference == MediaPreference.Seen) completedItems + item.id else completedItems - item.id
                recommendationExclusionKeys = if (preference == MediaPreference.NotInterested) recommendationExclusionKeys + key else recommendationExclusionKeys - key
                if (preference == MediaPreference.NotInterested) homeSections = dropRecommendation(homeSections, key)
                refreshProgress(activeSession)
            }.onFailure { error = it.message ?: "Failed to update viewing preference" }
        }
    }

    fun setShowPreference(activeSession: Session, show: ShowSummary, preference: MediaPreference) {
        scope.launch {
            val marker = showKey(show)
            val key = "show:${show.libraryId.trim().lowercase()}:${show.title.trim().lowercase()}"
            runCatching {
                val api = Api(activeSession)
                when (preference) {
                    MediaPreference.Unwatched -> {
                        api.restoreShowRecommendation(show.libraryId, show.title)
                        api.unmarkShowWatched(show.libraryId, show.title)
                    }
                    MediaPreference.Seen -> {
                        api.restoreShowRecommendation(show.libraryId, show.title)
                        api.markShowWatched(show.libraryId, show.title)
                    }
                    MediaPreference.NotInterested -> {
                        api.unmarkShowWatched(show.libraryId, show.title)
                        api.excludeShowRecommendation(show.libraryId, show.title)
                    }
                }
            }.onSuccess {
                completedShows = if (preference == MediaPreference.Seen) completedShows + marker else completedShows - marker
                recommendationExclusionKeys = if (preference == MediaPreference.NotInterested) recommendationExclusionKeys + key else recommendationExclusionKeys - key
                if (preference == MediaPreference.NotInterested) homeSections = dropRecommendation(homeSections, key)
                refreshProgress(activeSession)
            }.onFailure { error = it.message ?: "Failed to update viewing preference" }
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
                                // Honour an explicit position from the remote so
                                // "Start over" really starts over. Without one
                                // (older companion builds), resume like every
                                // other play path here does — casting used to
                                // restart a half-watched item from zero.
                                val start = command.payload.optLongOrNull("positionMs")
                                    ?: runCatching { Api(activeSession).progress(itemId) }.getOrNull()
                                        ?.takeIf { progressResumable(it.positionMs, it.durationMs) }
                                        ?.positionMs
                                    ?: 0L
                                screen = Screen.Player(item, audio, subtitle, start, token = command.id)
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

    fun openSeasonWatchMenu(season: SeasonSummary, focusRequester: FocusRequester?) {
        val active = session ?: return
        scope.launch {
            runCatching { Api(active).episodes(season.libraryId, season.showTitle, season.seasonNumber) }
                .onSuccess { seasonEpisodes ->
                    val watched = seasonEpisodes.isNotEmpty() && seasonEpisodes.all { it.id in completedItems }
                    watchMenu = WatchMenuState(
                        title = season.title.ifBlank { "Season ${season.seasonNumber}" },
                        watched = watched,
                        onMarkWatched = { setSeasonWatched(active, season, true) },
                        onMarkUnwatched = { setSeasonWatched(active, season, false) },
                        restoreFocus = { focusRequester?.requestFocus() },
                    )
                }
                .onFailure { error = it.message ?: "Failed to load season options" }
        }
    }

    fun scanSignature(statuses: List<ScanStatus>): String {
        return statuses
            .filter { it.finishedAt.isNotBlank() }
            .sortedBy { it.libraryId }
            .joinToString("|") { status ->
                listOf(status.libraryId, status.finishedAt, status.status, status.mediaFound, status.itemsImported, status.errors).joinToString(":")
            }
    }

    suspend fun waitForLibraryUpdate(api: Api) {
        val previous = lastScanSignature
        var sawRunning = false
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 30L * 60L * 1000L) {
            delay(1500)
            val statuses = runCatching { api.scanStatus() }.getOrNull() ?: continue
            val running = statuses.any { it.status == "running" }
            sawRunning = sawRunning || running
            val signature = scanSignature(statuses)
            if (signature.isNotBlank() && signature != previous && !running) {
                lastScanSignature = signature
                return
            }
            if (sawRunning && !running) {
                if (signature.isNotBlank()) lastScanSignature = signature
                return
            }
        }
    }

    LaunchedEffect(session, deviceId) {
        val active = session ?: return@LaunchedEffect
        delay(1500)
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
            loading = homeSections.isEmpty()
            error = ""
            screen = Screen.Home
            runCatching { Api(activeSession).home() }
                .onSuccess { applyHomePayload(activeSession, it, generation) }
                .onFailure { error = it.message ?: "Load failed" }
            if (generation == loadGeneration) loading = false
        }
    }

    fun loadLibraryPage(library: Library, activeSession: Session, page: Int = 0, genre: String = selectedGenre, sort: String = selectedSort, minRating: Double = selectedMinRating, seenStatus: String = selectedSeenStatus, decades: String = selectedDecades, resetFiltersOnLibraryChange: Boolean = true, preserveFocusKey: Boolean = false) {
        loadGeneration += 1
        val generation = loadGeneration
        if (!preserveFocusKey) libraryFocusKey = null
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
            val activeDecades = if (shouldResetFilters) "" else decades
            selectedGenre = activeGenre
            selectedSort = activeSort
            selectedMinRating = activeMinRating
            selectedSeenStatus = activeSeenStatus
            selectedDecades = activeDecades
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
                libraryDecades = api.decades(library.id, if (library.type == "tv") "tv" else "movie")
                libraryAlphabet = if (activeSort.isBlank()) api.alphabet(library.id, if (library.type == "tv") "tv" else "movie", activeGenre, activeDecades) else emptyList()
                if (library.type == "tv") {
                    val pageItems = api.showsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus, activeDecades)
                    if (generation != loadGeneration) return@launch
                    shows = pageItems
                    pageHasNext = pageItems.size == pageSize
                    libraryFullyLoaded = pageItems.size < pageSize
                } else {
                    val pageItems = api.itemsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus, activeDecades)
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
                        val canUpdateLibraryCache = pageIndex == 0 && activeGenre.isBlank() && activeSort.isBlank() && activeMinRating <= 0.0 && activeSeenStatus.isBlank() && activeDecades.isBlank()
                        var offset = pageIndex + if (library.type == "tv") shows.size else items.size
                        while (true) {
                            if (library.type == "tv") {
                                val pageItems = api.showsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus, activeDecades)
                                if (generation != loadGeneration) return@launch
                                shows = (shows + pageItems).distinctBy { showKey(it) }
                                if (canUpdateLibraryCache) {
                                    AppCache.writeShows(context, activeSession, library.id, shows, pageItems.size < pageSize)
                                }
                                if (pageItems.size < pageSize) break
                                offset += pageSize
                            } else {
                                val pageItems = api.itemsPage(library.id, pageSize, offset, activeGenre, activeSort, activeMinRating, activeSeenStatus, activeDecades)
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

    fun refreshVisibleContent(activeSession: Session) {
        when (val current = screen) {
            Screen.Home -> if (libraries.isNotEmpty()) loadHome(activeSession, libraries)
            Screen.Watchlist -> refreshWatchlist(activeSession)
            is Screen.LibraryPage -> loadLibraryPage(current.library, activeSession, pageIndex, selectedGenre, selectedSort, selectedMinRating, selectedSeenStatus, preserveFocusKey = true)
            is Screen.Show, is Screen.Season -> visibleContentRefresh += 1
            is Screen.Detail -> scope.launch {
                runCatching { Api(activeSession).item(current.item.id) }
                    .onSuccess { refreshed -> screen = current.copy(item = refreshed) }
            }
            else -> Unit
        }
    }

    fun scanLibraries() {
        val active = session ?: return
        if (!active.isAdmin) return
        scope.launch {
            runCatching {
                val api = Api(active)
                api.scanLibraries()
                error = "Library update started"
                waitForLibraryUpdate(api)
                refreshVisibleContent(active)
            }
                .onFailure { error = it.message ?: "Library update failed" }
        }
    }

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        val api = Api(active)
        lastScanSignature = runCatching { scanSignature(api.scanStatus()) }.getOrDefault("")
        while (true) {
            delay(4_000)
            val statuses = runCatching { api.scanStatus() }.getOrNull() ?: continue
            if (statuses.any { it.status == "running" }) continue
            val signature = scanSignature(statuses)
            if (signature.isBlank()) continue
            if (lastScanSignature.isBlank()) {
                lastScanSignature = signature
                continue
            }
            if (signature != lastScanSignature) {
                lastScanSignature = signature
                refreshVisibleContent(active)
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
                        is Screen.LibraryPage -> loadLibraryPage(current.library, activeSession, pageIndex, selectedGenre, selectedSort, selectedMinRating, selectedSeenStatus, preserveFocusKey = true)
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
        delay(8000)
        while (true) {
            updateAvailable = runCatching { Api(active).tvUpdate(appVersionCode(context)).available }.getOrDefault(false)
            delay(5 * 60 * 1000)
        }
    }

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        loading = true
        val cachedLibraries = withContext(Dispatchers.IO) { AppCache.readLibraries(context, active) }
        if (cachedLibraries.isNotEmpty()) {
            libraries = cachedLibraries
            homeSections = withContext(Dispatchers.IO) { AppCache.readHomeSections(context, active) }
            if (homeSections.isNotEmpty()) {
                screen = Screen.Home
                loading = false
            }
        }
        runCatching { Api(active).home() }
            .onSuccess { applyHomePayload(active, it) }
            .onFailure {
                error = it.message ?: "Server unavailable"
                // Cached sections are better than bouncing to login when the
                // server is merely unreachable.
                if (homeSections.isEmpty()) screen = Screen.Login
                loading = false
            }
    }

    // The "More" chip on a shelf. Known targets open the screen that shows the
    // whole set; anything else falls back to a plain shelf of what the section
    // carries, so a section type this build predates still works.
    fun openShelfTarget(section: HomeSection) {
        val active = session ?: return
        when (section.more) {
            "recent/movies" -> libraries.firstOrNull { it.type == "movies" || it.type == "movie" }?.let { library ->
                loadLibraryPage(library, active, page = 0, genre = "", sort = "mtime", minRating = 0.0, seenStatus = "", decades = "", resetFiltersOnLibraryChange = false)
            }
            "recent/tv" -> libraries.firstOrNull { it.type == "tv" }?.let { library ->
                loadLibraryPage(library, active, page = 0, genre = "", sort = "mtime", minRating = 0.0, seenStatus = "", decades = "", resetFiltersOnLibraryChange = false)
            }
            "watchlist" -> {
                refreshWatchlist(active)
                screen = Screen.Watchlist
            }
            else -> if (section.items.isNotEmpty()) screen = Screen.ItemShelf(section.title, section.items)
        }
    }

    // The arrange screen works on the layout document, not on the rendered
    // shelves: the server owns what a shelf contains, the account owns which
    // shelves there are and in what order.
    fun startHomeArrange(activeSession: Session) {
        scope.launch {
            val api = Api(activeSession)
            val catalog = runCatching { api.homeCatalog() }.getOrNull()
            val layout = runCatching { api.homeLayout() }.getOrNull()
            if (catalog == null || layout == null) {
                error = "Could not load the shelf list"
                return@launch
            }
            homeCatalog = catalog
            homeEditDraft = withUniqueIds(layout.sections)
            homeEditIndex = 0
            homeEditGrabbed = false
            homeShelvesOpen = false
            homeGenrePicker = false
            homeOptionsIndex = null
            screen = Screen.ArrangeHome
        }
    }

    fun finishHomeArrange(activeSession: Session) {
        val draft = homeEditDraft
        homeEditGrabbed = false
        homeShelvesOpen = false
        homeGenrePicker = false
        homeGenreTarget = null
        homeOptionsIndex = null
        screen = Screen.Home
        scope.launch {
            runCatching { Api(activeSession).saveHomeLayout(draft) }
                .onSuccess { loadHome(activeSession, libraries) }
                .onFailure { error = it.message ?: "Could not save the layout" }
        }
    }

    fun loadHomeGenres(activeSession: Session) {
        if (homeGenres.isNotEmpty()) return
        scope.launch {
            val api = Api(activeSession)
            val movieLib = libraries.firstOrNull { it.type == "movies" || it.type == "movie" }
            val tvLib = libraries.firstOrNull { it.type == "tv" }
            val rows = mutableListOf<Pair<String, String>>()
            movieLib?.let { library ->
                runCatching { api.genres(library.id) }.getOrDefault(emptyList()).forEach { rows.add(it to "movies") }
            }
            tvLib?.let { library ->
                runCatching { api.genres(library.id) }.getOrDefault(emptyList()).forEach { rows.add(it to "tv") }
            }
            homeGenres = rows
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
        } else if (s.fromHistory) {
            Screen.History
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

    fun returnFromPlayer() {
        val active = session
        val player = screen as? Screen.Player
        if (active != null && player != null) {
            scope.launch {
                runCatching {
                    Api(active).clientLog(
                        JSONObject()
                            .put("deviceId", deviceId)
                            .put("deviceName", shieldDeviceName())
                            .put("event", "return-from-player")
                            .put("screen", "main")
                            .put("itemId", player.item.id)
                            .put("title", if (player.item.kind == "episode") player.item.episodeTitle.ifBlank { player.item.title } else player.item.title)
                            .put("message", lastPlayerReturnScreen?.javaClass?.simpleName ?: lastDetail?.javaClass?.simpleName.orEmpty()),
                    )
                }
            }
        }
        screen = lastPlayerReturnScreen ?: lastDetail ?: Screen.Home
        lastPlayerReturnScreen = null
        session?.let { active -> refreshProgress(active) }
    }

    fun playEpisode(item: PopItem, returnScreen: Screen) {
        val active = session ?: return
        scope.launch {
            val progress = runCatching { Api(active).progress(item.id) }.getOrNull()
            val startPosition = progress
                ?.takeIf { progressResumable(it.positionMs, it.durationMs) }
                ?.positionMs
                ?: 0L
            lastPlayerReturnScreen = returnScreen
            screen = Screen.Player(item, audioIndex = null, subtitleIndex = null, startPositionMs = startPosition)
        }
    }

    // Back unwinds the arrange screen one step at a time — picker, checklist,
    // a held shelf — and only then saves and returns home.
    BackHandler(enabled = screen is Screen.ArrangeHome) {
        when {
            homeGenrePicker -> {
                homeGenrePicker = false
                homeGenreTarget = null
            }
            homeOptionsIndex != null -> homeOptionsIndex = null
            homeShelvesOpen -> homeShelvesOpen = false
            homeEditGrabbed -> homeEditGrabbed = false
            else -> session?.let { finishHomeArrange(it) }
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
            is Screen.ItemShelf -> screen = s.returnTo ?: Screen.Home
            is Screen.Actor -> screen = lastActorReturnScreen ?: Screen.Home
            is Screen.Player -> returnFromPlayer()
            is Screen.SidecarPlayer -> screen = s.returnScreen
            else -> screen = Screen.Home
        }
    }

    CompositionLocalProvider(LocalResumeProgress provides resumeFractionById) {
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
                            prefs.edit().putString("server", it.server).putString("token", it.token).putString("username", it.username).putString("displayName", it.displayName).putBoolean("isAdmin", it.isAdmin).putLong("userId", it.userId).putString("avatar", it.avatar).apply()
                            session = it
                            screen = Screen.Loading
                        }
                        .onFailure { error = it.message ?: "Login failed" }
                    loading = false
                }
            },
            onQrLogin = {
                prefs.edit().putString("server", it.server).putString("token", it.token).putString("username", it.username).putString("displayName", it.displayName).putBoolean("isAdmin", it.isAdmin).putLong("userId", it.userId).putString("avatar", it.avatar).apply()
                session = it
                screen = Screen.Loading
            },
        )
        Screen.Home -> HomeView(
            session = session,
            libraries = libraries,
            sections = homeSections,
            onArrange = { session?.let { startHomeArrange(it) } },
            completedItems = completedItems,
            completedShows = completedShows,
            watchlistItems = watchlistItems,
            watchlistShows = watchlistShows,
            error = error,
            loading = loading,
            showUpdate = updateAvailable,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onHistory = { screen = Screen.History },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onMore = ::openShelfTarget,
            onPlayItem = { item ->
                scope.launch {
                    val progress = session?.let { active -> runCatching { Api(active).progress(item.id) }.getOrNull() }
                    val startPosition = progress
                        ?.takeIf { progressResumable(it.positionMs, it.durationMs) }
                        ?.positionMs
                        ?: 0L
                    lastPlayerReturnScreen = Screen.Home
                    screen = Screen.Player(item, audioIndex = null, subtitleIndex = null, startPositionMs = startPosition)
                }
            },
            onPlayShow = { show ->
                scope.launch {
                    val active = session
                    val episodes = if (active != null) {
                        runCatching { Api(active).episodes(show.libraryId, show.title) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    val episode = episodes.firstOrNull { it.id !in completedItems } ?: episodes.firstOrNull()
                    if (episode != null) {
                        val progress = active?.let { runCatching { Api(it).progress(episode.id) }.getOrNull() }
                        val startPosition = progress
                            ?.takeIf { progressResumable(it.positionMs, it.durationMs) }
                            ?.positionMs
                            ?: 0L
                        lastPlayerReturnScreen = Screen.Home
                        screen = Screen.Player(episode, audioIndex = null, subtitleIndex = null, startPositionMs = startPosition)
                    } else {
                        showFocusSeason = null
                        screen = Screen.Show(show, fromHome = true)
                    }
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
        Screen.ArrangeHome -> ArrangeHomeView(
            session = session,
            draft = homeEditDraft,
            catalog = homeCatalog,
            rendered = homeSections,
            focusedIndex = homeEditIndex.coerceIn(0, maxOf(homeEditDraft.lastIndex, 0)),
            grabbed = homeEditGrabbed,
            onFocusIndex = { index -> if (!homeEditGrabbed) homeEditIndex = index },
            onToggleGrab = { homeEditGrabbed = !homeEditGrabbed },
            onMove = { from, to ->
                homeEditDraft = moveSection(homeEditDraft, from, to)
                homeEditIndex = to
            },
            onRemove = { index ->
                homeEditDraft = homeEditDraft.filterIndexed { position, _ -> position != index }
                homeEditIndex = index.coerceAtMost(maxOf(homeEditDraft.lastIndex, 0))
                homeEditGrabbed = false
            },
            onAdd = { homeShelvesOpen = true },
            onOptions = { index -> homeOptionsIndex = index },
            onDone = { session?.let { finishHomeArrange(it) } },
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
            onHistory = { screen = Screen.History },
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
        Screen.History -> HistoryView(
            session = session,
            libraries = libraries,
            showUpdate = updateAvailable,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { library -> session?.let { loadLibraryPage(library, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onHistory = { screen = Screen.History },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onItem = { screen = Screen.Detail(it, null, fromHistory = true) },
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
            selectedDecades = selectedDecades,
            selectedSort = selectedSort,
            selectedMinRating = selectedMinRating,
            selectedSeenStatus = selectedSeenStatus,
            genres = libraryGenres,
            decades = libraryDecades,
            initialFocusKey = libraryFocusKey,
            showUpdate = updateAvailable,
            onHome = { session?.let { loadHome(it, libraries) } },
            onLibrary = { lib -> session?.let { loadLibraryPage(lib, it, 0, "") } },
            onWatchlist = {
                session?.let { refreshWatchlist(it) }
                screen = Screen.Watchlist
            },
            onHistory = { screen = Screen.History },
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
            onFilters = { sort, seenStatus, minRating, genre, decades ->
                session?.let { loadLibraryPage(current.library, it, 0, genre, sort, minRating, seenStatus, decades = decades) }
            },
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
        is Screen.ItemShelf -> ItemShelfView(
            session = session,
            title = current.title,
            items = current.items,
            completedItems = completedItems,
            watchlistItems = watchlistItems,
            onBack = { screen = current.returnTo ?: Screen.Home },
            onItem = { screen = Screen.Detail(it, null, fromHome = true) },
            onItemMenu = { item, requester -> openItemWatchMenu(item, requester) },
        )
        is Screen.Show -> ShowView(
            session = session,
            show = current.show,
            libraries = libraries,
            showUpdate = updateAvailable,
            initialSeasonFocus = showFocusSeason,
            initialEpisodeFocus = seasonFocusEpisode,
            startWithEpisodes = false,
            completedItems = completedItems,
            watchlistItems = watchlistItems,
            showWatched = completedShows.contains(showKey(current.show)),
            showWatchlisted = watchlistShows.contains(showKey(current.show)),
            recommendationExcluded = recommendationExclusionKeys.contains("show:${current.show.libraryId.trim().lowercase()}:${current.show.title.trim().lowercase()}"),
            refreshToken = visibleContentRefresh,
            onHome = { screen = Screen.Home },
            onLibrary = { library -> session?.let { loadLibraryPage(library, it, 0, "") } },
            onWatchlist = { screen = Screen.Watchlist },
            onHistory = { screen = Screen.History },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onShowWatchlistChange = {
                session?.let { active -> setShowWatchlisted(active, current.show, !watchlistShows.contains(showKey(current.show))) }
            },
            onPreferenceChange = { preference -> session?.let { active -> setShowPreference(active, current.show, preference) } },
            onSeason = { season ->
                showFocusSeason = season.seasonNumber
                seasonFocusEpisode = null
            },
            onSeasonMenu = { season, requester -> openSeasonWatchMenu(season, requester) },
            onEpisodeFocus = {
                seasonFocusEpisode = it.id
            },
            onPlayEpisode = { item ->
                seasonFocusEpisode = item.id
                playEpisode(item, current)
            },
            onEpisode = { item ->
                lastDetail = null
                seasonFocusEpisode = item.id
                screen = Screen.Detail(item, current.show, fromHome = current.fromHome, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist, fromActor = current.fromActor)
            },
            onEpisodeMenu = { episode, requester -> openItemWatchMenu(episode, requester) },
        )
        is Screen.Season -> ShowView(
            session = session,
            show = current.show,
            libraries = libraries,
            showUpdate = updateAvailable,
            initialSeasonFocus = current.season.seasonNumber,
            initialEpisodeFocus = seasonFocusEpisode,
            startWithEpisodes = true,
            completedItems = completedItems,
            watchlistItems = watchlistItems,
            showWatched = completedShows.contains(showKey(current.show)),
            showWatchlisted = watchlistShows.contains(showKey(current.show)),
            recommendationExcluded = recommendationExclusionKeys.contains("show:${current.show.libraryId.trim().lowercase()}:${current.show.title.trim().lowercase()}"),
            refreshToken = visibleContentRefresh,
            onHome = { screen = Screen.Home },
            onLibrary = { library -> session?.let { loadLibraryPage(library, it, 0, "") } },
            onWatchlist = { screen = Screen.Watchlist },
            onHistory = { screen = Screen.History },
            onSearch = ::openSearch,
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onShowWatchlistChange = {
                session?.let { active -> setShowWatchlisted(active, current.show, !watchlistShows.contains(showKey(current.show))) }
            },
            onPreferenceChange = { preference -> session?.let { active -> setShowPreference(active, current.show, preference) } },
            onSeason = { season ->
                showFocusSeason = season.seasonNumber
                seasonFocusEpisode = null
            },
            onSeasonMenu = { season, requester -> openSeasonWatchMenu(season, requester) },
            onEpisodeFocus = {
                seasonFocusEpisode = it.id
            },
            onPlayEpisode = { item ->
                seasonFocusEpisode = item.id
                playEpisode(item, current)
            },
            onEpisode = { item ->
                lastDetail = null
                seasonFocusEpisode = item.id
                screen = Screen.Detail(item, current.show, fromHome = current.fromHome, fromSearch = current.fromSearch, fromWatchlist = current.fromWatchlist, fromActor = current.fromActor)
            },
            onEpisodeMenu = { episode, requester -> openItemWatchMenu(episode, requester) },
        )
        is Screen.Detail -> DetailView(
            item = current.item,
            session = session,
            libraries = libraries,
            showUpdate = updateAvailable,
            watched = completedItems.contains(current.item.id),
            watchlisted = watchlistItems.contains(current.item.id),
            recommendationExcluded = current.item.kind == "movie" && recommendationExclusionKeys.contains("item:${current.item.id}"),
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
            onWatchlistChange = { listed ->
                session?.let { setItemWatchlisted(it, current.item, listed) }
            },
            onPreferenceChange = { preference -> session?.let { setItemPreference(it, current.item, preference) } },
            onResetProgress = { session?.let { setItemWatched(it, current.item, false) } },
            userRating = userItemRatings[current.item.id] ?: 0,
            onRate = { value -> session?.let { setItemRating(it, current.item, value) } },
            onHome = { screen = Screen.Home },
            onLibrary = { library -> session?.let { loadLibraryPage(library, it, 0, "") } },
            onSearch = ::openSearch,
            onWatchlist = { screen = Screen.Watchlist },
            onHistory = { screen = Screen.History },
            onUpdates = { updateDialogOpen = true },
            onScan = ::scanLibraries,
            onLogout = {
                prefs.edit().clear().apply()
                session = null
                screen = Screen.Login
            },
            onActor = { actor ->
                lastActorReturnScreen = current
                screen = Screen.Actor(actor)
            },
            onMoreLikeThis = { items ->
                screen = Screen.ItemShelf("More like this", items, returnTo = current)
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
        is Screen.Player -> key(current.item.id, current.token) {
            PlayerScreen(
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
                onBack = ::returnFromPlayer,
                onRemoteStop = {
                    pendingPlayerCommand = null
                    // The phone sends "stop" and then "playItem" a moment later, so
                    // this teardown can finish after the next item is already on
                    // screen. Returning then would navigate away from a player that
                    // just started and leave the details page showing instead — the
                    // reason a remote play used to need pressing play twice.
                    if (screen === current) returnFromPlayer()
                },
                onRemoteCommandConsumed = { id ->
                    if (pendingPlayerCommand?.id == id) pendingPlayerCommand = null
                },
                onPlayNext = { next ->
                    pendingPlayerCommand = null
                    screen = Screen.Player(next, null, null, 0L)
                },
            )
        }
            is Screen.SidecarPlayer -> SidecarPlayerScreen(
                url = current.url,
                title = current.title,
                session = session,
                onBack = { screen = current.returnScreen },
            )
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
                menu.onAddWatchlist?.invoke()
            },
            onRemoveWatchlist = {
                closeWatchMenu(menu)
                menu.onRemoveWatchlist?.invoke()
            },
            onDismiss = { closeWatchMenu(menu) },
        )
    }
    if (homeShelvesOpen && !homeGenrePicker) {
        val genreType = homeCatalog.firstOrNull { it.repeatable && it.params.any { param -> param.name == "genre" } }
        TvCheckListShelf(
            title = "SHELVES",
            subtitle = "What home shows",
            rows = homeCatalog.map { type ->
                if (type.type == genreType?.type) {
                    val count = homeEditDraft.count { it.type == type.type }
                    TvCheckRow(
                        key = type.type,
                        label = type.label,
                        description = if (count > 0) "$count on home · pick genres" else "Pick one or more genres",
                        checked = count > 0,
                        onToggle = {
                            session?.let { loadHomeGenres(it) }
                            homeGenrePicker = true
                        },
                    )
                } else {
                    TvCheckRow(
                        key = type.type,
                        label = type.label,
                        description = type.description,
                        checked = homeEditDraft.any { it.type == type.type },
                        onToggle = { homeEditDraft = withUniqueIds(toggleSectionType(homeEditDraft, type)) },
                    )
                }
            },
            onDismiss = { homeShelvesOpen = false },
        )
    }

    homeOptionsIndex?.let { optionsIndex ->
        val section = homeEditDraft.getOrNull(optionsIndex)
        val definition = section?.let { current -> homeCatalog.firstOrNull { it.type == current.type } }
        if (section == null || definition == null) {
            homeOptionsIndex = null
        } else {
            TvOptionsShelf(
                title = "SHELF OPTIONS",
                subtitle = sectionLabel(section, definition),
                rows = definition.params.map { param ->
                    val value = section.params[param.name]?.ifBlank { null } ?: param.default
                    TvOptionRow(
                        key = param.name,
                        label = param.label.ifBlank { param.name },
                        value = when {
                            param.name == "genre" -> value.ifBlank { "Choose…" }
                            param.type == "int" -> "$value items"
                            else -> value.ifBlank { "—" }
                        },
                        onCycle = {
                            if (param.name == "genre") {
                                session?.let { loadHomeGenres(it) }
                                homeGenreTarget = optionsIndex
                                homeGenrePicker = true
                            } else {
                                homeEditDraft = updateSection(homeEditDraft, optionsIndex) { cycleParam(it, param) }
                            }
                        },
                    )
                },
                onDismiss = { homeOptionsIndex = null },
            )
        }
    }

    if (homeGenrePicker) {
        val genreType = homeCatalog.firstOrNull { it.repeatable && it.params.any { param -> param.name == "genre" } }
        val target = homeGenreTarget
        TvCheckListShelf(
            title = if (target != null) "GENRE" else "GENRE SHELVES",
            subtitle = if (target != null) "Which genre" else "One shelf per genre",
            rows = homeGenres.map { (genre, kind) ->
                val current = target?.let { homeEditDraft.getOrNull(it) }
                TvCheckRow(
                    key = "$kind:$genre",
                    label = genre,
                    description = if (kind == "tv") "TV shows" else "Movies",
                    checked = if (current != null) {
                        current.params["genre"] == genre && genreKind(current) == kind
                    } else {
                        homeEditDraft.any { it.type == genreType?.type && it.params["genre"] == genre && genreKind(it) == kind }
                    },
                    onToggle = {
                        if (target != null) {
                            homeEditDraft = updateSection(homeEditDraft, target) { section ->
                                section.copy(params = section.params + mapOf("genre" to genre, "kind" to kind))
                            }
                            homeGenrePicker = false
                            homeGenreTarget = null
                        } else {
                            val type = genreType ?: return@TvCheckRow
                            homeEditDraft = withUniqueIds(toggleGenreSection(homeEditDraft, type, genre, kind))
                        }
                    },
                )
            },
            onDismiss = {
                homeGenrePicker = false
                homeGenreTarget = null
            },
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
