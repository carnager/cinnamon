package dev.popcorn.tv

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch

@Composable
fun ShowView(
    session: Session?,
    show: ShowSummary,
    initialSeasonFocus: Int?,
    initialEpisodeFocus: Long?,
    startWithEpisodes: Boolean,
    completedItems: Set<Long>,
    watchlistItems: Set<Long>,
    refreshToken: Long,
    onSeason: (SeasonSummary) -> Unit,
    onEpisodeFocus: (PopItem) -> Unit,
    onEpisode: (PopItem) -> Unit,
    onEpisodeMenu: (PopItem, FocusRequester) -> Unit,
    onActor: (Actor) -> Unit,
) {
    val seasons = remember { mutableStateListOf<SeasonSummary>() }
    val episodes = remember { mutableStateListOf<PopItem>() }
    val showActors = remember { mutableStateListOf<Actor>() }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var episodesLoading by remember { mutableStateOf(false) }
    var selectedSeason by remember(show.libraryId, show.title) { mutableStateOf<SeasonSummary?>(null) }
    var focusedSeason by remember(show.libraryId, show.title) { mutableStateOf<SeasonSummary?>(null) }
    var focusedEpisode by remember(show.libraryId, show.title) { mutableStateOf<PopItem?>(null) }
    var showingEpisodes by remember(show.libraryId, show.title) { mutableStateOf(startWithEpisodes) }
    var themeAvailable by remember(show.libraryId, show.title) { mutableStateOf(false) }
    var fullTextOpen by remember(show.libraryId, show.title) { mutableStateOf(false) }
    val descriptionFocus = remember(show.libraryId, show.title) { FocusRequester() }
    val castFocus = remember(show.libraryId, show.title) { FocusRequester() }
    val bodyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(show.libraryId, show.title, session) {
        val active = session ?: return@LaunchedEffect
        themeAvailable = false
        runCatching { Api(active).showTheme(show.libraryId, show.title) }
            .onSuccess { themeAvailable = it.theme }
        runCatching { Api(active).showActors(show.libraryId, show.title) }
            .onSuccess {
                showActors.clear()
                showActors.addAll(it)
            }
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
                if (!showingEpisodes) focusedEpisode = null
            }
            .onFailure { error = it.message ?: "Failed to load seasons" }
        loading = false
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
                focusedEpisode = if (showingEpisodes) {
                    it.firstOrNull { episode -> episode.id == initialEpisodeFocus } ?: it.firstOrNull()
                } else {
                    null
                }
            }
            .onFailure { error = it.message ?: "Failed to load episodes" }
        episodesLoading = false
    }

    BackHandler(enabled = showingEpisodes && !startWithEpisodes) {
        showingEpisodes = false
        focusedEpisode = null
    }

    if (themeAvailable) {
        ThemeMusicPlayer(session = session, show = show)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        ShowHeader(
            session = session,
            show = show,
            focusedSeason = focusedSeason,
            focusedEpisode = focusedEpisode.takeIf { showingEpisodes },
            descriptionFocus = descriptionFocus,
            onFullText = { fullTextOpen = true },
        )

        if (error.isNotBlank()) {
            Text(error, color = ErrorRed, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp), fontSize = 12.sp)
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            if (!showingEpisodes) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = bodyListState,
                    contentPadding = PaddingValues(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item {
                        SeasonStripe(
                            session = session,
                            seasons = seasons,
                            selectedSeason = selectedSeason,
                            initialFocusKey = initialSeasonFocus?.let { "${show.libraryId}:${show.title}:$it" },
                            onFocus = {
                                focusedSeason = it
                                focusedEpisode = null
                            },
                            onSeason = {
                                selectedSeason = it
                                focusedSeason = it
                                focusedEpisode = null
                                showingEpisodes = true
                                onSeason(it)
                            },
                            onUp = { requestTvFocus(descriptionFocus) },
                            onDown = {
                                if (showActors.isEmpty()) {
                                    false
                                } else {
                                    scope.launch {
                                        bodyListState.animateScrollToItem(1)
                                        delay(80)
                                        requestTvFocus(castFocus)
                                    }
                                    true
                                }
                            },
                        )
                    }
                    if (showActors.isNotEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CastStrip(
                                    session = session,
                                    actors = showActors,
                                    firstFocusRequester = castFocus,
                                    onActor = onActor,
                                )
                            }
                        }
                    }
                }
            } else if (episodesLoading && episodes.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            } else {
                EpisodeStripe(
                    session = session,
                    episodes = episodes,
                    completedEpisodes = completedItems,
                    watchlistEpisodes = watchlistItems,
                    initialEpisodeFocus = initialEpisodeFocus,
                    onFocus = {
                        focusedEpisode = it
                        onEpisodeFocus(it)
                    },
                    onEpisode = {
                        focusedEpisode = it
                        onEpisodeFocus(it)
                        onEpisode(it)
                    },
                    onEpisodeMenu = onEpisodeMenu,
                    onUp = { requestTvFocus(descriptionFocus) },
                )
            }
        }
    }

    if (fullTextOpen) {
        val activeEpisode = focusedEpisode.takeIf { showingEpisodes }
        ShowFullTextDialog(
            title = activeEpisode?.episodeTitle?.ifBlank { activeEpisode.title } ?: show.title,
            subtitle = if (activeEpisode != null) {
                "S%02dE%02d".format(activeEpisode.seasonNumber, activeEpisode.episodeNumber)
            } else {
                focusedSeason?.let { seasonTitle(it) }.orEmpty()
            },
            overview = activeEpisode?.overview?.takeIf { it.isNotBlank() } ?: show.overview,
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
    onFullText: () -> Unit = {},
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

    Box(Modifier.fillMaxWidth().height(300.dp)) {
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

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = TvDetailMaxWidth)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(session, show.posterItemId, Modifier.width(116.dp), show.posterMtimeUnix)

            Column(Modifier.weight(1f)) {
                Text(
                    show.title,
                    color = Color.White,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 42.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(44.dp),
                )

                Text(
                    episodeTitle.ifBlank { focusedSeason?.let { seasonTitle(it) }.orEmpty() },
                    color = Accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(26.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(26.dp),
                ) {
                    if (meta != null) {
                        Text(meta, color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        show.yearsLabel().takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Muted, fontSize = 15.sp)
                        }
                        Text("${show.seasonCount} seasons", color = Muted, fontSize = 15.sp)
                        Text("${show.episodeCount} episodes", color = Muted, fontSize = 15.sp)
                    }
                    if (rating > 0) RatingBadge(rating)
                }

                Text(
                    show.genres,
                    color = Accent.copy(alpha = .90f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(22.dp),
                )

                FocusableShowDescription(
                    overview = overview,
                    focusRequester = descriptionFocus,
                    onClick = onFullText,
                )
            }
        }
    }
}

@Composable
private fun FocusableShowDescription(
    overview: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val text = overview.ifBlank { "No description available." }
    Column(
        Modifier
            .height(66.dp)
            .widthIn(max = 680.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.Black.copy(alpha = .48f) else Color.Black.copy(alpha = .16f))
            .border(2.dp, if (focused) FocusGlow else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .tvActivate(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = .78f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
            if (season.rating > 0) PosterRating(season.rating)
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
) {
    CardShell(modifier = Modifier.fillMaxSize(), autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onUp = onUp, onClick = onClick, onLongClick = onLongClick) {
        Box {
            EpisodeStill(session, item, Modifier.fillMaxWidth())
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
            if (item.rating > 0) PosterRating(item.rating)
            PosterStatusBadges(watched, watchlisted)
        }
        Spacer(Modifier.height(4.dp))
        Text(item.episodeTitle.ifBlank { item.title }, color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("S%02dE%02d".format(item.seasonNumber, item.episodeNumber), color = Muted, fontSize = 10.sp)
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
