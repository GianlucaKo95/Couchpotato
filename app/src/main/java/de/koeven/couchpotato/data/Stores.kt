package de.koeven.couchpotato.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("couchpotato", Context.MODE_PRIVATE)

    fun config() = XtreamConfig(
        p.getString("url", null) ?: "",
        p.getString("user", null) ?: "",
        p.getString("pass", null) ?: "",
    )

    fun save(c: XtreamConfig) {
        p.edit()
            .putString("url", XtreamConfig.normalizeBase(c.baseUrl))
            .putString("user", c.user.trim())
            .putString("pass", c.pass.trim())
            .apply()
    }

    fun hiddenApps(): Set<String> = p.getStringSet("hidden", null)?.toSet() ?: emptySet()
    fun hideApp(pkg: String) = p.edit().putStringSet("hidden", hiddenApps() + pkg).apply()
    fun resetHiddenApps() = p.edit().remove("hidden").apply()

    fun hiddenContinue(): Set<String> = p.getStringSet("hiddenContinue", null)?.toSet() ?: emptySet()
    fun hideContinue(key: String) = p.edit().putStringSet("hiddenContinue", (hiddenContinue() + key).toList().takeLast(200).toSet()).apply()

    /** Live-TV favourites, in the order they were added. */
    fun favorites(): List<LiveChannel> = runCatching {
        JSONArray(p.getString("liveFavs", null) ?: "[]").mapObjects {
            LiveChannel(it.getLong("id"), it.str("name") ?: "", it.str("logo"), it.str("cat"))
        }
    }.getOrDefault(emptyList())

    fun setFavorites(list: List<LiveChannel>) {
        val arr = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo).put("cat", it.categoryId)
        })
        p.edit().putString("liveFavs", arr.toString()).apply()
    }

    /** true: hand live channels to IsiPro instead of Couchpotato's own player. */
    var liveInExternalApp: Boolean
        get() = p.getBoolean("liveExternal", false)
        set(value) = p.edit().putBoolean("liveExternal", value).apply()

    fun markLaunched(pkg: String) = p.edit().putLong("launch:$pkg", System.currentTimeMillis()).apply()
    fun lastLaunched(pkg: String): Long = p.getLong("launch:$pkg", 0L)
}

/** Playback positions. One entry per movie, and at most one per series. */
class ProgressStore(context: Context) {
    private val p = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun get(key: String): ProgressEntry? = p.getString(key, null)?.let(::parse)

    fun all(): List<ProgressEntry> = p.all.values.mapNotNull { (it as? String)?.let(::parse) }

    fun forSeries(seriesId: Long): ProgressEntry? =
        all().filter { it.item.seriesId == seriesId }.maxByOrNull { it.updatedAt }

    fun save(item: PlayItem, positionMs: Long, durationMs: Long) =
        put(ProgressEntry(item, positionMs, durationMs, System.currentTimeMillis(), upNext = false))

    /** Item played to the end: drop it; for series queue the next episode instead. */
    fun finished(item: PlayItem, next: PlayItem?) {
        if (next != null && item.seriesId != null && next.seriesId == item.seriesId) {
            put(ProgressEntry(next, 0, 0, System.currentTimeMillis(), upNext = true))
        } else {
            p.edit().remove(item.key).apply()
        }
    }

    fun remove(key: String) = p.edit().remove(key).apply()

    fun continueWatching(): List<ProgressEntry> = all()
        .filter { e ->
            e.upNext || (e.positionMs > 30_000 && (e.durationMs <= 0 || e.positionMs < e.durationMs * 0.95))
        }
        .sortedByDescending { it.updatedAt }
        .take(30)

    private fun put(e: ProgressEntry) {
        val edit = p.edit()
        // keep only the latest episode per series
        e.item.seriesId?.let { sid ->
            all().filter { it.item.seriesId == sid && it.item.key != e.item.key }
                .forEach { edit.remove(it.item.key) }
        }
        val json = JSONObject()
            .put("item", e.item.toJson())
            .put("pos", e.positionMs)
            .put("dur", e.durationMs)
            .put("at", e.updatedAt)
            .put("upNext", e.upNext)
        edit.putString(e.item.key, json.toString()).apply()
    }

    private fun parse(s: String): ProgressEntry? = runCatching {
        val o = JSONObject(s)
        ProgressEntry(
            item = PlayItem.fromJson(o.getJSONObject("item")),
            positionMs = o.optLong("pos"),
            durationMs = o.optLong("dur"),
            updatedAt = o.optLong("at"),
            upNext = o.optBoolean("upNext"),
        )
    }.getOrNull()
}

/** Last fetched "new" rows on disk, so the home screen is filled instantly after every boot. */
class CatalogCache(context: Context) {
    private val file = File(context.filesDir, "catalog.json")

    data class Data(val movies: List<VodItem>, val series: List<SeriesItem>)

    fun ageMs(): Long = if (file.exists()) System.currentTimeMillis() - file.lastModified() else Long.MAX_VALUE

    fun load(): Data? = runCatching {
        val o = JSONObject(file.readText())
        Data(
            movies = o.getJSONArray("movies").mapObjects {
                VodItem(it.getLong("id"), it.str("name") ?: "", it.str("image"), it.optLong("added"), it.str("ext") ?: "mp4")
            },
            series = o.getJSONArray("series").mapObjects {
                SeriesItem(it.getLong("id"), it.str("name") ?: "", it.str("image"), it.optLong("updated"))
            },
        )
    }.getOrNull()

    fun save(movies: List<VodItem>, series: List<SeriesItem>) {
        val o = JSONObject()
            .put("movies", JSONArray(movies.map {
                JSONObject().put("id", it.id).put("name", it.name).put("image", it.image).put("added", it.added).put("ext", it.ext)
            }))
            .put("series", JSONArray(series.map {
                JSONObject().put("id", it.id).put("name", it.name).put("image", it.image).put("updated", it.updated)
            }))
        file.writeText(o.toString())
    }
}
