package de.koeven.couchpotato.data

import android.content.Context
import de.koeven.couchpotato.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Everything the info screens show about a film or series. */
data class TitleDetails(
    val plot: String?,
    val tagline: String?,
    val genres: List<String>,
    val year: String?,
    val runtimeMin: Int,
    val rating: Float,            // 0–10
    val votes: Int,
    val fsk: String?,             // "0", "6", "12", "16", "18"
    val cast: List<String>,       // "Name (Rolle)"
    val director: String?,        // films: director, series: creator
    val country: String?,
    val trailer: String?,         // YouTube id
    val backdrop: String?,
    val poster: String?,
    val fromTmdb: Boolean,
    val tmdbId: Long? = null,
    val tv: Boolean = false,
) {
    companion object {
        /** Provider details (get_vod_info) in the same shape – used when TMDB is off or doesn't know the film. */
        fun fromProvider(m: MovieInfo) = TitleDetails(
            plot = m.plot, tagline = null,
            genres = m.genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            year = m.year, runtimeMin = m.durationMin, rating = m.rating, votes = 0, fsk = null,
            cast = m.cast?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            director = m.director, country = m.country, trailer = m.trailer,
            backdrop = m.backdrop, poster = m.cover, fromTmdb = false,
        )
    }
}

/**
 * TMDB (themoviedb.org) – where most covers come from. German texts, FSK, cast, rating, trailer.
 * A separate service: these requests never reach the IPTV provider and are not limited by it.
 * Every answer (also "not found") is kept on the stick, so each title is asked for once.
 */
class Tmdb(context: Context) {
    init { TmdbGate.init(context) }
    private val settings = SettingsStore(context)
    private val dir = File(context.filesDir, "tmdb")

    /** Token from the build (repo secret) or set on the stick via adb. */
    val token: String get() = settings.tmdbToken?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_TOKEN
    val enabled: Boolean get() = token.isNotBlank()

    /**
     * @param kind SearchEntry.KIND_MOVIE / KIND_SERIES
     * @param providerId id at the IPTV provider (cache key)
     */
    // ---------------- ratings already on the stick (for sorting the Mediathek) ----------------

    private var ratings: MutableMap<String, Float>? = null

    /** TMDB rating of a title whose details were fetched before – no request. */
    fun cachedRating(kind: Char, providerId: Long): Float? = ratingIndex()["${kind}_$providerId"]

    @Synchronized
    private fun ratingIndex(): MutableMap<String, Float> {
        ratings?.let { return it }
        val idx = File(dir, "ratings.tsv")
        val map = HashMap<String, Float>()
        if (idx.exists()) {
            idx.forEachLine { l -> l.split('\t').takeIf { it.size == 2 }?.let { (k, v) -> v.toFloatOrNull()?.let { map[k] = it } } }
        } else {
            // first time: collect from the details already cached
            dir.listFiles { f -> f.isFile && f.name.matches(Regex("""[a-z]_\d+\.json""")) }?.forEach { f ->
                runCatching { JSONObject(f.readText()) }.getOrNull()?.takeIf { !it.optBoolean("notFound") }?.let { o ->
                    val r = o.optDouble("vote_average", 0.0).toFloat()
                    if (r > 0f && o.optInt("vote_count") >= 5) map[f.name.removeSuffix(".json")] = r
                }
            }
            runCatching { dir.mkdirs(); idx.writeText(map.entries.joinToString("") { "${it.key}\t${it.value}\n" }) }
        }
        return map.also { ratings = it }
    }

    @Synchronized
    private fun rememberRating(kind: Char, providerId: Long, d: TitleDetails) {
        if (d.rating <= 0f || d.votes < 5) return
        val key = "${kind}_$providerId"
        val map = ratingIndex()
        if (map[key] == d.rating) return
        map[key] = d.rating
        runCatching { File(dir, "ratings.tsv").appendText("$key\t${d.rating}\n") }
    }

    /**
     * Plot of one episode. Kept on the stick for good; with [fetch] one request the first time,
     * otherwise only what is already there.
     */
    suspend fun episodePlot(tvId: Long, season: Int, episode: Int, fetch: Boolean): String? = withContext(Dispatchers.IO) {
        val f = File(dir, "ep/${tvId}_${season}_$episode.json")
        if (f.exists()) return@withContext runCatching { JSONObject(f.readText()).optString("overview").ifBlank { null } }.getOrNull()
        if (!fetch || !enabled) return@withContext null
        val json = runCatching { get("tv/$tvId/season/$season/episode/$episode?language=de-DE") }.getOrNull()
            ?: return@withContext null      // offline / TMDB busy: try again another time
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("overview", json.optString("overview")).toString())
        json.optString("overview").ifBlank { null }
    }

    /** Details already on the stick – never a request (for the info area on the home screen). */
    fun cachedDetails(kind: Char, providerId: Long): TitleDetails? {
        val f = File(dir, "${kind}_$providerId.json")
        if (!f.exists()) return null
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        if (o.optBoolean("notFound")) return null
        return runCatching { parse(o, kind == SearchEntry.KIND_SERIES) }.getOrNull()
    }

    suspend fun details(kind: Char, providerId: Long, name: String, year: String?, tmdbId: Long?): TitleDetails? =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext null
            val tv = kind == SearchEntry.KIND_SERIES
            val f = File(dir, "${kind}_$providerId.json")
            if (f.exists()) {
                val cached = runCatching { JSONObject(f.readText()) }.getOrNull()
                if (cached != null) {
                    if (cached.optBoolean("notFound")) {
                        // try again after a week (maybe TMDB knows it by then)
                        if (System.currentTimeMillis() - f.lastModified() < 7 * DAY) return@withContext null
                    } else {
                        return@withContext parse(cached, tv)?.also { rememberRating(kind, providerId, it) }
                    }
                }
            }
            val id = tmdbId ?: search(tv, name, year)
            if (id == null) {
                dir.mkdirs()
                f.writeText(JSONObject().put("notFound", true).toString())
                return@withContext null
            }
            val append = if (tv) "credits,content_ratings,videos" else "credits,release_dates,videos"
            val path = if (tv) "tv/$id" else "movie/$id"
            val json = get("$path?language=de-DE&append_to_response=$append&include_video_language=de,en")
            dir.mkdirs()
            f.writeText(json.toString())
            parse(json, tv)?.also { rememberRating(kind, providerId, it) }
        }

    // ---------------- titles from other apps (ARD, ZDF …) ----------------

    /**
     * Details for a title that only comes with its name (e.g. "Werwölfe" from the ARD app):
     * tries the [names] one after another (see Names.lookupNames), takes a TMDB hit only if the
     * name matches exactly. Every answer is kept on the stick (also "not found").
     * With [fetch] = false nothing is requested.
     */
    suspend fun byTitle(names: List<String>, fetch: Boolean): TitleDetails? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        var asked = false
        for (name in names) {
            val r = byTitleOne(name, fetch)
            if (r.second) asked = true
            r.first?.let { found ->
                if (asked) logLookup(names, name)
                return@withContext found
            }
        }
        if (asked) logLookup(names, null)
        null
    }

    /** One name: (details or null, true if TMDB was asked). */
    private fun byTitleOne(title: String, fetch: Boolean): Pair<TitleDetails?, Boolean> {
        val norm = SearchIndex.normalize(title)
        // placeholders some apps send instead of a name: nothing to look up
        if (norm.length < 3 || norm in setOf("film", "folge", "serie", "video", "live", "livestream")) return null to false
        val f = File(dir, "x/" + norm.replace(' ', '_').take(80) + ".json")
        if (f.exists()) {
            val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (o != null && !o.optBoolean("notFound")) return runCatching { parse(o, o.optBoolean("_tv")) }.getOrNull() to false
            if (o != null && System.currentTimeMillis() - f.lastModified() < 7 * DAY) return null to false
        }
        if (!fetch) return null to false
        val hit = runCatching {
            get("search/multi?query=${URLEncoder.encode(title, "UTF-8")}&language=de-DE").optJSONArray("results")
                .objects().firstOrNull { r ->
                    r.str("media_type") in setOf("tv", "movie") &&
                        listOfNotNull(r.str("name"), r.str("title"), r.str("original_name"), r.str("original_title"))
                            .any { SearchIndex.normalize(it) == norm }
                }
        }.getOrElse { return null to true }      // offline / TMDB busy: try again another time
        f.parentFile?.mkdirs()
        if (hit == null) {
            f.writeText(JSONObject().put("notFound", true).toString())
            return null to true
        }
        val tv = hit.str("media_type") == "tv"
        val append = if (tv) "credits,content_ratings,videos" else "credits,release_dates,videos"
        val json = runCatching {
            get("${if (tv) "tv" else "movie"}/${hit.optLong("id")}?language=de-DE&append_to_response=$append&include_video_language=de,en")
        }.getOrElse { return null to true }
        f.writeText(json.put("_tv", tv).put("_name", hit.str("name") ?: hit.str("title")).toString())
        return runCatching { parse(json, tv) }.getOrNull() to true
    }

    private fun logLookup(names: List<String>, found: String?) = runCatching {
        val f = File(dir, "x/_log.txt")
        f.parentFile?.mkdirs()
        val line = names.joinToString(" / ") { "„$it“" } + " → " + (found?.let { "gefunden als „$it“" } ?: "nichts bei TMDB")
        val lines = (if (f.exists()) f.readLines() else emptyList()) + line
        f.writeText(lines.takeLast(10).joinToString("\n"))
    }

    /** The last lookups for titles from other apps, newest first (for the settings). */
    fun lookupLog(): List<String> =
        runCatching { File(dir, "x/_log.txt").readLines().filter { it.isNotBlank() }.reversed() }.getOrDefault(emptyList())

    // ---------------- streaming services (data: JustWatch via TMDB) ----------------

    /** A film/series found at TMDB, with the streaming services that have it in Germany (flatrate/free). */
    data class StreamHit(
        val tmdbId: Long,
        val tv: Boolean,
        val title: String,
        val year: String?,
        val poster: String?,
        val providers: List<String>,
    )

    /**
     * Cross-app search: films and series matching [query] and where they stream in Germany.
     * Only TMDB is asked (1 search + 1 small request per hit), never the IPTV provider.
     */
    suspend fun searchStreaming(query: String, limit: Int = 14): List<StreamHit> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val q = query.trim()
        synchronized(searchCache) { searchCache[q.lowercase()] }?.let { return@withContext it }
        readHits(diskFile("search", q), 3 * DAY)?.let { cached ->
            synchronized(searchCache) { searchCache[q.lowercase()] = cached }
            return@withContext cached
        }
        val res = get("search/multi?query=${URLEncoder.encode(q, "UTF-8")}&language=de-DE&include_adult=false")
            .optJSONArray("results").objects()
            .filter { it.str("media_type") == "movie" || it.str("media_type") == "tv" }
            .take(limit)
        val hits = coroutineScope {
            res.map { o ->
                async {
                    val tv = o.str("media_type") == "tv"
                    val id = o.optLong("id")
                    val provs = runCatching { providers(tv, id) }.getOrDefault(emptyList())
                    StreamHit(
                        tmdbId = id, tv = tv,
                        title = (if (tv) o.str("name") else o.str("title")) ?: "",
                        year = (if (tv) o.str("first_air_date") else o.str("release_date"))?.take(4)?.takeIf { it.length == 4 },
                        poster = o.str("poster_path")?.let { "$IMG/w342$it" },
                        providers = provs,
                    )
                }
            }.awaitAll()
        }.filter { it.title.isNotBlank() && it.providers.isNotEmpty() }.distinctBy { "${it.tv}${it.tmdbId}" }
        synchronized(searchCache) {
            if (searchCache.size > 40) searchCache.remove(searchCache.keys.first())
            searchCache[q.lowercase()] = hits
        }
        writeHits(diskFile("search", q), hits)
        hits
    }

    /** Streaming services (flatrate, free, with ads) in Germany; kept 3 days on the stick. */
    suspend fun providers(tv: Boolean, tmdbId: Long): List<String> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val f = File(dir, "prov_${if (tv) "tv" else "movie"}_$tmdbId.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3 * DAY) {
            runCatching { return@withContext JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }
        }
        val de = get("${if (tv) "tv" else "movie"}/$tmdbId/watch/providers").optJSONObject("results")?.optJSONObject("DE")
        val list = listOf("flatrate", "free", "ads")
            .flatMap { k -> de?.optJSONArray(k).objects() }
            .sortedBy { it.optInt("display_priority", 99) }
            .mapNotNull { it.str("provider_name") }
            .distinct()
        dir.mkdirs()
        f.writeText(JSONArray(list).toString())
        list
    }

    private val searchCache = LinkedHashMap<String, List<StreamHit>>()

    // ---------------- themes ("Weihnachten", "Fußball") ----------------

    /** A film/series TMDB tags with a theme. */
    data class ThemeHit(
        val tmdbId: Long,
        val tv: Boolean,
        val title: String,
        val originalTitle: String?,
        val year: String?,
        val poster: String?,
    )

    private val keywordCache = HashMap<String, List<Long>>()
    private val themeCache = LinkedHashMap<String, List<ThemeHit>>()

    /** TMDB keyword ids for a word ("christmas" → 207317 …). */
    private fun keywordIds(name: String, exact: Boolean): List<Long> {
        synchronized(keywordCache) { keywordCache[name] }?.let { return it }
        // keyword ids never change: kept on the stick for good
        val f = diskFile("kw", "$exact:$name")
        if (f.exists()) runCatching {
            val a = JSONArray(f.readText())
            val ids = (0 until a.length()).map { a.getLong(it) }
            synchronized(keywordCache) { keywordCache[name] = ids }
            return ids
        }
        val res = get("search/keyword?query=${URLEncoder.encode(name, "UTF-8")}").optJSONArray("results").objects()
        val ids = (if (exact) res.filter { it.str("name").equals(name, ignoreCase = true) }.ifEmpty { res.take(1) } else res.take(2))
            .map { it.optLong("id") }.filter { it > 0 }
        synchronized(keywordCache) { keywordCache[name] = ids }
        f.parentFile?.mkdirs()
        f.writeText(JSONArray(ids).toString())
        return ids
    }

    /**
     * Popular films and series on a theme: keywords and/or genres from [theme], or – without a theme –
     * a free keyword lookup of [freeWords]. About 4–8 small TMDB requests, never the IPTV provider.
     */
    suspend fun themeTitles(theme: Theme?, freeWords: String?): List<ThemeHit> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val cacheKey = theme?.label ?: "free:$freeWords"
        synchronized(themeCache) { themeCache[cacheKey] }?.let { return@withContext it }
        readThemes(diskFile("theme", cacheKey), 14 * DAY)?.let { cached ->
            synchronized(themeCache) { themeCache[cacheKey] = cached }
            return@withContext cached
        }
        val kw = when {
            theme != null -> theme.keywords.flatMap { keywordIds(it, exact = true) }
            !freeWords.isNullOrBlank() -> keywordIds(freeWords, exact = false)
            else -> emptyList()
        }.distinct()
        val mg = theme?.movieGenres.orEmpty()
        val tg = theme?.tvGenres.orEmpty()
        if (kw.isEmpty() && mg.isEmpty() && tg.isEmpty()) return@withContext emptyList()
        val kwParam = if (kw.isNotEmpty()) "&with_keywords=" + kw.joinToString("|") else ""
        fun discover(tv: Boolean, genres: List<Int>, pages: Int): List<ThemeHit> {
            if (kw.isEmpty() && genres.isEmpty()) return emptyList()
            val gParam = if (genres.isNotEmpty()) "&with_genres=" + genres.joinToString("|") else ""
            val type = if (tv) "tv" else "movie"
            return (1..pages).flatMap { page ->
                runCatching {
                    get("discover/$type?language=de-DE&sort_by=popularity.desc&include_adult=false&vote_count.gte=15$kwParam$gParam&page=$page")
                        .optJSONArray("results").objects()
                }.getOrDefault(emptyList())
            }.map { o ->
                ThemeHit(
                    tmdbId = o.optLong("id"), tv = tv,
                    title = (if (tv) o.str("name") else o.str("title")) ?: "",
                    originalTitle = if (tv) o.str("original_name") else o.str("original_title"),
                    year = (if (tv) o.str("first_air_date") else o.str("release_date"))?.take(4)?.takeIf { it.length == 4 },
                    poster = o.str("poster_path")?.let { "$IMG/w342$it" },
                )
            }
        }
        // genre-only themes (Komödie …) are huge: keep to the most popular; keyword themes go deeper
        val moviePages = if (kw.isNotEmpty()) 4 else 3
        val tvPages = if (kw.isNotEmpty()) 2 else 2
        val hits = coroutineScope {
            val m = async { discover(false, mg, moviePages) }
            val t = async { if (kw.isNotEmpty() || tg.isNotEmpty()) discover(true, tg, tvPages) else emptyList() }
            m.await() + t.await()
        }.filter { it.title.isNotBlank() }.distinctBy { "${it.tv}${it.tmdbId}" }
        synchronized(themeCache) {
            if (themeCache.size > 30) themeCache.remove(themeCache.keys.first())
            themeCache[cacheKey] = hits
        }
        if (hits.isNotEmpty()) writeThemes(diskFile("theme", cacheKey), hits)
        hits
    }

    // ---------------- results kept on the stick ----------------

    private fun diskFile(kind: String, key: String): File =
        File(dir, "$kind/${key.lowercase().trim().hashCode().toUInt()}.json")

    private fun fresh(f: File, maxAge: Long) = f.exists() && System.currentTimeMillis() - f.lastModified() < maxAge

    private fun readHits(f: File, maxAge: Long): List<StreamHit>? = if (!fresh(f, maxAge)) null else runCatching {
        JSONArray(f.readText()).objects().map { o ->
            StreamHit(
                o.getLong("id"), o.optBoolean("tv"), o.getString("title"), o.str("year"), o.str("poster"),
                o.optJSONArray("prov")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            )
        }
    }.getOrNull()

    private fun writeHits(f: File, hits: List<StreamHit>) = runCatching {
        f.parentFile?.mkdirs()
        f.writeText(JSONArray(hits.map {
            JSONObject().put("id", it.tmdbId).put("tv", it.tv).put("title", it.title)
                .put("year", it.year ?: JSONObject.NULL).put("poster", it.poster ?: JSONObject.NULL)
                .put("prov", JSONArray(it.providers))
        }).toString())
    }

    private fun readThemes(f: File, maxAge: Long): List<ThemeHit>? = if (!fresh(f, maxAge)) null else runCatching {
        JSONArray(f.readText()).objects().map { o ->
            ThemeHit(o.getLong("id"), o.optBoolean("tv"), o.getString("title"), o.str("orig"), o.str("year"), o.str("poster"))
        }
    }.getOrNull()

    private fun writeThemes(f: File, hits: List<ThemeHit>) = runCatching {
        f.parentFile?.mkdirs()
        f.writeText(JSONArray(hits.map {
            JSONObject().put("id", it.tmdbId).put("tv", it.tv).put("title", it.title)
                .put("orig", it.originalTitle ?: JSONObject.NULL).put("year", it.year ?: JSONObject.NULL)
                .put("poster", it.poster ?: JSONObject.NULL)
        }).toString())
    }

    // ---------------------------------------------------------------------------------

    private fun search(tv: Boolean, rawName: String, year: String?): Long? {
        val (name, nameYear) = cleanName(rawName)
        if (name.isBlank()) return null
        val y = year ?: nameYear
        val q = URLEncoder.encode(name, "UTF-8")
        val type = if (tv) "tv" else "movie"
        val yearParam = when {
            y == null -> ""
            tv -> "&first_air_date_year=$y"
            else -> "&year=$y"
        }
        var results = get("search/$type?query=$q&language=de-DE$yearParam").optJSONArray("results")
        if ((results == null || results.length() == 0) && yearParam.isNotEmpty()) {
            results = get("search/$type?query=$q&language=de-DE").optJSONArray("results")   // year may be off by one
        }
        val first = results?.optJSONObject(0) ?: return null
        return first.optLong("id").takeIf { it > 0 }
    }

    private fun get(path: String): JSONObject {
        val t = token
        val v4 = t.length > 40      // long "read access token" (v4) vs. short API key (v3)
        val sep = if ('?' in path) '&' else '?'
        val url = "https://api.themoviedb.org/3/$path" + if (v4) "" else "${sep}api_key=$t"
        var retryAfter: Long? = null
        // through the brake: max. 4 at a time, 20 per second, pause on "429 too many requests"
        return TmdbGate.run({
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            if (v4) c.setRequestProperty("Authorization", "Bearer $t")
            try {
                val code = c.responseCode
                retryAfter = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                when {
                    code == 429 -> code to null
                    code == 401 -> throw IOException("TMDB-Schlüssel ungültig")
                    code !in 200..299 -> throw IOException("TMDB antwortet mit HTTP $code")
                    else -> code to JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                }
            } finally {
                c.disconnect()
            }
        }, { retryAfter })
    }

    private fun parse(o: JSONObject, tv: Boolean): TitleDetails {
        val genres = o.optJSONArray("genres").objects().mapNotNull { it.str("name") }
        val date = if (tv) o.str("first_air_date") else o.str("release_date")
        val runtime = if (tv) o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime")
        val credits = o.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast").objects().take(8).mapNotNull { c ->
            val n = c.str("name") ?: return@mapNotNull null
            val role = c.str("character")?.takeIf { it.isNotBlank() }
            if (role != null) "$n ($role)" else n
        }
        val director = if (tv) {
            o.optJSONArray("created_by").objects().mapNotNull { it.str("name") }.take(2).joinToString().ifBlank { null }
        } else {
            credits?.optJSONArray("crew").objects().filter { it.str("job") == "Director" }
                .mapNotNull { it.str("name") }.take(2).joinToString().ifBlank { null }
        }
        val fsk = if (tv) {
            o.optJSONObject("content_ratings")?.optJSONArray("results").objects()
                .firstOrNull { it.str("iso_3166_1") == "DE" }?.str("rating")
        } else {
            o.optJSONObject("release_dates")?.optJSONArray("results").objects()
                .firstOrNull { it.str("iso_3166_1") == "DE" }
                ?.optJSONArray("release_dates").objects()
                .mapNotNull { it.str("certification") }.firstOrNull { it.isNotBlank() }
        }
        val trailer = o.optJSONObject("videos")?.optJSONArray("results").objects()
            .filter { it.str("site") == "YouTube" && it.str("type") in setOf("Trailer", "Teaser") }
            .sortedBy { if (it.str("iso_639_1") == "de") 0 else 1 }
            .firstOrNull()?.str("key")
        val country = if (tv) {
            o.optJSONArray("origin_country")?.optString(0)?.takeIf { it.isNotBlank() }
        } else {
            o.optJSONArray("production_countries").objects().firstOrNull()?.str("iso_3166_1")
        }
        return TitleDetails(
            plot = o.str("overview"),
            tagline = o.str("tagline"),
            genres = genres,
            year = date?.take(4)?.takeIf { it.length == 4 },
            runtimeMin = runtime,
            rating = o.optDouble("vote_average", 0.0).toFloat().coerceIn(0f, 10f),
            votes = o.optInt("vote_count"),
            fsk = fsk?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() },
            cast = cast,
            director = director,
            country = country,
            trailer = trailer,
            backdrop = o.str("backdrop_path")?.let { "$IMG/w1280$it" },
            poster = o.str("poster_path")?.let { "$IMG/w500$it" },
            fromTmdb = true,
            tmdbId = o.optLong("id").takeIf { it > 0 },
            tv = tv,
        )
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val IMG = "https://image.tmdb.org/t/p"

        private val PREFIX = Regex("""^\s*(\|[^|]{1,12}\||\[[^\]]{1,12}\]|[A-Z]{2,4}\s*[-:|]\s+)""")
        private val YEAR = Regex("""[(\[]?\b(19\d\d|20\d\d)\b[)\]]?""")
        private val NOISE = Regex("""[(\[][^)\]]*[)\]]|\b(4K|UHD|FHD|HD|SD|HEVC|H265|MULTI|DE|GER|GERMAN|OmU|DUAL)\b""", RegexOption.IGNORE_CASE)

        /** "DE - Der Pate (1972) [4K]" → ("Der Pate", "1972") */
        fun cleanName(raw: String): Pair<String, String?> {
            var s = raw.replace(PREFIX, "")
            val year = YEAR.find(s)?.groupValues?.get(1)
            s = s.replace(YEAR, " ").replace(NOISE, " ")
            s = s.replace(Regex("""\s+"""), " ").trim().trim('-', ':', '|', ' ')
            return s to year
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
