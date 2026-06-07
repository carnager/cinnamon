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
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            } else {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
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
        val audioButton = playerView.findViewById<View>(R.id.popcorn_audio)
        val subtitleButton = playerView.findViewById<View>(R.id.popcorn_subtitles)
        val bandwidthButton = playerView.findViewById<View>(R.id.popcorn_bandwidth)
        val hlsTimeBar = playerView.findViewById<View>(R.id.popcorn_hls_progress)
        val hlsPositionView = playerView.findViewById<View>(R.id.popcorn_hls_position)
        val hlsDurationView = playerView.findViewById<View>(R.id.popcorn_hls_duration)
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)

        titleView?.text = title
        clearLogoView?.visibility = View.GONE
        audioButton?.visibility = View.GONE
        subtitleButton?.visibility = View.GONE
        bandwidthButton?.visibility = View.GONE
        hlsTimeBar?.visibility = View.GONE
        hlsPositionView?.visibility = View.GONE
        hlsDurationView?.visibility = View.GONE
        nativeTimeBar?.visibility = View.VISIBLE
        nativeTimeBar?.setKeyTimeIncrement(30_000)

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
                    nativeTimeBar?.requestFocus()
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

        playerView.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.setOnKeyListener(controllerKeyListener)
        nativeTimeBar?.setOnKeyListener(timeBarKeyListener)

        onDispose {
            playerView.setOnKeyListener(null)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.setOnKeyListener(null)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.setOnKeyListener(null)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.setOnKeyListener(null)
            nativeTimeBar?.setOnKeyListener(null)
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
