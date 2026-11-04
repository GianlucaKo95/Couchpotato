package de.koeven.couchpotato.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build

/**
 * Reads Android TV's system-wide "Watch Next" row (TvContract.WatchNextPrograms),
 * where streaming apps can publish what you're in the middle of.
 *
 * EXPERIMENTAL on Fire TV: Amazon's own apps report progress to the Amazon home screen,
 * and Android may restrict reading other apps' entries. Whatever comes back is merged
 * into "Weiterschauen".
 */
object WatchNextSource {

    data class Program(
        val key: String,
        val packageName: String?,
        val title: String,
        val subtitle: String?,
        val image: String?,
        val intentUri: String?,
        val lastEngagement: Long,
        val positionMs: Long,
        val durationMs: Long,
    ) {
        val fraction: Float
            get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private val CONTENT_URI: Uri = Uri.parse("content://android.media.tv/watch_next_program")

    fun load(context: Context): List<Program> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return emptyList()
        }
        return try {
            val cursor = context.contentResolver.query(CONTENT_URI, null, null, null, null)
            if (cursor == null) {
                return emptyList()
            }
            val list = cursor.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.int("browsable") == 0) continue
                        val title = c.string("title") ?: continue
                        val season = c.string("season_display_number")
                        val episode = c.string("episode_display_number")
                        val episodeTitle = c.string("episode_title")
                        val subtitle = listOfNotNull(
                            if (season != null && episode != null) "S$season E$episode" else null,
                            episodeTitle,
                        ).joinToString(" · ").ifEmpty { null }
                        val pkg = c.string("package_name")
                        add(
                            Program(
                                // stable across reboots (row ids change when apps republish)
                                key = "wn:$pkg:$title:${subtitle ?: ""}",
                                packageName = pkg,
                                title = title,
                                subtitle = subtitle,
                                image = c.string("poster_art_uri") ?: c.string("thumbnail_uri"),
                                intentUri = c.string("intent_uri"),
                                lastEngagement = c.long("last_engagement_time_utc_millis"),
                                positionMs = c.long("last_playback_position_millis"),
                                durationMs = c.long("duration_millis"),
                            )
                        )
                    }
                }
            }
            list
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun open(context: Context, p: Program): Boolean {
        val intent = p.intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return p.packageName?.let { AppsRepository.launch(context, it) } ?: false
    }

    private fun Cursor.idx(col: String) = getColumnIndex(col)
    private fun Cursor.string(col: String): String? =
        idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }?.takeIf { it.isNotBlank() }
    private fun Cursor.long(col: String): Long =
        idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L
    private fun Cursor.int(col: String): Int =
        idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: 1
}
