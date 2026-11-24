package de.koeven.couchpotato.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.koeven.couchpotato.data.AppsRepository
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.ProgressStore
import de.koeven.couchpotato.data.ScreenTime
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.TitleInfoRepository
import de.koeven.couchpotato.data.Tmdb
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.ui.Discovery
import de.koeven.couchpotato.ui.Notice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Link to the Hausgeist (the family's home assistant): "Spiel Pokémon im Kinderzimmer".
 *
 * The TV connects OUT to the Hausgeist and fetches commands by long-poll – no open port on the stick,
 * works through the Hausgeist's normal HTTPS address. Pairing: the TV shows a 6-digit code, an
 * admin types it into the Hausgeist app. Kids rules are enforced HERE: when the TV is in a kids
 * profile or the request comes from a child, only released categories/apps and no streaming search.
 */
object HausgeistLink {
    const val DEFAULT_URL = "https://homegeist.heimdns.de"
    private const val PREFS = "hausgeist"

    sealed interface Status {
        data object Off : Status
        data class Pairing(val code: String, val url: String) : Status
        data class Connected(val name: String) : Status
        data class Trouble(val name: String, val message: String) : Status
    }

    val status = MutableStateFlow<Status>(Status.Off)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loop: Thread? = null
    @Volatile private var pairing: Thread? = null

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun url(): String = prefs().getString("url", null) ?: DEFAULT_URL
    private fun token(): String? = prefs().getString("token", null)
    fun pairedName(): String? = prefs().getString("name", null)

    fun install(context: Context) {
        app = context.applicationContext
        if (token() != null) startLoop()
    }

    // ---------------- pairing ----------------

    /** Asks the Hausgeist for a code and waits (max. 10 minutes) until an admin confirms it in the app. */
    fun pair(baseUrl: String) {
        val base = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_URL }.let { if (it.startsWith("http")) it else "https://$it" }
        pairing?.interrupt()
        pairing = Thread({
            try {
                val name = runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull() ?: Build.MODEL
                val r = http("POST", "$base/api/tv/pair", JSONObject().put("name", name).put("model", Build.MODEL), auth = null)
                val id = r.getString("pair_id")
                status.value = Status.Pairing(r.getString("code"), base)
                val until = System.currentTimeMillis() + 10 * 60_000L
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(3_000)
                    val p = http("GET", "$base/api/tv/pair/$id", null, auth = null)
                    when (p.optString("status")) {
                        "done" -> {
                            prefs().edit().putString("url", base).putString("token", p.getString("token")).putString("name", p.optString("name")).apply()
                            status.value = Status.Connected(p.optString("name"))
                            startLoop()
                            return@Thread
                        }
                        "expired" -> break
                    }
                }
                status.value = Status.Trouble("", "Code abgelaufen – bitte neu koppeln")
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                status.value = Status.Trouble("", "Hausgeist nicht erreichbar: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "hausgeist-pair").apply { isDaemon = true; start() }
    }

    fun unpair() {
        pairing?.interrupt()
        prefs().edit().remove("token").remove("name").apply()
        loop?.interrupt()
        loop = null
        status.value = Status.Off
    }

    // ---------------- command loop ----------------

    private fun startLoop() {
        if (loop?.isAlive == true) return
        loop = Thread({
            var backoff = 5_000L
            while (!Thread.currentThread().isInterrupted) {
                val token = token() ?: break
                try {
                    val cmd = http("GET", "${url()}/api/tv/next?wait=25", null, auth = token, readTimeoutMs = 40_000)
                    status.value = Status.Connected(cmd.optString("name").ifEmpty { pairedName().orEmpty() })
                    backoff = 5_000L
                    if (cmd.isNull("id")) continue
                    val id = cmd.getString("id")
                    val reply = JSONObject().put("id", id)
                    try {
                        reply.put("result", run(cmd.getString("cmd"), cmd.optJSONObject("args") ?: JSONObject()))
                    } catch (e: Refused) {
                        reply.put("error", e.message)
                    } catch (e: Exception) {
                        reply.put("error", "Fehler am Fernseher: ${e.message ?: e.javaClass.simpleName}")
                    }
                    runCatching { http("POST", "${url()}/api/tv/result", reply, auth = token) }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Unauthorized) {
                    prefs().edit().remove("token").apply()
                    status.value = Status.Trouble(pairedName().orEmpty(), "Kopplung wurde im Hausgeist entfernt")
                    break
                } catch (e: Exception) {
                    status.value = Status.Trouble(pairedName().orEmpty(), "Keine Verbindung zum Hausgeist")
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                }
            }
        }, "hausgeist-link").apply { isDaemon = true; start() }
    }

    private class Refused(msg: String) : Exception(msg)
    private class Unauthorized : Exception("401")

    private fun http(method: String, url: String, body: JSONObject?, auth: String?, readTimeoutMs: Int = 15_000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = readTimeoutMs
        auth?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        if (code == 401 && auth != null) throw Unauthorized()
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifEmpty { null } ?: "HTTP $code")
        return JSONObject(text.ifEmpty { "{}" })
    }

    // ---------------- commands ----------------

    private val titles by lazy { TitleInfoRepository(app, SettingsStore(app)) { LiveLineup.entries(app) } }

    private fun kidMode(args: JSONObject) = Profiles.active(app).kid || args.optBoolean("as_kid")

    private fun kidAllowed(e: SearchEntry): Boolean =
        e.categoryKey in Profiles.kidCategories(app) || "${e.kind}:${e.id}" in Profiles.unlocked(app)

    private fun checkScreenTime() {
        when (val s = ScreenTime.state(app)) {
            is ScreenTime.State.Bedtime -> throw Refused("Im Kinderprofil ist ab ${s.at} Uhr Schlafenszeit")
            is ScreenTime.State.LimitReached -> throw Refused("Die Bildschirmzeit für heute ist aufgebraucht")
            else -> {}
        }
    }

    private fun run(cmd: String, args: JSONObject): Any = when (cmd) {
        "state" -> TvLink.state().put("profil", Profiles.active(app).name).put("kinderprofil", Profiles.active(app).kid)
        "search" -> search(args.optString("query"), kidMode(args))
        "play" -> play(args.optString("kind"), args.optLong("id"), kidMode(args))
        "open_app" -> openApp(args.optString("app"), args.optString("query").takeIf { it.isNotBlank() }, kidMode(args))
        "show_search" -> showSearch(args.optString("query"))
        "key" -> TvLink.key(args.optString("k")).also { if (it.has("error")) throw Refused(it.getString("error")) }
        "message" -> message(args.optString("text"))
        else -> throw Refused("Unbekannter Befehl")
    }

    private fun entryJson(e: SearchEntry): JSONObject {
        val o = JSONObject().put("kind", when (e.kind) { SearchEntry.KIND_MOVIE -> "movie"; SearchEntry.KIND_SERIES -> "series"; else -> "live" })
            .put("id", e.id).put("name", e.name)
        e.year?.let { o.put("year", it) }
        if (e.kind == SearchEntry.KIND_SERIES) {
            ProgressStore(app).forSeries(e.id)?.let { p -> o.put("weiter_bei", p.item.subtitle ?: p.item.title) }
        }
        return o
    }

    private fun search(query: String, kid: Boolean): JSONObject {
        if (query.trim().length < 2) throw Refused("Suchbegriff zu kurz")
        val entries = LiveLineup.entries(app)
        if (entries.isEmpty()) throw Refused("Die Mediathek ist auf diesem Fernseher noch nicht geladen")
        val res = SearchIndex.search(entries, query, SettingsStore(app).hiddenCategories(), limit = 20)
        fun pick(list: List<SearchEntry>) = JSONArray(list.filter { !kid || kidAllowed(it) }.take(6).map(::entryJson))
        val out = JSONObject().put("serien", pick(res.series)).put("filme", pick(res.movies)).put("sender", pick(res.live))
        if (kid) {
            out.put("hinweis", "Kinderprofil: nur freigegebene Inhalte, keine Streaming-Dienste")
        } else {
            val discovery = Discovery(app, Tmdb(app), apps = { AppsRepository.load(app) }, catalogue = { entries }, hidden = { SettingsStore(app).hiddenCategories() })
            val streams = runCatching { runBlocking { discovery.streamingSearch(query) } }.getOrDefault(emptyList())
            out.put("streaming", JSONArray(streams.take(5).map { s ->
                JSONObject().put("titel", s.hit.title).put("jahr", s.hit.year).put("serie", s.hit.tv)
                    .put("apps", JSONArray(s.apps.map { it.label }))
            }))
        }
        return out
    }

    private fun play(kind: String, id: Long, kid: Boolean): JSONObject {
        if (kid) checkScreenTime()
        val entries = LiveLineup.entries(app)
        val k = when (kind) { "movie" -> SearchEntry.KIND_MOVIE; "series" -> SearchEntry.KIND_SERIES; "live" -> SearchEntry.KIND_LIVE; else -> throw Refused("Unbekannte Art") }
        val e = entries.firstOrNull { it.kind == k && it.id == id } ?: throw Refused("Gibt es auf diesem Fernseher nicht")
        if (kid && !kidAllowed(e)) throw Refused("„${e.name}“ ist im Kinderprofil nicht freigegeben")
        val (items, index, note) = when (k) {
            SearchEntry.KIND_MOVIE -> Triple(listOf(PlayItem.movie(VodItem(e.id, e.name, e.image, e.added, e.ext, e.categoryId))), 0, null)
            SearchEntry.KIND_SERIES -> {
                val info = runBlocking { titles.loadSeries(e.id, e.name) }
                if (info.episodes.isEmpty()) throw Refused("Keine Folgen gefunden")
                val last = ProgressStore(app).forSeries(e.id)
                val from = last?.item?.streamId ?: info.episodes.first().id
                Triple(Playback.episodePlaylist(info, from), 0, if (last != null) "weiter bei ${last.item.subtitle ?: last.item.title}" else "ab Folge 1")
            }
            else -> {
                var list = LiveLineup.channels(app, e.categoryId.orEmpty())
                if (list.none { it.id == e.id }) list = listOf(LiveChannel(e.id, e.name, e.image, e.categoryId))
                Triple(list.map { PlayItem.live(it) }, list.indexOfFirst { it.id == e.id }.coerceAtLeast(0), null)
            }
        }
        main.post { runCatching { Playback.start(app, items, index) } }
        return JSONObject().put("ok", true).put("titel", e.name).apply { note?.let { put("info", it) } }
    }

    private fun openApp(name: String, query: String?, kid: Boolean): JSONObject {
        if (kid) checkScreenTime()
        val n = SearchIndex.normalize(name.replace("+", " plus"))
        val apps = AppsRepository.load(app)
        val a = apps.firstOrNull { it.packageName == name }
            ?: apps.firstOrNull { SearchIndex.normalize(it.label.replace("+", " plus")) == n }
            ?: apps.firstOrNull { SearchIndex.normalize(it.label.replace("+", " plus")).contains(n) }
            ?: throw Refused("App „$name“ ist auf diesem Fernseher nicht installiert. Installiert: ${apps.take(25).joinToString { it.label }}")
        if (kid && a.packageName !in Profiles.kidApps(app)) throw Refused("${a.label} ist im Kinderprofil nicht freigegeben")
        SettingsStore(app).markLaunched(a.packageName)
        if (query == null) {
            main.post { AppsRepository.launch(app, a.packageName) }
            return JSONObject().put("ok", true).put("app", a.label)
        }
        val searchable = a.packageName in AppsRepository.searchablePackages(app)
        var handed = false
        val done = java.util.concurrent.CountDownLatch(1)
        main.post {
            handed = searchable && AppsRepository.searchInApp(app, a.packageName, query)
            if (!searchable) AppsRepository.launch(app, a.packageName)
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return JSONObject().put("ok", true).put("app", a.label).put("suchbegriff_uebernommen", handed)
            .put("info", if (handed) "Suche in ${a.label} geöffnet – dort noch den Titel auswählen" else "${a.label} geöffnet – nimmt keinen Suchbegriff an, bitte dort suchen")
    }

    private fun showSearch(query: String): JSONObject {
        main.post {
            runCatching {
                app.startActivity(
                    Intent(app, de.koeven.couchpotato.ui.MainActivity::class.java)
                        .putExtra(RemoteNav.EXTRA_SEARCH, query)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }
        return JSONObject().put("ok", true)
    }

    private fun message(text: String): JSONObject {
        main.post {
            if (TvLink.foreground() is de.koeven.couchpotato.ui.MainActivity) Notice.show(text, long = true)
            else repeat(2) { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        }
        return JSONObject().put("ok", true)
    }
}

/** Requests from outside (the Hausgeist) to open a screen in the launcher. */
object RemoteNav {
    const val EXTRA_SEARCH = "hausgeist_search"
    data class Search(val query: String, val id: Long = System.nanoTime())
    val search = MutableStateFlow<Search?>(null)
}
