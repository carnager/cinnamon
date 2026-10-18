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
    val channels: Int = 0,
    val profile: String = "",
) {
    // "Deutsch · Forced" — what a viewer picks a track by.
    fun primaryLabel(): String {
        val lang = languageDisplayName(language)
        val parts = mutableListOf<String>()
        if (lang.isNotBlank()) parts.add(lang)
        // Release titles often just repeat the language ("German", "GER - SUBS").
        // ...or the format shown on the second line ("DTS" beside "DTS 5.1").
        val format = friendlyCodec(codec, profile)
        if (title.isNotBlank() && !title.contains(lang, ignoreCase = true) && !title.equals(language, ignoreCase = true) &&
            !format.contains(title, ignoreCase = true) && !title.equals(codec, ignoreCase = true)
        ) parts.add(title)
        if (forced && parts.none { it.contains("forced", ignoreCase = true) }) parts.add("Forced")
        return parts.joinToString(" \u00b7 ").ifBlank { "Track $index" }
    }

    // "TrueHD 7.1 · Default" — format details, secondary.
    fun detailLabel(): String {
        val parts = mutableListOf<String>()
        val format = friendlyCodec(codec, profile)
        if (format.isNotBlank()) parts.add(if (type == "audio" && channels > 0) "$format ${channelLabel(channels)}" else format)
        if (default) parts.add("Default")
        return parts.joinToString(" \u00b7 ")
    }

    fun label(): String = listOf(primaryLabel(), detailLabel()).filter { it.isNotBlank() }.joinToString(" \u00b7 ")

    // Compact form for the detail page's selector buttons: no "Default".
    fun shortLabel(): String = listOf(primaryLabel(), detailLabel().split(" \u00b7 ").filterNot { it == "Default" }.joinToString(" \u00b7 "))
        .filter { it.isNotBlank() }.joinToString(" \u00b7 ")
}

private fun languageDisplayName(code: String): String {
    val trimmed = code.trim()
    if (trimmed.isBlank() || trimmed.equals("und", ignoreCase = true)) return ""
    val name = runCatching { java.util.Locale.forLanguageTag(normalizeLang(trimmed)).getDisplayLanguage(java.util.Locale.getDefault()) }.getOrNull().orEmpty()
    return if (name.isBlank() || name.equals(trimmed, ignoreCase = true)) trimmed.uppercase() else name.replaceFirstChar { it.titlecase() }
}

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

private fun friendlyCodec(codec: String, profile: String): String = when (codec.lowercase()) {
    "ac3" -> "Dolby Digital"
    "eac3" -> if (profile.contains("atmos", ignoreCase = true)) "Dolby Digital+ Atmos" else "Dolby Digital+"
    "truehd" -> if (profile.contains("atmos", ignoreCase = true)) "TrueHD Atmos" else "TrueHD"
    "dts" -> when {
        profile.contains("MA", ignoreCase = false) -> "DTS-HD MA"
        profile.contains("HRA", ignoreCase = false) || profile.contains("HD", ignoreCase = false) -> "DTS-HD"
        else -> "DTS"
    }
    "aac" -> "AAC"
    "opus" -> "Opus"
    "flac" -> "FLAC"
    "mp3" -> "MP3"
    "vorbis" -> "Vorbis"
    "subrip", "srt" -> "SRT"
    "ass", "ssa" -> "ASS"
    "hdmv_pgs_subtitle" -> "PGS"
    "dvd_subtitle" -> "VobSub"
    "dvb_subtitle" -> "DVB"
    "webvtt" -> "WebVTT"
    "mov_text" -> "Text"
    else -> codec.uppercase()
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
data class Recommendation(val key: String, val reason: String, val source: String, val item: PopItem?, val show: ShowSummary?)
data class RecommendationExclusion(
    val key: String,
    val kind: String,
    val item: PopItem?,
    val show: ShowSummary?,
    val path: String,
    val sizeBytes: Long,
)
enum class MediaPreference { Unwatched, Seen, NotInterested }
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
    val recommendations: List<Recommendation>,
    val excludedRecommendationKeys: Set<String>,
    val sections: List<HomeSection>,
    // The raw sections array, kept verbatim so the offline cache can replay the
    // payload through the same parser instead of a second serializer that would
    // drift from it.
    val sectionsJson: String,
)

// A home shelf as composed by the server. The client switches on layout, never
// on type, so section types added server-side render without an app change and
// unknown layouts are skipped.
data class HomeSection(
    val id: String,
    val type: String,
    val layout: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val more: String,
    // The parameters the shelf was built from, so a client can reproduce it.
    val params: Map<String, String>,
    val items: List<PopItem>,
    val shows: List<ShowSummary>,
    val entries: List<Recommendation>,
)
// The layout editor's vocabulary: what shelves exist (catalog) and which ones
// this account has chosen, in order.
data class HomeSectionType(
    val type: String,
    val label: String,
    val description: String,
    val layout: String,
    val kind: String,
    val repeatable: Boolean,
    val params: List<HomeSectionParam>,
)
data class HomeSectionParam(
    val name: String,
    val label: String,
    val type: String,
    val options: List<String>,
    // Values a picker should offer for a number, and what they are measured in.
    val choices: List<String>,
    val suffix: String,
    // A comma-separated list where any value matches, not a single choice.
    val multi: Boolean,
    // The companion parameter holding this dimension's any/all mode, and
    // whether this parameter is that companion (shown inside the picker).
    val matchParam: String,
    val hidden: Boolean,
    val default: String,
    val required: Boolean,
)
data class HomeLayoutSection(
    val id: String,
    val type: String,
    val title: String = "",
    val enabled: Boolean = true,
    val params: Map<String, String> = emptyMap(),
)
data class HomeLayout(val source: String, val sections: List<HomeLayoutSection>)

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
    data object ArrangeHome : Screen
    data class LibraryPage(val library: Library) : Screen
    data class ItemShelf(val title: String, val items: List<PopItem>, val returnTo: Screen? = null) : Screen
    data object Search : Screen
    data class Show(val show: ShowSummary, val fromHome: Boolean = false, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false, val fromActor: dev.popcorn.tv.Actor? = null) : Screen
    data class Detail(val item: PopItem, val fromShow: ShowSummary?, val fromHome: Boolean = false, val fromSearch: Boolean = false, val fromWatchlist: Boolean = false, val fromHistory: Boolean = false, val fromActor: dev.popcorn.tv.Actor? = null, val fromShelf: ItemShelf? = null) : Screen
    data class Actor(val actor: dev.popcorn.tv.Actor) : Screen
    // token distinguishes two requests to play the same item. The player is
    // keyed on it, so a remote "play" for whatever is already on screen
    // restarts it instead of quietly reusing the running one.
    // tracksChosen: the viewer picked audio/subtitles (detail page, phone remote).
    // Otherwise — Up Next, Continue Watching, a show's play button — the player
    // applies the stored language preferences itself.
    data class Player(val item: PopItem, val audioIndex: Int?, val subtitleIndex: Int?, val startPositionMs: Long = 0L, val token: Long = 0L, val tracksChosen: Boolean = false) : Screen
    data class SidecarPlayer(val url: String, val title: String, val returnScreen: Screen) : Screen
}
