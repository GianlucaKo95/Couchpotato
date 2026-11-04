package de.koeven.couchpotato.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HomeRows {
    const val CONTINUE = "continue"
    const val LIVE = "live"
    const val APPS = "apps"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val RECOMMENDED = "recommended"
    const val MY_LIST = "mylist"
    val ALL = listOf(CONTINUE, MY_LIST, LIVE, APPS, RECOMMENDED, MOVIES, SERIES)

    fun label(id: String) = when (id) {
        CONTINUE -> "Weiterschauen"
        LIVE -> "Live-TV – Favoriten"
        APPS -> "Apps"
        MOVIES -> "Neue Filme"
        SERIES -> "Neue Serien"
        RECOMMENDED -> "Für dich"
        MY_LIST -> "Meine Liste"
        else -> id
    }
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("couchpotato", Context.MODE_PRIVATE)
    private val ctx = context.applicationContext

    /** Key of a per-profile value (favourites, searches …) for the active profile. */
    private fun mine(key: String) = key + Profiles.suffix(ctx)

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

    /** true = login is entered as one complete link instead of server/user/password. */
    var loginAsLink: Boolean
        get() = p.getBoolean("loginAsLink", false)
        set(value) = p.edit().putBoolean("loginAsLink", value).apply()

    fun hiddenApps(): Set<String> = p.getStringSet("hidden", null)?.toSet() ?: emptySet()
    fun hideApp(pkg: String) = p.edit().putStringSet("hidden", hiddenApps() + pkg).apply()
    fun resetHiddenApps() = p.edit().remove("hidden").apply()

    fun hiddenContinue(): Set<String> = p.getStringSet(mine("hiddenContinue"), null)?.toSet() ?: emptySet()
    fun hideContinue(key: String) = p.edit().putStringSet(mine("hiddenContinue"), (hiddenContinue() + key).toList().takeLast(200).toSet()).apply()

    /** Live-TV favourites, in the order they were added. */
    fun favorites(): List<LiveChannel> = runCatching {
        JSONArray(p.getString(mine("liveFavs"), null) ?: "[]").mapObjects {
            LiveChannel(it.getLong("id"), Names.display(it.str("name") ?: ""), it.str("logo"), it.str("cat"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun setFavorites(list: List<LiveChannel>) {
        val arr = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo).put("cat", it.categoryId)
        })
        p.edit().putString(mine("liveFavs"), arr.toString()).apply()
    }

    /** true: hand live channels to IsiPro instead of Couchpotato's own player. */
    var liveInExternalApp: Boolean
        get() = p.getBoolean("liveExternal", false)
        set(value) = p.edit().putBoolean("liveExternal", value).apply()

    /** true = apps in the user's own order, false = most recently used first. */
    var appsCustomOrder: Boolean
        get() = p.getBoolean("appsCustom", false)
        set(value) = p.edit().putBoolean("appsCustom", value).apply()

    fun appOrder(): List<String> = runCatching {
        val arr = JSONArray(p.getString("appOrder", null) ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun setAppOrder(pkgs: List<String>) = p.edit().putString("appOrder", JSONArray(pkgs).toString()).apply()

    /** Order of the rows on the home screen (ids from [HomeRows]). */
    fun rowOrder(): List<String> = runCatching {
        val arr = JSONArray(p.getString("rowOrder", null) ?: "[]")
        val saved = (0 until arr.length()).map { arr.getString(it) }.filter { it in HomeRows.ALL }
        val order = saved.toMutableList()
        // "Für dich" arrived later: put it in front of "Neue Filme" instead of at the very end
        if (HomeRows.RECOMMENDED !in order) {
            val at = order.indexOf(HomeRows.MOVIES).takeIf { it >= 0 } ?: order.size
            order.add(at, HomeRows.RECOMMENDED)
        }
        // "Meine Liste" arrived later: right after "Weiterschauen"
        if (HomeRows.MY_LIST !in order) {
            order.add(order.indexOf(HomeRows.CONTINUE) + 1, HomeRows.MY_LIST)
        }
        order + HomeRows.ALL.filterNot { it in order }   // rows added in later versions go to the end
    }.getOrDefault(HomeRows.ALL)

    fun setRowOrder(ids: List<String>) = p.edit().putString("rowOrder", JSONArray(ids).toString()).apply()

    /** Mediathek order: 0 = newest first, 1 = A–Z, 2 = best rating. */
    var browseSort: Int
        get() = p.getInt("browseSort", 0)
        set(value) = p.edit().putInt("browseSort", value).apply()

    /** Own order of the categories in the Mediathek (ids), per kind; empty = the provider's order. */
    fun categoryOrder(kind: Char): List<String> = runCatching {
        val arr = JSONArray(p.getString("catOrder_$kind", null) ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun setCategoryOrder(kind: Char, ids: List<String>?) {
        if (ids == null) p.edit().remove("catOrder_$kind").apply()
        else p.edit().putString("catOrder_$kind", JSONArray(ids).toString()).apply()
    }

    /** Hidden provider categories as "m:<id>" (Filme), "s:<id>" (Serien), "l:<id>" (Live-TV). */
    fun hiddenCategories(): Set<String> = p.getStringSet("hiddenCats", null)?.toSet() ?: emptySet()

    fun setCategoriesHidden(keys: Collection<String>, hidden: Boolean) {
        val set = hiddenCategories().toMutableSet()
        if (hidden) set += keys else set -= keys.toSet()
        p.edit().putStringSet("hiddenCats", set).apply()
    }

    // ---- request brake: kept on disk so it survives reboots ----

    /** "Jetzt läuft": when it was last fetched and the titles, so a reboot doesn't refetch. */
    var epgLoadedAt: Long
        get() = p.getLong("epgAt", 0L)
        set(value) = p.edit().putLong("epgAt", value).apply()

    fun epgTitles(): Map<Long, String> = runCatching {
        val o = JSONObject(p.getString("epgTitles", null) ?: "{}")
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to o.getString(k) } }.toMap()
    }.getOrDefault(emptyMap())

    fun setEpgTitles(map: Map<Long, String>) {
        val o = JSONObject()
        map.forEach { (id, title) -> o.put(id.toString(), title) }
        p.edit().putString("epgTitles", o.toString()).apply()
    }

    /** Failed catalogue syncs in a row and when the last one was tried (for the back-off). */
    var syncFailures: Int
        get() = p.getInt("syncFailures", 0)
        set(value) = p.edit().putInt("syncFailures", value).apply()

    var lastSyncAttempt: Long
        get() = p.getLong("syncAttemptAt", 0L)
        set(value) = p.edit().putLong("syncAttemptAt", value).apply()

    var cloudSyncAttemptAt: Long
        get() = p.getLong("cloudSyncAttemptAt", 0L)
        set(value) = p.edit().putLong("cloudSyncAttemptAt", value).apply()

    /** false = pictures from the provider's own server are not loaded at all. */
    var loadProviderImages: Boolean
        get() = p.getBoolean("providerImages", true)
        set(value) = p.edit().putBoolean("providerImages", value).apply()

    /** TMDB key/token set on the stick (adb … --es tmdb 'TOKEN'); otherwise the one from the build. */
    var tmdbToken: String?
        get() = p.getString("tmdbToken", null)
        set(value) = p.edit().putString("tmdbToken", value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    /** Film details (plot, cast …): one request per film, the first time it is opened. */
    var loadMovieDetails: Boolean
        get() = p.getBoolean("movieDetails", true)
        set(value) = p.edit().putBoolean("movieDetails", value).apply()

    /** Last searches (newest first), shown as suggestions in the search window. */
    fun recentSearches(): List<String> =
        p.getString(mine("recentSearches"), null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val list = (listOf(t) + recentSearches().filterNot { it.equals(t, ignoreCase = true) }).take(8)
        p.edit().putString(mine("recentSearches"), list.joinToString("\n")).apply()
    }

    fun markLaunched(pkg: String) = p.edit().putLong("launch:$pkg", System.currentTimeMillis()).apply()
    fun lastLaunched(pkg: String): Long = p.getLong("launch:$pkg", 0L)
}

/** Episodes marked as seen ("s:<series id>" → episode ids) – per profile, synced with the household. */
class WatchedStore(context: Context) {
    private val p = context.getSharedPreferences("watched" + Profiles.suffix(context), Context.MODE_PRIVATE)

    fun forSeries(seriesId: Long): Set<Long> =
        p.getStringSet("s:$seriesId", null)?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty()

    fun set(seriesId: Long, episodeIds: Collection<Long>, watched: Boolean) {
        val now = forSeries(seriesId)
        val next = if (watched) now + episodeIds else now - episodeIds.toSet()
        p.edit().putStringSet("s:$seriesId", next.map { it.toString() }.toSet()).apply()
    }
}

/** Playback positions. One entry per movie, and at most one per series. */
/** Positions for "Weiterschauen" – one store per profile. */
class ProgressStore(context: Context) {
    private val p = context.getSharedPreferences("progress" + Profiles.suffix(context), Context.MODE_PRIVATE)
    private val history = WatchHistory(context)
    private val watched = WatchedStore(context)

    fun get(key: String): ProgressEntry? = p.getString(key, null)?.let(::parse)

    fun all(): List<ProgressEntry> = p.all.values.mapNotNull { (it as? String)?.let(::parse) }

    fun forSeries(seriesId: Long): ProgressEntry? =
        all().filter { it.item.seriesId == seriesId }.maxByOrNull { it.updatedAt }

    fun save(item: PlayItem, positionMs: Long, durationMs: Long) {
        history.add(item)
        put(ProgressEntry(item, positionMs, durationMs, System.currentTimeMillis(), upNext = false))
    }

    /** Item played to the end: drop it; for series queue the next episode instead. */
    fun finished(item: PlayItem, next: PlayItem?) {
        history.add(item)
        item.seriesId?.let { sid -> watched.set(sid, listOf(item.streamId), true) }
        if (next != null && item.seriesId != null && next.seriesId == item.seriesId) {
            put(ProgressEntry(next, 0, 0, System.currentTimeMillis(), upNext = true))
        } else {
            p.edit().remove(item.key).apply()
        }
    }

    fun remove(key: String) = p.edit().remove(key).apply()

    fun continueWatching(): List<ProgressEntry> = all()
        .filter { e ->
            e.upNext || (e.positionMs > 10_000 && (e.durationMs <= 0 || e.positionMs < e.durationMs * 0.95))
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
    private val file = File(context.filesDir, "catalog${Profiles.suffix(context)}.json")   // per profile (kids!)

    data class Data(val movies: List<VodItem>, val series: List<SeriesItem>)

    fun ageMs(): Long = if (file.exists()) System.currentTimeMillis() - file.lastModified() else Long.MAX_VALUE

    fun load(): Data? = runCatching {
        val o = JSONObject(file.readText())
        Data(
            movies = o.getJSONArray("movies").mapObjects {
                VodItem(it.getLong("id"), Names.display(it.str("name") ?: ""), it.str("image"), it.optLong("added"), it.str("ext") ?: "mp4", it.str("cat"))
            },
            series = o.getJSONArray("series").mapObjects {
                SeriesItem(it.getLong("id"), Names.display(it.str("name") ?: ""), it.str("image"), it.optLong("updated"), it.str("cat"))
            },
        )
    }.getOrNull()

    fun save(movies: List<VodItem>, series: List<SeriesItem>) {
        val o = JSONObject()
            .put("movies", JSONArray(movies.map {
                JSONObject().put("id", it.id).put("name", it.name).put("image", it.image).put("added", it.added).put("ext", it.ext).put("cat", it.categoryId)
            }))
            .put("series", JSONArray(series.map {
                JSONObject().put("id", it.id).put("name", it.name).put("image", it.image).put("updated", it.updated).put("cat", it.categoryId)
            }))
        file.writeText(o.toString())
    }
}
