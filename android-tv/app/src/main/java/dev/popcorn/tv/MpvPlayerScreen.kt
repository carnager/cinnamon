package dev.popcorn.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val MPV_TAG = "popcorn-mpv"

// libmpv test player: direct-streams the original file and lets mpv do all
// decoding — MediaCodec for video, ffmpeg for audio the box can't (DTS,
// TrueHD), libass for every subtitle format. No server transcode involved.
@Composable
fun MpvPlayerScreen(
    item: PopItem,
    session: Session?,
    deviceId: String,
    initialAudioIndex: Int?,
    initialSubtitleIndex: Int?,
    initialStartPositionMs: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val reportScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val focus = remember { FocusRequester() }
    var positionMs by remember { mutableLongStateOf(initialStartPositionMs.coerceAtLeast(0)) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf("") }
    var osdUntil by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + 5_000) }
    var osdPinned by remember { mutableStateOf(false) }
    var osdTick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var continuousPlayedMs by remember { mutableLongStateOf(0L) }
    var exiting by remember { mutableStateOf(false) }
    var surfaceLost by remember { mutableStateOf(false) }

    fun logClient(event: String, message: String = "", extra: JSONObject? = null) {
        Log.i(MPV_TAG, "$event $message ${extra ?: ""}")
        val activeSession = session ?: return
        reportScope.launch {
            runCatching {
                Api(activeSession).clientLog(
                    JSONObject()
                        .put("deviceId", deviceId)
                        .put("deviceName", shieldDeviceName())
                        .put("event", event)
                        .put("screen", "player")
                        .put("itemId", item.id)
                        .put("title", if (item.kind == "episode") item.episodeTitle.ifBlank { item.title } else item.title)
                        .put("planMode", "direct")
                        .put("usesHls", false)
                        .put("usesMpv", true)
                        .put("positionMs", positionMs)
                        .put("durationMs", durationMs)
                        .put("message", message.take(230))
                        .put("extra", extra ?: JSONObject()),
                )
            }
        }
    }

    val view = remember {
        MpvSurface(context, onEvent = { event ->
            when (event) {
                is MpvSurface.Event.Position -> positionMs = event.ms
                is MpvSurface.Event.Duration -> durationMs = event.ms
                is MpvSurface.Event.Paused -> paused = event.paused
                is MpvSurface.Event.Buffering -> buffering = event.buffering
                is MpvSurface.Event.Loaded -> {
                    buffering = false
                    logClient("mpv-file-loaded", extra = event.info)
                }
                is MpvSurface.Event.Ended -> {
                    logClient("mpv-end-file", message = event.reason)
                    if (!exiting) error = "playback stopped: mpv could not open or keep playing the file"
                }
                is MpvSurface.Event.Log -> logClient("mpv-log", message = event.text)
                // Home, screensaver, input switch: playback was already stopped
                // to keep the decoder from wedging, so leave the player.
                is MpvSurface.Event.SurfaceLost -> {
                    logClient("mpv-surface-lost")
                    surfaceLost = true
                }
            }
        })
    }

    fun progressCompleted(position: Long, duration: Long): Boolean {
        if (duration <= 0 || position <= 0) return false
        return duration - position <= 90_000 || position.toDouble() / duration.toDouble() >= 0.92
    }

    fun reportProgress(state: String) {
        val activeSession = session ?: return
        val duration = durationMs
        val position = positionMs
        if (duration <= 0) return
        val completed = progressCompleted(position, duration)
        if (position < 5_000 && !completed) return
        reportScope.launch {
            runCatching { Api(activeSession).saveProgress(item.id, position, duration, completed, state, continuousPlayedMs) }
        }
    }

    fun showOsd() {
        osdUntil = SystemClock.elapsedRealtime() + 5_000
        osdTick++
    }

    fun seekBy(deltaMs: Long) {
        continuousPlayedMs = 0L
        view.seekRelative(deltaMs / 1000.0)
        showOsd()
    }

    fun exit() {
        if (exiting) return
        exiting = true
        reportProgress("stopped")
        logClient("mpv-exit")
        // Tear mpv down while the surface still exists, then navigate.
        view.shutdown { onBack() }
    }

    LaunchedEffect(surfaceLost) {
        if (surfaceLost) exit()
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        val activeSession = session ?: return@LaunchedEffect
        val url = playbackUrl(activeSession, item.id, null, null, 0.0)
        logClient("mpv-start", extra = JSONObject().put("startMs", initialStartPositionMs).put("abi", android.os.Build.SUPPORTED_ABIS.joinToString(",")))
        view.load(
            url = url,
            token = activeSession.token,
            startMs = initialStartPositionMs,
            audioIndex = initialAudioIndex,
            subtitleIndex = initialSubtitleIndex,
        )
    }

    // Progress, played-time evidence (the server ignores reports with less
    // than two minutes of settled playback) and the stats line.
    LaunchedEffect(Unit) {
        var lastSample = SystemClock.elapsedRealtime()
        var lastReport = lastSample
        while (true) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            if (!paused && !buffering) continuousPlayedMs += (now - lastSample).coerceIn(0L, 12_000L)
            lastSample = now
            if (now - lastReport >= 10_000) {
                lastReport = now
                reportProgress(if (paused) "paused" else "playing")
            }
            if (osdPinned || now < osdUntil) stats = view.statsLine()
            osdTick++
        }
    }

    DisposableEffect(Unit) {
        onDispose { view.shutdown {} }
    }

    BackHandler { exit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val e = keyEvent.nativeKeyEvent
                if (e.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (e.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        view.togglePause(); showOsd(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { view.setPaused(false); showOsd(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { view.setPaused(true); showOsd(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-60_000); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(60_000); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { view.cycle("audio"); showOsd(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { view.cycle("sub"); showOsd(); true }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> {
                        osdPinned = !osdPinned; showOsd(); true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        // Reading osdTick re-evaluates the timed OSD once a second.
        val osdVisible = osdTick >= 0 && (osdPinned || paused || buffering || error != null || SystemClock.elapsedRealtime() < osdUntil)
        if (osdVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp)
                    .background(Color(0xB0000000))
                    .padding(16.dp),
            ) {
                val title = if (item.kind == "episode") "${item.title} — ${item.episodeTitle}" else item.title
                Text("mpv test · $title", color = Color.White, fontSize = 18.sp)
                val state = when {
                    error != null -> error!!
                    buffering -> "buffering"
                    paused -> "paused"
                    else -> "playing"
                }
                Text("${formatMs(positionMs)} / ${formatMs(durationMs)} · $state", color = Color.White, fontSize = 16.sp)
                if (stats.isNotBlank()) Text(stats, color = Color(0xFFB0B0B0), fontSize = 13.sp)
                Text("OK pause · ◀▶ ±10s · ⏪⏩ ±60s · ▲ audio · ▼ subs · menu pin stats", color = Color(0xFF808080), fontSize = 12.sp)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(Locale.US, s / 60, s % 60)
}

// SurfaceView host for one libmpv instance. Follows mpv-android's lifecycle:
// vo stays "null" until a surface exists, android-surface-size tracks the
// surface, and the vo is dropped again before the surface goes away.
class MpvSurface(
    context: Context,
    private val onEvent: (Event) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, MPVLib.EventObserver, MPVLib.LogObserver {
    sealed class Event {
        data class Position(val ms: Long) : Event()
        data class Duration(val ms: Long) : Event()
        data class Paused(val paused: Boolean) : Event()
        data class Buffering(val buffering: Boolean) : Event()
        data class Loaded(val info: JSONObject) : Event()
        data class Ended(val reason: String) : Event()
        data class Log(val text: String) : Event()
        data object SurfaceLost : Event()
    }

    private val main = Handler(Looper.getMainLooper())
    private val mpv: MPVLib = requireNotNull(MPVLib.create(context.applicationContext)) { "libmpv could not be created" }
    private var released = false
    private var pendingAudioIndex: Int? = null
    private var pendingSubtitleIndex: Int? = null

    init {
        holder.addCallback(this)
        keepScreenOn = true
        mpv.addLogObserver(this)
        mpv.setOptionString("msg-level", "all=v")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("vo", "null")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("audio-set-media-role", "yes")
        // 32-bit process: keep the demuxer cache well inside the address space.
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${16 * 1024 * 1024}")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("input-default-bindings", "no")
        // Pointing libass at /system/fonts made it load ~200 font files
        // (CJK included) twice per start — about 4s on the Streamer. One copied
        // Roboto plus the file's own embedded fonts covers our subtitles.
        val fontsDir = subtitleFontsDir(context)
        mpv.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
        mpv.setOptionString("osd-fonts-dir", fontsDir.absolutePath)
        mpv.setOptionString("sub-font-provider", "none")
        mpv.setOptionString("embeddedfonts", "yes")
        mpv.setOptionString("sub-font", "Roboto")
        mpv.setOptionString("sub-scale-with-window", "yes")
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    fun load(url: String, token: String, startMs: Long, audioIndex: Int?, subtitleIndex: Int?) {
        if (released) return
        pendingAudioIndex = audioIndex
        pendingSubtitleIndex = subtitleIndex
        if (token.isNotBlank()) mpv.setPropertyString("http-header-fields", "Authorization: Bearer $token")
        mpv.setPropertyString("start", if (startMs > 0) "%.3f".format(Locale.US, startMs / 1000.0) else "none")
        mpv.command(arrayOf("loadfile", url, "replace"))
    }

    fun togglePause() {
        if (!released) mpv.command(arrayOf("cycle", "pause"))
    }

    fun setPaused(paused: Boolean) {
        if (!released) mpv.setPropertyBoolean("pause", paused)
    }

    fun seekRelative(seconds: Double) {
        if (!released) mpv.command(arrayOf("seek", "%.3f".format(Locale.US, seconds), "relative"))
    }

    fun cycle(property: String) {
        if (!released) mpv.command(arrayOf("cycle", property))
    }

    fun statsLine(): String {
        if (released) return ""
        fun p(name: String) = runCatching { mpv.getPropertyString(name) }.getOrNull().orEmpty()
        val video = "${p("video-codec").substringBefore(' ')} ${p("video-params/w")}x${p("video-params/h")} ${p("video-params/pixelformat")}"
        val hwdec = p("hwdec-current").ifBlank { "no" }
        val audio = "${p("audio-codec-name")} ${p("audio-params/channel-count")}ch → ${p("current-ao")}"
        val sub = p("current-tracks/sub/codec").ifBlank { "off" }
        val drops = "drop ${p("frame-drop-count").ifBlank { "0" }}/${p("decoder-frame-drop-count").ifBlank { "0" }}"
        val cache = p("demuxer-cache-duration").toDoubleOrNull()?.let { "cache %.0fs".format(Locale.US, it) }.orEmpty()
        return "video $video · hwdec $hwdec · vo ${p("current-vo")}\naudio $audio · aid ${p("aid")} · sub $sub (sid ${p("sid")}) · $drops · $cache"
    }

    // With zero-copy decoding every decoded frame pins a MediaCodec output
    // buffer that only the vo gives back. Dropping the vo (surface gone)
    // while a file is loaded wedges the decoder in dequeueInputBuffer while
    // it holds the core lock, and every later mpv call — on the main thread —
    // hangs: the ANR on Back. So: stop playback first, then drop the vo, and
    // do it off the main thread so a slow teardown can't freeze the UI.
    fun shutdown(onDone: () -> Unit) {
        if (released) {
            main.post(onDone)
            return
        }
        released = true
        holder.removeCallback(this)
        var done = false
        val finish = Runnable {
            if (!done) {
                done = true
                onDone()
            }
        }
        Thread({
            runCatching { mpv.removeObserver(this) }
            runCatching { mpv.removeLogObserver(this) }
            runCatching { mpv.command(arrayOf("stop")) }
            runCatching { mpv.setPropertyString("vo", "null") }
            runCatching { mpv.detachSurface() }
            runCatching { mpv.destroy() }
            main.post(finish)
        }, "mpv-shutdown").start()
        // Never strand the viewer on a black screen if teardown hangs.
        main.postDelayed(finish, 3_000)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!released) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        // Unplanned surface loss: the surface dies when this returns, so this
        // must be synchronous — stop first (see shutdown) and leave the player.
        mpv.command(arrayOf("stop"))
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
        post(Event.SurfaceLost)
    }

    // mpv delivers on its own event thread; hand everything to the main thread.
    private fun post(event: Event) = main.post { if (!released) onEvent(event) }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        eventProperty(property, value.toDouble())
    }

    override fun eventProperty(property: String, value: Double) {
        val ms = (value * 1000.0).toLong().coerceAtLeast(0)
        when (property) {
            "time-pos" -> post(Event.Position(ms))
            "duration" -> post(Event.Duration(ms))
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> post(Event.Paused(value))
            "paused-for-cache" -> post(Event.Buffering(value))
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> main.post { onFileLoaded() }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                // keep-open=yes: a normal EOF pauses on the last frame instead,
                // so an END_FILE here is a stop or a failure to open.
                post(Event.Ended("end-file"))
            }
        }
    }

    private fun onFileLoaded() {
        if (released) return
        selectTrack("audio", "aid", pendingAudioIndex, offValue = "auto")
        selectTrack("sub", "sid", pendingSubtitleIndex, offValue = "no")
        val info = JSONObject()
        listOf("file-format", "video-codec", "audio-codec-name", "hwdec-current", "current-vo", "current-ao", "track-list/count").forEach { name ->
            info.put(name, runCatching { mpv.getPropertyString(name) }.getOrNull().orEmpty())
        }
        post(Event.Loaded(info))
    }

    // The server identifies streams by ffmpeg index; mpv exposes that as
    // track-list/N/ff-index.
    private fun selectTrack(type: String, property: String, ffIndex: Int?, offValue: String) {
        if (ffIndex == null) {
            mpv.setPropertyString(property, offValue)
            return
        }
        val count = mpv.getPropertyInt("track-list/count") ?: return
        for (i in 0 until count) {
            if (mpv.getPropertyString("track-list/$i/type") != type) continue
            if (mpv.getPropertyInt("track-list/$i/ff-index") != ffIndex) continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            mpv.setPropertyInt(property, id)
            return
        }
    }

    private fun subtitleFontsDir(context: Context): File {
        val dir = File(context.filesDir, "mpv-fonts")
        val target = File(dir, "Roboto-Regular.ttf")
        if (!target.exists()) {
            runCatching {
                dir.mkdirs()
                val source = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/DroidSans.ttf")
                    .map(::File).first { it.canRead() }
                source.copyTo(target, overwrite = true)
            }
        }
        return dir
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val line = "[$prefix] ${text.trimEnd()}"
        when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> {
                Log.e("mpv", line)
                post(Event.Log(line))
            }
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> Log.w("mpv", line)
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO -> Log.i("mpv", line)
            else -> Log.v("mpv", line)
        }
    }
}
