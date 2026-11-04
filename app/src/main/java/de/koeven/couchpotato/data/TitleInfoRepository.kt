package de.koeven.couchpotato.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Details from the IPTV provider that the catalogue doesn't carry: a series' episodes and a film's
 * info. Both are kept on the stick and only asked for again when something changed – every request
 * goes through the provider brake in [XtreamClient].
 */
class TitleInfoRepository(
    context: Context,
    private val settings: SettingsStore,
    private val catalogue: () -> List<SearchEntry>?,
) {
    private val seriesDir = File(context.filesDir, "seriesinfo")
    private val movieDir = File(context.filesDir, "vodinfo")
    private val seriesCache = HashMap<Long, SeriesInfo>()

    /** Series loaded in this session (no disk, no request). */
    fun cachedSeries(id: Long): SeriesInfo? = seriesCache[id]

    /** Another provider: other ids – forget everything. */
    fun clear() {
        seriesCache.clear()
        seriesDir.deleteRecursively()
        movieDir.deleteRecursively()
    }

    suspend fun loadSeries(seriesId: Long, fallbackName: String): SeriesInfo =
        seriesCache[seriesId] ?: loadSeriesCached(seriesId, fallbackName).also { seriesCache[seriesId] = it }

    /**
     * Series info from the stick if still current (new episodes!), otherwise ONE request.
     * If the provider can't be asked right now, the older copy is used.
     */
    private suspend fun loadSeriesCached(seriesId: Long, fallbackName: String): SeriesInfo = withContext(Dispatchers.IO) {
        val client = XtreamClient(settings.config())
        val f = File(seriesDir, "$seriesId.json")
        val stale = if (f.exists()) runCatching { client.parseSeriesInfo(f.readText(), seriesId, fallbackName) }.getOrNull() else null
        if (stale != null) {
            // The catalogue says when a series last changed (new episodes). As long as our copy is newer
            // than that, it is up to date – no request at all. Without that date: refresh after 3 days.
            val changedAt = (catalogue()?.firstOrNull { it.kind == SearchEntry.KIND_SERIES && it.id == seriesId }?.added ?: 0L) * 1000
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

    /**
     * Film details: from the stick if fetched before; otherwise ONE request (through the brake),
     * then kept for good. Returns null if switched off or the provider can't be asked right now.
     */
    suspend fun movieInfo(id: Long): Result<MovieInfo>? = withContext(Dispatchers.IO) {
        val f = File(movieDir, "$id.json")
        if (f.exists()) {
            runCatching { MovieInfo.fromInfo(JSONObject(f.readText()).getJSONObject("info")) }
                .getOrNull()?.let { return@withContext Result.success(it) }
        }
        if (!settings.loadMovieDetails) return@withContext null
        runCatching {
            val text = XtreamClient(settings.config()).vodInfoRaw(id)
            movieDir.mkdirs()
            f.writeText(text)
            MovieInfo.fromInfo(JSONObject(text).getJSONObject("info"))
        }
    }

    private companion object {
        const val SERIES_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L  // series info without a change date in the catalogue
        const val SERIES_SAFETY_MS = 14 * 24 * 60 * 60 * 1000L  // …and with one: still re-check every 2 weeks
    }
}
