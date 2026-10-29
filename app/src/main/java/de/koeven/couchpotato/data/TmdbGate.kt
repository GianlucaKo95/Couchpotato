package de.koeven.couchpotato.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Semaphore

/**
 * Brake for TMDB. TMDB allows roughly 40 requests per second per key; Couchpotato stays at half:
 * at most 4 at the same time and 20 per second. If TMDB still answers "429 too many requests",
 * all TMDB requests pause for as long as TMDB asks. Counts are kept for the settings screen.
 */
object TmdbGate {
    private const val MAX_PARALLEL = 4
    private const val MIN_GAP_MS = 50L           // = 20 per second
    private const val DEFAULT_PAUSE_MS = 10_000L
    private const val HOUR_MS = 60 * 60 * 1000L

    private val parallel = Semaphore(MAX_PARALLEL, true)
    private val lock = Any()
    private var lastStart = 0L
    private val recent = ArrayDeque<Long>()      // start times of the last hour
    @Volatile var pausedUntil = 0L; private set
    @Volatile var last429 = 0L; private set
    private var today = ""
    private var todayCount = 0
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("tmdbgate", Context.MODE_PRIVATE)
        prefs = p
        synchronized(lock) {
            today = p.getString("day", "") ?: ""
            todayCount = p.getInt("count", 0)
            last429 = p.getLong("last429", 0L)
            pausedUntil = p.getLong("pausedUntil", 0L)
            p.getString("recent", null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.let { recent.addAll(it) }
        }
    }

    /** Runs one TMDB request through the brake. [call] returns the HTTP code and the result. */
    fun <T> run(call: () -> Pair<Int, T?>, retryAfterSec: () -> Long?): T {
        if (System.currentTimeMillis() < pausedUntil) throw IOException("TMDB bremst gerade – kurz warten")
        parallel.acquire()
        try {
            synchronized(lock) {
                val wait = lastStart + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                val now = System.currentTimeMillis()
                lastStart = now
                count(now)
            }
            val (code, result) = call()
            if (code == 429) {
                val pause = (retryAfterSec()?.times(1000) ?: DEFAULT_PAUSE_MS).coerceIn(1_000L, 120_000L)
                val now = System.currentTimeMillis()
                pausedUntil = now + pause
                last429 = now
                save()
                throw IOException("TMDB bremst gerade – kurz warten")
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            parallel.release()
        }
    }

    private fun count(now: Long) {
        while (recent.isNotEmpty() && now - recent.first() > HOUR_MS) recent.removeFirst()
        recent.addLast(now)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))
        if (day != today) { today = day; todayCount = 0 }
        todayCount++
        save()
    }

    private fun save() {
        val p = prefs ?: return
        synchronized(lock) {
            p.edit()
                .putString("day", today).putInt("count", todayCount)
                .putLong("last429", last429).putLong("pausedUntil", pausedUntil)
                .putString("recent", recent.joinToString(","))
                .apply()
        }
    }

    fun lastHour(): Int = synchronized(lock) {
        val now = System.currentTimeMillis()
        recent.count { now - it <= HOUR_MS }
    }

    fun today(): Int = synchronized(lock) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        if (day == today) todayCount else 0
    }
}
