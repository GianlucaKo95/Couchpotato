package de.koeven.couchpotato.data

import android.content.Context

/**
 * "Meine Liste": films and series to watch later, per profile, only on the stick (and in the
 * household sync). Key "m:<id>" / "s:<id>" like the catalogue, value = when it was added.
 */
class Watchlist(context: Context) {
    private val p = context.getSharedPreferences(FILE + Profiles.suffix(context), Context.MODE_PRIVATE)

    fun all(): Map<String, Long> = p.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    operator fun contains(key: String): Boolean = p.contains(key)

    /** Adds or removes; returns true if the title is on the list now. */
    fun toggle(key: String): Boolean {
        val on = key !in this
        if (on) p.edit().putLong(key, System.currentTimeMillis()).apply() else p.edit().remove(key).apply()
        return on
    }

    fun remove(key: String) = p.edit().remove(key).apply()

    companion object {
        const val FILE = "watchlist"

        fun key(kind: Char, id: Long) = "$kind:$id"

        /** The list in catalogue entries, newest addition first; titles the provider dropped are left out. */
        fun entries(list: Map<String, Long>, catalogue: List<SearchEntry>, hidden: Set<String>): List<SearchEntry> {
            if (list.isEmpty()) return emptyList()
            val found = HashMap<String, SearchEntry>()
            for (e in catalogue) {
                val k = key(e.kind, e.id)
                if (k in list && k !in found && e.categoryKey !in hidden) found[k] = e
            }
            return list.entries.sortedByDescending { it.value }.mapNotNull { found[it.key] }
        }
    }
}
