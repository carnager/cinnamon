package dev.popcorn.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jdtech.mpv.MPVLib
import org.json.JSONObject
import java.io.File
import java.util.Locale

// libmpv behind media3's Player interface, so PlayerScreen — OSD, menus,
// progress, Up Next, remote control, HLS fallback — drives either engine.
// mpv decodes what the box can't: DTS/TrueHD via ffmpeg, every subtitle
// format via libass, video still in hardware through MediaCodec.
//
// Tracks are addressed by ffmpeg stream index (what the server reports), not
// through media3 track selection: see selectTracks / selectExternalSubtitle.
private val creditsChapter = Regex("""^\s*(end\s*credits|credits|ending|ed|outro|abspann|nachspann)\b""", RegexOption.IGNORE_CASE)

@OptIn(UnstableApi::class)
class MpvPlayer(
    context: Context,
    authToken: String,
) : SimpleBasePlayer(Looper.getMainLooper()), SurfaceHolder.Callback, MPVLib.EventObserver, MPVLib.LogObserver {
    private val main = Handler(Looper.getMainLooper())

    // Diagnostics for the server's client log: (event, message, extra).
    var onDiagnostic: (String, String, JSONObject?) -> Unit = { _, _, _ -> }
    private val mpv: MPVLib = requireNotNull(MPVLib.create(context.applicationContext)) { "libmpv could not be created" }

    private var mediaItem: MediaItem? = null
    private var prepared = false
    private var loadIssued = false
    // END_FILE of a file we stopped can arrive after the next load was issued;
    // only events after this load's START_FILE belong to it.
    private var startSeen = false
    private var fileLoaded = false
    private var startMs = 0L
    private var pendingSeekMs: Long? = null
    private var playWhenReady = false
    private var seeking = false
    private var pausedForCache = false
    private var ended = false
    private var error: PlaybackException? = null
    private var released = false
    private var firstFrameReported = false
    private var newlyRenderedFirstFrame = false

    @Volatile private var positionMs = 0L
    @Volatile private var positionSampledAt = 0L
    @Volatile private var cacheEndMs = 0L
    private var durationMs = C.TIME_UNSET

    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceAttached = false

    private var wantedAudioIndex: Int? = null
    private var wantedSubtitleIndex: Int? = null
    private var wantedExternalSubtitle: String? = null

    init {
        mpv.addLogObserver(this)
        mpv.setOptionString("msg-level", "all=info")
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
        // EOF pauses on the last frame instead of unloading, so the end is an
        // observable state (eof-reached) rather than an END_FILE.
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("osd-level", "0")
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
        if (authToken.isNotBlank()) mpv.setOptionString("http-header-fields", "Authorization: Bearer $authToken")
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("seeking", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    // ── media3 state ──

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SET_VIDEO_SURFACE,
            )
            .build()
        val item = mediaItem
        val playlist = if (item == null) emptyList() else listOf(
            MediaItemData.Builder(item.mediaId.ifBlank { "mpv" })
                .setMediaItem(item)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                .setIsSeekable(true)
                .build(),
        )
        val playbackState = when {
            item == null || !prepared || error != null -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            !fileLoaded || seeking || pausedForCache -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val firstFrame = newlyRenderedFirstFrame
        newlyRenderedFirstFrame = false
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (item == null) C.INDEX_UNSET else 0)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(if (playbackState == Player.STATE_IDLE) error else null)
            .setIsLoading(prepared && !fileLoaded)
            .setContentPositionMs { currentPositionEstimate() }
            .setContentBufferedPositionMs { maxOf(cacheEndMs, currentPositionEstimate()) }
            .setNewlyRenderedFirstFrame(firstFrame)
            .build()
    }

    private fun currentPositionEstimate(): Long {
        pendingSeekMs?.let { return it }
        if (!fileLoaded) return startMs
        val base = positionMs
        if (!playWhenReady || seeking || pausedForCache || ended) return base
        val drift = (android.os.SystemClock.elapsedRealtime() - positionSampledAt).coerceIn(0L, 1_000L)
        return base + drift
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        stopFile()
        mediaItem = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        startMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0)
        positionMs = startMs
        error = null
        prepared = false
        wantedExternalSubtitle = null
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (mediaItem != null && !prepared) {
            prepared = true
            error = null
            // PlayerScreen calls setMediaItem → prepare → seekTo(start) in one go.
            // Loading on the next loop turn folds that seek into the load's
            // start position instead of loading at 0 and seeking afterwards.
            main.post { loadNow() }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        positionSampledAt = android.os.SystemClock.elapsedRealtime()
        if (!released) mpv.setPropertyBoolean("pause", !playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.coerceAtLeast(0)
        ended = false
        when {
            released -> Unit
            !loadIssued -> startMs = target
            !fileLoaded -> pendingSeekMs = target
            else -> {
                seeking = true
                this.positionMs = target
                positionSampledAt = android.os.SystemClock.elapsedRealtime()
                mpv.command(arrayOf("seek", seconds(target), "absolute"))
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopFile()
        prepared = false
        error = null
        return Futures.immediateVoidFuture()
    }

    // With zero-copy decoding every decoded frame pins a MediaCodec output
    // buffer that only the vo gives back. Dropping the vo (surface gone) with
    // a file loaded wedges the decoder in dequeueInputBuffer while it holds
    // mpv's core lock, and every later mpv call — on the main thread — hangs.
    // So the file is always stopped before the vo goes, and the slow part
    // (destroy) runs off the main thread.
    override fun handleRelease(): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        stopFile()
        released = true
        surfaceHolder?.removeCallback(this)
        surfaceHolder = null
        detachSurface()
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.removeLogObserver(this) }
        Thread({ runCatching { mpv.destroy() } }, "mpv-destroy").start()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val holder = when (videoOutput) {
            is SurfaceView -> videoOutput.holder
            is SurfaceHolder -> videoOutput
            else -> null
        }
        if (holder != null && holder !== surfaceHolder) {
            surfaceHolder?.removeCallback(this)
            detachSurface()
            surfaceHolder = holder
            holder.addCallback(this)
            if (holder.surface?.isValid == true) attachSurface(holder)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        surfaceHolder?.removeCallback(this)
        surfaceHolder = null
        if (surfaceAttached && fileLoaded) stopFileForSurfaceLoss()
        detachSurface()
        return Futures.immediateVoidFuture()
    }

    // ── tracks, addressed by ffmpeg stream index ──

    fun selectTracks(audioIndex: Int?, subtitleIndex: Int?) {
        wantedAudioIndex = audioIndex
        wantedSubtitleIndex = subtitleIndex
        if (fileLoaded && !released) applyTrackSelection()
    }

    // HLS plans carry no subtitle tracks; the server's VTT conversions arrive
    // as MediaItem subtitle configurations and are added as external files.
    fun selectExternalSubtitle(id: String?) {
        wantedExternalSubtitle = id
        if (fileLoaded && !released) applyExternalSubtitle()
    }

    private fun applyTrackSelection() {
        selectByFfIndex("audio", "aid", wantedAudioIndex, offValue = "auto")
        if (mediaItem?.localConfiguration?.subtitleConfigurations.isNullOrEmpty()) {
            selectByFfIndex("sub", "sid", wantedSubtitleIndex, offValue = "no")
        } else {
            applyExternalSubtitle()
        }
    }

    private fun selectByFfIndex(type: String, property: String, ffIndex: Int?, offValue: String) {
        if (ffIndex == null) {
            mpv.setPropertyString(property, offValue)
            return
        }
        val count = mpv.getPropertyInt("track-list/count") ?: return
        for (i in 0 until count) {
            if (mpv.getPropertyString("track-list/$i/type") != type) continue
            if (mpv.getPropertyBoolean("track-list/$i/external") == true) continue
            if (mpv.getPropertyInt("track-list/$i/ff-index") != ffIndex) continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            mpv.setPropertyInt(property, id)
            return
        }
        onDiagnostic("mpv-track-missing", "$type ff-index $ffIndex not found", null)
    }

    // Each external subtitle is a server-side conversion that demuxes the
    // whole file, and sub-add blocks until it is downloaded. So only the
    // track asked for is added, the first time it is asked for, off the main
    // thread ("select" makes it current once loaded).
    private val externalAdded = mutableSetOf<String>()

    private fun applyExternalSubtitle() {
        val wanted = wantedExternalSubtitle
        if (wanted == null) {
            mpv.setPropertyString("sid", "no")
            return
        }
        val count = mpv.getPropertyInt("track-list/count") ?: return
        for (i in 0 until count) {
            if (mpv.getPropertyString("track-list/$i/type") != "sub") continue
            if (mpv.getPropertyString("track-list/$i/title") != wanted) continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            mpv.setPropertyInt("sid", id)
            return
        }
        if (!externalAdded.add(wanted)) return
        val config = mediaItem?.localConfiguration?.subtitleConfigurations?.firstOrNull { it.id == wanted } ?: return
        Thread({
            if (!released) runCatching { mpv.command(arrayOf("sub-add", config.uri.toString(), "select", wanted, config.language.orEmpty())) }
        }, "mpv-sub-add").start()
    }

    // ── adjustments surfaced in the player's settings menu ──

    var audioDelayMs: Int = 0
        set(value) {
            field = value
            if (!released) mpv.setPropertyDouble("audio-delay", value / 1000.0)
        }

    var subtitleDelayMs: Int = 0
        set(value) {
            field = value
            if (!released) mpv.setPropertyDouble("sub-delay", value / 1000.0)
        }

    var subtitleScalePercent: Int = 100
        set(value) {
            field = value
            if (!released) mpv.setPropertyDouble("sub-scale", value / 100.0)
        }

    // What is actually playing, for the "Playback info" panel.
    fun playbackInfo(): List<Pair<String, String>> {
        if (released || !fileLoaded) return emptyList()
        fun p(name: String) = runCatching { mpv.getPropertyString(name) }.getOrNull().orEmpty()
        val w = p("video-params/w")
        val h = p("video-params/h")
        return listOf(
            "Video" to listOf(p("video-codec").substringBefore(" /").substringBefore(" ("), if (w.isNotBlank()) "${w}x$h" else "", p("video-params/pixelformat")).filter { it.isNotBlank() }.joinToString(" · "),
            "Decoder" to (p("hwdec-current").takeIf { it.isNotBlank() && it != "no" }?.let { "hardware ($it)" } ?: "software"),
            "Audio" to listOf(p("audio-codec-name"), p("audio-params/channel-count").takeIf { it.isNotBlank() }?.let { "${it}ch" }.orEmpty(), p("audio-params/samplerate").takeIf { it.isNotBlank() }?.let { "$it Hz" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
            "Audio out" to p("current-ao"),
            "Subtitles" to p("current-tracks/sub/codec").ifBlank { "off" },
            "Dropped frames" to "${p("frame-drop-count").ifBlank { "0" }} output · ${p("decoder-frame-drop-count").ifBlank { "0" }} decoder",
            "Buffered" to (p("demuxer-cache-duration").toDoubleOrNull()?.let { "%.0f s".format(Locale.US, it) } ?: "-"),
            "Container" to p("file-format"),
        ).filter { it.second.isNotBlank() }
    }

    // Chapter start times, for marks on the progress bar.
    fun chapterTimesMs(): List<Long> {
        if (released || !fileLoaded) return emptyList()
        val count = mpv.getPropertyInt("chapter-list/count") ?: return emptyList()
        return (0 until count).mapNotNull { i -> mpv.getPropertyDouble("chapter-list/$i/time")?.let { (it * 1000).toLong() } }
    }

    // Start of the end credits, from the file's chapter titles (anime and
    // most Blu-ray rips mark them), or null.
    fun creditsStartMs(): Long? {
        if (released || !fileLoaded) return null
        val count = mpv.getPropertyInt("chapter-list/count") ?: return null
        for (i in 0 until count) {
            val title = mpv.getPropertyString("chapter-list/$i/title").orEmpty()
            if (!creditsChapter.containsMatchIn(title)) continue
            val time = mpv.getPropertyDouble("chapter-list/$i/time") ?: continue
            return (time * 1000).toLong()
        }
        return null
    }

    // ── mpv plumbing ──

    private fun loadNow() {
        val item = mediaItem ?: return
        if (released || !prepared || loadIssued) return
        val url = item.localConfiguration?.uri?.toString() ?: return
        loadIssued = true
        startSeen = false
        fileLoaded = false
        ended = false
        firstFrameReported = false
        positionMs = startMs
        mpv.setPropertyString("start", if (startMs > 0) seconds(startMs) else "none")
        mpv.setPropertyBoolean("pause", !playWhenReady)
        mpv.command(arrayOf("loadfile", url, "replace"))
        invalidateState()
    }

    private fun stopFile() {
        // Keep reporting the last position after a stop, as ExoPlayer does:
        // PlayerScreen saves progress once more after stopping, and falling
        // back to the load's start would rewind the saved resume point.
        if (fileLoaded) startMs = currentPositionEstimate()
        if (!released && loadIssued) runCatching { mpv.command(arrayOf("stop")) }
        loadIssued = false
        fileLoaded = false
        pendingSeekMs = null
        seeking = false
        pausedForCache = false
        ended = false
        durationMs = C.TIME_UNSET
        cacheEndMs = 0L
    }

    // The surface dies as soon as surfaceDestroyed returns, so this must stop
    // synchronously. Playback ends up IDLE, as ExoPlayer is after stop().
    private fun stopFileForSurfaceLoss() {
        onDiagnostic("mpv-surface-lost", "", null)
        stopFile()
        prepared = false
        invalidateState()
    }

    private fun attachSurface(holder: SurfaceHolder) {
        if (released || surfaceAttached) return
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
        surfaceAttached = true
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        surfaceAttached = false
        runCatching { mpv.setPropertyString("vo", "null") }
        runCatching { mpv.setOptionString("force-window", "no") }
        runCatching { mpv.detachSurface() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = attachSurface(holder)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!released) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        if (fileLoaded || loadIssued) stopFileForSurfaceLoss()
        detachSurface()
    }

    private fun onFileLoaded() {
        if (released || !loadIssued) return
        fileLoaded = true
        pendingSeekMs?.let { target ->
            pendingSeekMs = null
            seeking = true
            positionMs = target
            mpv.command(arrayOf("seek", seconds(target), "absolute"))
        }
        externalAdded.clear()
        mpv.setPropertyDouble("audio-delay", audioDelayMs / 1000.0)
        mpv.setPropertyDouble("sub-scale", subtitleScalePercent / 100.0)
        mpv.setPropertyDouble("sub-delay", subtitleDelayMs / 1000.0)
        applyTrackSelection()
        val info = JSONObject()
        listOf("file-format", "video-codec", "audio-codec-name", "hwdec-current", "current-vo", "current-ao", "track-list/count").forEach { name ->
            info.put(name, runCatching { mpv.getPropertyString(name) }.getOrNull().orEmpty())
        }
        onDiagnostic("mpv-file-loaded", "", info)
        invalidateState()
    }

    private fun onEndFile() {
        if (released || !loadIssued || !startSeen) return
        // keep-open=yes turns a normal EOF into a pause on the last frame, and
        // stop() clears loadIssued before mpv reports back, so an END_FILE here
        // is a failure: the open failed, or the stream broke mid-play.
        val wasLoaded = fileLoaded
        stopFile()
        error = PlaybackException(
            if (wasLoaded) "mpv: playback stopped unexpectedly" else "mpv: could not open the stream",
            null,
            if (wasLoaded) PlaybackException.ERROR_CODE_IO_UNSPECIFIED else PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        onDiagnostic("mpv-end-file", if (wasLoaded) "after load" else "before load", null)
        invalidateState()
    }

    // mpv calls back on its event thread; everything is handed to the main
    // thread before touching player state.
    private fun onMain(block: () -> Unit) = main.post { if (!released) block() }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: String) = Unit
    override fun eventProperty(property: String, value: Long) = eventProperty(property, value.toDouble())

    override fun eventProperty(property: String, value: Double) {
        val ms = (value * 1000.0).toLong().coerceAtLeast(0)
        when (property) {
            "time-pos" -> {
                positionMs = ms
                positionSampledAt = android.os.SystemClock.elapsedRealtime()
            }
            "demuxer-cache-time" -> cacheEndMs = ms
            "duration" -> onMain {
                if (durationMs != ms) {
                    durationMs = ms
                    invalidateState()
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        onMain {
            when (property) {
                "paused-for-cache" -> pausedForCache = value
                "seeking" -> seeking = value
                "eof-reached" -> ended = value && fileLoaded
                else -> return@onMain
            }
            invalidateState()
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> onMain { if (loadIssued) startSeen = true }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onMain { if (startSeen) onFileLoaded() }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> onMain { onEndFile() }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> onMain {
                seeking = false
                if (!firstFrameReported && fileLoaded) {
                    firstFrameReported = true
                    newlyRenderedFirstFrame = true
                }
                invalidateState()
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val line = "[$prefix] ${text.trimEnd()}"
        when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> {
                Log.e("mpv", line)
                main.post { onDiagnostic("mpv-log", line, null) }
            }
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> Log.w("mpv", line)
            else -> Log.i("mpv", line)
        }
    }

    private fun seconds(ms: Long) = "%.3f".format(Locale.US, ms / 1000.0)

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
}
