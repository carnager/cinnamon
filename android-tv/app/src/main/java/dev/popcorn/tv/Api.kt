package dev.popcorn.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
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

private fun jsonToUser(o: JSONObject): User {
    return User(
        id = o.optLong("id"),
        username = o.optString("username"),
        displayName = o.optString("displayName"),
        isAdmin = o.optBoolean("isAdmin"),
        avatar = o.optString("avatar"),
    )
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
        year = o.optInt("year"),
        endYear = o.optInt("endYear"),
    )
}

private fun showToJson(show: ShowSummary): JSONObject {
    return JSONObject()
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
}

private fun jsonToItem(o: JSONObject): PopItem {
    val actors = o.optJSONArray("actors") ?: JSONArray()
    return PopItem(
        id = o.getLong("id"),
        libraryId = o.getString("libraryId"),
        kind = o.optString("kind"),
        title = o.optString("title"),
        originalTitle = o.optString("originalTitle"),
        year = o.optInt("year"),
        durationMs = o.optLong("durationMs"),
        videoCodec = o.optString("videoCodec"),
        audioCodec = o.optString("audioCodec"),
        imdbId = o.optString("imdbId"),
        tmdbId = o.optString("tmdbId"),
        tvdbId = o.optString("tvdbId"),
        width = o.optInt("width"),
        height = o.optInt("height"),
        posterPath = o.optString("posterPath"),
        posterMtimeUnix = o.optLong("posterMtimeUnix"),
        backdropPath = o.optString("backdropPath"),
        backdropMtimeUnix = o.optLong("backdropMtimeUnix"),
        overview = o.optString("overview"),
        tagline = o.optString("tagline"),
        officialRating = o.optString("officialRating"),
        genres = o.optString("genres"),
        tags = o.optString("tags"),
        studios = o.optString("studios"),
        directors = o.optString("directors"),
        writers = o.optString("writers"),
        countries = o.optString("countries"),
        premiered = o.optString("premiered"),
        rating = o.optDouble("rating"),
        showTitle = o.optString("showTitle"),
        seasonNumber = o.optInt("seasonNumber"),
        episodeNumber = o.optInt("episodeNumber"),
        episodeTitle = o.optString("episodeTitle"),
        actors = (0 until actors.length()).map { i ->
            val actor = actors.getJSONObject(i)
            Actor(
                name = actor.optString("name"),
                role = actor.optString("role"),
                thumb = actor.optString("thumb"),
            )
        },
    )
}

private fun itemToJson(item: PopItem): JSONObject {
    return JSONObject()
        .put("id", item.id)
        .put("libraryId", item.libraryId)
        .put("kind", item.kind)
        .put("title", item.title)
        .put("originalTitle", item.originalTitle)
        .put("year", item.year)
        .put("durationMs", item.durationMs)
        .put("videoCodec", item.videoCodec)
        .put("audioCodec", item.audioCodec)
        .put("imdbId", item.imdbId)
        .put("tmdbId", item.tmdbId)
        .put("tvdbId", item.tvdbId)
        .put("width", item.width)
        .put("height", item.height)
        .put("posterPath", item.posterPath)
        .put("posterMtimeUnix", item.posterMtimeUnix)
        .put("backdropPath", item.backdropPath)
        .put("backdropMtimeUnix", item.backdropMtimeUnix)
        .put("overview", item.overview)
        .put("tagline", item.tagline)
        .put("officialRating", item.officialRating)
        .put("genres", item.genres)
        .put("tags", item.tags)
        .put("studios", item.studios)
        .put("directors", item.directors)
        .put("writers", item.writers)
        .put("countries", item.countries)
        .put("premiered", item.premiered)
        .put("rating", item.rating)
        .put("showTitle", item.showTitle)
        .put("seasonNumber", item.seasonNumber)
        .put("episodeNumber", item.episodeNumber)
        .put("episodeTitle", item.episodeTitle)
}

private fun jsonToActor(o: JSONObject): Actor {
    return Actor(
        name = o.optString("name"),
        role = o.optString("role"),
        thumb = o.optString("thumb"),
    )
}

private fun jsonToActorInfo(o: JSONObject): ActorInfo {
    return ActorInfo(
        name = o.optString("name"),
        tmdbId = o.optString("tmdbId"),
        imdbId = o.optString("imdbId"),
        biography = o.optString("biography"),
        birthday = o.optString("birthday"),
        deathday = o.optString("deathday"),
        placeOfBirth = o.optString("placeOfBirth"),
        knownForDepartment = o.optString("knownForDepartment"),
        profilePath = o.optString("profilePath"),
        source = o.optString("source"),
    )
}

fun JSONObject.optIntOrNull(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}

fun JSONObject.optLongOrNull(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

class Api(private val session: Session) {
    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val json = request("/api/auth/login", "POST", body)
        val user = json.optJSONObject("user")
        Session(
            server = session.server,
            token = json.getString("token"),
            username = user?.optString("username").orEmpty(),
            isAdmin = user?.optBoolean("isAdmin") ?: false,
            userId = user?.optLong("id") ?: 0L,
            avatar = user?.optString("avatar").orEmpty(),
            displayName = user?.optString("displayName").orEmpty(),
        )
    }

    suspend fun me(): User = withContext(Dispatchers.IO) {
        jsonToUser(request("/api/auth/me"))
    }

    suspend fun startQrLogin(deviceName: String): QRLoginStart = withContext(Dispatchers.IO) {
        val json = request("/api/auth/qr/start", "POST", JSONObject().put("deviceName", deviceName).toString())
        QRLoginStart(json.getString("code"), json.optString("expiresAt"))
    }

    suspend fun approveQrLogin(code: String) = withContext(Dispatchers.IO) {
        request("/api/auth/qr/complete", "POST", JSONObject().put("code", code).toString())
    }

    suspend fun pollQrLogin(code: String): Session? = withContext(Dispatchers.IO) {
        val json = request("/api/auth/qr/poll?code=${enc(code)}")
        if (json.optString("status") != "approved") return@withContext null
        val user = json.optJSONObject("user")
        Session(
            server = session.server,
            token = json.getString("token"),
            username = user?.optString("username").orEmpty(),
            isAdmin = user?.optBoolean("isAdmin") ?: false,
            userId = user?.optLong("id") ?: 0L,
            avatar = user?.optString("avatar").orEmpty(),
            displayName = user?.optString("displayName").orEmpty(),
        )
    }

    suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/libraries")
        (0 until arr.length()).map { i ->
            jsonToLibrary(arr.getJSONObject(i))
        }
    }

    suspend fun home(): HomePayload = withContext(Dispatchers.IO) {
        val json = request("/api/home")
        val libraries = json.optJSONArray("libraries") ?: JSONArray()
        val watchlistJson = json.optJSONObject("watchlist") ?: JSONObject()
        HomePayload(
            user = jsonToUser(json.optJSONObject("user") ?: JSONObject()),
            libraries = (0 until libraries.length()).map { i -> jsonToLibrary(libraries.getJSONObject(i)) },
            homeMovies = parseItems(json.optJSONArray("homeMovies") ?: JSONArray()),
            homeShows = parseShows(json.optJSONArray("homeShows") ?: JSONArray()),
            recentMovies = parseItems(json.optJSONArray("recentMovies") ?: JSONArray()),
            recentShows = parseShows(json.optJSONArray("recentShows") ?: JSONArray()),
            continueMovies = parseItems(json.optJSONArray("continueMovies") ?: JSONArray()),
            continueEpisodes = parseItems(json.optJSONArray("continueEpisodes") ?: JSONArray()),
            progress = parseProgressList(json.optJSONArray("progress") ?: JSONArray()),
            showProgress = parseShowProgressList(json.optJSONArray("showProgress") ?: JSONArray()),
            watchlist = parseWatchlist(watchlistJson),
            recommendations = parseRecommendations(json.optJSONArray("recommendations") ?: JSONArray()),
            excludedRecommendationKeys = jsonStringSet(json.optJSONArray("excludedRecommendationKeys") ?: JSONArray()),
        )
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

    suspend fun itemsPage(libraryId: String, limit: Int, offset: Int, genre: String, sort: String = "", minRating: Double = 0.0, seenStatus: String = "", decades: String = ""): List<PopItem> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        val sortParam = if (sort.isNotBlank()) "&sort=${enc(sort)}" else ""
        val ratingParam = if (minRating > 0.0) "&minRating=$minRating" else ""
        val seenParam = if (seenStatus.isNotBlank()) "&seen=${enc(seenStatus)}" else ""
        val decadesParam = if (decades.isNotBlank()) "&decades=${enc(decades)}" else ""
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset$genreParam$sortParam$ratingParam$seenParam$decadesParam"))
    }

    suspend fun recentItems(libraryId: String, limit: Int): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items?libraryId=${enc(libraryId)}&limit=$limit&offset=0&sort=mtime"))
    }

    suspend fun similar(itemId: Long): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestArray("/api/items/$itemId/similar"))
    }

    suspend fun searchMovies(query: String, fields: String = "title"): List<PopItem> = withContext(Dispatchers.IO) {
        parseItems(requestItemsEnvelope("/api/search?limit=500&kind=movie&q=${enc(query)}&fields=${enc(fields)}"))
    }

    suspend fun searchShows(query: String, fields: String = "title"): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?limit=500&q=${enc(query)}&fields=${enc(fields)}"))
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

    suspend fun showsPage(libraryId: String, limit: Int, offset: Int, genre: String, sort: String = "", minRating: Double = 0.0, seenStatus: String = "", decades: String = ""): List<ShowSummary> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        val sortParam = if (sort.isNotBlank()) "&sort=${enc(sort)}" else ""
        val ratingParam = if (minRating > 0.0) "&minRating=$minRating" else ""
        val seenParam = if (seenStatus.isNotBlank()) "&seen=${enc(seenStatus)}" else ""
        val decadesParam = if (decades.isNotBlank()) "&decades=${enc(decades)}" else ""
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=$offset$genreParam$sortParam$ratingParam$seenParam$decadesParam"))
    }

    suspend fun recentShows(libraryId: String, limit: Int): List<ShowSummary> = withContext(Dispatchers.IO) {
        parseShows(requestArray("/api/tv/shows?libraryId=${enc(libraryId)}&limit=$limit&offset=0&sort=mtime"))
    }

    suspend fun genres(libraryId: String): List<String> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/genres?libraryId=${enc(libraryId)}")
        (0 until arr.length()).map { i -> arr.getString(i) }
    }

    suspend fun decades(libraryId: String, kind: String): List<Int> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/decades?libraryId=${enc(libraryId)}&kind=${enc(kind)}")
        (0 until arr.length()).map { i -> arr.optInt(i) }.filter { it > 0 }
    }

    suspend fun scanLibraries() = withContext(Dispatchers.IO) {
        requestText("/api/scan", "POST", "{}")
    }

    suspend fun scanStatus(): List<ScanStatus> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/scan")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScanStatus(
                libraryId = o.optString("libraryId"),
                status = o.optString("status"),
                finishedAt = o.optString("finishedAt"),
                mediaFound = o.optLong("mediaFound"),
                itemsImported = o.optLong("itemsImported"),
                errors = o.optLong("errors"),
            )
        }
    }

    suspend fun alphabet(libraryId: String, kind: String, genre: String = "", decades: String = ""): List<AlphabetEntry> = withContext(Dispatchers.IO) {
        val genreParam = if (genre.isNotBlank()) "&genre=${enc(genre)}" else ""
        val decadesParam = if (decades.isNotBlank()) "&decades=${enc(decades)}" else ""
        val arr = requestArray("/api/alphabet?libraryId=${enc(libraryId)}&kind=${enc(kind)}$genreParam$decadesParam")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AlphabetEntry(
                letter = o.optString("letter"),
                offset = o.optInt("offset"),
                count = o.optInt("count"),
            )
        }
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

    suspend fun showActors(libraryId: String, showTitle: String): List<Actor> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/shows/actors?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}")
        (0 until arr.length()).map { i -> jsonToActor(arr.getJSONObject(i)) }
    }

    suspend fun episodes(libraryId: String, showTitle: String, season: Int): List<PopItem> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/episodes?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season")
        parseItems(arr)
    }

    suspend fun episodes(libraryId: String, showTitle: String): List<PopItem> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/tv/episodes?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}")
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

    suspend fun playbackFailure(itemId: Long, plan: PlaybackPlan, errorCode: String, message: String, positionMs: Long = 0L): PlaybackPlan? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("itemId", itemId)
            .put("planId", plan.planId)
            .put("mode", plan.mode)
            .put("client", "android-tv")
            .put("errorCode", errorCode)
            .put("message", message)
            .put("positionMs", positionMs)
        val json = request("/api/playback/failure", "POST", body.toString())
        if (!json.optBoolean("retry")) return@withContext null
        json.optJSONObject("plan")?.let(::jsonToPlaybackPlan)
    }

    suspend fun itemSidecars(itemId: Long): SidecarStatus = withContext(Dispatchers.IO) {
        val o = request("/api/items/$itemId/sidecars")
        SidecarStatus(trailer = o.optBoolean("trailer"), theme = o.optBoolean("theme"))
    }

    suspend fun showTheme(libraryId: String, showTitle: String): SidecarStatus = withContext(Dispatchers.IO) {
        val o = request("/api/tv/theme?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}")
        SidecarStatus(theme = o.optBoolean("theme"))
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

    suspend fun actorDetail(name: String): ActorDetail = withContext(Dispatchers.IO) {
        val o = request("/api/actors?name=${enc(name)}")
        val movies = o.optJSONArray("movies") ?: JSONArray()
        val shows = o.optJSONArray("shows") ?: JSONArray()
        ActorDetail(
            actor = jsonToActor(o.optJSONObject("actor") ?: JSONObject()),
            info = jsonToActorInfo(o.optJSONObject("info") ?: JSONObject()),
            profileUrl = o.optString("profileUrl"),
            movies = parseItems(movies),
            shows = parseShows(shows),
        )
    }

    suspend fun progressList(): List<PlaybackProgress> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlaybackProgress>()
        val limit = 500
        var offset = 0
        while (true) {
            val arr = requestArray("/api/progress?limit=$limit&offset=$offset")
            out.addAll(parseProgressList(arr))
            if (arr.length() < limit) break
            offset += limit
        }
        out
    }

    suspend fun showProgress(): List<ShowProgress> = withContext(Dispatchers.IO) {
        parseShowProgressList(requestArray("/api/progress/tv"))
    }

    suspend fun watchlist(): Watchlist = withContext(Dispatchers.IO) {
        parseWatchlist(request("/api/watchlist?limit=300"))
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

    suspend fun tvUpdate(currentVersionCode: Int): AppUpdateInfo = withContext(Dispatchers.IO) {
        val json = request("/api/app/tv/update?versionCode=$currentVersionCode")
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

    suspend fun downloadTvUpdate(info: AppUpdateInfo, outFile: File): File = withContext(Dispatchers.IO) {
        outFile.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = openConnection(info.apkUrl)
        conn.requestMethod = "GET"
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error(text.ifBlank { "HTTP $code" })
        }
        conn.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256.isNotBlank() && !actual.equals(info.sha256, ignoreCase = true)) {
            outFile.delete()
            error("Downloaded APK checksum mismatch")
        }
        outFile
    }

    suspend fun saveProgress(itemId: Long, positionMs: Long, durationMs: Long, completed: Boolean, state: String, continuousMs: Long? = null) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("positionMs", positionMs)
            .put("durationMs", durationMs)
            .put("completed", completed)
            .put("state", state)
        if (continuousMs != null) payload.put("continuousMs", continuousMs.coerceAtLeast(0))
        request("/api/items/$itemId/progress", "PUT", payload.toString())
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

    suspend fun markSeasonWatched(libraryId: String, showTitle: String, season: Int) = withContext(Dispatchers.IO) {
        request("/api/progress/tv/season?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season", "PUT", "{}")
    }

    suspend fun unmarkSeasonWatched(libraryId: String, showTitle: String, season: Int) = withContext(Dispatchers.IO) {
        requestText("/api/progress/tv/season?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}&season=$season", "DELETE", null)
    }

    suspend fun userRatings(): List<UserRatingRow> = withContext(Dispatchers.IO) {
        val arr = requestArray("/api/ratings/user")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UserRatingRow(
                kind = o.optString("kind"),
                itemId = o.optLong("itemId"),
                libraryId = o.optString("libraryId"),
                showTitle = o.optString("showTitle"),
                rating = o.optInt("rating"),
            )
        }
    }

    suspend fun setItemRating(itemId: Long, rating: Int) = withContext(Dispatchers.IO) {
        request("/api/items/$itemId/rating", "PUT", "{\"rating\":$rating}")
    }

    suspend fun deleteItemRating(itemId: Long) = withContext(Dispatchers.IO) {
        requestText("/api/items/$itemId/rating", "DELETE", null)
    }

    suspend fun setShowRating(libraryId: String, showTitle: String, rating: Int) = withContext(Dispatchers.IO) {
        request("/api/ratings/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "PUT", "{\"rating\":$rating}")
    }

    suspend fun deleteShowRating(libraryId: String, showTitle: String) = withContext(Dispatchers.IO) {
        requestText("/api/ratings/tv?libraryId=${enc(libraryId)}&showTitle=${enc(showTitle)}", "DELETE", null)
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

    suspend fun clientLog(body: JSONObject) = withContext(Dispatchers.IO) {
        requestText("/api/client/log", "POST", body.toString())
    }

    private fun parseItems(arr: JSONArray): List<PopItem> = (0 until arr.length()).map { i ->
        jsonToItem(arr.getJSONObject(i))
    }

    private fun parseRecommendations(arr: JSONArray): List<Recommendation> = (0 until arr.length()).map { index ->
        val row = arr.getJSONObject(index)
        Recommendation(
            key = row.optString("key"),
            reason = row.optString("reason"),
            source = row.optString("source"),
            item = row.optJSONObject("item")?.let(::jsonToItem),
            show = row.optJSONObject("show")?.let(::jsonToShow),
        )
    }

    private fun jsonStringSet(arr: JSONArray): Set<String> =
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()

    private fun parseProgressList(arr: JSONArray): List<PlaybackProgress> {
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PlaybackProgress(
                itemId = o.optLong("itemId"),
                positionMs = o.optLong("positionMs"),
                durationMs = o.optLong("durationMs"),
                completed = o.optBoolean("completed"),
            )
        }
    }

    private fun parseShowProgressList(arr: JSONArray): List<ShowProgress> {
        return (0 until arr.length()).map { i ->
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

    private fun parseWatchlist(json: JSONObject): Watchlist {
        return Watchlist(
            items = parseItems(json.optJSONArray("items") ?: JSONArray()),
            shows = parseShows(json.optJSONArray("shows") ?: JSONArray()),
        )
    }

    private fun jsonToPlaybackPlan(o: JSONObject): PlaybackPlan {
        val selected = o.optJSONObject("selected") ?: JSONObject()
        val reasons = o.optJSONArray("reasons") ?: JSONArray()
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
            reasons = (0 until reasons.length()).map { reasons.optString(it) },
        )
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
        val conn = openConnection(path)
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

    private fun openConnection(path: String): HttpURLConnection {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else session.server + path
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        if (session.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${session.token}")
        return conn
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
