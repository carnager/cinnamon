package dev.popcorn.tv

import org.json.JSONObject

data class StreamInfo(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String,
    val title: String,
    val default: Boolean,
    val forced: Boolean,
) {
    fun label(): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add(title)
        if (language.isNotBlank()) parts.add(language.uppercase())
        if (codec.isNotBlank()) parts.add(codec)
        if (forced) parts.add("(forced)")
        return parts.joinToString(" \u2022 ").ifBlank { "Track ${index}" }
    }
}

data class BandwidthOption(val label: String, val kbps: Int?)

val BandwidthOptions = listOf(
    BandwidthOption("Direct", null),
    BandwidthOption("1.5 mbit", 1500),
    BandwidthOption("3 mbit", 3000),
    BandwidthOption("5 mbit", 5000),
    BandwidthOption("8 mbit", 8000),
    BandwidthOption("10 mbit", 10000),
    BandwidthOption("15 mbit", 15000),
)

data class Session(
    val server: String,
    val token: String,
    val username: String = "",
    val isAdmin: Boolean = false,
    val userId: Long = 0,
    val avatar: String = "",
    val displayName: String = "",
)
data class User(val id: Long, val username: String, val displayName: String, val isAdmin: Boolean, val avatar: String = "")
data class Library(val id: String, val name: String, val type: String)

data class PlaybackPlan(
    val planId: String,
    val mode: String,
    val playable: Boolean,
    val url: String,
    val sessionId: String,
    val startPositionMs: Long,
    val durationMs: Long,
    val selectedAudioIndex: Int?,
    val selectedSubtitleIndex: Int?,
    val reasons: List<String>,
) {
    val usesHls: Boolean get() = mode != "direct"
}

data class AlphabetEntry(val letter: String, val offset: Int, val count: Int)

data class SidecarStatus(
    val trailer: Boolean = false,
    val theme: Boolean = false,
)
data class ScanStatus(
    val libraryId: String,
    val status: String,
    val finishedAt: String,
    val mediaFound: Long,
    val itemsImported: Long,
    val errors: Long,
)

data class ShowSummary(
    val libraryId: String,
    val title: String,
    val episodeCount: Int,
    val seasonCount: Int,
    val posterItemId: Long,
    val posterMtimeUnix: Long,
    val backdropItemId: Long,
    val backdropMtimeUnix: Long,
    val overview: String,
    val genres: String,
    val rating: Double,
    val year: Int = 0,
    val endYear: Int = 0,
)

// yearsLabel renders a show's run as "2019" or "2009–2012"; empty when unknown.
fun ShowSummary.yearsLabel(): String = when {
    year <= 0 -> ""
    endYear > year -> "$year–$endYear"
    else -> "$year"
}

data class SeasonSummary(
    val libraryId: String,
    val showTitle: String,
    val seasonNumber: Int,
    val title: String,
    val episodeCount: Int,
    val durationMs: Long,
    val posterItemId: Long,
    val posterMtimeUnix: Long,
    val backdropItemId: Long,
    val overview: String,
    val rating: Double,
)

data class PlaybackProgress(val itemId: Long, val positionMs: Long, val durationMs: Long, val completed: Boolean)
data class ShowProgress(val libraryId: String, val showTitle: String, val episodeCount: Int, val completedCount: Int, val completed: Boolean)
data class Watchlist(val items: List<PopItem>, val shows: List<ShowSummary>)
data class WatchHistoryEntry(
    val id: String,
    val item: PopItem?,
    val kind: String,
    val title: String,
    val subtitle: String,
    val year: Int,
    val watchedAt: String,
    val source: String,
)
data class WatchHistory(val items: List<WatchHistoryEntry>, val source: String, val traktLinked: Boolean)
data class UserRatingRow(val kind: String, val itemId: Long, val libraryId: String, val showTitle: String, val rating: Int)
data class HomePayload(
    val user: User,
    val libraries: List<Library>,
    val homeMovies: List<PopItem>,
    val homeShows: List<ShowSummary>,
    val recentMovies: List<PopItem>,
    val recentShows: List<ShowSummary>,
    val continueMovies: List<PopItem>,
    val continueEpisodes: List<PopItem>,
    val progress: List<PlaybackProgress>,
    val showProgress: List<ShowProgress>,
    val watchlist: Watchlist,
)
data class RemoteCommand(val id: Long, val type: String, val payload: JSONObject)
data class PlayerRemoteCommand(val id: Long, val type: String, val payload: JSONObject)
data class QRLoginStart(val code: String, val expiresAt: String)

data class ExternalRatings(
    val imdbId: String,
    val tmdbId: String,
    val localRating: Double,
    val imdbRating: Double,
    val tmdbRating: Double,
    val rottenTomatoesRating: Int,
    val metacriticRating: Int,
)

data class Actor(
    val name: String,
    val role: String,
    val thumb: String,
)

data class ActorInfo(
    val name: String,
    val tmdbId: String,
    val imdbId: String,
    val biography: String,
    val birthday: String,
    val deathday: String,
    val placeOfBirth: String,
    val knownForDepartment: String,
    val profilePath: String,
    val source: String,
)

data class ActorDetail(
    val actor: Actor,
    val info: ActorInfo,
    val profileUrl: String,
    val movies: List<PopItem>,
    val shows: List<ShowSummary>,
)

data class PopItem(
    val id: Long,
    val libraryId: String,
    val kind: String,
    val title: String,
    val originalTitle: String,
    val year: Int,
    val durationMs: Long,
    val videoCodec: String,
    val audioCodec: String,
    val imdbId: String,
    val tmdbId: String,
    val tvdbId: String,
    val width: Int,
    val height: Int,
    val posterPath: String,
    val posterMtimeUnix: Long,
    val backdropPath: String,
    val backdropMtimeUnix: Long,
    val overview: String,
    val tagline: String,
    val officialRating: String,
    val genres: String,
    val tags: String,
    val studios: String,
    val directors: String,
    val writers: String,
    val countries: String,
    val premiered: String,
    val rating: Double,
    val showTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val actors: List<Actor> = emptyList(),
)

data class CachedList<T>(val entries: List<T>, val fullyLoaded: Boolean)
data class AppUpdateInfo(
    val configured: Boolean,
    val available: Boolean,
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val error: String,
)

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data object Home : Screen
    data object Watchlist : Screen
    data object History : Screen
    data object Updates : Screen
    data class LibraryPage(val library: Library) : Screen
    data class ItemShelf(val title: String, val items: List<PopItem>, val returnTo: Screen? = null) : Screen
    data object Search : Screen
    data class Show(val show: ShowSummary, val fromHome: Boolean = false, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false, val fromActor: dev.popcorn.tv.Actor? = null) : Screen
    data class Season(val show: ShowSummary, val season: SeasonSummary, val fromHome: Boolean = false, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false, val fromActor: dev.popcorn.tv.Actor? = null) : Screen
    data class Detail(val item: PopItem, val fromShow: ShowSummary?, val fromHome: Boolean = false, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false, val fromHistory: Boolean = false, val fromActor: dev.popcorn.tv.Actor? = null) : Screen
    data class Actor(val actor: dev.popcorn.tv.Actor) : Screen
    // token distinguishes two requests to play the same item. The player is
    // keyed on it, so a remote "play" for whatever is already on screen
    // restarts it instead of quietly reusing the running one.
    data class Player(val item: PopItem, val audioIndex: Int?, val subtitleIndex: Int?, val startPositionMs: Long = 0L, val token: Long = 0L) : Screen
    data class SidecarPlayer(val url: String, val title: String, val returnScreen: Screen) : Screen
}
