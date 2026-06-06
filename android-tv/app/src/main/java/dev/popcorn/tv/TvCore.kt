package dev.popcorn.tv

import android.content.Context
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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

// ── Plex-inspired palette ──
val Bg = Color(0xFF0D0D0F)
val SurfaceColor = Color(0xFF18181C)
val Surface2 = Color(0xFF222228)
val Surface3 = Color(0xFF2C2C34)
val Line = Color(0xFF38384A)
val TextColor = Color(0xFFF0F0F2)
val Muted = Color(0xFF88889A)
val Accent = Color(0xFFE5A00D)
val AccentDim = Color(0xFFB8800A)
val Gold = Color(0xFFE5A00D)
val ErrorRed = Color(0xFFFF6B6B)
val FocusGlow = Color(0xFFFFFFFF)
val CardShape = RoundedCornerShape(6.dp)
val TvPageMaxWidth = 1200.dp
val TvDetailMaxWidth = 880.dp

object PlayerOsdBridge {
    var handler: ((AndroidKeyEvent) -> Boolean)? = null

    fun dispatch(event: AndroidKeyEvent): Boolean = handler?.invoke(event) == true
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

fun imageUrl(session: Session?, itemId: Long, kind: String, version: Long = 0): String {
    if (session == null || itemId <= 0) return ""
    val suffix = if (version > 0) "?v=$version" else ""
    return "${session.server}/api/items/$itemId/image/$kind$suffix"
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

fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
