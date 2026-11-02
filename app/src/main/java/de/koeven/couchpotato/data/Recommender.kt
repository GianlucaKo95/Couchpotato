package de.koeven.couchpotato.data

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.min

/**
 * What you have watched (films and series, newest first) – the basis for "Für dich".
 * Filled by [ProgressStore], i.e. by Couchpotato's player and by IsiPro playback.
 */
class WatchHistory(context: Context) {
    private val p = context.getSharedPreferences("history" + Profiles.suffix(context), Context.MODE_PRIVATE)

    /** key "m:<movie id>" or "s:<series id>" → last watched (ms). */
    fun all(): Map<String, Long> = p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    fun add(item: PlayItem) {
        val key = when {
            item.kind == PlayItem.KIND_MOVIE -> "m:${item.streamId}"
            item.seriesId != null -> "s:${item.seriesId}"
            else -> return
        }
        val edit = p.edit().putLong(key, System.currentTimeMillis())
        val all = all()
        if (all.size > MAX) all.entries.sortedBy { it.value }.take(all.size - MAX).forEach { edit.remove(it.key) }
        edit.apply()
    }

    fun clear() = p.edit().clear().apply()

    private companion object {
        const val MAX = 400
    }
}

/**
 * "Für dich": recommendations computed on the stick from the catalogue – no request.
 * At first mostly by the provider's rating (and how new a title is); the more you watch,
 * the more your favourite categories and genres count.
 */
object Recommender {

    data class Result(val items: List<SearchEntry>, val personal: Boolean)

    fun recommend(
        entries: List<SearchEntry>,
        hidden: Set<String>,
        history: Map<String, Long>,
        limit: Int = 30,
    ): Result {
        val byKey = entries.asSequence()
            .filter { it.kind != SearchEntry.KIND_LIVE }
            .associateBy { "${it.kind}:${it.id}" }
        val watched = history.keys.mapNotNull { byKey[it] }

        // what you like: categories and genres of what you watched (recent counts more)
        val now = System.currentTimeMillis()
        val catLove = HashMap<String, Double>()
        val genreLove = HashMap<String, Double>()
        for (w in watched) {
            val ageDays = (now - (history["${w.kind}:${w.id}"] ?: now)) / 86_400_000.0
            val weight = exp(-ageDays / 120.0)
            catLove.merge(w.categoryKey, weight, Double::plus)
            genres(w.genre).forEach { genreLove.merge(it, weight, Double::plus) }
        }
        val maxCat = catLove.values.maxOrNull() ?: 0.0
        val maxGenre = genreLove.values.maxOrNull() ?: 0.0
        // 0 = only ratings, up to 0.65 after ~12 watched titles
        val personal = min(watched.size / 12.0, 1.0) * 0.65

        val nowSec = now / 1000
        val candidates = entries.filter {
            it.kind != SearchEntry.KIND_LIVE &&
                it.categoryKey !in hidden &&
                "${it.kind}:${it.id}" !in history
        }.distinctBy { "${it.kind}:${it.id}" }
        val anyRatings = candidates.any { it.rating > 0f }

        fun score(e: SearchEntry): Double {
            val rating = if (e.rating > 0f) e.rating / 10.0 else if (anyRatings) 0.35 else 0.5
            val ageDays = if (e.added > 0) (nowSec - e.added) / 86_400.0 else 3650.0
            val fresh = exp(-ageDays.coerceAtLeast(0.0) / 365.0)
            val base = 0.75 * rating + 0.25 * fresh
            if (personal == 0.0) return base
            val cat = if (maxCat > 0) (catLove[e.categoryKey] ?: 0.0) / maxCat else 0.0
            val g = genres(e.genre)
            val genre = if (maxGenre > 0 && g.isNotEmpty()) g.maxOf { (genreLove[it] ?: 0.0) / maxGenre } else 0.0
            val taste = 0.6 * cat + 0.4 * genre
            return (1 - personal) * base + personal * (0.4 * base + 0.6 * taste)
        }

        val ranked = candidates.asSequence()
            .filter { anyRatings.not() || it.rating == 0f || it.rating >= 5.5f }   // skip what others found bad
            .map { it to score(it) }
            .sortedByDescending { it.second }
            .map { it.first }

        // variety: at most 4 titles per category, films and series mixed
        val perCat = HashMap<String, Int>()
        val picked = ArrayList<SearchEntry>(limit)
        for (e in ranked) {
            val n = perCat[e.categoryKey] ?: 0
            if (n >= 4) continue
            perCat[e.categoryKey] = n + 1
            picked += e
            if (picked.size >= limit) break
        }
        return Result(picked, personal > 0.0)
    }

    /** Titles like this one: same category or shared genre, best rated first. */
    fun similar(entries: List<SearchEntry>, of: SearchEntry, hidden: Set<String>, limit: Int = 20): List<SearchEntry> {
        val g = genres(of.genre)
        return entries.asSequence()
            .filter { it.kind == of.kind && it.id != of.id && it.categoryKey !in hidden }
            .distinctBy { it.id }
            .map { e ->
                val shared = genres(e.genre).count { it in g }
                val sameCat = if (e.categoryKey == of.categoryKey) 1 else 0
                e to (shared * 2 + sameCat * 2 + e.rating / 10.0)
            }
            .filter { it.second >= 2.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private val SPLIT = Regex("""\s*[,/&|]\s*""")

    fun genres(genre: String?): List<String> =
        genre?.split(SPLIT)?.map { it.trim().lowercase() }?.filter { it.length > 1 }.orEmpty()
}

/** Details of a film (get_vod_info) – fetched once per film, then kept on the stick for good. */
data class MovieInfo(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val year: String?,
    val durationMin: Int,
    val rating: Float,
    val country: String?,
    val trailer: String?,          // YouTube id
    val backdrop: String?,
    val cover: String?,
) {
    companion object {
        fun fromInfo(o: JSONObject): MovieInfo {
            val durSec = o.str("duration_secs")?.toIntOrNull()
                ?: o.str("duration")?.split(':')?.mapNotNull { it.toIntOrNull() }?.let { p ->
                    if (p.size == 3) p[0] * 3600 + p[1] * 60 + p[2] else null
                }
                ?: o.str("episode_run_time")?.toIntOrNull()?.times(60)
                ?: 0
            val backdrop = o.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") }
                ?: o.str("backdrop_path")?.takeIf { it.startsWith("http") }
            return MovieInfo(
                plot = o.str("plot") ?: o.str("description"),
                cast = o.str("cast") ?: o.str("actors"),
                director = o.str("director"),
                genre = o.str("genre"),
                year = (o.str("releasedate") ?: o.str("release_date") ?: o.str("year"))?.take(4)
                    ?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } },
                durationMin = durSec / 60,
                rating = (o.str("rating")?.replace(',', '.')?.toFloatOrNull() ?: 0f).coerceIn(0f, 10f),
                country = o.str("country"),
                trailer = o.str("youtube_trailer")?.takeIf { it.isNotBlank() },
                backdrop = backdrop,
                cover = (o.str("cover_big") ?: o.str("movie_image"))?.takeIf { it.startsWith("http") },
            )
        }
    }
}
