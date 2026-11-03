package de.koeven.couchpotato.data

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Mit dem Handy eingeben": a tiny web page on the TV, reachable from phones in the same Wi-Fi.
 * Scan the QR code, paste the M3U link (or type anything) on the phone, send – it lands in the
 * TV's text field. Runs only while the dialog is open (10 minutes at most), needs the 4-digit
 * code shown on the TV (the QR code carries it), and stops after 5 wrong codes.
 */
object PhoneLink {
    data class Session(val url: String, val pin: String, val qr: Bitmap)
    data class Received(val text: String, val at: Long = System.nanoTime())

    val received = MutableStateFlow<Received?>(null)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var pin = ""
    @Volatile private var password = false
    @Volatile private var title = ""
    private var wrong = 0
    private var stopAt = 0L

    /** Starts the page (or restarts it with a new code). Null when the TV has no network. */
    fun start(fieldTitle: String, isPassword: Boolean): Session? {
        stop()
        val ip = localIp() ?: return null
        val socket = (8765..8775).firstNotNullOfOrNull { port -> runCatching { ServerSocket(port) }.getOrNull() } ?: return null
        server = socket
        pin = (SecureRandom().nextInt(9000) + 1000).toString()
        password = isPassword
        title = fieldTitle
        wrong = 0
        stopAt = System.currentTimeMillis() + 10 * 60_000L
        received.value = null
        Thread({ serve(socket) }, "phonelink").apply { isDaemon = true; start() }
        val url = "http://$ip:${socket.localPort}/"
        return Session(url, pin, qr("$url?p=$pin"))
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (System.currentTimeMillis() > stopAt) { runCatching { client.close() }; stop(); break }
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
        val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (method == "POST" && path.startsWith("/check")) {
            val body = ByteArray(length.coerceAtMost(200)).also { var off = 0; while (off < it.size) { val n = input.read(it, off, it.size - off); if (n < 0) break; off += n } }
            val given = String(body, Charsets.UTF_8).substringAfter("pin=", "").take(8)
            if (given != pin) {
                wrong++
                if (wrong >= 5) stop()
                return respond(c, 403, "text/plain; charset=utf-8", "Code falsch – er steht auf dem Fernseher.")
            }
            return respond(c, 200, "text/plain; charset=utf-8", "ok")
        }
        if (method == "POST" && path.startsWith("/send")) {
            if (length > 16_000) return respond(c, 413, "text/plain", "zu lang")
            val body = ByteArray(length).also { var off = 0; while (off < length) { val n = input.read(it, off, length - off); if (n < 0) break; off += n } }
            val form = String(body, Charsets.UTF_8).split('&').associate {
                val (k, v) = (it.split('=', limit = 2) + "").let { p -> p[0] to p[1] }
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
            if (form["pin"] != pin) {
                wrong++
                if (wrong >= 5) stop()
                return respond(c, 403, "text/plain; charset=utf-8", "Code falsch – er steht auf dem Fernseher.")
            }
            // live: every keystroke on the phone, so an empty field (all deleted) is fine too
            received.value = Received(form["text"].orEmpty())
            return respond(c, 200, "text/plain; charset=utf-8", "ok")
        }
        if (method == "GET") return respond(c, 200, "text/html; charset=utf-8", page())
        respond(c, 404, "text/plain", "")
    }

    private fun respond(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 413 -> "Too Large"; else -> "Not Found" }
        val out = c.getOutputStream()
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun page(): String {
        val esc = title.replace("&", "&amp;").replace("<", "&lt;")
        val field = if (password) """<input id="t" type="password" autocomplete="off">"""
            else """<textarea id="t" rows="4" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>"""
        return """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Couchpotato</title>
<style>
body{margin:0;background:#165A61;color:#F4F1EC;font:17px/1.4 system-ui,sans-serif}
main{max-width:520px;margin:0 auto;padding:28px 18px}
h1{font-size:24px;margin:0 0 4px}p{margin:0 0 18px;color:#C3D6D7}
label{display:block;font-weight:600;margin:14px 0 6px}
textarea,input{box-sizing:border-box;width:100%;border:0;border-radius:12px;padding:14px;font:inherit;background:#F4F1EC;color:#1B1408}
button{margin-top:18px;width:100%;min-height:52px;border:0;border-radius:999px;background:#E8B86D;color:#1B1408;font:inherit;font-weight:700}
#s,#ls{margin-top:14px;font-weight:600;min-height:1.4em}
</style></head><body><main>
<h1>Couchpotato</h1>
<section id="login"><p>Gib den Code ein, der auf dem Fernseher steht.</p>
<label for="p">Code vom Fernseher</label><input id="p" inputmode="numeric" maxlength="4" autocomplete="one-time-code">
<button id="l" type="button">Anmelden</button><div id="ls" role="status"></div></section>
<section id="form" style="display:none"><p>Eingabe für „$esc“ auf dem Fernseher</p>
<label for="t">Text oder Link einfügen</label>$field
<div id="s" role="status">Was du hier tippst, steht sofort auf dem Fernseher.</div></section>
</main><script>
function show(){document.getElementById('login').style.display='none';document.getElementById('form').style.display='block';document.getElementById('t').focus();}
var q=new URLSearchParams(location.search).get('p');
// opened via the QR code: it brings the code along – straight to the field
if(q){document.getElementById('p').value=q;show();}
document.getElementById('l').onclick=function(){
var ls=document.getElementById('ls');ls.textContent='Prüfe …';
fetch('/check',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({pin:document.getElementById('p').value})})
.then(function(r){return r.text().then(function(t){if(r.ok){ls.textContent='';show();}else ls.textContent=t;});})
.catch(function(){ls.textContent='Fernseher nicht erreichbar – gleiches WLAN?';});};
// live: send shortly after each keystroke (one request at a time, the newest text wins)
var timer=null,busy=false,again=false;
function push(){
if(busy){again=true;return;}busy=true;
var s=document.getElementById('s');
fetch('/send',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
body:new URLSearchParams({text:document.getElementById('t').value,pin:document.getElementById('p').value})})
.then(function(r){return r.text().then(function(t){s.textContent=r.ok?'✓ Live mit dem Fernseher verbunden – dort mit „Übernehmen“ bestätigen':t;});})
.catch(function(){s.textContent='Fernseher nicht erreichbar – gleiches WLAN?';})
.then(function(){busy=false;if(again){again=false;push();}});}
var t=document.getElementById('t');
t.addEventListener('input',function(){clearTimeout(timer);timer=setTimeout(push,200);});
// pasting: the whole field goes out at once, right away
t.addEventListener('paste',function(){clearTimeout(timer);setTimeout(push,30);});
t.addEventListener('change',function(){clearTimeout(timer);push();});
</script></body></html>"""
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        return bmp
    }
}
