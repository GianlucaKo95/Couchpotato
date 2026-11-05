package de.koeven.couchpotato.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import de.koeven.couchpotato.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates straight from this repo's GitHub releases (read-only token from the build).
 * Couchpotato looks at most once an hour, downloads the APK, checks its SHA-256 (when GitHub
 * provides one) and hands it to Android's installer – no adb needed any more.
 */
object Updater {
    data class Release(val build: Int, val url: String, val size: Long, val sha256: String?)

    private const val PREFS = "updater"
    private const val CHECK_GAP_MS = 1 * 3600_000L      // GitHub only, never the IPTV provider
    private const val API = "https://api.github.com/repos/GianlucaKo95/Couchpotato"

    val configured: Boolean get() = BuildConfig.UPDATE_TOKEN.isNotBlank()
    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What went wrong last time (search, download or Android's installer) – shown in the settings. */
    fun lastProblem(c: Context): String? = prefs(c).getString("problem", null)
    fun setProblem(c: Context, text: String?) = prefs(c).edit().putString("problem", text).apply()

    /** Last known newer release (from the last check), or null. */
    fun known(c: Context): Release? = prefs(c).getString("latest", null)?.let { parse(it) }?.takeIf { it.build > currentBuild }

    /** Looks for a newer build. [force] ignores the 1-hour gap (button in the settings). */
    suspend fun check(c: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val p = prefs(c)
        if (!force && System.currentTimeMillis() - p.getLong("checkedAt", 0L) < CHECK_GAP_MS) return@withContext known(c)
        p.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
        // the highest build number wins. GitHub sorts the list by tag NAME ("build-99" before
        // "build-115"), so look at a whole page plus the release flagged "latest"
        val list = org.json.JSONArray(get("$API/releases?per_page=100"))
        val latest = runCatching { JSONObject(get("$API/releases/latest")) }.getOrNull()
        val best = ((0 until list.length()).map { list.getJSONObject(it) } + listOfNotNull(latest))
            .filter { !it.optBoolean("draft") }
            .maxByOrNull { it.optString("tag_name").removePrefix("build-").toIntOrNull() ?: -1 }
            ?.toString() ?: return@withContext null
        p.edit().putString("latest", best).apply()
        parse(best)?.takeIf { it.build > currentBuild }
    }

    private fun parse(text: String): Release? = runCatching {
        val o = JSONObject(text)
        val build = o.getString("tag_name").removePrefix("build-").toInt()
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.optString("name") == "couchpotato.apk" }
        Release(
            build = build,
            url = "$API/releases/assets/${apk.getLong("id")}",
            size = apk.optLong("size"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        )
    }.getOrNull()

    /** May Couchpotato install apps? (Fire TV: Einstellungen → Mein Fire TV → Entwickleroptionen.) */
    fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        runCatching {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Downloads, verifies and starts the installation (Android then asks once to confirm). */
    suspend fun install(c: Context, r: Release, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val file = File(c.cacheDir, "update.apk")
        val api = (URL(r.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "Couchpotato-Updater")
        }
        val conn = if (api.responseCode in 300..399) {
            // the signed download link must be fetched without our token
            val location = api.getHeaderField("Location") ?: throw IOException("Download-Link fehlt")
            api.disconnect()
            (URL(location).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        } else api
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download fehlgeschlagen (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { onProgress(pct) } }
                    }
                }
            }
        } finally { conn.disconnect() }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val sizeWrong = r.size > 0 && file.length() != r.size
        if (sizeWrong || (r.sha256 != null && !hash.equals(r.sha256, ignoreCase = true))) {
            file.delete(); throw IOException("Download beschädigt – bitte noch einmal")
        }

        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("couchpotato.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, UpdateResultReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
        file.delete()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Couchpotato-Updater")
        try {
            if (conn.responseCode !in 200..299) throw IOException(if (conn.responseCode == 401) "Update-Schlüssel ungültig oder abgelaufen" else "GitHub antwortet mit HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}

/** Android's installer reports back here; the confirmation dialog is started from here. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                // some projector firmwares have no confirmation screen for app installs: say so instead of nothing
                val ok = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!ok) problem(context, "Dieses Gerät zeigt keinen Installationsdialog an – Update bitte einmal per adb einspielen.")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.setProblem(context, null)   // the new version starts as the launcher
            else -> problem(context, "Android hat das Update nicht installiert: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "abgebrochen") + " (Status $status)")
        }
    }

    private fun problem(context: Context, text: String) {
        Updater.setProblem(context, text)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
}
