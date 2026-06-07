package dev.popcorn.tv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onRemoteStop: () -> Unit,
    onRemoteCommandConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
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
    val pressedSeekKeys = remember(item.id) { mutableMapOf<Int, Long>() }
    val originalStreams = remember { mutableStateListOf<StreamInfo>() }
    val playbackProfile = remember(context) { buildPlaybackProfile(context) }

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
        val position = if (planUsesHls) playbackBaseMs + current else current
        return if (item.durationMs > 0) position.coerceIn(0, item.durationMs) else position
    }

    fun logicalDurationMs(): Long {
        return item.durationMs.takeIf { it > 0 } ?: exoPlayer.duration.coerceAtLeast(0)
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
        applyOriginalTrackSelection(exoPlayer, originalStreams, selectedAudioIndex, selectedSubtitleIndex)
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

    fun playbackMediaItem(activeSession: Session, url: String, subtitleIndex: Int?, subtitleStartMs: Long): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        if (subtitleIndex != null && isTextSubtitle(subtitleIndex)) {
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
        exoPlayer.setMediaItem(playbackMediaItem(activeSession, absolutePlanUrl(activeSession, plan), plan.selectedSubtitleIndex, subtitleStartMs))
        exoPlayer.prepare()
        if (!plan.usesHls && startMs > 0) {
            exoPlayer.seekTo(startMs)
        }
        exoPlayer.playWhenReady = wasPlaying
        if (showController) {
            playerView.showController()
            scheduleControllerAutoHide()
        } else {
            playerView.hideController()
            playerView.requestFocus()
        }
        if (oldHlsSession != null && oldHlsSession != hlsSessionId) {
            scope.launch { stopHlsSession(activeSession, oldHlsSession) }
        }
    }

    fun requestPlaybackPlan(kbps: Int?, startMs: Long, forceMode: String, wasPlaying: Boolean = exoPlayer.playWhenReady, showController: Boolean = true) {
        val activeSession = session ?: return
        val serial = playbackPlanSerial + 1L
        playbackPlanSerial = serial
        playbackPlanJob?.cancel()
        playbackPlanJob = scope.launch {
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
                applyPlaybackPlan(activeSession, plan, wasPlaying, startMs, showController)
            }.onFailure {
                if (serial != playbackPlanSerial) return@onFailure
                if (forceMode == "direct") return@onFailure
                val fallbackUrl = playbackUrl(
                    activeSession,
                    item.id,
                    kbps,
                    kbps?.let { newHlsSessionId(deviceId, item.id) },
                    startMs / 1000.0,
                    selectedAudioIndex,
                    selectedSubtitleIndex,
                )
                exoPlayer.setMediaItem(playbackMediaItem(activeSession, fallbackUrl, selectedSubtitleIndex, if (kbps == null) 0L else startMs))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = wasPlaying
                if (!showController) {
                    playerView.hideController()
                    playerView.requestFocus()
                }
            }
        }
    }

    fun seekToLogical(targetMs: Long, showController: Boolean = true) {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        pendingSeekTargetMs = null
        val duration = logicalDurationMs()
        val target = if (duration > 0) targetMs.coerceIn(0, duration) else targetMs.coerceAtLeast(0)
        if (planUsesHls) {
            requestPlaybackPlan(selectedBandwidth, target, "auto", showController = showController)
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
        requestPlaybackPlan(kbps, startMs, if (kbps == null) "direct" else "auto")
    }

    fun switchAudio(index: Int?) {
        selectedAudioIndex = index
        requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), "auto")
    }

    fun switchSubtitle(index: Int?) {
        selectedSubtitleIndex = index
        requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), "auto")
    }

    fun applyRemoteCommand(command: PlayerRemoteCommand) {
        when (command.type) {
            "pause" -> exoPlayer.pause()
            "resume" -> exoPlayer.play()
            "seek" -> {
                val target = command.payload.optLong("positionMs").coerceAtLeast(0)
                if (planUsesHls) {
                    requestPlaybackPlan(selectedBandwidth, target, "auto")
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
        requestPlaybackPlan(selectedBandwidth, initialStartPositionMs.coerceAtLeast(0), "auto", wasPlaying = true)
    }

    fun isActionKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

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
        playerView.post {
            val focusTarget = if (focusTimeBar) {
                playerView.findViewById<View>(R.id.popcorn_hls_progress)?.takeIf { planUsesHls }
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
            playbackPlanJob?.cancel()
            pressedSeekKeys.clear()
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

            override fun onPlayerError(error: PlaybackException) {
                val activeSession = session ?: return
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
        val audioButton = playerView.findViewById<View>(R.id.popcorn_audio)
        val subtitleButton = playerView.findViewById<View>(R.id.popcorn_subtitles)
        val bandwidthButton = playerView.findViewById<TextView>(R.id.popcorn_bandwidth)
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        val hlsTimeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
        val nativeDurationView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
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
        nativeTimeBar?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        hlsTimeBar?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        nativePositionView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        nativeDurationView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        hlsPositionView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        hlsDurationView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        playerView.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.setOnKeyListener(controllerKeyListener)
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
                if (wasScrubbing && !canceled && planUsesHls) {
                    seekToLogical(position.coerceAtLeast(0))
                } else if (wasScrubbing && !canceled) {
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
            if (planUsesHls && originalStreams.any { it.type == "audio" }) {
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
            if (planUsesHls && originalStreams.any { it.type == "subtitle" }) {
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
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
        val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_position)
        val nativeDurationView = playerView.findViewById<TextView>(androidx.media3.ui.R.id.exo_duration)
        val positionView = playerView.findViewById<TextView>(R.id.popcorn_hls_position)
        val durationView = playerView.findViewById<TextView>(R.id.popcorn_hls_duration)
        nativeTimeBar?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        timeBar?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        nativePositionView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        nativeDurationView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        positionView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        durationView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        while (planUsesHls) {
            val duration = logicalDurationMs()
            val position = pendingSeekTargetMs ?: logicalPositionMs()
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
    }
}
