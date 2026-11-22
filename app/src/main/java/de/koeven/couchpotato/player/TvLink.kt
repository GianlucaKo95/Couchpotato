package de.koeven.couchpotato.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import de.koeven.couchpotato.data.CloudSync
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.ProgressStore
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.TitleInfoRepository
import de.koeven.couchpotato.data.VodItem
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Link to the Couchpotato iPhone app in the same Wi-Fi: the phone gets the catalogue from here (no
 * extra requests to the provider), starts things on the TV and works as a remote.
 *
 * Only devices of the same household get in: every request is signed with a key derived from the
 * household's encryption key (which never leaves the devices). Found via Bonjour/NSD as _couchpotato._tcp.
 */
object TvLink {
    private const val PORT = 8790
    private const val SERVICE = "_couchpotato._tcp"
    private const val MAX_SKEW_S = 120L

    private lateinit var app: Context
    @Volatile private var server: ServerSocket? = null
    @Volatile private var key: ByteArray? = null
    @Volatile private var keyAt = 0L
    @Volatile private var registeredTag: String? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed: WeakReference<Activity>? = null

    /** Called once from the Application; tracks which of our screens is in front (for the remote). */
    fun install(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        refresh()
    }

    /** Starts the server and (re-)announces the TV – e.g. after joining a household. Cheap when nothing changed. */
    fun refresh() {
        Thread({
            runCatching {
                val k = loadKey(force = true)
                if (k == null) { unregister(); return@runCatching }
                if (server == null) {
                    val s = runCatching { ServerSocket(PORT) }.getOrNull() ?: ServerSocket(0)
                    server = s
                    Thread({ serve(s) }, "tvlink").apply { isDaemon = true; start() }
                }
                val tag = tagOf(k)
                if (tag != registeredTag) register(tag)
            }
        }, "tvlink-start").apply { isDaemon = true; start() }
    }

    // ---------------- announce ----------------

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    /** Lets the phone show only the TVs of its own household (reveals nothing about the key). */
    private fun tagOf(k: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(k).take(6).joinToString("") { "%02x".format(it) }

    private fun register(tag: String) {
        unregister()
        val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = SERVICE
            port = server?.localPort ?: return
            setAttribute("h", tag)
            setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { registeredTag = tag }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { registeredTag = null }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsdListener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun unregister() {
        val l = nsdListener ?: return
        nsdListener = null
        registeredTag = null
        runCatching { (app.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) }
    }

    private fun loadKey(force: Boolean = false): ByteArray? {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - keyAt < 60_000) return key
        key = CloudSync(app).linkKey()
        keyAt = now
        return key
    }

    // ---------------- serve ----------------

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread { runCatching { handle(client) }; runCatching { client.close() } }.apply { isDaemon = true; start() }
        }
    }

    private class Req(val method: String, val target: String, val path: String, val query: Map<String, String>, val body: ByteArray)

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > 8_000) return
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val first = lines.first().split(" ")
        val method = first.getOrNull(0) ?: ""
        val target = first.getOrNull(1) ?: "/"
        fun header(name: String) = lines.firstOrNull { it.startsWith("$name:", true) }?.substringAfter(':')?.trim()
        val length = header("Content-Length")?.toIntOrNull()?.coerceIn(0, 64_000) ?: 0
        val body = ByteArray(length).also { var off = 0; while (off < it.size) { val n = input.read(it, off, it.size - off); if (n < 0) break; off += n } }
        val req = Req(method, target, target.substringBefore('?'), params(target.substringAfter('?', "")), body)
        val out = c.getOutputStream()
        if (!authorized(req, header("X-CP-Link"))) return send(out, 403, "application/json", """{"error":"not in this household"}""".toByteArray())
        when {
            method == "GET" && req.path == "/info" -> json(out, info())
            method == "GET" && req.path == "/catalog" -> file(out, File(app.filesDir, "catalog_v3.tsv"), "text/tab-separated-values; charset=utf-8")
            method == "GET" && req.path == "/categories" -> file(out, File(app.filesDir, "categories_v3.json"), "application/json")
            method == "GET" && req.path == "/series" -> series(out, req.query["id"]?.toLongOrNull() ?: 0L)
            method == "GET" && req.path == "/movie" -> file(out, File(app.filesDir, "vodinfo/${req.query["id"]?.toLongOrNull() ?: 0L}.json"), "application/json")
            method == "GET" && req.path == "/epg" -> epg(out, req.query["id"]?.toLongOrNull() ?: 0L)
            method == "GET" && req.path == "/state" -> json(out, state())
            method == "POST" && req.path == "/play" -> json(out, play(JSONObject(String(req.body, Charsets.UTF_8))))
            method == "POST" && req.path == "/key" -> json(out, key(JSONObject(String(req.body, Charsets.UTF_8)).optString("k")))
            else -> send(out, 404, "application/json", "{}".toByteArray())
        }
    }

    /** X-CP-Link: "<unix seconds>.<hex HMAC-SHA256(link key, seconds \n METHOD \n target \n sha256(body))>". */
    private fun authorized(r: Req, value: String?): Boolean {
        val k = loadKey() ?: return false
        val (ts, sig) = value?.split('.', limit = 2)?.takeIf { it.size == 2 } ?: return false
        val t = ts.toLongOrNull() ?: return false
        if (kotlin.math.abs(System.currentTimeMillis() / 1000 - t) > MAX_SKEW_S) return false
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(r.body).joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }
            .doFinal("$ts\n${r.method}\n${r.target}\n$bodyHash".toByteArray(Charsets.UTF_8))
        val expected = mac.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), sig.lowercase().toByteArray())
    }

    private fun params(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
        val p = it.split('=', limit = 2)
        java.net.URLDecoder.decode(p[0], "UTF-8") to java.net.URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, o: JSONObject) = send(out, 200, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun file(out: OutputStream, f: File, type: String) {
        if (!f.exists()) return send(out, 404, "application/json", "{}".toByteArray())
        val bytes = f.readBytes()
        send(out, 200, type, bytes, "X-Modified: ${f.lastModified() / 1000}\r\n")
    }

    // ---------------- answers ----------------

    private fun info(): JSONObject {
        val f = File(app.filesDir, "catalog_v3.tsv")
        return JSONObject().put("name", deviceName()).put("model", Build.MODEL)
            .put("catalog", if (f.exists()) f.lastModified() / 1000 else 0)
            .put("profile", Profiles.active(app).id).put("kid", Profiles.active(app).kid)
    }

    private val titles by lazy { TitleInfoRepository(app, SettingsStore(app)) { LiveLineup.entries(app) } }

    /** Seasons/episodes as the provider sent them – from the TV's copy, or ONE request through the TV's brake. */
    private fun series(out: OutputStream, id: Long) {
        val name = LiveLineup.entries(app).firstOrNull { it.kind == SearchEntry.KIND_SERIES && it.id == id }?.name
            ?: return send(out, 404, "application/json", "{}".toByteArray())
        runCatching { kotlinx.coroutines.runBlocking { titles.loadSeries(id, name) } }
        file(out, File(app.filesDir, "seriesinfo/$id.json"), "application/json")
    }

    /**
     * Programme of a channel for the phone: kept 20 minutes, so several phones (and zapping back and forth)
     * cost the provider at most one request per channel and 20 minutes – through the TV's brake, with the smaller
     * background budget, so a phone can never use up the hour of the person in front of the TV.
     */
    private fun epg(out: OutputStream, id: Long) {
        val dir = File(app.cacheDir, "epg").apply { mkdirs() }
        val f = File(dir, "$id.json")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 20 * 60_000L) {
            val text = runCatching {
                kotlinx.coroutines.runBlocking { de.koeven.couchpotato.data.XtreamClient(SettingsStore(app).config(), background = true).shortEpgRaw(id) }
            }.getOrNull()
            if (text != null) f.writeText(text)
        }
        file(out, f, "application/json")
    }

    /** What's on: the player's item, position and pause state (read on the main thread). */
    private fun state(): JSONObject = onMain {
        val a = resumed?.get()
        val o = JSONObject().put("screen", when (a) { null -> "other"; is PlayerActivity -> "player"; else -> "home" })
        if (a is PlayerActivity && runCatching { a.player }.isSuccess) {
            val p = a.player
            val item = if (a.isLive) a.items.getOrNull(a.live.index) else a.items.getOrNull(p.currentMediaItemIndex)
            o.put("title", item?.title).put("subtitle", item?.subtitle).put("kind", item?.kind).put("key", item?.key)
                .put("image", item?.image)
                .put("pos", p.currentPosition.coerceAtLeast(0)).put("dur", p.duration.takeIf { it > 0 } ?: 0)
                .put("paused", !p.playWhenReady)
                .put("live", a.isLive)
                .put("profile", de.koeven.couchpotato.data.Profiles.active(app).id)
            item?.let { o.put("item", it.toJson()) }           // lets the iPhone take over exactly here
        }
        o
    } ?: JSONObject().put("screen", "other")

    /** {"kind":"live","id":…,"cat":…} | {"kind":"movie","id":…,"pos":ms} | {"kind":"episode","series":…,"ep":…,"pos":ms} */
    private fun play(o: JSONObject): JSONObject {
        val kid = Profiles.active(app).kid
        val entries = LiveLineup.entries(app)
        val (items, index) = when (o.optString("kind")) {
            "live" -> {
                val id = o.optLong("id")
                val cat = o.optString("cat").ifEmpty { entries.firstOrNull { it.kind == SearchEntry.KIND_LIVE && it.id == id }?.categoryId.orEmpty() }
                var list = LiveLineup.channels(app, cat)
                if (list.none { it.id == id }) {
                    val e = entries.firstOrNull { it.kind == SearchEntry.KIND_LIVE && it.id == id }
                        ?: return JSONObject().put("error", "Sender gibt es auf diesem Fernseher nicht")
                    list = listOf(LiveChannel(e.id, e.name, e.image, e.categoryId))
                }
                list.map { PlayItem.live(it) } to list.indexOfFirst { it.id == id }.coerceAtLeast(0)
            }
            "movie" -> {
                if (kid) return JSONObject().put("error", "Am Fernseher ist gerade ein Kinderprofil aktiv")
                val e = entries.firstOrNull { it.kind == SearchEntry.KIND_MOVIE && it.id == o.optLong("id") }
                    ?: return JSONObject().put("error", "Film gibt es auf diesem Fernseher nicht")
                listOf(PlayItem.movie(VodItem(e.id, e.name, e.image, e.added, e.ext, e.categoryId))) to 0
            }
            "episode" -> {
                if (kid) return JSONObject().put("error", "Am Fernseher ist gerade ein Kinderprofil aktiv")
                val sid = o.optLong("series")
                val name = entries.firstOrNull { it.kind == SearchEntry.KIND_SERIES && it.id == sid }?.name
                    ?: return JSONObject().put("error", "Serie gibt es auf diesem Fernseher nicht")
                val info = runCatching { kotlinx.coroutines.runBlocking { titles.loadSeries(sid, name) } }.getOrNull()
                    ?: return JSONObject().put("error", "Folgen konnten nicht geladen werden")
                Playback.episodePlaylist(info, o.optLong("ep")) to 0
            }
            else -> return JSONObject().put("error", "Unbekannt")
        }
        if (items.isEmpty()) return JSONObject().put("error", "Nichts zum Abspielen")
        // continue where the phone stopped – the household sync may not have arrived yet
        val pos = o.optLong("pos")
        val first = items[index]
        if (pos > 10_000 && first.kind != PlayItem.KIND_LIVE) {
            val store = ProgressStore(app)
            if ((store.get(first.key)?.positionMs ?: 0L) < pos) store.save(first, pos, o.optLong("dur"))
        }
        main.post { runCatching { Playback.start(app, items, index) } }
        return JSONObject().put("ok", true).put("title", first.title)
    }

    /** Keys go to our own screen in front (player or launcher); Home and volume work everywhere. */
    private fun key(k: String): JSONObject {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (k) {
            "volup" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "voldown" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "home" -> main.post {
                runCatching {
                    app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            else -> {
                val code = KEYS[k] ?: return JSONObject().put("error", "Unbekannte Taste")
                val a = resumed?.get() ?: return JSONObject().put("error", "Am Fernseher ist gerade eine andere App vorne")
                main.post {
                    val t = SystemClock.uptimeMillis()
                    a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                    a.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
                }
            }
        }
        return JSONObject().put("ok", true)
    }

    private val KEYS = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK, "menu" to KeyEvent.KEYCODE_MENU,
        "playpause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "rew" to KeyEvent.KEYCODE_MEDIA_REWIND, "ff" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "chup" to KeyEvent.KEYCODE_CHANNEL_UP, "chdown" to KeyEvent.KEYCODE_CHANNEL_DOWN, "last" to KeyEvent.KEYCODE_LAST_CHANNEL,
    )

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block).getOrNull(); done.countDown() }
        done.await(2, TimeUnit.SECONDS)
        return result
    }
}
