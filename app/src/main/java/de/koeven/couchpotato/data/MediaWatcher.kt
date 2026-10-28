package de.koeven.couchpotato.data

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * TEST: listens to what other apps (Netflix, Prime …) report to the system while they play.
 * Needs a one-time permission via adb:
 *   adb shell cmd notification allow_listener de.koeven.couchpotato/de.koeven.couchpotato.data.MediaWatcher
 * Everything found is written to a small log on the stick and shown under
 * Einstellungen → Weiterschauen, so we can see which fields (title, ID …) the apps hand out.
 * Nothing is sent anywhere.
 */
class MediaWatcher : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<android.media.session.MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var sessionManager: MediaSessionManager? = null

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
        val keys = list.map { it.sessionToken }.toSet()
        // drop sessions that are gone
        controllers.keys.filter { it !in keys }.forEach { k ->
            controllers.remove(k)?.let { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        }
        for (c in list) {
            if (c.packageName == packageName) continue
            val key = c.sessionToken
            if (key in controllers) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = record(c)
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == PlaybackState.STATE_PLAYING) record(c)
                }
                override fun onQueueTitleChanged(title: CharSequence?) = record(c)
                override fun onExtrasChanged(extras: Bundle?) = record(c)
            }
            runCatching { c.registerCallback(cb, handler) }
            controllers[key] = c to cb
            record(c)
        }
    }

    private fun record(c: MediaController) {
        runCatching {
            val f = linkedMapOf<String, String>()
            c.metadata?.let { md ->
                md.description?.let { d ->
                    d.mediaId?.let { f["description.mediaId"] = it }
                    d.title?.let { f["description.title"] = it.toString() }
                    d.subtitle?.let { f["description.subtitle"] = it.toString() }
                    d.description?.let { f["description.description"] = it.toString() }
                    d.mediaUri?.let { f["description.mediaUri"] = it.toString() }
                    d.iconUri?.let { f["description.iconUri"] = it.toString() }
                    d.extras?.let { putBundle(f, "description.extras", it) }
                }
                for (k in md.keySet()) f["meta.${k.removePrefix("android.media.metadata.")}"] = metaValue(md, k)
            }
            c.playbackState?.let { s ->
                f["state"] = stateName(s.state) + " · Position ${s.position / 1000}s"
                s.extras?.let { putBundle(f, "state.extras", it) }
            }
            c.queueTitle?.let { f["queueTitle"] = it.toString() }
            c.queue?.let { q ->
                f["queue"] = "${q.size} Einträge"
                q.firstOrNull()?.description?.let { d ->
                    f["queue[0]"] = listOfNotNull(d.mediaId, d.title?.toString(), d.subtitle?.toString()).joinToString(" | ")
                }
            }
            c.extras?.let { putBundle(f, "session.extras", it) }
            val act = c.sessionActivity
            f["sessionActivity"] = if (act != null) "vorhanden" else "keine"
            act?.let { lastActivity[c.packageName] = it }
            if (f.size > 2) store(this, c.packageName, "Wiedergabe", f)
        }.onFailure { Log.w(TAG, "record failed", it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        val notification = n.notification ?: return
        val extras = notification.extras ?: return
        val isMedia = notification.category == Notification.CATEGORY_TRANSPORT ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (!isMedia) return
        runCatching {
            val f = linkedMapOf<String, String>()
            putBundle(f, "notification", extras)
            notification.contentIntent?.let {
                f["contentIntent"] = "vorhanden"
                lastActivity[n.packageName] = it
            }
            store(this, n.packageName, "Benachrichtigung", f)
        }
    }

    companion object {
        private const val TAG = "MediaWatcher"
        private const val PREFS = "mediawatch"
        private const val MAX_ENTRIES = 25

        /** Last "open the player" link each app handed out (only in memory). */
        val lastActivity = ConcurrentHashMap<String, PendingIntent>()

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        data class Entry(val time: Long, val pkg: String, val source: String, val fields: Map<String, String>)

        fun log(context: Context): List<Entry> = runCatching {
            val arr = JSONArray(prefs(context).getString("log", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val fo = o.getJSONObject("fields")
                Entry(o.getLong("time"), o.getString("pkg"), o.getString("source"),
                    fo.keys().asSequence().associateWith { fo.getString(it) })
            }
        }.getOrDefault(emptyList())

        fun clear(context: Context) = prefs(context).edit().remove("log").apply()

        /** Opens the app at the place its last playback pointed to (if it gave us a link). */
        fun openLast(pkg: String): Boolean =
            lastActivity[pkg]?.let { runCatching { it.send(); true }.getOrDefault(false) } ?: false

        @Synchronized
        private fun store(context: Context, pkg: String, source: String, fields: Map<String, String>) {
            // one entry per app and kind of report: a chatty player must not push the others out
            val kept = log(context).filterNot { it.pkg == pkg && it.source == source }
            val list = (listOf(Entry(System.currentTimeMillis(), pkg, source, fields)) + kept).take(MAX_ENTRIES)
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(JSONObject().put("time", e.time).put("pkg", e.pkg).put("source", e.source)
                    .put("fields", JSONObject(e.fields)))
            }
            prefs(context).edit().putString("log", arr.toString()).apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun putBundle(f: MutableMap<String, String>, prefix: String, b: Bundle) {
            for (k in b.keySet()) {
                @Suppress("DEPRECATION")
                val v = runCatching { b.get(k) }.getOrNull() ?: continue
                val text = when (v) {
                    is android.graphics.Bitmap -> "[Bild ${v.width}×${v.height}]"
                    is CharSequence, is Number, is Boolean -> v.toString()
                    is Array<*> -> v.joinToString(prefix = "[", postfix = "]", limit = 5)
                    else -> v.javaClass.simpleName
                }
                f["$prefix.$k"] = text.take(300)
            }
        }

        private fun metaValue(md: MediaMetadata, key: String): String {
            md.getText(key)?.let { return it.toString().take(300) }
            md.getBitmap(key)?.let { return "[Bild ${it.width}×${it.height}]" }
            md.getRating(key)?.let { return it.toString() }
            return md.getLong(key).toString()
        }

        private fun stateName(s: Int) = when (s) {
            PlaybackState.STATE_PLAYING -> "spielt"
            PlaybackState.STATE_PAUSED -> "pausiert"
            PlaybackState.STATE_STOPPED -> "gestoppt"
            PlaybackState.STATE_BUFFERING -> "lädt"
            PlaybackState.STATE_NONE -> "nichts"
            else -> "Status $s"
        }
    }
}
