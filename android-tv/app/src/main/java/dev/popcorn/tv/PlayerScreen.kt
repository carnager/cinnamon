package dev.popcorn.tv

import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PlayerScreen(
    item: PopItem,
    session: Session?,
    deviceId: String,
    remoteCommand: PlayerRemoteCommand?,
    initialAudioIndex: Int?,
    initialSubtitleIndex: Int?,
    initialStartPositionMs: Long,
    initialBandwidthKbps: Int?,
    onBandwidthSelected: (Int?) -> Unit,
    onBack: () -> Unit,
    onRemoteStop: () -> Unit,
    onRemoteCommandConsumed: (Long) -> Unit,
    onPlayNext: (PopItem) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = (context as? ComponentActivity)?.lifecycle
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    // Outlives the composition: dispose-time cleanup (progress save, HLS stop, logs)
    // launched on the composition scope is silently dropped because that scope is
    // already cancelled when onDispose runs.
    val reportScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    var selectedAudioIndex by remember(item.id) { mutableStateOf(initialAudioIndex) }
    var selectedSubtitleIndex by remember(item.id) { mutableStateOf(initialSubtitleIndex) }
    var selectedBandwidth by remember(item.id) { mutableStateOf(initialBandwidthKbps) }
    var hlsSessionId by remember(item.id) { mutableStateOf<String?>(null) }
    var playbackBaseMs by remember(item.id) { mutableStateOf(0L) }
    var planUsesHls by remember(item.id) { mutableStateOf(false) }
    var activePlan by remember(item.id) { mutableStateOf<PlaybackPlan?>(null) }
    var fallbackRetried by remember(item.id) { mutableStateOf(false) }
    var playbackPlanJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var playbackPlanSerial by remember(item.id) { mutableStateOf(0L) }
    var pendingSeekTargetMs by remember(item.id) { mutableStateOf<Long?>(null) }
    var pendingSeekJob by remember(item.id) { mutableStateOf<Job?>(null) }
    var exitingPlayer by remember(item.id) { mutableStateOf(false) }
    var continuousPlayedMs by remember(item.id) { mutableStateOf(0L) }
    var playbackSampleAtMs by remember(item.id) { mutableStateOf(SystemClock.elapsedRealtime()) }
    val pressedSeekKeys = remember(item.id) { mutableMapOf<Int, Long>() }
    val originalStreams = remember { mutableStateListOf<StreamInfo>() }
    val playbackProfile = remember(context) { buildPlaybackProfile(context) }

    // ── Up Next (auto-play next episode) ──
    var nextEpisode by remember(item.id) { mutableStateOf<PopItem?>(null) }
    var upNextDismissed by remember(item.id) { mutableStateOf(false) }
    var upNextSecondsLeft by remember(item.id) { mutableStateOf<Int?>(null) }
    var advancingToNext by remember(item.id) { mutableStateOf(false) }
    val upNextFocus = remember { FocusRequester() }

    fun playNextEpisode() {
        val next = nextEpisode ?: return
        if (advancingToNext) return
        advancingToNext = true
        onPlayNext(next)
    }

    // Matches the render condition for the Up Next overlay (see the Box below).
    // The OSD key bridge and focus handling use this to yield to the card.
    fun upNextCardVisible(): Boolean =
        nextEpisode != null && upNextSecondsLeft != null && !upNextDismissed && !advancingToNext

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
        object : Runnable {
            override fun run() {
                if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null) return
                if (exoPlayer.isPlaying) {
                    playerView.hideController()
                    playerView.requestFocus()
                } else if (playerView.isControllerFullyVisible) {
                    // Not playing yet (still buffering); keep polling instead of leaving
                    // the controller stuck visible forever.
                    mainHandler.postDelayed(this, 1_000)
                }
            }
        }
    }

    fun scheduleControllerAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, 4_500)
    }

    fun focusPlayerControl(focusTimeBar: Boolean = false) {
        playerView.post {
            val focusTarget = if (focusTimeBar) {
                playerView.findViewById<View>(R.id.popcorn_hls_progress)?.takeIf { planUsesHls }
                    ?: playerView.findViewById<View>(R.id.popcorn_progress)
            } else {
                playerView.findViewById<View>(R.id.popcorn_play_pause)
            }
            val focused = focusTarget?.takeIf { it.isShown && it.isEnabled }?.requestFocus() ?: false
            if (!focused) playerView.requestFocus()
        }
        playerView.postDelayed({
            val fallbackTarget = if (focusTimeBar) {
                playerView.findViewById<View>(R.id.popcorn_hls_progress)?.takeIf { planUsesHls }
                    ?: playerView.findViewById<View>(R.id.popcorn_progress)
            } else {
                playerView.findViewById<View>(R.id.popcorn_play_pause)
            }
            fallbackTarget?.takeIf { it.isShown && it.isEnabled }?.requestFocus()
        }, 120)
    }

    fun logicalPositionMs(): Long {
        val current = exoPlayer.currentPosition.coerceAtLeast(0)
        val position = if (planUsesHls) playbackBaseMs + current else current
        return if (item.durationMs > 0) position.coerceIn(0, item.durationMs) else position
    }

    fun logicalDurationMs(): Long {
        return item.durationMs.takeIf { it > 0 }
            ?: exoPlayer.duration.coerceAtLeast(0)
    }

    fun playerStateLabel(): String = when {
        exoPlayer.isPlaying -> "exo-playing"
        exoPlayer.playbackState == Player.STATE_READY -> "exo-ready"
        exoPlayer.playbackState == Player.STATE_BUFFERING -> "exo-buffering"
        exoPlayer.playbackState == Player.STATE_ENDED -> "exo-ended"
        else -> "exo-idle"
    }

    fun logClient(event: String, keyEvent: AndroidKeyEvent? = null, message: String = "", extra: JSONObject? = null) {
        val activeSession = session ?: return
        reportScope.launch {
            runCatching {
                val body = JSONObject()
                    .put("deviceId", deviceId)
                    .put("deviceName", shieldDeviceName())
                    .put("event", event)
                    .put("screen", "player")
                    .put("itemId", item.id)
                    .put("title", if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title)
                    .put("planMode", activePlan?.mode.orEmpty())
                    .put("usesHls", planUsesHls)
                    .put("usesMpv", false)
                    .put("hlsId", hlsSessionId.orEmpty())
                    .put("positionMs", logicalPositionMs())
                    .put("durationMs", logicalDurationMs())
                    .put("keyCode", keyEvent?.keyCode ?: 0)
                    .put("keyAction", keyEvent?.action ?: -1)
                    .put("playerState", playerStateLabel())
                    .put("message", message)
                    .put("extra", extra ?: JSONObject())
                Api(activeSession).clientLog(body)
            }
        }
    }

    fun boundarySeekTarget(currentMs: Long, forward: Boolean): Long {
        val step = 30_000L
        return if (forward) {
            ((currentMs / step) + 1L) * step
        } else {
            (((currentMs - 1L).coerceAtLeast(0L) / step) * step).coerceAtLeast(0L)
        }
    }

    fun isHiddenSeekKeyCode(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
        keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND ||
        keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD

    fun shouldHandleSeekKey(event: AndroidKeyEvent): Boolean {
        if (!isHiddenSeekKeyCode(event.keyCode)) return false
        if (event.action == AndroidKeyEvent.ACTION_UP) {
            pressedSeekKeys.remove(event.keyCode)
            return false
        }
        if (event.action != AndroidKeyEvent.ACTION_DOWN) return false
        val lastDown = pressedSeekKeys[event.keyCode]
        if (event.repeatCount == 0 && lastDown != null && event.eventTime - lastDown < 80L) return false
        pressedSeekKeys[event.keyCode] = event.eventTime
        return true
    }

    fun applyDirectTrackSelections() {
        if (planUsesHls || originalStreams.isEmpty()) return
        val result = applyOriginalTrackSelection(exoPlayer, originalStreams, selectedAudioIndex, selectedSubtitleIndex)
        logClient("apply-direct-tracks", message = result.take(230))
    }

    fun applyHlsSubtitleSelection(subtitleIndex: Int?) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleIndex == null)
            .build()
    }

    fun progressCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        return durationMs - positionMs <= 90_000 || positionMs.toDouble() / durationMs.toDouble() >= 0.92
    }

    fun sampleProgressEvidence() {
        val now = SystemClock.elapsedRealtime()
        if (exoPlayer.isPlaying) continuousPlayedMs += (now - playbackSampleAtMs).coerceIn(0L, 12_000L)
        playbackSampleAtMs = now
    }

    fun resetProgressEvidence() {
        continuousPlayedMs = 0L
        playbackSampleAtMs = SystemClock.elapsedRealtime()
    }

    fun reportProgress(state: String, forceCompleted: Boolean = false) {
        val activeSession = session ?: return
        sampleProgressEvidence()
        val duration = logicalDurationMs()
        if (duration <= 0) return
        val position = logicalPositionMs()
        val completed = forceCompleted || progressCompleted(position, duration)
        if (position < 5_000 && !completed) return
        reportScope.launch {
            runCatching { Api(activeSession).saveProgress(item.id, position, duration, completed, state, continuousPlayedMs) }
        }
    }

    fun reportRemoteState(state: String) {
        val activeSession = session ?: return
        if (deviceId.isBlank()) return
        reportScope.launch {
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

    fun absolutePlanUrl(activeSession: Session, plan: PlaybackPlan): String {
        return if (plan.url.startsWith("http://") || plan.url.startsWith("https://")) plan.url else activeSession.server + plan.url
    }

    fun subtitleUrl(activeSession: Session, subtitleIndex: Int, startMs: Long): String {
        val startSeconds = "%.3f".format(java.util.Locale.US, startMs.coerceAtLeast(0) / 1000.0)
        return "${activeSession.server}/api/items/${item.id}/subtitles/$subtitleIndex.vtt?start=$startSeconds"
    }

    fun isTextSubtitle(index: Int): Boolean {
        val codec = originalStreams.firstOrNull { it.type == "subtitle" && it.index == index }?.codec?.lowercase().orEmpty()
        return codec in setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")
    }

    // Direct play never sideloads the server-converted VTT: the embedded text
    // track is already in the stream and ExoPlayer decodes it (including
    // SSA/ASS), while the sideload URL forces the server to demux the whole
    // file over NFS just to convert the track. HLS still needs the sideload —
    // the transcoded stream carries no text tracks.
    fun playbackMediaItem(activeSession: Session, url: String, subtitleIndex: Int?, subtitleStartMs: Long, sideloadSubtitle: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        if (sideloadSubtitle && subtitleIndex != null && isTextSubtitle(subtitleIndex)) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl(activeSession, subtitleIndex, subtitleStartMs)))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    fun applyPlaybackPlan(activeSession: Session, plan: PlaybackPlan, wasPlaying: Boolean, startMs: Long, showController: Boolean = true) {
        if (!plan.playable || plan.url.isBlank()) return
        val oldHlsSession = hlsSessionId
        activePlan = plan
        planUsesHls = plan.usesHls
        hlsSessionId = plan.sessionId.ifBlank { null }
        playbackBaseMs = if (plan.usesHls) plan.startPositionMs.coerceAtLeast(0) else 0L
        plan.selectedAudioIndex?.let { selectedAudioIndex = it }
        selectedSubtitleIndex = plan.selectedSubtitleIndex
        if (plan.usesHls) {
            applyHlsSubtitleSelection(plan.selectedSubtitleIndex)
        }
        val subtitleStartMs = if (plan.usesHls) plan.startPositionMs else 0L
        exoPlayer.setMediaItem(playbackMediaItem(activeSession, absolutePlanUrl(activeSession, plan), plan.selectedSubtitleIndex, subtitleStartMs, sideloadSubtitle = plan.usesHls))
        exoPlayer.prepare()
        if (!plan.usesHls && startMs > 0) {
            exoPlayer.seekTo(startMs)
        }
        exoPlayer.playWhenReady = wasPlaying
        if (showController) {
            playerView.showController()
            focusPlayerControl()
            scheduleControllerAutoHide()
        } else {
            playerView.hideController()
            playerView.requestFocus()
        }
        if (oldHlsSession != null && oldHlsSession != hlsSessionId) {
            reportScope.launch {
                logClient("hls-stop-old", message = oldHlsSession)
                stopHlsSession(activeSession, oldHlsSession)
            }
        }
        logClient("plan-applied", extra = JSONObject().put("wasPlaying", wasPlaying).put("startMs", startMs).put("mode", plan.mode))
    }

    // "Direct" bandwidth means original quality, not "force direct file play": with
    // auto the server direct-plays when the profile allows it and falls back to
    // remux/transcode otherwise. Forcing "direct" returns an unplayable plan for
    // files the device cannot play natively (e.g. AC3 audio without passthrough).
    fun forceModeForBandwidth(kbps: Int?): String = if (kbps == null) "auto" else "transcode"

    fun requestPlaybackPlan(kbps: Int?, startMs: Long, forceMode: String, wasPlaying: Boolean? = null, showController: Boolean = true) {
        val activeSession = session ?: return
        val shouldPlay = wasPlaying ?: exoPlayer.playWhenReady
        val serial = playbackPlanSerial + 1L
        playbackPlanSerial = serial
        playbackPlanJob?.cancel()
        playbackPlanJob = scope.launch {
            logClient("playback-plan-request", extra = JSONObject().put("bandwidthKbps", kbps ?: 0).put("forceMode", forceMode).put("startMs", startMs))
            runCatching {
                Api(activeSession).playbackPlan(
                    itemId = item.id,
                    startPositionMs = startMs.coerceAtLeast(0),
                    audioIndex = selectedAudioIndex,
                    subtitleIndex = selectedSubtitleIndex,
                    bandwidthKbps = kbps,
                    forceMode = forceMode,
                    profile = playbackProfile,
                )
            }.onSuccess { plan ->
                if (serial != playbackPlanSerial) return@onSuccess
                fallbackRetried = false
                logClient("playback-plan-response", extra = JSONObject().put("mode", plan.mode).put("hls", plan.usesHls).put("playable", plan.playable))
                if (!plan.playable || plan.url.isBlank()) {
                    logClient("plan-unplayable", message = plan.reasons.joinToString("; ").take(230))
                    if (forceMode != "auto") {
                        requestPlaybackPlan(kbps, startMs, "auto", wasPlaying = shouldPlay, showController = showController)
                    }
                    return@onSuccess
                }
                applyPlaybackPlan(activeSession, plan, shouldPlay, startMs, showController)
            }.onFailure {
                if (serial != playbackPlanSerial) return@onFailure
                logClient("playback-plan-failed", message = it.message.orEmpty())
                val fallbackHlsSession = kbps?.let { _ -> newHlsSessionId(deviceId, item.id) }
                val fallbackUrl = playbackUrl(
                    activeSession,
                    item.id,
                    kbps,
                    fallbackHlsSession,
                    startMs / 1000.0,
                    selectedAudioIndex,
                    selectedSubtitleIndex,
                )
                val oldHlsSession = hlsSessionId
                activePlan = null
                planUsesHls = fallbackHlsSession != null
                hlsSessionId = fallbackHlsSession
                playbackBaseMs = if (fallbackHlsSession != null) startMs.coerceAtLeast(0) else 0L
                exoPlayer.setMediaItem(
                    playbackMediaItem(activeSession, fallbackUrl, selectedSubtitleIndex, if (fallbackHlsSession != null) startMs else 0L, sideloadSubtitle = fallbackHlsSession != null),
                )
                exoPlayer.prepare()
                if (fallbackHlsSession == null && startMs > 0) {
                    exoPlayer.seekTo(startMs)
                }
                exoPlayer.playWhenReady = shouldPlay
                if (!showController) {
                    playerView.hideController()
                    playerView.requestFocus()
                }
                if (oldHlsSession != null && oldHlsSession != hlsSessionId) {
                    reportScope.launch { stopHlsSession(activeSession, oldHlsSession) }
                }
            }
        }
    }

    fun seekToLogical(targetMs: Long, showController: Boolean = true) {
        resetProgressEvidence()
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        pendingSeekTargetMs = null
        val duration = logicalDurationMs()
        val target = if (duration > 0) targetMs.coerceIn(0, duration) else targetMs.coerceAtLeast(0)
        if (planUsesHls) {
            requestPlaybackPlan(selectedBandwidth, target, forceModeForBandwidth(selectedBandwidth), showController = showController)
        } else {
            exoPlayer.seekTo(target)
            if (!showController) {
                playerView.hideController()
                playerView.requestFocus()
            }
        }
    }

    fun pendingSeekTarget(currentTargetMs: Long?, keyCode: Int): Long? {
        val current = currentTargetMs ?: logicalPositionMs()
        return when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> boundarySeekTarget(current, forward = false)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> boundarySeekTarget(current, forward = true)
            else -> null
        }
    }

    fun commitPendingSeek(showController: Boolean) {
        val target = pendingSeekTargetMs ?: return
        seekToLogical(target, showController = showController)
    }

    fun schedulePendingSeekCommit(showController: Boolean) {
        pendingSeekJob?.cancel()
        pendingSeekJob = scope.launch {
            delay(650)
            commitPendingSeek(showController)
        }
    }

    fun switchBandwidth(kbps: Int?, targetSeconds: Double = logicalPositionMs() / 1000.0) {
        val startMs = (targetSeconds * 1000.0).toLong().coerceAtLeast(0)
        selectedBandwidth = kbps
        onBandwidthSelected(kbps)
        requestPlaybackPlan(kbps, startMs, forceModeForBandwidth(kbps))
    }

    fun switchAudio(index: Int?) {
        selectedAudioIndex = index
        if (!planUsesHls && originalStreams.isNotEmpty()) {
            logClient("switch-audio-direct", extra = JSONObject().put("index", index ?: -1))
            applyDirectTrackSelections()
            return
        }
        logClient("switch-audio-plan", extra = JSONObject().put("index", index ?: -1))
        requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), forceModeForBandwidth(selectedBandwidth))
    }

    fun switchSubtitle(index: Int?) {
        selectedSubtitleIndex = index
        if (!planUsesHls && originalStreams.isNotEmpty()) {
            logClient("switch-subtitle-direct", extra = JSONObject().put("index", index ?: -1))
            applyDirectTrackSelections()
            // Progressive playback discards buffered samples of deselected
            // tracks, so a newly enabled text track stays silent until the
            // already-buffered media (up to ~50s) has played out. Seek in
            // place to flush: the empty text queue can't satisfy the seek, so
            // ExoPlayer re-extracts from the current position with the track
            // active and cues appear immediately.
            if (index != null) {
                exoPlayer.seekTo(exoPlayer.currentPosition)
            }
            return
        }
        logClient("switch-subtitle-plan", extra = JSONObject().put("index", index ?: -1))
        requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), forceModeForBandwidth(selectedBandwidth))
    }

    fun applyRemoteCommand(command: PlayerRemoteCommand) {
        when (command.type) {
            "pause" -> exoPlayer.pause()
            "resume" -> exoPlayer.play()
            "seek" -> {
                resetProgressEvidence()
                val target = command.payload.optLong("positionMs").coerceAtLeast(0)
                if (planUsesHls) {
                    requestPlaybackPlan(selectedBandwidth, target, forceModeForBandwidth(selectedBandwidth))
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

    LaunchedEffect(item.id, session) {
        if (session == null) return@LaunchedEffect
        requestPlaybackPlan(selectedBandwidth, initialStartPositionMs.coerceAtLeast(0), forceModeForBandwidth(selectedBandwidth), wasPlaying = true)
    }

    // Resolve the next episode (same show) so we can auto-advance at the end.
    LaunchedEffect(item.id, session) {
        nextEpisode = null
        if (item.kind != "episode") return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        val episodes = runCatching { Api(active).episodes(item.libraryId, item.showTitle) }.getOrNull() ?: return@LaunchedEffect
        nextEpisode = episodeAfter(episodes, item)
    }

    // Surface the Up Next countdown over the last seconds of playback.
    LaunchedEffect(item.id, nextEpisode, exoPlayer) {
        if (nextEpisode == null) {
            upNextSecondsLeft = null
            return@LaunchedEffect
        }
        while (true) {
            delay(500)
            if (upNextDismissed || advancingToNext) {
                upNextSecondsLeft = null
                continue
            }
            val duration = logicalDurationMs()
            val remaining = duration - logicalPositionMs()
            upNextSecondsLeft = if (duration > 0 &&
                exoPlayer.playbackState == Player.STATE_READY &&
                remaining in 1..UpNextLeadMs
            ) {
                ((remaining + 999) / 1000).toInt()
            } else {
                null
            }
        }
    }

    fun isActionKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

    fun isBackKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_BACK ||
        keyCode == AndroidKeyEvent.KEYCODE_ESCAPE

    fun isRevealKey(keyCode: Int): Boolean =
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == AndroidKeyEvent.KEYCODE_MENU

    fun isHiddenSeekKey(keyCode: Int): Boolean = isHiddenSeekKeyCode(keyCode)

    fun previewSeekFromHiddenControls(keyCode: Int): Boolean {
        if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null || playerView.isControllerFullyVisible) {
            return false
        }
        pendingSeekJob?.cancel()
        pendingSeekTargetMs = pendingSeekTarget(pendingSeekTargetMs, keyCode) ?: return false
        return true
    }

    fun revealController(focusTimeBar: Boolean = false): Boolean {
        if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null || playerView.isControllerFullyVisible) {
            return false
        }
        playerView.showController()
        scheduleControllerAutoHide()
        focusPlayerControl(focusTimeBar)
        return true
    }

    fun exitPlayer() {
        logClient("exit-player-called")
        if (exitingPlayer) {
            logClient("exit-player-ignored", message = "already exiting")
            return
        }
        exitingPlayer = true
        playbackPlanJob?.cancel()
        pendingSeekJob?.cancel()
        mainHandler.removeCallbacks(autoHideRunnable)
        logClient("exit-player-stopping-engines")
        reportProgress("stopped")
        runCatching { exoPlayer.stop() }
        logClient("exit-player-navigate")
        onBack()
    }

    // BACK with the OSD up only dismisses the OSD; playback exits when BACK
    // is pressed with the controls already hidden.
    fun dismissOsdOrExit(source: String) {
        if (playerView.isControllerFullyVisible) {
            logClient("$source-hide-osd")
            mainHandler.removeCallbacks(autoHideRunnable)
            playerView.hideController()
            playerView.requestFocus()
        } else {
            exitPlayer()
        }
    }

    BackHandler {
        logClient("compose-back-handler")
        dismissOsdOrExit("compose-back")
    }

    DisposableEffect(Unit) {
        PlayerBackBridge.handler = { event ->
            when {
                // The native track menu owns all keys while open; let events reach
                // its view listeners so BACK closes the menu, not the player.
                playerView.findViewWithTag<View>(NativeTrackMenuTag) != null -> false
                isBackKey(event.keyCode) && event.action == AndroidKeyEvent.ACTION_DOWN -> {
                    logClient("player-back-dispatch", event)
                    if (event.repeatCount == 0) dismissOsdOrExit("player-back")
                    true
                }
                isBackKey(event.keyCode) && event.action == AndroidKeyEvent.ACTION_UP -> {
                    logClient("player-back-dispatch-up", event)
                    true
                }
                else -> false
            }
        }
        PlayerOsdBridge.handler = { event ->
            when {
                playerView.findViewWithTag<View>(NativeTrackMenuTag) != null -> false
                isBackKey(event.keyCode) && event.action == AndroidKeyEvent.ACTION_DOWN -> {
                    logClient("player-osd-back", event)
                    if (event.repeatCount == 0) dismissOsdOrExit("player-osd-back")
                    true
                }
                isBackKey(event.keyCode) && event.action == AndroidKeyEvent.ACTION_UP -> {
                    logClient("player-osd-back-up", event)
                    true
                }
                upNextCardVisible() -> {
                    // The Up Next card owns the screen during its countdown. Don't
                    // consume D-pad/Enter as playback controls; let them fall through
                    // to the Compose overlay so its buttons can be focused and pressed.
                    false
                }
                isHiddenSeekKey(event.keyCode) && !playerView.isControllerFullyVisible && playerView.findViewWithTag<View>(NativeTrackMenuTag) == null -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) {
                        pressedSeekKeys.remove(event.keyCode)
                        schedulePendingSeekCommit(showController = false)
                    } else if (shouldHandleSeekKey(event)) {
                        previewSeekFromHiddenControls(event.keyCode)
                    }
                    true
                }
                isActionKey(event.keyCode) && !playerView.isControllerFullyVisible && playerView.findViewWithTag<View>(NativeTrackMenuTag) == null -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                    true
                }
                isRevealKey(event.keyCode) && !playerView.isControllerFullyVisible && playerView.findViewWithTag<View>(NativeTrackMenuTag) == null -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) revealController()
                    true
                }
                event.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && !playerView.isControllerFullyVisible && playerView.findViewWithTag<View>(NativeTrackMenuTag) == null -> {
                    // Swallow UP while the OSD is hidden: media3's PlayerView would consume
                    // it and show the controller without focusing any of our controls.
                    true
                }
                event.action == AndroidKeyEvent.ACTION_UP && playerView.isControllerFullyVisible -> {
                    scheduleControllerAutoHide()
                    val focused = playerView.findFocus()
                    if (focused == null || focused === playerView) {
                        logClient("osd-focus-repair", event)
                        focusPlayerControl()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        scheduleControllerAutoHide()
        logClient("player-mounted", extra = JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
        onDispose {
            logClient("player-dispose")
            if (PlayerBackBridge.handler != null) PlayerBackBridge.handler = null
            if (PlayerOsdBridge.handler != null) PlayerOsdBridge.handler = null
            playbackPlanJob?.cancel()
            pressedSeekKeys.clear()
            mainHandler.removeCallbacks(autoHideRunnable)
            closeNativeTrackMenu(playerView, restoreFocus = false)
            val oldHlsSession = hlsSessionId
            val activeSession = session
            if (oldHlsSession != null && activeSession != null) {
                reportScope.launch {
                    logClient("hls-stop-dispose", message = oldHlsSession)
                    stopHlsSession(activeSession, oldHlsSession)
                }
            }
            reportProgress("stopped")
            logClient("player-release")
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycle) {
        if (lifecycle == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            logClient("lifecycle-${event.name.lowercase()}")
            if (event == Lifecycle.Event.ON_STOP) {
                reportProgress("stopped")
                exoPlayer.stop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(exoPlayer, item.id) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    reportProgress("stopped", forceCompleted = true)
                    if (nextEpisode != null && !upNextDismissed) {
                        playNextEpisode()
                    }
                }
                if (playbackState == Player.STATE_READY) {
                    applyDirectTrackSelections()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                sampleProgressEvidence()
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    reportProgress(if (isPlaying) "playing" else "paused")
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) resetProgressEvidence()
            }

            // Diagnostic for embedded-subtitle playback: report what ExoPlayer
            // actually exposes and selects, so track problems are debuggable
            // from the server log without a device attached.
            override fun onTracksChanged(tracks: Tracks) {
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val supported = text.count { it.length > 0 && it.isTrackSupported(0) }
                val selected = text.filter { it.isSelected }.joinToString(",") { group ->
                    val format = group.getTrackFormat(0)
                    "${format.sampleMimeType}/${format.language}"
                }
                val disabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                logClient(
                    "tracks-changed",
                    message = "textGroups=${text.size} supported=$supported textDisabled=$disabled selected=[$selected]".take(230),
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                val activeSession = session ?: return
                logClient("exo-error", message = "${error.errorCodeName}: ${error.message.orEmpty()}".take(230))
                val failedPlan = activePlan ?: return
                if (fallbackRetried) return
                fallbackRetried = true
                val position = logicalPositionMs()
                scope.launch {
                    runCatching {
                        Api(activeSession).playbackFailure(
                            itemId = item.id,
                            plan = failedPlan,
                            errorCode = error.errorCodeName,
                            message = error.message.orEmpty(),
                            positionMs = position,
                        )
                    }.onSuccess { fallback ->
                        if (fallback != null) {
                            applyPlaybackPlan(activeSession, fallback, wasPlaying = true, startMs = position)
                        }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(playerView, selectedBandwidth, planUsesHls) {
        val titleView = playerView.findViewById<TextView>(R.id.popcorn_title)
        val clearLogoView = playerView.findViewById<ImageView>(R.id.popcorn_clearlogo)
        val playPauseButton = playerView.findViewById<ImageButton>(R.id.popcorn_play_pause)
        val audioButton = playerView.findViewById<View>(R.id.popcorn_audio)
        val subtitleButton = playerView.findViewById<View>(R.id.popcorn_subtitles)
        val bandwidthButton = playerView.findViewById<TextView>(R.id.popcorn_bandwidth)
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_progress)
        val hlsTimeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(R.id.popcorn_position)
        val nativeDurationView = playerView.findViewById<TextView>(R.id.popcorn_duration)
        val hlsPositionView = playerView.findViewById<TextView>(R.id.popcorn_hls_position)
        val hlsDurationView = playerView.findViewById<TextView>(R.id.popcorn_hls_duration)
        val activeTimeBar = if (planUsesHls) hlsTimeBar else nativeTimeBar
        fun updateDisplayedPosition(positionMs: Long) {
            if (planUsesHls) {
                hlsPositionView?.text = fmtClock(positionMs)
                hlsTimeBar?.setPosition(positionMs)
            } else {
                nativePositionView?.text = fmtClock(positionMs)
                nativeTimeBar?.setPosition(positionMs)
            }
        }

        val controllerKeyListener = View.OnKeyListener { _, keyCode, event ->
            if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null) return@OnKeyListener false
            if (event.action != AndroidKeyEvent.ACTION_UP) return@OnKeyListener false

            if (isRevealKey(keyCode) && revealController()) {
                true
            } else {
                scheduleControllerAutoHide()
                false
            }
        }
        val timeBarKeyListener = View.OnKeyListener { _, keyCode, event ->
            if (playerView.findViewWithTag<View>(NativeTrackMenuTag) != null) return@OnKeyListener false
            if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == AndroidKeyEvent.ACTION_DOWN) {
                    playPauseButton?.takeIf { it.isShown && it.isEnabled }?.requestFocus()
                    scheduleControllerAutoHide()
                }
                return@OnKeyListener true
            }
            if (isActionKey(keyCode)) {
                when (event.action) {
                    AndroidKeyEvent.ACTION_DOWN -> true
                    AndroidKeyEvent.ACTION_UP -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        activeTimeBar?.requestFocus()
                        scheduleControllerAutoHide()
                        true
                    }
                    else -> false
                }
            } else
            if ((keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) && isHiddenSeekKey(keyCode)) {
                if (event.action == AndroidKeyEvent.ACTION_UP) {
                    pressedSeekKeys.remove(keyCode)
                    schedulePendingSeekCommit(showController = true)
                    return@OnKeyListener true
                }
                if (!shouldHandleSeekKey(event)) return@OnKeyListener true
                pendingSeekJob?.cancel()
                val target = pendingSeekTarget(pendingSeekTargetMs, keyCode) ?: return@OnKeyListener true
                pendingSeekTargetMs = target
                updateDisplayedPosition(target)
                scheduleControllerAutoHide()
                true
            } else {
                controllerKeyListener.onKey(null, keyCode, event)
            }
        }

        titleView?.text = if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title
        if (clearLogoView != null && session != null) {
            val request = ImageRequest.Builder(context)
                .data(imageUrl(session, item.id, "clearlogo", item.posterMtimeUnix.coerceAtLeast(item.backdropMtimeUnix)))
                .size(440, 192)
                .addHeader("Authorization", "Bearer ${session.token}")
                .target(clearLogoView)
                .build()
            context.imageLoader.enqueue(request)
        }
        bandwidthButton?.text = bandwidthLabel(selectedBandwidth)
        playPauseButton?.setImageResource(
            if (exoPlayer.isPlaying) {
                androidx.media3.ui.R.drawable.exo_icon_pause
            } else {
                androidx.media3.ui.R.drawable.exo_icon_play
            },
        )
        nativeTimeBar?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        hlsTimeBar?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        nativePositionView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        nativeDurationView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        hlsPositionView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        hlsDurationView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        playerView.setOnKeyListener(controllerKeyListener)
        playPauseButton?.setOnKeyListener(controllerKeyListener)
        playPauseButton?.setOnClickListener {
            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            playPauseButton.setImageResource(
                if (exoPlayer.isPlaying) {
                    androidx.media3.ui.R.drawable.exo_icon_pause
                } else {
                    androidx.media3.ui.R.drawable.exo_icon_play
                },
            )
            scheduleControllerAutoHide()
        }
        playerView.findViewById<View>(R.id.popcorn_rew)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(R.id.popcorn_rew)?.setOnClickListener {
            seekToLogical(boundarySeekTarget(logicalPositionMs(), forward = false))
            scheduleControllerAutoHide()
        }
        playerView.findViewById<View>(R.id.popcorn_ffwd)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(R.id.popcorn_ffwd)?.setOnClickListener {
            seekToLogical(boundarySeekTarget(logicalPositionMs(), forward = true))
            scheduleControllerAutoHide()
        }
        activeTimeBar?.setOnKeyListener(timeBarKeyListener)
        var timeBarScrubbing = false
        val timeScrubListener = object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                timeBarScrubbing = true
                updateDisplayedPosition(position.coerceAtLeast(0))
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                if (timeBarScrubbing) {
                    updateDisplayedPosition(position.coerceAtLeast(0))
                }
            }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                val wasScrubbing = timeBarScrubbing
                timeBarScrubbing = false
                if (wasScrubbing && !canceled) {
                    seekToLogical(position.coerceAtLeast(0))
                } else {
                    updateDisplayedPosition(logicalPositionMs())
                }
            }
        }
        hlsTimeBar?.addListener(timeScrubListener)
        nativeTimeBar?.addListener(timeScrubListener)
        nativeTimeBar?.setKeyTimeIncrement(30_000)
        hlsTimeBar?.setKeyTimeIncrement(30_000)
        audioButton?.setOnClickListener {
            scheduleControllerAutoHide()
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
        }
        audioButton?.setOnKeyListener(controllerKeyListener)
        subtitleButton?.setOnClickListener {
            scheduleControllerAutoHide()
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
            playerView.findViewById<View>(R.id.popcorn_play_pause)?.setOnKeyListener(null)
            playerView.findViewById<View>(R.id.popcorn_play_pause)?.setOnClickListener(null)
            playerView.findViewById<View>(R.id.popcorn_rew)?.setOnKeyListener(null)
            playerView.findViewById<View>(R.id.popcorn_rew)?.setOnClickListener(null)
            playerView.findViewById<View>(R.id.popcorn_ffwd)?.setOnKeyListener(null)
            playerView.findViewById<View>(R.id.popcorn_ffwd)?.setOnClickListener(null)
            nativeTimeBar?.setOnKeyListener(null)
            hlsTimeBar?.setOnKeyListener(null)
            hlsTimeBar?.removeListener(timeScrubListener)
            nativeTimeBar?.removeListener(timeScrubListener)
            pendingSeekJob?.cancel()
            audioButton?.setOnClickListener(null)
            audioButton?.setOnKeyListener(null)
            subtitleButton?.setOnClickListener(null)
            subtitleButton?.setOnKeyListener(null)
            bandwidthButton?.setOnClickListener(null)
            bandwidthButton?.setOnKeyListener(null)
        }
    }

    LaunchedEffect(selectedBandwidth, activePlan?.mode, playerView) {
        playerView.findViewById<TextView>(R.id.popcorn_bandwidth)?.text = bandwidthLabel(selectedBandwidth)
    }

    LaunchedEffect(planUsesHls, playbackBaseMs, playerView, item.durationMs) {
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_progress)
        val playPauseButton = playerView.findViewById<ImageButton>(R.id.popcorn_play_pause)
        val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(R.id.popcorn_position)
        val nativeDurationView = playerView.findViewById<TextView>(R.id.popcorn_duration)
        val positionView = playerView.findViewById<TextView>(R.id.popcorn_hls_position)
        val durationView = playerView.findViewById<TextView>(R.id.popcorn_hls_duration)
        nativeTimeBar?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        timeBar?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        nativePositionView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        nativeDurationView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        positionView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        durationView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        while (true) {
            val duration = logicalDurationMs()
            val position = pendingSeekTargetMs ?: logicalPositionMs()
            if (duration > 0) {
                if (planUsesHls) {
                    timeBar?.setDuration(duration)
                    timeBar?.setBufferedPosition(duration)
                    timeBar?.setPosition(position)
                    durationView?.text = fmtClock(duration)
                    positionView?.text = fmtClock(position)
                } else {
                    nativeTimeBar?.setDuration(duration)
                    nativeTimeBar?.setBufferedPosition(exoPlayer.bufferedPosition.coerceAtLeast(0))
                    nativeTimeBar?.setPosition(position)
                    nativeDurationView?.text = fmtClock(duration)
                    nativePositionView?.text = fmtClock(position)
                }
            }
            playPauseButton?.setImageResource(
                if (exoPlayer.isPlaying) {
                    androidx.media3.ui.R.drawable.exo_icon_pause
                } else {
                    androidx.media3.ui.R.drawable.exo_icon_play
                },
            )
            delay(250)
        }
    }

    LaunchedEffect(originalStreams.size, selectedAudioIndex, selectedSubtitleIndex, planUsesHls, exoPlayer) {
        if (planUsesHls || originalStreams.isEmpty()) return@LaunchedEffect
        repeat(20) {
            applyDirectTrackSelections()
            if (exoPlayer.currentTracks.groups.isNotEmpty()) return@LaunchedEffect
            delay(250)
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
        playerView.showController()
        focusPlayerControl()
        scheduleControllerAutoHide()
    }

    // The embedded PlayerView holds Android View focus during playback. While the
    // Up Next card is visible, block the player from holding/reclaiming focus and
    // release it so the Compose overlay's buttons can take focus from the D-pad.
    val upNextVisible = nextEpisode != null && upNextSecondsLeft != null && !upNextDismissed && !advancingToNext
    LaunchedEffect(upNextVisible) {
        if (upNextVisible) {
            playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            playerView.isFocusable = false
            playerView.clearFocus()
            delay(120)
            runCatching { upNextFocus.requestFocus() }
        } else {
            playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            playerView.isFocusable = true
        }
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
        pendingSeekTargetMs?.let { target ->
            Text(
                text = fmtClock(target),
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(min = 168.dp)
                    .background(Color(0xCC080C12), RoundedCornerShape(18.dp))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
        val next = nextEpisode
        val secondsLeft = upNextSecondsLeft
        if (next != null && secondsLeft != null && !upNextDismissed && !advancingToNext) {
            UpNextCard(
                episode = next,
                secondsLeft = secondsLeft,
                focusRequester = upNextFocus,
                onPlayNow = { playNextEpisode() },
                onDismiss = { upNextDismissed = true },
            )
            BackHandler { upNextDismissed = true }
        }
    }
}

// Window before the end of an episode during which the Up Next card appears.
private const val UpNextLeadMs = 25_000L

// Next episode in show order: prefer the position in the returned list, fall
// back to the first episode after the current one by (season, episode).
private fun episodeAfter(episodes: List<PopItem>, current: PopItem): PopItem? {
    val idx = episodes.indexOfFirst { it.id == current.id }
    if (idx >= 0) return episodes.getOrNull(idx + 1)
    return episodes
        .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        .firstOrNull {
            it.seasonNumber > current.seasonNumber ||
                (it.seasonNumber == current.seasonNumber && it.episodeNumber > current.episodeNumber)
        }
}

@Composable
private fun UpNextCard(
    episode: PopItem,
    secondsLeft: Int,
    focusRequester: FocusRequester,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focusRequester.requestFocus() }
    }
    val code = if (episode.seasonNumber > 0 && episode.episodeNumber > 0) {
        "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber)
    } else {
        ""
    }
    val line = listOf(episode.showTitle, code, episode.episodeTitle.ifBlank { episode.title })
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")
    Box(Modifier.fillMaxSize().padding(end = 48.dp, bottom = 64.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF00B0E14))
                .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Up next  ·  in ${secondsLeft}s", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(line, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusButton(label = "Play now", primary = true, modifier = Modifier.focusRequester(focusRequester), onClick = onPlayNow)
                FocusButton(label = "Cancel", primary = false, onClick = onDismiss)
            }
        }
    }
}
