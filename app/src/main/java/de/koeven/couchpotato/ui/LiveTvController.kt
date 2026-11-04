package de.koeven.couchpotato.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.XtreamClient
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Live TV: favourites, "Jetzt läuft" and starting a channel (here or in IsiPro). */
class LiveTvController(private val ctx: HomeContext) {
    private val settings get() = ctx.settings
    private var epgJob: Job? = null

    /** After a reboot: show the stored "Jetzt läuft" titles if they are recent enough. */
    fun restoreEpg() {
        if (System.currentTimeMillis() - settings.epgLoadedAt < EPG_KEEP_MS) {
            ctx.update { it.copy(nowPlaying = settings.epgTitles()) }
        }
    }

    /** "Jetzt läuft" for the first favourites; at most every 30 minutes, background budget. */
    fun refreshEpg(force: Boolean) {
        val cfg = settings.config()
        val favs = settings.favorites()
        if (!cfg.isValid || favs.isEmpty() || epgJob?.isActive == true) return
        if (!force && System.currentTimeMillis() - settings.epgLoadedAt < EPG_MAX_AGE_MS) return
        settings.epgLoadedAt = System.currentTimeMillis()   // also on failure: no retry before 30 min
        epgJob = ctx.scope.launch {
            val client = XtreamClient(cfg, background = true)
            val result = coroutineScope {
                // one after another (the client spaces them out); only the first ones in the row
                favs.take(EPG_MAX_CHANNELS).map { ch ->
                    ch.id to runCatching { client.nowPlaying(ch.id) }.getOrNull()
                }
            }
            val map = result.mapNotNull { (id, title) -> title?.let { id to it } }.toMap()
            if (map.isNotEmpty()) settings.setEpgTitles(map)
            ctx.update { it.copy(nowPlaying = if (map.isEmpty()) it.nowPlaying else map) }
        }
    }

    fun toggleFavorite(channel: LiveChannel) {
        val current = settings.favorites()
        val updated = if (current.any { it.id == channel.id }) current.filterNot { it.id == channel.id }
        else current + channel
        settings.setFavorites(updated)
        ctx.update { it.copy(favorites = updated) }
        // only the newly added channel needs "Jetzt läuft" (1 request instead of all of them)
        if (updated.size > current.size && updated.indexOfFirst { it.id == channel.id } < EPG_MAX_CHANNELS) {
            val cfg = settings.config()
            ctx.scope.launch {
                val title = runCatching { XtreamClient(cfg, background = true).nowPlaying(channel.id) }.getOrNull()
                    ?: return@launch
                ctx.update { it.copy(nowPlaying = it.nowPlaying + (channel.id to title)) }
            }
        }
    }

    /** Moves a favourite by [delta] places; returns its new index. */
    fun moveFavorite(channelId: Long, delta: Int): Int {
        val list = settings.favorites().toMutableList()
        val i = list.indexOfFirst { it.id == channelId }
        if (i < 0) return 0
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (j != i) {
            list.add(j, list.removeAt(i))
            settings.setFavorites(list)
            ctx.update { it.copy(favorites = list) }
        }
        return j
    }

    fun removeFavorite(channel: LiveChannel) {
        val updated = settings.favorites().filterNot { it.id == channel.id }
        settings.setFavorites(updated)
        ctx.update { it.copy(favorites = updated) }
    }

    fun setLiveInExternalApp(value: Boolean) {
        settings.liveInExternalApp = value
        ctx.update { it.copy(liveInExternalApp = value) }
    }

    /**
     * Plays [channels] starting at [index]. In Couchpotato's player you can zap through the list
     * with up/down; optionally the stream is handed to IsiPro instead.
     */
    fun play(context: Context, channels: List<LiveChannel>, index: Int) {
        val channel = channels.getOrNull(index) ?: return
        if (settings.liveInExternalApp && openInExternalApp(context, channel)) return
        Playback.start(context, channels.map { PlayItem.live(it) }, index)
    }

    private fun openInExternalApp(context: Context, channel: LiveChannel): Boolean {
        val pkg = ctx.state.value.apps.firstOrNull { it.label.contains("isipro", ignoreCase = true) }?.packageName
        if (pkg == null) {
            Notice.show("IsiPro nicht gefunden – spiele in Couchpotato")
            return false
        }
        val url = XtreamClient.streamUrl(settings.config(), PlayItem.live(channel))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
        return runCatching { context.startActivity(intent) }.isSuccess.also { ok ->
            if (ok) settings.markLaunched(pkg)
            else Notice.show("IsiPro nimmt keine Sender-Links an – spiele in Couchpotato", long = true)
        }
    }

    private companion object {
        const val EPG_MAX_AGE_MS = 30 * 60 * 1000L             // "Jetzt läuft": every 30 minutes …
        const val EPG_MAX_CHANNELS = 8                         // … for the first 8 favourites
        const val EPG_KEEP_MS = 2 * 60 * 60 * 1000L            // show stored titles after a reboot
    }
}
