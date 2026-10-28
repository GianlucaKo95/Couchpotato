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
 * The provider has no search API, so all titles are downloaded once (streamed, never held
 * as JSON) and written to a compact tab-separated file. Searching is then local and instant.
 */
class SearchIndex(context: Context) {

    private val file = File(context.filesDir, "search_index_v2.tsv")   // v2: with category column

    fun exists(): Boolean = file.exists() && file.length() > 0
    fun ageMs(): Long = if (file.exists()) System.currentTimeMillis() - file.lastModified() else Long.MAX_VALUE
    fun clear() { file.delete() }

    /** Rebuilds the index; returns the kinds that could not be loaded (empty = all fine). */
    suspend fun rebuild(client: XtreamClient): List<String> = withContext(Dispatchers.IO) {
        val tmp = File(file.path + ".tmp")
        val failed = mutableListOf<String>()
        var count = 0
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            failed += client.dumpCatalog { kind, id, name, image, ext, cat ->
                val clean = name.sanitize()
                if (clean.isEmpty()) return@dumpCatalog
                w.append(kind).append('\t')
                    .append(id.toString()).append('\t')
                    .append(ext.sanitize()).append('\t')
                    .append(image?.sanitize() ?: "").append('\t')
                    .append(clean).append('\t')
                    .append(normalize(clean)).append('\t')
                    .append(cat?.sanitize() ?: "").append('\n')
                count++
            }
        }
        if (count > 0) {
            file.delete()
            tmp.renameTo(file)
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
