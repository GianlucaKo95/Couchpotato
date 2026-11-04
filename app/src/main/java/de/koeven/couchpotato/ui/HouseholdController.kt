package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.CloudSync

/**
 * Household sync (end-to-end encrypted, no account): profiles, progress and settings between
 * the TVs. The IPTV login only travels if that was switched on explicitly on the main TV.
 */
class HouseholdController(
    private val ctx: HomeContext,
    private val catalog: CatalogController,
    private val profiles: ProfilesController,
) {
    private val cloud = CloudSync(ctx.app)
    private val settings get() = ctx.settings

    fun status(): String = cloud.status()
    val linked: Boolean get() = cloud.linked
    val isMain: Boolean get() = cloud.isMain
    fun code(): String? = cloud.householdCode()
    var shareIptv: Boolean
        get() = cloud.shareIptv
        set(value) { cloud.shareIptv = value }

    suspend fun create(): String = cloud.createHousehold()

    suspend fun join(code: String): String {
        val result = cloud.join(code)
        reload()
        return result.message + takeSharedLogin(result.iptv, always = true)
    }

    suspend fun syncNow(): String {
        val result = cloud.sync()
        settings.cloudSyncAttemptAt = System.currentTimeMillis()
        reload()
        return result.message + takeSharedLogin(result.iptv, always = false)
    }

    suspend fun renewCode(): String = cloud.renewCode()

    suspend fun leave(): String = cloud.leave()

    /**
     * Automatic sync in the background (at most once a minute). If the cloud can't be reached,
     * just try again later – it must not show up as an IPTV error on the home screen.
     */
    suspend fun autoSync() {
        if (!cloud.linked || System.currentTimeMillis() - settings.cloudSyncAttemptAt < CLOUD_SYNC_MIN_GAP_MS) return
        settings.cloudSyncAttemptAt = System.currentTimeMillis()
        val synced = runCatching { cloud.sync() }.getOrNull() ?: return
        reload()
        runCatching { takeSharedLogin(synced.iptv, always = false) }
    }

    /** The main TV shares its IPTV login (only if switched on there): use it here when this TV has none. */
    private suspend fun takeSharedLogin(iptv: org.json.JSONObject?, always: Boolean): String {
        val cfg = cloud.iptvConfig(iptv) ?: return ""
        if (!always && settings.config().isValid) return ""
        if (cfg == settings.config()) return ""
        catalog.applyLogin(cfg)
        catalog.ensureIndex(force = false)
        return " IPTV-Zugang vom Hauptgerät übernommen. Hinweis: Viele Anbieter erlauben nur 1–2 Geräte gleichzeitig."
    }

    private suspend fun reload() {
        ctx.reloadStores()
        profiles.refresh()
        ctx.update { it.copy(hiddenCategories = ctx.hidden()) }
        ctx.refreshLocal()
        catalog.recomputeRows()
        catalog.recomputeRecommendations()
    }

    private companion object {
        const val CLOUD_SYNC_MIN_GAP_MS = 60 * 1000L
    }
}
