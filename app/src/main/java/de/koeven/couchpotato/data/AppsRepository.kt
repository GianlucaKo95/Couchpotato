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

    // ---------------- projector settings (HY300 & co.) ----------------

    /**
     * Android projectors keep keystone, zoom and projection direction in a hidden window of
     * their own launcher. With Couchpotato as launcher it would be unreachable – so it comes
     * back as a tile. Known windows first, then a search through the projector's system apps.
     * "pkg/class" in [AppEntry.packageName] marks such a tile.
     */
    private val KNOWN_EXTRAS = listOf(
        "com.htc.luminaos/.activity.ProjectActivity" to "Projektion",       // HY300 / HY300 Pro (LuminaOS)
        "com.htc.magcubicos/.activity.ProjectActivity" to "Projektion",     // Magcubic OS
        "com.hysd.vafocus/.VajzActivity" to "Fokus",                        // HY300 Pro autofocus calibration
    )
    private val EXTRA_PACKAGES = listOf("com.htc.", "com.hysd.", "com.softwinner.", "com.allwinner.", "com.magcubic.", "com.xgimi.", "com.dangbei.")
    private val EXTRA_WORDS = Regex("(?i)project|keystone|trapez|correct|zoom|focus")

    private var extras: List<AppEntry>? = null

    fun projectorExtras(context: Context): List<AppEntry> {
        extras?.let { return it }
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>()   // component → label
        for ((comp, label) in KNOWN_EXTRAS) {
            val cn = android.content.ComponentName.unflattenFromString(comp) ?: continue
            val ai = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull() ?: continue
            if (ai.exported) found[cn.flattenToShortString()] = label
        }
        if (found.isEmpty()) {
            // unknown model: look through the projector maker's apps for such windows
            val pkgs = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
                .map { it.packageName }.filter { n -> EXTRA_PACKAGES.any { n.startsWith(it) } }
            for (pkgName in pkgs) {
                val pi = runCatching { pm.getPackageInfo(pkgName, android.content.pm.PackageManager.GET_ACTIVITIES) }.getOrNull() ?: continue
                for (ai in pi.activities.orEmpty()) {
                    if (!ai.exported || !EXTRA_WORDS.containsMatchIn(ai.name.substringAfterLast('.'))) continue
                    val name = ai.name.substringAfterLast('.')
                    val label = when {
                        name.contains("focus", true) -> "Fokus"
                        name.contains("zoom", true) -> "Zoom"
                        else -> "Projektion"
                    }
                    found[android.content.ComponentName(pi.packageName, ai.name).flattenToShortString()] = label
                    if (found.size >= 4) break
                }
            }
        }
        return found.map { (comp, label) ->
            val bmp = extraBanner(label)
            AppEntry(packageName = comp, label = "Beamer · $label", banner = bmp.asImageBitmap(), icon = null)
        }.also { extras = it }
    }

    /** A simple banner in Couchpotato's colours: petrol, a projector symbol, the name. */
    private fun extraBanner(label: String): Bitmap {
        val w = 480; val h = 270
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF0B3236.toInt())
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // projector: body, lens and a light cone
        p.color = 0x40E8B86D
        c.drawPath(android.graphics.Path().apply { moveTo(238f, 92f); lineTo(400f, 40f); lineTo(400f, 170f); close() }, p)
        p.color = 0xFFF4F1EC.toInt()
        c.drawRoundRect(80f, 70f, 250f, 140f, 18f, 18f, p)
        p.color = 0xFFE8B86D.toInt()
        c.drawCircle(214f, 105f, 22f, p)
        p.color = 0xFF0B3236.toInt()
        c.drawCircle(214f, 105f, 11f, p)
        p.color = 0xFFF4F1EC.toInt()
        p.textSize = 46f
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText(label, 80f, 222f, p)
        return bmp
    }

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
        }.getOrNull() } + projectorExtras(context)
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
        // projector settings tile: start that window directly
        android.content.ComponentName.unflattenFromString(pkg)?.takeIf { pkg.contains('/') }?.let { cn ->
            return runCatching {
                context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
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
