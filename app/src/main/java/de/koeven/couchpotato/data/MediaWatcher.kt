package de.koeven.couchpotato.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Watches what other players (IsiPro …) report to the system while they play.
 * When one of them plays a film or an episode from the provider, the position is written to
 * Couchpotato's own "Weiterschauen" – so you can continue in Couchpotato what you started in IsiPro.
 *
 * Needs a one-time permission via adb:
 *   adb shell cmd notification allow_listener de.koeven.couchpotato/de.koeven.couchpotato.data.MediaWatcher
 * Only reads what happens on the stick; never talks to the provider.
 */
class MediaWatcher : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var sessionManager: MediaSessionManager? = null
    private val progress: ProgressStore get() = ProgressStore(this)   // always the active profile

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = msm
        val me = ComponentName(this, MediaWatcher::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            attach(msm.getActiveSessions(me))
        }.onFailure { Log.w(TAG, "media sessions not readable", it) }
    }

    override fun onListenerDisconnected() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        controllers.clear()
        super.onListenerDisconnected()
    }

    private fun attach(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { k ->
            controllers.remove(k)?.let { (c, cb) ->
                record(c)                                  // last position when the player closes
                runCatching { c.unregisterCallback(cb) }
            }
        }
        for (c in list) {
            if (c.packageName == packageName) continue     // our own player saves itself
            if (c.sessionToken in controllers) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = record(c)
                override fun onPlaybackStateChanged(state: PlaybackState?) = record(c)
                override fun onSessionDestroyed() = record(c)
            }
            runCatching { c.registerCallback(cb, handler) }
            controllers[c.sessionToken] = c to cb
            record(c)
        }
    }

    private fun record(c: MediaController) {
        runCatching {
            val md = c.metadata ?: return
            val state = c.playbackState ?: return
            var pos = state.position
            if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
                pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
            }
            val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
            val uri = md.description?.mediaUri?.toString() ?: md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            if (uri != null && PROVIDER_URL.containsMatchIn(uri)) {
                recordIptv(md, uri, pos, dur)
            } else {
                recordOther(c, md, state, pos, dur)
            }
        }.onFailure { Log.w(TAG, "record failed", it) }
    }

    /** Film or episode from the provider playing in another app (IsiPro) → Couchpotato's own progress. */
    private fun recordIptv(md: MediaMetadata, uri: String, pos: Long, dur: Long) {
        val m = VOD_URL.find(uri) ?: return             // live channels have no "weiter"
        val kind = m.groupValues[1]
        val id = m.groupValues[2].toLongOrNull() ?: return
        val ext = m.groupValues[3]
        if (pos <= 5_000) return
        val title = (md.description?.title ?: md.getString(MediaMetadata.METADATA_KEY_TITLE))
            ?.toString()?.takeIf { it.isNotBlank() }
        val item = if (kind == "movie") {
            PlayItem(
                key = "vod:$id", kind = PlayItem.KIND_MOVIE, streamId = id, ext = ext,
                title = title ?: "Film", subtitle = null, image = null, seriesId = null,
            )
        } else {
            PlayItem(
                key = "ep:$id", kind = PlayItem.KIND_EPISODE, streamId = id, ext = ext,
                title = title ?: "Folge", subtitle = null, image = null, seriesId = null,
            )
        }
        // keep what Couchpotato already knows about this title (name, cover, series)
        val known = progress.get(item.key)?.item
        val merged = if (known != null) item.copy(
            title = known.title, subtitle = known.subtitle, image = known.image, seriesId = known.seriesId,
        ) else item
        if (dur > 0 && pos >= dur * 0.95) progress.finished(merged, null)
        else progress.save(merged, pos, dur)
    }

    /** Any other app (ARD Mediathek, Disney+ …): title, show and picture for "Weiterschauen". */
    private fun recordOther(c: MediaController, md: MediaMetadata, state: PlaybackState, pos: Long, dur: Long) {
        if (state.state != PlaybackState.STATE_PLAYING && state.state != PlaybackState.STATE_PAUSED) return
        val d = md.description
        val title = (d?.title ?: md.getText(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val subtitle = (d?.subtitle ?: md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getText(MediaMetadata.METADATA_KEY_ALBUM) ?: md.getText(MediaMetadata.METADATA_KEY_ARTIST))
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != title }
        val imageUrl = (d?.iconUri?.toString()
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
            ?.takeIf { it.startsWith("http") }
        val art = if (imageUrl == null) {
            d?.iconBitmap ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } else null
        // one entry per show: episodes of the same series replace each other
        val show = (subtitle ?: title).lowercase()
        val key = "media:${c.packageName}:${show.hashCode().toUInt()}"
        MediaPlays.save(
            this,
            MediaPlay(key, c.packageName, title, subtitle, imageUrl, pos.coerceAtLeast(0), dur, System.currentTimeMillis()),
            art,
            c.sessionActivity,
        )
    }

    companion object {
        private const val TAG = "MediaWatcher"
        /** …/movie/USER/PASS/123.mkv  or  …/series/USER/PASS/456.mp4 */
        /** Stream links of an Xtream provider (user and password in the path). */
        private val PROVIDER_URL = Regex("""/(live|movie|series)/[^/]+/[^/]+/\d+""")
        private val VOD_URL = Regex("""/(movie|series)/[^/]+/[^/]+/(\d+)\.(\w+)""")

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
