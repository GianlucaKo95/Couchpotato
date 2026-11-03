package de.koeven.couchpotato.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Screen time for kids profiles. Counted is the time the launcher is NOT in front while a kids
 * profile is active – i.e. Couchpotato's player as well as Netflix, YouTube Kids & co.
 * Browsing the launcher itself is free. Stays on this Fire TV (not synced).
 */
object ScreenTime {
    private const val PREFS = "screentime"
    private const val MAX_AWAY_MS = 3 * 3600_000L      // TV switched off with an app open: don't count all night

    sealed interface State {
        data class Allowed(val remainingMin: Int?) : State
        /** [evening]: from 19 o'clock on the "Gute Nacht" version, even without a bedtime set. */
        data class LimitReached(val evening: Boolean) : State
        data class Bedtime(val at: String) : State
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(Date())

    /** The launcher goes to the background (an app or the player starts). */
    fun leaveLauncher(c: Context) {
        val p = Profiles.active(c)
        if (!p.kid) return
        prefs(c).edit().putLong("awayFrom", System.currentTimeMillis()).putString("awayProfile", p.id).apply()
    }

    /** Back in the launcher: book the time spent away. */
    fun backInLauncher(c: Context) {
        val pr = prefs(c)
        val from = pr.getLong("awayFrom", 0L)
        val id = pr.getString("awayProfile", null)
        pr.edit().remove("awayFrom").remove("awayProfile").apply()
        if (from <= 0 || id == null) return
        val ms = (System.currentTimeMillis() - from).coerceIn(0, MAX_AWAY_MS)
        val key = "used_${id}_${today()}"
        pr.edit().putLong(key, pr.getLong(key, 0L) + ms).apply()
    }

    /** A kids profile is in an app or the player right now. */
    fun isAway(c: Context): Boolean = prefs(c).getLong("awayFrom", 0L) > 0

    /** Used today in ms, including a session that is running right now. */
    fun usedMs(c: Context, p: Profile): Long {
        val pr = prefs(c)
        var ms = pr.getLong("used_${p.id}_${today()}", 0L)
        val from = pr.getLong("awayFrom", 0L)
        if (from > 0 && pr.getString("awayProfile", null) == p.id) ms += (System.currentTimeMillis() - from).coerceIn(0, MAX_AWAY_MS)
        return ms
    }

    /** Parents' "30 Minuten mehr" (with PIN) – today only. */
    fun addBonus(c: Context, p: Profile, minutes: Int) {
        val key = "bonus_${p.id}_${today()}"
        prefs(c).edit().putInt(key, prefs(c).getInt(key, 0) + minutes).apply()
    }

    fun state(c: Context, p: Profile = Profiles.active(c)): State {
        if (!p.kid) return State.Allowed(null)
        val bonus = prefs(c).getInt("bonus_${p.id}_${today()}", 0)
        if (p.bedtime >= 0) {
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            // locked from bedtime until 6 in the morning; a bonus pushes bedtime back
            val bed = p.bedtime + bonus
            if (now >= bed || now < 6 * 60) return State.Bedtime(clock(p.bedtime))
        }
        if (p.dailyMinutes <= 0) return State.Allowed(null)
        val left = p.dailyMinutes + bonus - (usedMs(c, p) / 60_000).toInt()
        if (left > 0) return State.Allowed(left)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return State.LimitReached(evening = hour >= 19 || hour < 6)
    }

    fun clock(minuteOfDay: Int) = "%d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
