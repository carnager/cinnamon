package dev.popcorn.tv

import android.content.Context
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Cinnamon TV palette ──
val Bg = Color(0xFF050B0F)
val SurfaceColor = Color(0xFF0C141A)
val Surface2 = Color(0xFF111B22)
val Surface3 = Color(0xFF19262E)
val Line = Color(0xFF2B3942)
val TextColor = Color(0xFFF3F1EF)
val Muted = Color(0xFF99A5AD)
val Accent = Color(0xFFF47B35)
val AccentDim = Color(0xFFB95525)
val Teal = Color(0xFF42C7BD)
val Gold = Color(0xFFF28A2E)
val ErrorRed = Color(0xFFFF6B6B)
val FocusGlow = Color(0xFFFFFFFF)
val CardShape = RoundedCornerShape(6.dp)
val TvPageMaxWidth = 1200.dp
val TvDetailMaxWidth = 880.dp

object PlayerOsdBridge {
    var handler: ((AndroidKeyEvent) -> Boolean)? = null

    fun dispatch(event: AndroidKeyEvent): Boolean = handler?.invoke(event) == true
}

object PlayerBackBridge {
    var handler: ((AndroidKeyEvent) -> Boolean)? = null

    fun dispatch(event: AndroidKeyEvent): Boolean = handler?.invoke(event) == true
}

object BrowseBackBridge {
    var handler: ((AndroidKeyEvent) -> Boolean)? = null
    private var consumeNextBackUp = false

    fun consumeNextBackUp() {
        consumeNextBackUp = true
    }

    fun dispatch(event: AndroidKeyEvent): Boolean {
        if (handler?.invoke(event) == true) return true
        if (
            consumeNextBackUp &&
            event.keyCode == AndroidKeyEvent.KEYCODE_BACK &&
            event.action == AndroidKeyEvent.ACTION_UP
        ) {
            consumeNextBackUp = false
            return true
        }
        return false
    }
}

fun isActivationKey(key: Key): Boolean {
    return key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
}

fun Modifier.tvActivate(onClick: () -> Unit): Modifier = onKeyEvent {
    if (it.type == KeyEventType.KeyUp && isActivationKey(it.key)) {
        onClick()
        true
    } else {
        false
    }
}.clickable(onClick = onClick)

fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun fmtEndsAround(ms: Long): String {
    if (ms <= 0) return ""
    val endTime = LocalTime.now().plusSeconds(ms / 1000)
    return "Ends around ${endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

fun fmtClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ── Playback progress helpers ──
//
// The server keeps the "completed" flag sticky (completed = MAX(old, new)) so a
// finished show stays "seen" even when you start re-watching it. That means we
// can't use the completed flag to decide whether to offer resume — a re-watch
// resets the position but leaves completed = true. Instead we look only at the
// saved position: near the end means finished, a meaningful mid-point means
// resume.

// progressFinished mirrors the server's isFinished and the player's
// progressCompleted: at/near the end of the file.
fun progressFinished(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0 || positionMs <= 0) return false
    return durationMs - positionMs <= 90_000 || positionMs.toDouble() / durationMs.toDouble() >= 0.92
}

// progressResumable is true when there is a mid-file position worth resuming,
// regardless of the sticky completed flag.
fun progressResumable(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0 || positionMs < 30_000) return false
    return !progressFinished(positionMs, durationMs)
}

fun resumeFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

// resumeFractionMap builds itemId -> watched fraction for everything currently
// resumable, used to draw the progress bar on posters.
fun resumeFractionMap(progress: List<PlaybackProgress>): Map<Long, Float> {
    val out = HashMap<Long, Float>()
    for (p in progress) {
        if (!progressResumable(p.positionMs, p.durationMs)) continue
        out[p.itemId] = resumeFraction(p.positionMs, p.durationMs)
    }
    return out
}

// Provides per-item resume fractions to poster cards without threading the map
// through every screen composable.
val LocalResumeProgress = compositionLocalOf { emptyMap<Long, Float>() }

// Artwork width the server will generate for card-sized images (see
// internal/server/thumbs.go — requests snap up to the nearest bucket, 400 or
// 800). Source posters are routinely 1000x1500, so a shelf of cards otherwise
// pulls megabytes per row. Full-screen backdrops omit the width and keep the
// original, which a 1080p panel can actually use.
const val ArtworkCard = 400

fun imageUrl(session: Session?, itemId: Long, kind: String, version: Long = 0, width: Int = 0): String {
    if (session == null || itemId <= 0) return ""
    val params = buildList {
        if (version > 0) add("v=$version")
        if (width > 0) add("w=$width")
    }
    val suffix = if (params.isEmpty()) "" else "?" + params.joinToString("&")
    return "${session.server}/api/items/$itemId/image/$kind$suffix"
}

fun avatarUrl(session: Session?): String {
    if (session == null || session.userId <= 0 || session.avatar.isBlank()) return ""
    return "${session.server}/api/users/${session.userId}/avatar?v=${session.avatar}"
}

fun actorImageUrl(session: Session?, thumb: String): String {
    val value = thumb.trim()
    if (value.isBlank()) return ""
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    if (session != null && value.startsWith("/")) return "${session.server}$value"
    return ""
}

fun shieldDeviceName(model: String = Build.MODEL): String {
    val cleaned = model
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?i)\\bshield\\b"), "Shield")
    if (cleaned.isBlank()) return "Shield"
    return if (Regex("(?i)\\bshield\\b").containsMatchIn(cleaned)) cleaned else "Shield $cleaned"
}

fun playbackUrl(
    session: Session?,
    itemId: Long,
    bandwidthKbps: Int?,
    hlsSessionId: String?,
    startSeconds: Double,
    audioIndex: Int? = null,
    subtitleIndex: Int? = null,
): String {
    val server = session?.server.orEmpty()
    if (bandwidthKbps == null || hlsSessionId == null) {
        return "$server/api/items/$itemId/stream"
    }
    val params = mutableListOf(
        "bandwidth=$bandwidthKbps",
        "start=${"%.3f".format(Locale.US, startSeconds)}",
    )
    if (audioIndex != null) params.add("audio=$audioIndex")
    if (subtitleIndex != null) params.add("subtitle=$subtitleIndex")
    return "$server/api/items/$itemId/hls/$hlsSessionId/index.m3u8?${params.joinToString("&")}"
}

fun trailerUrl(session: Session?, itemId: Long): String {
    return "${session?.server.orEmpty()}/api/items/$itemId/trailer"
}

fun themeUrl(session: Session?, libraryId: String, showTitle: String): String {
    val server = session?.server.orEmpty()
    return "$server/api/tv/theme?libraryId=${urlEncode(libraryId)}&showTitle=${urlEncode(showTitle)}&stream=1"
}

fun youtubeTrailerSearchUrl(title: String, year: Int): String {
    val query = listOf(title, year.takeIf { it > 0 }?.toString(), "german", "trailer")
        .filterNotNull()
        .joinToString(" ")
    return "https://www.youtube.com/results?search_query=${urlEncode(query)}"
}

private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
