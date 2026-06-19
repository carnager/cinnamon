package dev.popcorn.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DetailView(
    item: PopItem,
    session: Session?,
    watched: Boolean,
    watchlisted: Boolean,
    onPlay: (Int?, Int?, Long) -> Unit,
    onTrailer: (Boolean) -> Unit,
    onWatchedChange: (Boolean) -> Unit,
    onWatchlistChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onWatchlist: () -> Unit,
    onActor: (Actor) -> Unit,
) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    var detailItem by remember(item.id) { mutableStateOf(item) }
    var selectedAudio by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember(item.id) { mutableStateOf<Int?>(null) }
    var streamsLoaded by remember(item.id) { mutableStateOf(false) }
    var externalRatings by remember(item.id) { mutableStateOf<ExternalRatings?>(null) }
    var sidecars by remember(item.id) { mutableStateOf(SidecarStatus()) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }
    var fullTextOpen by remember { mutableStateOf(false) }
    var resumeProgress by remember(item.id) { mutableStateOf<PlaybackProgress?>(null) }
    val audioFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val castFocus = remember { FocusRequester() }

    LaunchedEffect(item.id) {
        detailItem = item
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).item(item.id) }
            .onSuccess { detailItem = it }
    }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).streams(item.id) }
            .onSuccess { list ->
                streams.clear()
                streams.addAll(list)
                val defAudio = list.firstOrNull { it.type == "audio" && it.default }
                    ?: list.firstOrNull { it.type == "audio" }
                selectedAudio = defAudio?.index
                val defSub = list.firstOrNull { it.type == "subtitle" && it.default }
                selectedSubtitle = defSub?.index
                streamsLoaded = true
            }
            .onFailure { streamsLoaded = true }
    }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        externalRatings = null
        runCatching { Api(active).ratings(item.id) }
            .onSuccess { externalRatings = it }
    }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        sidecars = SidecarStatus()
        runCatching { Api(active).itemSidecars(item.id) }
            .onSuccess { sidecars = it }
    }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        resumeProgress = runCatching { Api(active).progress(item.id) }.getOrNull()
    }

    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }
    val displayTitle = if (detailItem.kind == "episode") detailItem.episodeTitle.ifBlank { detailItem.title } else detailItem.title
    val metadata = detailMetadata(detailItem)
    val audioLabel = selectedTrackLabel(audioTracks, selectedAudio, if (streamsLoaded) "Default audio" else "Loading\u2026")
    val subtitleLabel = selectedTrackLabel(subtitleTracks, selectedSubtitle, if (streamsLoaded) "Off" else "Loading\u2026")
    val resumePosition = resumeProgress?.positionMs ?: 0L
    val resumeDuration = resumeProgress?.durationMs?.takeIf { it > 0 } ?: detailItem.durationMs
    // Resume keys off the saved position, not the completed flag, so a finished
    // (still "seen") item being re-watched still offers Resume.
    val canResume = resumeProgress != null && progressResumable(resumePosition, resumeDuration)

    LaunchedEffect(item.id) {
        delay(260)
        runCatching { playFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Full-screen backdrop
        if (detailItem.backdropPath.isNotBlank() && session != null) {
            SizedAsyncImage(
                model = imageUrl(session, detailItem.id, "backdrop", detailItem.backdropMtimeUnix),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 1920,
                heightPx = 1080,
                authToken = session.token,
            )
        } else if (detailItem.posterPath.isNotBlank() && session != null) {
            SizedAsyncImage(
                model = imageUrl(session, detailItem.id, "poster", detailItem.posterMtimeUnix),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 900,
                heightPx = 900,
                authToken = session.token,
            )
        }

        // Heavy bottom gradient for content readability
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Bg.copy(alpha = .15f),
                        Bg.copy(alpha = .40f),
                        Bg.copy(alpha = .92f),
                        Bg,
                    ),
                    startY = 0f,
                    endY = 900f,
                )
            )
        )
        // Left-side gradient for text
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Bg.copy(alpha = .85f),
                        Bg.copy(alpha = .50f),
                        Color.Transparent,
                    ),
                    startX = 0f,
                    endX = 800f,
                )
            )
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 52.dp, end = 48.dp),
        ) {
            Spacer(Modifier.height(54.dp))
            DetailHeroContent(
                session = session,
                detailItem = detailItem,
                displayTitle = displayTitle,
                metadata = metadata,
                externalRatings = externalRatings,
                audioTracks = audioTracks,
                selectedAudio = selectedAudio,
                audioLabel = audioLabel,
                subtitleLabel = subtitleLabel,
                audioFocus = audioFocus,
                subtitleFocus = subtitleFocus,
                descriptionFocus = descriptionFocus,
                playFocus = playFocus,
                castFocus = castFocus,
                watched = watched,
                watchlisted = watchlisted,
                onAudio = { audioMenuOpen = true },
                onSubtitle = { subtitleMenuOpen = true },
                onFullText = { fullTextOpen = true },
                canResume = canResume,
                resumePositionMs = resumePosition,
                onResume = { onPlay(selectedAudio, selectedSubtitle, resumePosition) },
                onPlayFromStart = { onPlay(selectedAudio, selectedSubtitle, 0L) },
                onTrailer = { onTrailer(sidecars.trailer) },
                onWatchedChange = { onWatchedChange(!watched) },
                onWatchlistChange = { onWatchlistChange(!watchlisted) },
            )

            if (detailItem.actors.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CastStrip(
                    session = session,
                    actors = detailItem.actors,
                    firstFocusRequester = castFocus,
                    onUp = { runCatching { playFocus.requestFocus() }.isSuccess },
                    onActor = onActor,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (!streamsLoaded) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
        }
    }

    if (audioMenuOpen) {
        TrackChoiceDialog(
            title = "Audio Track",
            tracks = audioTracks,
            selected = selectedAudio,
            allowNone = false,
            emptyLabel = "Default",
            onDismiss = { audioMenuOpen = false },
            onSelect = { selectedAudio = it; audioMenuOpen = false },
        )
    }

    if (subtitleMenuOpen) {
        TrackChoiceDialog(
            title = "Subtitle Track",
            tracks = subtitleTracks,
            selected = selectedSubtitle,
            allowNone = true,
            emptyLabel = "Off",
            onDismiss = { subtitleMenuOpen = false },
            onSelect = { selectedSubtitle = it; subtitleMenuOpen = false },
        )
    }

    if (fullTextOpen) {
        FullTextDialog(item = detailItem, onDismiss = { fullTextOpen = false })
    }
}

@Composable
private fun DetailHeroContent(
    session: Session?,
    detailItem: PopItem,
    displayTitle: String,
    metadata: String,
    externalRatings: ExternalRatings?,
    audioTracks: List<StreamInfo>,
    selectedAudio: Int?,
    audioLabel: String,
    subtitleLabel: String,
    audioFocus: FocusRequester,
    subtitleFocus: FocusRequester,
    descriptionFocus: FocusRequester,
    playFocus: FocusRequester,
    castFocus: FocusRequester,
    watched: Boolean,
    watchlisted: Boolean,
    canResume: Boolean,
    resumePositionMs: Long,
    onAudio: () -> Unit,
    onSubtitle: () -> Unit,
    onFullText: () -> Unit,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onTrailer: () -> Unit,
    onWatchedChange: () -> Unit,
    onWatchlistChange: () -> Unit,
) {
    Row(
        Modifier.widthIn(max = 920.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (detailItem.posterPath.isNotBlank() && session != null) {
            Column(Modifier.width(140.dp)) {
                SizedAsyncImage(
                    model = imageUrl(session, detailItem.id, "poster", detailItem.posterMtimeUnix),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    widthPx = 280,
                    heightPx = 420,
                    authToken = session.token,
                )
                val genres = detailGenres(detailItem)
                if (genres.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        genres.joinToString(", "),
                        color = Accent.copy(alpha = .78f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(Modifier.widthIn(max = 680.dp)) {
            Text(
                displayTitle,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 33.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (detailItem.originalTitle.isNotBlank() && detailItem.originalTitle != displayTitle) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detailItem.originalTitle,
                    color = Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (metadata.isNotBlank()) {
                    Text(metadata, color = Color.White.copy(alpha = .85f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                if (detailItem.officialRating.isNotBlank()) {
                    ContentRatingChip(detailItem.officialRating)
                }
            }

            DetailRatingRow(detailItem, externalRatings)

            val chips = detailChips(detailItem, audioTracks, selectedAudio)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                chips.forEach { TechChip(it) }
                SelectorBadge(
                    label = "Audio",
                    value = audioLabel,
                    focusRequester = audioFocus,
                    onLeft = { true },
                    onRight = {
                        subtitleFocus.requestFocus()
                        true
                    },
                    onDown = { requestDetailFocus(descriptionFocus) },
                    onClick = onAudio,
                )
                SelectorBadge(
                    label = "Subs",
                    value = subtitleLabel,
                    focusRequester = subtitleFocus,
                    onLeft = {
                        audioFocus.requestFocus()
                        true
                    },
                    onDown = { requestDetailFocus(descriptionFocus) },
                    onClick = onSubtitle,
                )
            }

            FocusableDescriptionPanel(
                item = detailItem,
                focusRequester = descriptionFocus,
                onUp = { requestDetailFocus(audioFocus) },
                onDown = { requestDetailFocus(playFocus) },
                onClick = onFullText,
            )

            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canResume) {
                    PlayButton(
                        label = "Resume",
                        detail = fmtClock(resumePositionMs),
                        focusRequester = playFocus,
                        onUp = { requestDetailFocus(descriptionFocus) },
                        onDown = { requestDetailFocus(castFocus) },
                        onClick = onResume,
                    )
                    PlayButton(
                        label = "Play",
                        primary = false,
                        onUp = { requestDetailFocus(descriptionFocus) },
                        onDown = { requestDetailFocus(castFocus) },
                        onClick = onPlayFromStart,
                    )
                } else {
                    PlayButton(
                        label = "Play",
                        focusRequester = playFocus,
                        onUp = { requestDetailFocus(descriptionFocus) },
                        onDown = { requestDetailFocus(castFocus) },
                        onClick = onPlayFromStart,
                    )
                }
                ActionToggle(
                    label = "Trailer",
                    active = false,
                    onUp = { requestDetailFocus(descriptionFocus) },
                    onDown = { requestDetailFocus(castFocus) },
                    onClick = onTrailer,
                )
                ActionToggle(
                    label = if (watched) "Seen" else "Mark seen",
                    active = watched,
                    onUp = { requestDetailFocus(descriptionFocus) },
                    onDown = { requestDetailFocus(castFocus) },
                    onClick = onWatchedChange,
                )
                ActionToggle(
                    label = if (watchlisted) "In watchlist" else "Watchlist",
                    active = watchlisted,
                    onUp = { requestDetailFocus(descriptionFocus) },
                    onDown = { requestDetailFocus(castFocus) },
                    onClick = onWatchlistChange,
                )
            }
        }
    }
}

private fun requestDetailFocus(requester: FocusRequester): Boolean {
    return runCatching { requester.requestFocus() }.isSuccess
}

// ── Action buttons ──

@Composable
private fun PlayButton(
    label: String = "Play",
    detail: String? = null,
    primary: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        primary -> Accent
        else -> Color.White.copy(alpha = .12f)
    }
    val contentColor = if (focused || primary) Color.Black else TextColor
    val borderColor = when {
        focused -> Accent
        primary -> Color.Transparent
        else -> Color.White.copy(alpha = .22f)
    }
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(2.dp, borderColor, RoundedCornerShape(6.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
                    else -> false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("\u25B6", color = contentColor, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(label, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (detail != null) {
            Text(detail, color = contentColor.copy(alpha = .72f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActionToggle(
    label: String,
    active: Boolean,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused && active -> Accent.copy(alpha = .92f)
        focused -> Color.White.copy(alpha = .16f)
        active -> Accent.copy(alpha = .30f)
        else -> Color.White.copy(alpha = .10f)
    }
    val borderColor = when {
        focused -> Color.White.copy(alpha = .78f)
        active -> Accent.copy(alpha = .50f)
        else -> Color.White.copy(alpha = .18f)
    }
    Box(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
                    else -> false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused && active) Color.Black else TextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun FocusableDescriptionPanel(
    item: PopItem,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    val hasOverview = item.overview.isNotBlank()
    if (!hasOverview) return
    var focused by remember { mutableStateOf(false) }

    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .widthIn(max = 620.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.Black.copy(alpha = .48f) else Color.Black.copy(alpha = .22f))
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
                    else -> false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        if (hasOverview) {
            Text(
                item.overview,
                color = Color.White.copy(alpha = .82f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FullTextDialog(item: PopItem, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        bodyFocus.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(720.dp)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceColor)
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(22.dp),
        ) {
            Text(item.episodeTitle.ifBlank { item.title }, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.tagline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(item.tagline, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Line.copy(alpha = .6f), RoundedCornerShape(8.dp))
                    .focusRequester(bodyFocus)
                    .focusable()
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (it.key) {
                            Key.DirectionDown -> {
                                scope.launch { scroll.animateScrollTo((scroll.value + 180).coerceAtMost(scroll.maxValue)) }
                                true
                            }
                            Key.DirectionUp -> {
                                scope.launch { scroll.animateScrollTo((scroll.value - 180).coerceAtLeast(0)) }
                                true
                            }
                            else -> false
                        }
                    }
                    .verticalScroll(scroll)
                    .padding(14.dp),
            ) {
                Text(item.overview.ifBlank { "No description available." }, color = TextColor.copy(alpha = .88f), fontSize = 15.sp, lineHeight = 23.sp)
                val rows = detailFactRows(item)
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    DetailFactRows(rows)
                }
            }
        }
    }
}

// ── Info chips ──

@Composable
private fun ContentRatingChip(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TechChip(text: String) {
    Text(
        text,
        color = TextColor.copy(alpha = .85f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = .08f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun detailGenres(item: PopItem): List<String> =
    item.genres.split(",", "/", "|").map { it.trim() }.filter { it.isNotBlank() }.take(3)

@Composable
private fun SelectorBadge(
    label: String,
    value: String,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onLeft: (() -> Boolean)? = null,
    onRight: (() -> Boolean)? = null,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .height(28.dp)
            .widthIn(max = 180.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White.copy(alpha = .20f) else Color.White.copy(alpha = .08f))
            .border(1.dp, if (focused) FocusGlow else Color.White.copy(alpha = .10f), RoundedCornerShape(4.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                when {
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && onLeft != null -> onLeft()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && onRight != null -> onRight()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                    it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
                    else -> false
                }
            }
            .tvActivate(onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, color = if (focused) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(value, color = TextColor.copy(alpha = .86f), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("\u25BE", color = if (focused) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailRatingRow(item: PopItem, ratings: ExternalRatings?) {
    val badges = mutableListOf<Pair<String, String>>()
    if ((ratings?.imdbRating ?: 0.0) > 0) badges.add("IMDb" to "%.1f".format(Locale.US, ratings!!.imdbRating))
    if ((ratings?.tmdbRating ?: 0.0) > 0) badges.add("TMDb" to "%.1f".format(Locale.US, ratings!!.tmdbRating))
    if ((ratings?.rottenTomatoesRating ?: 0) > 0) badges.add("RT" to "${ratings!!.rottenTomatoesRating}%")
    if ((ratings?.metacriticRating ?: 0) > 0) badges.add("MC" to ratings!!.metacriticRating.toString())
    if (badges.isEmpty() && item.rating > 0) badges.add("NFO" to "%.1f".format(Locale.US, item.rating))

    if (badges.isEmpty()) return

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.take(4).forEach { (label, value) -> SourceRatingBadge(label, value) }
    }
}

@Composable
private fun DetailFactPanel(item: PopItem) {
    val rows = detailFactRows(item)
    if (rows.isEmpty()) return

    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .widthIn(max = 600.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = .35f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DetailFactRows(rows)
    }
}

private fun detailFactRows(item: PopItem): List<Pair<String, String>> {
    return listOfNotNull(
        item.tagline.takeIf { it.isNotBlank() }?.let { "Tagline" to it },
        item.directors.takeIf { it.isNotBlank() }?.let { "Director" to it },
        item.writers.takeIf { it.isNotBlank() }?.let { "Writers" to it },
        item.studios.takeIf { it.isNotBlank() }?.let { "Studios" to it },
        item.countries.takeIf { it.isNotBlank() }?.let { "Country" to it },
    )
}

@Composable
private fun DetailFactRows(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.take(6).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(label, color = Accent.copy(alpha = .85f), fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(58.dp))
                Text(value, color = Color.White.copy(alpha = .75f), fontSize = 11.sp, lineHeight = 15.sp, maxLines = if (label == "Tags") 2 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Cast ──

@Composable
fun CastStrip(
    session: Session?,
    actors: List<Actor>,
    firstFocusRequester: FocusRequester? = null,
    onUp: (() -> Boolean)? = null,
    onActor: (Actor) -> Unit,
) {
    Column(Modifier.widthIn(max = 920.dp)) {
        Text("Cast", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(actors.take(14)) { index, actor ->
                CastAvatar(
                    session = session,
                    actor = actor,
                    focusRequester = if (index == 0) firstFocusRequester else null,
                    onUp = onUp,
                    onClick = { onActor(actor) },
                )
            }
        }
    }
}

@Composable
private fun CastAvatar(
    session: Session?,
    actor: Actor,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val thumbUrl = actorImageUrl(session, actor.thumb)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(78.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null && onUp()
            }
            .tvActivate(onClick),
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Surface2)
                .border(2.dp, if (focused) FocusGlow else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbUrl.isNotBlank()) {
                SizedAsyncImage(
                    model = thumbUrl,
                    contentDescription = actor.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    widthPx = 120,
                    heightPx = 120,
                    authToken = if (actor.thumb.startsWith("/")) session?.token.orEmpty() else "",
                )
            } else {
                Text(actorInitials(actor.name), color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(actor.name, color = TextColor, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actor.role.isNotBlank()) {
            Text(actor.role, color = Muted, fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun actorInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }.joinToString("").ifBlank { "?" }
}

// ── Metadata helpers ──

private fun detailMetadata(item: PopItem): String {
    val parts = mutableListOf<String>()
    if (item.kind == "episode") {
        if (item.showTitle.isNotBlank()) parts.add(item.showTitle)
        if (item.seasonNumber > 0 || item.episodeNumber > 0) parts.add("S%02dE%02d".format(item.seasonNumber, item.episodeNumber))
    } else if (item.year > 0) {
        parts.add(item.year.toString())
    }
    if (item.durationMs > 0) {
        parts.add(fmtDuration(item.durationMs))
        parts.add(fmtEndsAround(item.durationMs))
    }
    return parts.joinToString("  \u2022  ")
}

private fun detailChips(item: PopItem, audioTracks: List<StreamInfo>, selectedAudio: Int?): List<String> {
    val chips = mutableListOf<String>()
    val resolution = when {
        item.height >= 2000 -> "4K"
        item.height >= 1000 -> "1080p"
        item.height >= 700 -> "720p"
        item.height > 0 -> "${item.height}p"
        else -> ""
    }
    if (resolution.isNotBlank()) chips.add(resolution)
    item.videoCodec.takeIf { it.isNotBlank() }?.let { chips.add(it.uppercase(Locale.US)) }
    return chips.take(3)
}

// ── Track selection UI ──

@Composable
fun TrackSelectorButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Surface3 else Surface2)
            .border(2.dp, if (focused) FocusGlow else Line, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (focused) Accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("\u25BE", color = if (focused) Accent else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TrackChoiceDialog(
    title: String,
    tracks: List<StreamInfo>,
    selected: Int?,
    allowNone: Boolean,
    emptyLabel: String,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceColor)
                .border(1.dp, Line, RoundedCornerShape(10.dp))
                .padding(vertical = 12.dp),
        ) {
            Text(title, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(horizontal = 12.dp),
            ) {
                if (allowNone) {
                    item {
                        TrackRow(label = emptyLabel, selected = selected == null, onClick = { onSelect(null) })
                    }
                }
                items(tracks.size) { i ->
                    val track = tracks[i]
                    TrackRow(
                        label = track.label(),
                        selected = track.index == selected,
                        onClick = { onSelect(track.index) },
                    )
                }
            }
        }
    }
}

fun selectedTrackLabel(tracks: List<StreamInfo>, selected: Int?, fallback: String): String {
    return tracks.firstOrNull { it.index == selected }?.label() ?: fallback
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        selected && focused -> Accent.copy(alpha = .25f)
        selected -> Accent.copy(alpha = .12f)
        focused -> Surface2
        else -> Color.Transparent
    }
    val border = when {
        focused -> FocusGlow
        selected -> Accent.copy(alpha = .30f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (selected) "\u25C9" else "\u25CB",
            color = if (selected) Accent else Muted,
            fontSize = 14.sp,
        )
        Text(label, color = if (selected) TextColor else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
