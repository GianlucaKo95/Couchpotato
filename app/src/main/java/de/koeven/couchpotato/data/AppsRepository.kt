package de.koeven.couchpotato.data

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

object AppsRepository {

    // Rendering banners is the slow part; keep them per package + update time.
    private val cache = HashMap<String, Pair<Long, AppEntry>>()

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                found[pkg] = ri
            }
        }
        return found.values.mapNotNull { ri -> runCatching {
            val ai = ri.activityInfo
            val updated = runCatching { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            cache[ai.packageName]?.takeIf { it.first == updated }?.second
                ?: fromDisk(context, ai.packageName, updated, ri)
                ?: run {
                    val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
                    val icon = if (banner == null) runCatching { ri.loadIcon(pm) }.getOrNull() else null
                    val bannerBmp = banner?.let { runCatching { it.toBitmapKeepingRatio(480) }.getOrNull() }
                    val iconBmp = icon?.let { runCatching { it.toBitmapKeepingRatio(192) }.getOrNull() }
                    toDisk(context, ai.packageName, updated, bannerBmp, iconBmp)
                    AppEntry(
                        packageName = ai.packageName,
                        label = ri.loadLabel(pm).toString(),
                        banner = bannerBmp?.asImageBitmap(),
                        icon = iconBmp?.asImageBitmap(),
                    )
                }.also { cache[ai.packageName] = updated to it }
        }.getOrNull() }
    }

    /*
     * After a reboot of the stick, rendering every app banner from the other apps' resources is the
     * slowest part of showing the home screen. The rendered bitmaps are kept on disk, per app version.
     */
    private fun diskFile(context: Context, pkg: String, updated: Long, kind: String) =
        java.io.File(context.cacheDir, "appart").apply { mkdirs() }.resolve("${pkg}_${updated}_$kind.png")

    private fun fromDisk(context: Context, pkg: String, updated: Long, ri: ResolveInfo): AppEntry? = runCatching {
        val b = diskFile(context, pkg, updated, "banner")
        val i = diskFile(context, pkg, updated, "icon")
        if (!b.exists() && !i.exists()) return null
        AppEntry(
            packageName = pkg,
            label = ri.loadLabel(context.packageManager).toString(),
            banner = if (b.exists()) android.graphics.BitmapFactory.decodeFile(b.path)?.asImageBitmap() else null,
            icon = if (i.exists()) android.graphics.BitmapFactory.decodeFile(i.path)?.asImageBitmap() else null,
        ).takeIf { it.banner != null || it.icon != null }
    }.getOrNull()

    private fun toDisk(context: Context, pkg: String, updated: Long, banner: Bitmap?, icon: Bitmap?) {
        runCatching {
            // older versions of this app's pictures are no longer needed
            java.io.File(context.cacheDir, "appart").listFiles()?.filter { it.name.startsWith("${pkg}_") }?.forEach { it.delete() }
            banner?.let { bmp -> diskFile(context, pkg, updated, "banner").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            icon?.let { bmp -> diskFile(context, pkg, updated, "icon").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    /**
     * Renders a drawable at its own aspect ratio (longest side = [maxSide] px). Forcing every banner
     * into 320x180 stretched those that aren't exactly 16:9.
     */
    private fun Drawable.toBitmapKeepingRatio(maxSide: Int): Bitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: maxSide
        val h = intrinsicHeight.takeIf { it > 0 } ?: maxSide
        val scale = maxSide.toFloat() / maxOf(w, h)
        return toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }

    fun launch(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Packages that accept a search intent (the query can be handed over). */
    fun searchablePackages(context: Context): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEARCH), 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /** Opens [pkg] with the search term; falls back to just starting the app. */
    fun searchInApp(context: Context, pkg: String, query: String): Boolean {
        val intent = Intent(Intent.ACTION_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtra("query", query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
        launch(context, pkg)
        return false
    }

    /** Fire TV's own search (covers Netflix, Prime & co.), with the term pre-filled where supported. */
    fun openGlobalSearch(context: Context, query: String): Boolean {
        val intents = listOf(
            Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).putExtra(SearchManager.QUERY, query),
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
            if (runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
