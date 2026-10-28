package de.koeven.couchpotato.data

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What other apps (ARD Mediathek, Disney+ …) reported while playing, kept for "Weiterschauen".
 * One entry per app and show; written by [MediaWatcher], read by the home screen.
 */
data class MediaPlay(
    val key: String,
    val packageName: String,
    val title: String,
    val subtitle: String?,
    val image: String?,          // http(s) link or file:// of a saved picture
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

object MediaPlays {
    private const val PREFS = "mediaplays"
    private const val MAX_ENTRIES = 20

    /** "Open where it was playing" links of the apps (only valid while the stick runs). */
    val openLinks = ConcurrentHashMap<String, PendingIntent>()

    fun all(context: Context): List<MediaPlay> =
        prefs(context).all.values.mapNotNull { (it as? String)?.let(::parse) }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(context: Context, play: MediaPlay, art: Bitmap?, link: PendingIntent?) {
        link?.let { openLinks[play.key] = it }
        var image = play.image ?: all(context).firstOrNull { it.key == play.key && it.title == play.title }?.image
        if (image == null && art != null) image = saveArt(context, play, art)
        val p = prefs(context)
        val edit = p.edit()
        // keep the list short: drop the oldest shows
        all(context).filter { it.key != play.key }.drop(MAX_ENTRIES - 1).forEach { old ->
            edit.remove(old.key)
            deleteArt(old.image)
        }
        val o = JSONObject()
            .put("key", play.key)
            .put("pkg", play.packageName)
            .put("title", play.title)
            .put("subtitle", play.subtitle ?: JSONObject.NULL)
            .put("image", image ?: JSONObject.NULL)
            .put("pos", play.positionMs)
            .put("dur", play.durationMs)
            .put("at", play.updatedAt)
        edit.putString(play.key, o.toString()).apply()
    }

    fun remove(context: Context, key: String) {
        val old = all(context).firstOrNull { it.key == key }
        prefs(context).edit().remove(key).apply()
        deleteArt(old?.image)
        openLinks.remove(key)
    }

    /** Opens the app where it was playing; false if the app gave no link (then just start the app). */
    fun open(key: String): Boolean =
        openLinks[key]?.let { runCatching { it.send(); true }.getOrDefault(false) } ?: false

    private fun saveArt(context: Context, play: MediaPlay, art: Bitmap): String? = runCatching {
        val dir = File(context.filesDir, "media_art").apply { mkdirs() }
        val f = File(dir, "${(play.key + play.title).hashCode().toUInt()}.jpg")
        val scaled = if (art.width > 480) {
            Bitmap.createScaledBitmap(art, 480, (art.height * 480f / art.width).toInt().coerceAtLeast(1), true)
        } else art
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        "file://${f.absolutePath}"
    }.getOrNull()

    private fun deleteArt(image: String?) {
        if (image != null && image.startsWith("file://")) runCatching { File(image.removePrefix("file://")).delete() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parse(s: String): MediaPlay? = runCatching {
        val o = JSONObject(s)
        MediaPlay(
            key = o.getString("key"),
            packageName = o.getString("pkg"),
            title = o.getString("title"),
            subtitle = o.str("subtitle"),
            image = o.str("image"),
            positionMs = o.optLong("pos"),
            durationMs = o.optLong("dur"),
            updatedAt = o.optLong("at"),
        )
    }.getOrNull()
}
