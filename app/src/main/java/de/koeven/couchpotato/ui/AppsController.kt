package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.AppEntry
import de.koeven.couchpotato.data.AppsRepository

/** "Meine Apps": order, hiding and launching – plus the order of the home rows. */
class AppsController(private val ctx: HomeContext) {
    private val settings get() = ctx.settings

    fun sorted(apps: List<AppEntry>): List<AppEntry> {
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
    fun move(pkg: String, delta: Int): Int {
        val list = ctx.state.value.apps.toMutableList()
        val i = list.indexOfFirst { it.packageName == pkg }
        if (i < 0) return 0
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (j != i) list.add(j, list.removeAt(i))
        // keep hidden apps' positions in the saved order too
        val shown = list.map { it.packageName }
        settings.setAppOrder(shown + settings.appOrder().filterNot { it in shown })
        settings.appsCustomOrder = true
        ctx.update { it.copy(apps = list, appsCustomOrder = true) }
        return j
    }

    fun setCustomOrder(custom: Boolean) {
        if (custom && settings.appOrder().isEmpty()) {
            settings.setAppOrder(ctx.state.value.apps.map { it.packageName })
        }
        settings.appsCustomOrder = custom
        ctx.refreshLocalLater()
    }

    fun moveRow(id: String, delta: Int) {
        val list = settings.rowOrder().toMutableList()
        val i = list.indexOf(id)
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        settings.setRowOrder(list)
        ctx.update { it.copy(rowOrder = list) }
    }

    fun launch(pkg: String) {
        settings.markLaunched(pkg)
        AppsRepository.launch(ctx.app, pkg)
    }

    fun hide(pkg: String) {
        settings.hideApp(pkg)
        ctx.refreshLocalLater()
    }

    fun resetHidden() {
        settings.resetHiddenApps()
        ctx.refreshLocalLater()
    }
}
