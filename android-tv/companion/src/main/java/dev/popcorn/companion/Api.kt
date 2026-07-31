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

    fun readHomeSections(context: Context, session: Session): List<HomeSection> {
        return runCatching {
            jsonToHomeSections(JSONArray(cacheFile(context, session, "home_sections").readText()))
        }.getOrDefault(emptyList())
    }

    fun writeHomeSections(context: Context, session: Session, sectionsJson: String) {
        runCatching { cacheFile(context, session, "home_sections").writeText(sectionsJson) }
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
        authSession(json)
    }

    suspend fun completeQr(code: String) = withContext(Dispatchers.IO) {
        request("/api/auth/qr/complete", "POST", JSONObject().put("code", code).toString())
    }

    suspend fun claimQr(code: String): Session = withContext(Dispatchers.IO) {
        authSession(request("/api/auth/qr/claim", "POST", JSONObject().put("code", code).toString()))
    }

    suspend fun refreshSession(): Session = withContext(Dispatchers.IO) {
        val user = request("/api/auth/me")
        Session(
            server = session.server,
            token = session.token,
            username = user.optString("username"),
            isAdmin = user.optBoolean("isAdmin"),
            userId = user.optLong("id"),
            avatar = user.optString("avatar"),
            displayName = user.optString("displayName"),
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        requestText("/api/auth/logout", "POST", null)
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

    suspend fun decades(libraryId: String, kind: String): List<Int> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/decades?libraryId=${enc(libraryId)}&kind=${enc(kind)}")
        (0 until arr.length()).map { arr.optInt(it) }.filter { it > 0 }
    }

    suspend fun alphabet(libraryId: String, kind: String, filters: LibraryFilters = LibraryFilters()): List<AlphabetEntry> = withContext(Dispatchers.IO) {
        val genreParam = if (filters.genre.isNotBlank()) "&genre=${enc(filters.genre)}" else ""
        val decadesParam = if (filters.decades.isNotBlank()) "&decades=${enc(filters.decades)}" else ""
        val arr = requestArray("/api/alphabet?libraryId=${enc(libraryId)}&kind=${enc(kind)}$genreParam$decadesParam")
        (0 until arr.length()).map { index ->
            val item = arr.getJSONObject(index)
            AlphabetEntry(item.optString("letter"), item.optInt("offset"), item.optInt("count"))
        }
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

    // The compact response contains every user-specific home row and marker,
    // but skips the 300-item library samples used only by the TV client.
    suspend fun home(): HomeContent = withContext(Dispatchers.IO) {
        // profile=phone picks this client's home layout, falling back to the
        // user's default layout when they have not customised the phone.
        val o = request("/api/home?profile=phone")
        val libraries = o.optJSONArray("libraries") ?: JSONArray()
        val progress = o.optJSONArray("progress") ?: JSONArray()
        val showProgress = o.optJSONArray("showProgress") ?: JSONArray()
        val watchlist = o.optJSONObject("watchlist") ?: JSONObject()
        HomeContent(
            libraries = (0 until libraries.length()).map { jsonToLibrary(libraries.getJSONObject(it)) },
            sections = jsonToHomeSections(o.optJSONArray("sections") ?: JSONArray()),
            sectionsJson = (o.optJSONArray("sections") ?: JSONArray()).toString(),
            progress = (0 until progress.length()).map {
                val item = progress.getJSONObject(it)
                PlaybackProgress(
                    itemId = item.optLong("itemId"),
                    positionMs = item.optLong("positionMs"),
                    durationMs = item.optLong("durationMs"),
                    completed = item.optBoolean("completed"),
                )
            },
            showProgress = (0 until showProgress.length()).map {
                val show = showProgress.getJSONObject(it)
                ShowProgress(
                    libraryId = show.optString("libraryId"),
                    showTitle = show.optString("showTitle"),
                    episodeCount = show.optInt("episodeCount"),
                    completedCount = show.optInt("completedCount"),
                    completed = show.optBoolean("completed"),
                )
            },
            watchlist = Watchlist(
                items = parseItems(watchlist.optJSONArray("items") ?: JSONArray()),
                shows = parseShows(watchlist.optJSONArray("shows") ?: JSONArray()),
            ),
            excludedRecommendationKeys = jsonStringSet(o.optJSONArray("excludedRecommendationKeys") ?: JSONArray()),
        )
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

    suspend fun item(itemId: Long): PopItem = withContext(Dispatchers.IO) {
        jsonToItem(request("/api/items/$itemId"))
    }

    suspend fun progressList(): List<PlaybackProgress> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlaybackProgress>()
        val limit = 500
        var offset = 0
        while (true) {
            val arr = requestArray("/api/progress?limit=$limit&offset=$offset")
            for (index in 0 until arr.length()) {
                val o = arr.getJSONObject(index)
                out.add(
                    PlaybackProgress(
                        itemId = o.optLong("itemId"),
                        positionMs = o.optLong("positionMs"),
                        durationMs = o.optLong("durationMs"),
                        completed = o.optBoolean("completed"),
                    ),
                )
            }
            if (arr.length() < limit) break
            offset += limit
        }
        out
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

    suspend fun addItemWatchlist(itemId: Long) = withContext(Dispatchers.IO) {
        request("/api/items/$itemId/watchlist", "PUT", "{}")
    }

    suspend fun removeItemWatchlist(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/watchlist", "DELETE", null)
    }

    suspend fun recommendationExclusions(): List<RecommendationExclusion> = withContext(Dispatchers.IO) {
        val rows = requestArray("/api/recommendations/exclusions")
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RecommendationExclusion(
                key = row.optString("key"),
                kind = row.optString("kind"),
                item = row.optJSONObject("item")?.let(::jsonToItem),
                show = row.optJSONObject("show")?.let(::jsonToShow),
                path = row.optString("path"),
                sizeBytes = row.optLong("sizeBytes"),
            )
        }
    }

    suspend fun excludeItemRecommendation(itemId: Long) = withContext(Dispatchers.IO) {
        request("/api/items/$itemId/recommendation-exclusion", "PUT", "{}")
    }

    suspend fun restoreItemRecommendation(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/recommendation-exclusion", "DELETE", null)
    }

    suspend fun excludeShowRecommendation(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        request("/api/recommendations/exclusions/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "PUT", "{}")
    }

    suspend fun restoreShowRecommendation(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        requestText("/api/recommendations/exclusions/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "DELETE", null)
    }

    suspend fun watchHistory(limit: Int = 120): WatchHistory = withContext(Dispatchers.IO) {
        val json = request("/api/history?limit=$limit")
        val rows = json.optJSONArray("items") ?: JSONArray()
        WatchHistory(
            items = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                WatchHistoryEntry(
                    id = row.optString("id"),
                    item = row.optJSONObject("item")?.let(::jsonToItem),
                    kind = row.optString("kind"),
                    title = row.optString("title"),
                    subtitle = row.optString("subtitle"),
                    year = row.optInt("year"),
                    watchedAt = row.optString("watchedAt"),
                    source = row.optString("source"),
                )
            },
            source = json.optString("source", "local"),
            traktLinked = json.optBoolean("traktLinked"),
        )
    }

    suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long, completed: Boolean, continuousMs: Long? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("completed", completed)
            .put("state", if (completed) "ended" else "")
        if (continuousMs != null) body.put("continuousMs", continuousMs.coerceAtLeast(0))
        request("/api/items/$itemId/progress", "PUT", body.toString())
    }

    suspend fun itemRating(itemId: Long): Int = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/ratings/user")
        (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { it.optString("kind") != "show" && it.optLong("itemId") == itemId }
            ?.optInt("rating") ?: 0
    }

    suspend fun setItemRating(itemId: Long, rating: Int) = withContext(Dispatchers.IO) {
        request("/api/items/$itemId/rating", "PUT", "{\"rating\":$rating}")
    }

    suspend fun deleteItemRating(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/rating", "DELETE", null)
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

    suspend fun searchMovies(query: String, fields: String = "title"): List<PopItem> = withContext(Dispatchers.IO) {
        val root = request("/api/search?limit=500&kind=movie&q=${enc(query)}&fields=${enc(fields)}")
        parseItems(root.optJSONArray("items") ?: JSONArray())
    }

    suspend fun searchShows(query: String, fields: String = "title"): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?limit=500&q=${enc(query)}&fields=${enc(fields)}"))
    }

    private fun filterParams(filters: LibraryFilters): String {
        val params = mutableListOf<String>()
        if (filters.genre.isNotBlank()) params += "genre=${enc(filters.genre)}"
        if (filters.minRating > 0) params += "minRating=${filters.minRating}"
        if (filters.sort.isNotBlank()) params += "sort=${enc(filters.sort)}"
        if (filters.seenStatus.isNotBlank()) params += "seen=${enc(filters.seenStatus)}"
        if (filters.decades.isNotBlank()) params += "decades=${enc(filters.decades)}"
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

    private fun authSession(json: JSONObject): Session {
        val user = json.optJSONObject("user")
        return Session(
            server = session.server,
            token = json.getString("token"),
            username = user?.optString("username").orEmpty(),
            isAdmin = user?.optBoolean("isAdmin") ?: false,
            userId = user?.optLong("id") ?: 0L,
            avatar = user?.optString("avatar").orEmpty(),
            displayName = user?.optString("displayName").orEmpty(),
        )
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

    private fun parseItems(arr: JSONArray): List<PopItem> = (0 until arr.length()).map { jsonToItem(arr.getJSONObject(it)) }


    private fun jsonStringSet(arr: JSONArray): Set<String> =
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()

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

    suspend fun actorDetail(name: String): ActorDetail = withContext(Dispatchers.IO) {
        val root = request("/api/actors?name=${enc(name)}")
        val actor = root.optJSONObject("actor") ?: JSONObject()
        val info = root.optJSONObject("info") ?: JSONObject()
        ActorDetail(
            actor = Actor(actor.optString("name", name), actor.optString("role"), actor.optString("thumb")),
            info = ActorInfo(
                biography = info.optString("biography"),
                birthday = info.optString("birthday"),
                placeOfBirth = info.optString("placeOfBirth"),
                knownForDepartment = info.optString("knownForDepartment"),
            ),
            profileUrl = root.optString("profileUrl"),
            movies = parseItems(root.optJSONArray("movies") ?: JSONArray()),
            shows = parseShows(root.optJSONArray("shows") ?: JSONArray()),
        )
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

private fun jsonToHomeSections(arr: JSONArray): List<HomeSection> = (0 until arr.length()).map { index ->
    val o = arr.getJSONObject(index)
    val items = o.optJSONArray("items") ?: JSONArray()
    val shows = o.optJSONArray("shows") ?: JSONArray()
    val entries = o.optJSONArray("entries") ?: JSONArray()
    HomeSection(
        id = o.optString("id"),
        type = o.optString("type"),
        layout = o.optString("layout"),
        kind = o.optString("kind"),
        title = o.optString("title"),
        subtitle = o.optString("subtitle"),
        more = o.optString("more"),
        items = (0 until items.length()).map { jsonToItem(items.getJSONObject(it)) },
        shows = (0 until shows.length()).map { jsonToShow(shows.getJSONObject(it)) },
        entries = (0 until entries.length()).map { entryIndex ->
            val row = entries.getJSONObject(entryIndex)
            Recommendation(
                key = row.optString("key"),
                reason = row.optString("reason"),
                source = row.optString("source"),
                item = row.optJSONObject("item")?.let(::jsonToItem),
                show = row.optJSONObject("show")?.let(::jsonToShow),
            )
        },
    )
}

private fun jsonToItem(o: JSONObject): PopItem = PopItem(
    id = o.getLong("id"),
    libraryId = o.getString("libraryId"),
    kind = o.optString("kind"),
    title = o.optString("title"),
    year = o.optInt("year"),
    durationMs = o.optLong("durationMs"),
    posterMtimeUnix = o.optLong("posterMtimeUnix"),
    backdropMtimeUnix = o.optLong("backdropMtimeUnix"),
    overview = o.optString("overview"),
    genres = o.optString("genres"),
    rating = o.optDouble("rating"),
    imdbId = o.optString("imdbId"),
    tmdbId = o.optString("tmdbId"),
    showTitle = o.optString("showTitle"),
    seasonNumber = o.optInt("seasonNumber"),
    episodeNumber = o.optInt("episodeNumber"),
    episodeTitle = o.optString("episodeTitle"),
    actors = (o.optJSONArray("actors") ?: JSONArray()).let { actors ->
        (0 until actors.length()).map { index ->
            actors.getJSONObject(index).let { Actor(it.optString("name"), it.optString("role"), it.optString("thumb")) }
        }
    },
    originalTitle = o.optString("originalTitle"),
    videoCodec = o.optString("videoCodec"),
    audioCodec = o.optString("audioCodec"),
    tvdbId = o.optString("tvdbId"),
    width = o.optInt("width"),
    height = o.optInt("height"),
    posterPath = o.optString("posterPath"),
    backdropPath = o.optString("backdropPath"),
    tagline = o.optString("tagline"),
    officialRating = o.optString("officialRating"),
    tags = o.optString("tags"),
    studios = o.optString("studios"),
    directors = o.optString("directors"),
    writers = o.optString("writers"),
    countries = o.optString("countries"),
    premiered = o.optString("premiered"),
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
    .put("originalTitle", item.originalTitle)
    .put("videoCodec", item.videoCodec)
    .put("audioCodec", item.audioCodec)
    .put("tvdbId", item.tvdbId)
    .put("width", item.width)
    .put("height", item.height)
    .put("posterPath", item.posterPath)
    .put("backdropPath", item.backdropPath)
    .put("tagline", item.tagline)
    .put("officialRating", item.officialRating)
    .put("tags", item.tags)
    .put("studios", item.studios)
    .put("directors", item.directors)
    .put("writers", item.writers)
    .put("countries", item.countries)
    .put("premiered", item.premiered)
    .put("actors", JSONArray().apply {
        item.actors.forEach { actor -> put(JSONObject().put("name", actor.name).put("role", actor.role).put("thumb", actor.thumb)) }
    })

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

// Artwork widths the server will generate (see internal/server/thumbs.go —
// requests snap up to the nearest bucket). Source posters are routinely
// 1000x1500 and backdrops 1920x1080, so asking for a scaled copy cuts a poster
// wall from tens of megabytes to a few hundred kilobytes.
const val ArtworkThumb = 200
const val ArtworkCard = 400
const val ArtworkFull = 800

fun imageUrl(session: Session, itemId: Long, version: Long, kind: String = "poster", width: Int = 0): String {
    if (itemId <= 0) return ""
    val sized = if (width > 0) "&w=$width" else ""
    return "${session.server}/api/items/$itemId/image/$kind?v=$version$sized"
}

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
