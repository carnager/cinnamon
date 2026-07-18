package dev.popcorn.companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

object CompanionCache {
    fun readLibraries(context: Context, session: Session): List<Library> {
        return runCatching {
            val arr = JSONArray(cacheFile(context, session, "libraries").readText())
            (0 until arr.length()).map { jsonToLibrary(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun writeLibraries(context: Context, session: Session, libraries: List<Library>) {
        runCatching {
            val arr = JSONArray()
            libraries.forEach { arr.put(libraryToJson(it)) }
            cacheFile(context, session, "libraries").writeText(arr.toString())
        }
    }

    fun readItems(context: Context, session: Session, key: String): List<PopItem> {
        return runCatching {
            val arr = JSONArray(cacheFile(context, session, key).readText())
            (0 until arr.length()).map { jsonToItem(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun writeItems(context: Context, session: Session, key: String, items: List<PopItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { arr.put(itemToJson(it)) }
            cacheFile(context, session, key).writeText(arr.toString())
        }
    }

    fun readShows(context: Context, session: Session, key: String): List<ShowSummary> {
        return runCatching {
            val arr = JSONArray(cacheFile(context, session, key).readText())
            (0 until arr.length()).map { jsonToShow(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun writeShows(context: Context, session: Session, key: String, shows: List<ShowSummary>) {
        runCatching {
            val arr = JSONArray()
            shows.forEach { arr.put(showToJson(it)) }
            cacheFile(context, session, key).writeText(arr.toString())
        }
    }

    fun readSeasons(context: Context, session: Session, key: String): List<SeasonSummary> {
        return runCatching {
            val arr = JSONArray(cacheFile(context, session, key).readText())
            (0 until arr.length()).map { jsonToSeason(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun writeSeasons(context: Context, session: Session, key: String, seasons: List<SeasonSummary>) {
        runCatching {
            val arr = JSONArray()
            seasons.forEach { arr.put(seasonToJson(it)) }
            cacheFile(context, session, key).writeText(arr.toString())
        }
    }

    private fun cacheFile(context: Context, session: Session, key: String): File {
        val serverKey = session.server.fold(0) { acc, c -> acc * 31 + c.code }.toString()
        val dir = File(context.filesDir, "popcorn-companion-cache/$serverKey").apply { mkdirs() }
        return File(dir, "${key.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
    }
}

fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

class Api(private val session: Session) {
    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        val json = request("/api/auth/login", "POST", JSONObject().put("username", username).put("password", password).toString())
        val user = json.optJSONObject("user")
        Session(session.server, json.getString("token"), user?.optString("username").orEmpty())
    }

    suspend fun completeQr(code: String) = withContext(Dispatchers.IO) {
        request("/api/auth/qr/complete", "POST", JSONObject().put("code", code).toString())
    }

    suspend fun companionUpdate(currentVersionCode: Int): AppUpdateInfo = withContext(Dispatchers.IO) {
        val json = request("/api/app/companion/update?versionCode=$currentVersionCode")
        AppUpdateInfo(
            configured = json.optBoolean("configured", false),
            available = json.optBoolean("available", false),
            versionCode = json.optInt("versionCode"),
            versionName = json.optString("versionName"),
            notes = json.optString("notes"),
            apkUrl = json.optString("apkUrl"),
            sha256 = json.optString("sha256"),
            sizeBytes = json.optLong("sizeBytes"),
            error = json.optString("error"),
        )
    }

    suspend fun downloadCompanionUpdate(info: AppUpdateInfo, outFile: File): File = withContext(Dispatchers.IO) {
        outFile.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(session.server + info.apkUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 120000
        if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error(text.ifBlank { "HTTP $code" })
        }
        conn.inputStream.use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        val expected = info.sha256.trim().lowercase()
        if (expected.isNotBlank()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                outFile.delete()
                error("APK checksum mismatch")
            }
        }
        outFile
    }

    suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/libraries")
        (0 until arr.length()).map { jsonToLibrary(arr.getJSONObject(it)) }
    }

    suspend fun devices(): List<Device> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/devices")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Device(o.getString("id"), o.optString("name"), o.optString("kind"))
        }
    }

    suspend fun deviceState(deviceId: String): PlayerState = withContext(Dispatchers.IO) {
        val o = request("/api/devices/${enc(deviceId)}/state")
        PlayerState(o.optLong("itemId"), o.optString("title"), o.optString("state", "idle"), o.optLong("positionMs"), o.optLong("durationMs"))
    }

    suspend fun sendCommand(deviceId: String, type: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        requestText("/api/devices/${enc(deviceId)}/commands", "POST", JSONObject().put("type", type).put("payload", payload).toString())
    }

    suspend fun itemsPage(libraryId: String, limit: Int, offset: Int, filters: LibraryFilters = LibraryFilters()): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset${filterParams(filters)}"))
    }

    suspend fun recentItems(libraryId: String, limit: Int): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&sort=mtime"))
    }

    suspend fun showsPage(libraryId: String, limit: Int, offset: Int, filters: LibraryFilters = LibraryFilters()): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset${filterParams(filters)}"))
    }

    suspend fun recentShows(libraryId: String, limit: Int): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&sort=mtime"))
    }

    suspend fun genres(libraryId: String): List<String> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/genres?libraryId=${enc(libraryId)}")
        (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun seasons(libraryId: String, showTitle: String): List<SeasonSummary> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/seasons?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SeasonSummary(o.getString("libraryId"), o.getString("showTitle"), o.optInt("seasonNumber"), o.optString("title"), o.optInt("episodeCount"), o.optLong("posterItemId"), o.optLong("posterMtimeUnix"), o.optString("overview"))
        }
    }

    suspend fun episodes(libraryId: String, showTitle: String, season: Int): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/tv/episodes?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season"))
    }

    suspend fun streams(itemId: Long): List<StreamInfo> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/items/$itemId/streams")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StreamInfo(
                index = o.optInt("index"),
                type = o.optString("type"),
                codec = o.optString("codec"),
                language = o.optString("language"),
                title = o.optString("title"),
                default = o.optBoolean("default"),
                forced = o.optBoolean("forced"),
            )
        }
    }

    suspend fun playbackPlan(
        itemId: Long,
        startPositionMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
        bandwidthKbps: Int?,
        forceMode: String,
        profile: JSONObject,
    ): PlaybackPlan = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("itemId", itemId)
            .put("startPositionMs", startPositionMs)
            .put("forceMode", forceMode)
            .put("profile", profile)
        if (audioIndex != null) body.put("audioIndex", audioIndex)
        if (subtitleIndex != null) body.put("subtitleIndex", subtitleIndex)
        if (bandwidthKbps != null) body.put("bandwidthKbps", bandwidthKbps)
        jsonToPlaybackPlan(request("/api/playback/plan", "POST", body.toString()))
    }

    suspend fun playbackFailure(itemId: Long, plan: PlaybackPlan, errorCode: String, message: String): PlaybackPlan? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("itemId", itemId)
            .put("planId", plan.planId)
            .put("mode", plan.mode)
            .put("client", "android-phone")
            .put("errorCode", errorCode)
            .put("message", message)
        val json = request("/api/playback/failure", "POST", body.toString())
        if (!json.optBoolean("retry")) return@withContext null
        json.optJSONObject("plan")?.let(::jsonToPlaybackPlan)
    }

    suspend fun ratings(itemId: Long): ExternalRatings = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/ratings")
        ExternalRatings(
            imdbId = o.optString("imdbId"),
            tmdbId = o.optString("tmdbId"),
            imdbRating = o.optDouble("imdbRating"),
            tmdbRating = o.optDouble("tmdbRating"),
            rottenTomatoesRating = o.optInt("rottenTomatoesRating"),
            metacriticRating = o.optInt("metacriticRating"),
        )
    }

    // Aggregated home payload: the continue-watching rows plus a resume map
    // (itemId -> watched fraction) derived from in-progress playback.
    suspend fun home(): HomeContinue = withContext(Dispatchers.IO) {
        val o = request("/api/home")
        val movies = parseItems(o.optJSONArray("continueMovies") ?: JSONArray())
        val episodes = parseItems(o.optJSONArray("continueEpisodes") ?: JSONArray())
        val resume = mutableMapOf<Long, Float>()
        val prog = o.optJSONArray("progress") ?: JSONArray()
        for (i in 0 until prog.length()) {
            val p = prog.getJSONObject(i)
            val dur = p.optLong("durationMs")
            val pos = p.optLong("positionMs")
            if (!p.optBoolean("completed") && dur > 0 && pos > 0) {
                resume[p.optLong("itemId")] = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            }
        }
        HomeContinue(movies, episodes, resume)
    }

    suspend fun progress(itemId: Long): PlaybackProgress = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/progress")
        PlaybackProgress(
            itemId = o.optLong("itemId", itemId),
            positionMs = o.optLong("positionMs"),
            durationMs = o.optLong("durationMs"),
            completed = o.optBoolean("completed"),
        )
    }

    suspend fun progressList(): List<PlaybackProgress> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/progress?limit=5000")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlaybackProgress(
                itemId = o.optLong("itemId"),
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                completed = o.optBoolean("completed"),
            )
        }
    }

    suspend fun showProgress(): List<ShowProgress> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/progress/tv")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ShowProgress(
                libraryId = o.optString("libraryId"),
                showTitle = o.optString("showTitle"),
                episodeCount = o.optInt("episodeCount"),
                completedCount = o.optInt("completedCount"),
                completed = o.optBoolean("completed"),
            )
        }
    }

    suspend fun watchlist(): Watchlist = withContext(Dispatchers.IO) {
        val root = request("/api/watchlist?limit=5000")
        Watchlist(
            items = parseItems(root.optJSONArray("items") ?: JSONArray()),
            shows = parseShows(root.optJSONArray("shows") ?: JSONArray()),
        )
    }

    suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long, completed: Boolean) = withContext(Dispatchers.IO) {
        request(
            "/api/items/$itemId/progress",
            "PUT",
            JSONObject()
                .put("positionMs", positionMs)
                .put("durationMs", durationMs)
                .put("completed", completed)
                .put("state", if (completed) "ended" else "")
                .toString(),
        )
    }

    suspend fun markItemWatched(item: PopItem) = withContext(Dispatchers.IO) {
        val duration = if (item.durationMs > 0) item.durationMs else 1L
        request(
            "/api/items/${item.id}/progress",
            "PUT",
            JSONObject()
                .put("positionMs", duration)
                .put("durationMs", duration)
                .put("completed", true)
                .put("state", "manual")
                .toString(),
        )
    }

    suspend fun unmarkItemWatched(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/progress", "DELETE", null)
    }

    suspend fun markShowWatched(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        request("/api/progress/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "PUT", "{}")
    }

    suspend fun unmarkShowWatched(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        requestText("/api/progress/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "DELETE", null)
    }

    suspend fun markSeasonWatched(libraryId: String, showTitle: String, season: Int) = withContext(Dispatchers.IO) {
        request("/api/progress/tv/season?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season", "PUT", "{}")
    }

    suspend fun unmarkSeasonWatched(libraryId: String, showTitle: String, season: Int) = withContext(Dispatchers.IO) {
        requestText("/api/progress/tv/season?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season", "DELETE", null)
    }

    suspend fun stopHls(sessionId: String) = withContext(Dispatchers.IO) {
        requestText("/api/hls/${enc(sessionId)}", "DELETE", null)
    }

    suspend fun searchMovies(query: String, filters: LibraryFilters = LibraryFilters()): List<PopItem> = withContext(Dispatchers.IO) {
        val root = request("/api/search?limit=40&kind=movie&q=${enc(query)}${filterParams(filters)}")
        parseItems(root.optJSONArray("items") ?: JSONArray())
    }

    suspend fun searchShows(query: String, filters: LibraryFilters = LibraryFilters()): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?limit=40&q=${enc(query)}${filterParams(filters)}"))
    }

    private fun filterParams(filters: LibraryFilters): String {
        val params = mutableListOf<String>()
        if (filters.genre.isNotBlank()) params += "genre=${enc(filters.genre)}"
        if (filters.minRating > 0) params += "minRating=${filters.minRating}"
        if (filters.sort.isNotBlank()) params += "sort=${enc(filters.sort)}"
        return if (params.isEmpty()) "" else "&${params.joinToString("&")}"
    }

    private fun requestArray(path: String): JSONArray {
        val text = requestText(path, "GET", null).trim()
        if (text.isBlank() || text == "null") return JSONArray()
        if (text.startsWith("[")) return JSONArray(text)
        return JSONObject(text).optJSONArray("items") ?: JSONArray()
    }

    private fun request(path: String, method: String = "GET", body: String? = null): JSONObject {
        val text = requestText(path, method, body).trim()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestText(path: String, method: String, body: String?): String {
        val conn = URL(session.server + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            // Use a fresh connection per request instead of a pooled keep-alive
            // socket. After the server restarts or the network blips, a stale
            // pooled socket hangs or errors and HttpURLConnection's pool stays
            // poisoned until the app process restarts — which is why the app
            // previously "lost its connection" until a manual restart.
            conn.setRequestProperty("Connection", "close")
            if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(text.ifBlank { "HTTP $code" })
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseShows(arr: JSONArray): List<ShowSummary> = (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        ShowSummary(o.getString("libraryId"), o.optString("title"), o.optInt("episodeCount"), o.optInt("seasonCount"), o.optLong("posterItemId"), o.optLong("posterMtimeUnix"), o.optString("overview"), o.optString("genres"), o.optDouble("rating"), o.optInt("year"), o.optInt("endYear"), o.optLong("backdropItemId"), o.optLong("backdropMtimeUnix"))
    }

    private fun parseItems(arr: JSONArray): List<PopItem> = (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        PopItem(o.getLong("id"), o.getString("libraryId"), o.optString("kind"), o.optString("title"), o.optInt("year"), o.optLong("durationMs"), o.optLong("posterMtimeUnix"), o.optLong("backdropMtimeUnix"), o.optString("overview"), o.optString("genres"), o.optDouble("rating"), o.optString("imdbId"), o.optString("tmdbId"), o.optString("showTitle"), o.optInt("seasonNumber"), o.optInt("episodeNumber"), o.optString("episodeTitle"), parseActors(o.optJSONArray("actors")))
    }

    private fun parseActors(arr: JSONArray?): List<Actor> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val a = arr.getJSONObject(it)
            Actor(a.optString("name"), a.optString("role"), a.optString("thumb"))
        }
    }

    suspend fun similar(itemId: Long): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items/$itemId/similar"))
    }

    suspend fun showActors(libraryId: String, showTitle: String): List<Actor> = withContext(Dispatchers.IO) {
        parseActors(requestArray("/api/tv/shows/actors?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}"))
    }

    suspend fun itemSidecars(itemId: Long): SidecarStatus = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/sidecars")
        SidecarStatus(trailer = o.optBoolean("trailer"), theme = o.optBoolean("theme"))
    }

    private fun jsonToPlaybackPlan(o: JSONObject): PlaybackPlan {
        val selected = o.optJSONObject("selected") ?: JSONObject()
        return PlaybackPlan(
            planId = o.optString("planId"),
            mode = o.optString("mode"),
            playable = o.optBoolean("playable", true),
            url = o.optString("url"),
            sessionId = o.optString("sessionId"),
            startPositionMs = o.optLong("startPositionMs"),
            durationMs = o.optLong("durationMs"),
            selectedAudioIndex = selected.optIntOrNull("audioIndex"),
            selectedSubtitleIndex = selected.optIntOrNull("subtitleIndex"),
        )
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private fun jsonToLibrary(o: JSONObject): Library = Library(o.getString("id"), o.optString("name"), o.optString("type", "movies"))
private fun libraryToJson(library: Library): JSONObject = JSONObject()
    .put("id", library.id)
    .put("name", library.name)
    .put("type", library.type)

private fun jsonToItem(o: JSONObject): PopItem = PopItem(
    o.getLong("id"),
    o.getString("libraryId"),
    o.optString("kind"),
    o.optString("title"),
    o.optInt("year"),
    o.optLong("durationMs"),
    o.optLong("posterMtimeUnix"),
    o.optLong("backdropMtimeUnix"),
    o.optString("overview"),
    o.optString("genres"),
    o.optDouble("rating"),
    o.optString("imdbId"),
    o.optString("tmdbId"),
    o.optString("showTitle"),
    o.optInt("seasonNumber"),
    o.optInt("episodeNumber"),
    o.optString("episodeTitle"),
)

private fun itemToJson(item: PopItem): JSONObject = JSONObject()
    .put("id", item.id)
    .put("libraryId", item.libraryId)
    .put("kind", item.kind)
    .put("title", item.title)
    .put("year", item.year)
    .put("durationMs", item.durationMs)
    .put("posterMtimeUnix", item.posterMtimeUnix)
    .put("backdropMtimeUnix", item.backdropMtimeUnix)
    .put("overview", item.overview)
    .put("genres", item.genres)
    .put("rating", item.rating)
    .put("imdbId", item.imdbId)
    .put("tmdbId", item.tmdbId)
    .put("showTitle", item.showTitle)
    .put("seasonNumber", item.seasonNumber)
    .put("episodeNumber", item.episodeNumber)
    .put("episodeTitle", item.episodeTitle)

private fun jsonToShow(o: JSONObject): ShowSummary = ShowSummary(
    o.getString("libraryId"),
    o.optString("title"),
    o.optInt("episodeCount"),
    o.optInt("seasonCount"),
    o.optLong("posterItemId"),
    o.optLong("posterMtimeUnix"),
    o.optString("overview"),
    o.optString("genres"),
    o.optDouble("rating"),
    o.optInt("year"),
    o.optInt("endYear"),
    o.optLong("backdropItemId"),
    o.optLong("backdropMtimeUnix"),
)

private fun showToJson(show: ShowSummary): JSONObject = JSONObject()
    .put("libraryId", show.libraryId)
    .put("title", show.title)
    .put("year", show.year)
    .put("endYear", show.endYear)
    .put("episodeCount", show.episodeCount)
    .put("seasonCount", show.seasonCount)
    .put("posterItemId", show.posterItemId)
    .put("posterMtimeUnix", show.posterMtimeUnix)
    .put("backdropItemId", show.backdropItemId)
    .put("backdropMtimeUnix", show.backdropMtimeUnix)
    .put("overview", show.overview)
    .put("genres", show.genres)
    .put("rating", show.rating)

private fun jsonToSeason(o: JSONObject): SeasonSummary = SeasonSummary(
    o.getString("libraryId"),
    o.getString("showTitle"),
    o.optInt("seasonNumber"),
    o.optString("title"),
    o.optInt("episodeCount"),
    o.optLong("posterItemId"),
    o.optLong("posterMtimeUnix"),
    o.optString("overview"),
)

private fun seasonToJson(season: SeasonSummary): JSONObject = JSONObject()
    .put("libraryId", season.libraryId)
    .put("showTitle", season.showTitle)
    .put("seasonNumber", season.seasonNumber)
    .put("title", season.title)
    .put("episodeCount", season.episodeCount)
    .put("posterItemId", season.posterItemId)
    .put("posterMtimeUnix", season.posterMtimeUnix)
    .put("overview", season.overview)

fun imageUrl(session: Session, itemId: Long, version: Long, kind: String = "poster"): String = if (itemId > 0) "${session.server}/api/items/$itemId/image/$kind?v=$version" else ""

fun streamUrl(session: Session, itemId: Long): String = "${session.server}/api/items/$itemId/stream"

fun hlsUrl(session: Session, itemId: Long, hlsSession: String, bandwidthKbps: Int, startMs: Long, audioIndex: Int?, subtitleIndex: Int?): String {
    val params = mutableListOf(
        "bandwidth=$bandwidthKbps",
        "start=${"%.3f".format(java.util.Locale.US, startMs / 1000.0)}",
    )
    if (audioIndex != null) params += "audio=$audioIndex"
    if (subtitleIndex != null) params += "subtitle=$subtitleIndex"
    return "${session.server}/api/items/$itemId/hls/$hlsSession/index.m3u8?${params.joinToString("&")}"
}

fun hlsOwnerToken(value: String): String {
    val cleaned = value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)
    return cleaned.ifBlank { "local" }
}

fun sameServer(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

fun parseQr(raw: String): ScannedQr? {
    val text = raw.trim()
    if (text.isBlank()) return null
    return runCatching {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "popcorn-shield-setup" -> ScannedQr(
                type = "popcorn-shield-setup",
                callback = json.getString("callback"),
                code = json.getString("code"),
            )

            "popcorn-login" -> ScannedQr(
                type = "popcorn-login",
                server = json.getString("server").trimEnd('/'),
                code = json.getString("code"),
            )

            else -> null
        }
    }.getOrNull() ?: run {
        val prefix = "popcorn-login:"
        if (!text.startsWith(prefix)) null else ScannedQr(type = "popcorn-login", code = text.removePrefix(prefix))
    }
}

fun postShieldSetup(callback: String, code: String, session: Session) {
    val body = JSONObject()
        .put("code", code)
        .put("server", session.server.trimEnd('/'))
        .put("token", session.token)
        .put("username", session.username)
        .toString()
        .toByteArray(Charsets.UTF_8)
    Log.i("PopcornQR", "Posting Shield setup to $callback")
    val conn = URL(callback).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 5000
    conn.readTimeout = 8000
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("Content-Length", body.size.toString())
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(body.size)
    conn.outputStream.use { it.write(body) }
    val codeResult = conn.responseCode
    val stream = if (codeResult in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    Log.i("PopcornQR", "Shield setup response HTTP $codeResult ${text.take(160)}")
    if (codeResult !in 200..299) error(text.ifBlank { "HTTP $codeResult" })
}
