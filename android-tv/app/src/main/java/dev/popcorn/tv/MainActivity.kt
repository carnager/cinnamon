package dev.popcorn.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.max
import java.util.Locale

private val Bg = Color(0xFF08090C)
private val SurfaceColor = Color(0xFF111319)
private val Surface2 = Color(0xFF181C26)
private val Surface3 = Color(0xFF1E2331)
private val Line = Color(0xFF283043)
private val TextColor = Color(0xFFE8ECF2)
private val Muted = Color(0xFF8B93A5)
private val Accent = Color(0xFF4FD1A5)
private val AccentDim = Color(0xFF2A8B6E)
private val Gold = Color(0xFFF0C040)
private val ErrorRed = Color(0xFFFF8B8B)
private val FocusGlow = Color(0xFF4FD1A5)
private val CardShape = RoundedCornerShape(8.dp)

private fun isActivationKey(key: Key): Boolean {
    return key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
}

private fun Modifier.tvActivate(onClick: () -> Unit): Modifier = onKeyEvent {
    if (it.type == KeyEventType.KeyUp && isActivationKey(it.key)) {
        onClick()
        true
    } else {
        false
    }
}.clickable(onClick = onClick)

private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun fmtClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

data class StreamInfo(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String,
    val title: String,
    val default: Boolean,
    val forced: Boolean,
) {
    fun label(): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add(title)
        if (language.isNotBlank()) parts.add(language.uppercase())
        if (codec.isNotBlank()) parts.add(codec)
        if (forced) parts.add("(forced)")
        return parts.joinToString(" \u2022 ").ifBlank { "Track ${index}" }
    }
}

private data class BandwidthOption(val label: String, val kbps: Int?)

private val BandwidthOptions = listOf(
    BandwidthOption("Direct", null),
    BandwidthOption("3 mbit", 3000),
    BandwidthOption("5 mbit", 5000),
    BandwidthOption("8 mbit", 8000),
    BandwidthOption("10 mbit", 10000),
    BandwidthOption("15 mbit", 15000),
)

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

private object PlayerOsdBridge {
    var handler: ((AndroidKeyEvent) -> Boolean)? = null

    fun dispatch(event: AndroidKeyEvent): Boolean = handler?.invoke(event) == true
}

data class Session(val server: String, val token: String, val username: String = "")
data class User(val id: Long, val username: String, val displayName: String)
data class Library(val id: String, val name: String, val type: String)
data class ShowSummary(
    val libraryId: String,
    val title: String,
    val episodeCount: Int,
    val seasonCount: Int,
    val posterItemId: Long,
    val posterMtimeUnix: Long,
    val backdropItemId: Long,
    val backdropMtimeUnix: Long,
    val overview: String,
    val genres: String,
    val rating: Double,
)
data class SeasonSummary(
    val libraryId: String,
    val showTitle: String,
    val seasonNumber: Int,
    val title: String,
    val episodeCount: Int,
    val durationMs: Long,
    val posterItemId: Long,
    val posterMtimeUnix: Long,
    val backdropItemId: Long,
    val overview: String,
    val rating: Double,
)
data class PlaybackProgress(val itemId: Long, val positionMs: Long, val durationMs: Long, val completed: Boolean)
data class ShowProgress(val libraryId: String, val showTitle: String, val episodeCount: Int, val completedCount: Int, val completed: Boolean)
data class Watchlist(val items: List<PopItem>, val shows: List<ShowSummary>)
data class RemoteCommand(val id: Long, val type: String, val payload: JSONObject)
data class PlayerRemoteCommand(val id: Long, val type: String, val payload: JSONObject)
data class QRLoginStart(val code: String, val expiresAt: String)
data class ExternalRatings(
    val imdbId: String,
    val tmdbId: String,
    val localRating: Double,
    val imdbRating: Double,
    val tmdbRating: Double,
    val rottenTomatoesRating: Int,
    val metacriticRating: Int,
)
data class PopItem(
    val id: Long,
    val libraryId: String,
    val kind: String,
    val title: String,
    val year: Int,
    val durationMs: Long,
    val posterPath: String,
    val posterMtimeUnix: Long,
    val backdropPath: String,
    val backdropMtimeUnix: Long,
    val overview: String,
    val genres: String,
    val rating: Double,
    val showTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
)

private data class CachedList<T>(val entries: List<T>, val fullyLoaded: Boolean)

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

private sealed interface SearchResult {
    data class Show(val show: ShowSummary) : SearchResult
    data class Movie(val item: PopItem) : SearchResult
}

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data object Home : Screen
    data object Watchlist : Screen
    data class LibraryPage(val library: Library) : Screen
    data object Search : Screen
    data class Show(val show: ShowSummary, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false) : Screen
    data class Season(val show: ShowSummary, val season: SeasonSummary, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false) : Screen
    data class Detail(val item: PopItem, val fromShow: ShowSummary?, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false) : Screen
    data class Player(val item: PopItem, val audioIndex: Int?, val subtitleIndex: Int?) : Screen
}

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

@Composable
fun LoginView(initialServer: String, error: String, onLogin: (String, String, String) -> Unit, onQrLogin: (Session) -> Unit) {
    var server by remember { mutableStateOf(initialServer) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("choose") }
    var setupPayload by remember { mutableStateOf("") }
    var setupAddress by remember { mutableStateOf("") }
    var setupJob by remember { mutableStateOf<Job?>(null) }
    var setupSocket by remember { mutableStateOf<ServerSocket?>(null) }
    var qrError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun startPhoneSetup() {
        setupJob?.cancel()
        runCatching { setupSocket?.close() }
        setupPayload = ""
        setupAddress = ""
        setupJob = scope.launch {
            qrError = ""
            val code = UUID.randomUUID().toString().replace("-", "")
            val host = withContext(Dispatchers.IO) { localIPv4Address() }
            if (host.isBlank()) {
                qrError = "Could not find Shield network address"
                return@launch
            }
            val socket = withContext(Dispatchers.IO) { ServerSocket(0) }
            setupSocket = socket
            val callback = "http://$host:${socket.localPort}/pair"
            setupAddress = callback
            setupPayload = JSONObject()
                .put("type", "popcorn-shield-setup")
                .put("callback", callback)
                .put("code", code)
                .put("deviceName", "Shield ${Build.MODEL}".trim())
                .toString()
            runCatching {
                withContext(Dispatchers.IO) { socket.use { it.acceptShieldSetup(code) } }
            }.onSuccess {
                onQrLogin(it)
            }.onFailure {
                if (qrError.isBlank()) qrError = it.message ?: "Phone setup failed"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            setupJob?.cancel()
            runCatching { setupSocket?.close() }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(if (mode == "qr") 440.dp else 360.dp)
                .clip(CardShape)
                .background(SurfaceColor)
                .border(1.dp, Line, CardShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Popcorn", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))

            when (mode) {
                "manual" -> {
                    TvTextField(server, "Server URL", onChange = { server = it })
                    TvTextField(username, "Username", onChange = { username = it })
                    TvTextField(password, "Password", password = true, onChange = { password = it })
                    Spacer(Modifier.height(2.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { onLogin(server, username, password) },
                    ) {
                        Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextColor),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { mode = "choose" },
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                "qr" -> {
                    if (setupPayload.isNotBlank()) {
                        QRCode(payload = setupPayload, modifier = Modifier.size(210.dp))
                        Text("Scan with Popcorn Remote", color = Muted, fontSize = 12.sp)
                        Text(setupAddress, color = TextColor, fontSize = 12.sp, textAlign = TextAlign.Center)
                    } else {
                        Box(Modifier.size(210.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                            Text("Create a phone login QR", color = Muted, fontSize = 13.sp)
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { startPhoneSetup() },
                    ) {
                        Text(if (setupPayload.isBlank()) "Create QR Code" else "Refresh QR Code", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextColor),
                        shape = RoundedCornerShape(6.dp),
                        onClick = {
                            setupPayload = ""
                            setupAddress = ""
                            setupJob?.cancel()
                            runCatching { setupSocket?.close() }
                            mode = "choose"
                        },
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                else -> {
                    Button(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(6.dp),
                        onClick = {
                            mode = "qr"
                            startPhoneSetup()
                        },
                    ) {
                        Text("Login with QR Code", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = TextColor),
                        shape = RoundedCornerShape(6.dp),
                        onClick = { mode = "manual" },
                    ) {
                        Text("Login Manually", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            if (qrError.isNotBlank()) {
                Text(qrError, color = ErrorRed, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun QRCode(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { qrBitmap(payload, 512) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR login", modifier = Modifier.fillMaxSize())
    }
}

private fun qrBitmap(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (y in 0 until size) {
        for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

private fun ServerSocket.acceptShieldSetup(expectedCode: String): Session {
    val socket = accept()
    socket.use {
        it.soTimeout = 30000
        val reader = it.getInputStream().bufferedReader()
        val requestLine = reader.readLine().orEmpty()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: ""
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        val bodyChars = CharArray(contentLength.coerceAtLeast(0))
        var read = 0
        while (read < bodyChars.size) {
            val n = reader.read(bodyChars, read, bodyChars.size - read)
            if (n < 0) break
            read += n
        }
        fun respond(status: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            it.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            it.getOutputStream().write(bytes)
            it.getOutputStream().flush()
        }
        if (!requestLine.startsWith("POST /pair ")) {
            respond("404 Not Found", """{"error":"not found"}""")
            error("invalid setup request")
        }
        val json = JSONObject(String(bodyChars, 0, read))
        if (json.optString("code") != expectedCode) {
            respond("403 Forbidden", """{"error":"invalid code"}""")
            error("invalid setup code")
        }
        val server = json.optString("server").trimEnd('/')
        val token = json.optString("token")
        val username = json.optString("username")
        if (server.isBlank() || token.isBlank()) {
            respond("400 Bad Request", """{"error":"server and token are required"}""")
            error("phone did not send server and token")
        }
        respond("200 OK", """{"ok":true}""")
        return Session(server, token, username)
    }
}

private fun localIPv4Address(): String {
    return runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                val host = address.hostAddress.orEmpty()
                if (host.indexOf(':') < 0 && !address.isLoopbackAddress && !host.startsWith("169.254.")) {
                    return@runCatching host
                }
            }
        }
        ""
    }.getOrDefault("")
}

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
                ) { result, _, _, focusRequester ->
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

// ── Detail View ──

@Composable
fun DetailView(item: PopItem, session: Session?, onPlay: (Int?, Int?) -> Unit) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    var selectedAudio by remember { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember { mutableStateOf<Int?>(null) }
    var streamsLoaded by remember { mutableStateOf(false) }
    var externalRatings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).streams(item.id) }
            .onSuccess { list ->
                streams.clear()
                streams.addAll(list)
                val defAudio = list.firstOrNull { it.type == "audio" && it.default }
                    ?: list.firstOrNull { it.type == "audio" }
                selectedAudio = defAudio?.index
                val defSub = list.firstOrNull { it.type == "subtitle" && it.default }
                selectedSubtitle = defSub?.index
                streamsLoaded = true
            }
            .onFailure { streamsLoaded = true }
    }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        externalRatings = null
        runCatching { Api(active).ratings(item.id) }
            .onSuccess { externalRatings = it }
    }

    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Backdrop
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            if (item.backdropPath.isNotBlank() && session != null) {
                SizedAsyncImage(
                    model = imageUrl(session, item.id, "backdrop", item.backdropMtimeUnix),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    widthPx = 900,
                    heightPx = 360,
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(colors = listOf(Color.Transparent, Bg), startY = 28f)
                )
            )
        }

        // Info row: poster + details + play
        Row(
            Modifier.padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Poster(session, item.id, Modifier.width(90.dp), item.posterMtimeUnix)
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title,
                    color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp,
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val metaParts = mutableListOf<String>()
                    if (item.kind == "episode") {
                        metaParts.add("S%02dE%02d".format(item.seasonNumber, item.episodeNumber))
                        if (item.showTitle.isNotBlank()) metaParts.add(item.showTitle)
                    } else {
                        if (item.year > 0) metaParts.add(item.year.toString())
                    }
                    if (item.durationMs > 0) metaParts.add(fmtDuration(item.durationMs))
                    if (metaParts.isNotEmpty()) Text(metaParts.joinToString(" \u00b7 "), color = Muted, fontSize = 12.sp)
                    val hasSourceRatings = externalRatings?.let { it.imdbRating > 0 || it.tmdbRating > 0 || it.rottenTomatoesRating > 0 } == true
                    if (item.rating > 0 && !hasSourceRatings) RatingBadge(item.rating)
                    externalRatings?.let { ratings ->
                        if (ratings.imdbRating > 0) SourceRatingBadge("IMDb", "%.1f".format(Locale.US, ratings.imdbRating))
                        if (ratings.tmdbRating > 0) SourceRatingBadge("TMDb", "%.1f".format(Locale.US, ratings.tmdbRating))
                        if (ratings.rottenTomatoesRating > 0) SourceRatingBadge("RT", "${ratings.rottenTomatoesRating}%")
                    }
                }
                if (item.genres.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(item.genres, color = Muted, fontSize = 11.sp)
                }
                if (item.overview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(item.overview, color = TextColor.copy(alpha = .75f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                FocusButton(label = "\u25B6  Play", primary = true, onClick = { onPlay(selectedAudio, selectedSubtitle) })
            }
        }

        Spacer(Modifier.height(12.dp))

        // Audio & Subtitle columns side by side
        if (streamsLoaded && (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty())) {
            Row(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Audio column
                if (audioTracks.isNotEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio", color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn {
                            items(audioTracks) { track ->
                                TrackRow(
                                    label = track.label(),
                                    selected = track.index == selectedAudio,
                                    onClick = { selectedAudio = track.index },
                                )
                            }
                        }
                    }
                }
                // Subtitle column
                if (subtitleTracks.isNotEmpty()) {
                    Column(Modifier.weight(1f)) {
                        Text("Subtitles", color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn {
                            item {
                                TrackRow(label = "Off", selected = selectedSubtitle == null, onClick = { selectedSubtitle = null })
                            }
                            items(subtitleTracks) { track ->
                                TrackRow(
                                    label = track.label(),
                                    selected = track.index == selectedSubtitle,
                                    onClick = { selectedSubtitle = track.index },
                                )
                            }
                        }
                    }
                }
            }
        } else if (!streamsLoaded) {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) Accent.copy(alpha = .15f) else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused) FocusGlow else if (selected) Accent.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(5.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (selected) "\u25C9" else "\u25CB",
            color = if (selected) Accent else Muted,
            fontSize = 12.sp,
        )
        Text(label, color = if (selected) TextColor else Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Player ──

@Composable
fun PlayerScreen(
    item: PopItem,
    session: Session?,
    deviceId: String,
    remoteCommand: PlayerRemoteCommand?,
    initialAudioIndex: Int?,
    initialSubtitleIndex: Int?,
    onRemoteStop: () -> Unit,
    onRemoteCommandConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    var selectedBandwidth by remember { mutableStateOf<Int?>(null) }
    var selectedAudioIndex by remember(item.id) { mutableStateOf(initialAudioIndex) }
    var selectedSubtitleIndex by remember(item.id) { mutableStateOf(initialSubtitleIndex) }
    var hlsSessionId by remember { mutableStateOf<String?>(null) }
    var playbackBaseMs by remember { mutableStateOf(0L) }
    val originalStreams = remember { mutableStateListOf<StreamInfo>() }
    var resumeApplied by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, session) {
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).streams(item.id) }
            .onSuccess { streams ->
                originalStreams.clear()
                originalStreams.addAll(streams)
                if (selectedAudioIndex == null) {
                    selectedAudioIndex = streams.firstOrNull { it.type == "audio" && it.default }?.index
                        ?: streams.firstOrNull { it.type == "audio" }?.index
                }
            }
    }

    val exoPlayer = remember {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (session != null && session.token.isNotBlank()) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${session.token}"))
            }
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                val url = playbackUrl(session, item.id, null, null, 0.0)
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            }
    }

    val playerView = remember {
        (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
            player = exoPlayer
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = false
            controllerShowTimeoutMs = 5000
            keepScreenOn = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    val autoHideRunnable = remember(playerView, exoPlayer) {
        Runnable {
            if (playerView.findViewWithTag<View>(NativeTrackMenuTag) == null && exoPlayer.isPlaying) {
                playerView.hideController()
                playerView.requestFocus()
            }
        }
    }

    fun scheduleControllerAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, 4_500)
    }

    fun logicalPositionMs(): Long {
        val current = exoPlayer.currentPosition.coerceAtLeast(0)
        val position = if (selectedBandwidth != null) playbackBaseMs + current else current
        return if (item.durationMs > 0) position.coerceIn(0, item.durationMs) else position
    }

    fun logicalDurationMs(): Long {
        return item.durationMs.takeIf { it > 0 } ?: exoPlayer.duration.coerceAtLeast(0)
    }

    fun applyDirectTrackSelections() {
        if (selectedBandwidth != null || originalStreams.isEmpty()) return
        applyOriginalTrackSelection(exoPlayer, originalStreams, selectedAudioIndex, selectedSubtitleIndex)
    }

    fun progressCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        return durationMs - positionMs <= 90_000 || positionMs.toDouble() / durationMs.toDouble() >= 0.92
    }

    fun reportProgress(state: String, forceCompleted: Boolean = false) {
        val activeSession = session ?: return
        val duration = logicalDurationMs()
        if (duration <= 0) return
        val position = logicalPositionMs()
        val completed = forceCompleted || progressCompleted(position, duration)
        if (position < 5_000 && !completed) return
        scope.launch {
            runCatching { Api(activeSession).saveProgress(item.id, position, duration, completed, state) }
        }
    }

    fun reportRemoteState(state: String) {
        val activeSession = session ?: return
        if (deviceId.isBlank()) return
        scope.launch {
            runCatching {
                Api(activeSession).putDeviceState(
                    deviceId = deviceId,
                    itemId = item.id,
                    title = if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title,
                    state = state,
                    positionMs = logicalPositionMs(),
                    durationMs = logicalDurationMs(),
                )
            }
        }
    }

    fun switchBandwidth(kbps: Int?, targetSeconds: Double = logicalPositionMs() / 1000.0) {
        val activeSession = session ?: return
        val oldHlsSession = hlsSessionId
        val startMs = (targetSeconds * 1000.0).toLong().coerceAtLeast(0)
        val wasPlaying = exoPlayer.playWhenReady
        val newHlsSession = kbps?.let { newHlsSessionId(item.id) }
        selectedBandwidth = kbps
        hlsSessionId = newHlsSession
        playbackBaseMs = if (kbps != null) startMs else 0L
        val url = playbackUrl(activeSession, item.id, kbps, newHlsSession, targetSeconds, selectedAudioIndex, selectedSubtitleIndex)
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        exoPlayer.prepare()
        if (kbps == null && startMs > 0) {
            exoPlayer.seekTo(startMs)
        }
        exoPlayer.playWhenReady = wasPlaying
        playerView.showController()
        scheduleControllerAutoHide()
        if (oldHlsSession != null && oldHlsSession != newHlsSession) {
            scope.launch { stopHlsSession(activeSession, oldHlsSession) }
        }
    }

    fun switchAudio(index: Int?) {
        selectedAudioIndex = index
        if (selectedBandwidth != null) {
            switchBandwidth(selectedBandwidth)
        }
    }

    fun switchSubtitle(index: Int?) {
        selectedSubtitleIndex = index
        if (selectedBandwidth != null) {
            switchBandwidth(selectedBandwidth)
        }
    }

    fun applyRemoteCommand(command: PlayerRemoteCommand) {
        when (command.type) {
            "pause" -> exoPlayer.pause()
            "resume" -> exoPlayer.play()
            "seek" -> {
                val target = command.payload.optLong("positionMs").coerceAtLeast(0)
                if (selectedBandwidth != null) {
                    switchBandwidth(selectedBandwidth, target / 1000.0)
                } else {
                    exoPlayer.seekTo(target)
                }
            }
            "stop" -> {
                reportProgress("stopped")
                reportRemoteState("stopped")
                onRemoteStop()
            }
            "bandwidth" -> {
                switchBandwidth(command.payload.optIntOrNull("bandwidthKbps"))
            }
        }
        onRemoteCommandConsumed(command.id)
    }

    fun isRevealKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == AndroidKeyEvent.KEYCODE_MENU

    fun isHiddenSeekKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
        keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND ||
        keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD

    fun seekFromHiddenControls(keyCode: Int): Boolean {
        if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null || playerView.isControllerFullyVisible) {
            return false
        }
        val deltaMs = when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -10_000L
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 10_000L
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> -30_000L
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 30_000L
            else -> return false
        }
        val duration = logicalDurationMs()
        val current = logicalPositionMs()
        val target = if (duration > 0) {
            (current + deltaMs).coerceIn(0, duration)
        } else {
            (current + deltaMs).coerceAtLeast(0)
        }
        if (selectedBandwidth != null) {
            switchBandwidth(selectedBandwidth, target / 1000.0)
        } else {
            exoPlayer.seekTo(target)
        }
        playerView.showController()
        scheduleControllerAutoHide()
            playerView.post {
            (playerView.findViewById<View>(R.id.popcorn_hls_progress)?.takeIf { selectedBandwidth != null }
                ?: playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress))?.requestFocus()
                ?: playerView.requestFocus()
        }
        return true
    }

    fun revealController(focusTimeBar: Boolean = false): Boolean {
        if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null || playerView.isControllerFullyVisible) {
            return false
        }
        playerView.showController()
        scheduleControllerAutoHide()
        playerView.post {
            val focusTarget = if (focusTimeBar) {
                playerView.findViewById<View>(R.id.popcorn_hls_progress)?.takeIf { selectedBandwidth != null }
                    ?: playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            } else {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
            }
            focusTarget?.requestFocus() ?: playerView.requestFocus()
        }
        return true
    }

    DisposableEffect(Unit) {
        PlayerOsdBridge.handler = { event ->
            when {
                isHiddenSeekKey(event.keyCode) && !playerView.isControllerFullyVisible && playerView.findViewWithTag<View>(NativeTrackMenuTag) == null -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) seekFromHiddenControls(event.keyCode)
                    true
                }
                isRevealKey(event.keyCode) && !playerView.isControllerFullyVisible && playerView.findViewWithTag<View>(NativeTrackMenuTag) == null -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) revealController()
                    true
                }
                event.action == AndroidKeyEvent.ACTION_UP && playerView.isControllerFullyVisible -> {
                    scheduleControllerAutoHide()
                    false
                }
                else -> false
            }
        }
        scheduleControllerAutoHide()
        onDispose {
            if (PlayerOsdBridge.handler != null) PlayerOsdBridge.handler = null
            mainHandler.removeCallbacks(autoHideRunnable)
            closeNativeTrackMenu(playerView, restoreFocus = false)
            val oldHlsSession = hlsSessionId
            val activeSession = session
            if (oldHlsSession != null && activeSession != null) {
                scope.launch { stopHlsSession(activeSession, oldHlsSession) }
            }
            reportProgress("stopped")
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer, item.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    reportProgress("stopped", forceCompleted = true)
                }
                if (playbackState == Player.STATE_READY) {
                    applyDirectTrackSelections()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    reportProgress(if (isPlaying) "playing" else "paused")
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(playerView, selectedBandwidth) {
        val titleView = playerView.findViewById<TextView>(R.id.popcorn_title)
        val audioButton = playerView.findViewById<View>(R.id.popcorn_audio)
        val subtitleButton = playerView.findViewById<View>(R.id.popcorn_subtitles)
        val bandwidthButton = playerView.findViewById<TextView>(R.id.popcorn_bandwidth)
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        val hlsTimeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
        val nativeDurationView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
        val hlsPositionView = playerView.findViewById<TextView>(R.id.popcorn_hls_position)
        val hlsDurationView = playerView.findViewById<TextView>(R.id.popcorn_hls_duration)
        val activeTimeBar = if (selectedBandwidth != null) hlsTimeBar else nativeTimeBar
        val controllerKeyListener = View.OnKeyListener { view, keyCode, event ->
            if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null) return@OnKeyListener false
            if (event.action != AndroidKeyEvent.ACTION_UP) return@OnKeyListener false

            if (isRevealKey(keyCode) && revealController()) {
                true
            } else {
                scheduleControllerAutoHide()
                false
            }
        }

        titleView?.text = if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title
        bandwidthButton?.text = bandwidthLabel(selectedBandwidth)
        nativeTimeBar?.visibility = if (selectedBandwidth == null) View.VISIBLE else View.GONE
        hlsTimeBar?.visibility = if (selectedBandwidth == null) View.GONE else View.VISIBLE
        nativePositionView?.visibility = if (selectedBandwidth == null) View.VISIBLE else View.GONE
        nativeDurationView?.visibility = if (selectedBandwidth == null) View.VISIBLE else View.GONE
        hlsPositionView?.visibility = if (selectedBandwidth == null) View.GONE else View.VISIBLE
        hlsDurationView?.visibility = if (selectedBandwidth == null) View.GONE else View.VISIBLE
        playerView.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.setOnKeyListener(controllerKeyListener)
        activeTimeBar?.setOnKeyListener(controllerKeyListener)
        val hlsScrubListener = object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled && selectedBandwidth != null) {
                    switchBandwidth(selectedBandwidth, position.coerceAtLeast(0) / 1000.0)
                }
            }
        }
        hlsTimeBar?.addListener(hlsScrubListener)
        nativeTimeBar?.setKeyTimeIncrement(10_000)
        hlsTimeBar?.setKeyTimeIncrement(10_000)
        audioButton?.setOnClickListener {
            scheduleControllerAutoHide()
            if (selectedBandwidth != null && originalStreams.any { it.type == "audio" }) {
                showNativeOriginalTrackMenu(
                    playerView = playerView,
                    title = "Audio Track",
                    tracks = originalStreams.filter { it.type == "audio" },
                    selectedIndex = selectedAudioIndex,
                    allowOff = false,
                    returnFocus = audioButton,
                    onSelected = { switchAudio(it) },
                    onClosed = ::scheduleControllerAutoHide,
                )
            } else {
                showNativeTrackMenu(
                    playerView = playerView,
                    player = exoPlayer,
                    title = "Audio Track",
                    trackType = C.TRACK_TYPE_AUDIO,
                    allowOff = false,
                    returnFocus = audioButton,
                    onClosed = ::scheduleControllerAutoHide,
                )
            }
        }
        audioButton?.setOnKeyListener(controllerKeyListener)
        subtitleButton?.setOnClickListener {
            scheduleControllerAutoHide()
            if (selectedBandwidth != null && originalStreams.any { it.type == "subtitle" }) {
                showNativeOriginalTrackMenu(
                    playerView = playerView,
                    title = "Subtitle Track",
                    tracks = originalStreams.filter { it.type == "subtitle" },
                    selectedIndex = selectedSubtitleIndex,
                    allowOff = true,
                    returnFocus = subtitleButton,
                    onSelected = { switchSubtitle(it) },
                    onClosed = ::scheduleControllerAutoHide,
                )
            } else {
                showNativeTrackMenu(
                    playerView = playerView,
                    player = exoPlayer,
                    title = "Subtitle Track",
                    trackType = C.TRACK_TYPE_TEXT,
                    allowOff = true,
                    returnFocus = subtitleButton,
                    onClosed = ::scheduleControllerAutoHide,
                )
            }
        }
        subtitleButton?.setOnKeyListener(controllerKeyListener)
        bandwidthButton?.setOnClickListener {
            scheduleControllerAutoHide()
            showNativeBandwidthMenu(
                playerView = playerView,
                selectedBandwidth = selectedBandwidth,
                returnFocus = bandwidthButton,
                onSelected = { switchBandwidth(it) },
                onClosed = ::scheduleControllerAutoHide,
            )
        }
        bandwidthButton?.setOnKeyListener(controllerKeyListener)

        onDispose {
            playerView.setOnKeyListener(null)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.setOnKeyListener(null)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.setOnKeyListener(null)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.setOnKeyListener(null)
            nativeTimeBar?.setOnKeyListener(null)
            hlsTimeBar?.setOnKeyListener(null)
            hlsTimeBar?.removeListener(hlsScrubListener)
            audioButton?.setOnClickListener(null)
            audioButton?.setOnKeyListener(null)
            subtitleButton?.setOnClickListener(null)
            subtitleButton?.setOnKeyListener(null)
            bandwidthButton?.setOnClickListener(null)
            bandwidthButton?.setOnKeyListener(null)
        }
    }

    LaunchedEffect(selectedBandwidth, playerView) {
        playerView.findViewById<TextView>(R.id.popcorn_bandwidth)?.text = bandwidthLabel(selectedBandwidth)
    }

    LaunchedEffect(selectedBandwidth, playbackBaseMs, playerView, item.durationMs) {
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
        val nativeDurationView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
        val positionView = playerView.findViewById<TextView>(R.id.popcorn_hls_position)
        val durationView = playerView.findViewById<TextView>(R.id.popcorn_hls_duration)
        nativeTimeBar?.visibility = if (selectedBandwidth == null) View.VISIBLE else View.GONE
        timeBar?.visibility = if (selectedBandwidth == null) View.GONE else View.VISIBLE
        nativePositionView?.visibility = if (selectedBandwidth == null) View.VISIBLE else View.GONE
        nativeDurationView?.visibility = if (selectedBandwidth == null) View.VISIBLE else View.GONE
        positionView?.visibility = if (selectedBandwidth == null) View.GONE else View.VISIBLE
        durationView?.visibility = if (selectedBandwidth == null) View.GONE else View.VISIBLE
        while (selectedBandwidth != null) {
            val duration = logicalDurationMs()
            val position = logicalPositionMs()
            if (duration > 0) {
                timeBar?.setDuration(duration)
                timeBar?.setBufferedPosition(duration)
                timeBar?.setPosition(position)
                durationView?.text = fmtClock(duration)
                positionView?.text = fmtClock(position)
            }
            delay(250)
        }
    }

    LaunchedEffect(originalStreams.size, selectedAudioIndex, selectedSubtitleIndex, selectedBandwidth, exoPlayer) {
        if (selectedBandwidth != null || originalStreams.isEmpty()) return@LaunchedEffect
        repeat(20) {
            applyDirectTrackSelections()
            if (exoPlayer.currentTracks.groups.isNotEmpty()) return@LaunchedEffect
            delay(250)
        }
    }

    LaunchedEffect(item.id, session, exoPlayer) {
        val activeSession = session ?: return@LaunchedEffect
        if (resumeApplied) return@LaunchedEffect
        val progress = runCatching { Api(activeSession).progress(item.id) }.getOrNull()
        val duration = progress?.durationMs?.takeIf { it > 0 } ?: item.durationMs
        val position = progress?.positionMs ?: 0
        val canResume = progress != null &&
            !progress.completed &&
            duration > 0 &&
            position >= 30_000 &&
            position < (duration - 90_000).coerceAtLeast(30_000)
        resumeApplied = true
        if (canResume) {
            delay(250)
            exoPlayer.seekTo(position)
        }
    }

    LaunchedEffect(item.id, session, exoPlayer) {
        while (true) {
            delay(10_000)
            if (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_BUFFERING) {
                reportProgress("")
            }
        }
    }

    LaunchedEffect(remoteCommand?.id) {
        remoteCommand?.let { applyRemoteCommand(it) }
    }

    LaunchedEffect(deviceId, item.id, exoPlayer) {
        while (true) {
            delay(1_000)
            val state = when {
                exoPlayer.playbackState == Player.STATE_ENDED -> "ended"
                exoPlayer.isPlaying -> "playing"
                exoPlayer.playbackState == Player.STATE_BUFFERING -> "buffering"
                else -> "paused"
            }
            reportRemoteState(state)
        }
    }

    LaunchedEffect(Unit) {
        delay(150)
        playerView.requestFocus()
        playerView.showController()
        scheduleControllerAutoHide()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { playerView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val NativeTrackMenuTag = "popcorn_native_track_menu"

private fun showNativeOriginalTrackMenu(
    playerView: PlayerView,
    title: String,
    tracks: List<StreamInfo>,
    selectedIndex: Int?,
    allowOff: Boolean,
    returnFocus: View?,
    onSelected: (Int?) -> Unit,
    onClosed: () -> Unit,
) {
    val choices = mutableListOf<NativeChoice>()
    if (allowOff) {
        choices.add(NativeChoice("Off", selectedIndex == null) { onSelected(null) })
    }
    tracks.forEach { track ->
        choices.add(NativeChoice(track.label(), track.index == selectedIndex) { onSelected(track.index) })
    }
    showNativeChoiceMenu(
        playerView = playerView,
        title = title,
        choices = choices.ifEmpty { listOf(NativeChoice("No tracks available yet", false) {}) },
        returnFocus = returnFocus,
        onClosed = onClosed,
    )
}

private fun applyOriginalTrackSelection(
    player: ExoPlayer,
    originalStreams: List<StreamInfo>,
    audioIndex: Int?,
    subtitleIndex: Int?,
) {
    applyOriginalTrack(player, originalStreams.firstOrNull { it.index == audioIndex && it.type == "audio" }, C.TRACK_TYPE_AUDIO, disable = false)
    if (subtitleIndex == null) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    } else {
        applyOriginalTrack(player, originalStreams.firstOrNull { it.index == subtitleIndex && it.type == "subtitle" }, C.TRACK_TYPE_TEXT, disable = false)
    }
}

private fun applyOriginalTrack(player: ExoPlayer, stream: StreamInfo?, trackType: Int, disable: Boolean) {
    if (disable) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
        return
    }
    if (stream == null) return
    var bestGroup: androidx.media3.common.Tracks.Group? = null
    var bestIndex = -1
    var bestScore = 0
    for (group in player.currentTracks.groups.filter { it.type == trackType }) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            var score = 0
            if (stream.language.isNotBlank() && format.language?.equals(stream.language, ignoreCase = true) == true) score += 8
            if (stream.title.isNotBlank() && format.label?.contains(stream.title, ignoreCase = true) == true) score += 4
            if (stream.codec.isNotBlank() && format.sampleMimeType?.contains(stream.codec, ignoreCase = true) == true) score += 2
            if (score > bestScore) {
                bestScore = score
                bestGroup = group
                bestIndex = i
            }
        }
    }
    val group = bestGroup ?: return
    if (bestIndex < 0) return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(trackType, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, bestIndex))
        .build()
}

private fun showNativeBandwidthMenu(
    playerView: PlayerView,
    selectedBandwidth: Int?,
    returnFocus: View?,
    onSelected: (Int?) -> Unit,
    onClosed: () -> Unit,
) {
    val choices = BandwidthOptions.map { option ->
        NativeChoice(
            label = option.label,
            selected = option.kbps == selectedBandwidth,
            action = { onSelected(option.kbps) },
        )
    }
    showNativeChoiceMenu(
        playerView = playerView,
        title = "Bandwidth",
        choices = choices,
        returnFocus = returnFocus,
        onClosed = onClosed,
    )
}

private fun showNativeTrackMenu(
    playerView: PlayerView,
    player: ExoPlayer,
    title: String,
    trackType: Int,
    allowOff: Boolean,
    returnFocus: View?,
    onClosed: () -> Unit,
) {
    val choices = mutableListOf<NativeChoice>()
    if (allowOff) {
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(trackType)
        choices.add(NativeChoice("Off", disabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()
        })
    }

    for (group in player.currentTracks.groups.filter { it.type == trackType }) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val label = buildString {
                if (!format.label.isNullOrBlank()) append(format.label)
                if (!format.language.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(format.language!!.uppercase())
                }
                if (!format.sampleMimeType.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(format.sampleMimeType!!.substringAfterLast("/"))
                }
                if (format.channelCount > 0 && trackType == C.TRACK_TYPE_AUDIO) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(when (format.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${format.channelCount}ch"
                    })
                }
                if (isEmpty()) append("Track ${i + 1}")
            }
            val trackGroup = group.mediaTrackGroup
            val trackIndex = i
            choices.add(NativeChoice(label, group.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                    .build()
            })
        }
    }

    if (choices.isEmpty()) {
        choices.add(NativeChoice("No tracks available yet", selected = false, action = {}))
    }

    showNativeChoiceMenu(
        playerView = playerView,
        title = title,
        choices = choices,
        returnFocus = returnFocus,
        onClosed = onClosed,
    )
}

private data class NativeChoice(val label: String, val selected: Boolean, val action: () -> Unit)

private fun showNativeChoiceMenu(
    playerView: PlayerView,
    title: String,
    choices: List<NativeChoice>,
    returnFocus: View?,
    onClosed: () -> Unit,
) {
    closeNativeTrackMenu(playerView, restoreFocus = false)
    playerView.hideController()

    val context = playerView.context
    val overlay = FrameLayout(context).apply {
        tag = NativeTrackMenuTag
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0x99000000.toInt())
        setOnKeyListener { _, keyCode, event ->
            if (event.action == AndroidKeyEvent.ACTION_UP && keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                closeNativeTrackMenu(playerView, returnFocus, onClosed = onClosed)
                true
            } else {
                false
            }
        }
    }

    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF111319.toInt())
        setPadding(dp(context, 0), dp(context, 10), dp(context, 0), dp(context, 10))
    }
    val panelParams = FrameLayout.LayoutParams(dp(context, 340), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        marginEnd = dp(context, 32)
    }
    overlay.addView(panel, panelParams)

    panel.addView(TextView(context).apply {
        text = title
        setTextColor(0xFF4FD1A5.toInt())
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 10))
    })

    val rows = mutableListOf<TextView>()
    var selectedRow: TextView? = null
    fun closeAfterSelection() = closeNativeTrackMenu(playerView, returnFocus, onClosed = onClosed)
    fun addRow(choice: NativeChoice) {
        val row = TextView(context).apply {
            text = if (choice.selected) "\u2713  ${choice.label}" else "    ${choice.label}"
            setTextColor(if (choice.selected) 0xFFFFFFFF.toInt() else 0xFFE8ECF2.toInt())
            textSize = 13f
            typeface = if (choice.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isFocusable = true
            isClickable = true
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9))
            setBackgroundColor(if (choice.selected) 0x332A8B6E else 0x00000000)
            setOnFocusChangeListener { view, focused ->
                view.setBackgroundColor(if (focused) 0xFF263042.toInt() else if (choice.selected) 0x332A8B6E else 0x00000000)
                (view as TextView).setTextColor(if (focused) 0xFF4FD1A5.toInt() else if (choice.selected) 0xFFFFFFFF.toInt() else 0xFFE8ECF2.toInt())
            }
            setOnClickListener {
                choice.action()
                closeAfterSelection()
            }
            setOnKeyListener { view, keyCode, event ->
                when {
                    keyCode == AndroidKeyEvent.KEYCODE_BACK -> {
                        if (event.action == AndroidKeyEvent.ACTION_UP) closeNativeTrackMenu(playerView, returnFocus, onClosed = onClosed)
                        true
                    }
                    event.action == AndroidKeyEvent.ACTION_UP && (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER || keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) -> {
                        view.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        if (choice.selected && selectedRow == null) selectedRow = row
        rows.add(row)
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    choices.forEach { addRow(it) }

    playerView.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    overlay.post {
        (selectedRow ?: rows.firstOrNull { it.text.toString().trim() != "No tracks available yet" })?.requestFocus()
            ?: overlay.requestFocus()
    }
}

private fun closeNativeTrackMenu(
    playerView: PlayerView,
    returnFocus: View? = null,
    restoreFocus: Boolean = true,
    onClosed: (() -> Unit)? = null,
) {
    playerView.findViewWithTag<View>(NativeTrackMenuTag)?.let { playerView.removeView(it) }
    if (restoreFocus) {
        playerView.showController()
        playerView.post {
            val focusTarget = returnFocus?.takeIf { it.isAttachedToWindow }
                ?: playerView
            focusTarget.requestFocus()
            onClosed?.invoke()
        }
    }
}

private fun bandwidthLabel(kbps: Int?): String {
    return BandwidthOptions.firstOrNull { it.kbps == kbps }?.label ?: "Direct"
}

private fun newHlsSessionId(itemId: Long): String {
    return "android_${itemId}_${System.currentTimeMillis()}"
}

private fun playbackUrl(
    session: Session?,
    itemId: Long,
    bandwidthKbps: Int?,
    hlsSessionId: String?,
    startSeconds: Double,
    audioIndex: Int? = null,
    subtitleIndex: Int? = null,
): String {
    val server = session?.server.orEmpty()
    if (bandwidthKbps == null || hlsSessionId == null) {
        return "$server/api/items/$itemId/stream"
    }
    val params = mutableListOf(
        "bandwidth=$bandwidthKbps",
        "start=${"%.3f".format(Locale.US, startSeconds)}",
    )
    if (audioIndex != null) params.add("audio=$audioIndex")
    if (subtitleIndex != null) params.add("subtitle=$subtitleIndex")
    return "$server/api/items/$itemId/hls/$hlsSessionId/index.m3u8?${params.joinToString("&")}"
}

private suspend fun stopHlsSession(session: Session, hlsSessionId: String) = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("${session.server}/api/hls/$hlsSessionId").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        conn.responseCode
        conn.inputStream.close()
    }
}

private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

@Composable
fun TrackMenu(title: String, player: ExoPlayer, trackType: Int, allowOff: Boolean = false, onDismiss: () -> Unit) {
    // Get available tracks
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType }
    val tracks = mutableListOf<Pair<String, () -> Unit>>()

    if (allowOff) {
        tracks.add("Off" to {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()
            onDismiss()
        })
    }

    for (group in trackGroups) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val label = buildString {
                if (!format.label.isNullOrBlank()) append(format.label)
                val lang = format.language
                if (!lang.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(lang.uppercase())
                }
                val mime = format.sampleMimeType
                if (!mime.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(mime.substringAfterLast("/"))
                }
                if (format.channelCount > 0 && trackType == C.TRACK_TYPE_AUDIO) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(when (format.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${format.channelCount}ch"
                    })
                }
                if (isEmpty()) append("Track ${i + 1}")
            }
            val isSelected = group.isTrackSelected(i)
            val trackGroup = group.mediaTrackGroup
            val trackIndex = i
            tracks.add(("${if (isSelected) "\u25C9 " else "\u25CB "}$label") to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                    .build()
                onDismiss()
            })
        }
    }

    // Render menu
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(tracks.size) {
        if (tracks.isNotEmpty()) firstFocus.requestFocus()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .width(280.dp)
                .padding(end = 24.dp)
                .clip(CardShape)
                .background(SurfaceColor)
                .border(1.dp, Line, CardShape)
                .padding(vertical = 10.dp),
        ) {
            Text(title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            tracks.forEachIndexed { index, (label, action) ->
                var focused by remember { mutableStateOf(false) }
                Text(
                    label,
                    color = if (focused) Accent else TextColor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (focused) Surface2 else Color.Transparent)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .tvActivate(action)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ── Grid & Cards ──

@Composable
fun <T> PosterGrid(entries: List<T>, key: (T) -> Any, content: @Composable (T, Boolean, Int, FocusRequester) -> Unit) {
    val firstKey = entries.firstOrNull()?.let { key(it) }
    var initialFocusPending by remember(firstKey) { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = 32f * 2f
        val minCellWidth = 120f
        val spacing = 10f
        val columns = max(1, ((maxWidth.value - horizontalPadding + spacing) / (minCellWidth + spacing)).toInt())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            gridItemsIndexed(entries, key = { _, item -> key(item) }) { index, item ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = initialFocusPending && index == 0
                if (focusNow) {
                    LaunchedEffect(key(item)) {
                        delay(450)
                        initialFocusPending = false
                    }
                }
                content(item, focusNow, index % columns, focusRequester)
            }
        }
    }
}

@Composable
fun ShowCard(
    session: Session?,
    show: ShowSummary,
    watched: Boolean = false,
    watchlisted: Boolean = false,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onLeftEdge = onLeftEdge, onClick = onClick, onLongClick = onLongClick) {
        Box {
            Poster(session, show.posterItemId, Modifier.fillMaxWidth(), show.posterMtimeUnix)
            if (show.rating > 0) PosterRating(show.rating)
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Spacer(Modifier.height(4.dp))
        Text(show.title, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${show.seasonCount}S \u00b7 ${show.episodeCount}E", color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun ItemCard(
    session: Session?,
    item: PopItem,
    watched: Boolean = false,
    watchlisted: Boolean = false,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
) {
    CardShell(autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onLeftEdge = onLeftEdge, onClick = onClick, onLongClick = onLongClick) {
        Box {
            Poster(session, item.id, Modifier.fillMaxWidth(), item.posterMtimeUnix)
            if (item.rating > 0) PosterRating(item.rating)
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title,
            color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.year.takeIf { it > 0 }?.toString(),
            fmtDuration(item.durationMs).ifBlank { null },
        ).joinToString(" \u00b7 ")
        if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun BoxScope.SeenBadge() {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Accent.copy(alpha = .92f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text("Seen", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun BoxScope.WatchlistBadge() {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Gold.copy(alpha = .94f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text("List", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun EpisodeRow(session: Session?, item: PopItem, watched: Boolean = false, watchlisted: Boolean = false, autoFocus: Boolean = false, onFocus: (() -> Unit)? = null, onClick: () -> Unit, onLongClick: ((FocusRequester) -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    var longPressReady by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            focusRequester.requestFocus()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 2.dp)
            .clip(CardShape)
            .background(if (focused) Surface2 else SurfaceColor)
            .border(1.dp, if (focused) FocusGlow else Color.Transparent, CardShape)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    longPressJob = null
                    longPressReady = false
                }
            }
            .focusable()
            .onKeyEvent {
                when {
                    isActivationKey(it.key) && it.type == KeyEventType.KeyDown -> {
                        if (onLongClick != null) {
                            if (longPressJob == null) {
                                longPressReady = false
                                longPressJob = scope.launch {
                                    delay(650)
                                    longPressReady = true
                                }
                            }
                        }
                        true
                    }
                    isActivationKey(it.key) && it.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressReady) {
                            longPressReady = false
                            onLongClick?.invoke(focusRequester)
                        } else {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.width(100.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Surface2),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (session != null) {
                val thumbUrl = if (item.backdropPath.isNotBlank()) {
                    imageUrl(session, item.id, "backdrop", item.backdropMtimeUnix)
                } else if (item.posterPath.isNotBlank()) {
                    imageUrl(session, item.id, "poster", item.posterMtimeUnix)
                } else null
                if (thumbUrl != null) {
                    SizedAsyncImage(model = thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 150)
                }
            }
            Text(
                "%02d".format(item.episodeNumber),
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(4.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
        }
        Column(Modifier.weight(1f)) {
            Text(item.episodeTitle.ifBlank { item.title }, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(item.overview, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            val dur = fmtDuration(item.durationMs)
            if (dur.isNotBlank()) Text(dur, color = Muted, fontSize = 10.sp)
            if (item.rating > 0) {
                Spacer(Modifier.height(2.dp))
                RatingBadge(item.rating, small = true)
            }
        }
    }
}

@Composable
fun WatchActionOverlay(
    title: String,
    watched: Boolean,
    watchlisted: Boolean,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onAddWatchlist: () -> Unit,
    onRemoveWatchlist: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        delay(80)
        firstFocus.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .58f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(320.dp)
                .clip(CardShape)
                .background(SurfaceColor)
                .border(1.dp, Line, CardShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = TextColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    if (watched) "Watched" else "Unwatched",
                    if (watchlisted) "In watchlist" else "Not in watchlist",
                ).joinToString(" · "),
                color = Muted,
                fontSize = 12.sp,
            )
            FocusButton("Mark watched", primary = !watched, modifier = Modifier.focusRequester(firstFocus)) { onMarkWatched() }
            FocusButton("Mark unwatched", primary = watched) { onMarkUnwatched() }
            FocusButton("Add to watchlist", primary = !watchlisted) { onAddWatchlist() }
            FocusButton("Remove from watchlist", primary = watchlisted) { onRemoveWatchlist() }
            FocusButton("Cancel", primary = false) { onDismiss() }
        }
    }
}

// ── Shared Components ──

@Composable
fun CardShell(
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLeftEdge: (() -> Boolean)? = null,
    onClick: () -> Unit,
    onLongClick: ((FocusRequester) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressReady by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val requester = focusRequester ?: remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            requester.requestFocus()
        }
    }
    Column(
        Modifier
            .clip(CardShape)
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, CardShape)
            .background(if (focused) Surface2 else Color.Transparent)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    longPressJob = null
                    longPressReady = false
                }
            }
            .focusable()
            .onKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && onLeftEdge != null -> onLeftEdge()
                    isActivationKey(it.key) && it.type == KeyEventType.KeyDown -> {
                        if (onLongClick != null) {
                            if (longPressJob == null) {
                                longPressReady = false
                                longPressJob = scope.launch {
                                    delay(650)
                                    longPressReady = true
                                }
                            }
                        }
                        true
                    }
                    isActivationKey(it.key) && it.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        longPressJob = null
                        if (longPressReady) {
                            longPressReady = false
                            onLongClick?.invoke(requester)
                        } else {
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(if (focused) 5.dp else 4.dp),
        content = content,
    )
}

@Composable
fun FocusButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (primary) {
                    if (focused) Accent else AccentDim
                } else {
                    if (focused) Surface3 else Surface2
                }
            )
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.Black else TextColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun Poster(session: Session?, itemId: Long, modifier: Modifier, version: Long = 0) {
    val url = imageUrl(session, itemId, "poster", version)
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 390)
        } else {
            Text("?", color = Muted, fontSize = 20.sp)
        }
    }
}

private fun imageUrl(session: Session?, itemId: Long, kind: String, version: Long = 0): String {
    if (session == null || itemId <= 0) return ""
    val suffix = if (version > 0) "?v=$version" else ""
    return "${session.server}/api/items/$itemId/image/$kind$suffix"
}

@Composable
fun SizedAsyncImage(
    model: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    widthPx: Int,
    heightPx: Int,
) {
    val context = LocalContext.current
    val request = remember(model, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(model)
            .size(widthPx, heightPx)
            .crossfade(false)
            .allowHardware(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
fun Pill(text: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Accent else if (focused) Surface2 else Color.Transparent)
            .border(1.dp, if (focused && !selected) FocusGlow else if (!selected) Line else Color.Transparent, RoundedCornerShape(999.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, color = if (selected) Color.Black else TextColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (badge != null) {
            Text(badge, color = if (selected) Color.Black.copy(alpha = .6f) else Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun RatingBadge(rating: Double, small: Boolean = false) {
    Text(
        "\u2605 ${"%.1f".format(rating)}",
        color = Gold,
        fontWeight = FontWeight.Bold,
        fontSize = if (small) 9.sp else 11.sp,
        modifier = Modifier
            .background(Gold.copy(alpha = .12f), RoundedCornerShape(3.dp))
            .padding(horizontal = if (small) 4.dp else 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SourceRatingBadge(label: String, value: String) {
    Row(
        modifier = Modifier
            .background(Surface2, RoundedCornerShape(3.dp))
            .border(1.dp, Line, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        Text(value, color = TextColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

@Composable
fun PosterRating(rating: Double) {
    Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopEnd) {
        Text(
            "\u2605 ${"%.1f".format(rating)}",
            color = Gold, fontWeight = FontWeight.Bold, fontSize = 9.sp,
            modifier = Modifier.background(Color.Black.copy(alpha = .7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun TvTextField(value: String, label: String, password: Boolean = false, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = Muted, fontSize = 11.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextColor,
            unfocusedTextColor = TextColor,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line,
            cursorColor = Accent,
            focusedLabelColor = Accent,
            unfocusedLabelColor = Muted,
            focusedContainerColor = Bg,
            unfocusedContainerColor = Bg,
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.then(if (modifier == Modifier) Modifier.fillMaxWidth() else Modifier),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
    )
}

@Composable
fun LoadingView(error: String) {
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        if (error.isBlank()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                Text("Loading\u2026", color = Muted, fontSize = 13.sp)
            }
        } else {
            Text(error, color = ErrorRed, fontSize = 14.sp)
        }
    }
}

private object AppCache {
    fun readLibraries(context: Context, session: Session): List<Library> {
        return runCatching {
            val arr = JSONArray(cacheFile(context, session, "libraries").readText())
            (0 until arr.length()).map { jsonToLibrary(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun writeLibraries(context: Context, session: Session, libraries: List<Library>) {
        runCatching {
            val arr = JSONArray()
            libraries.forEach { arr.put(libraryToJson(it)) }
            cacheFile(context, session, "libraries").writeText(arr.toString())
        }
    }

    fun readItems(context: Context, session: Session, libraryId: String): CachedList<PopItem> {
        return readCachedList(cacheFile(context, session, "items_$libraryId"), ::jsonToItem)
    }

    fun writeItems(context: Context, session: Session, libraryId: String, items: List<PopItem>, fullyLoaded: Boolean) {
        writeCachedList(cacheFile(context, session, "items_$libraryId"), items, fullyLoaded, ::itemToJson)
    }

    fun readShows(context: Context, session: Session, libraryId: String): CachedList<ShowSummary> {
        return readCachedList(cacheFile(context, session, "shows_$libraryId"), ::jsonToShow)
    }

    fun writeShows(context: Context, session: Session, libraryId: String, shows: List<ShowSummary>, fullyLoaded: Boolean) {
        writeCachedList(cacheFile(context, session, "shows_$libraryId"), shows, fullyLoaded, ::showToJson)
    }

    private fun <T> readCachedList(file: File, parser: (JSONObject) -> T): CachedList<T> {
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("entries")
            CachedList(
                entries = (0 until arr.length()).map { parser(arr.getJSONObject(it)) },
                fullyLoaded = root.optBoolean("fullyLoaded", false),
            )
        }.getOrDefault(CachedList(emptyList(), false))
    }

    private fun <T> writeCachedList(file: File, entries: List<T>, fullyLoaded: Boolean, writer: (T) -> JSONObject) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { arr.put(writer(it)) }
            file.writeText(JSONObject().put("fullyLoaded", fullyLoaded).put("entries", arr).toString())
        }
    }

    private fun cacheFile(context: Context, session: Session, name: String): File {
        val serverKey = session.server.fold(0) { acc, c -> acc * 31 + c.code }.toString()
        val dir = File(context.filesDir, "popcorn-cache/$serverKey").apply { mkdirs() }
        return File(dir, "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
    }
}

private fun jsonToLibrary(o: JSONObject): Library {
    return Library(o.getString("id"), o.getString("name"), o.optString("type", "movies"))
}

private fun libraryToJson(library: Library): JSONObject {
    return JSONObject()
        .put("id", library.id)
        .put("name", library.name)
        .put("type", library.type)
}

private fun jsonToShow(o: JSONObject): ShowSummary {
    return ShowSummary(
        libraryId = o.getString("libraryId"),
        title = o.getString("title"),
        episodeCount = o.optInt("episodeCount"),
        seasonCount = o.optInt("seasonCount"),
        posterItemId = o.optLong("posterItemId"),
        posterMtimeUnix = o.optLong("posterMtimeUnix"),
        backdropItemId = o.optLong("backdropItemId"),
        backdropMtimeUnix = o.optLong("backdropMtimeUnix"),
        overview = o.optString("overview"),
        genres = o.optString("genres"),
        rating = o.optDouble("rating"),
    )
}

private fun showToJson(show: ShowSummary): JSONObject {
    return JSONObject()
        .put("libraryId", show.libraryId)
        .put("title", show.title)
        .put("episodeCount", show.episodeCount)
        .put("seasonCount", show.seasonCount)
        .put("posterItemId", show.posterItemId)
        .put("posterMtimeUnix", show.posterMtimeUnix)
        .put("backdropItemId", show.backdropItemId)
        .put("backdropMtimeUnix", show.backdropMtimeUnix)
        .put("overview", show.overview)
        .put("genres", show.genres)
        .put("rating", show.rating)
}

private fun jsonToItem(o: JSONObject): PopItem {
    return PopItem(
        id = o.getLong("id"),
        libraryId = o.getString("libraryId"),
        kind = o.optString("kind"),
        title = o.optString("title"),
        year = o.optInt("year"),
        durationMs = o.optLong("durationMs"),
        posterPath = o.optString("posterPath"),
        posterMtimeUnix = o.optLong("posterMtimeUnix"),
        backdropPath = o.optString("backdropPath"),
        backdropMtimeUnix = o.optLong("backdropMtimeUnix"),
        overview = o.optString("overview"),
        genres = o.optString("genres"),
        rating = o.optDouble("rating"),
        showTitle = o.optString("showTitle"),
        seasonNumber = o.optInt("seasonNumber"),
        episodeNumber = o.optInt("episodeNumber"),
        episodeTitle = o.optString("episodeTitle"),
    )
}

private fun itemToJson(item: PopItem): JSONObject {
    return JSONObject()
        .put("id", item.id)
        .put("libraryId", item.libraryId)
        .put("kind", item.kind)
        .put("title", item.title)
        .put("year", item.year)
        .put("durationMs", item.durationMs)
        .put("posterPath", item.posterPath)
        .put("posterMtimeUnix", item.posterMtimeUnix)
        .put("backdropPath", item.backdropPath)
        .put("backdropMtimeUnix", item.backdropMtimeUnix)
        .put("overview", item.overview)
        .put("genres", item.genres)
        .put("rating", item.rating)
        .put("showTitle", item.showTitle)
        .put("seasonNumber", item.seasonNumber)
        .put("episodeNumber", item.episodeNumber)
        .put("episodeTitle", item.episodeTitle)
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

// ── API Client ──

class Api(private val session: Session) {
    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val json = request("/api/auth/login", "POST", body)
        val user = json.optJSONObject("user")
        Session(session.server, json.getString("token"), user?.optString("username").orEmpty())
    }

    suspend fun me(): User = withContext(Dispatchers.IO) {
        val json = request("/api/auth/me")
        User(
            id = json.optLong("id"),
            username = json.optString("username"),
            displayName = json.optString("displayName"),
        )
    }

    suspend fun startQrLogin(deviceName: String): QRLoginStart = withContext(Dispatchers.IO) {
        val json = request("/api/auth/qr/start", "POST", JSONObject().put("deviceName", deviceName).toString())
        QRLoginStart(json.getString("code"), json.optString("expiresAt"))
    }

    suspend fun pollQrLogin(code: String): Session? = withContext(Dispatchers.IO) {
        val json = request("/api/auth/qr/poll?code=${enc(code)}")
        if (json.optString("status") != "approved") return@withContext null
        val user = json.optJSONObject("user")
        Session(session.server, json.getString("token"), user?.optString("username").orEmpty())
    }

    suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/libraries")
        (0 until arr.length()).map { i ->
            jsonToLibrary(arr.getJSONObject(i))
        }
    }

    suspend fun item(itemId: Long): PopItem = withContext(Dispatchers.IO) {
        jsonToItem(request("/api/items/$itemId"))
    }

    suspend fun items(libraryId: String): List<PopItem> = withContext(Dispatchers.IO) {
        val pageSize = 500
        val out = mutableListOf<PopItem>()
        var offset = 0
        while (true) {
            val page = itemsPage(libraryId, pageSize, offset)
            out.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        out
    }

    suspend fun itemsPage(libraryId: String, limit: Int, offset: Int): List<PopItem> = withContext(Dispatchers.IO) {
        itemsPage(libraryId, limit, offset, "")
    }

    suspend fun itemsPage(libraryId: String, limit: Int, offset: Int, genre: String): List<PopItem> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset$genreParam"))
    }

    suspend fun recentItems(libraryId: String, limit: Int): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=0&sort=recent"))
    }

    suspend fun searchMovies(query: String): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestItemsEnvelope("/api/search?limit=120&kind=movie&q=${enc(query)}"))
    }

    suspend fun searchShows(query: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?limit=120&q=${enc(query)}"))
    }

    suspend fun shows(libraryId: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        val pageSize = 500
        val out = mutableListOf<ShowSummary>()
        var offset = 0
        while (true) {
            val page = showsPage(libraryId, pageSize, offset)
            out.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        out
    }

    suspend fun showsPage(libraryId: String, limit: Int, offset: Int): List<ShowSummary> = withContext(Dispatchers.IO) {
        showsPage(libraryId, limit, offset, "")
    }

    suspend fun showsPage(libraryId: String, limit: Int, offset: Int, genre: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset$genreParam"))
    }

    suspend fun recentShows(libraryId: String, limit: Int): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=0&sort=recent"))
    }

    suspend fun genres(libraryId: String): List<String> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/genres?libraryId=${enc(libraryId)}")
        (0 until arr.length()).map { i -> arr.getString(i) }
    }

    private fun parseShows(arr: JSONArray): List<ShowSummary> {
        return (0 until arr.length()).map { i ->
            jsonToShow(arr.getJSONObject(i))
        }
    }

    suspend fun seasons(libraryId: String, showTitle: String): List<SeasonSummary> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/seasons?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SeasonSummary(
                libraryId = o.getString("libraryId"),
                showTitle = o.getString("showTitle"),
                seasonNumber = o.optInt("seasonNumber"),
                title = o.optString("title"),
                episodeCount = o.optInt("episodeCount"),
                durationMs = o.optLong("durationMs"),
                posterItemId = o.optLong("posterItemId"),
                posterMtimeUnix = o.optLong("posterMtimeUnix"),
                backdropItemId = o.optLong("backdropItemId"),
                overview = o.optString("overview"),
                rating = o.optDouble("rating"),
            )
        }
    }

    suspend fun episodes(libraryId: String, showTitle: String, season: Int): List<PopItem> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/episodes?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season")
        parseItems(arr)
    }

    suspend fun streams(itemId: Long): List<StreamInfo> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/items/$itemId/streams")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StreamInfo(
                index = o.getInt("index"),
                type = o.getString("type"),
                codec = o.optString("codec"),
                language = o.optString("language"),
                title = o.optString("title"),
                default = o.optBoolean("default"),
                forced = o.optBoolean("forced"),
            )
        }
    }

    suspend fun progress(itemId: Long): PlaybackProgress = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/progress")
        PlaybackProgress(
            itemId = o.optLong("itemId", itemId),
            positionMs = o.optLong("positionMs"),
            durationMs = o.optLong("durationMs"),
            completed = o.optBoolean("completed"),
        )
    }

    suspend fun ratings(itemId: Long): ExternalRatings = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/ratings")
        ExternalRatings(
            imdbId = o.optString("imdbId"),
            tmdbId = o.optString("tmdbId"),
            localRating = o.optDouble("localRating"),
            imdbRating = o.optDouble("imdbRating"),
            tmdbRating = o.optDouble("tmdbRating"),
            rottenTomatoesRating = o.optInt("rottenTomatoesRating"),
            metacriticRating = o.optInt("metacriticRating"),
        )
    }

    suspend fun progressList(): List<PlaybackProgress> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlaybackProgress>()
        val limit = 500
        var offset = 0
        while (true) {
            val arr = requestArray("/api/progress?limit=$limit&offset=$offset")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PlaybackProgress(
                        itemId = o.optLong("itemId"),
                        positionMs = o.optLong("positionMs"),
                        durationMs = o.optLong("durationMs"),
                        completed = o.optBoolean("completed"),
                    )
                )
            }
            if (arr.length() < limit) break
            offset += limit
        }
        out
    }

    suspend fun showProgress(): List<ShowProgress> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/progress/tv")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ShowProgress(
                libraryId = o.getString("libraryId"),
                showTitle = o.getString("showTitle"),
                episodeCount = o.optInt("episodeCount"),
                completedCount = o.optInt("completedCount"),
                completed = o.optBoolean("completed"),
            )
        }
    }

    suspend fun watchlist(): Watchlist = withContext(Dispatchers.IO) {
        val json = request("/api/watchlist?limit=300")
        val itemsArr = json.optJSONArray("items") ?: JSONArray()
        val showsArr = json.optJSONArray("shows") ?: JSONArray()
        Watchlist(
            items = parseItems(itemsArr),
            shows = parseShows(showsArr),
        )
    }

    suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long, completed: Boolean, state: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("completed", completed)
            .put("state", state)
            .toString()
        request("/api/items/$itemId/progress", "PUT", body)
    }

    suspend fun markItemWatched(item: PopItem) = withContext(Dispatchers.IO) {
        val duration = if (item.durationMs > 0) item.durationMs else 1L
        saveProgress(item.id, duration, duration, true, "manual")
    }

    suspend fun unmarkItemWatched(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/progress", "DELETE", null)
    }

    suspend fun markShowWatched(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        request("/api/progress/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "PUT", "{}")
    }

    suspend fun unmarkShowWatched(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        requestText("/api/progress/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "DELETE", null)
    }

    suspend fun addItemWatchlist(itemId: Long) = withContext(Dispatchers.IO) {
        request("/api/items/$itemId/watchlist", "PUT", "{}")
    }

    suspend fun removeItemWatchlist(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/watchlist", "DELETE", null)
    }

    suspend fun addShowWatchlist(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        request("/api/watchlist/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "PUT", "{}")
    }

    suspend fun removeShowWatchlist(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        requestText("/api/watchlist/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "DELETE", null)
    }

    suspend fun registerDevice(existingId: String?, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("id", existingId ?: "")
            .put("name", name)
            .put("kind", "tv")
            .toString()
        request("/api/devices/register", "POST", body).getString("id")
    }

    suspend fun remoteCommands(deviceId: String, after: Long): List<RemoteCommand> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/devices/${enc(deviceId)}/commands?after=$after")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteCommand(
                id = o.optLong("id"),
                type = o.optString("type"),
                payload = o.optJSONObject("payload") ?: JSONObject(),
            )
        }
    }

    suspend fun putDeviceState(deviceId: String, itemId: Long, title: String, state: String, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("itemId", itemId)
            .put("title", title)
            .put("state", state)
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .toString()
        requestText("/api/devices/${enc(deviceId)}/state", "PUT", body)
    }

    private fun parseItems(arr: JSONArray): List<PopItem> = (0 until arr.length()).map { i ->
        jsonToItem(arr.getJSONObject(i))
    }

    private fun requestItemsEnvelope(path: String): JSONArray {
        val text = requestText(path, "GET", null)
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "null") return JSONArray()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val json = JSONObject(trimmed)
        val items = json.optJSONArray("items")
        if (items != null) return items
        return JSONArray()
    }

    private fun requestArray(path: String): JSONArray {
        val text = requestText(path, "GET", null)
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "null") return JSONArray()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val json = JSONObject(trimmed)
        return json.optJSONArray("items") ?: JSONArray()
    }

    private fun request(path: String, method: String = "GET", body: String? = null): JSONObject {
        return JSONObject(requestText(path, method, body))
    }

    private fun requestText(path: String, method: String, body: String?): String {
        val conn = URL(session.server + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) error(text.ifBlank { "HTTP $code" })
        return text
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
