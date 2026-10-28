package de.koeven.couchpotato.data

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray
import org.json.JSONObject

data class XtreamConfig(val baseUrl: String, val user: String, val pass: String) {
    val isValid: Boolean get() = baseUrl.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    companion object {
        /** Accepts "host:port", "http://host:port/", or a pasted get.php / player_api.php link. */
        fun normalizeBase(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            for (marker in listOf("/player_api.php", "/get.php", "/xmltv.php")) {
                val i = s.indexOf(marker)
                if (i > 0) s = s.substring(0, i)
            }
            return s.trimEnd('/')
        }

        /** Parses an M3U link like http://host:8080/get.php?username=X&password=Y&type=m3u_plus */
        fun fromM3uLink(link: String): XtreamConfig? = runCatching {
            val uri = Uri.parse(link.trim())
            val user = uri.getQueryParameter("username")
            val pass = uri.getQueryParameter("password")
            if (user.isNullOrBlank() || pass.isNullOrBlank()) null
            else XtreamConfig(normalizeBase(link), user, pass)
        }.getOrNull()
    }
}

data class VodItem(val id: Long, val name: String, val image: String?, val added: Long, val ext: String)

data class SeriesItem(val id: Long, val name: String, val image: String?, val updated: Long)

data class LiveCategory(val id: String, val name: String)

data class LiveChannel(val id: Long, val name: String, val logo: String?, val categoryId: String?)

data class Episode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val image: String?,
    val ext: String,
    val durationSec: Int,
)

data class SeriesInfo(
    val id: Long,
    val name: String,
    val cover: String?,
    val plot: String?,
    val episodes: List<Episode>,   // sorted by season, then episode number
)

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
)

/** Something the player can play; also the unit stored for "Weiterschauen". */
data class PlayItem(
    val key: String,          // "vod:<id>" or "ep:<id>"
    val kind: String,         // KIND_MOVIE or KIND_EPISODE
    val streamId: Long,
    val ext: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val seriesId: Long?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("kind", kind)
        .put("streamId", streamId)
        .put("ext", ext)
        .put("title", title)
        .put("subtitle", subtitle ?: JSONObject.NULL)
        .put("image", image ?: JSONObject.NULL)
        .put("seriesId", seriesId ?: JSONObject.NULL)

    companion object {
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
        const val KIND_LIVE = "live"

        fun fromJson(o: JSONObject) = PlayItem(
            key = o.getString("key"),
            kind = o.getString("kind"),
            streamId = o.getLong("streamId"),
            ext = o.str("ext") ?: "mp4",
            title = o.str("title") ?: "",
            subtitle = o.str("subtitle"),
            image = o.str("image"),
            seriesId = o.str("seriesId")?.toLongOrNull(),
        )

        fun movie(v: VodItem) = PlayItem(
            key = "vod:${v.id}", kind = KIND_MOVIE, streamId = v.id, ext = v.ext,
            title = v.name, subtitle = null, image = v.image, seriesId = null,
        )

        fun live(c: LiveChannel) = PlayItem(
            key = "live:${c.id}", kind = KIND_LIVE, streamId = c.id, ext = "ts",
            title = c.name, subtitle = null, image = c.logo, seriesId = null,
        )

        fun episode(s: SeriesInfo, e: Episode) = PlayItem(
            key = "ep:${e.id}", kind = KIND_EPISODE, streamId = e.id, ext = e.ext,
            title = s.name,
            subtitle = "S%02d E%02d · %s".format(e.season, e.number, e.title),
            image = e.image ?: s.cover,
            seriesId = s.id,
        )
    }
}

data class ProgressEntry(
    val item: PlayItem,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val upNext: Boolean,      // next episode queued after finishing the previous one
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

// ---- small JSON helpers (Android's optString returns "null" for JSON null) ----

internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

internal fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }
