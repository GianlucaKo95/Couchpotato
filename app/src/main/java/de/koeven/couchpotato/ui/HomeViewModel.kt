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
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.MovieInfo
import de.koeven.couchpotato.data.Profile
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.TitleDetails
import de.koeven.couchpotato.data.Tmdb
import de.koeven.couchpotato.data.Recommender
import de.koeven.couchpotato.data.WatchHistory
import de.koeven.couchpotato.data.MediaPlay
import de.koeven.couchpotato.data.MediaPlays
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

    /** Something another app reported while playing (ARD Mediathek, Disney+ …). */
    data class Media(val play: MediaPlay, val appLabel: String?) : ContinueItem {
        override val key: String get() = play.key
        override val sortTime: Long get() = play.updatedAt
    }

    /** Streaming app whose progress we can't read (Netflix …): a tile that opens the app. */
    data class AppTile(val app: AppEntry, val lastUsed: Long) : ContinueItem {
        override val key: String get() = "app:${app.packageName}"
        override val sortTime: Long get() = lastUsed
    }
}

/** Streaming apps shown as a tile in "Weiterschauen" (if installed). */
private val STREAMING_APPS = listOf("com.netflix.ninja", "com.disney.disneyplus")

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
    val rowOrder: List<String> = HomeRows.ALL,
    val hiddenCategories: Set<String> = emptySet(),
    val appsCustomOrder: Boolean = false,
    val syncInfo: String = "",
    val imageInfo: String = "",
    val loadProviderImages: Boolean = true,
    val recommended: List<SearchEntry> = emptyList(),
    val recommendedPersonal: Boolean = false,
    val loadMovieDetails: Boolean = true,
    val tmdbOn: Boolean = false,
    val profile: Profile = Profile(Profile.MAIN, "Ich", 0, kid = false),
    val profiles: List<Profile> = emptyList(),
    val needsProfilePick: Boolean = false,
    val hasPin: Boolean = false,
    val kidCategories: Set<String> = emptySet(),
    val kidApps: Set<String> = emptySet(),
    val allApps: List<AppEntry> = emptyList(),
)

/** An installed app in the "search in other apps" row; [acceptsQuery] = search term is handed over. */
data class SearchApp(val app: AppEntry, val acceptsQuery: Boolean)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private var progress = ProgressStore(app)
    private var cache = CatalogCache(app)
    private val watchNextCache = WatchNextCache(app)
    private val seriesCache = HashMap<Long, SeriesInfo>()
    private var history = WatchHistory(app)
    private val app = app
    val tmdb = Tmdb(app)
    private val seriesDir = java.io.File(app.filesDir, "seriesinfo")
    private val movieDir = java.io.File(app.filesDir, "vodinfo")
    private var epgJob: Job? = null
    private var categoriesByKind: Map<Char, List<LiveCategory>> = emptyMap()

    private val searchIndex = SearchIndex(app)
    private var indexEntries: List<SearchEntry>? = null
    private var indexJob: Job? = null
    private val _search = MutableStateFlow(SearchResults())
    val search: StateFlow<SearchResults> = _search
    val searchQuery = MutableStateFlow("")

    private val _state = MutableStateFlow(HomeState(configured = settings.config().isValid))
    val state: StateFlow<HomeState> = _state

    init {
        XtreamClient.init(app)
        if (System.currentTimeMillis() - settings.epgLoadedAt < EPG_KEEP_MS) {
            _state.update { it.copy(nowPlaying = settings.epgTitles()) }
        }
        val profiles = Profiles.all(app)
        _state.update {
            it.copy(
                profile = Profiles.active(app),
                profiles = profiles,
                needsProfilePick = profiles.size > 1,     // "Wer schaut?" after every start
                hasPin = Profiles.hasPin(app),
                kidCategories = Profiles.kidCategories(app),
                kidApps = Profiles.kidApps(app),
            )
        }
        val hidden = effectiveHidden()
        _state.update { it.copy(hiddenCategories = hidden) }
        cache.load()?.let { c ->
            _state.update {
                it.copy(
                    movies = c.movies.filterNot { m -> "m:${m.categoryId ?: ""}" in hidden },
                    series = c.series.filterNot { s -> "s:${s.categoryId ?: ""}" in hidden },
                )
            }
        }
    }

    fun config(): XtreamConfig = settings.config()

    var loginAsLink: Boolean
        get() = settings.loginAsLink
        set(value) { settings.loginAsLink = value }

    fun onResume() {
        viewModelScope.launch {
            refreshLocal()
            recomputeRecommendations()
        }
        ensureIndex(force = false)      // local; talks to the provider at most once a day
        refreshEpg(force = false)
    }

    /** Titles started in IsiPro carry no cover: take name and cover from the local catalogue. */
    private fun enrich(e: ProgressEntry): ProgressEntry {
        if (e.item.image != null || e.item.kind != PlayItem.KIND_MOVIE) return e
        val hit = indexEntries?.firstOrNull { it.kind == SearchEntry.KIND_MOVIE && it.id == e.item.streamId } ?: return e
        return e.copy(item = e.item.copy(title = hit.name, image = hit.image))
    }

    private suspend fun refreshLocal() {
        val hidden = settings.hiddenApps()
        val kid = Profiles.active(app).kid
        val kidApps = Profiles.kidApps(app)
        val allApps = withContext(Dispatchers.IO) { AppsRepository.load(getApplication()) }
        val apps = sortApps(
            if (kid) allApps.filter { it.packageName in kidApps }      // kids: only released apps
            else allApps.filterNot { it.packageName in hidden }
        )
        val labels = apps.associate { it.packageName to it.label }
        val hiddenCw = settings.hiddenContinue()
        val cw = withContext(Dispatchers.IO) {
            val iptv = progress.continueWatching().map { ContinueItem.Iptv(enrich(it)) }
            val media = MediaPlays.all(getApplication())
                .filter { it.packageName != getApplication<Application>().packageName }
                .map { ContinueItem.Media(it, labels[it.packageName]) }
            val reporting = media.map { it.play.packageName }.toSet()
            // app tile only for apps that report nothing themselves
            val tiles = STREAMING_APPS.filter { it !in reporting }
                .mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }
                .map { ContinueItem.AppTile(it, settings.lastLaunched(it.packageName)) }
            val external = watchNextCache.merge(WatchNextSource.load(getApplication())) { pkg -> settings.lastLaunched(pkg) }
                .filter { it.packageName != getApplication<Application>().packageName }
                .map { ContinueItem.External(it, it.packageName?.let { pkg -> labels[pkg] }) }
            (if (kid) iptv else iptv + external + media + tiles)
                .filterNot { it.key in hiddenCw }
                .sortedByDescending { it.sortTime }
                .take(40)
        }
        _state.update {
            it.copy(
                apps = apps,
                allApps = allApps.sortedBy { a -> a.label.lowercase() },
                continueWatching = cw,
                configured = settings.config().isValid,
                tick = it.tick + 1,
                externalStatus = WatchNextSource.lastResult,
                favorites = settings.favorites(),
                liveInExternalApp = settings.liveInExternalApp,
                rowOrder = settings.rowOrder(),
                appsCustomOrder = settings.appsCustomOrder,
                syncInfo = syncInfo(),
                imageInfo = imageInfo(),
                loadProviderImages = settings.loadProviderImages,
                loadMovieDetails = settings.loadMovieDetails,
                tmdbOn = tmdb.enabled,
            )
        }
    }

    private fun syncInfo(): String {
        val last = searchIndex.lastSync()
        val lastText = if (last == 0L) "noch nie" else
            java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(last)) + " Uhr"
        return "Letzter Abgleich: $lastText · Anfragen in der letzten Stunde: ${XtreamClient.requestsLastHour()}" +
            " · Bilder vom Anbieter: ${XtreamClient.imagesLastHour()}"
    }

    /** Time of the last successful catalogue sync (0 = never). */
    fun lastSyncAt(): Long = searchIndex.lastSync()

    private fun imageInfo(): String =
        searchIndex.imageStats() ?: "Bildquellen werden beim nächsten Abgleich ausgewertet."

    fun setLoadProviderImages(value: Boolean) {
        settings.loadProviderImages = value
        XtreamClient.loadProviderImages = value
        _state.update { it.copy(loadProviderImages = value) }
    }

    /** "Neue Filme/Serien" come from the local catalogue; this only syncs if it is older than a day. */
    fun refreshCatalog(force: Boolean) = ensureIndex(force)

    /**
     * Saves the login. Only if it really changed are the lock pause and the catalogue reset –
     * pressing "Speichern & testen" again and again must not hammer a provider that locked us.
     * Returns true if the login changed.
     */
    fun applyLogin(cfg: XtreamConfig): Boolean {
        val before = settings.config()
        settings.save(cfg)
        val after = settings.config()
        XtreamClient.providerHost = after.host
        _state.update { it.copy(configured = after.isValid) }
        if (before == after) return false
        XtreamClient.clearBlock()
        seriesCache.clear()
        seriesDir.deleteRecursively()     // other provider: other ids
        movieDir.deleteRecursively()
        history.clear()
        searchIndex.clear()
        indexEntries = null
        categoriesByKind = emptyMap()
        settings.syncFailures = 0
        settings.lastSyncAttempt = 0L
        return true
    }

    suspend fun saveAndTest(cfg: XtreamConfig): String {
        val changed = applyLogin(cfg)
        return try {
            val msg = XtreamClient(settings.config()).checkAuth()   // 1 request
            if (changed) ensureIndex(force = true)
            msg
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------------- Live-TV ----------------

    /** "Jetzt läuft" for the first favourites; at most every 30 minutes, background budget. */
    fun refreshEpg(force: Boolean) {
        val cfg = settings.config()
        val favs = settings.favorites()
        if (!cfg.isValid || favs.isEmpty() || epgJob?.isActive == true) return
        if (!force && System.currentTimeMillis() - settings.epgLoadedAt < EPG_MAX_AGE_MS) return
        settings.epgLoadedAt = System.currentTimeMillis()   // also on failure: no retry before 30 min
        epgJob = viewModelScope.launch {
            val client = XtreamClient(cfg, background = true)
            val result = coroutineScope {
                // one after another (the client spaces them out); only the first ones in the row
                favs.take(EPG_MAX_CHANNELS).map { ch ->
                    ch.id to runCatching { client.nowPlaying(ch.id) }.getOrNull()
                }
            }
            val map = result.mapNotNull { (id, title) -> title?.let { id to it } }.toMap()
            if (map.isNotEmpty()) settings.setEpgTitles(map)
            _state.update { it.copy(nowPlaying = if (map.isEmpty()) it.nowPlaying else map) }
        }
    }

    /** Live categories without the hidden ones (used by "Sender verwalten") – local, no request. */
    suspend fun liveCategories(): List<LiveCategory> {
        val hidden = effectiveHidden()
        return allCategories(SearchEntry.KIND_LIVE).filterNot { "l:${it.id}" in hidden }
    }

    // ---------------- Mediathek / Kategorien ----------------

    /** Remembered so the Mediathek opens where you left it (e.g. after watching something). */
    var browseKind: Char = SearchEntry.KIND_LIVE
    var browseCategoryId: String? = null

    /** The local catalogue; loads it from the stick (or does the first sync) if needed. */
    private suspend fun catalog(): List<SearchEntry> {
        if (indexEntries == null) ensureIndex(force = false)
        indexJob?.join()
        return indexEntries ?: throw java.io.IOException(
            "Noch kein Katalog auf dem Stick – Einstellungen → „Katalog jetzt abgleichen“."
        )
    }

    suspend fun allCategories(kind: Char): List<LiveCategory> {
        val entries = catalog()
        categoriesByKind[kind]?.takeIf { it.isNotEmpty() }?.let { return it }
        // category names missing (that request failed): use the ids from the titles
        return entries.asSequence().filter { it.kind == kind }.mapNotNull { it.categoryId }.distinct()
            .map { LiveCategory(it, "Kategorie $it") }.toList()
    }

    /** Number of titles per category (for the Mediathek list) – local, no request. */
    suspend fun categoryCounts(kind: Char): Map<String, Int> = withContext(Dispatchers.Default) {
        catalog().asSequence().filter { it.kind == kind }.mapNotNull { it.categoryId }
            .groupingBy { it }.eachCount()
    }

    suspend fun moviesIn(categoryId: String): List<VodItem> = withContext(Dispatchers.Default) {
        catalog().filter { it.kind == SearchEntry.KIND_MOVIE && it.categoryId == categoryId }
            .sortedByDescending { it.added }
            .map { VodItem(it.id, it.name, it.image, it.added, it.ext, it.categoryId) }
    }

    suspend fun seriesIn(categoryId: String): List<SeriesItem> = withContext(Dispatchers.Default) {
        catalog().filter { it.kind == SearchEntry.KIND_SERIES && it.categoryId == categoryId }
            .sortedByDescending { it.added }
            .map { SeriesItem(it.id, it.name, it.image, it.added, it.categoryId) }
    }

    suspend fun liveChannels(categoryId: String): List<LiveChannel> = withContext(Dispatchers.Default) {
        catalog().filter { it.kind == SearchEntry.KIND_LIVE && it.categoryId == categoryId }
            .map { LiveChannel(it.id, it.name, it.image, it.categoryId) }
    }

    fun setCategoryHidden(kind: Char, categoryId: String, hidden: Boolean) =
        setCategoriesHidden(kind, listOf(categoryId), hidden)

    /** Hides/shows categories – everything is recomputed locally, without a single request. */
    @Suppress("UNUSED_PARAMETER")
    fun setCategoriesHidden(kind: Char, categoryIds: List<String>, hidden: Boolean, refresh: Boolean = true) {
        if (categoryIds.isEmpty()) return
        settings.setCategoriesHidden(categoryIds.map { "$kind:$it" }, hidden)
        _state.update { it.copy(hiddenCategories = effectiveHidden()) }
        applyCategoryChanges()
    }

    fun applyCategoryChanges(@Suppress("UNUSED_PARAMETER") kind: Char? = null) {
        viewModelScope.launch {
            recomputeRows()
            runSearch(searchQuery.value)
        }
    }

    fun toggleFavorite(channel: LiveChannel) {
        val current = settings.favorites()
        val updated = if (current.any { it.id == channel.id }) current.filterNot { it.id == channel.id }
        else current + channel
        settings.setFavorites(updated)
        _state.update { it.copy(favorites = updated) }
        // only the newly added channel needs "Jetzt läuft" (1 request instead of all of them)
        if (updated.size > current.size && updated.indexOfFirst { it.id == channel.id } < EPG_MAX_CHANNELS) {
            val cfg = settings.config()
            viewModelScope.launch {
                val title = runCatching { XtreamClient(cfg, background = true).nowPlaying(channel.id) }.getOrNull()
                    ?: return@launch
                _state.update { it.copy(nowPlaying = it.nowPlaying + (channel.id to title)) }
            }
        }
    }

    /** Moves a favourite by [delta] places; returns its new index. */
    fun moveFavorite(channelId: Long, delta: Int): Int {
        val list = settings.favorites().toMutableList()
        val i = list.indexOfFirst { it.id == channelId }
        if (i < 0) return 0
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (j != i) {
            list.add(j, list.removeAt(i))
            settings.setFavorites(list)
            _state.update { it.copy(favorites = list) }
        }
        return j
    }

    // ---------------- Reihenfolge ----------------

    private fun sortApps(apps: List<AppEntry>): List<AppEntry> {
        if (settings.appsCustomOrder) {
            val order = settings.appOrder().withIndex().associate { (i, pkg) -> pkg to i }
            // apps not in the saved order (newly installed) go to the end, alphabetically
            return apps.sortedWith(
                compareBy<AppEntry> { order[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() }
            )
        }
        return apps.sortedWith(
            compareByDescending<AppEntry> { settings.lastLaunched(it.packageName) }.thenBy { it.label.lowercase() }
        )
    }

    /** Moves an app by [delta]; switches to the user's own order on first use. Returns the new index. */
    fun moveApp(pkg: String, delta: Int): Int {
        val list = _state.value.apps.toMutableList()
        val i = list.indexOfFirst { it.packageName == pkg }
        if (i < 0) return 0
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (j != i) list.add(j, list.removeAt(i))
        // keep hidden apps' positions in the saved order too
        val shown = list.map { it.packageName }
        settings.setAppOrder(shown + settings.appOrder().filterNot { it in shown })
        settings.appsCustomOrder = true
        _state.update { it.copy(apps = list, appsCustomOrder = true) }
        return j
    }

    fun setAppsCustomOrder(custom: Boolean) {
        if (custom && settings.appOrder().isEmpty()) {
            settings.setAppOrder(_state.value.apps.map { it.packageName })
        }
        settings.appsCustomOrder = custom
        viewModelScope.launch { refreshLocal() }
    }

    fun moveRow(id: String, delta: Int) {
        val list = settings.rowOrder().toMutableList()
        val i = list.indexOf(id)
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        settings.setRowOrder(list)
        _state.update { it.copy(rowOrder = list) }
    }

    fun removeFavorite(channel: LiveChannel) {
        val updated = settings.favorites().filterNot { it.id == channel.id }
        settings.setFavorites(updated)
        _state.update { it.copy(favorites = updated) }
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

    /**
     * Loads the catalogue from the stick and syncs it with the provider only when it is older
     * than a day (or when asked to). One sync = 6 requests, nothing else talks to the provider
     * except opening a series, "Jetzt läuft" and playback itself.
     */
    fun ensureIndex(force: Boolean) {
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch { syncCatalog(force) }
    }

    /** "Katalog jetzt abgleichen" in the settings. */
    fun syncNow(): String {
        val sinceAttempt = System.currentTimeMillis() - settings.lastSyncAttempt
        if (sinceAttempt < MIN_MANUAL_SYNC_GAP_MS ||
            (searchIndex.exists() && searchIndex.ageMs() < MIN_MANUAL_SYNC_GAP_MS)
        ) {
            return "Der Katalog wurde gerade erst abgeglichen – bitte in ein paar Minuten wieder."
        }
        if (indexJob?.isActive == true) return "Abgleich läuft bereits …"
        indexJob = viewModelScope.launch { syncCatalog(force = true) }
        return "Abgleich gestartet (6 Anfragen, dauert etwas) …"
    }

    private suspend fun syncCatalog(force: Boolean) {
        loadLocalCatalog()
        val cfg = settings.config()
        if (!cfg.isValid) {
            setIndexStatus("IPTV ist noch nicht eingerichtet – gesucht wird nur in Apps.")
            return
        }
        val stale = !searchIndex.exists() || searchIndex.ageMs() > SYNC_MAX_AGE_MS
        if (!force && !stale) return
        val now = System.currentTimeMillis()
        if (!force && settings.syncFailures > 0) {
            val next = settings.lastSyncAttempt + backoffMs(settings.syncFailures)
            if (now < next) {
                val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(next))
                setIndexStatus("Letzter Abgleich fehlgeschlagen – nächster Versuch automatisch um $at Uhr.")
                return
            }
        }
        settings.lastSyncAttempt = now
        _state.update { it.copy(loading = true, error = null) }
        setIndexStatus(
            if (indexEntries.isNullOrEmpty()) "Katalog wird geladen … (einmal am Tag, 6 Anfragen)"
            else "Katalog wird im Hintergrund abgeglichen …"
        )
        try {
            // automatic daily sync = background budget; asked for by the user = normal budget
            val failed = searchIndex.rebuild(XtreamClient(cfg, background = !force))
            if (searchIndex.lastSync() >= now) settings.syncFailures = 0 else settings.syncFailures += 1
            indexEntries = null
            loadLocalCatalog()
            setIndexStatus(indexSummary(failed))
            _state.update {
                it.copy(loading = false, error = if (failed.isEmpty()) null else "Nicht geladen: ${failed.joinToString()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settings.syncFailures += 1
            _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            setIndexStatus("Katalog konnte nicht abgeglichen werden: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _state.update { it.copy(syncInfo = syncInfo(), imageInfo = imageInfo()) }
        }
    }

    private suspend fun loadLocalCatalog() {
        if (indexEntries == null && searchIndex.exists()) {
            setIndexStatus("Lade Katalog …")
            indexEntries = searchIndex.load()
            categoriesByKind = withContext(Dispatchers.IO) { searchIndex.loadCategories() }
            setIndexStatus(indexSummary(emptyList()))
        }
        recomputeRows()
        runSearch(searchQuery.value)
        refreshLocal()      // names and covers for titles started in IsiPro
    }

    /** "Neue Filme/Serien": the newest titles of the local catalogue, hidden categories left out. */
    private suspend fun recomputeRows() {
        val entries = indexEntries ?: return
        val hidden = effectiveHidden()
        val (movies, series) = withContext(Dispatchers.Default) {
            val m = entries.asSequence()
                .filter { it.kind == SearchEntry.KIND_MOVIE && it.categoryKey !in hidden }
                .sortedByDescending { it.added }
                .take(ROW_SIZE)
                .map { VodItem(it.id, it.name, it.image, it.added, it.ext, it.categoryId) }
                .toList()
            val sr = entries.asSequence()
                .filter { it.kind == SearchEntry.KIND_SERIES && it.categoryKey !in hidden }
                .sortedByDescending { it.added }
                .take(ROW_SIZE)
                .map { SeriesItem(it.id, it.name, it.image, it.added, it.categoryId) }
                .toList()
            m to sr
        }
        withContext(Dispatchers.IO) { cache.save(movies, series) }
        _state.update { it.copy(movies = movies, series = series) }
        recomputeRecommendations()
    }

    /** "Für dich" – local, recomputed after every playback (the history changes). */
    private suspend fun recomputeRecommendations() {
        val entries = indexEntries ?: return
        val hidden = effectiveHidden()
        val r = withContext(Dispatchers.Default) { Recommender.recommend(entries, hidden, history.all()) }
        _state.update { it.copy(recommended = r.items, recommendedPersonal = r.personal) }
    }

    // ---------------- Profile ----------------

    private var categoryKeysOf: List<SearchEntry>? = null
    private var categoryKeys: Set<String> = emptySet()

    /**
     * Hidden categories for the active profile: the globally hidden ones, and for kids
     * additionally everything that is not released for kids.
     */
    private fun effectiveHidden(): Set<String> {
        val hidden = settings.hiddenCategories()
        if (!Profiles.active(app).kid) return hidden
        val entries = indexEntries
        if (entries !== categoryKeysOf) {
            val all = HashSet<String>()
            categoriesByKind.forEach { (k, list) -> list.forEach { c -> all += "$k:${c.id}" } }
            entries?.forEach { all += it.categoryKey }
            categoryKeys = all
            categoryKeysOf = entries
        }
        return hidden + (categoryKeys - Profiles.kidCategories(app))
    }

    private fun refreshProfiles() {
        _state.update {
            it.copy(
                profile = Profiles.active(app),
                profiles = Profiles.all(app),
                hasPin = Profiles.hasPin(app),
                kidCategories = Profiles.kidCategories(app),
                kidApps = Profiles.kidApps(app),
            )
        }
    }

    /** Switches profile: own "Weiterschauen", history, favourites; kids see only what is released. */
    fun switchProfile(id: String) {
        Profiles.setActive(app, id)
        progress = ProgressStore(app)
        history = WatchHistory(app)
        cache = CatalogCache(app)
        categoryKeysOf = null
        refreshProfiles()
        _state.update {
            it.copy(
                needsProfilePick = false,
                hiddenCategories = effectiveHidden(),
                favorites = settings.favorites(),
                movies = emptyList(), series = emptyList(), recommended = emptyList(),
            )
        }
        searchQuery.value = ""
        _search.value = SearchResults()
        viewModelScope.launch {
            refreshLocal()
            recomputeRows()
        }
    }

    /** Section the settings should open with (e.g. "profiles" from "Profile verwalten"). */
    var settingsSection: String? = null

    fun showProfilePicker() = _state.update { it.copy(needsProfilePick = true) }
    fun closeProfilePicker() = _state.update { it.copy(needsProfilePick = false) }

    fun addProfile(name: String, kid: Boolean): Profile {
        val p = Profiles.add(app, name, kid)
        if (kid && Profiles.kidCategories(app).isEmpty()) suggestKidCategories()
        refreshProfiles()
        return p
    }

    fun updateProfile(p: Profile) {
        Profiles.update(app, p)
        refreshProfiles()
        if (p.id == Profiles.active(app).id) switchProfile(p.id)
    }

    fun removeProfile(id: String) {
        val wasActive = Profiles.active(app).id == id
        Profiles.remove(app, id)
        refreshProfiles()
        if (wasActive) switchProfile(Profile.MAIN)
    }

    fun setPin(pin: String) {
        Profiles.setPin(app, pin)
        refreshProfiles()
    }

    fun checkPin(pin: String): Boolean = Profiles.hasPin(app) && Profiles.checkPin(app, pin)

    /** PIN needed to leave a kids profile (and to open the settings there). */
    fun pinNeeded(): Boolean = Profiles.active(app).kid && Profiles.hasPin(app)

    fun setKidCategory(key: String, allowed: Boolean) {
        Profiles.setKidCategory(app, key, allowed)
        refreshProfiles()
        if (Profiles.active(app).kid) applyCategoryChanges()
    }

    fun setKidApp(pkg: String, allowed: Boolean) {
        Profiles.setKidApp(app, pkg, allowed)
        refreshProfiles()
        viewModelScope.launch { refreshLocal() }
    }

    /** First kids profile: pre-select categories whose names sound like kids' content. */
    private fun suggestKidCategories() {
        for ((k, list) in categoriesByKind) {
            list.filter { Profiles.looksLikeKids(it.name) }.forEach { Profiles.setKidCategory(app, "$k:${it.id}", true) }
        }
    }

    /** All categories of a kind (also hidden ones) – for the kids release list. */
    fun categoriesFor(kind: Char): List<LiveCategory> = categoriesByKind[kind].orEmpty()

    sealed interface Gate {
        data object Allowed : Gate
        data object Checking : Gate
        data class Blocked(val reason: String) : Gate
    }

    /**
     * May the active profile play this title? Adults: always. Kids: only with a known FSK up to
     * the profile's limit – or if a parent released the title with the PIN.
     */
    fun gate(kind: Char, id: Long, fsk: String?, detailsLoaded: Boolean): Gate {
        val p = Profiles.active(app)
        if (!p.kid) return Gate.Allowed
        if ("$kind:$id" in Profiles.unlocked(app)) return Gate.Allowed
        if (!detailsLoaded) return Gate.Checking
        val age = fsk?.toIntOrNull() ?: return Gate.Blocked("FSK unbekannt")
        return if (age <= p.maxFsk) Gate.Allowed else Gate.Blocked("FSK $age")
    }

    fun unlockTitle(kind: Char, id: Long) = Profiles.unlock(app, "$kind:$id")

    // ---------------- Info-Screens ----------------

    /** Catalogue entry of a film or series (name, cover, rating …) – local. */
    fun catalogEntry(kind: Char, id: Long): SearchEntry? =
        indexEntries?.firstOrNull { it.kind == kind && it.id == id }

    /** Titles like this one, from the catalogue – local. */
    suspend fun similarTo(kind: Char, id: Long): List<SearchEntry> {
        val entries = indexEntries ?: return emptyList()
        val of = entries.firstOrNull { it.kind == kind && it.id == id } ?: return emptyList()
        val hidden = effectiveHidden()
        return withContext(Dispatchers.Default) { Recommender.similar(entries, of, hidden) }
    }

    /**
     * Film details: from the stick if fetched before; otherwise ONE request (through the brake),
     * then kept for good. Returns null if switched off or the provider can't be asked right now.
     */
    suspend fun movieInfo(id: Long): Result<MovieInfo>? = withContext(Dispatchers.IO) {
        val f = java.io.File(movieDir, "$id.json")
        if (f.exists()) {
            runCatching { MovieInfo.fromInfo(org.json.JSONObject(f.readText()).getJSONObject("info")) }
                .getOrNull()?.let { return@withContext Result.success(it) }
        }
        if (!settings.loadMovieDetails) return@withContext null
        runCatching {
            val text = XtreamClient(settings.config()).vodInfoRaw(id)
            movieDir.mkdirs()
            f.writeText(text)
            MovieInfo.fromInfo(org.json.JSONObject(text).getJSONObject("info"))
        }
    }

    /**
     * Info screen data: TMDB first (separate service – costs the IPTV provider nothing),
     * otherwise the provider's own details (one throttled request, kept for good).
     * Failure carries the reason, null = nothing to show.
     */
    suspend fun titleDetails(kind: Char, id: Long, fallbackName: String): Result<TitleDetails>? {
        val e = catalogEntry(kind, id)
        if (tmdb.enabled) {
            val r = runCatching { tmdb.details(kind, id, e?.name ?: fallbackName, e?.year, e?.tmdbId) }
            r.getOrNull()?.let { return Result.success(it) }
            if (kind == SearchEntry.KIND_SERIES) return r.exceptionOrNull()?.let { Result.failure(it) }
        }
        if (kind != SearchEntry.KIND_MOVIE) return null      // series: the provider data comes with the episodes
        return movieInfo(id)?.map { TitleDetails.fromProvider(it) }
    }

    fun setTmdbToken(token: String) {
        settings.tmdbToken = token
        _state.update { it.copy(tmdbOn = tmdb.enabled) }
    }

    fun setLoadMovieDetails(value: Boolean) {
        settings.loadMovieDetails = value
        _state.update { it.copy(loadMovieDetails = value) }
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

    fun recentSearches(): List<String> = settings.recentSearches()
    fun rememberSearch(q: String) = settings.addRecentSearch(q)

    fun setQuery(q: String) {
        searchQuery.value = q
    }

    suspend fun runSearch(q: String) {
        val entries = indexEntries ?: return
        val hidden = effectiveHidden()
        _search.value = withContext(Dispatchers.Default) { SearchIndex.search(entries, q, hidden) }
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
            is ContinueItem.AppTile -> settings.hideContinue(item.key)
            is ContinueItem.Media -> MediaPlays.remove(getApplication(), item.key)
        }
        viewModelScope.launch { refreshLocal() }
    }

    /** Back to where the other app was playing; if it gave no link, just start the app. */
    fun openMedia(item: ContinueItem.Media) {
        settings.markLaunched(item.play.packageName)
        if (!MediaPlays.open(item.key)) AppsRepository.launch(getApplication(), item.play.packageName)
    }

    fun openExternal(item: ContinueItem.External) {
        item.program.packageName?.let { settings.markLaunched(it) }
        WatchNextSource.open(getApplication(), item.program)
    }

    fun progressFor(key: String): ProgressEntry? = progress.get(key)

    /** "Von vorne": forget the position, the player then starts at 0. */
    fun restartFromBeginning(key: String) {
        progress.remove(key)
        viewModelScope.launch { refreshLocal() }
    }

    fun progressForSeries(seriesId: Long): ProgressEntry? = progress.forSeries(seriesId)

    suspend fun loadSeries(seriesId: Long, fallbackName: String): SeriesInfo =
        seriesCache[seriesId] ?: loadSeriesCached(seriesId, fallbackName).also { seriesCache[seriesId] = it }

    /**
     * Series info from the stick if younger than a day (new episodes!), otherwise ONE request.
     * If the provider can't be asked right now, the older copy is used.
     */
    private suspend fun loadSeriesCached(seriesId: Long, fallbackName: String): SeriesInfo = withContext(Dispatchers.IO) {
        val client = XtreamClient(settings.config())
        val f = java.io.File(seriesDir, "$seriesId.json")
        val stale = if (f.exists()) runCatching { client.parseSeriesInfo(f.readText(), seriesId, fallbackName) }.getOrNull() else null
        if (stale != null && System.currentTimeMillis() - f.lastModified() < SERIES_MAX_AGE_MS) return@withContext stale
        try {
            val text = client.seriesInfoRaw(seriesId)
            seriesDir.mkdirs()
            f.writeText(text)
            client.parseSeriesInfo(text, seriesId, fallbackName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stale ?: throw e
        }
    }

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
        const val SERIES_MAX_AGE_MS = 24 * 60 * 60 * 1000L     // series info: once a day at most
        // kept low on purpose: the provider locks the account after too many requests
        const val SYNC_MAX_AGE_MS = 24 * 60 * 60 * 1000L       // whole catalogue: once a day
        const val MIN_MANUAL_SYNC_GAP_MS = 10 * 60 * 1000L     // "jetzt abgleichen" not more often
        const val EPG_MAX_AGE_MS = 30 * 60 * 1000L             // "Jetzt läuft": every 30 minutes …
        const val EPG_MAX_CHANNELS = 8                         // … for the first 8 favourites
        const val EPG_KEEP_MS = 2 * 60 * 60 * 1000L            // show stored titles after a reboot

        /** Failed syncs: retry after 1 h, 2 h, 4 h, 8 h, then every 12 h. */
        fun backoffMs(failures: Int): Long =
            (60 * 60 * 1000L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(12 * 60 * 60 * 1000L)
    }
}
