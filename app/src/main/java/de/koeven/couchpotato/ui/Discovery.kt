package de.koeven.couchpotato.ui

import android.content.Context
import de.koeven.couchpotato.data.AppEntry
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.Themes
import de.koeven.couchpotato.data.TitleDetails
import de.koeven.couchpotato.data.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** TMDB/JustWatch service name (normalised start) → words to find the installed app by its label. */
private val PROVIDER_KEYS = linkedMapOf(
    "amazon prime" to listOf("prime video", "amazon prime"),
    "amazon video" to listOf("prime video"),
    "disney plus" to listOf("disney"),
    "apple tv" to listOf("apple tv"),
    "paramount" to listOf("paramount"),
    "ard" to listOf("ard"),
    "zdf" to listOf("zdf"),
    "rtl" to listOf("rtl"),
    "joyn" to listOf("joyn"),
    "wow" to listOf("wow"),
    "sky" to listOf("sky", "wow"),
    "magenta" to listOf("magenta"),
    "youtube" to listOf("youtube"),
    "netflix" to listOf("netflix"),
)


/**
 * Finding things beyond the IPTV catalogue – all via TMDB, never at the IPTV provider:
 * which installed streaming app has a title, the cross-app search and the theme search
 * ("Weihnachtsfilm", "Film mit Fußball").
 */
class Discovery(
    private val app: Context,
    private val tmdb: Tmdb,
    private val apps: () -> List<AppEntry>,
    private val catalogue: () -> List<SearchEntry>?,
    private val hidden: () -> Set<String>,
) {

    /** A TMDB hit together with the installed apps that stream it. */
    data class StreamResult(val hit: Tmdb.StreamHit, val apps: List<AppEntry>)

    /** Installed app for a streaming service name from TMDB/JustWatch ("Disney Plus" → Disney+ app). */
    fun appFor(provider: String): AppEntry? {
        val p = SearchIndex.normalize(provider.replace("+", " plus"))
        val keys = PROVIDER_KEYS.entries.firstOrNull { (k, _) -> p.startsWith(k) }?.value
            ?: listOf(p.substringBefore(' '))
        return apps().firstOrNull { a ->
            val label = SearchIndex.normalize(a.label.replace("+", " plus"))
            keys.any { k -> " $label".contains(" $k") }   // at a word start: "ard" must not hit "Leonardo"
        }
    }

    fun appsFor(providers: List<String>): List<AppEntry> = providers.mapNotNull { appFor(it) }.distinctBy { it.packageName }

    /** Cross-app search (not in kids profiles: no FSK there). Only hits you can watch with an installed app. */
    suspend fun streamingSearch(query: String): List<StreamResult> {
        if (Profiles.active(app).kid || !tmdb.enabled || query.trim().length < 3) return emptyList()
        return runCatching { tmdb.searchStreaming(query) }.getOrDefault(emptyList())
            .map { StreamResult(it, appsFor(it.providers)) }
            .filter { it.apps.isNotEmpty() }
    }


    data class ThemeResult(val label: String, val iptv: List<SearchEntry>, val streaming: List<StreamResult>)

    private var matchIndexOf: List<SearchEntry>? = null
    private var byTmdb: Map<String, SearchEntry> = emptyMap()
    private var byName: Map<String, List<SearchEntry>> = emptyMap()

    /** Lookup tables to find TMDB titles in the IPTV catalogue (by TMDB id, else by cleaned name). */
    private fun ensureMatchIndex(entries: List<SearchEntry>) {
        if (matchIndexOf === entries) return
        val t = HashMap<String, SearchEntry>()
        val n = HashMap<String, MutableList<SearchEntry>>()
        for (e in entries) {
            if (e.kind == SearchEntry.KIND_LIVE) continue
            e.tmdbId?.let { t["${e.kind}:$it"] = e }
            val clean = SearchIndex.normalize(Tmdb.cleanName(e.name).first)
            if (clean.isNotEmpty()) n.getOrPut("${e.kind}:$clean") { mutableListOf() } += e
        }
        byTmdb = t
        byName = n
        matchIndexOf = entries
    }

    private fun findInCatalog(h: Tmdb.ThemeHit): SearchEntry? {
        val kind = if (h.tv) SearchEntry.KIND_SERIES else SearchEntry.KIND_MOVIE
        byTmdb["$kind:${h.tmdbId}"]?.let { return it }
        val hy = h.year?.toIntOrNull()
        for (name in listOfNotNull(h.title, h.originalTitle)) {
            val list = byName["$kind:${SearchIndex.normalize(Tmdb.cleanName(name).first)}"] ?: continue
            val hit = list.firstOrNull { e ->
                val ey = (e.year ?: Tmdb.cleanName(e.name).second)?.toIntOrNull()
                hy == null || ey == null || kotlin.math.abs(ey - hy) <= 1
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * "Weihnachtsfilm", "Film mit Fußball": popular titles on that theme from TMDB, split into what is
     * in the IPTV catalogue (matched on the stick) and what runs at installed streaming services.
     */
    suspend fun themeSearch(query: String): ThemeResult? {
        if (!tmdb.enabled) return null
        val theme = Themes.match(query)
        val free = if (theme == null) {
            Themes.freeWords(query).takeIf { it.length >= 3 && it != SearchIndex.normalize(query) }   // only "Film über X"
        } else null
        if (theme == null && free == null) return null
        val hits = runCatching { tmdb.themeTitles(theme, free) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return null
        val entries = catalogue().orEmpty()
        val hidden = hidden()
        val kid = Profiles.active(app).kid
        return withContext(Dispatchers.Default) {
            ensureMatchIndex(entries)
            val matched = HashSet<String>()
            val iptv = hits.mapNotNull { h ->
                findInCatalog(h)?.takeIf { it.categoryKey !in hidden }?.also { matched += "${h.tv}${h.tmdbId}" }
            }.distinctBy { "${it.kind}${it.id}" }.take(40)
            val streaming = if (kid) emptyList() else coroutineScope {
                hits.filterNot { "${it.tv}${it.tmdbId}" in matched }.take(24).map { h ->
                    async {
                        val provs = runCatching { tmdb.providers(h.tv, h.tmdbId) }.getOrDefault(emptyList())
                        val apps = appsFor(provs)
                        if (apps.isEmpty()) null
                        else StreamResult(Tmdb.StreamHit(h.tmdbId, h.tv, h.title, h.year, h.poster, provs), apps)
                    }
                }.awaitAll().filterNotNull()
            }
            ThemeResult(theme?.label ?: free!!.replaceFirstChar { it.uppercase() }, iptv, streaming)
        }
    }

    /** Where a title from the info screen also streams (installed apps only). */
    suspend fun alsoOn(details: TitleDetails?): List<AppEntry> {
        val id = details?.tmdbId ?: return emptyList()
        if (Profiles.active(app).kid || !tmdb.enabled) return emptyList()
        return appsFor(runCatching { tmdb.providers(details.tv, id) }.getOrDefault(emptyList()))
    }

}
