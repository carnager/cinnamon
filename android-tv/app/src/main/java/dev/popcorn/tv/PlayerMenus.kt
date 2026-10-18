package dev.popcorn.tv

import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.Util as Media3Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


// Returns a compact outcome summary ("audio=… text=…") for client-side logging.
fun applyOriginalTrackSelection(
    player: ExoPlayer,
    originalStreams: List<StreamInfo>,
    audioIndex: Int?,
    subtitleIndex: Int?,
): String {
    val audioStreams = originalStreams.filter { it.type == "audio" }
    val subtitleStreams = originalStreams.filter { it.type == "subtitle" }
    val audioStream = audioStreams.firstOrNull { it.index == audioIndex }
    val audioOrdinal = audioStreams.indexOfFirst { it.index == audioIndex }.takeIf { it >= 0 }
    val subtitleStream = subtitleStreams.firstOrNull { it.index == subtitleIndex }
    val subtitleOrdinal = subtitleStreams.indexOfFirst { it.index == subtitleIndex }.takeIf { it >= 0 }
    val audioResult = applyOriginalTrack(player, audioStream, C.TRACK_TYPE_AUDIO, disable = false, preferredOrdinal = audioOrdinal)
    val textResult = if (subtitleIndex == null) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        "off"
    } else {
        applyOriginalTrack(player, subtitleStream, C.TRACK_TYPE_TEXT, disable = false, preferredOrdinal = subtitleOrdinal)
    }
    return "audio=$audioResult text=$textResult"
}

private fun applyOriginalTrack(player: ExoPlayer, stream: StreamInfo?, trackType: Int, disable: Boolean, preferredOrdinal: Int? = null): String {
    if (disable) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
        return "disabled"
    }
    if (stream == null) return "no-stream"
    // ffprobe reports ISO 639-2 codes ("ger", "jpn") while ExoPlayer normalizes
    // Format.language to 639-1 ("de", "ja") — compare both sides normalized.
    val streamLanguage = stream.language.takeIf { it.isNotBlank() }?.let { Media3Util.normalizeLanguageCode(it) }
    var bestGroup: androidx.media3.common.Tracks.Group? = null
    var bestIndex = -1
    var bestScore = 0
    val candidates = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()
    for (group in player.currentTracks.groups.filter { it.type == trackType }) {
        for (i in 0 until group.length) {
            candidates.add(group to i)
            val format = group.getTrackFormat(i)
            val formatLanguage = format.language?.let { Media3Util.normalizeLanguageCode(it) }
            val formatForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
            var score = 0
            if (streamLanguage != null && formatLanguage != null && formatLanguage.equals(streamLanguage, ignoreCase = true)) score += 8
            if (stream.title.isNotBlank()) {
                val label = format.label.orEmpty().trim()
                // Exact title must strictly outrank a substring hit: with paired
                // tracks like "Deutsch" / "Deutsch (forced)" the forced label
                // CONTAINS the plain title, and an equal score would silently
                // render the near-empty forced track for a "Deutsch" pick.
                if (label.equals(stream.title.trim(), ignoreCase = true)) {
                    score += 8
                } else if (label.contains(stream.title, ignoreCase = true)) {
                    score += 1
                }
            }
            // Forced-flag agreement separates same-language pairs that carry no
            // titles at all (only "eng" + "eng forced" dispositions).
            if (stream.forced == formatForced) score += 2
            if (stream.codec.isNotBlank() && format.sampleMimeType?.contains(stream.codec, ignoreCase = true) == true) score += 1
            if (score > bestScore) {
                bestScore = score
                bestGroup = group
                bestIndex = i
            }
        }
    }
    // Weak signals (substring title, forced agreement, codec) can sum to at
    // most 4 and say nothing about which track the user picked — trust the
    // container-order ordinal unless language or the exact title matched.
    if (bestScore < 8 && preferredOrdinal != null) {
        candidates.getOrNull(preferredOrdinal)?.let { (group, index) ->
            bestGroup = group
            bestIndex = index
        }
    }
    val group = bestGroup ?: return "no-group(candidates=${candidates.size} ordinal=$preferredOrdinal)"
    if (bestIndex < 0) return "no-index"
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(trackType, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, bestIndex))
        .build()
    val format = group.getTrackFormat(bestIndex)
    val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
    return "override(score=$bestScore idx=$bestIndex mime=${format.sampleMimeType} lang=${format.language} label=${format.label} forced=$forced supported=${group.isTrackSupported(bestIndex)})"
}


fun bandwidthLabel(kbps: Int?): String {
    return BandwidthOptions.firstOrNull { it.kbps == kbps }?.label ?: "Direct"
}

fun newHlsSessionId(deviceId: String, itemId: Long): String {
    val owner = deviceId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: "local"
    return "android_${owner}_${itemId}_${System.currentTimeMillis()}"
}

suspend fun stopHlsSession(session: Session, hlsSessionId: String) = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL("${session.server}/api/hls/$hlsSessionId").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        conn.responseCode
        conn.inputStream.close()
    }
}

