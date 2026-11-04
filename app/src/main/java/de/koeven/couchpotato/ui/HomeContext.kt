package de.koeven.couchpotato.ui

import android.app.Application
import de.koeven.couchpotato.data.CatalogCache
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.ProgressStore
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.Tmdb
import de.koeven.couchpotato.data.WatchHistory
import de.koeven.couchpotato.data.Watchlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the parts of the home screen logic share: stores, the screen state and the local
 * catalogue. Owned by [HomeViewModel]; the controllers (profiles, live TV, Mediathek …) work on it.
 */
class HomeContext(val app: Application, val scope: CoroutineScope) {
    val settings = SettingsStore(app)
    val tmdb = Tmdb(app)
    val searchIndex = SearchIndex(app)

    /** Per profile – swapped on a profile switch or after a household sync. */
    var progress = ProgressStore(app); private set
    var history = WatchHistory(app); private set
    var cache = CatalogCache(app); private set
    var watchlist = Watchlist(app); private set

    val state = MutableStateFlow(HomeState(configured = settings.config().isValid))
    fun update(f: (HomeState) -> HomeState) = state.update(f)

    /** The local catalogue (null = not loaded yet) and the category names per kind. */
    var entries: List<SearchEntry>? = null
    var categoriesByKind: Map<Char, List<LiveCategory>> = emptyMap()

    /** Set by the view model: rebuilds apps and "Weiterschauen". */
    lateinit var refreshLocal: suspend () -> Unit
    fun refreshLocalLater() { scope.launch { refreshLocal() } }

    fun reloadStores() {
        progress = ProgressStore(app)
        history = WatchHistory(app)
        cache = CatalogCache(app)
        watchlist = Watchlist(app)
        categoryKeysOf = null
    }

    private var categoryKeysOf: List<SearchEntry>? = null
    private var categoryKeys: Set<String> = emptySet()

    /**
     * Hidden categories for the active profile: the globally hidden ones, and for kids
     * additionally everything that is not released for kids.
     */
    fun hidden(): Set<String> {
        val hidden = settings.hiddenCategories()
        if (!Profiles.active(app).kid) return hidden
        val entries = entries
        if (entries !== categoryKeysOf) {
            val all = HashSet<String>()
            categoriesByKind.forEach { (k, list) -> list.forEach { c -> all += "$k:${c.id}" } }
            entries?.forEach { all += it.categoryKey }
            categoryKeys = all
            categoryKeysOf = entries
        }
        return hidden + (categoryKeys - Profiles.kidCategories(app))
    }
}
