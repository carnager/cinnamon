package dev.popcorn.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AppCache {
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

    fun readItems(context: Context, session: Session, libraryId: String): CachedList<PopItem> {
        return readCachedList(cacheFile(context, session, "items_$libraryId"), ::jsonToItem)
    }

    fun writeItems(context: Context, session: Session, libraryId: String, items: List<PopItem>, fullyLoaded: Boolean) {
        writeCachedList(cacheFile(context, session, "items_$libraryId"), items, fullyLoaded, ::itemToJson)
    }

    fun readShows(context: Context, session: Session, libraryId: String): CachedList<ShowSummary> {
        return readCachedList(cacheFile(context, session, "shows_$libraryId"), ::jsonToShow)
    }

    fun writeShows(context: Context, session: Session, libraryId: String, shows: List<ShowSummary>, fullyLoaded: Boolean) {
        writeCachedList(cacheFile(context, session, "shows_$libraryId"), shows, fullyLoaded, ::showToJson)
    }

    private fun <T> readCachedList(file: File, parser: (JSONObject) -> T): CachedList<T> {
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("entries")
            CachedList(
                entries = (0 until arr.length()).map { parser(arr.getJSONObject(it)) },
                fullyLoaded = root.optBoolean("fullyLoaded", false),
            )
        }.getOrDefault(CachedList(emptyList(), false))
    }

    private fun <T> writeCachedList(file: File, entries: List<T>, fullyLoaded: Boolean, writer: (T) -> JSONObject) {
        runCatching {
            val arr = JSONArray()
            entries.forEach { arr.put(writer(it)) }
            file.writeText(JSONObject().put("fullyLoaded", fullyLoaded).put("entries", arr).toString())
        }
    }

    private fun cacheFile(context: Context, session: Session, name: String): File {
        val serverKey = session.server.fold(0) { acc, c -> acc * 31 + c.code }.toString()
        val dir = File(context.filesDir, "popcorn-cache/$serverKey").apply { mkdirs() }
        return File(dir, "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json")
    }
}

private fun jsonToLibrary(o: JSONObject): Library {
    return Library(o.getString("id"), o.getString("name"), o.optString("type", "movies"))
}

private fun libraryToJson(library: Library): JSONObject {
    return JSONObject()
        .put("id", library.id)
        .put("name", library.name)
        .put("type", library.type)
}

private fun jsonToShow(o: JSONObject): ShowSummary {
    return ShowSummary(
        libraryId = o.getString("libraryId"),
        title = o.getString("title"),
        episodeCount = o.optInt("episodeCount"),
        seasonCount = o.optInt("seasonCount"),
        posterItemId = o.optLong("posterItemId"),
        posterMtimeUnix = o.optLong("posterMtimeUnix"),
        backdropItemId = o.optLong("backdropItemId"),
        backdropMtimeUnix = o.optLong("backdropMtimeUnix"),
        overview = o.optString("overview"),
        genres = o.optString("genres"),
        rating = o.optDouble("rating"),
    )
}

private fun showToJson(show: ShowSummary): JSONObject {
    return JSONObject()
        .put("libraryId", show.libraryId)
        .put("title", show.title)
        .put("episodeCount", show.episodeCount)
        .put("seasonCount", show.seasonCount)
        .put("posterItemId", show.posterItemId)
        .put("posterMtimeUnix", show.posterMtimeUnix)
        .put("backdropItemId", show.backdropItemId)
        .put("backdropMtimeUnix", show.backdropMtimeUnix)
        .put("overview", show.overview)
        .put("genres", show.genres)
        .put("rating", show.rating)
}

private fun jsonToItem(o: JSONObject): PopItem {
    return PopItem(
        id = o.getLong("id"),
        libraryId = o.getString("libraryId"),
        kind = o.optString("kind"),
        title = o.optString("title"),
        year = o.optInt("year"),
        durationMs = o.optLong("durationMs"),
        posterPath = o.optString("posterPath"),
        posterMtimeUnix = o.optLong("posterMtimeUnix"),
        backdropPath = o.optString("backdropPath"),
        backdropMtimeUnix = o.optLong("backdropMtimeUnix"),
        overview = o.optString("overview"),
        genres = o.optString("genres"),
        rating = o.optDouble("rating"),
        showTitle = o.optString("showTitle"),
        seasonNumber = o.optInt("seasonNumber"),
        episodeNumber = o.optInt("episodeNumber"),
        episodeTitle = o.optString("episodeTitle"),
    )
}

private fun itemToJson(item: PopItem): JSONObject {
    return JSONObject()
        .put("id", item.id)
        .put("libraryId", item.libraryId)
        .put("kind", item.kind)
        .put("title", item.title)
        .put("year", item.year)
        .put("durationMs", item.durationMs)
        .put("posterPath", item.posterPath)
        .put("posterMtimeUnix", item.posterMtimeUnix)
        .put("backdropPath", item.backdropPath)
        .put("backdropMtimeUnix", item.backdropMtimeUnix)
        .put("overview", item.overview)
        .put("genres", item.genres)
        .put("rating", item.rating)
        .put("showTitle", item.showTitle)
        .put("seasonNumber", item.seasonNumber)
        .put("episodeNumber", item.episodeNumber)
        .put("episodeTitle", item.episodeTitle)
}

fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

class Api(private val session: Session) {
    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val json = request("/api/auth/login", "POST", body)
        val user = json.optJSONObject("user")
        Session(session.server, json.getString("token"), user?.optString("username").orEmpty())
    }

    suspend fun me(): User = withContext(Dispatchers.IO) {
        val json = request("/api/auth/me")
        User(
            id = json.optLong("id"),
            username = json.optString("username"),
            displayName = json.optString("displayName"),
        )
    }

    suspend fun startQrLogin(deviceName: String): QRLoginStart = withContext(Dispatchers.IO) {
        val json = request("/api/auth/qr/start", "POST", JSONObject().put("deviceName", deviceName).toString())
        QRLoginStart(json.getString("code"), json.optString("expiresAt"))
    }

    suspend fun pollQrLogin(code: String): Session? = withContext(Dispatchers.IO) {
        val json = request("/api/auth/qr/poll?code=${enc(code)}")
        if (json.optString("status") != "approved") return@withContext null
        val user = json.optJSONObject("user")
        Session(session.server, json.getString("token"), user?.optString("username").orEmpty())
    }

    suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/libraries")
        (0 until arr.length()).map { i ->
            jsonToLibrary(arr.getJSONObject(i))
        }
    }

    suspend fun item(itemId: Long): PopItem = withContext(Dispatchers.IO) {
        jsonToItem(request("/api/items/$itemId"))
    }

    suspend fun items(libraryId: String): List<PopItem> = withContext(Dispatchers.IO) {
        val pageSize = 500
        val out = mutableListOf<PopItem>()
        var offset = 0
        while (true) {
            val page = itemsPage(libraryId, pageSize, offset)
            out.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        out
    }

    suspend fun itemsPage(libraryId: String, limit: Int, offset: Int): List<PopItem> = withContext(Dispatchers.IO) {
        itemsPage(libraryId, limit, offset, "")
    }

    suspend fun itemsPage(libraryId: String, limit: Int, offset: Int, genre: String): List<PopItem> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset$genreParam"))
    }

    suspend fun recentItems(libraryId: String, limit: Int): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=0&sort=recent"))
    }

    suspend fun searchMovies(query: String): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestItemsEnvelope("/api/search?limit=120&kind=movie&q=${enc(query)}"))
    }

    suspend fun searchShows(query: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?limit=120&q=${enc(query)}"))
    }

    suspend fun shows(libraryId: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        val pageSize = 500
        val out = mutableListOf<ShowSummary>()
        var offset = 0
        while (true) {
            val page = showsPage(libraryId, pageSize, offset)
            out.addAll(page)
            if (page.size < pageSize) break
            offset += pageSize
        }
        out
    }

    suspend fun showsPage(libraryId: String, limit: Int, offset: Int): List<ShowSummary> = withContext(Dispatchers.IO) {
        showsPage(libraryId, limit, offset, "")
    }

    suspend fun showsPage(libraryId: String, limit: Int, offset: Int, genre: String): List<ShowSummary> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset$genreParam"))
    }

    suspend fun recentShows(libraryId: String, limit: Int): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=0&sort=recent"))
    }

    suspend fun genres(libraryId: String): List<String> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/genres?libraryId=${enc(libraryId)}")
        (0 until arr.length()).map { i -> arr.getString(i) }
    }

    private fun parseShows(arr: JSONArray): List<ShowSummary> {
        return (0 until arr.length()).map { i ->
            jsonToShow(arr.getJSONObject(i))
        }
    }

    suspend fun seasons(libraryId: String, showTitle: String): List<SeasonSummary> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/seasons?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SeasonSummary(
                libraryId = o.getString("libraryId"),
                showTitle = o.getString("showTitle"),
                seasonNumber = o.optInt("seasonNumber"),
                title = o.optString("title"),
                episodeCount = o.optInt("episodeCount"),
                durationMs = o.optLong("durationMs"),
                posterItemId = o.optLong("posterItemId"),
                posterMtimeUnix = o.optLong("posterMtimeUnix"),
                backdropItemId = o.optLong("backdropItemId"),
                overview = o.optString("overview"),
                rating = o.optDouble("rating"),
            )
        }
    }

    suspend fun episodes(libraryId: String, showTitle: String, season: Int): List<PopItem> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/episodes?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season")
        parseItems(arr)
    }

    suspend fun streams(itemId: Long): List<StreamInfo> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/items/$itemId/streams")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StreamInfo(
                index = o.getInt("index"),
                type = o.getString("type"),
                codec = o.optString("codec"),
                language = o.optString("language"),
                title = o.optString("title"),
                default = o.optBoolean("default"),
                forced = o.optBoolean("forced"),
            )
        }
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

    suspend fun ratings(itemId: Long): ExternalRatings = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/ratings")
        ExternalRatings(
            imdbId = o.optString("imdbId"),
            tmdbId = o.optString("tmdbId"),
            localRating = o.optDouble("localRating"),
            imdbRating = o.optDouble("imdbRating"),
            tmdbRating = o.optDouble("tmdbRating"),
            rottenTomatoesRating = o.optInt("rottenTomatoesRating"),
            metacriticRating = o.optInt("metacriticRating"),
        )
    }

    suspend fun progressList(): List<PlaybackProgress> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlaybackProgress>()
        val limit = 500
        var offset = 0
        while (true) {
            val arr = requestArray("/api/progress?limit=$limit&offset=$offset")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PlaybackProgress(
                        itemId = o.optLong("itemId"),
                        positionMs = o.optLong("positionMs"),
                        durationMs = o.optLong("durationMs"),
                        completed = o.optBoolean("completed"),
                    )
                )
            }
            if (arr.length() < limit) break
            offset += limit
        }
        out
    }

    suspend fun showProgress(): List<ShowProgress> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/progress/tv")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ShowProgress(
                libraryId = o.getString("libraryId"),
                showTitle = o.getString("showTitle"),
                episodeCount = o.optInt("episodeCount"),
                completedCount = o.optInt("completedCount"),
                completed = o.optBoolean("completed"),
            )
        }
    }

    suspend fun watchlist(): Watchlist = withContext(Dispatchers.IO) {
        val json = request("/api/watchlist?limit=300")
        val itemsArr = json.optJSONArray("items") ?: JSONArray()
        val showsArr = json.optJSONArray("shows") ?: JSONArray()
        Watchlist(
            items = parseItems(itemsArr),
            shows = parseShows(showsArr),
        )
    }

    suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long, completed: Boolean, state: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("completed", completed)
            .put("state", state)
            .toString()
        request("/api/items/$itemId/progress", "PUT", body)
    }

    suspend fun markItemWatched(item: PopItem) = withContext(Dispatchers.IO) {
        val duration = if (item.durationMs > 0) item.durationMs else 1L
        saveProgress(item.id, duration, duration, true, "manual")
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

    suspend fun addItemWatchlist(itemId: Long) = withContext(Dispatchers.IO) {
        request("/api/items/$itemId/watchlist", "PUT", "{}")
    }

    suspend fun removeItemWatchlist(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/watchlist", "DELETE", null)
    }

    suspend fun addShowWatchlist(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        request("/api/watchlist/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "PUT", "{}")
    }

    suspend fun removeShowWatchlist(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        requestText("/api/watchlist/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "DELETE", null)
    }

    suspend fun registerDevice(existingId: String?, name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("id", existingId ?: "")
            .put("name", name)
            .put("kind", "tv")
            .toString()
        request("/api/devices/register", "POST", body).getString("id")
    }

    suspend fun remoteCommands(deviceId: String, after: Long): List<RemoteCommand> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/devices/${enc(deviceId)}/commands?after=$after")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteCommand(
                id = o.optLong("id"),
                type = o.optString("type"),
                payload = o.optJSONObject("payload") ?: JSONObject(),
            )
        }
    }

    suspend fun putDeviceState(deviceId: String, itemId: Long, title: String, state: String, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("itemId", itemId)
            .put("title", title)
            .put("state", state)
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .toString()
        requestText("/api/devices/${enc(deviceId)}/state", "PUT", body)
    }

    private fun parseItems(arr: JSONArray): List<PopItem> = (0 until arr.length()).map { i ->
        jsonToItem(arr.getJSONObject(i))
    }

    private fun requestItemsEnvelope(path: String): JSONArray {
        val text = requestText(path, "GET", null)
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "null") return JSONArray()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val json = JSONObject(trimmed)
        val items = json.optJSONArray("items")
        if (items != null) return items
        return JSONArray()
    }

    private fun requestArray(path: String): JSONArray {
        val text = requestText(path, "GET", null)
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "null") return JSONArray()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val json = JSONObject(trimmed)
        return json.optJSONArray("items") ?: JSONArray()
    }

    private fun request(path: String, method: String = "GET", body: String? = null): JSONObject {
        return JSONObject(requestText(path, method, body))
    }

    private fun requestText(path: String, method: String, body: String?): String {
        val conn = URL(session.server + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) error(text.ifBlank { "HTTP $code" })
        return text
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
