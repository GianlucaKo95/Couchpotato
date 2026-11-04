package de.koeven.couchpotato.ui

import android.content.Context
import de.koeven.couchpotato.data.AppEntry
import de.koeven.couchpotato.data.AppsRepository
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The search screen: local catalogue search, recent searches and handing a query to other apps. */
class SearchController(private val ctx: HomeContext) {
    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results
    val query = MutableStateFlow("")

    /** Set by the view model: makes sure the catalogue is loaded. */
    internal var ensureIndex: () -> Unit = {}

    /** Called when the search screen opens: app list for hand-over + make sure the index is there. */
    fun prepare() {
        ctx.scope.launch {
            val searchable = withContext(Dispatchers.IO) { AppsRepository.searchablePackages(ctx.app) }
            val apps = ctx.state.value.apps
                .map { SearchApp(it, it.packageName in searchable) }
                .sortedByDescending { it.acceptsQuery }
            ctx.update { it.copy(searchApps = apps) }
        }
        ensureIndex()
    }

    fun recent(): List<String> = ctx.settings.recentSearches()
    fun remember(q: String) = ctx.settings.addRecentSearch(q)

    fun setQuery(q: String) {
        query.value = q
    }

    suspend fun run(q: String = query.value) {
        val entries = ctx.entries ?: return
        val hidden = ctx.hidden()
        _results.value = withContext(Dispatchers.Default) { SearchIndex.search(entries, q, hidden) }
    }

    /** Profile switch: start empty. */
    fun reset() {
        query.value = ""
        _results.value = SearchResults()
    }

    fun inApp(context: Context, app: SearchApp, query: String) {
        ctx.settings.markLaunched(app.app.packageName)
        val handedOver = app.acceptsQuery && AppsRepository.searchInApp(context, app.app.packageName, query)
        if (!handedOver) {
            if (!app.acceptsQuery) AppsRepository.launch(context, app.app.packageName)
            Notice.show("${app.app.label} nimmt keinen Suchbegriff an – bitte dort suchen")
        }
    }

    /** Opens the streaming app and searches the title there (or just opens it). */
    fun openInStreamingApp(context: Context, appEntry: AppEntry, title: String) {
        val sa = ctx.state.value.searchApps.firstOrNull { it.app.packageName == appEntry.packageName }
            ?: SearchApp(appEntry, AppsRepository.searchablePackages(context).contains(appEntry.packageName))
        inApp(context, sa, title)
    }

    fun openGlobal(context: Context, query: String) {
        if (!AppsRepository.openGlobalSearch(context, query)) {
            Notice.show("Fire-TV-Suche lässt sich hier nicht öffnen")
        }
    }
}
