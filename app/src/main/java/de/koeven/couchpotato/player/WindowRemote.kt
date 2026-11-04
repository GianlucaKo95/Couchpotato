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
            method == "GET" && path == "/categories" -> respond(c, "application/json", categories().toString())
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
            .put("percent", s.percent).put("canGrow", s.canGrow).put("canShrink", s.canShrink)
    }

    private fun entries(): List<SearchEntry> = catalogue ?: runCatching {
        kotlinx.coroutines.runBlocking { SearchIndex(app).load() }.filter { it.kind == SearchEntry.KIND_LIVE }
    }.getOrDefault(emptyList()).also { if (catalogue == null) catalogue = it }

    /** Favourites first, then the visible live categories in the own order of the Mediathek. */
    private fun categories(): JSONArray {
        val settings = SettingsStore(app)
        val hidden = settings.hiddenCategories()
        val all = SearchIndex(app).loadCategories()[SearchEntry.KIND_LIVE].orEmpty()
        val visible = de.koeven.couchpotato.ui.sortByOwnOrder(all, settings.categoryOrder(SearchEntry.KIND_LIVE))
            .filterNot { "${SearchEntry.KIND_LIVE}:${it.id}" in hidden }
        val arr = JSONArray()
        if (settings.favorites().isNotEmpty()) arr.put(JSONObject().put("id", FAVS).put("name", "★ Favoriten"))
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
        val play: (() -> Unit)? = if (p["a"] == "play") {
            val list = channelList(p["cat"].orEmpty())
            val i = list.indexOfFirst { it.id.toString() == p["id"] }
            if (i < 0) null else ({ FloatingPlayer.playList(list.map { PlayItem.live(it) }, i) })
        } else null
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
                "play" -> play?.invoke()
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
main{max-width:520px;margin:0 auto;padding:18px 14px 40px}
.k{color:#E8B86D;font-weight:700;font-size:13px;letter-spacing:.06em;text-transform:uppercase}
h1{font-size:22px;margin:2px 0 14px;min-height:1.3em}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:8px}
button{border:0;border-radius:14px;min-height:52px;font:inherit;font-weight:700;background:#165A61;color:#F4F1EC}
button.a{background:#E8B86D;color:#1B1408}button:disabled{opacity:.35}
.w2{grid-column:span 2}
select{width:100%;margin:16px 0 10px;padding:13px;border-radius:12px;border:0;font:inherit;background:#F4F1EC;color:#1B1408}
#list button{display:flex;align-items:center;gap:12px;width:100%;margin-bottom:6px;padding:8px 12px;text-align:left;font-weight:600;background:#0B3236}
#list img{width:56px;height:32px;object-fit:contain;background:#165A61;border-radius:6px}
#off{display:none;padding:30px 0;color:#C3D6D7}
</style></head><body><main>
<div class="k">Kleines Fenster</div><h1 id="t">…</h1>
<div id="off">Das Fenster ist gerade geschlossen. Starte es im Live-Player über ≡ → „Als kleines Fenster weiterlaufen“.</div>
<div id="ctl">
<div class="g"><button class="a w2" onclick="cmd('prev')">◀ Sender</button><button class="a w2" onclick="cmd('next')">Sender ▶</button></div>
<div class="g"><button id="mute" class="w2" onclick="cmd('mute')">Ton</button><button id="sm" onclick="cmd('smaller')">−</button><button id="bg" onclick="cmd('bigger')">+</button></div>
<div class="g"><button class="w2" onclick="cmd('corner')">Ecke</button><button onclick="cmd('full')">Vollbild</button><button onclick="cmd('close')">✕</button></div>
<select id="cat" onchange="load(this.value)"></select><div id="list"></div>
</div></main><script>
function show(s){
document.getElementById('off').style.display=s.active?'none':'block';
document.getElementById('ctl').style.display=s.active?'block':'none';
document.getElementById('t').textContent=s.active?s.title:'';
document.getElementById('mute').textContent=s.muted?'🔇 Ton an':'🔈 Ton aus';
document.getElementById('sm').disabled=!s.canShrink;document.getElementById('bg').disabled=!s.canGrow;
document.getElementById('bg').textContent='+ ('+s.percent+' %)';}
function cmd(a,extra){var b=new URLSearchParams(Object.assign({a:a},extra||{}));
fetch('cmd',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json();}).then(show).catch(function(){});}
function poll(){fetch('state').then(function(r){return r.json();}).then(show).catch(function(){show({active:false});});}
function load(cat){try{localStorage.setItem('cat',cat);}catch(e){}
fetch('channels?cat='+encodeURIComponent(cat)).then(function(r){return r.json();}).then(function(l){
var box=document.getElementById('list');box.innerHTML='';
l.forEach(function(ch){var b=document.createElement('button');
if(ch.logo){var i=document.createElement('img');i.src=ch.logo;i.loading='lazy';i.onerror=function(){i.style.visibility='hidden';};b.appendChild(i);}
b.appendChild(document.createTextNode(ch.name));b.onclick=function(){cmd('play',{cat:cat,id:ch.id});};box.appendChild(b);});});}
fetch('categories').then(function(r){return r.json();}).then(function(cats){
var sel=document.getElementById('cat');cats.forEach(function(c){var o=document.createElement('option');o.value=c.id;o.textContent=c.name;sel.appendChild(o);});
var last=null;try{last=localStorage.getItem('cat');}catch(e){}
if(last&&cats.some(function(c){return c.id==last;}))sel.value=last;if(sel.value)load(sel.value);});
poll();setInterval(poll,3000);
</script></body></html>"""
}
