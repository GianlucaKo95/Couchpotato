package de.koeven.couchpotato.data

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID

/**
 * "Auf dem Handy weiterschauen": when playback stops on this TV (Back, Home, or the TV is switched off),
 * what was playing and where goes into the household sync straight away – end-to-end encrypted like
 * everything else. The iPhone offers to continue from there.
 */
object Handoff {
    const val FILE = "handoff"
    private const val KEY = "last"

    /** {"device","from","profile","kind","key","streamId","ext","seriesId","title","subtitle","image","pos","dur","at"} */
    fun record(c: Context, item: PlayItem, posMs: Long, durMs: Long) {
        val o = item.toJson()
            .put("device", deviceId(c))
            .put("from", deviceName(c))
            .put("profile", Profiles.active(c).id)
            .put("pos", posMs.coerceAtLeast(0))
            .put("dur", durMs.coerceAtLeast(0))
            .put("at", System.currentTimeMillis())
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).commit()
    }

    /** Watched to the end here: nothing to hand over. */
    fun clear(c: Context) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val last = p.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (last.optString("device") == deviceId(c)) p.edit().remove(KEY).commit()
    }

    /**
     * Pushes right away – switching the TV off doesn't bring the home screen back, so the usual sync
     * would only run the next time the TV wakes up. A short wake lock keeps the CPU up for the request.
     */
    fun pushNow(c: Context) {
        val app = c.applicationContext
        val cloud = CloudSync(app)
        if (!cloud.linked) return
        Thread {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "couchpotato:handoff").apply { acquire(20_000L) }
            try {
                runBlocking { runCatching { cloud.sync() } }
            } finally {
                if (wl.isHeld) wl.release()
            }
        }.start()
    }

    /** Stays on this device (not in a synced file). */
    fun deviceId(c: Context): String {
        val p = c.getSharedPreferences("device", Context.MODE_PRIVATE)
        return p.getString("id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
    }

    fun deviceName(c: Context): String =
        runCatching { Settings.Global.getString(c.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
