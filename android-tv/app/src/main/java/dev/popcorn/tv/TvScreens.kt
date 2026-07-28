package dev.popcorn.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ShowView(
    session: Session?,
    show: ShowSummary,
    libraries: List<Library>,
    showUpdate: Boolean,
    initialSeasonFocus: Int?,
    initialEpisodeFocus: Long?,
    startWithEpisodes: Boolean,
    completedItems: Set<Long>,
    watchlistItems: Set<Long>,
    showWatched: Boolean,
    showWatchlisted: Boolean,
    refreshToken: Long,
    onHome: () -> Unit,
    onLibrary: (Library) -> Unit,
    onWatchlist: () -> Unit,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onUpdates: () -> Unit,
    onScan: () -> Unit,
    onLogout: () -> Unit,
    onShowWatchedChange: () -> Unit,
    onShowWatchlistChange: () -> Unit,
    onSeason: (SeasonSummary) -> Unit,
    onSeasonMenu: (SeasonSummary, FocusRequester) -> Unit,
    onEpisodeFocus: (PopItem) -> Unit,
    onPlayEpisode: (PopItem) -> Unit,
    onEpisode: (PopItem) -> Unit,
    onEpisodeMenu: (PopItem, FocusRequester) -> Unit,
) {
    val seasons = remember { mutableStateListOf<SeasonSummary>() }
    val episodes = remember { mutableStateListOf<PopItem>() }
    val showEpisodes = remember { mutableStateListOf<PopItem>() }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var episodesLoading by remember { mutableStateOf(false) }
    var selectedSeason by remember(show.libraryId, show.title) { mutableStateOf<SeasonSummary?>(null) }
    var focusedSeason by remember(show.libraryId, show.title) { mutableStateOf<SeasonSummary?>(null) }
    var focusedEpisode by remember(show.libraryId, show.title) { mutableStateOf<PopItem?>(null) }
    var themeAvailable by remember(show.libraryId, show.title) { mutableStateOf(false) }
    var fullTextOpen by remember(show.libraryId, show.title) { mutableStateOf(false) }
    val descriptionFocus = remember(show.libraryId, show.title) { FocusRequester() }
    val seasonFocus = remember(show.libraryId, show.title) { FocusRequester() }
    val firstEpisodeFocus = remember(show.libraryId, show.title) { FocusRequester() }
    val navFocus = remember { FocusRequester() }
    var episodePreviewActive by remember(show.libraryId, show.title) { mutableStateOf(false) }

    LaunchedEffect(show.libraryId, show.title, session) {
        val active = session ?: return@LaunchedEffect
        themeAvailable = false
        runCatching { Api(active).showTheme(show.libraryId, show.title) }
            .onSuccess { themeAvailable = it.theme }
    }

    LaunchedEffect(show.libraryId, show.title, refreshToken) {
        val active = session ?: return@LaunchedEffect
        loading = true
        runCatching { Api(active).seasons(show.libraryId, show.title) }
            .onSuccess {
                seasons.clear()
                seasons.addAll(it)
                val targetSeason = it.firstOrNull { season -> season.seasonNumber == initialSeasonFocus } ?: it.firstOrNull()
                selectedSeason = targetSeason
                focusedSeason = targetSeason
            }
            .onFailure { error = it.message ?: "Failed to load seasons" }
        loading = false
    }

    LaunchedEffect(show.libraryId, show.title, refreshToken) {
        showEpisodes.clear()
        val active = session ?: return@LaunchedEffect
        runCatching { Api(active).episodes(show.libraryId, show.title) }
            .onSuccess { loaded ->
                showEpisodes.clear()
                showEpisodes.addAll(
                    loaded.sortedWith(
                        compareBy<PopItem> { if (it.seasonNumber > 0) it.seasonNumber else Int.MAX_VALUE }
                            .thenBy { it.episodeNumber }
                            .thenBy { it.id },
                    ),
                )
            }
            .onFailure { error = it.message ?: "Failed to load episodes" }
    }

    LaunchedEffect(show.libraryId, show.title, selectedSeason?.seasonNumber, refreshToken) {
        val active = session ?: return@LaunchedEffect
        val season = selectedSeason ?: return@LaunchedEffect
        episodesLoading = true
        episodes.clear()
        focusedEpisode = null
        runCatching { Api(active).episodes(show.libraryId, show.title, season.seasonNumber) }
            .onSuccess {
                episodes.clear()
                episodes.addAll(it)
                focusedEpisode = it.firstOrNull { episode -> episode.id == initialEpisodeFocus } ?: it.firstOrNull()
            }
            .onFailure { error = it.message ?: "Failed to load episodes" }
        episodesLoading = false
    }

    if (themeAvailable) {
        ThemeMusicPlayer(session = session, show = show)
    }

    val resumeProgress = LocalResumeProgress.current
    val nextEpisode = showEpisodes.firstOrNull { it.id !in completedItems } ?: showEpisodes.firstOrNull()
    val nextEpisodeLabel = nextEpisode?.let { episode ->
        val verb = if ((resumeProgress[episode.id] ?: 0f) > 0f) "Continue" else "Play"
        "$verb S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber)
    }.orEmpty()

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            ShowHeader(
                session = session,
                show = show,
                focusedSeason = focusedSeason,
                focusedEpisode = focusedEpisode.takeIf { episodePreviewActive },
                descriptionFocus = descriptionFocus,
                watched = showWatched,
                watchlisted = showWatchlisted,
                nextEpisodeLabel = nextEpisodeLabel,
                onNextEpisode = nextEpisode?.let { episode ->
                    {
                        focusedEpisode = episode
                        onEpisodeFocus(episode)
                        onPlayEpisode(episode)
                    }
                },
                onWatchedChange = onShowWatchedChange,
                onWatchlistChange = onShowWatchlistChange,
                onFullText = { fullTextOpen = true },
                onActionFocus = { episodePreviewActive = false },
            )

            if (error.isNotBlank()) {
                Text(error, color = ErrorRed, modifier = Modifier.padding(start = 116.dp, end = 32.dp, top = 4.dp, bottom = 4.dp), fontSize = 12.sp)
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                // Keep tabs and episode cards clear of the navigation rail overlay
                // (84dp + the rows' own 32dp lines content up with the header text).
                Column(Modifier.fillMaxWidth().weight(1f).padding(start = 84.dp)) {
                    SeasonTabs(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        focusRequester = seasonFocus,
                        autoFocus = !startWithEpisodes,
                        onFocus = {
                            focusedSeason = it
                            episodePreviewActive = false
                        },
                        onSeason = {
                            selectedSeason = it
                            focusedSeason = it
                            focusedEpisode = null
                            onSeason(it)
                        },
                        onSeasonMenu = onSeasonMenu,
                        onUp = { requestTvFocus(descriptionFocus) },
                        onDown = { requestTvFocus(firstEpisodeFocus) },
                    )
                    if (episodesLoading && episodes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        EpisodeCarousel(
                            session = session,
                            episodes = episodes,
                            completedEpisodes = completedItems,
                            watchlistEpisodes = watchlistItems,
                            initialEpisodeFocus = initialEpisodeFocus,
                            firstFocusRequester = firstEpisodeFocus,
                            autoFocus = startWithEpisodes,
                            onFocus = {
                                focusedEpisode = it
                                episodePreviewActive = true
                                onEpisodeFocus(it)
                            },
                            onEpisode = {
                                focusedEpisode = it
                                onEpisodeFocus(it)
                                onEpisode(it)
                            },
                            onEpisodeMenu = onEpisodeMenu,
                            onUp = { requestTvFocus(seasonFocus) },
                        )
                    }
                }
            }
        }

        SideNavigation(
            session = session,
            libraries = libraries,
            selected = show.libraryId,
            showUpdate = showUpdate,
            onHome = onHome,
            onLibrary = onLibrary,
            onWatchlist = onWatchlist,
            onHistory = onHistory,
            onSearch = onSearch,
            onUpdates = onUpdates,
            onScan = onScan,
            onLogout = onLogout,
            firstFocusRequester = navFocus,
            onExit = { requestTvFocus(descriptionFocus) },
        )

        CinnamonBrand(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 24.dp),
            markSize = 42,
            fontSize = 21,
        )
    }

    if (fullTextOpen) {
        ShowFullTextDialog(
            title = show.title,
            subtitle = focusedSeason?.let { seasonTitle(it) }.orEmpty(),
            overview = show.overview,
            onDismiss = { fullTextOpen = false },
        )
    }
}

@Composable
fun ShowHeader(
    session: Session?,
    show: ShowSummary,
    focusedSeason: SeasonSummary?,
    focusedEpisode: PopItem?,
    descriptionFocus: FocusRequester? = null,
    watched: Boolean = false,
    watchlisted: Boolean = false,
    nextEpisodeLabel: String = "",
    onNextEpisode: (() -> Unit)? = null,
    onWatchedChange: () -> Unit = {},
    onWatchlistChange: () -> Unit = {},
    onFullText: () -> Unit = {},
    onActionFocus: () -> Unit = {},
) {
    val episodeTitle = focusedEpisode?.episodeTitle?.ifBlank { focusedEpisode.title }.orEmpty()
    val overview = focusedEpisode?.overview?.takeIf { it.isNotBlank() } ?: show.overview
    val meta = focusedEpisode?.let { episode ->
        listOfNotNull(
            "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber),
            fmtDuration(episode.durationMs).ifBlank { null },
            fmtEndsAround(episode.durationMs).takeIf { episode.durationMs > 0 },
        ).joinToString(" \u00b7 ")
    }
    val rating = if (focusedEpisode != null) {
        focusedEpisode.rating.takeIf { it > 0 } ?: 0.0
    } else {
        show.rating
    }

    Box(Modifier.fillMaxWidth().height(286.dp)) {
        val episodeBackdropUrl = if (focusedEpisode != null && session != null) {
            when {
                focusedEpisode.backdropPath.isNotBlank() -> imageUrl(session, focusedEpisode.id, "backdrop", focusedEpisode.backdropMtimeUnix)
                focusedEpisode.posterPath.isNotBlank() -> imageUrl(session, focusedEpisode.id, "poster", focusedEpisode.posterMtimeUnix)
                else -> ""
            }
        } else {
            ""
        }
        if (episodeBackdropUrl.isNotBlank() && session != null) {
            SizedAsyncImage(
                model = episodeBackdropUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 1280,
                heightPx = 500,
                authToken = session.token,
            )
        } else if (show.backdropItemId > 0 && session != null) {
            SizedAsyncImage(
                model = imageUrl(session, show.backdropItemId, "backdrop", show.backdropMtimeUnix),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                widthPx = 1280,
                heightPx = 500,
                authToken = session.token,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(colors = listOf(Bg.copy(alpha = .10f), Bg.copy(alpha = .60f), Bg), startY = 0f)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(colors = listOf(Bg.copy(alpha = .75f), Bg.copy(alpha = .25f), Color.Transparent), startX = 0f, endX = 600f)
            )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = TvDetailMaxWidth)
                .fillMaxWidth(.70f)
                .padding(start = 116.dp, end = 28.dp, bottom = 20.dp),
        ) {
            val genreLine = show.genres.split(",", "/", "|")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(3)
                .joinToString("  ·  ")
            if (genreLine.isNotBlank()) {
                Text(
                    genreLine.uppercase(),
                    color = Teal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
            }
            Text(
                show.title,
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 44.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeTitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    episodeTitle,
                    color = TextColor.copy(alpha = .88f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meta != null) {
                    Text(meta, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    show.yearsLabel().takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Muted, fontSize = 12.sp)
                    }
                    Text("·", color = Muted.copy(alpha = .6f), fontSize = 12.sp)
                    Text("${show.seasonCount} seasons", color = Muted, fontSize = 12.sp)
                    Text("·", color = Muted.copy(alpha = .6f), fontSize = 12.sp)
                    Text("${show.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                }
                if (rating > 0) RatingBadge(rating)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                overview.ifBlank { "No description available." },
                color = TextColor.copy(alpha = .76f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = if (episodeTitle.isBlank()) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val hasPrimary = onNextEpisode != null && nextEpisodeLabel.isNotBlank()
                if (hasPrimary) {
                    ShowPrimaryAction(label = nextEpisodeLabel, focusRequester = descriptionFocus, onFocus = onActionFocus, onClick = onNextEpisode!!)
                }
                ShowHeaderAction(
                    label = "Seen",
                    icon = Icons.Filled.CheckCircle,
                    active = watched,
                    activeTint = Accent,
                    focusRequester = if (!hasPrimary) descriptionFocus else null,
                    onFocus = onActionFocus,
                    onClick = onWatchedChange,
                )
                ShowHeaderAction(
                    label = "Watchlist",
                    icon = Icons.Filled.Bookmark,
                    active = watchlisted,
                    activeTint = Teal,
                    onFocus = onActionFocus,
                    onClick = onWatchlistChange,
                )
                ShowHeaderAction(
                    label = "More info",
                    icon = Icons.Filled.Info,
                    active = false,
                    activeTint = Accent,
                    onFocus = onActionFocus,
                    onClick = onFullText,
                )
            }
        }
    }
}

@Composable
private fun ShowPrimaryAction(
    label: String,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (focused) Accent else AccentDim)
            .border(1.dp, if (focused) Color(0xFFFFA66A) else Accent.copy(alpha = .48f), RoundedCornerShape(11.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TextColor, modifier = Modifier.size(19.dp))
        Text(label, color = TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShowHeaderAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeTint: Color,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val tint = when {
        focused -> TextColor
        active -> activeTint
        else -> Muted
    }
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Transparent)
            .border(
                1.dp,
                when {
                    focused -> Accent
                    else -> Color.Transparent
                },
                RoundedCornerShape(11.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ShowFullTextDialog(
    title: String,
    subtitle: String,
    overview: String,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { bodyFocus.requestFocus() }
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
            Text(title, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                Text(overview.ifBlank { "No description available." }, color = TextColor.copy(alpha = .88f), fontSize = 15.sp, lineHeight = 23.sp)
            }
        }
    }
}

@Composable
fun InlineEpisodeDetails(session: Session?, item: PopItem, onPlay: (Int?, Int?) -> Unit) {
    val streams = remember { mutableStateListOf<StreamInfo>() }
    var selectedAudio by remember(item.id) { mutableStateOf<Int?>(null) }
    var selectedSubtitle by remember(item.id) { mutableStateOf<Int?>(null) }
    var streamsLoaded by remember(item.id) { mutableStateOf(false) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        val active = session ?: return@LaunchedEffect
        streamsLoaded = false
        runCatching { Api(active).streams(item.id) }
            .onSuccess { list ->
                streams.clear()
                streams.addAll(list)
                val defAudio = list.firstOrNull { it.type == "audio" && it.default } ?: list.firstOrNull { it.type == "audio" }
                selectedAudio = defAudio?.index
                selectedSubtitle = list.firstOrNull { it.type == "subtitle" && it.default }?.index
                streamsLoaded = true
            }
            .onFailure { streamsLoaded = true }
    }

    val audioTracks = streams.filter { it.type == "audio" }
    val subtitleTracks = streams.filter { it.type == "subtitle" }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .widthIn(max = TvDetailMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusButton(label = "\u25B6  Play", primary = true, modifier = Modifier.width(126.dp)) {
                onPlay(selectedAudio, selectedSubtitle)
            }
            if (!streamsLoaded) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
            if (streamsLoaded && audioTracks.isNotEmpty()) {
                TrackSelectorButton(
                    label = "Audio",
                    value = selectedTrackLabel(audioTracks, selectedAudio, "Default"),
                    modifier = Modifier.weight(1f),
                    onClick = { audioMenuOpen = true },
                )
            }
            if (streamsLoaded && subtitleTracks.isNotEmpty()) {
                TrackSelectorButton(
                    label = "Subtitles",
                    value = selectedTrackLabel(subtitleTracks, selectedSubtitle, "Off"),
                    modifier = Modifier.weight(1f),
                    onClick = { subtitleMenuOpen = true },
                )
            }
        }
    }

    if (audioMenuOpen) {
        TrackChoiceDialog(
            title = "Audio",
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
            title = "Subtitles",
            tracks = subtitleTracks,
            selected = selectedSubtitle,
            allowNone = true,
            emptyLabel = "Off",
            onDismiss = { subtitleMenuOpen = false },
            onSelect = { selectedSubtitle = it; subtitleMenuOpen = false },
        )
    }
}

@Composable
fun SeasonHeader(session: Session?, show: ShowSummary, season: SeasonSummary, focusedEpisode: PopItem?) {
    val headline = focusedEpisode?.episodeTitle?.ifBlank { focusedEpisode.title } ?: seasonTitle(season)
    val meta = focusedEpisode?.let { episode ->
        listOfNotNull(
            "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber),
            fmtDuration(episode.durationMs).ifBlank { null },
            fmtEndsAround(episode.durationMs).takeIf { episode.durationMs > 0 },
        ).joinToString(" \u00b7 ")
    } ?: listOfNotNull(
        "${season.episodeCount} episodes",
        fmtDuration(season.durationMs).ifBlank { null },
    ).joinToString(" \u00b7 ")
    val overview = focusedEpisode?.overview?.takeIf { it.isNotBlank() } ?: season.overview
    val rating = if (focusedEpisode != null) {
        focusedEpisode.rating.takeIf { it > 0 } ?: 0.0
    } else {
        season.rating
    }

    Box(Modifier.fillMaxWidth().background(SurfaceColor), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .widthIn(max = TvDetailMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val episodeImage = focusedEpisode
            if (episodeImage != null) {
                EpisodeStill(session, episodeImage, Modifier.width(154.dp))
            } else {
                SeasonPoster(session, season, Modifier.width(70.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(show.title, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(headline, color = TextColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(meta, color = Muted, fontSize = 12.sp)
                    if (rating > 0) RatingBadge(rating)
                }
                if (overview.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(overview, color = TextColor.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SeasonTabs(
    seasons: List<SeasonSummary>,
    selectedSeason: SeasonSummary?,
    focusRequester: FocusRequester,
    autoFocus: Boolean,
    onFocus: (SeasonSummary) -> Unit,
    onSeason: (SeasonSummary) -> Unit,
    onSeasonMenu: (SeasonSummary, FocusRequester) -> Unit,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowItemsIndexed(seasons, key = { _, season -> seasonFocusKey(season) }) { _, season ->
                val selected = selectedSeason?.seasonNumber == season.seasonNumber
                var focused by remember { mutableStateOf(false) }
                var longPressReady by remember { mutableStateOf(false) }
                var longPressJob by remember { mutableStateOf<Job?>(null) }
                val localRequester = remember { FocusRequester() }
                val requester = if (selected) focusRequester else localRequester
                if (selected && autoFocus) {
                    LaunchedEffect(seasonFocusKey(season)) {
                        delay(180)
                        runCatching { requester.requestFocus() }
                    }
                }
                Row(
                    Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) Teal.copy(alpha = .11f) else Color.Transparent)
                        .border(
                            1.dp,
                            when {
                                focused -> Accent
                                selected -> Teal.copy(alpha = .52f)
                                else -> Color.Transparent
                            },
                            RoundedCornerShape(11.dp),
                        )
                        .focusRequester(requester)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (it.isFocused) onFocus(season)
                            if (!it.isFocused) {
                                longPressJob?.cancel()
                                longPressJob = null
                                longPressReady = false
                            }
                        }
                        .focusable()
                        .onPreviewKeyEvent {
                            when {
                                it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && onUp != null -> onUp()
                                it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && onDown != null -> onDown()
                                it.type == KeyEventType.KeyDown && isActivationKey(it.key) -> {
                                    if (longPressJob == null) {
                                        longPressReady = false
                                        longPressJob = scope.launch {
                                            delay(650)
                                            longPressReady = true
                                        }
                                    }
                                    true
                                }
                                it.type == KeyEventType.KeyUp && isActivationKey(it.key) -> {
                                    longPressJob?.cancel()
                                    longPressJob = null
                                    if (longPressReady) {
                                        longPressReady = false
                                        onSeasonMenu(season, requester)
                                    } else {
                                        onSeason(season)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(seasonTitle(season), color = if (focused) TextColor else if (selected) Teal else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${season.episodeCount}", color = if (focused) TextColor.copy(alpha = .72f) else Muted.copy(alpha = .72f), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun EpisodeCarousel(
    session: Session?,
    episodes: List<PopItem>,
    completedEpisodes: Set<Long>,
    watchlistEpisodes: Set<Long>,
    initialEpisodeFocus: Long?,
    firstFocusRequester: FocusRequester,
    autoFocus: Boolean,
    onFocus: (PopItem) -> Unit,
    onEpisode: (PopItem) -> Unit,
    onEpisodeMenu: (PopItem, FocusRequester) -> Unit,
    onUp: (() -> Boolean)? = null,
) {
    val targetId = initialEpisodeFocus?.takeIf { id -> episodes.any { it.id == id } } ?: episodes.firstOrNull()?.id
    if (episodes.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(184.dp), contentAlignment = Alignment.Center) {
            Text("No episodes found", color = Muted, fontSize = 12.sp)
        }
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(190.dp),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 5.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rowItemsIndexed(episodes, key = { _, episode -> episode.id }) { _, episode ->
            val localRequester = remember { FocusRequester() }
            val requester = if (episode.id == targetId) firstFocusRequester else localRequester
            Box(Modifier.width(450.dp).height(176.dp)) {
                EpisodeLandscapeCard(
                    session = session,
                    item = episode,
                    watched = completedEpisodes.contains(episode.id),
                    watchlisted = watchlistEpisodes.contains(episode.id),
                    autoFocus = autoFocus && episode.id == targetId,
                    focusRequester = requester,
                    onFocus = { onFocus(episode) },
                    onClick = { onEpisode(episode) },
                    onLongClick = { focusedRequester -> onEpisodeMenu(episode, focusedRequester) },
                    onUp = onUp,
                )
            }
        }
    }
}

@Composable
private fun EpisodeLandscapeCard(
    session: Session?,
    item: PopItem,
    watched: Boolean,
    watchlisted: Boolean,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (FocusRequester) -> Unit,
    onUp: (() -> Boolean)? = null,
) {
    CardShell(
        modifier = Modifier.fillMaxSize(),
        autoFocus = autoFocus,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onUp = onUp,
        focusScale = 1.01f,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(278.dp).aspectRatio(16f / 9f)) {
                EpisodeStill(session, item, Modifier.fillMaxSize())
                PosterCornerMarks(watched, watchlisted, item.rating, seenAtEnd = true)
                val progress = LocalResumeProgress.current[item.id] ?: 0f
                if (progress > 0f) PosterProgressBar(progress)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(9.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.White.copy(alpha = .92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play episode", tint = Bg, modifier = Modifier.size(18.dp))
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "EPISODE ${item.episodeNumber} · ${fmtDuration(item.durationMs).ifBlank { "—" }}",
                    color = Teal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    item.episodeTitle.ifBlank { item.title },
                    color = TextColor,
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.overview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        item.overview,
                        color = TextColor.copy(alpha = .66f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun SeasonStripe(
    session: Session?,
    seasons: List<SeasonSummary>,
    selectedSeason: SeasonSummary?,
    initialFocusKey: Any?,
    onFocus: (SeasonSummary) -> Unit,
    onSeason: (SeasonSummary) -> Unit,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
) {
    val firstKey = seasons.firstOrNull()?.let { seasonFocusKey(it) }
    val targetKey = initialFocusKey?.takeIf { requested -> seasons.any { seasonFocusKey(it) == requested } } ?: firstKey
    var initialFocusPending by remember(firstKey, targetKey) { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier
                .widthIn(max = TvDetailMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Seasons", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${seasons.size} available", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        LazyRow(
            modifier = Modifier.widthIn(max = TvDetailMaxWidth).fillMaxWidth().height(204.dp),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rowItemsIndexed(seasons, key = { _, season -> seasonFocusKey(season) }) { _, season ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = initialFocusPending && targetKey != null && seasonFocusKey(season) == targetKey
                if (focusNow) {
                    LaunchedEffect(seasonFocusKey(season)) {
                        delay(220)
                        runCatching { focusRequester.requestFocus() }
                        initialFocusPending = false
                    }
                }
                Box(Modifier.width(104.dp).height(178.dp)) {
                    SeasonCard(
                        session = session,
                        season = season,
                        selected = selectedSeason?.seasonNumber == season.seasonNumber,
                        autoFocus = focusNow,
                        focusRequester = focusRequester,
                        onFocus = { onFocus(season) },
                        onClick = { onSeason(season) },
                        onUp = onUp,
                        onDown = onDown,
                    )
                }
            }
        }
    }
}

@Composable
fun SeasonCard(
    session: Session?,
    season: SeasonSummary,
    selected: Boolean = false,
    autoFocus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
) {
    CardShell(
        modifier = Modifier.fillMaxSize(),
        autoFocus = autoFocus,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onUp = onUp,
        onDown = onDown,
        focusScale = 1.015f,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            val url = imageUrl(session, season.posterItemId, "season", season.posterMtimeUnix)
            if (url.isNotBlank()) {
                SizedAsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    widthPx = 260,
                    heightPx = 390,
                    authToken = session?.token.orEmpty(),
                )
            } else {
                Text(seasonTitle(season).take(1), color = Muted, fontSize = 20.sp)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)))),
            )
            Text(
                seasonTitle(season),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 8.dp),
            )
            PosterCornerMarks(false, false, season.rating)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Accent.copy(alpha = .95f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("On", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
fun EpisodeStripe(
    session: Session?,
    episodes: List<PopItem>,
    completedEpisodes: Set<Long>,
    watchlistEpisodes: Set<Long>,
    initialEpisodeFocus: Long?,
    onFocus: (PopItem) -> Unit,
    onEpisode: (PopItem) -> Unit,
    onEpisodeMenu: (PopItem, FocusRequester) -> Unit,
    onUp: (() -> Boolean)? = null,
) {
    val firstId = episodes.firstOrNull()?.id
    val targetId = initialEpisodeFocus?.takeIf { id -> episodes.any { it.id == id } } ?: firstId
    var initialFocusPending by remember(firstId, targetId) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .widthIn(max = TvDetailMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Episodes", color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${episodes.size} episodes", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        LazyRow(
            modifier = Modifier.widthIn(max = TvDetailMaxWidth).fillMaxWidth().height(170.dp),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            rowItemsIndexed(episodes, key = { _, episode -> episode.id }) { _, episode ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = initialFocusPending && targetId != null && episode.id == targetId
                if (focusNow) {
                    LaunchedEffect(episode.id) {
                        delay(220)
                        runCatching { focusRequester.requestFocus() }
                        initialFocusPending = false
                    }
                }
                Box(Modifier.width(224.dp).height(158.dp)) {
                    EpisodeCard(
                        session = session,
                        item = episode,
                        watched = completedEpisodes.contains(episode.id),
                        watchlisted = watchlistEpisodes.contains(episode.id),
                        autoFocus = focusNow,
                        focusRequester = focusRequester,
                        onFocus = { onFocus(episode) },
                        onClick = { onEpisode(episode) },
                        onLongClick = { requester -> onEpisodeMenu(episode, requester) },
                        onUp = onUp,
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(
    session: Session?,
    item: PopItem,
    watched: Boolean,
    watchlisted: Boolean,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (FocusRequester) -> Unit,
    onUp: (() -> Boolean)? = null,
    onDown: (() -> Boolean)? = null,
) {
    CardShell(modifier = Modifier.fillMaxSize(), autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onUp = onUp, onDown = onDown, onClick = onClick, onLongClick = onLongClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            EpisodeStill(session, item, Modifier.fillMaxSize())
            Text(
                "%02d".format(item.episodeNumber),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = .7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            PosterCornerMarks(watched, watchlisted, item.rating, seenAtEnd = true)
        }
        Spacer(Modifier.height(4.dp))
        Text(item.episodeTitle.ifBlank { item.title }, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                "S%02dE%02d".format(item.seasonNumber, item.episodeNumber),
                fmtDuration(item.durationMs).ifBlank { null },
            ).joinToString(" · "),
            color = Muted,
            fontSize = 10.sp,
        )
        if (item.overview.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                item.overview,
                color = TextColor.copy(alpha = .62f),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EpisodeStill(session: Session?, item: PopItem, modifier: Modifier) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (session != null) {
            val url = if (item.backdropPath.isNotBlank()) {
                imageUrl(session, item.id, "backdrop", item.backdropMtimeUnix)
            } else if (item.posterPath.isNotBlank()) {
                imageUrl(session, item.id, "poster", item.posterMtimeUnix)
            } else {
                ""
            }
            if (url.isNotBlank()) {
                SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 360, heightPx = 204, authToken = session.token)
            } else {
                Text("%02d".format(item.episodeNumber), color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text("%02d".format(item.episodeNumber), color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SeasonPoster(session: Session?, season: SeasonSummary, modifier: Modifier) {
    val url = imageUrl(session, season.posterItemId, "season", season.posterMtimeUnix)
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            SizedAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, widthPx = 260, heightPx = 390, authToken = session?.token.orEmpty())
        } else {
            Text(seasonTitle(season).take(1), color = Muted, fontSize = 20.sp)
        }
    }
}

private fun seasonTitle(season: SeasonSummary): String {
    if (season.title.isNotBlank()) return season.title
    return if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"
}

fun seasonFocusKey(season: SeasonSummary): String = "${season.libraryId}:${season.showTitle}:${season.seasonNumber}"

private fun requestTvFocus(requester: FocusRequester): Boolean {
    return runCatching { requester.requestFocus() }.isSuccess
}

@Composable
private fun ThemeMusicPlayer(session: Session?, show: ShowSummary) {
    val context = LocalContext.current
    val url = remember(session?.server, show.libraryId, show.title) {
        themeUrl(session, show.libraryId, show.title)
    }
    val player = remember(url, session?.token) {
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            val token = session?.token.orEmpty()
            if (token.isNotBlank()) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            }
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0.28f
                prepare()
                playWhenReady = true
            }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose { player.release() }
    }
}
