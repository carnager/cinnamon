package dev.popcorn.tv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import kotlinx.coroutines.delay

@Composable
fun SidecarPlayerScreen(
    url: String,
    title: String,
    session: Session?,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    BackHandler(onBack = onBack)

    val player = remember(url, session?.token) {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            val token = session?.token.orEmpty()
            if (token.isNotBlank()) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            }
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) onBack()
                    }
                })
            }
    }

    val playerView = remember {
        (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
            this.player = player
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = false
            controllerShowTimeoutMs = 5000
            keepScreenOn = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    // Shared with the position ticker so scrubbing isn't overwritten mid-drag.
    val scrubbing = remember { booleanArrayOf(false) }

    val autoHideRunnable = remember(playerView, player) {
        Runnable {
            if (player.isPlaying) {
                playerView.hideController()
                playerView.requestFocus()
            }
        }
    }

    fun scheduleControllerAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, 4_500)
    }

    fun isActionKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

    fun isRevealKey(keyCode: Int): Boolean =
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == AndroidKeyEvent.KEYCODE_MENU

    fun isSeekKey(keyCode: Int): Boolean = keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
        keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND ||
        keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD

    fun boundarySeekTarget(currentMs: Long, forward: Boolean): Long {
        val step = 30_000L
        return if (forward) {
            ((currentMs / step) + 1L) * step
        } else {
            (((currentMs - 1L).coerceAtLeast(0L) / step) * step).coerceAtLeast(0L)
        }
    }

    fun seekByKey(keyCode: Int) {
        val target = when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT,
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> boundarySeekTarget(player.currentPosition.coerceAtLeast(0), forward = false)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> boundarySeekTarget(player.currentPosition.coerceAtLeast(0), forward = true)
            else -> return
        }
        player.seekTo(target)
    }

    fun revealController(focusTimeBar: Boolean = false): Boolean {
        if (playerView.isControllerFullyVisible) return false
        playerView.showController()
        scheduleControllerAutoHide()
        playerView.post {
            val focusTarget = if (focusTimeBar) {
                playerView.findViewById<View>(R.id.popcorn_progress)
            } else {
                playerView.findViewById<View>(R.id.popcorn_play_pause)
            }
            focusTarget?.requestFocus() ?: playerView.requestFocus()
        }
        return true
    }

    DisposableEffect(playerView) {
        PlayerOsdBridge.handler = { event ->
            when {
                isSeekKey(event.keyCode) && !playerView.isControllerFullyVisible -> {
                    if (event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount == 0) seekByKey(event.keyCode)
                    true
                }
                isActionKey(event.keyCode) && !playerView.isControllerFullyVisible -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    true
                }
                isRevealKey(event.keyCode) && !playerView.isControllerFullyVisible -> {
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
            player.release()
        }
    }

    DisposableEffect(playerView) {
        val titleView = playerView.findViewById<TextView>(R.id.popcorn_title)
        val clearLogoView = playerView.findViewById<ImageView>(R.id.popcorn_clearlogo)
        val playPauseButton = playerView.findViewById<ImageButton>(R.id.popcorn_play_pause)
        val rewButton = playerView.findViewById<View>(R.id.popcorn_rew)
        val ffwdButton = playerView.findViewById<View>(R.id.popcorn_ffwd)
        val audioButton = playerView.findViewById<View>(R.id.popcorn_audio)
        val subtitleButton = playerView.findViewById<View>(R.id.popcorn_subtitles)
        val bandwidthButton = playerView.findViewById<View>(R.id.popcorn_bandwidth)
        val hlsTimeBar = playerView.findViewById<View>(R.id.popcorn_hls_progress)
        val hlsPositionView = playerView.findViewById<View>(R.id.popcorn_hls_position)
        val hlsDurationView = playerView.findViewById<View>(R.id.popcorn_hls_duration)
        val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_progress)

        titleView?.text = title
        clearLogoView?.visibility = View.GONE
        audioButton?.visibility = View.GONE
        subtitleButton?.visibility = View.GONE
        bandwidthButton?.visibility = View.GONE
        hlsTimeBar?.visibility = View.GONE
        hlsPositionView?.visibility = View.GONE
        hlsDurationView?.visibility = View.GONE
        timeBar?.visibility = View.VISIBLE
        timeBar?.setKeyTimeIncrement(30_000)

        val controllerKeyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != AndroidKeyEvent.ACTION_UP) return@OnKeyListener false
            if (isRevealKey(keyCode) && revealController()) {
                true
            } else {
                scheduleControllerAutoHide()
                false
            }
        }
        val timeBarKeyListener = View.OnKeyListener { _, keyCode, event ->
            when {
                isActionKey(keyCode) && event.action == AndroidKeyEvent.ACTION_DOWN -> true
                isActionKey(keyCode) && event.action == AndroidKeyEvent.ACTION_UP -> {
                    if (player.isPlaying) player.pause() else player.play()
                    timeBar?.requestFocus()
                    scheduleControllerAutoHide()
                    true
                }
                (keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) &&
                    event.action == AndroidKeyEvent.ACTION_DOWN -> {
                    seekByKey(keyCode)
                    scheduleControllerAutoHide()
                    true
                }
                else -> controllerKeyListener.onKey(null, keyCode, event)
            }
        }
        val scrubListener = object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubbing[0] = true
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {}
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing[0] = false
                if (!canceled) player.seekTo(position.coerceAtLeast(0))
            }
        }

        playerView.setOnKeyListener(controllerKeyListener)
        playPauseButton?.setOnKeyListener(controllerKeyListener)
        playPauseButton?.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            scheduleControllerAutoHide()
        }
        rewButton?.setOnKeyListener(controllerKeyListener)
        rewButton?.setOnClickListener {
            player.seekTo(boundarySeekTarget(player.currentPosition.coerceAtLeast(0), forward = false))
            scheduleControllerAutoHide()
        }
        ffwdButton?.setOnKeyListener(controllerKeyListener)
        ffwdButton?.setOnClickListener {
            player.seekTo(boundarySeekTarget(player.currentPosition.coerceAtLeast(0), forward = true))
            scheduleControllerAutoHide()
        }
        timeBar?.setOnKeyListener(timeBarKeyListener)
        timeBar?.addListener(scrubListener)

        onDispose {
            playerView.setOnKeyListener(null)
            playPauseButton?.setOnKeyListener(null)
            playPauseButton?.setOnClickListener(null)
            rewButton?.setOnKeyListener(null)
            rewButton?.setOnClickListener(null)
            ffwdButton?.setOnKeyListener(null)
            ffwdButton?.setOnClickListener(null)
            timeBar?.setOnKeyListener(null)
            timeBar?.removeListener(scrubListener)
        }
    }

    LaunchedEffect(playerView, player) {
        val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_progress)
        val positionView = playerView.findViewById<TextView>(R.id.popcorn_position)
        val durationView = playerView.findViewById<TextView>(R.id.popcorn_duration)
        val playPauseButton = playerView.findViewById<ImageButton>(R.id.popcorn_play_pause)
        while (true) {
            val duration = player.duration
            if (duration > 0 && !scrubbing[0]) {
                val position = player.currentPosition.coerceAtLeast(0)
                timeBar?.setDuration(duration)
                timeBar?.setBufferedPosition(player.bufferedPosition.coerceAtLeast(0))
                timeBar?.setPosition(position)
                durationView?.text = fmtClock(duration)
                positionView?.text = fmtClock(position)
            }
            playPauseButton?.setImageResource(
                if (player.isPlaying) {
                    androidx.media3.ui.R.drawable.exo_icon_pause
                } else {
                    androidx.media3.ui.R.drawable.exo_icon_play
                },
            )
            delay(250)
        }
    }

    LaunchedEffect(Unit) {
        delay(150)
        playerView.requestFocus()
        playerView.showController()
        scheduleControllerAutoHide()
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { playerView },
        )
    }
}
