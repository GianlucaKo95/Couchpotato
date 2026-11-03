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
import de.koeven.couchpotato.data.CloudSync
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.MovieInfo
import de.koeven.couchpotato.data.Profile
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.TitleDetails
import de.koeven.couchpotato.data.Themes
import de.koeven.couchpotato.data.Tmdb
import de.koeven.couchpotato.data.Recommender
import de.koeven.couchpotato.data.WatchHistory
import de.koeven.couchpotato.data.WatchedStore
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    data class Iptv(val entry: ProgressEntry, val newEpisodes: Boolean = false, val sort: Long = 0L) : ContinueItem {
        override val key: String get() = entry.item.key
        override val sortTime: Long get() = maxOf(entry.updatedAt, sort)
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

    /** A series you watched got new episodes since (provider's "last modified" is newer). */
    data class NewEpisodes(val series: SearchEntry, val watchedAt: Long) : ContinueItem {
        override val key: String get() = "new:${series.id}:${series.added}"
        override val sortTime: Long get() = series.added * 1000
    }

    /** Streaming app whose progress we can't read (Netflix …): a tile that opens the app. */
    data class AppTile(val app: AppEntry, val lastUsed: Long) : ContinueItem {
        override val key: String get() = "app:${app.packageName}"
        override val sortTime: Long get() = lastUsed
    }
}

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

/** Streaming apps shown as a tile in "Weiterschauen" (if installed). */
private val STREAMING_APPS = listOf("com.netflix.ninja", "com.disney.disneyplus")

data class HomeState(
    val screenLock: String? = null,      // kids: screen time used up / bedtime
    val screenLockNight: Boolean = false, // "Gute Nacht" version (bedtime, or limit used up from 19:00)
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
    private val cloudSync = CloudSync(app)
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
                // "Wer schaut?" as start screen after every start of the stick
                needsProfilePick = Profiles.firstStartSinceBoot(app) && profiles.size > 1,
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
                    movies = c.movies.filterNot { m -> "m:${m.categoryId ?: ""}" in hidden }.distinctBy { it.id },
                    series = c.series.filterNot { s -> "s:${s.categoryId ?: ""}" in hidden }.distinctBy { it.id },
                )
            }
        }
    }

    fun config(): XtreamConfig = settings.config()

    fun cloudSyncStatus(): String = cloudSync.status()

    val cloudLinked: Boolean get() = cloudSync.linked
    val cloudIsMain: Boolean get() = cloudSync.isMain
    fun householdCode(): String? = cloudSync.householdCode()
    var shareIptvWithHousehold: Boolean
        get() = cloudSync.shareIptv
        set(value) { cloudSync.shareIptv = value }

    suspend fun createHousehold(): String = cloudSync.createHousehold()

    suspend fun joinHousehold(code: String): String {
        val result = cloudSync.join(code)
        reloadAfterCloudSync()
        return result.message + takeSharedLogin(result.iptv, always = true)
    }

    suspend fun syncCloudNow(): String {
        val result = cloudSync.sync()
        settings.cloudSyncAttemptAt = System.currentTimeMillis()
        reloadAfterCloudSync()
        return result.message + takeSharedLogin(result.iptv, always = false)
    }

    suspend fun renewHouseholdCode(): String = cloudSync.renewCode()

    suspend fun leaveHousehold(): String = cloudSync.leave()

    /** The main TV shares its IPTV login (only if switched on there): use it here when this TV has none. */
    private suspend fun takeSharedLogin(iptv: org.json.JSONObject?, always: Boolean): String {
        val cfg = cloudSync.iptvConfig(iptv) ?: return ""
        if (!always && settings.config().isValid) return ""
        if (cfg == settings.config()) return ""
        applyLogin(cfg)
        ensureIndex(force = false)
        return " IPTV-Zugang vom Hauptgerät übernommen. Hinweis: Viele Anbieter erlauben nur 1–2 Geräte gleichzeitig."
    }

    private suspend fun reloadAfterCloudSync() {
        progress = ProgressStore(app)
        history = WatchHistory(app)
        cache = CatalogCache(app)
        refreshProfiles()
        _state.update { it.copy(hiddenCategories = effectiveHidden()) }
        refreshLocal()
        recomputeRows()
        recomputeRecommendations()
    }

    var loginAsLink: Boolean
        get() = settings.loginAsLink
        set(value) { settings.loginAsLink = value }

    fun onResume() {
        viewModelScope.launch {
            try {
                refreshLocal()
                checkForUpdate()
                // the big catalogue (search, "Für dich") only after apps and "Weiterschauen" are on screen,
                // so it doesn't compete with them for the stick's CPU after a reboot
                ensureIndex(force = false)      // local; talks to the provider at most once a day
                refreshEpg(force = false)
                recomputeRecommendations()
                if (cloudSync.linked && System.currentTimeMillis() - settings.cloudSyncAttemptAt >= CLOUD_SYNC_MIN_GAP_MS) {
                    settings.cloudSyncAttemptAt = System.currentTimeMillis()
                    // automatic sync in the background: if the cloud can't be reached, just try again later –
                    // it must not show up as an IPTV error on the home screen
                    val synced = runCatching { cloudSync.sync() }.getOrNull()
                    if (synced != null) {
                        reloadAfterCloudSync()
                        runCatching { takeSharedLogin(synced.iptv, always = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "unerwarteter Fehler") }
                ensureIndex(force = false)
            }
        }
    }

    /**
     * Series you watched (last 6 months) whose catalogue entry changed after you last watched –
     * from the daily catalogue sync, no extra request.
     */
    private fun newEpisodeSeries(): Map<Long, SearchEntry> {
        val entries = indexEntries ?: return emptyMap()
        val seen = history.all()
        val cutoff = System.currentTimeMillis() - 183L * 24 * 3600 * 1000
        val watched = seen.filter { (k, t) -> k.startsWith("s:") && t > cutoff }
            .mapNotNull { (k, t) -> k.removePrefix("s:").toLongOrNull()?.let { it to t } }.toMap()
        if (watched.isEmpty()) return emptyMap()
        val out = HashMap<Long, SearchEntry>()
        for (e in entries) {
            if (e.kind != SearchEntry.KIND_SERIES) continue
            val at = watched[e.id] ?: continue
            if (e.added * 1000 > at + 60_000) out[e.id] = e
        }
        return out
    }

    /** Titles started in IsiPro carry no cover: take name and cover from the local catalogue. */
    private fun enrich(e: ProgressEntry): ProgressEntry {
        if (e.item.image != null || e.item.kind != PlayItem.KIND_MOVIE) return e
        val hit = indexEntries?.firstOrNull { it.kind == SearchEntry.KIND_MOVIE && it.id == e.item.streamId } ?: return e
        return e.copy(item = e.item.copy(title = hit.name, image = hit.image))
    }

    private suspend fun refreshLocal() {
        checkScreenTime()
        val hidden = settings.hiddenApps()
        val kid = Profiles.active(app).kid
        val kidApps = Profiles.kidApps(app)
        val allApps = withContext(Dispatchers.IO) { AppsRepository.load(getApplication()) }
        val apps = sortApps(
            (if (kid) allApps.filter { it.packageName in kidApps }      // kids: only released apps
            else allApps.filterNot { it.packageName in hidden }).distinctBy { it.packageName }
        )
        val labels = apps.associate { it.packageName to it.label }
        // apps on screen right away – "Weiterschauen" from the other apps follows a moment later
        if (_state.value.apps.isEmpty()) _state.update { it.copy(apps = apps, allApps = allApps.sortedBy { a -> a.label.lowercase() }) }
        val hiddenCw = settings.hiddenContinue()
        val cw = withContext(Dispatchers.IO) {
            val fresh = newEpisodeSeries()
            val iptv = progress.continueWatching().map { e ->
                val sid = e.item.seriesId
                val updated = fresh[sid]
                // new episodes: the card says so and moves to the front
                if (updated != null) ContinueItem.Iptv(enrich(e), newEpisodes = true, sort = maxOf(e.updatedAt, updated.added * 1000))
                else ContinueItem.Iptv(enrich(e))
            }
            val inProgress = iptv.mapNotNull { it.entry.item.seriesId }.toSet()
            val newOnly = fresh.filterKeys { it !in inProgress }.values.map { ContinueItem.NewEpisodes(it, 0L) }
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
            (if (kid) iptv + newOnly else iptv + newOnly + external + media + tiles)
                .filterNot { it.key in hiddenCw }
                .distinctBy { it.key }
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
        // TMDB details are filed under the provider's ids (m_123 / s_45): those belong to the old provider
        java.io.File(app.filesDir, "tmdb").listFiles { f -> f.name.startsWith("m_") || f.name.startsWith("s_") }?.forEach { it.delete() }
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
            "Fehler: ${e.message ?: "unerwarteter Fehler"}"
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
        val list = categoriesByKind[kind]?.takeIf { it.isNotEmpty() }?.distinctBy { c -> c.id }
            // category names missing (that request failed): use the ids from the titles
            ?: entries.asSequence().filter { it.kind == kind }.mapNotNull { it.categoryId }.distinct()
                .map { LiveCategory(it, "Kategorie $it") }.toList()
        return sortedByOwnOrder(kind, list)
    }

    /** Own order first; categories the provider added later keep their place among the others. */
    private fun sortedByOwnOrder(kind: Char, list: List<LiveCategory>): List<LiveCategory> {
        val order = settings.categoryOrder(kind)
        if (order.isEmpty()) return list
        val pos = order.withIndex().associate { (i, id) -> id to i }
        val known = list.filter { it.id in pos }.sortedBy { pos[it.id] }
        // new categories: right after the category that comes before them at the provider
        val out = known.toMutableList()
        list.forEachIndexed { i, c ->
            if (c.id in pos) return@forEachIndexed
            val before = list.subList(0, i).lastOrNull { it.id in pos || it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, c)
        }
        return out
    }

    fun hasOwnCategoryOrder(kind: Char) = settings.categoryOrder(kind).isNotEmpty()

    fun setCategoryOrder(kind: Char, ids: List<String>?) {
        settings.setCategoryOrder(kind, ids)
        if (kind == SearchEntry.KIND_LIVE) viewModelScope.launch { refreshLocal() }
    }

    /** Number of titles per category (for the Mediathek list) – local, no request. */
    suspend fun categoryCounts(kind: Char): Map<String, Int> = withContext(Dispatchers.Default) {
        catalog().asSequence().filter { it.kind == kind }.mapNotNull { it.categoryId }
            .groupingBy { it }.eachCount()
    }

    var browseSort: Int
        get() = settings.browseSort
        set(value) { settings.browseSort = value }

    /** Newest first / A–Z (German collation: Ä next to A) / best rating (unrated last). */
    private fun List<SearchEntry>.sortedForBrowse(): List<SearchEntry> = when (settings.browseSort) {
        1 -> {
            val collator = java.text.Collator.getInstance(java.util.Locale.GERMAN).apply { strength = java.text.Collator.PRIMARY }
            sortedWith { a, b -> collator.compare(a.name, b.name) }
        }
        2 -> sortedWith(compareByDescending<SearchEntry> { it.rating }.thenByDescending { it.added })
        else -> sortedByDescending { it.added }
    }

    suspend fun moviesIn(categoryId: String): List<VodItem> = withContext(Dispatchers.Default) {
        catalog().filter { it.kind == SearchEntry.KIND_MOVIE && it.categoryId == categoryId }
            .sortedForBrowse()
            .map { VodItem(it.id, it.name, it.image, it.added, it.ext, it.categoryId) }
            .distinctBy { it.id }
    }

    suspend fun seriesIn(categoryId: String): List<SeriesItem> = withContext(Dispatchers.Default) {
        catalog().filter { it.kind == SearchEntry.KIND_SERIES && it.categoryId == categoryId }
            .sortedForBrowse()
            .map { SeriesItem(it.id, it.name, it.image, it.added, it.categoryId) }
            .distinctBy { it.id }
    }

    suspend fun liveChannels(categoryId: String): List<LiveChannel> = withContext(Dispatchers.Default) {
        catalog().filter { it.kind == SearchEntry.KIND_LIVE && it.categoryId == categoryId }
            .map { LiveChannel(it.id, it.name, it.image, it.categoryId) }
            .distinctBy { it.id }
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
            Notice.show("IsiPro nicht gefunden – spiele in Couchpotato")
            return false
        }
        val url = XtreamClient.streamUrl(settings.config(), PlayItem.live(channel))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
        return runCatching { context.startActivity(intent) }.isSuccess.also { ok ->
            if (ok) settings.markLaunched(pkg)
            else Notice.show("IsiPro nimmt keine Sender-Links an – spiele in Couchpotato", long = true)
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
        try {
            loadLocalCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "unerwarteter Fehler") }
            setIndexStatus("Lokale Daten konnten nicht geladen werden: ${e.message ?: "unerwarteter Fehler"}")
            return
        } catch (e: OutOfMemoryError) {
            // a huge local catalogue must not take the launcher down at start: run without it
            indexEntries = null
            System.gc()
            _state.update { it.copy(loading = false, error = "Zu wenig Speicher für den Katalog") }
            setIndexStatus("Katalog zu groß für den Speicher – bitte Kategorien ausblenden.")
            return
        }
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
            _state.update { it.copy(loading = false, error = e.message ?: "unerwarteter Fehler") }
            setIndexStatus("Katalog konnte nicht abgeglichen werden: ${e.message ?: "unerwarteter Fehler"}")
        } catch (e: OutOfMemoryError) {
            // a very large catalogue must not take the whole launcher down
            indexEntries = null
            System.gc()
            settings.syncFailures += 1
            _state.update { it.copy(loading = false, error = "Zu wenig Speicher für den Katalog") }
            setIndexStatus("Katalog zu groß für den Speicher – bitte Kategorien ausblenden.")
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
        } else if (indexEntries != null) {
            return      // already in memory: nothing changed, don't re-sort tens of thousands of titles on every return
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
                .distinctBy { it.id }   // providers often list a title in two categories (e.g. "Neu" + genre)
                .take(ROW_SIZE)
                .map { VodItem(it.id, it.name, it.image, it.added, it.ext, it.categoryId) }
                .toList()
            val sr = entries.asSequence()
                .filter { it.kind == SearchEntry.KIND_SERIES && it.categoryKey !in hidden }
                .sortedByDescending { it.added }
                .distinctBy { it.id }   // providers often list a title in two categories (e.g. "Neu" + genre)
                .take(ROW_SIZE)
                .map { SeriesItem(it.id, it.name, it.image, it.added, it.categoryId) }
                .toList()
            m to sr
        }
        // The disk cache is an optimization; full storage or a transient I/O error must not
        // take down the catalog load or the app's startup coroutine.
        try {
            withContext(Dispatchers.IO) { cache.save(movies, series) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The cache is optional; keep the in-memory rows available for this session.
        }
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

    /** In-app updates: at most every 6 hours; tells once per new build. */
    private fun checkForUpdate() {
        viewModelScope.launch {
            val r = runCatching { de.koeven.couchpotato.data.Updater.check(app) }.getOrNull() ?: return@launch
            val p = app.getSharedPreferences("updater", android.content.Context.MODE_PRIVATE)
            if (p.getInt("told", 0) == r.build) return@launch
            p.edit().putInt("told", r.build).apply()
            Notice.show("Update auf Build ${r.build} verfügbar – Einstellungen → Über Couchpotato", long = true)
        }
    }

    /** Kids profile: daily screen time and bedtime. */
    fun checkScreenTime() {
        val msg = when (val st = de.koeven.couchpotato.data.ScreenTime.state(app)) {
            is de.koeven.couchpotato.data.ScreenTime.State.LimitReached ->
                if (st.evening) "Jetzt ist Schlafenszeit. Gute Nacht und schlaf schön!"
                else "Die Bildschirmzeit für heute ist aufgebraucht. Morgen geht's weiter!"
            is de.koeven.couchpotato.data.ScreenTime.State.Bedtime -> "Ab ${st.at} Uhr ist Schlafenszeit. Gute Nacht!"
            is de.koeven.couchpotato.data.ScreenTime.State.Allowed -> null
        }
        val st = de.koeven.couchpotato.data.ScreenTime.state(app)
        val night = st is de.koeven.couchpotato.data.ScreenTime.State.Bedtime ||
            (st is de.koeven.couchpotato.data.ScreenTime.State.LimitReached && st.evening)
        if (_state.value.screenLock != msg) _state.update { it.copy(screenLock = msg, screenLockNight = night) }
    }

    fun extendScreenTime(minutes: Int) {
        de.koeven.couchpotato.data.ScreenTime.addBonus(app, Profiles.active(app), minutes)
        checkScreenTime()
    }

    /** "45 Min. heute übrig" for the settings. */
    fun screenTimeInfo(p: Profile): String {
        val used = de.koeven.couchpotato.data.ScreenTime.usedMs(app, p) / 60_000
        return "heute $used Min. geschaut"
    }
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

    /** Titles of a kind for the kids' Mediathek: one category or all visible ones, newest first – local. */
    suspend fun visibleTitles(kind: Char, categoryId: String?, limit: Int = 300): List<SearchEntry> {
        val entries = indexEntries ?: return emptyList()
        val hidden = effectiveHidden()
        return withContext(Dispatchers.Default) {
            entries.asSequence()
                .filter { it.kind == kind && it.categoryKey !in hidden && (categoryId == null || it.categoryId == categoryId) }
                .sortedByDescending { it.added }
                .distinctBy { it.id }
                .take(limit)
                .toList()
        }
    }

    /** Visible categories of a kind for the active profile (kids: only released ones). */
    suspend fun visibleCategories(kind: Char): List<LiveCategory> {
        val hidden = effectiveHidden()
        return allCategories(kind).filterNot { "$kind:${it.id}" in hidden }
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

    // ---------------- Suche über Streamingdienste ----------------

    /** A TMDB hit together with the installed apps that stream it. */
    data class StreamResult(val hit: Tmdb.StreamHit, val apps: List<AppEntry>)

    /** Installed app for a streaming service name from TMDB/JustWatch ("Disney Plus" → Disney+ app). */
    fun appFor(provider: String): AppEntry? {
        val p = SearchIndex.normalize(provider.replace("+", " plus"))
        val keys = PROVIDER_KEYS.entries.firstOrNull { (k, _) -> p.startsWith(k) }?.value
            ?: listOf(p.substringBefore(' '))
        return _state.value.apps.firstOrNull { a ->
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

    // ---------------- Themensuche ----------------

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
        val entries = indexEntries.orEmpty()
        val hidden = effectiveHidden()
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

    /** Opens the streaming app and searches the title there (or just opens it). */
    fun openInStreamingApp(context: Context, appEntry: AppEntry, title: String) {
        val sa = _state.value.searchApps.firstOrNull { it.app.packageName == appEntry.packageName }
            ?: SearchApp(appEntry, AppsRepository.searchablePackages(context).contains(appEntry.packageName))
        searchInApp(context, sa, title)
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
            Notice.show("${app.app.label} nimmt keinen Suchbegriff an – bitte dort suchen")
        }
    }

    fun openGlobalSearch(context: Context, query: String) {
        if (!AppsRepository.openGlobalSearch(context, query)) {
            Notice.show("Fire-TV-Suche lässt sich hier nicht öffnen")
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
            is ContinueItem.NewEpisodes -> settings.hideContinue(item.key)   // shows again with the next new episode
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
            if (next != null) progress.finished(last, PlayItem.episode(info, next))
            else {
                progress.forSeries(info.id)?.let { progress.remove(it.item.key) }
                history.add(last)
            }
        }
        _state.update { it.copy(tick = it.tick + 1) }
        viewModelScope.launch { refreshLocal() }
    }

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
        if (stale != null) {
            // The catalogue says when a series last changed (new episodes). As long as our copy is newer
            // than that, it is up to date – no request at all. Without that date: refresh after 3 days.
            val changedAt = (indexEntries?.firstOrNull { it.kind == SearchEntry.KIND_SERIES && it.id == seriesId }?.added ?: 0L) * 1000
            val age = System.currentTimeMillis() - f.lastModified()
            val upToDate = if (changedAt > 0) f.lastModified() >= changedAt && age < SERIES_SAFETY_MS
            else age < SERIES_MAX_AGE_MS
            if (upToDate) return@withContext stale
        }
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
        const val SERIES_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L  // series info without a change date in the catalogue
        const val SERIES_SAFETY_MS = 14 * 24 * 60 * 60 * 1000L  // …and with one: still re-check every 2 weeks
        // kept low on purpose: the provider locks the account after too many requests
        const val SYNC_MAX_AGE_MS = 24 * 60 * 60 * 1000L       // whole catalogue: once a day
        const val MIN_MANUAL_SYNC_GAP_MS = 10 * 60 * 1000L     // "jetzt abgleichen" not more often
        const val CLOUD_SYNC_MIN_GAP_MS = 60 * 1000L
        const val EPG_MAX_AGE_MS = 30 * 60 * 1000L             // "Jetzt läuft": every 30 minutes …
        const val EPG_MAX_CHANNELS = 8                         // … for the first 8 favourites
        const val EPG_KEEP_MS = 2 * 60 * 60 * 1000L            // show stored titles after a reboot

        /** Failed syncs: retry after 1 h, 2 h, 4 h, 8 h, then every 12 h. */
        fun backoffMs(failures: Int): Long =
            (60 * 60 * 1000L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(12 * 60 * 60 * 1000L)
    }
}
