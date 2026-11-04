package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.Recommender
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.data.XtreamClient
import de.koeven.couchpotato.data.XtreamConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The IPTV side: login, the local catalogue and its daily sync with the provider,
 * and the rows computed from it ("Neue Filme/Serien", "Für dich").
 */
class CatalogController(
    private val ctx: HomeContext,
    private val search: SearchController,
    private val titles: TitlesController,
) {
    private val settings get() = ctx.settings
    private val searchIndex get() = ctx.searchIndex
    private var indexJob: Job? = null

    fun config(): XtreamConfig = settings.config()

    var loginAsLink: Boolean
        get() = settings.loginAsLink
        set(value) { settings.loginAsLink = value }

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
        ctx.update { it.copy(configured = after.isValid) }
        if (before == after) return false
        XtreamClient.clearBlock()
        titles.clearProviderData()     // other provider: other ids
        ctx.history.clear()
        searchIndex.clear()
        ctx.entries = null
        ctx.categoriesByKind = emptyMap()
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

    fun syncInfo(): String {
        val last = searchIndex.lastSync()
        val lastText = if (last == 0L) "noch nie" else
            java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date(last)) + " Uhr"
        return "Letzter Abgleich: $lastText · Anfragen in der letzten Stunde: ${XtreamClient.requestsLastHour()}" +
            " · Bilder vom Anbieter: ${XtreamClient.imagesLastHour()}"
    }

    /** Time of the last successful catalogue sync (0 = never). */
    fun lastSyncAt(): Long = searchIndex.lastSync()

    fun imageInfo(): String =
        searchIndex.imageStats() ?: "Bildquellen werden beim nächsten Abgleich ausgewertet."

    fun setLoadProviderImages(value: Boolean) {
        settings.loadProviderImages = value
        XtreamClient.loadProviderImages = value
        ctx.update { it.copy(loadProviderImages = value) }
    }

    /** "Neue Filme/Serien" come from the local catalogue; this only syncs if it is older than a day. */
    fun refresh(force: Boolean) = ensureIndex(force)

    /** The local catalogue; loads it from the stick (or does the first sync) if needed. */
    suspend fun entries(): List<SearchEntry> {
        if (ctx.entries == null) ensureIndex(force = false)
        indexJob?.join()
        return ctx.entries ?: throw java.io.IOException(
            "Noch kein Katalog auf dem Stick – Einstellungen → „Katalog jetzt abgleichen“."
        )
    }

    /**
     * Loads the catalogue from the stick and syncs it with the provider only when it is older
     * than a day (or when asked to). One sync = 6 requests, nothing else talks to the provider
     * except opening a series, "Jetzt läuft" and playback itself.
     */
    fun ensureIndex(force: Boolean) {
        if (indexJob?.isActive == true) return
        indexJob = ctx.scope.launch { syncCatalog(force) }
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
        indexJob = ctx.scope.launch { syncCatalog(force = true) }
        return "Abgleich gestartet (6 Anfragen, dauert etwas) …"
    }

    private suspend fun syncCatalog(force: Boolean) {
        try {
            loadLocalCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.update { it.copy(loading = false, error = e.message ?: "unerwarteter Fehler") }
            setIndexStatus("Lokale Daten konnten nicht geladen werden: ${e.message ?: "unerwarteter Fehler"}")
            return
        } catch (e: OutOfMemoryError) {
            // a huge local catalogue must not take the launcher down at start: run without it
            ctx.entries = null
            System.gc()
            ctx.update { it.copy(loading = false, error = "Zu wenig Speicher für den Katalog") }
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
        ctx.update { it.copy(loading = true, error = null) }
        setIndexStatus(
            if (ctx.entries.isNullOrEmpty()) "Katalog wird geladen … (einmal am Tag, 6 Anfragen)"
            else "Katalog wird im Hintergrund abgeglichen …"
        )
        try {
            // automatic daily sync = background budget; asked for by the user = normal budget
            val failed = searchIndex.rebuild(XtreamClient(cfg, background = !force))
            if (searchIndex.lastSync() >= now) settings.syncFailures = 0 else settings.syncFailures += 1
            ctx.entries = null
            loadLocalCatalog()
            setIndexStatus(indexSummary(failed))
            ctx.update {
                it.copy(loading = false, error = if (failed.isEmpty()) null else "Nicht geladen: ${failed.joinToString()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settings.syncFailures += 1
            ctx.update { it.copy(loading = false, error = e.message ?: "unerwarteter Fehler") }
            setIndexStatus("Katalog konnte nicht abgeglichen werden: ${e.message ?: "unerwarteter Fehler"}")
        } catch (e: OutOfMemoryError) {
            // a very large catalogue must not take the whole launcher down
            ctx.entries = null
            System.gc()
            settings.syncFailures += 1
            ctx.update { it.copy(loading = false, error = "Zu wenig Speicher für den Katalog") }
            setIndexStatus("Katalog zu groß für den Speicher – bitte Kategorien ausblenden.")
        } finally {
            ctx.update { it.copy(syncInfo = syncInfo(), imageInfo = imageInfo()) }
        }
    }

    private suspend fun loadLocalCatalog() {
        if (ctx.entries == null && searchIndex.exists()) {
            setIndexStatus("Lade Katalog …")
            ctx.entries = searchIndex.load()
            ctx.categoriesByKind = withContext(Dispatchers.IO) { searchIndex.loadCategories() }
            setIndexStatus(indexSummary(emptyList()))
        } else if (ctx.entries != null) {
            return      // already in memory: nothing changed, don't re-sort tens of thousands of titles on every return
        }
        recomputeRows()
        search.run()
        ctx.refreshLocal()      // names and covers for titles started in IsiPro
    }

    /** "Neue Filme/Serien": the newest titles of the local catalogue, hidden categories left out. */
    suspend fun recomputeRows() {
        val entries = ctx.entries ?: return
        val hidden = ctx.hidden()
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
            withContext(Dispatchers.IO) { ctx.cache.save(movies, series) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The cache is optional; keep the in-memory rows available for this session.
        }
        ctx.update { it.copy(movies = movies, series = series) }
        recomputeRecommendations()
    }

    /** "Für dich" – local, recomputed after every playback (the history changes). */
    suspend fun recomputeRecommendations() {
        val entries = ctx.entries ?: return
        val hidden = ctx.hidden()
        val r = withContext(Dispatchers.Default) { Recommender.recommend(entries, hidden, ctx.history.all()) }
        ctx.update { it.copy(recommended = r.items, recommendedPersonal = r.personal) }
    }

    private fun indexSummary(failed: List<String>): String {
        val entries = ctx.entries.orEmpty()
        val m = entries.count { it.kind == SearchEntry.KIND_MOVIE }
        val s = entries.count { it.kind == SearchEntry.KIND_SERIES }
        val l = entries.count { it.kind == SearchEntry.KIND_LIVE }
        val base = "Durchsucht $m Filme, $s Serien und $l Sender"
        return if (failed.isEmpty()) base else "$base · nicht geladen: ${failed.joinToString()}"
    }

    private fun setIndexStatus(text: String) = ctx.update { it.copy(indexStatus = text) }

    internal companion object {
        const val ROW_SIZE = 40
        // kept low on purpose: the provider locks the account after too many requests
        const val SYNC_MAX_AGE_MS = 24 * 60 * 60 * 1000L       // whole catalogue: once a day
        const val MIN_MANUAL_SYNC_GAP_MS = 10 * 60 * 1000L     // "jetzt abgleichen" not more often

        /** Failed syncs: retry after 1 h, 2 h, 4 h, 8 h, then every 12 h. */
        fun backoffMs(failures: Int): Long =
            (60 * 60 * 1000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(12 * 60 * 60 * 1000L)
    }
}
