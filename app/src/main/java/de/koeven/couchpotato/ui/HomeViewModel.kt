package de.koeven.couchpotato.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.koeven.couchpotato.data.AppEntry
import de.koeven.couchpotato.data.AppsRepository
import de.koeven.couchpotato.data.CatalogCache
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.ProgressEntry
import de.koeven.couchpotato.data.ProgressStore
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.SearchResults
import de.koeven.couchpotato.data.SeriesInfo
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.data.WatchNextCache
import de.koeven.couchpotato.data.WatchNextSource
import de.koeven.couchpotato.data.XtreamClient
import de.koeven.couchpotato.data.XtreamConfig
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One tile in "Weiterschauen": either IPTV (own player) or another app's Watch Next entry. */
sealed interface ContinueItem {
    val key: String
    val sortTime: Long

    data class Iptv(val entry: ProgressEntry) : ContinueItem {
        override val key: String get() = entry.item.key
        override val sortTime: Long get() = entry.updatedAt
    }

    data class External(val program: WatchNextSource.Program, val appLabel: String?) : ContinueItem {
        override val key: String get() = program.key
        override val sortTime: Long get() = program.lastEngagement
    }
}

data class HomeState(
    val continueWatching: List<ContinueItem> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val movies: List<VodItem> = emptyList(),
    val series: List<SeriesItem> = emptyList(),
    val configured: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val tick: Int = 0,          // bumps whenever local data (progress) was re-read
    val externalStatus: String = "",
    val favorites: List<LiveChannel> = emptyList(),
    val nowPlaying: Map<Long, String> = emptyMap(),
    val liveInExternalApp: Boolean = false,
    val indexStatus: String = "",
    val searchApps: List<SearchApp> = emptyList(),
)

/** An installed app in the "search in other apps" row; [acceptsQuery] = search term is handed over. */
data class SearchApp(val app: AppEntry, val acceptsQuery: Boolean)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val progress = ProgressStore(app)
    private val cache = CatalogCache(app)
    private val watchNextCache = WatchNextCache(app)
    private val seriesCache = HashMap<Long, SeriesInfo>()
    private var catalogJob: Job? = null
    private var epgJob: Job? = null
    private var epgLoadedAt = 0L
    private val liveCache = HashMap<String, List<LiveChannel>>()
    private var categoriesCache: List<LiveCategory>? = null

    private val searchIndex = SearchIndex(app)
    private var indexEntries: List<SearchEntry>? = null
    private var indexJob: Job? = null
    private val _search = MutableStateFlow(SearchResults())
    val search: StateFlow<SearchResults> = _search
    val searchQuery = MutableStateFlow("")

    private val _state = MutableStateFlow(HomeState(configured = settings.config().isValid))
    val state: StateFlow<HomeState> = _state

    init {
        cache.load()?.let { c -> _state.update { it.copy(movies = c.movies, series = c.series) } }
    }

    fun config(): XtreamConfig = settings.config()

    fun onResume() {
        viewModelScope.launch { refreshLocal() }
        refreshCatalog(force = false)
        refreshEpg(force = false)
    }

    private suspend fun refreshLocal() {
        val hidden = settings.hiddenApps()
        val apps = withContext(Dispatchers.IO) {
            AppsRepository.load(getApplication())
                .filterNot { it.packageName in hidden }
                .sortedWith(
                    compareByDescending<AppEntry> { settings.lastLaunched(it.packageName) }
                        .thenBy { it.label.lowercase() }
                )
        }
        val labels = apps.associate { it.packageName to it.label }
        val hiddenCw = settings.hiddenContinue()
        val cw = withContext(Dispatchers.IO) {
            val iptv = progress.continueWatching().map { ContinueItem.Iptv(it) }
            val external = watchNextCache.merge(WatchNextSource.load(getApplication())) { pkg -> settings.lastLaunched(pkg) }
                .filter { it.packageName != getApplication<Application>().packageName }
                .map { ContinueItem.External(it, it.packageName?.let { pkg -> labels[pkg] }) }
            (iptv + external)
                .filterNot { it.key in hiddenCw }
                .sortedByDescending { it.sortTime }
                .take(40)
        }
        _state.update {
            it.copy(
                apps = apps,
                continueWatching = cw,
                configured = settings.config().isValid,
                tick = it.tick + 1,
                externalStatus = WatchNextSource.lastResult,
                favorites = settings.favorites(),
                liveInExternalApp = settings.liveInExternalApp,
            )
        }
    }

    fun refreshCatalog(force: Boolean) {
        val cfg = settings.config()
        if (!cfg.isValid) return
        if (catalogJob?.isActive == true) {
            if (!force) return
            catalogJob?.cancel()
        }
        if (!force && cache.ageMs() < CATALOG_MAX_AGE_MS && _state.value.movies.isNotEmpty()) return

        catalogJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val client = XtreamClient(cfg)
                val (movies, series) = coroutineScope {
                    val m = async { client.latestMovies(ROW_SIZE) }
                    val s = async { client.latestSeries(ROW_SIZE) }
                    m.await() to s.await()
                }
                withContext(Dispatchers.IO) { cache.save(movies, series) }
                _state.update { it.copy(movies = movies, series = series, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    suspend fun saveAndTest(cfg: XtreamConfig): String {
        settings.save(cfg)
        seriesCache.clear()
        liveCache.clear()
        categoriesCache = null
        searchIndex.clear()
        indexEntries = null
        _state.update { it.copy(configured = settings.config().isValid) }
        return try {
            val msg = XtreamClient(settings.config()).checkAuth()
            refreshCatalog(force = true)
            msg
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------------- Live-TV ----------------

    /** "Jetzt läuft" for the favourites; refreshed at most every 5 minutes unless forced. */
    fun refreshEpg(force: Boolean) {
        val cfg = settings.config()
        val favs = settings.favorites()
        if (!cfg.isValid || favs.isEmpty() || epgJob?.isActive == true) return
        if (!force && System.currentTimeMillis() - epgLoadedAt < EPG_MAX_AGE_MS) return
        epgJob = viewModelScope.launch {
            val client = XtreamClient(cfg)
            val result = coroutineScope {
                favs.take(40).map { ch ->
                    async { ch.id to runCatching { client.nowPlaying(ch.id) }.getOrNull() }
                }.awaitAll()
            }
            epgLoadedAt = System.currentTimeMillis()
            val map = result.mapNotNull { (id, title) -> title?.let { id to it } }.toMap()
            _state.update { it.copy(nowPlaying = map) }
        }
    }

    suspend fun liveCategories(): List<LiveCategory> =
        categoriesCache ?: XtreamClient(settings.config()).liveCategories().also { categoriesCache = it }

    suspend fun liveChannels(categoryId: String): List<LiveChannel> =
        liveCache[categoryId] ?: XtreamClient(settings.config()).liveChannels(categoryId)
            .also { liveCache[categoryId] = it }

    fun toggleFavorite(channel: LiveChannel) {
        val current = settings.favorites()
        val updated = if (current.any { it.id == channel.id }) current.filterNot { it.id == channel.id }
        else current + channel
        settings.setFavorites(updated)
        _state.update { it.copy(favorites = updated) }
        refreshEpg(force = true)
    }

    /** Moves a favourite one place to the left (towards the start of the row). */
    fun moveFavoriteLeft(channel: LiveChannel) {
        val list = settings.favorites().toMutableList()
        val i = list.indexOfFirst { it.id == channel.id }
        if (i <= 0) return
        list.add(i - 1, list.removeAt(i))
        settings.setFavorites(list)
        _state.update { it.copy(favorites = list) }
    }

    fun setLiveInExternalApp(value: Boolean) {
        settings.liveInExternalApp = value
        _state.update { it.copy(liveInExternalApp = value) }
    }

    /**
     * Plays [channels] starting at [index]. In Couchpotato's player you can zap through the list
     * with up/down; optionally the stream is handed to IsiPro instead.
     */
    fun playLive(context: Context, channels: List<LiveChannel>, index: Int) {
        val channel = channels.getOrNull(index) ?: return
        if (settings.liveInExternalApp && openInExternalApp(context, channel)) return
        Playback.start(context, channels.map { PlayItem.live(it) }, index)
    }

    private fun openInExternalApp(context: Context, channel: LiveChannel): Boolean {
        val pkg = _state.value.apps.firstOrNull { it.label.contains("isipro", ignoreCase = true) }?.packageName
        if (pkg == null) {
            Toast.makeText(context, "IsiPro nicht gefunden – spiele in Couchpotato", Toast.LENGTH_SHORT).show()
            return false
        }
        val url = XtreamClient.streamUrl(settings.config(), PlayItem.live(channel))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess.also { ok ->
            if (ok) settings.markLaunched(pkg)
            else Toast.makeText(context, "IsiPro nimmt keine Sender-Links an – spiele in Couchpotato", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Suche ----------------

    /** Called when the search screen opens: app list for hand-over + make sure the index is there. */
    fun prepareSearch() {
        viewModelScope.launch {
            val searchable = withContext(Dispatchers.IO) { AppsRepository.searchablePackages(getApplication()) }
            val apps = _state.value.apps
                .map { SearchApp(it, it.packageName in searchable) }
                .sortedByDescending { it.acceptsQuery }
            _state.update { it.copy(searchApps = apps) }
        }
        ensureIndex(force = false)
    }

    fun ensureIndex(force: Boolean) {
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            if (indexEntries == null && searchIndex.exists()) {
                setIndexStatus("Lade Suchindex …")
                indexEntries = searchIndex.load()
                setIndexStatus(indexSummary(emptyList()))
                runSearch(searchQuery.value)
            }
            val cfg = settings.config()
            if (!cfg.isValid) {
                setIndexStatus("IPTV ist noch nicht eingerichtet – gesucht wird nur in Apps.")
                return@launch
            }
            val stale = !searchIndex.exists() || searchIndex.ageMs() > INDEX_MAX_AGE_MS
            if (!force && !stale) return@launch
            setIndexStatus(
                if (indexEntries.isNullOrEmpty()) "Suchindex wird aufgebaut … (einmalig, dauert je nach Anbieter etwas)"
                else "Suchindex wird im Hintergrund aktualisiert …"
            )
            try {
                val failed = searchIndex.rebuild(XtreamClient(cfg))
                indexEntries = searchIndex.load()
                setIndexStatus(indexSummary(failed))
                runSearch(searchQuery.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setIndexStatus("Suchindex konnte nicht geladen werden: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun indexSummary(failed: List<String>): String {
        val entries = indexEntries.orEmpty()
        val m = entries.count { it.kind == SearchEntry.KIND_MOVIE }
        val s = entries.count { it.kind == SearchEntry.KIND_SERIES }
        val l = entries.count { it.kind == SearchEntry.KIND_LIVE }
        val base = "Durchsucht $m Filme, $s Serien und $l Sender"
        return if (failed.isEmpty()) base else "$base · nicht geladen: ${failed.joinToString()}"
    }

    private fun setIndexStatus(text: String) = _state.update { it.copy(indexStatus = text) }

    fun setQuery(q: String) {
        searchQuery.value = q
    }

    suspend fun runSearch(q: String) {
        val entries = indexEntries ?: return
        _search.value = withContext(Dispatchers.Default) { SearchIndex.search(entries, q) }
    }

    fun searchInApp(context: Context, app: SearchApp, query: String) {
        settings.markLaunched(app.app.packageName)
        val handedOver = app.acceptsQuery && AppsRepository.searchInApp(context, app.app.packageName, query)
        if (!handedOver) {
            if (!app.acceptsQuery) AppsRepository.launch(context, app.app.packageName)
            Toast.makeText(context, "${app.app.label} nimmt keinen Suchbegriff an – bitte dort suchen", Toast.LENGTH_SHORT).show()
        }
    }

    fun openGlobalSearch(context: Context, query: String) {
        if (!AppsRepository.openGlobalSearch(context, query)) {
            Toast.makeText(context, "Fire-TV-Suche lässt sich hier nicht öffnen", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Apps / Weiterschauen ----------------

    fun launchApp(pkg: String) {
        settings.markLaunched(pkg)
        AppsRepository.launch(getApplication(), pkg)
    }

    fun hideApp(pkg: String) {
        settings.hideApp(pkg)
        viewModelScope.launch { refreshLocal() }
    }

    fun resetHiddenApps() {
        settings.resetHiddenApps()
        viewModelScope.launch { refreshLocal() }
    }

    fun removeFromContinue(item: ContinueItem) {
        when (item) {
            is ContinueItem.Iptv -> progress.remove(item.key)
            is ContinueItem.External -> settings.hideContinue(item.key)   // can't delete other apps' entries
        }
        viewModelScope.launch { refreshLocal() }
    }

    fun openExternal(item: ContinueItem.External) {
        item.program.packageName?.let { settings.markLaunched(it) }
        WatchNextSource.open(getApplication(), item.program)
    }

    fun progressFor(key: String): ProgressEntry? = progress.get(key)

    fun progressForSeries(seriesId: Long): ProgressEntry? = progress.forSeries(seriesId)

    suspend fun loadSeries(seriesId: Long, fallbackName: String): SeriesInfo =
        seriesCache[seriesId] ?: XtreamClient(settings.config()).seriesInfo(seriesId, fallbackName)
            .also { seriesCache[seriesId] = it }

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

    private companion object {
        const val ROW_SIZE = 40
        const val CATALOG_MAX_AGE_MS = 3 * 60 * 60 * 1000L
        const val EPG_MAX_AGE_MS = 5 * 60 * 1000L
        const val INDEX_MAX_AGE_MS = 12 * 60 * 60 * 1000L
    }
}
