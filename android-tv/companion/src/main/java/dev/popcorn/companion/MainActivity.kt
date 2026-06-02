package dev.popcorn.companion

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PopcornColorScheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CompanionApp()
                }
            }
        }
    }
}

@Composable
fun CompanionApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("popcorn-remote", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var session by remember {
        mutableStateOf(
            prefs.getString("token", null)?.let {
                Session(
                    prefs.getString("server", "http://localhost:8097") ?: "http://localhost:8097",
                    it,
                    prefs.getString("username", "") ?: "",
                )
            }
        )
    }
    var pendingQrCode by remember { mutableStateOf("") }
    var pendingQrServer by remember { mutableStateOf("") }
    var pendingSetupCode by remember { mutableStateOf("") }
    var pendingSetupCallback by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun completeQr(active: Session, code: String = pendingQrCode) {
        if (code.isBlank()) return
        scope.launch {
            runCatching { Api(active).completeQr(code) }
                .onSuccess {
                    pendingQrCode = ""
                    pendingQrServer = ""
                    error = "Shield login approved"
                }
                .onFailure { error = it.message ?: "QR approval failed" }
        }
    }

    fun completeShieldSetup(active: Session, callback: String = pendingSetupCallback, code: String = pendingSetupCode) {
        if (callback.isBlank() || code.isBlank()) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { postShieldSetup(callback, code, active) } }
                .onSuccess {
                    pendingSetupCode = ""
                    pendingSetupCallback = ""
                    error = "Shield login approved"
                }
                .onFailure { error = "Shield setup failed: ${it.message ?: it::class.java.simpleName}" }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val parsed = parseQr(result.contents.orEmpty())
        if (parsed == null) {
            error = "That QR code is not a Popcorn login code"
            return@rememberLauncherForActivityResult
        }
        when (parsed.type) {
            "popcorn-shield-setup" -> {
                pendingSetupCode = parsed.code
                pendingSetupCallback = parsed.callback
                val active = session
                if (active != null) {
                    completeShieldSetup(active, parsed.callback, parsed.code)
                } else {
                    error = "Sign in on the phone, then the Shield will be approved"
                }
            }
            "popcorn-login" -> {
                pendingQrCode = parsed.code
                pendingQrServer = parsed.server
                val active = session
                if (active != null && sameServer(active.server, parsed.server)) {
                    completeQr(active, parsed.code)
                } else {
                    session = null
                    prefs.edit().putString("server", parsed.server).remove("token").apply()
                    error = "Sign in to approve the Shield login"
                }
            }
        }
    }

    if (session == null) {
        LoginView(
            initialServer = pendingQrServer.ifBlank { prefs.getString("server", "http://localhost:8097") ?: "http://localhost:8097" },
            error = error,
            onScan = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the Popcorn QR on your TV")
                        .setBeepEnabled(false)
                        .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
                )
            },
            onLogin = { server, username, password ->
                scope.launch {
                    error = ""
                    runCatching { Api(Session(server.trimEnd('/'), "")).login(username, password) }
                        .onSuccess {
                            prefs.edit()
                                .putString("server", it.server)
                                .putString("token", it.token)
                                .putString("username", it.username)
                                .apply()
                            session = it
                            if (pendingSetupCallback.isNotBlank()) {
                                completeShieldSetup(it)
                            } else {
                                completeQr(it)
                            }
                        }
                        .onFailure { error = it.message ?: "Login failed" }
                }
            },
        )
    } else {
        BrowserView(
            session = session!!,
            error = error,
            onError = { error = it },
            onLogout = {
                prefs.edit().remove("token").apply()
                session = null
                error = ""
            },
            onScan = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Popcorn QR on your TV").setBeepEnabled(false))
            },
        )
    }
}

@Composable
fun BrowserView(session: Session, error: String, onError: (String) -> Unit, onLogout: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Page>(Page.Home) }
    var backStack by remember { mutableStateOf<List<Page>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var state by remember { mutableStateOf(PlayerState(0, "", "idle", 0, 0)) }
    var movies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var shows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var recentMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var recentShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<SeasonSummary>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var searchShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var moviePage by remember { mutableIntStateOf(0) }
    var showPage by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var selectedBandwidth by remember { mutableStateOf<Int?>(null) }
    var playbackTarget by remember { mutableStateOf(PlaybackTarget.Shield) }
    var phoneState by remember { mutableStateOf(PhonePlaybackState()) }
    var phoneAudioIndex by remember { mutableStateOf<Int?>(null) }
    var phoneSubtitleIndex by remember { mutableStateOf<Int?>(null) }
    var phoneHlsSession by remember { mutableStateOf<String?>(null) }
    var phoneStreamBaseMs by remember { mutableStateOf(0L) }
    val api = remember(session) { Api(session) }
    val localPlayer = remember(session) {
        val headers = if (session.token.isNotBlank()) mapOf("Authorization" to "Bearer ${session.token}") else emptyMap()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    val movieLib = libraries.firstOrNull { it.type == "movies" }
    val tvLib = libraries.firstOrNull { it.type == "tv" }

    DisposableEffect(localPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error.message ?: "Phone playback failed")
            }
        }
        localPlayer.addListener(listener)
        onDispose {
            localPlayer.removeListener(listener)
            localPlayer.release()
        }
    }

    fun navigate(next: Page, stack: Boolean = true) {
        if (stack) backStack = backStack + page
        page = next
    }

    fun goBack() {
        val previous = backStack.lastOrNull()
        if (previous != null) {
            backStack = backStack.dropLast(1)
            page = previous
        } else {
            page = Page.Home
        }
    }

    fun send(type: String, payload: JSONObject = JSONObject()) {
        val device = selectedDevice ?: return onError("No Shield selected")
        scope.launch {
            runCatching { api.sendCommand(device.id, type, payload) }
                .onFailure { onError(it.message ?: "Remote command failed") }
        }
    }

    fun sendBandwidth(kbps: Int?) {
        selectedBandwidth = kbps
        val payload = JSONObject()
        if (kbps == null) payload.put("bandwidthKbps", JSONObject.NULL) else payload.put("bandwidthKbps", kbps)
        send("bandwidth", payload)
    }

    fun play(itemId: Long, audioIndex: Int?, subtitleIndex: Int?) {
        val device = selectedDevice ?: return onError("No Shield selected")
        val payload = JSONObject().put("itemId", itemId)
        if (audioIndex != null) payload.put("audioIndex", audioIndex)
        if (subtitleIndex != null) payload.put("subtitleIndex", subtitleIndex)
        val bandwidth = selectedBandwidth
        scope.launch {
            runCatching {
                val remoteState = runCatching { api.deviceState(device.id) }.getOrNull() ?: state
                if (remoteState.isActivePlayback()) {
                    api.sendCommand(device.id, "stop", JSONObject())
                    delay(900)
                }
                api.sendCommand(device.id, "playItem", payload)
                if (bandwidth != null) {
                    delay(900)
                    val bandwidthPayload = JSONObject().put("bandwidthKbps", bandwidth)
                    api.sendCommand(device.id, "bandwidth", bandwidthPayload)
                }
            }.onFailure {
                onError(it.message ?: "Remote play failed")
            }
        }
    }

    fun phoneAbsolutePosition(): Long {
        val item = phoneState.item ?: return 0
        val raw = phoneStreamBaseMs + localPlayer.currentPosition.coerceAtLeast(0)
        return raw.coerceIn(0, (localPlayer.duration.takeIf { it > 0 } ?: item.durationMs).coerceAtLeast(item.durationMs))
    }

    fun savePhoneProgress(final: Boolean = false) {
        val item = phoneState.item ?: return
        val duration = (localPlayer.duration.takeIf { it > 0 } ?: item.durationMs).coerceAtLeast(0)
        val position = phoneAbsolutePosition()
        if (position <= 0 || duration <= 0) return
        scope.launch {
            runCatching {
                api.saveProgress(
                    item.id,
                    position.coerceIn(0, duration),
                    duration,
                    completed = final || position >= (duration * 0.9).toLong(),
                )
            }
        }
    }

    fun stopPhonePlayback() {
        savePhoneProgress(final = false)
        localPlayer.stop()
        phoneHlsSession?.let { sessionId ->
            scope.launch { runCatching { api.stopHls(sessionId) } }
        }
        phoneHlsSession = null
        phoneStreamBaseMs = 0L
        phoneState = PhonePlaybackState()
        if (playbackTarget == PlaybackTarget.Phone) playbackTarget = PlaybackTarget.Shield
    }

    fun loadPhonePlayback(item: PopItem, audioIndex: Int?, subtitleIndex: Int?, bandwidthKbps: Int?, startMs: Long) {
        phoneHlsSession?.let { sessionId ->
            scope.launch { runCatching { api.stopHls(sessionId) } }
        }
        phoneAudioIndex = audioIndex
        phoneSubtitleIndex = subtitleIndex
        selectedBandwidth = bandwidthKbps
        playbackTarget = PlaybackTarget.Phone
        val duration = item.durationMs.coerceAtLeast(0)
        val target = startMs.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val url: String
        if (bandwidthKbps == null) {
            phoneHlsSession = null
            phoneStreamBaseMs = 0L
            url = streamUrl(session, item.id)
        } else {
            val hlsId = "phone_${item.id}_${System.currentTimeMillis()}"
            phoneHlsSession = hlsId
            phoneStreamBaseMs = target
            url = hlsUrl(session, item.id, hlsId, bandwidthKbps, target, audioIndex, subtitleIndex)
        }
        phoneState = PhonePlaybackState(item = item, state = "loading", positionMs = target, durationMs = duration, bandwidthKbps = bandwidthKbps)
        localPlayer.setMediaItem(MediaItem.fromUri(url))
        localPlayer.prepare()
        if (bandwidthKbps == null && target > 0) localPlayer.seekTo(target)
        localPlayer.playWhenReady = true
    }

    fun playLocally(item: PopItem, audioIndex: Int?, subtitleIndex: Int?) {
        scope.launch {
            val resume = runCatching { api.progress(item.id) }.getOrNull()
            val start = resume?.takeIf { !it.completed }?.positionMs ?: 0L
            loadPhonePlayback(item, audioIndex, subtitleIndex, null, start)
            navigate(Page.LocalPlayer(item, audioIndex, subtitleIndex, page))
        }
    }

    fun seekTo(positionMs: Long) {
        if (playbackTarget == PlaybackTarget.Phone && phoneState.item != null) {
            val item = phoneState.item ?: return
            val target = positionMs.coerceAtLeast(0)
            if (phoneState.bandwidthKbps == null) {
                localPlayer.seekTo(target)
            } else {
                loadPhonePlayback(item, phoneAudioIndex, phoneSubtitleIndex, phoneState.bandwidthKbps, target)
            }
            phoneState = phoneState.copy(positionMs = target)
            savePhoneProgress()
        } else {
            send("seek", JSONObject().put("positionMs", positionMs.coerceAtLeast(0)))
        }
    }

    fun openDetail(item: PopItem) {
        navigate(Page.Detail(item, page))
    }

    fun loadMovies(pageIndex: Int) {
        val lib = movieLib ?: return
        scope.launch {
            loading = true
            val cached = CompanionCache.readItems(context, session, "movies_${lib.id}_$pageIndex")
            if (cached.isNotEmpty()) {
                movies = cached
                moviePage = pageIndex
                page = Page.Movies
                backStack = emptyList()
            }
            runCatching { api.itemsPage(lib.id, 60, pageIndex * 60) }
                .onSuccess {
                    movies = it
                    moviePage = pageIndex
                    page = Page.Movies
                    backStack = emptyList()
                    CompanionCache.writeItems(context, session, "movies_${lib.id}_$pageIndex", it)
                }
                .onFailure { onError(it.message ?: "Movies failed") }
            loading = false
        }
    }

    fun loadShows(pageIndex: Int) {
        val lib = tvLib ?: return
        scope.launch {
            loading = true
            val cached = CompanionCache.readShows(context, session, "shows_${lib.id}_$pageIndex")
            if (cached.isNotEmpty()) {
                shows = cached
                showPage = pageIndex
                page = Page.Shows
                backStack = emptyList()
            }
            runCatching { api.showsPage(lib.id, 60, pageIndex * 60) }
                .onSuccess {
                    shows = it
                    showPage = pageIndex
                    page = Page.Shows
                    backStack = emptyList()
                    CompanionCache.writeShows(context, session, "shows_${lib.id}_$pageIndex", it)
                }
                .onFailure { onError(it.message ?: "Shows failed") }
            loading = false
        }
    }

    fun openShow(show: ShowSummary) {
        scope.launch {
            loading = true
            val key = "seasons_${show.libraryId}_${show.title}"
            val cached = CompanionCache.readSeasons(context, session, key)
            if (cached.isNotEmpty()) {
                seasons = cached
                navigate(Page.Show(show))
            }
            runCatching { api.seasons(show.libraryId, show.title) }
                .onSuccess {
                    seasons = it
                    CompanionCache.writeSeasons(context, session, key, it)
                    if (page !is Page.Show) navigate(Page.Show(show))
                }
                .onFailure { onError(it.message ?: "Seasons failed") }
            loading = false
        }
    }

    fun openSeason(show: ShowSummary, season: SeasonSummary) {
        scope.launch {
            loading = true
            val key = "episodes_${show.libraryId}_${show.title}_${season.seasonNumber}"
            val cached = CompanionCache.readItems(context, session, key)
            if (cached.isNotEmpty()) {
                episodes = cached
                navigate(Page.Season(show, season))
            }
            runCatching { api.episodes(show.libraryId, show.title, season.seasonNumber) }
                .onSuccess {
                    episodes = it
                    CompanionCache.writeItems(context, session, key, it)
                    if (page !is Page.Season) navigate(Page.Season(show, season))
                }
                .onFailure { onError(it.message ?: "Episodes failed") }
            loading = false
        }
    }

    LaunchedEffect(session) {
        loading = true
        val cachedLibraries = CompanionCache.readLibraries(context, session)
        if (cachedLibraries.isNotEmpty()) {
            libraries = cachedLibraries
            val cachedMovieLib = cachedLibraries.firstOrNull { it.type == "movies" }
            val cachedTvLib = cachedLibraries.firstOrNull { it.type == "tv" }
            if (cachedMovieLib != null) recentMovies = CompanionCache.readItems(context, session, "recent_movies_${cachedMovieLib.id}")
            if (cachedTvLib != null) recentShows = CompanionCache.readShows(context, session, "recent_shows_${cachedTvLib.id}")
        }
        runCatching {
            libraries = api.libraries()
            CompanionCache.writeLibraries(context, session, libraries)
            devices = uniquePlaybackDevices(api.devices())
            selectedDevice = devices.firstOrNull { it.kind == "tv" } ?: devices.firstOrNull()
            val ml = libraries.firstOrNull { it.type == "movies" }
            val tl = libraries.firstOrNull { it.type == "tv" }
            if (ml != null) {
                recentMovies = api.recentItems(ml.id, 12)
                CompanionCache.writeItems(context, session, "recent_movies_${ml.id}", recentMovies)
            }
            if (tl != null) {
                recentShows = api.recentShows(tl.id, 12)
                CompanionCache.writeShows(context, session, "recent_shows_${tl.id}", recentShows)
            }
        }.onFailure { onError(it.message ?: "Load failed") }
        loading = false
    }

    LaunchedEffect(selectedDevice?.id) {
        while (true) {
            val id = selectedDevice?.id
            if (id != null) runCatching { state = api.deviceState(id) }
            delay(1000)
        }
    }

    LaunchedEffect(localPlayer, phoneState.item?.id, phoneStreamBaseMs) {
        var lastSavedAt = 0L
        while (true) {
            val item = phoneState.item
            if (item != null) {
                val duration = (localPlayer.duration.takeIf { it > 0 } ?: item.durationMs).coerceAtLeast(0)
                val position = phoneAbsolutePosition()
                val label = when {
                    localPlayer.playbackState == Player.STATE_BUFFERING -> "buffering"
                    localPlayer.playbackState == Player.STATE_ENDED -> "ended"
                    localPlayer.isPlaying -> "playing"
                    localPlayer.playbackState == Player.STATE_IDLE -> "idle"
                    else -> "paused"
                }
                phoneState = phoneState.copy(state = label, positionMs = position, durationMs = duration)
                val now = System.currentTimeMillis()
                if (position > 0 && duration > 0 && now - lastSavedAt >= 5000) {
                    lastSavedAt = now
                    savePhoneProgress(final = label == "ended")
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            searchMovies = emptyList()
            searchShows = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        if (q == query.trim()) {
            runCatching {
                searchMovies = api.searchMovies(q)
                searchShows = api.searchShows(q)
            }.onFailure { onError(it.message ?: "Search failed") }
        }
    }

    BackHandler(enabled = page !is Page.Home || backStack.isNotEmpty()) {
        if (page is Page.Remote) {
            page = Page.Home
            backStack = emptyList()
        } else {
            goBack()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (page !is Page.Remote && page !is Page.LocalPlayer) {
            Scaffold(
            topBar = {
                CompanionTopAppBar(
                    devices = devices,
                    selectedDevice = selectedDevice,
                    playbackTarget = playbackTarget,
                    onSelectPhone = { playbackTarget = PlaybackTarget.Phone },
                    onSelectDevice = {
                        selectedDevice = it
                        playbackTarget = PlaybackTarget.Shield
                    },
                    onScan = onScan,
                    onLogout = onLogout,
                    onRefreshDevices = {
                        scope.launch {
                            runCatching {
                                val loaded = uniquePlaybackDevices(api.devices())
                                devices = loaded
                                selectedDevice = loaded.firstOrNull { it.id == selectedDevice?.id }
                                    ?: loaded.firstOrNull { it.kind == "tv" }
                                    ?: loaded.firstOrNull()
                            }.onFailure { onError(it.message ?: "Device refresh failed") }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    val miniState = if (playbackTarget == PlaybackTarget.Phone) {
                        PlayerState(
                            itemId = phoneState.item?.id ?: 0,
                            title = phoneState.item?.let { displayTitle(it) } ?: "",
                            state = phoneState.state.ifBlank { "idle" },
                            positionMs = phoneState.positionMs,
                            durationMs = phoneState.durationMs,
                        )
                    } else {
                        state
                    }
                    MiniPlayer(
                        session = session,
                        state = miniState,
                        target = playbackTarget,
                        onPlayPause = {
                            if (playbackTarget == PlaybackTarget.Phone && phoneState.item != null) {
                                localPlayer.playWhenReady = !localPlayer.isPlaying
                            } else {
                                if (state.state == "playing") send("pause") else send("resume")
                            }
                        },
                        onSeek = { delta -> seekTo(miniState.positionMs + delta) },
                        onClick = {
                            if (playbackTarget == PlaybackTarget.Phone && phoneState.item != null) {
                                val item = phoneState.item!!
                                navigate(Page.LocalPlayer(item, phoneAudioIndex, phoneSubtitleIndex, page), stack = false)
                            } else if (playbackTarget == PlaybackTarget.Shield) {
                                navigate(Page.Remote, stack = false)
                            }
                        },
                    )
                    BottomNavigation(
                        page = page,
                        onHome = { page = Page.Home; backStack = emptyList() },
                        onMovies = { loadMovies(moviePage) },
                        onShows = { loadShows(showPage) },
                        onSearch = { page = Page.Search; backStack = emptyList() },
                        onRemote = {
                            if (playbackTarget == PlaybackTarget.Phone && phoneState.item != null) {
                                page = Page.LocalPlayer(phoneState.item!!, phoneAudioIndex, phoneSubtitleIndex, page)
                            } else if (playbackTarget == PlaybackTarget.Shield) {
                                page = Page.Remote
                            } else {
                                page = Page.Home
                            }
                            backStack = emptyList()
                        },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (error.isNotBlank()) Text(error, color = if (error.contains("approved", true)) Accent else MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }
                Crossfade(targetState = page, animationSpec = tween(220), label = "page") { current ->
                    Box(Modifier.fillMaxSize()) {
                        when (current) {
                            Page.Home -> HomePage(session, recentMovies, recentShows, onMovie = ::openDetail, onShow = ::openShow)
                            Page.Movies -> MediaGrid(
                                title = "Movies",
                                session = session,
                                items = movies,
                                pageIndex = moviePage,
                                onPrev = { if (moviePage > 0) loadMovies(moviePage - 1) },
                                onNext = { loadMovies(moviePage + 1) },
                                onOpen = ::openDetail,
                            )
                            Page.Shows -> ShowGrid("TV Shows", session, shows, showPage, onPrev = { if (showPage > 0) loadShows(showPage - 1) }, onNext = { loadShows(showPage + 1) }, onShow = ::openShow)
                            is Page.Show -> SeasonList(session, current.show, seasons, onBack = ::goBack, onSeason = { openSeason(current.show, it) })
                            is Page.Season -> EpisodeList(current.show, current.season, episodes, onBack = ::goBack, onOpen = ::openDetail)
                            is Page.Detail -> DetailPage(
                                session,
                                api,
                                current.item,
                                playbackTarget = playbackTarget,
                                selectedDeviceName = selectedDevice?.name,
                                onBack = ::goBack,
                                onPlay = { item, audio, subtitle -> play(item.id, audio, subtitle) },
                                onPlayLocal = ::playLocally,
                            )
                            Page.Search -> SearchPage(session, query, { query = it }, searchMovies, searchShows, onMovie = ::openDetail, onShow = ::openShow)
                            Page.Remote -> Unit
                            is Page.LocalPlayer -> Unit
                        }
                    }
                }
            }
            }
        }
        AnimatedVisibility(
            visible = page is Page.Remote,
            enter = slideInVertically(animationSpec = tween(260)) { it / 2 } + fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(animationSpec = tween(240)) { it / 2 } + fadeOut(animationSpec = tween(160)),
        ) {
            RemotePage(
                session = session,
                state = state,
                onBack = { page = Page.Home; backStack = emptyList() },
                onPause = { send("pause") },
                onResume = { send("resume") },
                onStop = { send("stop") },
                onSeek = { delta -> seekTo(state.positionMs + delta) },
                onSeekTo = ::seekTo,
                onJump = { jumpDialog = true },
                selectedBandwidth = selectedBandwidth,
                onBandwidth = ::sendBandwidth,
            )
        }
        AnimatedVisibility(
            visible = page is Page.LocalPlayer,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            val current = page
            if (current is Page.LocalPlayer) {
                LocalPlayerPage(
                    session = session,
                    api = api,
                    player = localPlayer,
                    item = current.item,
                    state = phoneState,
                    selectedAudio = phoneAudioIndex,
                    selectedSubtitle = phoneSubtitleIndex,
                    selectedBandwidth = phoneState.bandwidthKbps,
                    onAudio = { audio ->
                        loadPhonePlayback(current.item, audio, phoneSubtitleIndex, phoneState.bandwidthKbps ?: 8000, phoneAbsolutePosition())
                    },
                    onSubtitle = { subtitle ->
                        loadPhonePlayback(current.item, phoneAudioIndex, subtitle, phoneState.bandwidthKbps ?: 8000, phoneAbsolutePosition())
                    },
                    onBandwidth = { bandwidth ->
                        loadPhonePlayback(current.item, phoneAudioIndex, phoneSubtitleIndex, bandwidth, phoneAbsolutePosition())
                    },
                    onBack = ::goBack,
                    onStop = {
                        stopPhonePlayback()
                        goBack()
                    },
                    onSeekTo = ::seekTo,
                    onError = onError,
                )
            }
        }
    }

    if (jumpDialog) {
        JumpDialog(
            durationMs = state.durationMs,
            onDismiss = { jumpDialog = false },
            onJump = {
                jumpDialog = false
                seekTo(it)
            },
        )
    }
}

fun displayTitle(item: PopItem): String = item.episodeTitle.ifBlank { item.title }
fun fmtDuration(ms: Long): String = if (ms <= 0) "" else "${(ms / 60000).coerceAtLeast(1)}m"
private fun Int.floorMod(n: Int): Int = ((this % n) + n) % n
fun Device.displayName(): String = cleanDeviceName(name) ?: id
fun cleanDeviceName(raw: String): String? {
    val trimmed = raw.trim().replace(Regex("\\s+"), " ")
    if (trimmed.isBlank()) return null
    return trimmed
        .replace(Regex("(?i)\\b(shield)\\s+\\1\\b"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
}
private fun uniquePlaybackDevices(devices: List<Device>): List<Device> {
    val seen = linkedSetOf<String>()
    return devices.filter { device ->
        val key = listOf(
            device.kind.trim().lowercase().ifBlank { "device" },
            device.displayName().trim().lowercase().ifBlank { device.id.trim().lowercase() },
        ).joinToString("\u0000")
        seen.add(key)
    }
}
