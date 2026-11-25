package de.koeven.couchpotato.sonos

import android.content.Context
import android.net.wifi.WifiManager

/**
 * What this stick knows about Sonos: the rooms found, the ones the player's sound goes to
 * (stays until "Fire TV" is picked again in the player) and the fine adjustment of the picture.
 * Only on this stick – not synced to the household (other TVs stand elsewhere).
 */
object SonosPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("sonos", Context.MODE_PRIVATE)

    fun known(c: Context): List<SonosSpeaker> =
        p(c).getString("known", "").orEmpty().lines().mapNotNull { SonosSpeaker.decode(it) }

    /** Chosen rooms in the order they were picked (the first leads the group). */
    fun selected(c: Context): List<SonosSpeaker> {
        val all = known(c).associateBy { it.uuid }
        return p(c).getString("selected", "").orEmpty().split(',').mapNotNull { all[it] }
    }

    fun setSelected(c: Context, uuids: List<String>) = p(c).edit().putString("selected", uuids.joinToString(",")).apply()

    fun trimMs(c: Context) = p(c).getInt("trimMs", 0)
    fun setTrimMs(c: Context, ms: Int) = p(c).edit().putInt("trimMs", ms).apply()

    /** Looks for Sonos in the home network (a few seconds, blocking) and remembers the rooms. */
    fun discover(c: Context): List<SonosSpeaker> {
        val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("couchpotato-sonos")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            val hosts = LinkedHashSet<String>()
            hosts += runCatching { SonosControl.search() }.getOrDefault(emptyList())
            hosts += known(c).map { it.host }               // multicast blocked? ask the ones we knew
            val control = SonosControl()
            for (h in hosts) {
                val rooms = runCatching { control.rooms(h) }.getOrNull()
                if (!rooms.isNullOrEmpty()) {
                    p(c).edit().putString("known", rooms.joinToString("\n") { it.encode() }).apply()
                    return rooms
                }
            }
            return emptyList()
        } finally {
            runCatching { lock?.release() }
        }
    }
}
