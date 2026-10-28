package de.koeven.couchpotato.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Keeps other apps' "Watch Next" entries across reboots.
 *
 * Many apps only (re)publish their entries once they have been started. Without this cache
 * the row would be empty after every power-on, because the stick reboots with the TV.
 *
 * Rules per app (package):
 *  - the system currently lists entries for it      -> those are the truth, cache is replaced
 *  - nothing listed, app was opened after the cache -> the app had its chance to republish,
 *                                                      so the list really is empty: drop cache
 *  - nothing listed, app not opened since           -> show the cached entries
 * Cached entries expire after [MAX_AGE_MS].
 */
class WatchNextCache(private val context: Context) {

    private val file = File(context.filesDir, "watchnext.json")
    private val imageDir = File(context.filesDir, "watchnext_img").apply { mkdirs() }

    private data class Cached(val program: WatchNextSource.Program, val savedAt: Long)

    fun merge(live: List<WatchNextSource.Program>, lastLaunched: (String) -> Long): List<WatchNextSource.Program> {
        val now = System.currentTimeMillis()
        val cached = read().filter { now - it.savedAt < MAX_AGE_MS }

        val livePackages = live.mapNotNull { it.packageName }.toSet()
        val keptFromCache = cached.filter { c ->
            val pkg = c.program.packageName ?: return@filter false
            val launched = lastLaunched(pkg)
            // give a just-opened app a minute to republish before trusting an empty list
            pkg !in livePackages && (launched < c.savedAt || now - launched < GRACE_MS)
        }

        // Live entries: keep a local copy of the artwork; content:// images from other apps
        // are often unreadable until that app runs again.
        val previousImages = cached.associate { it.program.key to it.program.image }
        val freshLive = live.map { p -> p.copy(image = persistImage(p) ?: previousImages[p.key] ?: p.image) }

        val result = freshLive.map { Cached(it, now) } + keptFromCache
        write(result)
        cleanupImages(result.mapNotNull { it.program.image })
        return result.map { it.program }
    }

    private fun persistImage(p: WatchNextSource.Program): String? {
        val src = p.image ?: return null
        if (src.startsWith("file://")) return src
        // http(s) artwork is already kept by Coil's disk cache; only copy local URIs
        if (!src.startsWith("content://") && !src.startsWith("android.resource://")) return null
        val target = File(imageDir, sha1(src) + ".img")
        if (!target.exists()) {
            val ok = runCatching {
                context.contentResolver.openInputStream(Uri.parse(src))?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } != null
            }.getOrDefault(false)
            if (!ok) {
                target.delete()
                return null
            }
        }
        return Uri.fromFile(target).toString()
    }

    private fun cleanupImages(inUse: List<String>) {
        val keep = inUse.mapNotNull { runCatching { Uri.parse(it).path }.getOrNull() }.toSet()
        imageDir.listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }

    private fun read(): List<Cached> = runCatching {
        JSONArray(file.readText()).mapObjects { o ->
            Cached(
                program = WatchNextSource.Program(
                    key = o.getString("key"),
                    packageName = o.str("pkg"),
                    title = o.str("title") ?: "",
                    subtitle = o.str("subtitle"),
                    image = o.str("image"),
                    intentUri = o.str("intent"),
                    lastEngagement = o.optLong("engaged"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                ),
                savedAt = o.optLong("savedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<Cached>) {
        val arr = JSONArray(items.map { c ->
            val p = c.program
            JSONObject()
                .put("key", p.key)
                .put("pkg", p.packageName)
                .put("title", p.title)
                .put("subtitle", p.subtitle)
                .put("image", p.image)
                .put("intent", p.intentUri)
                .put("engaged", p.lastEngagement)
                .put("pos", p.positionMs)
                .put("dur", p.durationMs)
                .put("savedAt", c.savedAt)
        })
        runCatching { file.writeText(arr.toString()) }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val GRACE_MS = 60_000L
    }
}
