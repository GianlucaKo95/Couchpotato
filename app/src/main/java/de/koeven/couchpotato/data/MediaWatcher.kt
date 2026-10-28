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
    private val progress by lazy { ProgressStore(this) }

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

    /** Film or episode from the provider playing in another app → into "Weiterschauen". */
    private fun record(c: MediaController) {
        runCatching {
            val md = c.metadata ?: return
            val uri = md.description?.mediaUri?.toString() ?: md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: return
            val m = VOD_URL.find(uri) ?: return             // live channels have no "weiter"
            val kind = m.groupValues[1]
            val id = m.groupValues[2].toLongOrNull() ?: return
            val ext = m.groupValues[3]
            val state = c.playbackState ?: return
            var pos = state.position
            if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
                pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
            }
            val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
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
        }.onFailure { Log.w(TAG, "record failed", it) }
    }

    companion object {
        private const val TAG = "MediaWatcher"
        /** …/movie/USER/PASS/123.mkv  or  …/series/USER/PASS/456.mp4 */
        private val VOD_URL = Regex("""/(movie|series)/[^/]+/[^/]+/(\d+)\.(\w+)""")

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
