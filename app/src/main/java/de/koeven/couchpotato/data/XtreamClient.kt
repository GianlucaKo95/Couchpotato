package de.koeven.couchpotato.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal Xtream Codes client.
 *
 * The VOD/series catalogues can be tens of thousands of entries, so they are
 * read as a stream and only the newest N items are kept in memory –
 * important on a Fire TV Stick with little RAM.
 */
/**
 * @param background requests nobody is waiting for (daily sync, "Jetzt läuft"). They get a
 *        smaller hourly budget and are simply skipped when it is used up.
 */
class XtreamClient(private val cfg: XtreamConfig, private val background: Boolean = false) {

    private val base = XtreamConfig.normalizeBase(cfg.baseUrl)

    private fun apiUrl(action: String?, extra: String = ""): String {
        val auth = "username=${Uri.encode(cfg.user)}&password=${Uri.encode(cfg.pass)}"
        val act = if (action != null) "&action=$action" else ""
        return "$base/player_api.php?$auth$act$extra"
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            if (code in BLOCK_CODES) {
                // The provider locks the account for a while after too many requests (it answers 404).
                // Stay away completely for a while instead of making it worse.
                throw ProviderBlockedException(code, blockNow())
            }
            throw IOException("Server antwortet mit HTTP $code")
        }
        return c
    }

    /**
     * All API requests of the app go through here: one at a time, at least [MIN_GAP_MS] apart,
     * and none at all while the provider has us blocked.
     */
    internal fun <T> gated(block: () -> T): T = synchronized(GATE) {
        val now = clock()
        if (now < blockedUntil) throw ProviderBlockedException(null, blockedUntil)
        while (requestLog.isNotEmpty() && requestLog.first() !in (now - HOUR_MS)..(now + CLOCK_FUTURE_TOLERANCE_MS)) requestLog.removeFirst()
        val limit = if (background) BACKGROUND_PER_HOUR else MAX_PER_HOUR
        if (requestLog.size >= limit) throw RequestBudgetException(background)
        val wait = lastRequestAt + MIN_GAP_MS - now
        if (wait > 0) sleep(wait)
        try {
            block()
        } finally {
            lastRequestAt = clock()
            requestLog.addLast(lastRequestAt)
            persist()
        }
    }

    private fun readText(url: String): String = gated {
        val c = open(url)
        try {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun <T> streamArray(
        action: String,
        extra: String = "",
        onObject: (JsonReader) -> Unit,
        result: () -> T,
    ): T = gated {
        val c = open(apiUrl(action, extra))
        try {
            JsonReader(InputStreamReader(c.inputStream.buffered(), Charsets.UTF_8)).use { r ->
                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                    throw IOException("Unerwartete Antwort vom Server – Zugangsdaten prüfen")
                }
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) onObject(r) else r.skipValue()
                }
                r.endArray()
            }
        } finally {
            c.disconnect()
        }
        result()
    }

    /** Checks the login and returns a short status line for the settings screen. */
    suspend fun checkAuth(): String = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(readText(apiUrl(null)))
        } catch (e: JSONException) {
            throw IOException("Keine gültige Antwort – Server-Adresse prüfen")
        }
        val ui = root.optJSONObject("user_info") ?: throw IOException("Keine Kontodaten erhalten")
        if (ui.optInt("auth", 0) != 1) throw IOException("Login abgelehnt – Benutzername/Passwort prüfen")
        val status = ui.str("status") ?: "unbekannt"
        val exp = ui.str("exp_date")?.toLongOrNull()?.let {
            " · gültig bis " + SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(it * 1000))
        } ?: ""
        "Verbunden · Status: $status$exp"
    }

    /** kind: SearchEntry.KIND_MOVIE / KIND_SERIES / KIND_LIVE */
    suspend fun categories(kind: Char): List<LiveCategory> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            SearchEntry.KIND_MOVIE -> "get_vod_categories"
            SearchEntry.KIND_SERIES -> "get_series_categories"
            else -> "get_live_categories"
        }
        val arr = try {
            JSONArray(readText(apiUrl(action)))
        } catch (e: JSONException) {
            throw IOException("Kategorien konnten nicht geladen werden")
        }
        arr.mapObjects { o ->
            o.str("category_id")?.let { id -> LiveCategory(id, o.str("category_name") ?: id) }
        }.filterNotNull()
    }

    /**
     * Streams every movie, series and live channel to [onItem] (kind, id, name, image, ext),
     * for the search index. Returns the names of the lists that could not be loaded.
     */
    /** Extra facts the lists already carry (no extra request): rating 0–10, genre, year. */
    data class Facts(val rating: Float, val genre: String?, val year: String?, val tmdb: Long?)

    suspend fun dumpCatalog(onItem: (Char, Long, String, String?, String, String?, Long, Facts) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            data class Spec(val action: String, val kind: Char, val idField: String, val imageField: String, val label: String)
            val specs = listOf(
                Spec("get_vod_streams", SearchEntry.KIND_MOVIE, "stream_id", "stream_icon", "Filme"),
                Spec("get_series", SearchEntry.KIND_SERIES, "series_id", "cover", "Serien"),
                Spec("get_live_streams", SearchEntry.KIND_LIVE, "stream_id", "stream_icon", "Live-TV"),
            )
            val failed = mutableListOf<String>()
            for (spec in specs) {
                try {
                    streamArray(spec.action, onObject = { r ->
                        var id: Long? = null
                        var name = ""
                        var image: String? = null
                        var ext = if (spec.kind == SearchEntry.KIND_LIVE) "ts" else "mp4"
                        var cat: String? = null
                        var added = 0L
                        var rating = 0f
                        var rating5 = 0f
                        var genre: String? = null
                        var year: String? = null
                        var tmdb: Long? = null
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "tmdb", "tmdb_id" -> tmdb = r.nextLenient()?.toLongOrNull()?.takeIf { it > 0 } ?: tmdb
                                "rating" -> rating = r.nextLenient()?.replace(',', '.')?.toFloatOrNull() ?: rating
                                "rating_5based" -> rating5 = r.nextLenient()?.replace(',', '.')?.toFloatOrNull() ?: rating5
                                "genre" -> genre = r.nextLenient()?.trim()?.takeIf { it.isNotEmpty() }
                                "year", "releaseDate", "release_date", "releasedate" ->
                                    year = r.nextLenient()?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } } ?: year
                                spec.idField -> id = r.nextLenient()?.toLongOrNull()
                                "name" -> name = r.nextLenient() ?: ""
                                spec.imageField -> image = r.nextLenient()?.takeIf { it.startsWith("http") }
                                "container_extension" -> ext = r.nextLenient()?.takeIf { it.isNotBlank() } ?: ext
                                "category_id" -> cat = r.nextLenient()
                                "added", "last_modified" -> added = r.nextLenient()?.toLongOrNull() ?: added
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        val score = (if (rating > 0f) rating else rating5 * 2f).coerceIn(0f, 10f)
                        id?.let { onItem(spec.kind, it, Names.display(name), image, ext, cat, added, Facts(score, genre, year, tmdb)) }
                    }) { Unit }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ProviderBlockedException) {
                    throw e   // stop right away, don't fire the remaining requests
                } catch (e: Exception) {
                    failed += spec.label
                }
            }
            failed
        }

    /** Title of what's on right now (Xtream sends EPG titles base64-encoded). */
    suspend fun nowPlaying(streamId: Long): String? = withContext(Dispatchers.IO) {
        val root = runCatching {
            JSONObject(readText(apiUrl("get_short_epg", "&stream_id=$streamId&limit=3")))
        }.getOrNull() ?: return@withContext null
        val listings = root.optJSONArray("epg_listings") ?: return@withContext null
        val now = System.currentTimeMillis() / 1000
        val entries = listings.mapObjects { it }
        val current = entries.firstOrNull { e ->
            val start = e.str("start_timestamp")?.toLongOrNull() ?: return@firstOrNull false
            val stop = e.str("stop_timestamp")?.toLongOrNull() ?: return@firstOrNull false
            now in start until stop
        } ?: entries.firstOrNull()
        current?.str("title")?.let(::decodeEpg)
    }

    private fun decodeEpg(raw: String): String = runCatching {
        String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() && it.none { ch -> ch == '\uFFFD' } } ?: raw

    /** Raw answer of get_series_info (kept on the stick by the caller). */
    suspend fun seriesInfoRaw(seriesId: Long): String = withContext(Dispatchers.IO) {
        val text = readText(apiUrl("get_series_info", "&series_id=$seriesId"))
        try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("Serie konnte nicht geladen werden")
        }
        text
    }

    /** Film details (plot, cast, director …): ONE request per film; the caller keeps the answer for good. */
    suspend fun vodInfoRaw(streamId: Long): String = withContext(Dispatchers.IO) {
        val text = readText(apiUrl("get_vod_info", "&vod_id=$streamId"))
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("Filmdetails konnten nicht geladen werden")
        }
        if (root.optJSONObject("info") == null) throw IOException("Der Anbieter hat zu diesem Film keine Details")
        text
    }

    /** Turns a get_series_info answer into [SeriesInfo] (no request). */
    fun parseSeriesInfo(text: String, seriesId: Long, fallbackName: String): SeriesInfo {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("Serie konnte nicht geladen werden")
        }
        val info = root.optJSONObject("info")
        val episodes = mutableListOf<Episode>()

        fun addAll(arr: JSONArray, seasonHint: Int?) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id")?.toLongOrNull() ?: continue
                val number = o.str("episode_num")?.toIntOrNull() ?: (i + 1)
                val epInfo = o.optJSONObject("info")
                episodes += Episode(
                    id = id,
                    season = o.str("season")?.toIntOrNull() ?: seasonHint ?: 1,
                    number = number,
                    title = Names.episode(o.str("title"), info?.str("name") ?: fallbackName, number),
                    image = epInfo?.str("movie_image")?.takeIf { it.startsWith("http") },
                    ext = o.str("container_extension") ?: "mp4",
                    durationSec = epInfo?.str("duration_secs")?.toIntOrNull() ?: 0,
                )
            }
        }

        // Most panels send {"1":[...],"2":[...]}, some send [[...],[...]].
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { addAll(it, k.toIntOrNull()) } }
            is JSONArray -> for (i in 0 until eps.length()) eps.optJSONArray(i)?.let { addAll(it, i + 1) }
        }

        return SeriesInfo(
            id = seriesId,
            name = Names.display(info?.str("name") ?: fallbackName),
            cover = info?.str("cover")?.takeIf { it.startsWith("http") },
            plot = info?.str("plot"),
            genre = info?.str("genre"),
            year = (info?.str("releaseDate") ?: info?.str("release_date") ?: info?.str("year"))?.take(4)?.takeIf { y -> y.all { it.isDigit() } },
            episodes = episodes.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number })),
            cast = info?.str("cast"),
            director = info?.str("director"),
            rating = (info?.str("rating")?.replace(',', '.')?.toFloatOrNull() ?: 0f).coerceIn(0f, 10f),
        )
    }

    companion object {
        const val USER_AGENT = "Couchpotato/0.1"

        private val GATE = Any()
        /** Time and waiting – replaceable only so the tests don't have to wait for real. */
        @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
        @Volatile internal var sleep: (Long) -> Unit = { Thread.sleep(it) }
        internal const val MIN_GAP_MS = 2_500L
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val CLOCK_FUTURE_TOLERANCE_MS = 5 * 60 * 1000L
        /** Hard cap for everything in one hour (a normal day needs ~6 for the sync). */
        const val MAX_PER_HOUR = 40
        /** Background work (sync, "Jetzt läuft") stops earlier, so you still have room to browse. */
        internal const val BACKGROUND_PER_HOUR = 20
        internal const val COOLDOWN_MS = 15 * 60 * 1000L
        private val BLOCK_CODES = setOf(401, 403, 404, 429, 509)

        /** Pictures that live on the provider's own server: one at a time, own hourly budget. */
        private const val IMAGE_GAP_MS = 1_200L
        const val IMAGES_PER_HOUR = 60

        private val requestLog = ArrayDeque<Long>()
        private val imageLog = ArrayDeque<Long>()
        @Volatile private var lastRequestAt = 0L
        @Volatile var blockedUntil = 0L
            private set

        /** Host of the provider (images from there are throttled); set from the login data. */
        @Volatile var providerHost: String? = null
        /** Settings switch: don't load pictures from the provider's server at all. */
        @Volatile var loadProviderImages: Boolean = true

        // ---- persisted, so the limits survive the stick rebooting with the TV ----
        private var prefs: SharedPreferences? = null

        fun init(context: Context) = synchronized(GATE) {
            if (prefs != null) return@synchronized
            val p = context.applicationContext.getSharedPreferences("netguard", Context.MODE_PRIVATE)
            prefs = p
            val now = System.currentTimeMillis()
            blockedUntil = p.getLong("blockedUntil", 0L)
                .takeIf { it in now..(now + COOLDOWN_MS + CLOCK_FUTURE_TOLERANCE_MS) } ?: 0L
            lastRequestAt = p.getLong("lastRequestAt", 0L)
                .takeIf { it in (now - HOUR_MS)..(now + CLOCK_FUTURE_TOLERANCE_MS) } ?: 0L
            fun restore(key: String, into: ArrayDeque<Long>) {
                p.getString(key, null)?.split(',')?.mapNotNull { it.toLongOrNull() }
                    ?.filter { it in (now - HOUR_MS)..(now + CLOCK_FUTURE_TOLERANCE_MS) }
                    ?.sorted()?.forEach { into.addLast(it) }
            }
            restore("requestLog", requestLog)
            restore("imageLog", imageLog)
        }

        private fun persist() {
            prefs?.edit()
                ?.putLong("blockedUntil", blockedUntil)
                ?.putLong("lastRequestAt", lastRequestAt)
                ?.putString("requestLog", requestLog.joinToString(","))
                ?.putString("imageLog", imageLog.joinToString(","))
                ?.apply()
        }

        /** The provider answered with a lock code: stay away completely for a while instead of making it worse. */
        internal fun blockNow(): Long = synchronized(GATE) {
            blockedUntil = clock() + COOLDOWN_MS
            persist()
            blockedUntil
        }

        /** Tests only: forget all limits. */
        internal fun resetForTests() = synchronized(GATE) {
            requestLog.clear(); imageLog.clear(); lastRequestAt = 0L; blockedUntil = 0L
        }

        /** For the settings screen. */
        fun requestsLastHour(): Int = synchronized(GATE) {
            val now = clock()
            requestLog.count { it in (now - HOUR_MS)..(now + CLOCK_FUTURE_TOLERANCE_MS) }
        }

        fun imagesLastHour(): Int = synchronized(GATE) {
            val now = System.currentTimeMillis()
            imageLog.count { it in (now - HOUR_MS)..(now + CLOCK_FUTURE_TOLERANCE_MS) }
        }

        /** Only when the login data really changed (a new account isn't locked). */
        fun clearBlock() = synchronized(GATE) {
            blockedUntil = 0L
            persist()
        }

        /** Runs a picture download from the provider's server through the same brake. */
        fun <T> gateImage(block: () -> T): T = synchronized(GATE) {
            val now = clock()
            if (now < blockedUntil) throw ProviderBlockedException(null, blockedUntil)
            while (imageLog.isNotEmpty() && imageLog.first() !in (now - HOUR_MS)..(now + CLOCK_FUTURE_TOLERANCE_MS)) imageLog.removeFirst()
            if (imageLog.size >= IMAGES_PER_HOUR) throw RequestBudgetException(true)
            val wait = lastRequestAt + IMAGE_GAP_MS - now
            if (wait > 0) sleep(wait)
            try {
                block()
            } finally {
                lastRequestAt = clock()
                imageLog.addLast(lastRequestAt)
                persist()
            }
        }

        fun streamUrl(cfg: XtreamConfig, item: PlayItem): String {
            val base = XtreamConfig.normalizeBase(cfg.baseUrl)
            val segment = when (item.kind) {
                PlayItem.KIND_MOVIE -> "movie"
                PlayItem.KIND_LIVE -> "live"
                else -> "series"
            }
            return "$base/$segment/${Uri.encode(cfg.user)}/${Uri.encode(cfg.pass)}/${item.streamId}.${item.ext}"
        }
    }
}

/** Reads strings, numbers, booleans as String; skips objects/arrays; null stays null. */
private fun JsonReader.nextLenient(): String? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

/** The provider refuses requests (usually a temporary lock after too many requests). */
class ProviderBlockedException(code: Int?, val until: Long) : IOException(
    buildString {
        val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(until))
        append("Anbieter sperrt gerade")
        if (code != null) append(" (HTTP $code)")
        append(" – vermutlich wegen zu vieler Anfragen. Couchpotato pausiert und versucht es ab $time Uhr wieder.")
        append(" Bleibt es danach so, bitte die Zugangsdaten prüfen.")
    }
)

/** Couchpotato's own hourly request budget is used up (protects the account from a lock). */
class RequestBudgetException(background: Boolean) : IOException(
    if (background) "Hintergrund-Abfragen pausiert (Stundenlimit erreicht) – geht später automatisch weiter."
    else "Zu viele Anfragen in der letzten Stunde – Couchpotato bremst, um eine Sperre beim Anbieter zu vermeiden. Bitte etwas später erneut versuchen."
)
