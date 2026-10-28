package de.koeven.couchpotato.data

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
import java.util.PriorityQueue

/**
 * Minimal Xtream Codes client.
 *
 * The VOD/series catalogues can be tens of thousands of entries, so they are
 * read as a stream and only the newest N items are kept in memory –
 * important on a Fire TV Stick with little RAM.
 */
class XtreamClient(private val cfg: XtreamConfig) {

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
            throw IOException("Server antwortet mit HTTP $code")
        }
        return c
    }

    private fun readText(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun <T> streamArray(
        action: String,
        extra: String = "",
        onObject: (JsonReader) -> Unit,
        result: () -> T,
    ): T {
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
        return result()
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

    suspend fun latestMovies(limit: Int): List<VodItem> = withContext(Dispatchers.IO) {
        val heap = PriorityQueue<VodItem>(limit + 1, compareBy<VodItem> { it.added })
        streamArray("get_vod_streams", onObject = { r ->
            var id: Long? = null
            var name = ""
            var icon: String? = null
            var added = 0L
            var ext = "mp4"
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = r.nextLenient()?.toLongOrNull()
                    "name" -> name = r.nextLenient() ?: ""
                    "stream_icon" -> icon = r.nextLenient()?.takeIf { it.startsWith("http") }
                    "added" -> added = r.nextLenient()?.toLongOrNull() ?: 0L
                    "container_extension" -> ext = r.nextLenient()?.takeIf { it.isNotBlank() } ?: "mp4"
                    else -> r.skipValue()
                }
            }
            r.endObject()
            id?.let {
                heap.add(VodItem(it, name.trim(), icon, added, ext))
                if (heap.size > limit) heap.poll()
            }
        }) { heap.sortedByDescending { it.added } }
    }

    suspend fun latestSeries(limit: Int): List<SeriesItem> = withContext(Dispatchers.IO) {
        val heap = PriorityQueue<SeriesItem>(limit + 1, compareBy<SeriesItem> { it.updated })
        streamArray("get_series", onObject = { r ->
            var id: Long? = null
            var name = ""
            var cover: String? = null
            var updated = 0L
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "series_id" -> id = r.nextLenient()?.toLongOrNull()
                    "name" -> name = r.nextLenient() ?: ""
                    "cover" -> cover = r.nextLenient()?.takeIf { it.startsWith("http") }
                    "last_modified" -> updated = r.nextLenient()?.toLongOrNull() ?: 0L
                    else -> r.skipValue()
                }
            }
            r.endObject()
            id?.let {
                heap.add(SeriesItem(it, name.trim(), cover, updated))
                if (heap.size > limit) heap.poll()
            }
        }) { heap.sortedByDescending { it.updated } }
    }

    suspend fun liveCategories(): List<LiveCategory> = withContext(Dispatchers.IO) {
        val arr = try {
            JSONArray(readText(apiUrl("get_live_categories")))
        } catch (e: JSONException) {
            throw IOException("Live-Kategorien konnten nicht geladen werden")
        }
        arr.mapObjects { o ->
            o.str("category_id")?.let { id -> LiveCategory(id, o.str("category_name") ?: id) }
        }.filterNotNull()
    }

    suspend fun liveChannels(categoryId: String): List<LiveChannel> = withContext(Dispatchers.IO) {
        val list = ArrayList<LiveChannel>()
        streamArray("get_live_streams", "&category_id=${Uri.encode(categoryId)}", onObject = { r ->
            var id: Long? = null
            var name = ""
            var logo: String? = null
            var cat: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stream_id" -> id = r.nextLenient()?.toLongOrNull()
                    "name" -> name = r.nextLenient() ?: ""
                    "stream_icon" -> logo = r.nextLenient()?.takeIf { it.startsWith("http") }
                    "category_id" -> cat = r.nextLenient()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            id?.let { list += LiveChannel(it, name.trim(), logo, cat ?: categoryId) }
        }) { list }
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

    suspend fun seriesInfo(seriesId: Long, fallbackName: String): SeriesInfo = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(readText(apiUrl("get_series_info", "&series_id=$seriesId")))
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
                    title = o.str("title") ?: "Folge $number",
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

        SeriesInfo(
            id = seriesId,
            name = info?.str("name") ?: fallbackName,
            cover = info?.str("cover")?.takeIf { it.startsWith("http") },
            plot = info?.str("plot"),
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.number })),
        )
    }

    companion object {
        const val USER_AGENT = "Couchpotato/0.1"

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
