package de.koeven.couchpotato.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If the app ever crashes, the reason is written to a small file on the stick and shown under
 * Einstellungen → Über Couchpotato – so a crash can be found without a computer and adb.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val head = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date()) +
                    " · Version $version · Thread ${thread.name}\n"
                File(app.filesDir, FILE).writeText(head + sw.toString().take(12_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash report, or null. */
    fun last(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
