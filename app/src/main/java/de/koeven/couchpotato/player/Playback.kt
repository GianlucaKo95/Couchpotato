package de.koeven.couchpotato.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.SeriesInfo
import org.json.JSONArray

object Playback {
    const val EXTRA_ITEMS = "items"
    const val EXTRA_INDEX = "index"

    /** Starts the player with a playlist (e.g. the rest of a season, so episodes auto-advance). */
    fun start(context: Context, items: List<PlayItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val json = JSONArray(items.map { it.toJson() }).toString()
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_ITEMS, json)
            .putExtra(EXTRA_INDEX, startIndex)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun decode(json: String?): List<PlayItem> {
        if (json == null) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { PlayItem.fromJson(arr.getJSONObject(it)) }
    }

    /** From the given episode to the end of the series (capped). */
    fun episodePlaylist(info: SeriesInfo, fromEpisodeId: Long, max: Int = 40): List<PlayItem> {
        val idx = info.episodes.indexOfFirst { it.id == fromEpisodeId }.coerceAtLeast(0)
        return info.episodes.drop(idx).take(max).map { PlayItem.episode(info, it) }
    }
}
