package dev.popcorn.tv

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import dev.jdtech.mpv.MPVLib

class MpvVideoHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TextureView.SurfaceTextureListener, MPVLib.EventObserver {
    private val textureView = TextureView(context)
    private val mpv = requireNotNull(MPVLib.create(context.applicationContext)) { "libmpv could not be created" }
    private var surface: Surface? = null
    private var mpvInitialized = false
    private var destroyed = false
    private var pendingLoad: MpvLoad? = null
    private var pendingAudioSelection = MpvTrackSelection(null, null)
    private var pendingSubtitleSelection = MpvTrackSelection(null, null)
    private var pendingStartPositionMs: Long = 0L
    private var pendingPlayWhenReady: Boolean = true

    var positionMs: Long = 0L
        private set
    var durationMs: Long = 0L
        private set
    var isPlaying: Boolean = false
        private set
    var isLoaded: Boolean = false
        private set
    var isEnded: Boolean = false
        private set

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        textureView.surfaceTextureListener = this
    }

    fun load(request: MpvLoad) {
        pendingLoad = request
        isLoaded = false
        isEnded = false
        positionMs = request.startPositionMs.coerceAtLeast(0)
        pendingAudioSelection = MpvTrackSelection(request.audioIndex, request.audioOrdinal)
        pendingSubtitleSelection = MpvTrackSelection(request.subtitleIndex, request.subtitleOrdinal)
        pendingStartPositionMs = request.startPositionMs.coerceAtLeast(0)
        pendingPlayWhenReady = request.playWhenReady
        if (surface != null) loadNow(request)
    }

    private fun loadNow(request: MpvLoad) {
        if (destroyed) return
        ensureInitialized()
        if (!mpvInitialized) return
        if (isLoaded) stop()
        if (request.authorizationHeader.isNotBlank()) {
            mpv.setOptionString("http-header-fields", request.authorizationHeader)
        }
        mpv.command(arrayOf("loadfile", request.url, "replace"))
        mpv.setPropertyBoolean("pause", true)
        isPlaying = false
    }

    fun play() {
        if (destroyed || !mpvInitialized) return
        mpv.setPropertyBoolean("pause", false)
        isPlaying = true
    }

    fun pause() {
        if (destroyed || !mpvInitialized) return
        mpv.setPropertyBoolean("pause", true)
        isPlaying = false
    }

    fun seekTo(positionMs: Long) {
        if (destroyed || !mpvInitialized) return
        val seconds = "%.3f".format(java.util.Locale.US, positionMs.coerceAtLeast(0) / 1000.0)
        mpv.command(arrayOf("seek", seconds, "absolute", "exact"))
        this.positionMs = positionMs.coerceAtLeast(0)
        isEnded = false
    }

    fun setAudioTrack(index: Int?, ordinal: Int?) {
        pendingAudioSelection = MpvTrackSelection(index, ordinal)
        if (destroyed || !mpvInitialized || !isLoaded) return
        applyAudioTrackSelection()
    }

    fun setSubtitleTrack(index: Int?, ordinal: Int?) {
        pendingSubtitleSelection = MpvTrackSelection(index, ordinal)
        if (destroyed || !mpvInitialized || !isLoaded) return
        applySubtitleTrackSelection()
    }

    private fun applyAudioTrackSelection() {
        val selection = pendingAudioSelection
        if (selection.streamIndex == null && selection.ordinal == null) {
            mpv.setPropertyString("aid", "auto")
        } else {
            val id = mpvTrackId("audio", selection)
            if (id != null) mpv.setPropertyString("aid", id.toString()) else mpv.setPropertyString("aid", "auto")
        }
    }

    private fun applySubtitleTrackSelection() {
        val selection = pendingSubtitleSelection
        if (selection.streamIndex == null && selection.ordinal == null) {
            mpv.setPropertyString("sid", "no")
            mpv.setPropertyBoolean("sub-visibility", false)
        } else {
            val id = mpvTrackId("sub", selection)
            if (id != null) {
                mpv.setPropertyString("sid", id.toString())
                mpv.setPropertyBoolean("sub-visibility", true)
            } else {
                mpv.setPropertyString("sid", "no")
                mpv.setPropertyBoolean("sub-visibility", false)
            }
        }
    }

    private fun mpvTrackId(kind: String, selection: MpvTrackSelection): Int? {
        val count = mpv.getPropertyInt("track-list/count") ?: return null
        val sameKindIds = mutableListOf<Int>()
        var exactId: Int? = null
        for (i in 0 until count) {
            val type = mpv.getPropertyString("track-list/$i/type") ?: continue
            if (type != kind) continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            sameKindIds.add(id)
            val ffIndex = mpv.getPropertyInt("track-list/$i/ff-index")
            val srcId = mpv.getPropertyInt("track-list/$i/src-id")
            if (selection.streamIndex != null && (ffIndex == selection.streamIndex || srcId == selection.streamIndex)) exactId = id
        }
        return selection.ordinal?.let { sameKindIds.getOrNull(it) } ?: exactId
    }

    fun stop() {
        if (destroyed || !mpvInitialized) return
        runCatching { mpv.command(arrayOf("stop")) }
        isPlaying = false
        isLoaded = false
    }

    fun release() {
        if (destroyed) return
        stop()
        destroyed = true
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (destroyed) return
        surface?.release()
        surface = Surface(surfaceTexture)
        if (mpvInitialized) {
            surface?.let { runCatching { mpv.attachSurface(it) } }
        } else {
            ensureInitialized()
        }
        pendingLoad?.let { loadNow(it) }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        if (!destroyed) {
            stop()
            runCatching { mpv.detachSurface() }
        }
        surface?.release()
        surface = null
        return true
    }

    private fun ensureInitialized() {
        val currentSurface = surface ?: return
        if (destroyed || mpvInitialized) return
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("hwdec", "mediacodec-copy")
        mpv.setOptionString("ao", "audiotrack")
        mpv.setOptionString("keep-open", "no")
        mpv.setOptionString("force-window", "yes")
        mpv.attachSurface(currentSurface)
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpvInitialized = true
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        if (property == "time-pos") positionMs = value * 1000L
        if (property == "duration") durationMs = value * 1000L
    }

    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos") positionMs = (value * 1000.0).toLong().coerceAtLeast(0)
        if (property == "duration") durationMs = (value * 1000.0).toLong().coerceAtLeast(0)
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> isPlaying = !value
            "eof-reached" -> isEnded = value
        }
    }

    override fun eventProperty(property: String, value: String) = Unit

    override fun event(eventId: Int) {
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
            isLoaded = true
            isEnded = false
            if (pendingStartPositionMs > 0) {
                val seconds = "%.3f".format(java.util.Locale.US, pendingStartPositionMs / 1000.0)
                mpv.command(arrayOf("seek", seconds, "absolute", "exact"))
                positionMs = pendingStartPositionMs
            }
            applyAudioTrackSelection()
            applySubtitleTrackSelection()
            if (pendingPlayWhenReady) play() else pause()
        }
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
            isPlaying = false
        }
    }
}

data class MpvLoad(
    val url: String,
    val authorizationHeader: String,
    val startPositionMs: Long,
    val audioIndex: Int?,
    val audioOrdinal: Int?,
    val subtitleIndex: Int?,
    val subtitleOrdinal: Int?,
    val playWhenReady: Boolean,
)

private data class MpvTrackSelection(
    val streamIndex: Int?,
    val ordinal: Int?,
)
