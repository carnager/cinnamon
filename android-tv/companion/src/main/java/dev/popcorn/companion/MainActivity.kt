package dev.popcorn.companion

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.util.Log
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackPrefs.load(this)
        setContent {
            MaterialTheme(colorScheme = PopcornColorScheme, typography = PopcornTypography, shapes = PopcornShapes) {
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
                    prefs.getBoolean("isAdmin", false),
                    prefs.getLong("userId", 0),
                    prefs.getString("avatar", "") ?: "",
                    prefs.getString("displayName", "") ?: "",
                )
            }
        )
    }
    var pendingQrCode by remember { mutableStateOf("") }
    var pendingQrServer by remember { mutableStateOf("") }
    var pendingSetupCode by remember { mutableStateOf("") }
    var pendingSetupCallback by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun persistSession(active: Session) {
        prefs.edit()
            .putString("server", active.server)
            .putString("token", active.token)
            .putString("username", active.username)
            .putBoolean("isAdmin", active.isAdmin)
            .putLong("userId", active.userId)
            .putString("avatar", active.avatar)
            .putString("displayName", active.displayName)
            .apply()
    }

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
                val active = session
                if (active != null && sameServer(active.server, parsed.server)) {
                    pendingQrCode = parsed.code
                    pendingQrServer = parsed.server
                    completeQr(active, parsed.code)
                } else if (active != null) {
                    error = "That sign-in code belongs to a different Cinnamon server"
                } else {
                    val server = parsed.server.ifBlank { prefs.getString("server", "").orEmpty() }.trimEnd('/')
                    if (server.isBlank()) {
                        error = "That sign-in code does not include a server address"
                    } else {
                        scope.launch {
                            error = "Signing in…"
                            runCatching { Api(Session(server, "")).claimQr(parsed.code) }
                                .onSuccess {
                                    persistSession(it)
                                    session = it
                                    pendingQrCode = ""
                                    pendingQrServer = ""
                                    error = ""
                                }
                                .onFailure { error = it.message ?: "QR sign-in failed" }
                        }
                    }
                }
            }
        }
    }

    // Hydrate sessions saved by older app versions so the user menu can show
    // the current display name, role, and avatar without requiring a re-login.
    LaunchedEffect(session?.token) {
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).refreshSession() }
            .onSuccess {
                persistSession(it)
                session = it
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
                            persistSession(it)
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
                val active = session!!
                scope.launch { runCatching { Api(active).logout() } }
                prefs.edit()
                    .remove("token")
                    .remove("username")
                    .remove("isAdmin")
                    .remove("userId")
                    .remove("avatar")
                    .remove("displayName")
                    .apply()
                session = null
                error = ""
            },
            onScan = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the Popcorn QR on your TV").setBeepEnabled(false))
            },
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun BrowserView(session: Session, error: String, onError: (String) -> Unit, onLogout: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val controlPrefs = remember { context.getSharedPreferences("cinnamon-playback-target", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Page>(Page.Home) }
    var backStack by remember { mutableStateOf<List<Page>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var state by remember { mutableStateOf(PlayerState(0, "", "idle", 0, 0)) }
    var movies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var shows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var continueMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var continueEpisodes by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var resumeProgress by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    var recentMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var recentShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var topMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var topShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var seasons by remember { mutableStateOf<List<SeasonSummary>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var completedItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var completedShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var watchlistItems by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var watchlistShows by remember { mutableStateOf<Set<String>>(emptySet()) }
    var watchlistMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var watchlistTvShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchMovies by remember { mutableStateOf<List<PopItem>>(emptyList()) }
    var searchShows by remember { mutableStateOf<List<ShowSummary>>(emptyList()) }
    var searchScope by remember { mutableStateOf("both") }
    var searchFields by remember { mutableStateOf(setOf("title")) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var movieNextOffset by remember { mutableIntStateOf(0) }
    var showNextOffset by remember { mutableIntStateOf(0) }
    var movieStartOffset by remember { mutableIntStateOf(0) }
    var showStartOffset by remember { mutableIntStateOf(0) }
    var movieHasMore by remember { mutableStateOf(true) }
    var showHasMore by remember { mutableStateOf(true) }
    var movieHasPrevious by remember { mutableStateOf(false) }
    var showHasPrevious by remember { mutableStateOf(false) }
    var movieLoadingMore by remember { mutableStateOf(false) }
    var showLoadingMore by remember { mutableStateOf(false) }
    var movieLoadingPrevious by remember { mutableStateOf(false) }
    var showLoadingPrevious by remember { mutableStateOf(false) }
    var movieScrollToken by remember { mutableIntStateOf(0) }
    var showScrollToken by remember { mutableIntStateOf(0) }
    var movieScrollIndex by remember { mutableIntStateOf(0) }
    var showScrollIndex by remember { mutableIntStateOf(0) }
    var movieLoadGeneration by remember { mutableIntStateOf(0) }
    var showLoadGeneration by remember { mutableIntStateOf(0) }
    var movieFilters by remember { mutableStateOf(LibraryFilters()) }
    var showFilters by remember { mutableStateOf(LibraryFilters()) }
    var movieGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var showGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var movieDecades by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showDecades by remember { mutableStateOf<List<Int>>(emptyList()) }
    var movieAlphabet by remember { mutableStateOf<List<AlphabetEntry>>(emptyList()) }
    var showAlphabet by remember { mutableStateOf<List<AlphabetEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var selectedBandwidth by remember { mutableStateOf<Int?>(null) }
    var companionUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var announcedCompanionUpdate by remember { mutableIntStateOf(0) }
    var playbackTarget by remember {
        mutableStateOf(if (controlPrefs.getString("kind", "tv") == "phone") PlaybackTarget.Phone else PlaybackTarget.Shield)
    }
    var phoneState by remember { mutableStateOf(PhonePlaybackState()) }
    var phoneAudioIndex by remember { mutableStateOf<Int?>(null) }
    var phoneSubtitleIndex by remember { mutableStateOf<Int?>(null) }
    var phoneHlsSession by remember { mutableStateOf<String?>(null) }
    var phoneStreamBaseMs by remember { mutableStateOf(0L) }
    var phonePlan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var phoneFallbackRetried by remember { mutableStateOf(false) }
    var phoneContinuousPlayedMs by remember { mutableStateOf(0L) }
    var phonePlaybackSampleAtMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val phonePlaybackProfile = remember(context) { buildPlaybackProfile(context) }
    val api = remember(session) { Api(session) }
    val localPlayer = remember(session) {
        val headers = if (session.token.isNotBlank()) mapOf("Authorization" to "Bearer ${session.token}") else emptyMap()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Keep streaming with the screen off (CPU + Wi-Fi stay awake), pause
            // when headphones are unplugged, and respect audio focus.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build()
    }
    val movieLib = libraries.firstOrNull { it.type == "movies" }
    val tvLib = libraries.firstOrNull { it.type == "tv" }
    fun showKey(libraryId: String, title: String): String = "${libraryId}\n${title.lowercase()}"
    fun showKey(show: ShowSummary): String = showKey(show.libraryId, show.title)
    fun reportError(error: Throwable, fallback: String) {
        if (error is CancellationException) return
        onError(error.message ?: fallback)
    }

    fun selectPhoneTarget() {
        playbackTarget = PlaybackTarget.Phone
        controlPrefs.edit().putString("kind", "phone").apply()
    }

    fun selectTvTarget(device: Device) {
        selectedDevice = device
        playbackTarget = PlaybackTarget.Shield
        controlPrefs.edit().putString("kind", "tv").putString("deviceId", device.id).apply()
    }

    fun publishCompanionUpdate(info: AppUpdateInfo?) {
        companionUpdate = info
        if (info?.available == true && info.versionCode > 0 && info.versionCode != announcedCompanionUpdate) {
            announcedCompanionUpdate = info.versionCode
            showUpdateDialog = true
        }
    }

    fun refreshMarkers() {
        scope.launch {
            runCatching {
                val progress = api.progressList()
                val showProgress = api.showProgress()
                val watchlist = api.watchlist()
                completedItems = progress.filter { it.completed }.map { it.itemId }.toSet()
                completedShows = showProgress.filter { it.completed }.map { showKey(it.libraryId, it.showTitle) }.toSet()
                watchlistItems = watchlist.items.map { it.id }.toSet()
                watchlistShows = watchlist.shows.map { showKey(it) }.toSet()
                watchlistMovies = watchlist.items
                watchlistTvShows = watchlist.shows
            }.onFailure { reportError(it, "Markers failed") }
        }
    }

    fun setItemWatched(item: PopItem, watched: Boolean) {
        scope.launch {
            runCatching {
                if (watched) api.markItemWatched(item) else api.unmarkItemWatched(item.id)
            }.onSuccess {
                completedItems = if (watched) completedItems + item.id else completedItems - item.id
                refreshMarkers()
            }.onFailure { reportError(it, "Could not update seen state") }
        }
    }

    fun setItemWatchlisted(item: PopItem, watchlisted: Boolean) {
        scope.launch {
            runCatching {
                if (watchlisted) api.addItemWatchlist(item.id) else api.removeItemWatchlist(item.id)
            }.onSuccess {
                watchlistItems = if (watchlisted) watchlistItems + item.id else watchlistItems - item.id
                refreshMarkers()
            }.onFailure { reportError(it, "Could not update watchlist") }
        }
    }

    fun setShowWatched(show: ShowSummary, watched: Boolean) {
        scope.launch {
            runCatching {
                if (watched) api.markShowWatched(show.libraryId, show.title) else api.unmarkShowWatched(show.libraryId, show.title)
            }.onSuccess {
                completedShows = if (watched) completedShows + showKey(show) else completedShows - showKey(show)
                refreshMarkers()
            }.onFailure { reportError(it, "Could not update seen state") }
        }
    }

    fun setSeasonWatched(show: ShowSummary, season: Int, watched: Boolean) {
        scope.launch {
            runCatching {
                if (watched) api.markSeasonWatched(show.libraryId, show.title, season) else api.unmarkSeasonWatched(show.libraryId, show.title, season)
            }.onSuccess { refreshMarkers() }
                .onFailure { reportError(it, "Could not update seen state") }
        }
    }

    suspend fun refreshDevices(selectIfNeeded: Boolean = true) {
        val loaded = uniquePlaybackDevices(api.devices())
        devices = loaded
        // Keep a TV resolved even while the phone is the active target, so the
        // remote has something to show the moment the user switches back.
        if (selectIfNeeded) {
            val current = selectedDevice
            if (current == null || loaded.none { it.id == current.id }) {
                val remembered = controlPrefs.getString("deviceId", "")
                selectedDevice = loaded.firstOrNull { it.id == remembered } ?: loaded.firstOrNull { it.kind == "tv" } ?: loaded.firstOrNull()
            }
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
        scope.launch {
            runCatching {
                if (selectedDevice == null) refreshDevices()
                val device = selectedDevice ?: error("No Shield selected")
                api.sendCommand(device.id, type, payload)
            }
                .onFailure { reportError(it, "Remote command failed") }
        }
    }

    fun sendBandwidth(kbps: Int?) {
        selectedBandwidth = kbps
        val payload = JSONObject()
        if (kbps == null) payload.put("bandwidthKbps", JSONObject.NULL) else payload.put("bandwidthKbps", kbps)
        send("bandwidth", payload)
    }

    // positionMs is sent explicitly so the TV starts exactly where the user
    // asked: resuming and starting over are both a deliberate choice here, not
    // something the TV should second-guess.
    fun play(itemId: Long, audioIndex: Int?, subtitleIndex: Int?, positionMs: Long = 0) {
        val payload = JSONObject().put("itemId", itemId).put("positionMs", positionMs.coerceAtLeast(0))
        if (audioIndex != null) payload.put("audioIndex", audioIndex)
        if (subtitleIndex != null) payload.put("subtitleIndex", subtitleIndex)
        val bandwidth = selectedBandwidth
        scope.launch {
            runCatching {
                if (selectedDevice == null) refreshDevices()
                val device = selectedDevice ?: error("No Shield selected")
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
                reportError(it, "Remote play failed")
            }
        }
    }

    fun phoneAbsolutePosition(): Long {
        val item = phoneState.item ?: return 0
        val raw = phoneStreamBaseMs + localPlayer.currentPosition.coerceAtLeast(0)
        return raw.coerceIn(0, (localPlayer.duration.takeIf { it > 0 } ?: item.durationMs).coerceAtLeast(item.durationMs))
    }

    fun resetPhoneProgressEvidence() {
        phoneContinuousPlayedMs = 0L
        phonePlaybackSampleAtMs = SystemClock.elapsedRealtime()
    }

    fun samplePhoneProgressEvidence() {
        val now = SystemClock.elapsedRealtime()
        if (localPlayer.isPlaying) phoneContinuousPlayedMs += (now - phonePlaybackSampleAtMs).coerceIn(0L, 2_000L)
        phonePlaybackSampleAtMs = now
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
                    completed = final,
                    continuousMs = phoneContinuousPlayedMs,
                )
            }
        }
    }

    fun stopPhonePlayback() {
        savePhoneProgress(final = false)
        localPlayer.stop()
        context.stopService(Intent(context, PlaybackService::class.java))
        phoneHlsSession?.let { sessionId ->
            scope.launch { runCatching { api.stopHls(sessionId) } }
        }
        phoneHlsSession = null
        phoneStreamBaseMs = 0L
        phonePlan = null
        phoneFallbackRetried = false
        resetPhoneProgressEvidence()
        phoneState = PhonePlaybackState()
    }

    fun absolutePlanUrl(plan: PlaybackPlan): String {
        return if (plan.url.startsWith("http://") || plan.url.startsWith("https://")) plan.url else session.server + plan.url
    }

    fun phoneSubtitleUrl(itemId: Long, subtitleIndex: Int, startMs: Long): String {
        val startSeconds = "%.3f".format(java.util.Locale.US, startMs.coerceAtLeast(0) / 1000.0)
        return "${session.server}/api/items/$itemId/subtitles/$subtitleIndex.vtt?start=$startSeconds"
    }

    fun phoneMediaItem(item: PopItem, plan: PlaybackPlan): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(absolutePlanUrl(plan)))
        val subtitleIndex = plan.selectedSubtitleIndex
        if (subtitleIndex != null) {
            val subtitleStartMs = if (plan.usesHls) plan.startPositionMs else 0L
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(phoneSubtitleUrl(item.id, subtitleIndex, subtitleStartMs)))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    fun applyPhonePlan(item: PopItem, plan: PlaybackPlan, bandwidthKbps: Int?, requestedStartMs: Long) {
        if (!plan.playable || plan.url.isBlank()) return
        phoneHlsSession?.let { sessionId ->
            scope.launch { runCatching { api.stopHls(sessionId) } }
        }
        val duration = item.durationMs.coerceAtLeast(0)
        val target = requestedStartMs.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        phonePlan = plan
        phoneHlsSession = plan.sessionId.ifBlank { null }
        phoneStreamBaseMs = if (plan.usesHls) plan.startPositionMs.coerceAtLeast(0) else 0L
        plan.selectedAudioIndex?.let { phoneAudioIndex = it }
        phoneSubtitleIndex = plan.selectedSubtitleIndex
        localPlayer.trackSelectionParameters = localPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, plan.selectedSubtitleIndex == null)
            .build()
        phoneState = PhonePlaybackState(item = item, state = "loading", positionMs = target, durationMs = duration, bandwidthKbps = bandwidthKbps)
        localPlayer.setMediaItem(phoneMediaItem(item, plan))
        localPlayer.prepare()
        if (!plan.usesHls && target > 0) localPlayer.seekTo(target)
        localPlayer.playWhenReady = true
        // Promote to a media foreground service so playback keeps running with the
        // screen off / app backgrounded. Safe here: playback is user-initiated, so
        // the app is in the foreground.
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
    }

    fun loadPhonePlayback(item: PopItem, audioIndex: Int?, subtitleIndex: Int?, bandwidthKbps: Int?, startMs: Long, forceMode: String = "auto") {
        if (phoneState.item?.id != item.id) resetPhoneProgressEvidence()
        phoneAudioIndex = audioIndex
        phoneSubtitleIndex = subtitleIndex
        selectedBandwidth = bandwidthKbps
        selectPhoneTarget()
        val duration = item.durationMs.coerceAtLeast(0)
        val target = startMs.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        phoneState = PhonePlaybackState(item = item, state = "loading", positionMs = target, durationMs = duration, bandwidthKbps = bandwidthKbps)
        scope.launch {
            runCatching {
                api.playbackPlan(
                    itemId = item.id,
                    startPositionMs = target,
                    audioIndex = audioIndex,
                    subtitleIndex = subtitleIndex,
                    bandwidthKbps = bandwidthKbps,
                    forceMode = forceMode,
                    profile = phonePlaybackProfile,
                )
            }.onSuccess { plan ->
                phoneFallbackRetried = false
                applyPhonePlan(item, plan, bandwidthKbps, target)
            }.onFailure { reportError(it, "Phone playback failed") }
        }
    }

    DisposableEffect(localPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val item = phoneState.item
                val failedPlan = phonePlan
                if (item == null || failedPlan == null || phoneFallbackRetried) {
                    onError(error.message ?: "Phone playback failed")
                    return
                }
                phoneFallbackRetried = true
                val position = phoneAbsolutePosition()
                scope.launch {
                    runCatching {
                        api.playbackFailure(item.id, failedPlan, error.errorCodeName, error.message.orEmpty())
                    }.onSuccess { fallback ->
                        if (fallback != null) {
                            applyPhonePlan(item, fallback, selectedBandwidth, position)
                        } else {
                            onError(error.message ?: "Phone playback failed")
                        }
                    }.onFailure {
                        onError(it.message ?: "Phone playback failed")
                    }
                }
            }
        }
        localPlayer.addListener(listener)
        // Publish the player as a MediaSession. The media foreground service is
        // started only while something is actually playing (see applyPhonePlan /
        // stopPhonePlayback), so playback survives screen-off / app-switch without
        // keeping the process (and its polling) alive when idle.
        val mediaSession = MediaSession.Builder(context, localPlayer).build()
        PlaybackHolder.session = mediaSession
        onDispose {
            context.stopService(Intent(context, PlaybackService::class.java))
            PlaybackHolder.session = null
            mediaSession.release()
            localPlayer.removeListener(listener)
            localPlayer.release()
        }
    }

    // The caller decides the start position: the detail page already knows
    // whether the user asked to resume or start over, and re-deriving it here
    // would override that choice.
    fun playLocally(item: PopItem, audioIndex: Int?, subtitleIndex: Int?, positionMs: Long = 0) {
        loadPhonePlayback(item, audioIndex, subtitleIndex, null, positionMs.coerceAtLeast(0), "auto")
        navigate(Page.LocalPlayer(item, audioIndex, subtitleIndex, page))
    }

    fun seekTo(positionMs: Long) {
        if (playbackTarget == PlaybackTarget.Phone && phoneState.item != null) {
            resetPhoneProgressEvidence()
            val item = phoneState.item ?: return
            val target = positionMs.coerceAtLeast(0)
            if (phoneHlsSession == null) {
                localPlayer.seekTo(target)
            } else {
                loadPhonePlayback(item, phoneAudioIndex, phoneSubtitleIndex, phoneState.bandwidthKbps, target, "auto")
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

    fun openActor(actor: Actor) {
        navigate(Page.Person(actor))
    }

    fun loadMovies(offset: Int = 0, append: Boolean = false, prepend: Boolean = false, scrollIndex: Int = 0, pageLimit: Int = 60) {
        val lib = movieLib ?: return
        if (append && (movieLoadingMore || movieLoadingPrevious || !movieHasMore)) return
        if (prepend && (movieLoadingPrevious || movieLoadingMore || !movieHasPrevious)) return
        val filters = movieFilters
        val pageSize = 60
        val incremental = append || prepend
        val existingItems = if (incremental) movies else emptyList()
        val requestLimit = if (prepend) (movieStartOffset - offset).coerceIn(1, pageSize) else pageLimit
        if (!incremental) {
            movieLoadGeneration += 1
            movieLoadingMore = false
            movieLoadingPrevious = false
            movieHasMore = true
            movieHasPrevious = offset > 0
        }
        val generation = movieLoadGeneration
        when {
            append -> movieLoadingMore = true
            prepend -> movieLoadingPrevious = true
            else -> loading = true
        }
        scope.launch {
            val cacheKey = "movies_${lib.id}_${filterKey(filters)}_${offset}_$requestLimit"
            val cached = CompanionCache.readItems(context, session, cacheKey)
            var positionedFromCache = false
            if (cached.isNotEmpty() && generation == movieLoadGeneration) {
                movies = when {
                    append -> (existingItems + cached).distinctBy { it.id }
                    prepend -> (cached + existingItems).distinctBy { it.id }
                    else -> cached
                }
                if (prepend) {
                    movieStartOffset = offset
                    movieHasPrevious = offset > 0
                } else {
                    movieNextOffset = offset + cached.size
                    movieHasMore = cached.size == requestLimit
                    if (!append) {
                        movieStartOffset = offset
                        movieHasPrevious = offset > 0
                        movieScrollIndex = scrollIndex.coerceIn(0, cached.lastIndex.coerceAtLeast(0))
                        movieScrollToken += 1
                        positionedFromCache = true
                    }
                }
                page = Page.Movies
                backStack = emptyList()
            }
            val result = runCatching {
                if (!incremental) {
                    val genres = api.genres(lib.id)
                    val decades = api.decades(lib.id, "movie")
                    val alphabet = if (filters.sort.isBlank() && filters.seenStatus.isBlank() && filters.minRating <= 0) api.alphabet(lib.id, "movie", filters) else emptyList()
                    if (generation == movieLoadGeneration) {
                        movieGenres = genres
                        movieDecades = decades
                        movieAlphabet = alphabet
                    }
                }
                api.itemsPage(lib.id, requestLimit, offset, filters)
            }
            if (generation != movieLoadGeneration) return@launch
            result
                .onSuccess {
                    movies = when {
                        append -> (existingItems + it).distinctBy { item -> item.id }
                        prepend -> (it + existingItems).distinctBy { item -> item.id }
                        else -> it
                    }
                    if (prepend) {
                        movieStartOffset = offset
                        movieHasPrevious = offset > 0 && it.isNotEmpty()
                    } else {
                        movieNextOffset = offset + it.size
                        movieHasMore = it.size == requestLimit
                        if (!append) {
                            movieStartOffset = offset
                            movieHasPrevious = offset > 0
                            if (!positionedFromCache) {
                                movieScrollIndex = scrollIndex.coerceIn(0, it.lastIndex.coerceAtLeast(0))
                                movieScrollToken += 1
                            }
                        }
                    }
                    page = Page.Movies
                    backStack = emptyList()
                    CompanionCache.writeItems(context, session, cacheKey, it)
                }
                .onFailure { reportError(it, "Movies failed") }
            when {
                append -> movieLoadingMore = false
                prepend -> movieLoadingPrevious = false
                else -> loading = false
            }
        }
    }

    fun loadShows(offset: Int = 0, append: Boolean = false, prepend: Boolean = false, scrollIndex: Int = 0, pageLimit: Int = 60) {
        val lib = tvLib ?: return
        if (append && (showLoadingMore || showLoadingPrevious || !showHasMore)) return
        if (prepend && (showLoadingPrevious || showLoadingMore || !showHasPrevious)) return
        val filters = showFilters
        val pageSize = 60
        val incremental = append || prepend
        val existingShows = if (incremental) shows else emptyList()
        val requestLimit = if (prepend) (showStartOffset - offset).coerceIn(1, pageSize) else pageLimit
        if (!incremental) {
            showLoadGeneration += 1
            showLoadingMore = false
            showLoadingPrevious = false
            showHasMore = true
            showHasPrevious = offset > 0
        }
        val generation = showLoadGeneration
        when {
            append -> showLoadingMore = true
            prepend -> showLoadingPrevious = true
            else -> loading = true
        }
        scope.launch {
            val cacheKey = "shows_${lib.id}_${filterKey(filters)}_${offset}_$requestLimit"
            val cached = CompanionCache.readShows(context, session, cacheKey)
            var positionedFromCache = false
            if (cached.isNotEmpty() && generation == showLoadGeneration) {
                shows = when {
                    append -> (existingShows + cached).distinctBy { showMarkerKey(it) }
                    prepend -> (cached + existingShows).distinctBy { showMarkerKey(it) }
                    else -> cached
                }
                if (prepend) {
                    showStartOffset = offset
                    showHasPrevious = offset > 0
                } else {
                    showNextOffset = offset + cached.size
                    showHasMore = cached.size == requestLimit
                    if (!append) {
                        showStartOffset = offset
                        showHasPrevious = offset > 0
                        showScrollIndex = scrollIndex.coerceIn(0, cached.lastIndex.coerceAtLeast(0))
                        showScrollToken += 1
                        positionedFromCache = true
                    }
                }
                page = Page.Shows
                backStack = emptyList()
            }
            val result = runCatching {
                if (!incremental) {
                    val genres = api.genres(lib.id)
                    val decades = api.decades(lib.id, "tv")
                    val alphabet = if (filters.sort.isBlank() && filters.seenStatus.isBlank() && filters.minRating <= 0) api.alphabet(lib.id, "tv", filters) else emptyList()
                    if (generation == showLoadGeneration) {
                        showGenres = genres
                        showDecades = decades
                        showAlphabet = alphabet
                    }
                }
                api.showsPage(lib.id, requestLimit, offset, filters)
            }
            if (generation != showLoadGeneration) return@launch
            result
                .onSuccess {
                    shows = when {
                        append -> (existingShows + it).distinctBy { show -> showMarkerKey(show) }
                        prepend -> (it + existingShows).distinctBy { show -> showMarkerKey(show) }
                        else -> it
                    }
                    if (prepend) {
                        showStartOffset = offset
                        showHasPrevious = offset > 0 && it.isNotEmpty()
                    } else {
                        showNextOffset = offset + it.size
                        showHasMore = it.size == requestLimit
                        if (!append) {
                            showStartOffset = offset
                            showHasPrevious = offset > 0
                            if (!positionedFromCache) {
                                showScrollIndex = scrollIndex.coerceIn(0, it.lastIndex.coerceAtLeast(0))
                                showScrollToken += 1
                            }
                        }
                    }
                    page = Page.Shows
                    backStack = emptyList()
                    CompanionCache.writeShows(context, session, cacheKey, it)
                }
                .onFailure { reportError(it, "Shows failed") }
            when {
                append -> showLoadingMore = false
                prepend -> showLoadingPrevious = false
                else -> loading = false
            }
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
                .onFailure { reportError(it, "Seasons failed") }
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
                    refreshMarkers()
                }
                .onFailure { reportError(it, "Episodes failed") }
            loading = false
        }
    }

    // Pull libraries + home shelves from the server. On the initial load we show
    // the spinner and seed from cache; background refreshes (foreground return /
    // periodic) update in place so newly added media appears without restarting.
    suspend fun loadContent(initial: Boolean) {
        if (initial) {
            loading = true
            val cachedLibraries = CompanionCache.readLibraries(context, session)
            if (cachedLibraries.isNotEmpty()) {
                libraries = cachedLibraries
                val cachedMovieLib = cachedLibraries.firstOrNull { it.type == "movies" }
                val cachedTvLib = cachedLibraries.firstOrNull { it.type == "tv" }
                if (cachedMovieLib != null) {
                    recentMovies = CompanionCache.readItems(context, session, "recent_movies_${cachedMovieLib.id}")
                    topMovies = CompanionCache.readItems(context, session, "top_movies_${cachedMovieLib.id}")
                }
                if (cachedTvLib != null) {
                    recentShows = CompanionCache.readShows(context, session, "recent_shows_${cachedTvLib.id}")
                    topShows = CompanionCache.readShows(context, session, "top_shows_${cachedTvLib.id}")
                }
            }
        }
        runCatching {
            publishCompanionUpdate(runCatching { api.companionUpdate(BuildConfig.VERSION_CODE) }.getOrNull())
            refreshMarkers()
            libraries = api.libraries()
            CompanionCache.writeLibraries(context, session, libraries)
            refreshDevices()
            val ml = libraries.firstOrNull { it.type == "movies" }
            val tl = libraries.firstOrNull { it.type == "tv" }
            if (ml != null) {
                movieGenres = api.genres(ml.id)
                movieDecades = api.decades(ml.id, "movie")
                recentMovies = api.recentItems(ml.id, 24)
                topMovies = api.itemsPage(ml.id, 24, 0, LibraryFilters(sort = "rating", minRating = 7.0))
                CompanionCache.writeItems(context, session, "recent_movies_${ml.id}", recentMovies)
                CompanionCache.writeItems(context, session, "top_movies_${ml.id}", topMovies)
            }
            if (tl != null) {
                showGenres = api.genres(tl.id)
                showDecades = api.decades(tl.id, "tv")
                recentShows = api.recentShows(tl.id, 24)
                topShows = api.showsPage(tl.id, 24, 0, LibraryFilters(sort = "rating", minRating = 7.0))
                CompanionCache.writeShows(context, session, "recent_shows_${tl.id}", recentShows)
                CompanionCache.writeShows(context, session, "top_shows_${tl.id}", topShows)
            }
        }.onFailure { reportError(it, "Load failed") }
        runCatching { api.home() }.onSuccess {
            continueMovies = it.movies
            continueEpisodes = it.episodes
            resumeProgress = it.resume
        }
        if (initial) loading = false
    }

    // Bumped on every foreground return to force the polling loops to restart.
    // The cached-app freezer can freeze a coroutine mid-delay() while the app is
    // backgrounded; the scheduled wake-up is then lost and the while(true) loop
    // never resumes — which is why the connection stayed dead until a manual
    // restart. Re-keying the loops on this counter gives them a fresh start.
    var resumeTick by remember { mutableStateOf(0) }
    // False once the device poll starts failing. Drives the offline banner and
    // triggers a content reload the moment the server answers again.
    var connected by remember { mutableStateOf(true) }

    fun reconnect() {
        resumeTick++
    }

    // Kick the loops as soon as the radio is back rather than waiting out the
    // current backoff delay.
    OnNetworkAvailable { if (!connected) reconnect() }

    LaunchedEffect(session) { loadContent(initial = true) }

    // On every foreground return: restart the polling loops (resumeTick) and
    // refresh content. The first ON_START is the cold start already handled by
    // the initial load above, so skip it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                if (first) {
                    first = false
                } else {
                    Log.i("PopcornCompanion", "foreground return: restarting polls + refresh")
                    resumeTick++
                    scope.launch { loadContent(initial = false) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // While the app stays open, keep the home shelves current so content added
    // server-side appears without any user action. Keyed on resumeTick as well
    // so a loop frozen by the app cache is replaced on the next foreground.
    LaunchedEffect(session, resumeTick) {
        while (true) {
            delay(60_000)
            loadContent(initial = false)
        }
    }

    LaunchedEffect(session, resumeTick) {
        while (true) {
            delay(5 * 60 * 1000)
            publishCompanionUpdate(runCatching { api.companionUpdate(BuildConfig.VERSION_CODE) }.getOrNull())
        }
    }

    LaunchedEffect(selectedDevice?.id, resumeTick) {
        while (true) {
            val id = selectedDevice?.id
            // A successful poll means the server is reachable again, so clear any
            // stale connection-error banner instead of leaving it up until restart.
            if (id != null) {
                runCatching { state = api.deviceState(id) }
                    .onSuccess { if (error.isNotBlank()) onError("") }
                    .onFailure { Log.w("PopcornCompanion", "deviceState poll failed: ${it.message}") }
            }
            delay(if (connected) 1000 else 3000)
        }
    }

    // The device poll doubles as the connection heartbeat. Failures back off
    // (1s → 30s) instead of hammering a dead server, and the first success after
    // an outage reloads content so the UI isn't left stale.
    LaunchedEffect(session, playbackTarget, resumeTick) {
        var failures = 0
        while (true) {
            val ok = runCatching { refreshDevices() }
                .onFailure { if (it !is CancellationException) Log.w("PopcornCompanion", "device refresh failed: ${it.message}") }
                .isSuccess
            if (ok) {
                failures = 0
                if (!connected) {
                    connected = true
                    if (error.isNotBlank()) onError("")
                    loadContent(initial = false)
                }
                delay(5000)
            } else {
                failures++
                connected = false
                delay((1000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(30_000L))
            }
        }
    }

    LaunchedEffect(localPlayer, phoneState.item?.id, phoneStreamBaseMs, resumeTick) {
        var lastSavedAt = 0L
        while (true) {
            samplePhoneProgressEvidence()
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

    LaunchedEffect(query, searchScope, searchFields) {
        val q = query.trim()
        val requestedScope = searchScope
        val requestedFields = searchFields
        if (q.length < 2) {
            searchMovies = emptyList()
            searchShows = emptyList()
            searchError = ""
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        delay(250)
        if (q == query.trim()) {
            val fields = requestedFields.sorted().joinToString(",")
            runCatching {
                val movies = if (requestedScope != "tv") api.searchMovies(q, fields) else emptyList()
                val shows = if (requestedScope != "movies") api.searchShows(q, fields) else emptyList()
                movies to shows
            }.onSuccess { (movies, shows) ->
                searchMovies = movies
                searchShows = shows
                searchError = ""
            }.onFailure {
                if (it is CancellationException) throw it
                searchMovies = emptyList()
                searchShows = emptyList()
                searchError = it.message ?: "Search failed"
            }
        }
        searchLoading = false
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
                    session = session,
                    playbackTarget = playbackTarget,
                    devices = devices,
                    selectedDevice = selectedDevice,
                    deviceStatus = state.state.ifBlank { "idle" },
                    onSelectPhone = ::selectPhoneTarget,
                    onSelectDevice = ::selectTvTarget,
                    onHistory = { navigate(Page.History) },
                    onScan = onScan,
                    showUpdate = companionUpdate?.available == true,
                    onUpdate = { showUpdateDialog = true },
                    onLogout = onLogout,
                    onRefreshDevices = {
                        scope.launch {
                            runCatching { refreshDevices() }.onFailure { reportError(it, "Device refresh failed") }
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
                        targetLabel = if (playbackTarget == PlaybackTarget.Phone) "This phone" else selectedDevice?.displayName() ?: "TV",
                        onPlayPause = {
                            if (playbackTarget == PlaybackTarget.Phone && phoneState.item != null) {
                                localPlayer.playWhenReady = !localPlayer.isPlaying
                            } else {
                                if (state.state == "playing") send("pause") else send("resume")
                            }
                        },
                        onSeek = { delta -> seekTo(miniState.positionMs + delta) },
                        // Always lands somewhere: the local player when the phone
                        // has something loaded, otherwise the remote — which
                        // stays reachable even when nothing is playing.
                        onClick = {
                            val local = phoneState.item
                            if (playbackTarget == PlaybackTarget.Phone && local != null) {
                                navigate(Page.LocalPlayer(local, phoneAudioIndex, phoneSubtitleIndex, page), stack = false)
                            } else {
                                navigate(Page.Remote, stack = false)
                            }
                        },
                    )
                    BottomNavigation(
                        page = page,
                        onHome = { page = Page.Home; backStack = emptyList() },
                        onMovies = {
                            if (movies.isEmpty()) loadMovies() else { page = Page.Movies; backStack = emptyList() }
                        },
                        onShows = {
                            if (shows.isEmpty()) loadShows() else { page = Page.Shows; backStack = emptyList() }
                        },
                        onSearch = { page = Page.Search; backStack = emptyList() },
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!connected) {
                    OfflineBanner(
                        onRetry = ::reconnect,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                StatusMessage(
                    message = error,
                    success = error.contains("approved", true),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }
                Crossfade(targetState = page, animationSpec = tween(220), label = "page") { current ->
                    Box(Modifier.fillMaxSize()) {
                        when (current) {
                            Page.Home -> HomePage(
                                session,
                                continueMovies,
                                continueEpisodes,
                                resumeProgress,
                                recentMovies,
                                recentShows,
                                topMovies,
                                topShows,
                                watchlistMovies,
                                watchlistTvShows,
                                completedItems,
                                completedShows,
                                watchlistItems,
                                watchlistShows,
                                onMovie = ::openDetail,
                                onShow = ::openShow,
                                onEpisode = ::openDetail,
                            )
                            Page.Movies -> MediaGrid(
                                title = "Movies",
                                session = session,
                                items = movies,
                                completedItems = completedItems,
                                watchlistItems = watchlistItems,
                                genres = movieGenres,
                                decades = movieDecades,
                                alphabet = movieAlphabet,
                                filters = movieFilters,
                                loadingMore = movieLoadingMore,
                                loadingPrevious = movieLoadingPrevious,
                                hasMore = movieHasMore,
                                hasPrevious = movieHasPrevious,
                                scrollToken = movieScrollToken,
                                scrollIndex = movieScrollIndex,
                                onFilters = {
                                    movieFilters = it
                                    loadMovies(0)
                                },
                                onLoadMore = { loadMovies(movieNextOffset, append = true) },
                                onLoadPrevious = { loadMovies((movieStartOffset - 60).coerceAtLeast(0), prepend = true) },
                                onAlphabet = {
                                    val start = (it.offset - 60).coerceAtLeast(0)
                                    loadMovies(start, scrollIndex = it.offset - start, pageLimit = 120)
                                },
                                onOpen = ::openDetail,
                            )
                            Page.Shows -> ShowGrid(
                                "TV Shows",
                                session,
                                shows,
                                completedShows,
                                watchlistShows,
                                genres = showGenres,
                                decades = showDecades,
                                alphabet = showAlphabet,
                                filters = showFilters,
                                loadingMore = showLoadingMore,
                                loadingPrevious = showLoadingPrevious,
                                hasMore = showHasMore,
                                hasPrevious = showHasPrevious,
                                scrollToken = showScrollToken,
                                scrollIndex = showScrollIndex,
                                onFilters = {
                                    showFilters = it
                                    loadShows(0)
                                },
                                onLoadMore = { loadShows(showNextOffset, append = true) },
                                onLoadPrevious = { loadShows((showStartOffset - 60).coerceAtLeast(0), prepend = true) },
                                onAlphabet = {
                                    val start = (it.offset - 60).coerceAtLeast(0)
                                    loadShows(start, scrollIndex = it.offset - start, pageLimit = 120)
                                },
                                onShow = ::openShow,
                            )
                            is Page.Show -> SeasonList(
                                session,
                                current.show,
                                seasons,
                                completedItems,
                                showWatched = completedShows.contains(showKey(current.show)),
                                onBack = ::goBack,
                                onSeason = { openSeason(current.show, it) },
                                onSetShowWatched = { setShowWatched(current.show, it) },
                                onSetSeasonWatched = { season, watched -> setSeasonWatched(current.show, season.seasonNumber, watched) },
                                onActor = ::openActor,
                            )
                            is Page.Season -> EpisodeList(session, current.show, current.season, episodes, completedItems, watchlistItems, onBack = ::goBack, onOpen = ::openDetail, onSetWatched = ::setItemWatched)
                            is Page.Detail -> DetailPage(
                                session,
                                api,
                                current.item,
                                playbackTarget = playbackTarget,
                                watched = completedItems.contains(current.item.id),
                                watchlisted = watchlistItems.contains(current.item.id),
                                onSetWatched = { setItemWatched(current.item, it) },
                                onSetWatchlisted = { setItemWatchlisted(current.item, it) },
                                onBack = ::goBack,
                                onPlay = { target, audio, subtitle, position -> play(target.id, audio, subtitle, position) },
                                onPlayLocal = { target, audio, subtitle, position -> playLocally(target, audio, subtitle, position) },
                                onOpenSimilar = ::openDetail,
                                onActor = ::openActor,
                            )
                            Page.Search -> SearchPage(
                                session,
                                query,
                                { query = it },
                                scope = searchScope,
                                onScope = { searchScope = it },
                                fields = searchFields,
                                onFields = { searchFields = it },
                                movies = searchMovies,
                                shows = searchShows,
                                completedItems = completedItems,
                                completedShows = completedShows,
                                watchlistItems = watchlistItems,
                                watchlistShows = watchlistShows,
                                loading = searchLoading,
                                error = searchError,
                                onMovie = ::openDetail,
                                onShow = ::openShow,
                            )
                            Page.History -> HistoryPage(session, onBack = ::goBack, onOpen = ::openDetail)
                            is Page.Person -> PersonPage(
                                session,
                                current.actor,
                                completedItems,
                                completedShows,
                                watchlistItems,
                                watchlistShows,
                                onBack = ::goBack,
                                onMovie = ::openDetail,
                                onShow = ::openShow,
                            )
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
                devices = devices,
                selectedDevice = selectedDevice,
                playbackTarget = playbackTarget,
                onSelectPhone = {
                    selectPhoneTarget()
                    if (phoneState.item != null) page = Page.LocalPlayer(phoneState.item!!, phoneAudioIndex, phoneSubtitleIndex, Page.Home)
                    else page = Page.Home
                },
                onSelectDevice = ::selectTvTarget,
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
                    devices = devices,
                    selectedDevice = selectedDevice,
                    onSelectDevice = {
                        selectTvTarget(it)
                        page = Page.Remote
                        backStack = emptyList()
                    },
                    onAudio = { audio ->
                        loadPhonePlayback(current.item, audio, phoneSubtitleIndex, phoneState.bandwidthKbps, phoneAbsolutePosition(), "auto")
                    },
                    onSubtitle = { subtitle ->
                        loadPhonePlayback(current.item, phoneAudioIndex, subtitle, phoneState.bandwidthKbps, phoneAbsolutePosition(), "auto")
                    },
                    onBandwidth = { bandwidth ->
                        loadPhonePlayback(current.item, phoneAudioIndex, phoneSubtitleIndex, bandwidth, phoneAbsolutePosition(), if (bandwidth == null) "direct" else "auto")
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
    if (showUpdateDialog && companionUpdate != null) {
        CompanionUpdateDialog(
            api = api,
            info = companionUpdate!!,
            onDismiss = { showUpdateDialog = false },
            onError = onError,
        )
    }
}

fun displayTitle(item: PopItem): String = item.episodeTitle.ifBlank { item.title }
fun fmtDuration(ms: Long): String = if (ms <= 0) "" else "${(ms / 60000).coerceAtLeast(1)}m"
fun fmtEndsAround(ms: Long): String {
    if (ms <= 0) return ""
    val endTime = java.time.LocalTime.now().plusSeconds(ms / 1000)
    return "Ends around ${endTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
}
fun filterKey(filters: LibraryFilters): String = listOf(
    filters.genre.ifBlank { "all" },
    if (filters.minRating > 0) filters.minRating.toInt().toString() else "any",
    filters.sort.ifBlank { "title" },
    filters.seenStatus.ifBlank { "all_seen" },
    filters.decades.ifBlank { "all_decades" },
).joinToString("_").replace(Regex("[^A-Za-z0-9_.-]"), "_")
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
