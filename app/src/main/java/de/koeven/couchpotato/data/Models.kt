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

        /**
         * Reads server, user and password out of a complete provider link. Understands
         *  - http://host:8080/get.php?username=U&password=P&type=m3u_plus   (also player_api.php, xmltv.php)
         *  - http://host:8080/playlist/U/P/m3u_plus  and  http://host:8080/get/U/P
         *  - a stream link like http://host:8080/live/U/P/123.ts, /movie/U/P/1.mkv, /series/U/P/7.mp4
         *  - http://host:8080/U/P/123   (short live link)
         * Returns null if the link carries no login (then it's not an Xtream playlist).
         */
        fun fromLink(input: String): XtreamConfig? = runCatching {
            val link = input.trim().trim('"', '\'', '<', '>').trim()
            if (link.isEmpty()) return null
            val withScheme = if (link.startsWith("http://") || link.startsWith("https://")) link else "http://$link"
            val uri = Uri.parse(withScheme)
            val authority = uri.encodedAuthority?.takeIf { it.isNotBlank() } ?: return null
            val root = "${uri.scheme}://$authority"

            val qUser = uri.getQueryParameter("username")
            val qPass = uri.getQueryParameter("password")
            if (!qUser.isNullOrBlank() && !qPass.isNullOrBlank()) {
                return XtreamConfig(normalizeBase(withScheme.substringBefore('?')), qUser.trim(), qPass.trim())
            }

            val seg = uri.pathSegments.filter { it.isNotBlank() }
            val pair: Pair<String, String>? = when {
                seg.size >= 3 && seg[0].lowercase() in PATH_PREFIXES -> seg[1] to seg[2]
                seg.size >= 3 && seg[2].substringBefore('.').all { it.isDigit() } -> seg[0] to seg[1]
                else -> null
            }
            pair?.let { (u, p) -> XtreamConfig(root, u, p) }
        }.getOrNull()

        private val PATH_PREFIXES = setOf("playlist", "get", "live", "movie", "series", "timeshift")
    }

    /** The standard M3U link for these credentials (shown when the login is entered as a link). */
    fun toM3uLink(): String =
        if (!isValid) "" else
            "${normalizeBase(baseUrl)}/get.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}&type=m3u_plus&output=ts"
}

data class VodItem(
    val id: Long,
    val name: String,
    val image: String?,
    val added: Long,
    val ext: String,
    val categoryId: String? = null,
)

data class SeriesItem(
    val id: Long,
    val name: String,
    val image: String?,
    val updated: Long,
    val categoryId: String? = null,
)

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
