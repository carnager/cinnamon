package dev.popcorn.companion

data class Session(
    val server: String,
    val token: String,
    val username: String = "",
    val isAdmin: Boolean = false,
    val userId: Long = 0,
    val avatar: String = "",
    val displayName: String = "",
)
data class Library(val id: String, val name: String, val type: String)
data class Device(val id: String, val name: String, val kind: String)
data class PlayerState(val itemId: Long, val title: String, val state: String, val positionMs: Long, val durationMs: Long)
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
data class HomeContinue(val movies: List<PopItem>, val episodes: List<PopItem>, val resume: Map<Long, Float>)
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

enum class PlaybackTarget { Shield, Phone }

data class PhonePlaybackState(
    val item: PopItem? = null,
    val state: String = "idle",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bandwidthKbps: Int? = null,
)

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
) {
    val usesHls: Boolean get() = mode != "direct"
}

// Mirrors progressFinished/progressResumable in the TV app (TvCore.kt) and the
// server's isFinished. The completed flag can't decide this on its own: a
// re-watch resets the position but leaves completed = true, so only the saved
// position is consulted — near the end means finished, a meaningful mid-point
// means offer to resume.
fun progressFinished(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0 || positionMs <= 0) return false
    return durationMs - positionMs <= 90_000 || positionMs.toDouble() / durationMs.toDouble() >= 0.92
}

fun progressResumable(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0 || positionMs < 30_000) return false
    return !progressFinished(positionMs, durationMs)
}

fun resumeFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

fun PlayerState.isActivePlayback(): Boolean {
    val normalized = state.lowercase()
    return itemId > 0 && normalized != "idle" && normalized != "stopped"
}

data class PopItem(
    val id: Long,
    val libraryId: String,
    val kind: String,
    val title: String,
    val year: Int,
    val durationMs: Long,
    val posterMtimeUnix: Long,
    val backdropMtimeUnix: Long,
    val overview: String,
    val genres: String,
    val rating: Double,
    val imdbId: String,
    val tmdbId: String,
    val showTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val actors: List<Actor> = emptyList(),
    val originalTitle: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val tvdbId: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val posterPath: String = "",
    val backdropPath: String = "",
    val tagline: String = "",
    val officialRating: String = "",
    val tags: String = "",
    val studios: String = "",
    val directors: String = "",
    val writers: String = "",
    val countries: String = "",
    val premiered: String = "",
)

data class Actor(val name: String, val role: String, val thumb: String)
data class ActorInfo(
    val biography: String,
    val birthday: String,
    val placeOfBirth: String,
    val knownForDepartment: String,
)
data class ActorDetail(
    val actor: Actor,
    val info: ActorInfo,
    val profileUrl: String,
    val movies: List<PopItem>,
    val shows: List<ShowSummary>,
)
data class SidecarStatus(val trailer: Boolean = false, val theme: Boolean = false)

data class ShowSummary(
    val libraryId: String,
    val title: String,
    val episodeCount: Int,
    val seasonCount: Int,
    val posterItemId: Long,
    val posterMtimeUnix: Long,
    val overview: String,
    val genres: String,
    val rating: Double,
    val year: Int = 0,
    val endYear: Int = 0,
    val backdropItemId: Long = 0,
    val backdropMtimeUnix: Long = 0,
)

// yearsLabel renders a show's run as "2019" or "2009–2012"; empty when unknown.
fun ShowSummary.yearsLabel(): String = when {
    year <= 0 -> ""
    endYear > year -> "$year–$endYear"
    else -> "$year"
}

data class LibraryFilters(
    val genre: String = "",
    val minRating: Double = 0.0,
    val sort: String = "",
    val seenStatus: String = "",
    val decades: String = "",
)

// Whether a library view should reserve the gutter for the alphabet rail.
// This has to be answerable before the alphabet itself arrives: deciding from
// the loaded list instead re-flows the grid mid-load (three columns across,
// then two) as the widened padding squeezes out an adaptive column. The
// condition mirrors the one MainActivity uses to decide whether to request an
// alphabet at all, so it is known from the first frame.
fun LibraryFilters.reservesAlphabetRail(): Boolean =
    sort.isBlank() && seenStatus.isBlank() && minRating <= 0

data class AlphabetEntry(
    val letter: String,
    val offset: Int,
    val count: Int,
)

data class ExternalRatings(
    val imdbId: String,
    val tmdbId: String,
    val imdbRating: Double,
    val tmdbRating: Double,
    val rottenTomatoesRating: Int,
    val metacriticRating: Int,
)

data class SeasonSummary(
    val libraryId: String,
    val showTitle: String,
    val seasonNumber: Int,
    val title: String,
    val episodeCount: Int,
    val posterItemId: Long,
    val posterMtimeUnix: Long,
    val overview: String,
)

data class ScannedQr(val type: String, val server: String = "", val code: String = "", val callback: String = "")
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

data class StreamInfo(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String,
    val title: String,
    val default: Boolean,
    val forced: Boolean,
) {
    // Track titles usually already name the language ("German", "Deutsch
    // 5.1"), so a naive title + language + codec join reads "German · GER ·
    // eac3". Drop any part the earlier ones already say.
    fun label(): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add(title.trim())
        val lang = language.trim()
        if (lang.isNotBlank() && parts.none { it.contains(lang, ignoreCase = true) }) parts.add(lang.uppercase())
        val audioCodec = codec.trim()
        if (audioCodec.isNotBlank() && parts.none { it.contains(audioCodec, ignoreCase = true) }) parts.add(audioCodec.uppercase())
        if (forced) parts.add("forced")
        return parts.joinToString(" · ").ifBlank { "Track $index" }
    }
}

sealed interface Page {
    data object Home : Page
    data object Movies : Page
    data object Shows : Page
    data object Search : Page
    data object Remote : Page
    data object History : Page
    data class Person(val actor: Actor) : Page
    data class Show(val show: ShowSummary) : Page
    data class Season(val show: ShowSummary, val season: SeasonSummary) : Page
    data class Detail(val item: PopItem, val from: Page) : Page
    data class LocalPlayer(val item: PopItem, val audioIndex: Int?, val subtitleIndex: Int?, val from: Page) : Page
}
