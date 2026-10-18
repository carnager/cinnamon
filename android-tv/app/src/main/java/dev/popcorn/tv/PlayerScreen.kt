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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.media3.common.TrackSelectionOverride
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
    resolveTrackPrefs: Boolean,
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
    var internalSeeks by remember(item.id) { mutableStateOf(0) }
    var playbackSampleAtMs by remember(item.id) { mutableStateOf(SystemClock.elapsedRealtime()) }
    val pressedSeekKeys = remember(item.id) { mutableMapOf<Int, Long>() }
    val originalStreams = remember { mutableStateListOf<StreamInfo>() }
    var streamsReady by remember(item.id) { mutableStateOf(false) }
    // Shown as a card instead of leaving a black screen: an unplayable plan,
    // or an engine error once the server fallback is used up.
    var playbackError by remember(item.id) { mutableStateOf<String?>(null) }
    var stoppedByLifecycle by remember(item.id) { mutableStateOf(false) }
    var mediaStarted by remember(item.id) { mutableStateOf(false) }
    val errorFocus = remember { FocusRequester() }
    // Fixed per player instance: switching engines mid-playback isn't supported.
    val useMpv = remember { PlaybackPrefs.useMpv }
    val playbackProfile = remember(context) { buildPlaybackProfile(context, mpv = useMpv) }

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
                if (resolveTrackPrefs) {
                    selectedAudioIndex = preferredAudioIndex(streams.filter { it.type == "audio" })
                    selectedSubtitleIndex = preferredSubtitleIndex(streams.filter { it.type == "subtitle" })
                } else if (selectedAudioIndex == null) {
                    selectedAudioIndex = preferredAudioIndex(streams.filter { it.type == "audio" && it.default }.ifEmpty { streams.filter { it.type == "audio" } })
                }
            }
        streamsReady = true
    }

    val exoPlayer: Player = remember {
        if (useMpv) {
            return@remember MpvPlayer(context, session?.token.orEmpty()).apply {
                audioDelayMs = PlaybackPrefs.audioDelayMs
                subtitleScalePercent = PlaybackPrefs.subtitleScalePercent
            }
        }
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (session != null && session.token.isNotBlank()) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${session.token}"))
            }
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
    }
    val mpvPlayer = exoPlayer as? MpvPlayer

    val playerView = remember {
        (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
            player = exoPlayer
            useController = true
            controllerAutoShow = true
            controllerHideOnTouch = false
            controllerShowTimeoutMs = 5000
            keepScreenOn = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // mpv reports no video tracks, so PlayerView keeps its shutter
            // closed; make it see-through rather than a black sheet over the video.
            if (useMpv) setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
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

    fun playerStateLabel(): String {
        val engine = if (useMpv) "mpv" else "exo"
        return when {
            exoPlayer.isPlaying -> "$engine-playing"
            exoPlayer.playbackState == Player.STATE_READY -> "$engine-ready"
            exoPlayer.playbackState == Player.STATE_BUFFERING -> "$engine-buffering"
            exoPlayer.playbackState == Player.STATE_ENDED -> "$engine-ended"
            else -> "$engine-idle"
        }
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
                    .put("usesMpv", useMpv)
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

    mpvPlayer?.onDiagnostic = { event, message, extra -> logClient(event, message = message.take(230), extra = extra) }

    // Seek steps follow the TV convention: a tap moves 10s from the target
    // already shown, and holding speeds up. It used to snap to 30s boundaries
    // (at 12:17, LEFT went back 17s), add 30s per key repeat (~10 minutes per
    // second held) and run past the end of the film.
    var seekRepeat by remember(item.id) { mutableStateOf(0) }

    fun seekStepMs(repeat: Int): Long = when {
        repeat < 5 -> 10_000L
        repeat < 15 -> 30_000L
        repeat < 30 -> 60_000L
        else -> 120_000L
    }

    fun relativeSeekTarget(currentMs: Long, forward: Boolean, repeat: Int = 0): Long {
        val step = seekStepMs(repeat)
        val target = if (forward) currentMs + step else currentMs - step
        val duration = logicalDurationMs()
        return if (duration > 0) target.coerceIn(0L, (duration - 5_000L).coerceAtLeast(0L)) else target.coerceAtLeast(0L)
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
        // Key repeat arrives ~20/s; about 8 steps a second keeps a hold
        // controllable.
        if (event.repeatCount > 0 && lastDown != null && event.eventTime - lastDown < 120L) return false
        pressedSeekKeys[event.keyCode] = event.eventTime
        seekRepeat = event.repeatCount
        return true
    }

    fun applyDirectTrackSelections() {
        if (planUsesHls || originalStreams.isEmpty()) return
        if (mpvPlayer != null) {
            // mpv addresses tracks by ffmpeg index directly; a selection made
            // before the file loads is applied once it has.
            mpvPlayer.selectTracks(selectedAudioIndex, selectedSubtitleIndex)
            logClient("apply-direct-tracks", message = "mpv audio=${selectedAudioIndex ?: -1} sub=${selectedSubtitleIndex ?: -1}")
            return
        }
        val result = applyOriginalTrackSelection(exoPlayer as ExoPlayer, originalStreams, selectedAudioIndex, selectedSubtitleIndex)
        logClient("apply-direct-tracks", message = result.take(230))
    }

    // Sideloaded subtitles are told apart by this id rather than by language:
    // a file can carry two tracks of the same language, one of them forced.
    fun sideloadedSubtitleId(index: Int): String = "popcorn-sub-$index"

    // Returns true once the wanted track exists and has been selected, so the
    // caller can keep retrying while the player is still preparing.
    fun applyHlsSubtitleSelection(subtitleIndex: Int?): Boolean {
        if (mpvPlayer != null) {
            mpvPlayer.selectExternalSubtitle(subtitleIndex?.let(::sideloadedSubtitleId))
            return true
        }
        val params = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleIndex == null)
        if (subtitleIndex == null) {
            exoPlayer.trackSelectionParameters = params.build()
            return true
        }
        val wanted = sideloadedSubtitleId(subtitleIndex)
        for (group in exoPlayer.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (track in 0 until group.length) {
                if (group.getTrackFormat(track).id != wanted) continue
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                exoPlayer.trackSelectionParameters = params.build()
                return true
            }
        }
        exoPlayer.trackSelectionParameters = params.build()
        return false
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

    // Seeks the viewer never asked for — trimming to a plan start the server
    // snapped back to a keyframe, or flushing the buffer so a text track starts
    // producing cues — must not read as jumping around. The server discards any
    // progress report with less than two minutes of settled playback behind it,
    // so a transport seek counted as a viewer seek throws away the next two
    // minutes of progress, including the report that would mark a film watched.
    fun seekWithoutLosingEvidence(positionMs: Long) {
        internalSeeks++
        exoPlayer.seekTo(positionMs)
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
        if (sideloadSubtitle) {
            // Sideload every text track, not only the chosen one. ExoPlayer
            // fetches a sideloaded subtitle when its track is selected, so the
            // rest cost nothing until picked — and switching becomes a track
            // selection rather than a new plan, which used to tear down the
            // transcode session and rebuffer for several seconds.
            val configs = originalStreams
                .filter { it.type == "subtitle" && isTextSubtitle(it.index) }
                .map { stream ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl(activeSession, stream.index, subtitleStartMs)))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setId(sideloadedSubtitleId(stream.index))
                        .setLanguage(stream.language.ifBlank { null })
                        .setLabel(stream.label())
                        .setSelectionFlags(if (stream.index == subtitleIndex) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }
            if (configs.isNotEmpty()) builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    fun applyPlaybackPlan(activeSession: Session, plan: PlaybackPlan, wasPlaying: Boolean, startMs: Long, showController: Boolean = true) {
        if (!plan.playable || plan.url.isBlank()) return
        playbackError = null
        mediaStarted = true
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
        } else if (plan.usesHls) {
            // A copied-video plan can only begin on a keyframe, so the server may
            // have snapped the start earlier than asked. Seek off the difference
            // locally — those seconds are in the first segment either way.
            val trimMs = startMs - playbackBaseMs
            if (trimMs > 500) seekWithoutLosingEvidence(trimMs)
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
                    } else {
                        playbackError = plan.reasons.joinToString("; ").ifBlank { "The server has no way to play this file." }
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
                mediaStarted = true
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
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> relativeSeekTarget(current, forward = false, repeat = seekRepeat)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> relativeSeekTarget(current, forward = true, repeat = seekRepeat)
            else -> null
        }
    }

    fun commitPendingSeek(showController: Boolean) {
        val target = pendingSeekTargetMs ?: return
        // The viewer may have brought the OSD up while the seek was pending;
        // a commit scheduled from hidden controls must not hide it again.
        seekToLogical(target, showController = showController || playerView.isControllerFullyVisible)
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
            if (index != null && mpvPlayer == null) {
                seekWithoutLosingEvidence(exoPlayer.currentPosition)
            }
            return
        }
        // Every text track is already sideloaded, so this is a track selection:
        // no new plan, no transcode restart, nothing to rebuffer.
        logClient("switch-subtitle-hls", extra = JSONObject().put("index", index ?: -1))
        applyHlsSubtitleSelection(index)
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
        // The plan carries the audio/subtitle choice (HLS bakes it in), so when
        // the player resolves language preferences itself it waits for them.
        if (resolveTrackPrefs) {
            withTimeoutOrNull(3_000) { snapshotFlow { streamsReady }.first { it } }
        }
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

    // Surface the Up Next countdown. With a credits chapter (mpv reads the
    // file's chapters) the card appears when the credits start and counts
    // down 15s of actual playback, then advances; otherwise it covers the
    // last 25s and the end of the file advances. Paused, the count holds.
    LaunchedEffect(item.id, nextEpisode, exoPlayer) {
        if (nextEpisode == null) {
            upNextSecondsLeft = null
            return@LaunchedEffect
        }
        var creditsStartMs: Long? = null
        var creditsPlayedMs = 0L
        while (true) {
            delay(500)
            if (upNextDismissed || advancingToNext) {
                upNextSecondsLeft = null
                continue
            }
            val duration = logicalDurationMs()
            val position = logicalPositionMs()
            val remaining = duration - position
            val ready = duration > 0 && exoPlayer.playbackState == Player.STATE_READY
            // Chapter times are file times: only meaningful when direct-playing.
            if (creditsStartMs == null && ready && mpvPlayer != null && !planUsesHls) {
                creditsStartMs = mpvPlayer.creditsStartMs()?.takeIf { it > duration * 0.7 && it < duration - 10_000 } ?: -1L
            }
            val credits = creditsStartMs?.takeIf { it > 0 }
            upNextSecondsLeft = when {
                !ready -> null
                credits != null && position >= credits -> {
                    if (exoPlayer.isPlaying) creditsPlayedMs += 500
                    val left = ((UpNextCreditsMs - creditsPlayedMs + 999) / 1000).toInt()
                    if (left <= 0) {
                        playNextEpisode()
                        null
                    } else {
                        left
                    }
                }
                credits == null && remaining in 1..UpNextLeadMs -> ((remaining + 999) / 1000).toInt()
                else -> {
                    if (credits != null) creditsPlayedMs = 0L
                    null
                }
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
                // Back closes the Up Next card rather than the player.
                upNextCardVisible() && isBackKey(event.keyCode) -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) upNextDismissed = true
                    true
                }
                playbackError != null && isBackKey(event.keyCode) -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) exitPlayer()
                    true
                }
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
                upNextCardVisible() && isBackKey(event.keyCode) -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) upNextDismissed = true
                    true
                }
                // The error card's buttons are Compose; let D-pad and OK reach them.
                playbackError != null -> false
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
                // Media FF/REW seek with the OSD up too (media3 would call its own
                // seekForward, which the mpv engine doesn't offer and HLS gets wrong).
                (event.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD || event.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND) && playerView.isControllerFullyVisible -> {
                    if (event.action == AndroidKeyEvent.ACTION_UP) {
                        pressedSeekKeys.remove(event.keyCode)
                        schedulePendingSeekCommit(showController = true)
                    } else if (shouldHandleSeekKey(event)) {
                        pendingSeekJob?.cancel()
                        pendingSeekTargetMs = pendingSeekTarget(pendingSeekTargetMs, event.keyCode)
                        scheduleControllerAutoHide()
                    }
                    true
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
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            // Paused, PlayerView shows its controls with nothing
                            // focused, and the next key was spent repairing focus.
                            playerView.showController()
                            focusPlayerControl()
                        } else {
                            exoPlayer.play()
                        }
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
            if (event == Lifecycle.Event.ON_STOP && !exitingPlayer) {
                reportProgress("stopped")
                exoPlayer.stop()
                stoppedByLifecycle = true
            }
            // Back from Home, an input switch or the screensaver: the engine was
            // stopped, so load the plan again, paused where it left off, instead
            // of leaving a dead black player.
            if (event == Lifecycle.Event.ON_START && stoppedByLifecycle && !exitingPlayer) {
                stoppedByLifecycle = false
                requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), forceModeForBandwidth(selectedBandwidth), wasPlaying = false)
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
                    } else {
                        // Nothing follows: leave instead of freezing on the last
                        // frame with the OSD up.
                        scope.launch {
                            delay(1_500)
                            if (exoPlayer.playbackState == Player.STATE_ENDED) exitPlayer()
                        }
                    }
                }
                // mpv applies its selection once per file load; re-applying it
                // after every seek's rebuffer would only churn aid/sid.
                if (playbackState == Player.STATE_READY && mpvPlayer == null) {
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
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (internalSeeks > 0) internalSeeks-- else resetProgressEvidence()
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
                val failedPlan = activePlan
                if (failedPlan == null || fallbackRetried) {
                    playbackError = error.message ?: error.errorCodeName
                    return
                }
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
                        } else {
                            playbackError = error.message ?: error.errorCodeName
                        }
                    }.onFailure {
                        playbackError = error.message ?: error.errorCodeName
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

        titleView?.text = if (item.kind == "episode") {
            val code = if (item.seasonNumber > 0 && item.episodeNumber > 0) "S%02dE%02d".format(item.seasonNumber, item.episodeNumber) else ""
            listOf(item.showTitle, code, item.episodeTitle.ifBlank { item.title }).filter { it.isNotBlank() }.joinToString("  \u00b7  ")
        } else {
            item.title
        }
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
            seekToLogical(relativeSeekTarget(logicalPositionMs(), forward = false))
            scheduleControllerAutoHide()
        }
        playerView.findViewById<View>(R.id.popcorn_ffwd)?.setOnKeyListener(controllerKeyListener)
        playerView.findViewById<View>(R.id.popcorn_ffwd)?.setOnClickListener {
            seekToLogical(relativeSeekTarget(logicalPositionMs(), forward = true))
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
        val settingsButton = playerView.findViewById<View>(R.id.popcorn_settings)
        settingsButton?.visibility = if (mpvPlayer != null) View.VISIBLE else View.GONE
        settingsButton?.setOnKeyListener(controllerKeyListener)
        settingsButton?.setOnClickListener {
            val engine = mpvPlayer ?: return@setOnClickListener
            scheduleControllerAutoHide()
            showNativeMpvSettingsMenu(
                playerView = playerView,
                player = engine,
                returnFocus = settingsButton,
                onAudioDelay = {
                    engine.audioDelayMs = it
                    PlaybackPrefs.setAudioDelay(context, it)
                },
                onSubtitleScale = {
                    engine.subtitleScalePercent = it
                    PlaybackPrefs.setSubtitleScale(context, it)
                },
                onClosed = ::scheduleControllerAutoHide,
            )
        }

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
            playerView.findViewById<View>(R.id.popcorn_settings)?.setOnClickListener(null)
            playerView.findViewById<View>(R.id.popcorn_settings)?.setOnKeyListener(null)
        }
    }

    LaunchedEffect(selectedSubtitleIndex, playerView) {
        playerView.findViewById<ImageButton>(R.id.popcorn_subtitles)?.setImageResource(
            if (selectedSubtitleIndex == null) androidx.media3.ui.R.drawable.exo_ic_subtitle_off else androidx.media3.ui.R.drawable.exo_ic_subtitle_on,
        )
    }

    // Say what the server is actually doing: "Direct" used to show for any
    // uncapped plan, even when the file was being remuxed or transcoded.
    LaunchedEffect(selectedBandwidth, activePlan?.mode, playerView) {
        playerView.findViewById<TextView>(R.id.popcorn_bandwidth)?.text = when {
            selectedBandwidth != null -> bandwidthLabel(selectedBandwidth)
            activePlan?.mode == "remux" -> "Remux"
            activePlan?.mode?.endsWith("transcode") == true -> "Transcode"
            else -> bandwidthLabel(null)
        }
    }

    LaunchedEffect(planUsesHls, playbackBaseMs, playerView, item.durationMs) {
        val nativeTimeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_progress)
        val playPauseButton = playerView.findViewById<ImageButton>(R.id.popcorn_play_pause)
        val timeBar = playerView.findViewById<DefaultTimeBar>(R.id.popcorn_hls_progress)
        val nativePositionView = playerView.findViewById<TextView>(R.id.popcorn_position)
        val nativeDurationView = playerView.findViewById<TextView>(R.id.popcorn_duration)
        val positionView = playerView.findViewById<TextView>(R.id.popcorn_hls_position)
        val durationView = playerView.findViewById<TextView>(R.id.popcorn_hls_duration)
        val endsView = playerView.findViewById<TextView>(R.id.popcorn_ends)
        val clockFormat = android.text.format.DateFormat.getTimeFormat(context)
        nativeTimeBar?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        timeBar?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        nativePositionView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        nativeDurationView?.visibility = if (planUsesHls) View.GONE else View.VISIBLE
        positionView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        durationView?.visibility = if (planUsesHls) View.VISIBLE else View.GONE
        var shownPlaying: Boolean? = null
        while (true) {
            // Nothing to update while the OSD is hidden.
            if (!playerView.isControllerFullyVisible) {
                delay(250)
                continue
            }
            val duration = logicalDurationMs()
            val position = pendingSeekTargetMs ?: logicalPositionMs()
            if (duration > 0) {
                val remaining = (duration - position).coerceAtLeast(0)
                endsView?.text = "-${fmtClock(remaining)}  \u00b7  ends ${clockFormat.format(java.util.Date(System.currentTimeMillis() + remaining))}"
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
            if (shownPlaying != exoPlayer.isPlaying) {
                shownPlaying = exoPlayer.isPlaying
                playPauseButton?.setImageResource(
                    if (exoPlayer.isPlaying) {
                        androidx.media3.ui.R.drawable.exo_icon_pause
                    } else {
                        androidx.media3.ui.R.drawable.exo_icon_play
                    },
                )
            }
            delay(250)
        }
    }

    LaunchedEffect(originalStreams.size, selectedAudioIndex, selectedSubtitleIndex, planUsesHls, exoPlayer) {
        if (originalStreams.isEmpty()) return@LaunchedEffect
        repeat(20) {
            // A selection made while the player is still preparing has no track
            // to bind to yet, so keep trying until the groups show up.
            if (planUsesHls) {
                if (applyHlsSubtitleSelection(selectedSubtitleIndex)) return@LaunchedEffect
            } else {
                applyDirectTrackSelections()
                if (mpvPlayer != null || exoPlayer.currentTracks.groups.isNotEmpty()) return@LaunchedEffect
            }
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

    // The phone remote shows this state: send it when it changes, plus a 3s
    // heartbeat for its position readout, instead of a PUT every second.
    LaunchedEffect(deviceId, item.id, exoPlayer) {
        var lastState = ""
        var lastSentAt = 0L
        while (true) {
            delay(1_000)
            val state = when {
                exoPlayer.playbackState == Player.STATE_ENDED -> "ended"
                exoPlayer.isPlaying -> "playing"
                exoPlayer.playbackState == Player.STATE_BUFFERING -> "buffering"
                else -> "paused"
            }
            val now = SystemClock.elapsedRealtime()
            if (state != lastState || now - lastSentAt >= 3_000) {
                lastState = state
                lastSentAt = now
                reportRemoteState(state)
            }
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
    val errorVisible = playbackError != null
    LaunchedEffect(upNextVisible, errorVisible) {
        if (errorVisible) {
            playerView.hideController()
            playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            playerView.isFocusable = false
            playerView.clearFocus()
            delay(120)
            runCatching { errorFocus.requestFocus() }
        } else if (upNextVisible) {
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
        if (!mediaStarted && playbackError == null) {
            // No media is set until the plan round trip returns, so PlayerView
            // shows no buffering spinner of its own yet.
            CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
        }
        playbackError?.let { message ->
            PlaybackErrorCard(
                message = message,
                focusRequester = errorFocus,
                onRetry = {
                    playbackError = null
                    fallbackRetried = false
                    requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), forceModeForBandwidth(selectedBandwidth), wasPlaying = true)
                },
                onTranscode = {
                    playbackError = null
                    fallbackRetried = false
                    requestPlaybackPlan(selectedBandwidth, logicalPositionMs(), "transcode", wasPlaying = true)
                },
                onBack = { exitPlayer() },
            )
        }
        pendingSeekTargetMs?.let { target ->
            val offset = target - logicalPositionMs()
            val sign = if (offset >= 0) "+" else "\u2212"
            Text(
                text = "${fmtClock(target)}   $sign${fmtClock(kotlin.math.abs(offset))}",
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

@Composable
private fun PlaybackErrorCard(
    message: String,
    focusRequester: FocusRequester,
    onRetry: () -> Unit,
    onTranscode: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xB0000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF00B0E14))
                .border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(14.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Couldn't play this", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFFB8C0CA), fontSize = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton(label = "Retry", primary = true, modifier = Modifier.focusRequester(focusRequester), onClick = onRetry)
                FocusButton(label = "Try transcoding", primary = false, onClick = onTranscode)
                FocusButton(label = "Back", primary = false, onClick = onBack)
            }
        }
    }
}

// Window before the end of an episode during which the Up Next card appears.
private const val UpNextLeadMs = 25_000L

// Credits played before Up Next advances, when the file marks its credits.
private const val UpNextCreditsMs = 15_000L

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
