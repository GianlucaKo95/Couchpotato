package de.koeven.couchpotato.data

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
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
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                found[pkg] = ri
            }
        }
        return found.values.map { ri ->
            val ai = ri.activityInfo
            val updated = runCatching { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            cache[ai.packageName]?.takeIf { it.first == updated }?.second ?: run {
                val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
                val icon = if (banner == null) runCatching { ri.loadIcon(pm) }.getOrNull() else null
                AppEntry(
                    packageName = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    banner = banner?.let { runCatching { it.toBitmap(320, 180).asImageBitmap() }.getOrNull() },
                    icon = icon?.let { runCatching { it.toBitmap(144, 144).asImageBitmap() }.getOrNull() },
                ).also { cache[ai.packageName] = updated to it }
            }
        }
    }

    fun launch(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
