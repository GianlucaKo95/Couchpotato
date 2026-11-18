package de.koeven.couchpotato.player

import android.content.Context
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.SettingsStore

/**
 * Live categories and their channels as the Mediathek shows them (own order, hidden ones left
 * out, kids only what is released) – for the channel list in the player and the phone remote.
 * Everything local, no request at the provider.
 */
internal object LiveLineup {
    const val FAVS = "_favs"

    /** The catalogue the launcher has in memory (set when it is loaded) – no second copy. */
    @Volatile var catalogue: List<SearchEntry>? = null

    fun entries(c: Context): List<SearchEntry> = catalogue ?: runCatching {
        kotlinx.coroutines.runBlocking { SearchIndex(c.applicationContext).load() }
    }.getOrDefault(emptyList()).also { if (catalogue == null && it.isNotEmpty()) catalogue = it }

    private fun hidden(c: Context, all: List<LiveCategory>): Set<String> {
        val hidden = SettingsStore(c).hiddenCategories()
        if (!Profiles.active(c).kid) return hidden
        val released = Profiles.kidCategories(c)
        return hidden + all.map { "${SearchEntry.KIND_LIVE}:${it.id}" }.filterNot { it in released }
    }

    /** "★ Favoriten" first (if there are any), then the visible categories. */
    fun categories(c: Context): List<LiveCategory> {
        val settings = SettingsStore(c)
        val all = SearchIndex(c.applicationContext).loadCategories()[SearchEntry.KIND_LIVE].orEmpty()
        val hidden = hidden(c, all)
        val visible = de.koeven.couchpotato.ui.sortByOwnOrder(all, settings.categoryOrder(SearchEntry.KIND_LIVE))
            .filterNot { "${SearchEntry.KIND_LIVE}:${it.id}" in hidden }
        val favs = if (settings.favorites().isNotEmpty()) listOf(LiveCategory(FAVS, "★ Favoriten")) else emptyList()
        return favs + visible
    }

    fun channels(c: Context, category: String): List<LiveChannel> =
        if (category == FAVS) SettingsStore(c).favorites()
        else entries(c).asSequence().filter { it.kind == SearchEntry.KIND_LIVE && it.categoryId == category }
            .map { LiveChannel(it.id, it.name, it.image, it.categoryId) }.distinctBy { it.id }.toList()

    /** Where a channel belongs: the favourites if it is one, otherwise its category. */
    fun categoryOf(c: Context, channelId: Long): String? =
        if (SettingsStore(c).favorites().any { it.id == channelId }) FAVS
        else entries(c).firstOrNull { it.kind == SearchEntry.KIND_LIVE && it.id == channelId }?.categoryId
}
