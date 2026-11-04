package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.data.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Mediathek: categories (own order, hidden ones) and the titles in them – all local, no request. */
class LibraryController(
    private val ctx: HomeContext,
    private val catalog: CatalogController,
    private val search: SearchController,
) {
    private val settings get() = ctx.settings

    /** Remembered so the Mediathek opens where you left it (e.g. after watching something). */
    var browseKind: Char = SearchEntry.KIND_LIVE
    var browseCategoryId: String? = null

    var browseSort: Int
        get() = settings.browseSort
        set(value) { settings.browseSort = value }

    suspend fun allCategories(kind: Char): List<LiveCategory> {
        val entries = catalog.entries()
        val list = ctx.categoriesByKind[kind]?.takeIf { it.isNotEmpty() }?.distinctBy { c -> c.id }
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
        if (kind == SearchEntry.KIND_LIVE) ctx.refreshLocalLater()
    }

    /** Number of titles per category (for the Mediathek list). */
    suspend fun categoryCounts(kind: Char): Map<String, Int> = withContext(Dispatchers.Default) {
        catalog.entries().asSequence().filter { it.kind == kind }.mapNotNull { it.categoryId }
            .groupingBy { it }.eachCount()
    }

    /** Newest first / A–Z (German collation: Ä next to A) / best rating (unrated last). */
    private fun List<SearchEntry>.sortedForBrowse(): List<SearchEntry> = when (settings.browseSort) {
        1 -> {
            val collator = java.text.Collator.getInstance(java.util.Locale.GERMAN).apply { strength = java.text.Collator.PRIMARY }
            sortedWith { a, b -> collator.compare(a.name, b.name) }
        }
        // TMDB where it's already on the stick (titles opened before), else the provider's rating
        2 -> sortedWith(compareByDescending<SearchEntry> { ctx.tmdb.cachedRating(it.kind, it.id) ?: it.rating }.thenByDescending { it.added })
        else -> sortedByDescending { it.added }
    }

    suspend fun moviesIn(categoryId: String): List<VodItem> = withContext(Dispatchers.Default) {
        catalog.entries().filter { it.kind == SearchEntry.KIND_MOVIE && it.categoryId == categoryId }
            .sortedForBrowse()
            .map { VodItem(it.id, it.name, it.image, it.added, it.ext, it.categoryId) }
            .distinctBy { it.id }
    }

    suspend fun seriesIn(categoryId: String): List<SeriesItem> = withContext(Dispatchers.Default) {
        catalog.entries().filter { it.kind == SearchEntry.KIND_SERIES && it.categoryId == categoryId }
            .sortedForBrowse()
            .map { SeriesItem(it.id, it.name, it.image, it.added, it.categoryId) }
            .distinctBy { it.id }
    }

    suspend fun liveChannels(categoryId: String): List<LiveChannel> = withContext(Dispatchers.Default) {
        catalog.entries().filter { it.kind == SearchEntry.KIND_LIVE && it.categoryId == categoryId }
            .map { LiveChannel(it.id, it.name, it.image, it.categoryId) }
            .distinctBy { it.id }
    }

    /** Live categories without the hidden ones (used by "Sender verwalten"). */
    suspend fun liveCategories(): List<LiveCategory> {
        val hidden = ctx.hidden()
        return allCategories(SearchEntry.KIND_LIVE).filterNot { "l:${it.id}" in hidden }
    }

    fun setCategoryHidden(kind: Char, categoryId: String, hidden: Boolean) =
        setCategoriesHidden(kind, listOf(categoryId), hidden)

    /** Hides/shows categories – everything is recomputed locally, without a single request. */
    @Suppress("UNUSED_PARAMETER")
    fun setCategoriesHidden(kind: Char, categoryIds: List<String>, hidden: Boolean, refresh: Boolean = true) {
        if (categoryIds.isEmpty()) return
        settings.setCategoriesHidden(categoryIds.map { "$kind:$it" }, hidden)
        ctx.update { it.copy(hiddenCategories = ctx.hidden()) }
        applyCategoryChanges()
    }

    fun applyCategoryChanges(@Suppress("UNUSED_PARAMETER") kind: Char? = null) {
        ctx.scope.launch {
            catalog.recomputeRows()
            search.run()
        }
    }

    /** Titles of a kind for the kids' Mediathek: one category or all visible ones, newest first. */
    suspend fun visibleTitles(kind: Char, categoryId: String?, limit: Int = 300): List<SearchEntry> {
        val entries = ctx.entries ?: return emptyList()
        val hidden = ctx.hidden()
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
        val hidden = ctx.hidden()
        return allCategories(kind).filterNot { "$kind:${it.id}" in hidden }
    }

    /** All categories of a kind (also hidden ones) – for the kids release list. */
    fun categoriesFor(kind: Char): List<LiveCategory> = ctx.categoriesByKind[kind].orEmpty()
}
