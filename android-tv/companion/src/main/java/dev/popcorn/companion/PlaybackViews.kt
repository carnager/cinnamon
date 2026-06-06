package dev.popcorn.companion

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(session: Session, state: PlayerState, target: PlaybackTarget, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onClick: () -> Unit) {
    val active = state.title.isNotBlank()
    val targetFraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(targetValue = targetFraction, animationSpec = tween(350), label = "miniProgress")
    val targetLabel = if (target == PlaybackTarget.Phone) "Phone" else "Shield"
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).animateContentSize(tween(180)),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = Line)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(7.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                if (state.itemId > 0) {
                    AuthAsyncImage(session, imageUrl(session, state.itemId, 0), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("♪", color = Muted, fontSize = 20.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (active) state.title else "$targetLabel idle", color = if (active) TextColor else Muted, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$targetLabel · ${state.state.ifBlank { "idle" }} · ${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { onSeek(-30000) }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (state.state == "playing") Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { onSeek(30000) }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Forward", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RemotePage(
    session: Session,
    state: PlayerState,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onJump: () -> Unit,
    selectedBandwidth: Int?,
    onBandwidth: (Int?) -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(0)
    val position = state.positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
    var scrub by remember(state.title, duration) { mutableStateOf(position.toFloat()) }
    var showBandwidthDialog by remember { mutableStateOf(false) }
    LaunchedEffect(position) { scrub = position.toFloat() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().animateContentSize(tween(180)),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Box(Modifier.fillMaxWidth()) {
                Text("⌄", color = TextColor, fontSize = 34.sp, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(8.dp))
                Text("Now Playing", color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
        }
        item {
            Box(Modifier.fillMaxWidth(.78f).aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                if (state.itemId > 0) {
                    AuthAsyncImage(session, imageUrl(session, state.itemId, 0), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("♪", color = Muted, fontSize = 56.sp)
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.title.ifBlank { "Shield idle" }, color = TextColor, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${state.state.ifBlank { "idle" }} on Shield", color = Muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Slider(
                    value = scrub,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { onSeekTo(scrub.toLong()) },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(formatTime(duration), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { onSeek(-30000) }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Back 30 seconds", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(38.dp))
                }
                Surface(
                    modifier = Modifier.size(92.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(state.state) {
                                detectTapGestures(
                                    onTap = { if (state.state == "playing") onPause() else onResume() },
                                    onLongPress = { onStop() },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (state.state == "playing") Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause. Long press to stop.", modifier = Modifier.size(44.dp))
                    }
                }
                IconButton(onClick = { onSeek(30000) }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Forward 30 seconds", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(38.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                PlayerMaterialIconButton("Jump", Icons.Default.SubdirectoryArrowRight, onJump)
                PlayerMaterialIconButton(BandwidthOptions.firstOrNull { it.kbps == selectedBandwidth }?.label ?: "Quality", Icons.Default.Speed, { showBandwidthDialog = true })
                PlayerMaterialIconButton("Back 30", Icons.Default.Replay30, { onSeek(-30000) })
                PlayerMaterialIconButton("Fwd 30", Icons.Default.Forward30, { onSeek(30000) })
            }
        }
    }

    if (showBandwidthDialog) {
        BandwidthDialog(
            selectedBandwidth = selectedBandwidth,
            onDismiss = { showBandwidthDialog = false },
            onBandwidth = {
                onBandwidth(it)
                showBandwidthDialog = false
            },
        )
    }
}

@Composable
fun LocalPlayerPage(
    session: Session,
    api: Api,
    player: ExoPlayer,
    item: PopItem,
    state: PhonePlaybackState,
    selectedAudio: Int?,
    selectedSubtitle: Int?,
    selectedBandwidth: Int?,
    onAudio: (Int?) -> Unit,
    onSubtitle: (Int?) -> Unit,
    onBandwidth: (Int?) -> Unit,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var streams by remember(item.id) { mutableStateOf<List<StreamInfo>>(emptyList()) }
    var scrub by remember(item.id) { mutableStateOf(state.positionMs.toFloat()) }
    var dragging by remember(item.id) { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }
    val duration = state.durationMs.coerceAtLeast(item.durationMs).coerceAtLeast(0)
    val position = state.positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
    val menuOpen = showQuality || showAudio || showSubtitles

    fun revealControls() {
        controlsVisible = true
    }

    BackHandler(onBack = onBack)

    DisposableEffect(fullscreen) {
        setImmersive(context, fullscreen)
        onDispose { setImmersive(context, false) }
    }

    LaunchedEffect(item.id) {
        runCatching { api.streams(item.id) }
            .onSuccess { streams = it }
            .onFailure { onError(it.message ?: "Could not load streams") }
    }

    LaunchedEffect(position) {
        if (!dragging) scrub = position.toFloat()
    }

    LaunchedEffect(controlsVisible, state.state, dragging, menuOpen) {
        if (controlsVisible && state.state == "playing" && !dragging && !menuOpen) {
            delay(3000)
            if (state.state == "playing" && !dragging && !menuOpen) controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (fullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    controlsVisible = !controlsVisible
                })
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = controlsVisible && !fullscreen,
                enter = fadeIn(animationSpec = tween(140)),
                exit = fadeOut(animationSpec = tween(180)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = .72f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Column(Modifier.weight(1f)) {
                        Text(displayTitle(item), color = TextColor, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Playing on phone · ${BandwidthOptions.firstOrNull { it.kbps == selectedBandwidth }?.label ?: "Direct"}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        this.player = player
                    }
                },
                update = { it.player = player },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible || !player.isPlaying || dragging || menuOpen,
            enter = fadeIn(animationSpec = tween(140)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = .68f))
                    .padding(horizontal = 18.dp, vertical = if (fullscreen) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (fullscreen) {
                    Text(displayTitle(item), color = TextColor, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Slider(
                    value = scrub.coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    onValueChange = {
                        revealControls()
                        dragging = true
                        scrub = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeekTo(scrub.toLong())
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(if (dragging) scrub.toLong() else position), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(formatTime(duration), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { revealControls(); onSeekTo(position - 30000) }) {
                        Icon(Icons.Default.Replay30, contentDescription = "Back 30 seconds", tint = TextColor, modifier = Modifier.size(34.dp))
                    }
                    Surface(
                        modifier = Modifier.size(76.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Box(
                            Modifier.fillMaxSize().clickable {
                                revealControls()
                                player.playWhenReady = !player.isPlaying
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause", modifier = Modifier.size(40.dp))
                        }
                    }
                    IconButton(onClick = { revealControls(); onSeekTo(position + 30000) }) {
                        Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds", tint = TextColor, modifier = Modifier.size(34.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    PlayerMaterialIconButton(BandwidthOptions.firstOrNull { it.kbps == selectedBandwidth }?.label ?: "Direct", Icons.Default.Speed, { revealControls(); showQuality = true })
                    PlayerMaterialIconButton(shortTrackLabel(audioTracks.firstOrNull { it.index == selectedAudio }, "Audio"), Icons.Default.MusicNote, { revealControls(); showAudio = true })
                    PlayerMaterialIconButton(shortTrackLabel(subtitleTracks.firstOrNull { it.index == selectedSubtitle }, "Subs"), Icons.Default.SubdirectoryArrowRight, { revealControls(); showSubtitles = true })
                    PlayerMaterialIconButton(if (fullscreen) "Window" else "Full", Icons.Default.LiveTv, { revealControls(); fullscreen = !fullscreen })
                }
                OutlinedButton(onClick = { revealControls(); onStop() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop phone playback")
                }
            }
        }
    }

    if (showQuality) {
        BandwidthDialog(
            selectedBandwidth = selectedBandwidth,
            onDismiss = { showQuality = false },
            onBandwidth = {
                showQuality = false
                onBandwidth(it)
            },
        )
    }
    if (showAudio) {
        TrackDialog("Audio", audioTracks, selectedAudio, "Default", { showAudio = false }, {
            showAudio = false
            onAudio(it)
        })
    }
    if (showSubtitles) {
        TrackDialog("Subtitles", subtitleTracks, selectedSubtitle, "Off", { showSubtitles = false }, {
            showSubtitles = false
            onSubtitle(it)
        })
    }
}

@Composable
fun BandwidthDialog(selectedBandwidth: Int?, onDismiss: () -> Unit, onBandwidth: (Int?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback quality") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BandwidthOptions.forEach { option ->
                    TrackChoice(option.label, selectedBandwidth == option.kbps) { onBandwidth(option.kbps) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun TrackDialog(title: String, tracks: List<StreamInfo>, selected: Int?, emptyLabel: String, onDismiss: () -> Unit, onSelect: (Int?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackChoice(emptyLabel, selected == null) { onSelect(null) }
                tracks.forEach { track ->
                    TrackChoice(track.label(), selected == track.index) { onSelect(track.index) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun PlayerMaterialIconButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(72.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Muted, modifier = Modifier.size(28.dp))
        Text(label, color = Muted, fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun JumpDialog(durationMs: Long, onDismiss: () -> Unit, onJump: (Long) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PopTextField(value, { value = it }, "hh:mm:ss, mm:ss, or minutes")
                if (durationMs > 0) Text("Runtime ${formatTime(durationMs)}", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = { parseTimeToMs(value)?.let(onJump) }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) {
                Text("Jump")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun setImmersive(context: Context, enabled: Boolean) {
    val activity = context as? ComponentActivity ?: return
    val window = activity.window
    if (enabled) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun parseTimeToMs(raw: String): Long? {
    val text = raw.trim()
    if (text.isBlank()) return null
    if (!text.contains(":")) {
        return text.replace(',', '.').toDoubleOrNull()?.let { (it * 60000).toLong() }
    }
    val parts = text.split(":").map { it.trim().toLongOrNull() ?: return null }
    val seconds = when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> return null
    }
    return seconds * 1000
}

private fun shortTrackLabel(track: StreamInfo?, fallback: String): String {
    if (track == null) return fallback
    return track.language.takeIf { it.isNotBlank() }?.uppercase()
        ?: track.title.takeIf { it.isNotBlank() }?.take(10)
        ?: fallback
}
