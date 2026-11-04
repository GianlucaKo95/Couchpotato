package de.koeven.couchpotato.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SearchIndex
import de.koeven.couchpotato.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * The phone as remote for the small window: a little web page on the TV (same Wi-Fi only),
 * running while the window is open. Always the same address, so it can stay a bookmark /
 * home-screen icon on the phone. It only steers the window – no logins, no settings.
 */
internal object WindowRemote {
    /** The catalogue the launcher has in memory (set when it is loaded) – no second copy. */
    @Volatile var catalogue: List<SearchEntry>? = null

    @Volatile private var server: ServerSocket? = null
    @Volatile var url: String? = null; private set
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    fun start(context: Context) {
        if (server != null) return
        app = context.applicationContext
        val ip = localIp() ?: return
        // fixed ports: the bookmark on the phone keeps working
        val socket = (8780..8785).firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return
        server = socket
        url = "http://$ip:${socket.localPort}/"
        Thread({ serve(socket) }, "windowremote").apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        url = null
    }

    fun qr(): Bitmap? {
        val text = url ?: return null
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        return bmp
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            Thread { runCatching { handle(client) }; runCatching { client.close() } }.apply { isDaemon = true; start() }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 10_000
        val input = BufferedInputStream(c.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > 8_000) return
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val (method, target) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val path = target.substringBefore('?')
        val query = params(target.substringAfter('?', ""))
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        when {
            method == "GET" && path == "/" -> respond(c, "text/html; charset=utf-8", PAGE)
            method == "GET" && path == "/state" -> respond(c, "application/json", state().toString())
            method == "GET" && path == "/categories" -> respond(c, "application/json", categories(kindOf(query["kind"])).toString())
            method == "GET" && path == "/titles" -> respond(c, "application/json", titles(kindOf(query["kind"]), query["cat"].orEmpty()).toString())
            method == "GET" && path == "/series" -> respond(c, "application/json", series(query["id"]?.toLongOrNull() ?: 0L).toString())
            method == "GET" && path == "/channels" -> respond(c, "application/json", channels(query["cat"].orEmpty()).toString())
            method == "POST" && path == "/cmd" -> {
                val body = ByteArray(length.coerceAtMost(2_000)).also { var off = 0; while (off < it.size) { val n = input.read(it, off, it.size - off); if (n < 0) break; off += n } }
                command(params(String(body, Charsets.UTF_8)))
                respond(c, "application/json", state().toString())
            }
            else -> respond(c, "text/plain", "", 404)
        }
    }

    private fun params(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
        val (k, v) = (it.split('=', limit = 2) + "").let { p -> p[0] to p[1] }
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun state(): JSONObject {
        val s = FloatingPlayer.state.value
        return JSONObject().put("active", s.active).put("title", s.title).put("muted", s.muted)
            .put("percent", s.percent).put("canGrow", s.canGrow).put("canShrink", s.canShrink).put("minimized", s.minimized)
            .put("vod", s.vod).put("paused", s.paused).put("hasNext", s.hasNext).put("kid", kid())
    }

    private fun entries(): List<SearchEntry> = catalogue ?: runCatching {
        kotlinx.coroutines.runBlocking { SearchIndex(app).load() }
    }.getOrDefault(emptyList()).also { if (catalogue == null && it.isNotEmpty()) catalogue = it }

    /** Films or series of a category, newest first. */
    private fun titles(kind: Char, cat: String): JSONArray = JSONArray(
        entries().asSequence().filter { it.kind == kind && it.categoryId == cat }
            .distinctBy { it.id }.sortedByDescending { it.added }.take(400)
            .map { JSONObject().put("id", it.id).put("name", it.name).put("image", it.image ?: JSONObject.NULL) }.toList()
    )

    private val seriesRepo by lazy {
        de.koeven.couchpotato.data.TitleInfoRepository(app, SettingsStore(app)) { entries() }
    }

    /** Seasons and episodes – from the stick, or ONE throttled request the first time (like on the TV). */
    private fun loadSeries(id: Long): de.koeven.couchpotato.data.SeriesInfo? {
        val name = entries().firstOrNull { it.kind == SearchEntry.KIND_SERIES && it.id == id }?.name ?: return null
        return runCatching { kotlinx.coroutines.runBlocking { seriesRepo.loadSeries(id, name) } }.getOrNull()
    }

    private fun series(id: Long): JSONObject {
        if (kid()) return JSONObject().put("error", "Im Kinderprofil nur Live-TV")
        val info = loadSeries(id) ?: return JSONObject().put("error", "Folgen konnten nicht geladen werden")
        val progress = de.koeven.couchpotato.data.ProgressStore(app).forSeries(id)?.item?.streamId
        return JSONObject().put("name", info.name).put("resume", progress ?: JSONObject.NULL).put("episodes", JSONArray(info.episodes.map {
            JSONObject().put("id", it.id).put("season", it.season).put("number", it.number).put("title", it.title)
        }))
    }

    private fun kid() = de.koeven.couchpotato.data.Profiles.active(app).kid

    /** "l" / "m" / "s" from the page; kids profiles only get live TV here (no FSK check on the phone). */
    private fun kindOf(k: String?): Char = when (k) {
        "m" -> SearchEntry.KIND_MOVIE
        "s" -> SearchEntry.KIND_SERIES
        else -> SearchEntry.KIND_LIVE
    }.let { if (it != SearchEntry.KIND_LIVE && kid()) SearchEntry.KIND_LIVE else it }

    /** The visible categories in the own order of the Mediathek; live TV starts with the favourites. */
    private fun categories(kind: Char): JSONArray {
        val settings = SettingsStore(app)
        val hidden = settings.hiddenCategories()
        val all = SearchIndex(app).loadCategories()[kind].orEmpty()
        val visible = de.koeven.couchpotato.ui.sortByOwnOrder(all, settings.categoryOrder(kind))
            .filterNot { "$kind:${it.id}" in hidden }
        val arr = JSONArray()
        if (kind == SearchEntry.KIND_LIVE && settings.favorites().isNotEmpty()) arr.put(JSONObject().put("id", FAVS).put("name", "★ Favoriten"))
        visible.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        return arr
    }

    private fun channelList(cat: String): List<LiveChannel> =
        if (cat == FAVS) SettingsStore(app).favorites()
        else entries().asSequence().filter { it.kind == SearchEntry.KIND_LIVE && it.categoryId == cat }
            .map { LiveChannel(it.id, it.name, it.image, it.categoryId) }.distinctBy { it.id }.toList()

    private fun channels(cat: String): JSONArray = JSONArray(channelList(cat).map {
        JSONObject().put("id", it.id).put("name", it.name).put("logo", it.logo ?: JSONObject.NULL)
    })

    private fun command(p: Map<String, String>) {
        // prepared here (may read the catalogue or ask the provider once), played on the main thread
        val play: (() -> Unit)? = when (p["a"]) {
            "play" -> {
                val list = channelList(p["cat"].orEmpty())
                val i = list.indexOfFirst { it.id.toString() == p["id"] }
                if (i < 0) null else ({ FloatingPlayer.playList(list.map { PlayItem.live(it) }, i) })
            }
            "movie" -> if (kid()) null else entries().firstOrNull { it.kind == SearchEntry.KIND_MOVIE && it.id.toString() == p["id"] }?.let { e ->
                val item = PlayItem.movie(de.koeven.couchpotato.data.VodItem(e.id, e.name, e.image, e.added, e.ext, e.categoryId))
                ({ FloatingPlayer.playList(listOf(item), 0) })
            }
            "episode" -> if (kid()) null else loadSeries(p["series"]?.toLongOrNull() ?: 0L)?.let { info ->
                val list = Playback.episodePlaylist(info, p["ep"]?.toLongOrNull() ?: 0L)
                ({ FloatingPlayer.playList(list, 0) })
            }
            else -> null
        }
        // the player and the window live on the main thread
        main.post {
            when (p["a"]) {
                "next" -> FloatingPlayer.next()
                "prev" -> FloatingPlayer.previous()
                "mute" -> FloatingPlayer.toggleSound()
                "bigger" -> FloatingPlayer.resize(+1)
                "smaller" -> FloatingPlayer.resize(-1)
                "corner" -> FloatingPlayer.nextCorner()
                "full" -> FloatingPlayer.fullscreen()
                "close" -> FloatingPlayer.stop(app)
                "min" -> FloatingPlayer.minimize()
                "restore" -> FloatingPlayer.restore()
                "play", "movie", "episode" -> play?.invoke()
                "back" -> FloatingPlayer.seekBy(-30_000)
                "fwd" -> FloatingPlayer.seekBy(30_000)
                "pause" -> FloatingPlayer.pauseToggle()
            }
        }
        Thread.sleep(150)   // so the answer already shows the new state
    }

    private fun respond(c: Socket, type: String, body: String, code: Int = 200) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private const val FAVS = "_favs"

    private val PAGE = """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta name="apple-mobile-web-app-capable" content="yes"><meta name="theme-color" content="#0E3E44">
<title>Couchpotato Fenster</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#0E3E44;color:#F4F1EC;font:16px/1.35 system-ui,sans-serif}
main{max-width:560px;margin:0 auto;padding:18px 14px 40px}
.k{color:#E8B86D;font-weight:700;font-size:13px;letter-spacing:.06em;text-transform:uppercase}
h1{font-size:21px;margin:2px 0 14px;min-height:1.3em}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:8px}
button{border:0;border-radius:14px;min-height:50px;font:inherit;font-weight:700;background:#165A61;color:#F4F1EC}
button.a{background:#E8B86D;color:#1B1408}button:disabled{opacity:.35}
.w2{grid-column:span 2}.w4{grid-column:span 4}
.tabs{display:flex;gap:6px;margin:18px 0 0}.tabs button{flex:1;min-height:44px;border-radius:999px}
.tabs button.on{background:#E8B86D;color:#1B1408}
select{width:100%;margin:12px 0 10px;padding:13px;border-radius:12px;border:0;font:inherit;background:#F4F1EC;color:#1B1408}
.row{display:flex;align-items:center;gap:12px;width:100%;margin-bottom:6px;padding:8px 12px;text-align:left;font-weight:600;background:#0B3236}
.row img{width:56px;height:32px;object-fit:contain;background:#165A61;border-radius:6px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.tile{padding:0;min-height:0;background:#0B3236;border-radius:10px;overflow:hidden;text-align:left;font-weight:600;font-size:13px}
.tile img{display:block;width:100%;aspect-ratio:2/3;object-fit:cover;background:#165A61}
.tile span{display:block;padding:6px 7px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ep{display:block;width:100%;margin-bottom:6px;padding:12px;text-align:left;font-weight:600;background:#0B3236}
.ep.cur{outline:2px solid #E8B86D}
#off,#msg{display:none;padding:24px 0;color:#C3D6D7}
</style></head><body><main>
<div class="k">Kleines Fenster</div><h1 id="t">…</h1>
<div id="off">Das Fenster ist gerade geschlossen. Starte es im Live-Player über ≡ → „Als kleines Fenster weiterlaufen“.</div>
<div id="ctl">
<div class="g" id="live"><button class="a w2" onclick="cmd('prev')">◀ Sender</button><button class="a w2" onclick="cmd('next')">Sender ▶</button></div>
<div class="g" id="vod"><button class="a" onclick="cmd('back')">⏪ 30</button><button id="pp" class="a w2" onclick="cmd('pause')">⏸ Pause</button><button class="a" onclick="cmd('fwd')">30 ⏩</button>
<button id="nx" class="w4" onclick="cmd('next')">Nächste Folge ▶</button></div>
<div class="g"><button id="mute" class="w2" onclick="cmd('mute')">Ton</button><button id="sm" onclick="cmd('smaller')">−</button><button id="bg" onclick="cmd('bigger')">+</button></div>
<div class="g"><button class="w2" onclick="cmd('corner')">Ecke</button><button class="w2" onclick="cmd('full')">Vollbild</button></div>
<div class="g"><button id="min" class="w2" onclick="cmd(minimized?'restore':'min')">Minimieren</button><button class="w2" onclick="cmd('close')">✕ Schließen</button></div>
<div class="tabs" id="tabs"><button data-k="l" onclick="setKind('l')">Live-TV</button><button data-k="m" onclick="setKind('m')">Filme</button><button data-k="s" onclick="setKind('s')">Serien</button></div>
<div id="browse"><select id="cat" onchange="loadList(this.value)"></select><div id="list"></div></div>
<div id="eps" style="display:none"><button class="row" onclick="closeSeries()">← Zurück</button><h1 id="sn"></h1><select id="season" onchange="showSeason(this.value)"></select><div id="eplist"></div></div>
<div id="msg"></div>
</div></main><script>
var minimized=false,kind='l',eps=[],resume=null,curSeries=null;
function get(k,d){try{return localStorage.getItem(k)||d;}catch(e){return d;}}
function put(k,v){try{localStorage.setItem(k,v);}catch(e){}}
function el(id){return document.getElementById(id);}
function show(s){minimized=!!s.minimized;
el('off').style.display=s.active?'none':'block';el('ctl').style.display=s.active?'block':'none';
el('t').textContent=s.active?s.title:'';
el('live').style.display=s.vod?'none':'grid';el('vod').style.display=s.vod?'grid':'none';
el('nx').style.display=s.hasNext?'block':'none';el('pp').textContent=s.paused?'▶ Weiter':'⏸ Pause';
el('mute').textContent=s.muted?'🔇 Ton an':'🔈 Ton aus';
el('sm').disabled=!s.canShrink;el('bg').disabled=!s.canGrow;el('bg').textContent='+ ('+s.percent+' %)';
el('min').textContent=minimized?'⬆ Einblenden':'⬇ Minimieren';el('min').className=minimized?'w2 a':'w2';
document.querySelectorAll('#tabs button').forEach(function(b){b.style.display=(s.kid&&b.dataset.k!='l')?'none':'';});
if(s.kid&&kind!='l')setKind('l');}
function cmd(a,extra){var b=new URLSearchParams(Object.assign({a:a},extra||{}));
fetch('cmd',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json();}).then(show).catch(function(){});}
function poll(){fetch('state').then(function(r){return r.json();}).then(show).catch(function(){show({active:false});});}
function setKind(k){kind=k;put('kind',k);closeSeries();
document.querySelectorAll('#tabs button').forEach(function(b){b.className=b.dataset.k==k?'on':'';});
var sel=el('cat');sel.innerHTML='';el('list').innerHTML='';
fetch('categories?kind='+k).then(function(r){return r.json();}).then(function(cats){
cats.forEach(function(c){var o=document.createElement('option');o.value=c.id;o.textContent=c.name;sel.appendChild(o);});
var last=get('cat_'+k,null);if(last&&cats.some(function(c){return c.id==last;}))sel.value=last;if(sel.value)loadList(sel.value);});}
function loadList(cat){put('cat_'+kind,cat);var box=el('list');box.innerHTML='';
if(kind=='l'){fetch('channels?cat='+encodeURIComponent(cat)).then(function(r){return r.json();}).then(function(l){box.className='';
l.forEach(function(ch){var b=document.createElement('button');b.className='row';
if(ch.logo){var i=document.createElement('img');i.src=ch.logo;i.loading='lazy';i.onerror=function(){i.style.visibility='hidden';};b.appendChild(i);}
b.appendChild(document.createTextNode(ch.name));b.onclick=function(){cmd('play',{cat:cat,id:ch.id});};box.appendChild(b);});});return;}
fetch('titles?kind='+kind+'&cat='+encodeURIComponent(cat)).then(function(r){return r.json();}).then(function(l){box.className='grid';
l.forEach(function(t){var b=document.createElement('button');b.className='tile';
var i=document.createElement('img');i.loading='lazy';if(t.image)i.src=t.image;i.onerror=function(){i.removeAttribute('src');};b.appendChild(i);
var s=document.createElement('span');s.textContent=t.name;b.appendChild(s);
b.onclick=function(){if(kind=='m')cmd('movie',{id:t.id});else openSeries(t.id);};box.appendChild(b);});});}
function openSeries(id){el('msg').style.display='block';el('msg').textContent='Lade Folgen …';
fetch('series?id='+id).then(function(r){return r.json();}).then(function(s){
if(s.error){el('msg').textContent=s.error;return;}el('msg').style.display='none';
curSeries=id;eps=s.episodes;resume=s.resume;el('sn').textContent=s.name;
var seasons=[];eps.forEach(function(e){if(seasons.indexOf(e.season)<0)seasons.push(e.season);});
var sel=el('season');sel.innerHTML='';seasons.forEach(function(n){var o=document.createElement('option');o.value=n;o.textContent='Staffel '+n;sel.appendChild(o);});
var r=eps.filter(function(e){return e.id==resume;})[0];sel.value=r?r.season:seasons[0];
el('browse').style.display='none';el('eps').style.display='block';showSeason(sel.value);window.scrollTo(0,0);});}
function showSeason(n){var box=el('eplist');box.innerHTML='';
eps.filter(function(e){return e.season==n;}).forEach(function(e){var b=document.createElement('button');b.className='ep'+(e.id==resume?' cur':'');
b.textContent='Folge '+e.number+' · '+e.title;b.onclick=function(){cmd('episode',{series:curSeries,ep:e.id});};box.appendChild(b);});}
function closeSeries(){el('eps').style.display='none';el('browse').style.display='block';el('msg').style.display='none';}
setKind(get('kind','l'));poll();setInterval(poll,3000);
</script></body></html>"""
}
