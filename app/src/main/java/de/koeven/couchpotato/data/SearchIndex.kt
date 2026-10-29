package de.koeven.couchpotato.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** One searchable IPTV title. kind: KIND_MOVIE / KIND_SERIES / KIND_LIVE. */
data class SearchEntry(
    val kind: Char,
    val id: Long,
    val name: String,
    val image: String?,
    val ext: String,
    val norm: String,
    val categoryId: String? = null,
    val added: Long = 0L,          // movies: "added", series: "last_modified" (unix seconds)
    val rating: Float = 0f,        // 0–10 as the provider lists it (0 = unknown)
    val genre: String? = null,
    val year: String? = null,
) {
    /** Same key format as SettingsStore.hiddenCategories(). */
    val categoryKey: String get() = "$kind:${categoryId ?: ""}"

    companion object {
        const val KIND_MOVIE = 'm'
        const val KIND_SERIES = 's'
        const val KIND_LIVE = 'l'
    }
}

data class SearchResults(
    val query: String = "",
    val movies: List<SearchEntry> = emptyList(),
    val series: List<SearchEntry> = emptyList(),
    val live: List<SearchEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty() && live.isEmpty()
}

/**
 * The whole provider catalogue on the stick.
 *
 * The provider locks the account after too many requests, so Couchpotato downloads everything
 * in ONE sync (6 requests: categories + lists for movies, series and live TV), at most once a
 * day, and writes it to a compact tab-separated file. "Neue Filme/Serien", the Mediathek,
 * "Sender verwalten", hiding categories and the search are all computed locally from it.
 */
class SearchIndex(context: Context) {

    private val file = File(context.filesDir, "catalog_v3.tsv")          // v3: + date column
    private val catFile = File(context.filesDir, "categories_v3.json")

    fun exists(): Boolean = file.exists() && file.length() > 0 && catFile.exists()
    fun ageMs(): Long = if (file.exists()) System.currentTimeMillis() - file.lastModified() else Long.MAX_VALUE
    fun lastSync(): Long = if (file.exists()) file.lastModified() else 0L
    fun clear() {
        file.delete()
        catFile.delete()
    }

    /** e.g. "12.345 Bilder · 8 % vom Anbieter-Server · sonst v. a. image.tmdb.org" */
    fun imageStats(): String? = runCatching {
        val img = org.json.JSONObject(catFile.readText()).optJSONObject("img") ?: return null
        val total = img.optInt("total")
        if (total == 0) return "Keine Bilder im Katalog"
        val provider = img.optInt("provider")
        val pct = provider * 100 / total
        val others = img.optJSONArray("hosts")?.mapObjects { it.str("host") }?.filterNotNull()
            ?.filter { it != XtreamClient.providerHost?.lowercase() }.orEmpty()
        val nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY)
        buildString {
            append("${nf.format(total)} Bilder · $pct % vom Anbieter-Server")
            if (others.isNotEmpty()) append(" · sonst v. a. ${others.take(2).joinToString()}")
        }
    }.getOrNull()

    fun loadCategories(): Map<Char, List<LiveCategory>> = runCatching {
        val o = org.json.JSONObject(catFile.readText())
        listOf(SearchEntry.KIND_MOVIE, SearchEntry.KIND_SERIES, SearchEntry.KIND_LIVE).associateWith { k ->
            o.optJSONArray(k.toString())?.mapObjects { c ->
                c.str("id")?.let { LiveCategory(it, c.str("name") ?: it) }
            }?.filterNotNull().orEmpty()
        }
    }.getOrDefault(emptyMap())

    /** Rebuilds the index; returns the kinds that could not be loaded (empty = all fine). */
    suspend fun rebuild(client: XtreamClient): List<String> = withContext(Dispatchers.IO) {
        val tmp = File(file.path + ".tmp")
        val failed = mutableListOf<String>()
        var count = 0

        // category names (3 small requests)
        val cats = org.json.JSONObject()
        for ((kind, label) in listOf(
            SearchEntry.KIND_MOVIE to "Film-Kategorien",
            SearchEntry.KIND_SERIES to "Serien-Kategorien",
            SearchEntry.KIND_LIVE to "Live-Kategorien",
        )) {
            try {
                val list = client.categories(kind)
                cats.put(kind.toString(), org.json.JSONArray(list.map {
                    org.json.JSONObject().put("id", it.id).put("name", it.name)
                }))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += label
                if (e is ProviderBlockedException) throw e   // no point in going on
            }
        }

        // all titles (3 big requests, streamed straight into the file)
        val providerHost = XtreamClient.providerHost
        val hostCounts = HashMap<String, Int>()
        var images = 0
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            failed += client.dumpCatalog { kind, id, name, image, ext, cat, added, facts ->
                val clean = name.sanitize()
                if (clean.isEmpty()) return@dumpCatalog
                // where do the pictures live? (provider's own server = throttled)
                image?.let { url ->
                    images++
                    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: "?"
                    hostCounts[host] = (hostCounts[host] ?: 0) + 1
                }
                w.append(kind).append('\t')
                    .append(id.toString()).append('\t')
                    .append(ext.sanitize()).append('\t')
                    .append(image?.sanitize() ?: "").append('\t')
                    .append(clean).append('\t')
                    .append(normalize(clean)).append('\t')
                    .append(cat?.sanitize() ?: "").append('\t')
                    .append(added.toString()).append('\t')
                    .append(if (facts.rating > 0f) "%.1f".format(Locale.ROOT, facts.rating) else "").append('\t')
                    .append(facts.genre?.sanitize() ?: "").append('\t')
                    .append(facts.year ?: "").append('\n')
                count++
            }
        }
        if (count > 0) {
            file.delete()
            tmp.renameTo(file)
            val fromProvider = providerHost?.let { hostCounts[it.lowercase()] } ?: 0
            val top = hostCounts.entries.sortedByDescending { it.value }.take(3)
            cats.put("img", org.json.JSONObject()
                .put("total", images)
                .put("provider", fromProvider)
                .put("hosts", org.json.JSONArray(top.map { org.json.JSONObject().put("host", it.key).put("n", it.value) })))
            catFile.writeText(cats.toString())
        } else {
            tmp.delete()
        }
        failed
    }

    suspend fun load(): List<SearchEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 6) return@mapNotNull null
                SearchEntry(
                    kind = p[0].firstOrNull() ?: return@mapNotNull null,
                    id = p[1].toLongOrNull() ?: return@mapNotNull null,
                    ext = p[2].ifEmpty { "mp4" },
                    image = p[3].ifEmpty { null },
                    name = p[4],
                    norm = p[5],
                    categoryId = p.getOrNull(6)?.ifEmpty { null },
                    added = p.getOrNull(7)?.toLongOrNull() ?: 0L,
                    rating = p.getOrNull(8)?.toFloatOrNull() ?: 0f,
                    genre = p.getOrNull(9)?.ifEmpty { null },
                    year = p.getOrNull(10)?.ifEmpty { null },
                )
            }.toList()
        }
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** "Café – Größe!" -> "cafe grosse": case, accents and punctuation don't matter. */
        fun normalize(s: String): String =
            Normalizer.normalize(s.replace("ß", "ss"), Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase(Locale.ROOT)
                .replace(NON_WORD, " ")
                .trim()

        fun search(
            entries: List<SearchEntry>,
            query: String,
            hiddenCategories: Set<String> = emptySet(),
            limit: Int = 40,
        ): SearchResults {
            val q = normalize(query)
            val tokens = q.split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty() || q.length < 2) return SearchResults(query)

            fun score(e: SearchEntry): Int = when {
                e.norm.startsWith(q) -> 0
                " ${e.norm}".contains(" $q") -> 1        // a word starts with the query
                " ${e.norm}".contains(" ${tokens[0]}") -> 2
                else -> 3
            }

            val hits = entries.filter { e -> tokens.all { e.norm.contains(it) } && e.categoryKey !in hiddenCategories }
                .sortedWith(compareBy<SearchEntry>({ score(it) }, { it.name.length }))

            return SearchResults(
                query = query,
                movies = hits.filter { it.kind == SearchEntry.KIND_MOVIE }.take(limit),
                series = hits.filter { it.kind == SearchEntry.KIND_SERIES }.take(limit),
                live = hits.filter { it.kind == SearchEntry.KIND_LIVE }.take(limit),
            )
        }

        private fun String.sanitize() = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    }
}
