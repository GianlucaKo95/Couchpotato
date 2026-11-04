package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.MovieInfo
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.ProgressEntry
import de.koeven.couchpotato.data.Recommender
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesInfo
import de.koeven.couchpotato.data.TitleDetails
import de.koeven.couchpotato.data.TitleInfoRepository
import de.koeven.couchpotato.data.WatchedStore
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One film or series: details, info area texts, episodes, seen/unseen and where to continue. */
class TitlesController(private val ctx: HomeContext) {
    private val app get() = ctx.app
    private val tmdb get() = ctx.tmdb

    /** Series episodes and film details from the provider (on the stick, throttled requests). */
    private val repo = TitleInfoRepository(ctx.app, ctx.settings) { ctx.entries }

    /** Login changed: the stored ids belong to the old provider. */
    fun clearProviderData() {
        repo.clear()
        // TMDB details are filed under the provider's ids (m_123 / s_45): those belong to the old provider
        java.io.File(app.filesDir, "tmdb").listFiles { f -> f.name.startsWith("m_") || f.name.startsWith("s_") }?.forEach { it.delete() }
    }

    /** Catalogue entry of a film or series (name, cover, rating …) – local. */
    fun entry(kind: Char, id: Long): SearchEntry? =
        ctx.entries?.firstOrNull { it.kind == kind && it.id == id }

    /** Titles like this one, from the catalogue – local. */
    suspend fun similarTo(kind: Char, id: Long): List<SearchEntry> {
        val entries = ctx.entries ?: return emptyList()
        val of = entries.firstOrNull { it.kind == kind && it.id == id } ?: return emptyList()
        val hidden = ctx.hidden()
        return withContext(Dispatchers.Default) { Recommender.similar(entries, of, hidden) }
    }

    /** Film details from the provider – see [TitleInfoRepository.movieInfo]. */
    suspend fun movieInfo(id: Long): Result<MovieInfo>? = repo.movieInfo(id)

    /**
     * Info screen data: TMDB first (separate service – costs the IPTV provider nothing),
     * otherwise the provider's own details (one throttled request, kept for good).
     * Failure carries the reason, null = nothing to show.
     */
    suspend fun details(kind: Char, id: Long, fallbackName: String): Result<TitleDetails>? {
        val e = entry(kind, id)
        if (tmdb.enabled) {
            val r = runCatching { tmdb.details(kind, id, e?.name ?: fallbackName, e?.year, e?.tmdbId) }
            r.getOrNull()?.let { return Result.success(it) }
            if (kind == SearchEntry.KIND_SERIES) return r.exceptionOrNull()?.let { Result.failure(it) }
        }
        if (kind != SearchEntry.KIND_MOVIE) return null      // series: the provider data comes with the episodes
        return movieInfo(id)?.map { TitleDetails.fromProvider(it) }
    }

    // ---------------- Info-Bereich auf der Startseite ----------------

    /** Short plot for the info area: TMDB if already on the stick, else the provider's series text. No request. */
    suspend fun heroPlot(kind: Char, id: Long, season: Int? = null, episode: Int? = null): String? = withContext(Dispatchers.IO) {
        val details = tmdb.cachedDetails(kind, id)
        // an episode: its own plot if TMDB knows it, else the series'
        val ep = if (season != null && episode != null) details?.tmdbId?.let { tmdb.episodePlot(it, season, episode, fetch = false) } else null
        ep ?: details?.plot?.takeIf { it.isNotBlank() }
            ?: if (kind == SearchEntry.KIND_SERIES) repo.cachedSeries(id)?.plot?.takeIf { it.isNotBlank() } else null
    }

    /** Wide TMDB picture for the info area, if already on the stick. No request. */
    suspend fun heroBackdrop(kind: Char, id: Long): String? = withContext(Dispatchers.IO) {
        tmdb.cachedDetails(kind, id)?.backdrop?.replace("/w1280/", "/w780/")   // plenty for a third of a TV
    }

    private val heroPrefetched = HashSet<String>()

    /**
     * "Weiterschauen": fetch the missing TMDB plots in the background (films, series and the
     * episode itself) – a handful of TMDB requests, nothing at the IPTV provider. Once per title.
     */
    fun prefetchHeroPlots(cw: List<ContinueItem>) {
        if (!tmdb.enabled) return
        val todo = cw.take(12).mapNotNull { c ->
            when (c) {
                is ContinueItem.Iptv -> c.entry.item
                    .takeIf { it.kind != PlayItem.KIND_LIVE }
                    ?.let { if (it.kind == PlayItem.KIND_EPISODE) it.seriesId?.let { sid -> Triple(SearchEntry.KIND_SERIES, sid, it) } else Triple(SearchEntry.KIND_MOVIE, it.streamId, it) }
                is ContinueItem.NewEpisodes -> Triple(SearchEntry.KIND_SERIES, c.series.id, null)
                else -> null
            }
        }.filter { (k, id, item) -> heroPrefetched.add("$k$id:${item?.streamId}") }
        if (todo.isEmpty()) return
        ctx.scope.launch(Dispatchers.IO) {
            for ((kind, id, item) in todo) {
                runCatching {
                    val details = tmdb.cachedDetails(kind, id) ?: run {
                        val e = entry(kind, id)
                        tmdb.details(kind, id, e?.name ?: item?.title ?: return@runCatching, e?.year, e?.tmdbId)
                    }
                    // the episode: "S01 E03 · …" → its own plot
                    val m = item?.subtitle?.let { Regex("""^S(\d+) E(\d+)""").find(it) }
                    val tv = details?.tmdbId
                    if (m != null && tv != null) {
                        tmdb.episodePlot(tv, m.groupValues[1].toInt(), m.groupValues[2].toInt(), fetch = true)
                    }
                }
            }
        }
    }

    // ---------------- Serien / Fortschritt ----------------

    suspend fun loadSeries(seriesId: Long, fallbackName: String): SeriesInfo = repo.loadSeries(seriesId, fallbackName)

    fun watchedEpisodes(seriesId: Long): Set<Long> = WatchedStore(app).forSeries(seriesId)

    /**
     * Marks episodes as (un)seen. "Weiterschauen" then continues with the first unseen episode
     * after the last seen one – or drops the series when everything is seen.
     */
    fun markWatched(info: SeriesInfo, episodeIds: Collection<Long>, watched: Boolean) {
        WatchedStore(app).set(info.id, episodeIds, watched)
        val seen = WatchedStore(app).forSeries(info.id)
        val eps = info.episodes
        val lastSeen = eps.indexOfLast { it.id in seen }
        if (watched && lastSeen >= 0) {
            val next = eps.drop(lastSeen + 1).firstOrNull { it.id !in seen }
            val last = PlayItem.episode(info, eps[lastSeen])
            if (next != null) ctx.progress.finished(last, PlayItem.episode(info, next))
            else {
                ctx.progress.forSeries(info.id)?.let { ctx.progress.remove(it.item.key) }
                ctx.history.add(last)
            }
        }
        ctx.update { it.copy(tick = it.tick + 1) }
        ctx.refreshLocalLater()
    }

    /** "Von vorne": forget the position, the player then starts at 0. */
    fun restartFromBeginning(key: String) {
        ctx.progress.remove(key)
        ctx.refreshLocalLater()
    }

    fun progressFor(key: String): ProgressEntry? = ctx.progress.get(key)
    fun progressForSeries(seriesId: Long): ProgressEntry? = ctx.progress.forSeries(seriesId)

    /** For series: the stored episode plus the following ones, so playback continues automatically. */
    suspend fun playlistFor(entry: ProgressEntry): List<PlayItem> {
        val item = entry.item
        val sid = item.seriesId
        if (item.kind != PlayItem.KIND_EPISODE || sid == null) return listOf(item)
        return runCatching { Playback.episodePlaylist(loadSeries(sid, item.title), item.streamId) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(item)
    }
}
