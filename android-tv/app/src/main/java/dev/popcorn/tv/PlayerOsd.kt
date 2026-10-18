package dev.popcorn.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.Date

// Everything the on-screen controls show, sampled by PlayerScreen.
data class OsdState(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val playing: Boolean,
    val pendingTargetMs: Long?,
    val chaptersMs: List<Long>,
)

// The player's on-screen controls, in Compose (they were media3's XML
// controller). Opening them focuses the progress bar, so Left/Right seek at
// once; Down reaches the buttons.
@Composable
fun PlayerOsd(
    visible: Boolean,
    state: OsdState,
    title: String,
    subtitle: String,
    logoUrl: String,
    authToken: String,
    subtitleValue: String,
    audioValue: String,
    qualityValue: String,
    showSettings: Boolean,
    nextLabel: String?,
    sheetOpen: Boolean,
    barFocus: FocusRequester,
    playFocus: FocusRequester,
    onBarKey: (AndroidKeyEvent) -> Boolean,
    onPlayPause: () -> Unit,
    onBack10: () -> Unit,
    onForward10: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onQuality: () -> Unit,
    onSettings: () -> Unit,
    onNext: () -> Unit,
) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(60)
            runCatching { barFocus.requestFocus() }
        }
    }
    // Focus goes back to the button that opened a side sheet when it closes
    // (by a pick or by Back); the sheet's rows had taken it, leaving nothing
    // focused afterwards.
    val subtitlesFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var sheetOpener by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(sheetOpen) {
        if (!sheetOpen) {
            val opener = sheetOpener ?: return@LaunchedEffect
            delay(60)
            if (runCatching { opener.requestFocus() }.isFailure) runCatching { barFocus.requestFocus() }
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
        Box(Modifier.fillMaxSize()) {
            // Scrims behind the top block and the bottom controls only, so the
            // picture stays visible in between.
            Box(
                Modifier.fillMaxWidth().height(220.dp).align(Alignment.TopStart).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent)),
                ),
            )
            Box(
                Modifier.fillMaxWidth().height(300.dp).align(Alignment.BottomStart).background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .86f))),
                ),
            )
            OsdTitleBlock(
                title = title,
                subtitle = subtitle,
                logoUrl = logoUrl,
                authToken = authToken,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 36.dp),
            )
            OsdClock(Modifier.align(Alignment.TopEnd).padding(end = 56.dp, top = 40.dp))
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 56.dp, bottom = 36.dp),
            ) {
                OsdTimes(state)
                Spacer(Modifier.height(10.dp))
                OsdProgressBar(state, barFocus, onBarKey)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OsdButton(icon = if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, label = null, onUp = { requestFocus(barFocus) }, onClick = onPlayPause, modifier = Modifier.focusRequester(playFocus))
                    OsdButton(icon = Icons.Filled.Replay10, label = null, onUp = { requestFocus(barFocus) }, onClick = onBack10)
                    OsdButton(icon = Icons.Filled.Forward10, label = null, onUp = { requestFocus(barFocus) }, onClick = onForward10)
                    Spacer(Modifier.width(14.dp))
                    OsdButton(icon = Icons.Filled.Subtitles, label = subtitleValue, onUp = { requestFocus(barFocus) }, onClick = { sheetOpener = subtitlesFocus; onSubtitles() }, modifier = Modifier.focusRequester(subtitlesFocus))
                    OsdButton(icon = Icons.Filled.Audiotrack, label = audioValue, onUp = { requestFocus(barFocus) }, onClick = { sheetOpener = audioFocus; onAudio() }, modifier = Modifier.focusRequester(audioFocus))
                    OsdButton(icon = Icons.Filled.Tune, label = qualityValue, onUp = { requestFocus(barFocus) }, onClick = { sheetOpener = qualityFocus; onQuality() }, modifier = Modifier.focusRequester(qualityFocus))
                    if (showSettings) OsdButton(icon = Icons.Filled.Settings, label = null, onUp = { requestFocus(barFocus) }, onClick = { sheetOpener = settingsFocus; onSettings() }, modifier = Modifier.focusRequester(settingsFocus))
                    if (nextLabel != null) {
                        Spacer(Modifier.width(14.dp))
                        OsdButton(icon = Icons.Filled.SkipNext, label = nextLabel, onUp = { requestFocus(barFocus) }, onClick = onNext)
                    }
                }
            }
        }
    }
}

private fun requestFocus(requester: FocusRequester): Boolean = runCatching { requester.requestFocus() }.isSuccess

@Composable
private fun OsdTitleBlock(title: String, subtitle: String, logoUrl: String, authToken: String, modifier: Modifier) {
    var logoFailed by remember(logoUrl) { mutableStateOf(logoUrl.isBlank()) }
    val context = LocalContext.current
    Column(modifier.widthIn(max = 720.dp)) {
        if (!logoFailed) {
            val request = remember(logoUrl, authToken) {
                ImageRequest.Builder(context)
                    .data(logoUrl)
                    .size(480, 180)
                    .apply { if (authToken.isNotBlank()) addHeader("Authorization", "Bearer $authToken") }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                onError = { logoFailed = true },
                modifier = Modifier.height(80.dp).widthIn(max = 360.dp),
            )
        } else {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Color.White.copy(alpha = .88f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OsdClock(modifier: Modifier) {
    val context = LocalContext.current
    val format = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = format.format(Date())
            delay(15_000)
        }
    }
    Text(now, color = Color.White.copy(alpha = .9f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun OsdTimes(state: OsdState) {
    val context = LocalContext.current
    val format = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val shown = state.pendingTargetMs ?: state.positionMs
    val remaining = (state.durationMs - shown).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fmtClock(shown), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (state.pendingTargetMs != null) {
            val offset = state.pendingTargetMs - state.positionMs
            Text(
                "  ${if (offset >= 0) "+" else "−"}${fmtClock(kotlin.math.abs(offset))}",
                color = Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.durationMs > 0) {
            Text(
                "−${fmtClock(remaining)}  ·  ends ${format.format(Date(System.currentTimeMillis() + remaining))}",
                color = Color.White.copy(alpha = .78f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// Track, buffered range, played range, chapter ticks and — focused — a thumb.
// Left/Right go to the screen's seek model through onKey.
@Composable
private fun OsdProgressBar(state: OsdState, focusRequester: FocusRequester, onKey: (AndroidKeyEvent) -> Boolean) {
    var focused by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shown = (state.pendingTargetMs ?: state.positionMs).coerceIn(0, duration)
    val barHeight = if (focused) 8.dp else 5.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { onKey(it.nativeKeyEvent) }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .22f)))
        val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
        Box(Modifier.width(width * buffered).height(barHeight).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .38f)))
        val played = shown.toFloat() / duration
        Box(Modifier.width(width * played).height(barHeight).clip(RoundedCornerShape(4.dp)).background(Accent))
        state.chaptersMs.forEach { chapter ->
            if (chapter in 1 until duration) {
                Box(
                    Modifier
                        .offset(x = width * (chapter.toFloat() / duration))
                        .width(2.dp)
                        .height(barHeight)
                        .background(Color.Black.copy(alpha = .55f)),
                )
            }
        }
        if (focused) {
            Box(
                Modifier
                    .offset(x = width * played - 9.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Accent, CircleShape),
            )
        }
    }
}

@Composable
private fun OsdButton(icon: ImageVector, label: String?, onUp: () -> Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val content = if (focused) Color.Black else Color.White
    Row(
        modifier
            .height(48.dp)
            .then(if (label == null) Modifier.width(56.dp) else Modifier.widthIn(max = 300.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = .12f))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) onUp() else false
            }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = if (label == null) 0.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(26.dp))
        if (label != null) {
            Text(label, color = content, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

data class SheetRow(val label: String, val detail: String = "", val selected: Boolean = false, val onClick: () -> Unit)

// Side panel for audio, subtitles, quality and settings, in the app's style
// (was a native View popup). Opens on the current choice; scrolls for
// remuxes with 20-30 tracks.
@Composable
fun PlayerSheet(title: String, rows: List<SheetRow>, onDismiss: () -> Unit) {
    // Fresh state per page: Settings > Audio delay reused the list scrolled
    // to the top, so the current value's row wasn't composed and nothing
    // took focus.
    androidx.compose.runtime.key(title) { PlayerSheetPage(title, rows) }
}

@Composable
private fun PlayerSheetPage(title: String, rows: List<SheetRow>) {
    val focusIndex = remember(title, rows.size) { rows.indexOfFirst { it.selected }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 2).coerceAtLeast(0))
    val first = remember(title) { FocusRequester() }
    LaunchedEffect(title, rows.size) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(440.dp)
                .background(Bg.copy(alpha = .97f))
                .border(1.dp, Color.White.copy(alpha = .10f))
                .padding(horizontal = 28.dp, vertical = 40.dp),
        ) {
            Text(title, color = TextColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(rows) { index, row ->
                    SheetRowItem(row, if (index == focusIndex) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
}

@Composable
private fun SheetRowItem(row: SheetRow, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else if (row.selected) Accent.copy(alpha = .16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(row.onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (row.selected) Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) Color.Black else Accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, color = if (focused) Color.Black else TextColor, fontSize = 16.sp, fontWeight = if (row.selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.detail.isNotBlank()) {
                Text(row.detail, color = if (focused) Color.Black.copy(alpha = .7f) else Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
