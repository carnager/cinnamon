package dev.popcorn.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay

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
) {
    val seasons = remember { mutableStateListOf<SeasonSummary>() }
    val episodes = remember { mutableStateListOf<PopItem>() }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var episodesLoading by remember { mutableStateOf(false) }
    var selectedSeason by remember(show.libraryId, show.title) { mutableStateOf<SeasonSummary?>(null) }
    var focusedSeason by remember(show.libraryId, show.title) { mutableStateOf<SeasonSummary?>(null) }
    var focusedEpisode by remember(show.libraryId, show.title) { mutableStateOf<PopItem?>(null) }
    var showingEpisodes by remember(show.libraryId, show.title) { mutableStateOf(startWithEpisodes) }
    var themeAvailable by remember(show.libraryId, show.title) { mutableStateOf(false) }

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
                )
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
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun ShowHeader(session: Session?, show: ShowSummary, focusedSeason: SeasonSummary?, focusedEpisode: PopItem?) {
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

    Box(Modifier.fillMaxWidth().height(250.dp)) {
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
                .padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Poster(session, show.posterItemId, Modifier.width(100.dp), show.posterMtimeUnix)

            Column(Modifier.weight(1f)) {
                Text(
                    show.title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(32.dp),
                )

                Text(
                    episodeTitle.ifBlank { focusedSeason?.let { seasonTitle(it) }.orEmpty() },
                    color = Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(22.dp),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(22.dp),
                ) {
                    if (meta != null) {
                        Text(meta, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("${show.seasonCount} seasons", color = Muted, fontSize = 12.sp)
                        Text("${show.episodeCount} episodes", color = Muted, fontSize = 12.sp)
                    }
                    if (rating > 0) RatingBadge(rating)
                }

                Text(
                    show.genres,
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(18.dp),
                )

                Text(
                    overview,
                    color = Color.White.copy(alpha = .70f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(36.dp),
                )
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
            modifier = Modifier.widthIn(max = TvDetailMaxWidth).fillMaxWidth().height(220.dp),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rowItemsIndexed(seasons, key = { _, season -> seasonFocusKey(season) }) { _, season ->
                val focusRequester = remember { FocusRequester() }
                val focusNow = initialFocusPending && targetKey != null && seasonFocusKey(season) == targetKey
                if (focusNow) {
                    LaunchedEffect(seasonFocusKey(season)) {
                        delay(180)
                        initialFocusPending = false
                    }
                }
                Box(Modifier.width(122.dp).height(210.dp)) {
                    SeasonCard(
                        session = session,
                        season = season,
                        selected = selectedSeason?.seasonNumber == season.seasonNumber,
                        autoFocus = focusNow,
                        focusRequester = focusRequester,
                        onFocus = { onFocus(season) },
                        onClick = { onSeason(season) },
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
) {
    CardShell(modifier = Modifier.fillMaxSize(), autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onClick = onClick) {
        Box {
            SeasonPoster(session, season, Modifier.fillMaxWidth())
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
        Spacer(Modifier.height(4.dp))
        Text(seasonTitle(season), color = TextColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${season.episodeCount} episodes", color = Muted, fontSize = 10.sp)
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
                        delay(180)
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
) {
    CardShell(modifier = Modifier.fillMaxSize(), autoFocus = autoFocus, focusRequester = focusRequester, onFocus = onFocus, onClick = onClick, onLongClick = onLongClick) {
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
            if (watched) SeenBadge()
            if (watchlisted) WatchlistBadge()
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
