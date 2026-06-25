package dev.popcorn.companion

data class Session(val server: String, val token: String, val username: String = "")
data class Library(val id: String, val name: String, val type: String)
data class Device(val id: String, val name: String, val kind: String)
data class PlayerState(val itemId: Long, val title: String, val state: String, val positionMs: Long, val durationMs: Long)
data class PlaybackProgress(val itemId: Long, val positionMs: Long, val durationMs: Long, val completed: Boolean)
data class ShowProgress(val libraryId: String, val showTitle: String, val episodeCount: Int, val completedCount: Int, val completed: Boolean)
data class Watchlist(val items: List<PopItem>, val shows: List<ShowSummary>)
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
)

data class Actor(val name: String, val role: String, val thumb: String)
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
)

data class LibraryFilters(
    val genre: String = "",
    val minRating: Double = 0.0,
    val sort: String = "",
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
    fun label(): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add(title)
        if (language.isNotBlank()) parts.add(language.uppercase())
        if (codec.isNotBlank()) parts.add(codec)
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
    data class Show(val show: ShowSummary) : Page
    data class Season(val show: ShowSummary, val season: SeasonSummary) : Page
    data class Detail(val item: PopItem, val from: Page) : Page
    data class LocalPlayer(val item: PopItem, val audioIndex: Int?, val subtitleIndex: Int?, val from: Page) : Page
}
