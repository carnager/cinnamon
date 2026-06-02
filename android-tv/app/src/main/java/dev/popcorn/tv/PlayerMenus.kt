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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val NativeTrackMenuTag = "popcorn_native_track_menu"

fun showNativeOriginalTrackMenu(
    playerView: PlayerView,
    title: String,
    tracks: List<StreamInfo>,
    selectedIndex: Int?,
    allowOff: Boolean,
    returnFocus: View?,
    onSelected: (Int?) -> Unit,
    onClosed: () -> Unit,
) {
    val choices = mutableListOf<NativeChoice>()
    if (allowOff) {
        choices.add(NativeChoice("Off", selectedIndex == null) { onSelected(null) })
    }
    tracks.forEach { track ->
        choices.add(NativeChoice(track.label(), track.index == selectedIndex) { onSelected(track.index) })
    }
    showNativeChoiceMenu(
        playerView = playerView,
        title = title,
        choices = choices.ifEmpty { listOf(NativeChoice("No tracks available yet", false) {}) },
        returnFocus = returnFocus,
        onClosed = onClosed,
    )
}

fun applyOriginalTrackSelection(
    player: ExoPlayer,
    originalStreams: List<StreamInfo>,
    audioIndex: Int?,
    subtitleIndex: Int?,
) {
    applyOriginalTrack(player, originalStreams.firstOrNull { it.index == audioIndex && it.type == "audio" }, C.TRACK_TYPE_AUDIO, disable = false)
    if (subtitleIndex == null) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    } else {
        applyOriginalTrack(player, originalStreams.firstOrNull { it.index == subtitleIndex && it.type == "subtitle" }, C.TRACK_TYPE_TEXT, disable = false)
    }
}

private fun applyOriginalTrack(player: ExoPlayer, stream: StreamInfo?, trackType: Int, disable: Boolean) {
    if (disable) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
        return
    }
    if (stream == null) return
    var bestGroup: androidx.media3.common.Tracks.Group? = null
    var bestIndex = -1
    var bestScore = 0
    for (group in player.currentTracks.groups.filter { it.type == trackType }) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            var score = 0
            if (stream.language.isNotBlank() && format.language?.equals(stream.language, ignoreCase = true) == true) score += 8
            if (stream.title.isNotBlank() && format.label?.contains(stream.title, ignoreCase = true) == true) score += 4
            if (stream.codec.isNotBlank() && format.sampleMimeType?.contains(stream.codec, ignoreCase = true) == true) score += 2
            if (score > bestScore) {
                bestScore = score
                bestGroup = group
                bestIndex = i
            }
        }
    }
    val group = bestGroup ?: return
    if (bestIndex < 0) return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(trackType, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, bestIndex))
        .build()
}

fun showNativeBandwidthMenu(
    playerView: PlayerView,
    selectedBandwidth: Int?,
    returnFocus: View?,
    onSelected: (Int?) -> Unit,
    onClosed: () -> Unit,
) {
    val choices = BandwidthOptions.map { option ->
        NativeChoice(
            label = option.label,
            selected = option.kbps == selectedBandwidth,
            action = { onSelected(option.kbps) },
        )
    }
    showNativeChoiceMenu(
        playerView = playerView,
        title = "Bandwidth",
        choices = choices,
        returnFocus = returnFocus,
        onClosed = onClosed,
    )
}

fun showNativeTrackMenu(
    playerView: PlayerView,
    player: ExoPlayer,
    title: String,
    trackType: Int,
    allowOff: Boolean,
    returnFocus: View?,
    onClosed: () -> Unit,
) {
    val choices = mutableListOf<NativeChoice>()
    if (allowOff) {
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(trackType)
        choices.add(NativeChoice("Off", disabled) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()
        })
    }

    for (group in player.currentTracks.groups.filter { it.type == trackType }) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val label = buildString {
                if (!format.label.isNullOrBlank()) append(format.label)
                if (!format.language.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(format.language!!.uppercase())
                }
                if (!format.sampleMimeType.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(format.sampleMimeType!!.substringAfterLast("/"))
                }
                if (format.channelCount > 0 && trackType == C.TRACK_TYPE_AUDIO) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(when (format.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${format.channelCount}ch"
                    })
                }
                if (isEmpty()) append("Track ${i + 1}")
            }
            val trackGroup = group.mediaTrackGroup
            val trackIndex = i
            choices.add(NativeChoice(label, group.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                    .build()
            })
        }
    }

    if (choices.isEmpty()) {
        choices.add(NativeChoice("No tracks available yet", selected = false, action = {}))
    }

    showNativeChoiceMenu(
        playerView = playerView,
        title = title,
        choices = choices,
        returnFocus = returnFocus,
        onClosed = onClosed,
    )
}

private data class NativeChoice(val label: String, val selected: Boolean, val action: () -> Unit)

private fun showNativeChoiceMenu(
    playerView: PlayerView,
    title: String,
    choices: List<NativeChoice>,
    returnFocus: View?,
    onClosed: () -> Unit,
) {
    closeNativeTrackMenu(playerView, restoreFocus = false)
    playerView.hideController()

    val context = playerView.context
    val overlay = FrameLayout(context).apply {
        tag = NativeTrackMenuTag
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0x99000000.toInt())
        setOnKeyListener { _, keyCode, event ->
            if (event.action == AndroidKeyEvent.ACTION_UP && keyCode == AndroidKeyEvent.KEYCODE_BACK) {
                closeNativeTrackMenu(playerView, returnFocus, onClosed = onClosed)
                true
            } else {
                false
            }
        }
    }

    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF111319.toInt())
        setPadding(dp(context, 0), dp(context, 10), dp(context, 0), dp(context, 10))
    }
    val panelParams = FrameLayout.LayoutParams(dp(context, 340), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        marginEnd = dp(context, 32)
    }
    overlay.addView(panel, panelParams)

    panel.addView(TextView(context).apply {
        text = title
        setTextColor(0xFF4FD1A5.toInt())
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 10))
    })

    val rows = mutableListOf<TextView>()
    var selectedRow: TextView? = null
    fun closeAfterSelection() = closeNativeTrackMenu(playerView, returnFocus, onClosed = onClosed)
    fun addRow(choice: NativeChoice) {
        val row = TextView(context).apply {
            text = if (choice.selected) "\u2713  ${choice.label}" else "    ${choice.label}"
            setTextColor(if (choice.selected) 0xFFFFFFFF.toInt() else 0xFFE8ECF2.toInt())
            textSize = 13f
            typeface = if (choice.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isFocusable = true
            isClickable = true
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9))
            setBackgroundColor(if (choice.selected) 0x332A8B6E else 0x00000000)
            setOnFocusChangeListener { view, focused ->
                view.setBackgroundColor(if (focused) 0xFF263042.toInt() else if (choice.selected) 0x332A8B6E else 0x00000000)
                (view as TextView).setTextColor(if (focused) 0xFF4FD1A5.toInt() else if (choice.selected) 0xFFFFFFFF.toInt() else 0xFFE8ECF2.toInt())
            }
            setOnClickListener {
                choice.action()
                closeAfterSelection()
            }
            setOnKeyListener { view, keyCode, event ->
                when {
                    keyCode == AndroidKeyEvent.KEYCODE_BACK -> {
                        if (event.action == AndroidKeyEvent.ACTION_UP) closeNativeTrackMenu(playerView, returnFocus, onClosed = onClosed)
                        true
                    }
                    event.action == AndroidKeyEvent.ACTION_UP && (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER || keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) -> {
                        view.performClick()
                        true
                    }
                    else -> false
                }
            }
        }
        if (choice.selected && selectedRow == null) selectedRow = row
        rows.add(row)
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    choices.forEach { addRow(it) }

    playerView.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    overlay.post {
        (selectedRow ?: rows.firstOrNull { it.text.toString().trim() != "No tracks available yet" })?.requestFocus()
            ?: overlay.requestFocus()
    }
}

fun closeNativeTrackMenu(
    playerView: PlayerView,
    returnFocus: View? = null,
    restoreFocus: Boolean = true,
    onClosed: (() -> Unit)? = null,
) {
    playerView.findViewWithTag<View>(NativeTrackMenuTag)?.let { playerView.removeView(it) }
    if (restoreFocus) {
        playerView.showController()
        playerView.post {
            val focusTarget = returnFocus?.takeIf { it.isAttachedToWindow }
                ?: playerView
            focusTarget.requestFocus()
            onClosed?.invoke()
        }
    }
}

fun bandwidthLabel(kbps: Int?): String {
    return BandwidthOptions.firstOrNull { it.kbps == kbps }?.label ?: "Direct"
}

fun newHlsSessionId(itemId: Long): String {
    return "android_${itemId}_${System.currentTimeMillis()}"
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

@Composable
fun TrackMenu(title: String, player: ExoPlayer, trackType: Int, allowOff: Boolean = false, onDismiss: () -> Unit) {
    val trackGroups = player.currentTracks.groups.filter { it.type == trackType }
    val tracks = mutableListOf<Pair<String, () -> Unit>>()

    if (allowOff) {
        tracks.add("Off" to {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()
            onDismiss()
        })
    }

    for (group in trackGroups) {
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val label = buildString {
                if (!format.label.isNullOrBlank()) append(format.label)
                val lang = format.language
                if (!lang.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(lang.uppercase())
                }
                val mime = format.sampleMimeType
                if (!mime.isNullOrBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(mime.substringAfterLast("/"))
                }
                if (format.channelCount > 0 && trackType == C.TRACK_TYPE_AUDIO) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(when (format.channelCount) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${format.channelCount}ch"
                    })
                }
                if (isEmpty()) append("Track ${i + 1}")
            }
            val isSelected = group.isTrackSelected(i)
            val trackGroup = group.mediaTrackGroup
            val trackIndex = i
            tracks.add(("${if (isSelected) "\u25C9 " else "\u25CB "}$label") to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                    .build()
                onDismiss()
            })
        }
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(tracks.size) {
        if (tracks.isNotEmpty()) firstFocus.requestFocus()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .width(280.dp)
                .padding(end = 24.dp)
                .clip(CardShape)
                .background(SurfaceColor)
                .border(1.dp, Line, CardShape)
                .padding(vertical = 10.dp),
        ) {
            Text(title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            tracks.forEachIndexed { index, (label, action) ->
                var focused by remember { mutableStateOf(false) }
                Text(
                    label,
                    color = if (focused) Accent else TextColor,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (focused) Surface2 else Color.Transparent)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .tvActivate(action)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}
