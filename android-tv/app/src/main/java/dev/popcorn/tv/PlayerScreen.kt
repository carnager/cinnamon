package dev.popcorn.tv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
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
    var hlsSessionId by remember(item.id) {
        mutableStateOf(initialBandwidthKbps?.let { newHlsSessionId(deviceId, item.id) })
    }
    var playbackBaseMs by remember(item.id) { mutableStateOf(if (initialBandwidthKbps != null) initialStartPositionMs.coerceAtLeast(0) else 0L) }
    val originalStreams = remember { mutableStateListOf<StreamInfo>() }

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
                val url = playbackUrl(
                    session,
                    item.id,
                    selectedBandwidth,
                    hlsSessionId,
                    initialStartPositionMs.coerceAtLeast(0) / 1000.0,
                    selectedAudioIndex,
                    selectedSubtitleIndex,
                )
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                if (selectedBandwidth == null && initialStartPositionMs > 0) {
                    seekTo(initialStartPositionMs)
                }
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
        val newHlsSession = kbps?.let { newHlsSessionId(deviceId, item.id) }
        selectedBandwidth = kbps
        onBandwidthSelected(kbps)
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
