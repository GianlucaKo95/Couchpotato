package de.koeven.couchpotato.data

import android.content.Context
import de.koeven.couchpotato.BuildConfig
import kotlinx.coroutines.Dispatchers
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
    private val settings = SettingsStore(context)
    private val dir = File(context.filesDir, "tmdb")

    /** Token from the build (repo secret) or set on the stick via adb. */
    val token: String get() = settings.tmdbToken?.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_TOKEN
    val enabled: Boolean get() = token.isNotBlank()

    /**
     * @param kind SearchEntry.KIND_MOVIE / KIND_SERIES
     * @param providerId id at the IPTV provider (cache key)
     */
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
                        return@withContext parse(cached, tv)
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
            parse(json, tv)
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
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        if (v4) c.setRequestProperty("Authorization", "Bearer $t")
        try {
            val code = c.responseCode
            if (code == 401) throw IOException("TMDB-Schlüssel ungültig")
            if (code !in 200..299) throw IOException("TMDB antwortet mit HTTP $code")
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
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
