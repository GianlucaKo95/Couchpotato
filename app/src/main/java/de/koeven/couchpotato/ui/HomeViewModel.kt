package de.koeven.couchpotato.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.koeven.couchpotato.data.AppsRepository
import de.koeven.couchpotato.data.MediaPlays
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.ProgressEntry
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.WatchNextCache
import de.koeven.couchpotato.data.WatchNextSource
import de.koeven.couchpotato.data.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen. Owns the shared [HomeContext] and the parts that work on it – the screens
 * talk to those directly (vm.profiles…, vm.live…, vm.library…). What is left here: start-up,
 * coming back to the launcher and the "Weiterschauen" row.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = HomeContext(app, viewModelScope)
    private val settings get() = ctx.settings
    private val watchNextCache = WatchNextCache(app)

    val state: StateFlow<HomeState> = ctx.state
    val tmdb get() = ctx.tmdb

    val search = SearchController(ctx)
    val titles = TitlesController(ctx)
    val catalog = CatalogController(ctx, search, titles)
    val library = LibraryController(ctx, catalog, search)
    val live = LiveTvController(ctx)
    val apps = AppsController(ctx)
    val profiles = ProfilesController(ctx, catalog, library, search)
    val household = HouseholdController(ctx, catalog, profiles)
    val discovery = Discovery(app, ctx.tmdb, apps = { ctx.state.value.apps }, catalogue = { ctx.entries }, hidden = { ctx.hidden() })

    /** Section the settings should open with (e.g. "profiles" from "Profile verwalten"). */
    var settingsSection: String? = null

    init {
        XtreamClient.init(app)
        ctx.refreshLocal = { refreshLocal() }
        search.ensureIndex = { catalog.ensureIndex(force = false) }
        live.restoreEpg()
        profiles.init()
        val hidden = ctx.hidden()
        ctx.update { it.copy(hiddenCategories = hidden) }
        ctx.cache.load()?.let { c ->
            ctx.update {
                it.copy(
                    movies = c.movies.filterNot { m -> "m:${m.categoryId ?: ""}" in hidden }.distinctBy { it.id },
                    series = c.series.filterNot { s -> "s:${s.categoryId ?: ""}" in hidden }.distinctBy { it.id },
                )
            }
        }
    }

    fun onResume() {
        viewModelScope.launch {
            try {
                refreshLocal()
                checkForUpdate()
                // the big catalogue (search, "Für dich") only after apps and "Weiterschauen" are on screen,
                // so it doesn't compete with them for the stick's CPU after a reboot
                catalog.ensureIndex(force = false)      // local; talks to the provider at most once a day
                live.refreshEpg(force = false)
                catalog.recomputeRecommendations()
                household.autoSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.update { it.copy(error = e.message ?: "unerwarteter Fehler") }
                catalog.ensureIndex(force = false)
            }
        }
    }

    /** In-app updates: at most every 6 hours; tells once per new build. */
    private fun checkForUpdate() {
        viewModelScope.launch {
            val app = ctx.app
            val r = runCatching { de.koeven.couchpotato.data.Updater.check(app) }.getOrNull() ?: return@launch
            val p = app.getSharedPreferences("updater", android.content.Context.MODE_PRIVATE)
            if (p.getInt("told", 0) == r.build) return@launch
            p.edit().putInt("told", r.build).apply()
            Notice.show("Update auf Build ${r.build} verfügbar – Einstellungen → Über Couchpotato", long = true)
        }
    }

    fun setTmdbToken(token: String) {
        settings.tmdbToken = token
        ctx.update { it.copy(tmdbOn = ctx.tmdb.enabled) }
    }

    fun setLoadMovieDetails(value: Boolean) {
        settings.loadMovieDetails = value
        ctx.update { it.copy(loadMovieDetails = value) }
    }

    // ---------------- Apps / Weiterschauen ----------------

    private suspend fun refreshLocal() {
        profiles.checkScreenTime()
        val app = ctx.app
        val hidden = settings.hiddenApps()
        val kid = Profiles.active(app).kid
        val kidApps = Profiles.kidApps(app)
        val allApps = withContext(Dispatchers.IO) { AppsRepository.load(app) }
        val apps = this.apps.sorted(
            (if (kid) allApps.filter { it.packageName in kidApps }      // kids: only released apps
            else allApps.filterNot { it.packageName in hidden }).distinctBy { it.packageName }
        )
        val labels = apps.associate { it.packageName to it.label }
        // apps on screen right away – "Weiterschauen" from the other apps follows a moment later
        if (ctx.state.value.apps.isEmpty()) ctx.update { it.copy(apps = apps, allApps = allApps.sortedBy { a -> a.label.lowercase() }) }
        val hiddenCw = settings.hiddenContinue()
        val cw = withContext(Dispatchers.IO) {
            val fresh = newEpisodeSeries()
            val iptv = ctx.progress.continueWatching().map { e ->
                val sid = e.item.seriesId
                val updated = fresh[sid]
                // new episodes: the card says so and moves to the front
                if (updated != null) ContinueItem.Iptv(enrich(e), newEpisodes = true, sort = maxOf(e.updatedAt, updated.added * 1000))
                else ContinueItem.Iptv(enrich(e))
            }
            val inProgress = iptv.mapNotNull { it.entry.item.seriesId }.toSet()
            val newOnly = fresh.filterKeys { it !in inProgress }.values.map { ContinueItem.NewEpisodes(it, 0L) }
            val media = MediaPlays.all(app)
                .filter { it.packageName != app.packageName }
                .map { ContinueItem.Media(it, labels[it.packageName]) }
            val external = watchNextCache.merge(WatchNextSource.load(app)) { pkg -> settings.lastLaunched(pkg) }
                .filter { it.packageName != app.packageName }
                .map { ContinueItem.External(it, it.packageName?.let { pkg -> labels[pkg] }) }
            // only real content – the apps have their own row ("Meine Apps")
            (if (kid) iptv + newOnly else iptv + newOnly + external + media)
                .filterNot { it.key in hiddenCw }
                .distinctBy { it.key }
                .sortedByDescending { it.sortTime }
                .take(40)
        }
        ctx.update {
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
                syncInfo = catalog.syncInfo(),
                imageInfo = catalog.imageInfo(),
                loadProviderImages = settings.loadProviderImages,
                loadMovieDetails = settings.loadMovieDetails,
                tmdbOn = ctx.tmdb.enabled,
            )
        }
        titles.prefetchHeroPlots(cw)
    }

    /**
     * Series you watched (last 6 months) whose catalogue entry changed after you last watched –
     * from the daily catalogue sync, no extra request.
     */
    private fun newEpisodeSeries(): Map<Long, SearchEntry> {
        val entries = ctx.entries ?: return emptyMap()
        val cutoff = System.currentTimeMillis() - 183L * 24 * 3600 * 1000
        val watched = ctx.history.all().filter { (k, t) -> k.startsWith("s:") && t > cutoff }
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
        val hit = ctx.entries?.firstOrNull { it.kind == SearchEntry.KIND_MOVIE && it.id == e.item.streamId } ?: return e
        return e.copy(item = e.item.copy(title = hit.name, image = hit.image))
    }

    fun removeFromContinue(item: ContinueItem) {
        when (item) {
            is ContinueItem.Iptv -> ctx.progress.remove(item.key)
            is ContinueItem.External -> settings.hideContinue(item.key)   // can't delete other apps' entries
            is ContinueItem.AppTile -> settings.hideContinue(item.key)
            is ContinueItem.NewEpisodes -> settings.hideContinue(item.key)   // shows again with the next new episode
            is ContinueItem.Media -> MediaPlays.remove(ctx.app, item.key)
        }
        ctx.refreshLocalLater()
    }

    /** Back to where the other app was playing; if it gave no link, just start the app. */
    fun openMedia(item: ContinueItem.Media) {
        settings.markLaunched(item.play.packageName)
        if (!MediaPlays.open(item.key)) AppsRepository.launch(ctx.app, item.play.packageName)
    }

    fun openExternal(item: ContinueItem.External) {
        item.program.packageName?.let { settings.markLaunched(it) }
        WatchNextSource.open(ctx.app, item.program)
    }
}
