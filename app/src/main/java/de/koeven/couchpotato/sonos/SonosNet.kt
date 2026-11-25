package de.koeven.couchpotato.sonos

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/*
 * Network side of the Sonos sound (plain Java networking, no Android): the speakers' local
 * control (SOAP over HTTP, port 1400, only inside the home network), finding them (SSDP) and the
 * small HTTP server the speakers fetch the player's sound from.
 */

/** Local control of one Sonos player. Blocking – call it off the main thread. */
class SonosControl(private val port: Int = 1400, private val timeoutMs: Int = 2_500) {

    fun call(host: String, path: String, service: String, action: String, args: List<Pair<String, String>>): String {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            c.outputStream.use { it.write(SonosXml.envelope(service, action, args).toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw IOException("$action: HTTP $code ${SonosXml.value(body, "errorCode") ?: ""}".trim())
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun avt(host: String, action: String, vararg args: Pair<String, String>) =
        call(host, "/MediaRenderer/AVTransport/Control", SonosXml.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    fun setUri(host: String, uri: String, meta: String) { avt(host, "SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to meta) }
    fun play(host: String) { avt(host, "Play", "Speed" to "1") }
    fun pause(host: String) { avt(host, "Pause") }
    fun stop(host: String) { avt(host, "Stop") }

    /** Leaves any group (the room plays on its own). */
    fun standalone(host: String) { avt(host, "BecomeCoordinatorOfStandaloneGroup") }

    /** The room follows [coordinatorUuid] (plays the same, in sync). */
    fun join(host: String, coordinatorUuid: String) = setUri(host, "x-rincon:$coordinatorUuid", "")

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING … */
    fun transportState(host: String): String? = SonosXml.value(avt(host, "GetTransportInfo"), "CurrentTransportState")

    /** RelTime in seconds and the URI playing. */
    fun positionInfo(host: String): Pair<Int?, String?> {
        val r = avt(host, "GetPositionInfo")
        return SonosXml.relTimeSeconds(SonosXml.value(r, "RelTime")) to SonosXml.value(r, "TrackURI")?.let { SonosXml.unescape(it) }
    }

    /** Changes the volume of the whole group by [delta]; returns the new volume (0–100). */
    fun groupVolume(host: String, delta: Int): Int? {
        val path = "/MediaRenderer/GroupRenderingControl/Control"
        val r = call(host, path, SonosXml.GROUP_RENDERING, "SetRelativeGroupVolume", listOf("InstanceID" to "0", "Adjustment" to delta.toString()))
        return SonosXml.value(r, "NewVolume")?.toIntOrNull()
    }

    /** All rooms of the household, asked from any one player. */
    fun rooms(host: String): List<SonosSpeaker> {
        val r = call(host, "/ZoneGroupTopology/Control", SonosXml.ZONE_TOPOLOGY, "GetZoneGroupState", emptyList())
        return SonosXml.zoneMembers(SonosXml.unescape(SonosXml.value(r, "ZoneGroupState") ?: return emptyList()))
    }

    companion object {
        /** Sonos players answering an SSDP search (needs a multicast lock on Android). */
        fun search(timeoutMs: Int = 2_500): List<String> {
            val hosts = LinkedHashSet<String>()
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = 300
                val target = InetSocketAddress("239.255.255.250", 1900)
                val end = System.currentTimeMillis() + timeoutMs
                var sent = 0
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    if (sent < 3) { runCatching { s.send(DatagramPacket(msg, msg.size, target)) }; sent++ }
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length)
                        if (text.contains("Sonos", ignoreCase = true) || text.contains("ZonePlayer")) {
                            Regex("(?im)^LOCATION:\\s*https?://([^:/\\s]+)").find(text)?.let { hosts += it.groupValues[1] }
                                ?: p.address?.hostAddress?.let { hosts += it }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
            return hosts.toList()
        }

        /** This device's address as seen from [host] (the one the speakers can reach). */
        fun localAddressFor(host: String): String = DatagramSocket().use {
            it.connect(InetAddress.getByName(host), 1400)
            it.localAddress.hostAddress ?: throw IOException("Keine eigene Adresse im Netz")
        }
    }
}

/**
 * The HTTP server the speakers fetch the sound from: one endless WAV per session at a random
 * path. Sound comes in at the speed the film plays; when none comes although the speakers are
 * listening (buffering), silence keeps the connection alive. Every piece sent is written into
 * [timeline], so the player knows which film time the speakers play.
 */
class SonosStream : Closeable {
    private class Chunk(val pcm: ByteArray, val mediaUs: Long)

    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val timeline = StreamTimeline()
    @Volatile var token = ""; private set
    @Volatile var rate = 48_000; private set
    /** Paused on purpose: no silence filling (the speakers are paused, too). */
    @Volatile var holdSilence = false
    private val queue = LinkedBlockingQueue<Chunk>()
    private val queued = AtomicLong()
    @Volatile private var current: Socket? = null
    private val random = SecureRandom()

    init {
        thread(name = "sonos-http", isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: continue
                thread(name = "sonos-conn", isDaemon = true) { runCatching { serve(s) } }
            }
        }
    }

    val path: String get() = "/cp/$token.wav"

    @Synchronized fun newSession(rate: Int) {
        this.rate = rate
        timeline.rate = rate
        token = ByteArray(9).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        queue.clear(); queued.set(0)
        timeline.clear()
        holdSilence = false
        current?.let { runCatching { it.close() } }
        current = null
    }

    @Synchronized fun endSession() {
        token = ""
        queue.clear(); queued.set(0)
        current?.let { runCatching { it.close() } }
        current = null
    }

    fun offer(pcm: ByteArray, mediaUs: Long) {
        if (token.isEmpty() || pcm.isEmpty()) return
        queue.add(Chunk(pcm, mediaUs))
        // nobody listening: keep at most ~20 s
        if (queued.addAndGet(pcm.size.toLong()) > 20L * rate * Wav.FRAME_BYTES) {
            queue.poll()?.let { queued.addAndGet(-it.pcm.size.toLong()) }
        }
    }

    override fun close() {
        endSession()
        runCatching { server.close() }
    }

    private fun serve(s: Socket) = s.use {
        s.soTimeout = 5_000
        s.tcpNoDelay = true
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val request = input.readLine() ?: return
        var rangeStart = 0L
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) {
                Regex("bytes=(\\d+)-").find(line)?.let { rangeStart = it.groupValues[1].toLongOrNull() ?: 0L }
            }
        }
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: return
        val reqPath = parts.getOrNull(1)?.substringBefore('?') ?: return
        val out = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
        val myToken = token
        if (myToken.isEmpty() || reqPath != "/cp/$myToken.wav") {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            return
        }
        val total = Wav.CONTENT_LENGTH
        rangeStart = rangeStart.coerceIn(0L, total - 1)
        val head = StringBuilder()
        if (rangeStart > 0) head.append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $rangeStart-${total - 1}/$total\r\n")
        else head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: audio/wav\r\nContent-Length: ${total - rangeStart}\r\nAccept-Ranges: bytes\r\n")
        head.append("Cache-Control: no-cache\r\nConnection: close\r\nServer: Couchpotato\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (method.equals("HEAD", ignoreCase = true)) { out.flush(); return }
        synchronized(this) {
            if (token != myToken) return
            current?.let { if (it !== s) runCatching { it.close() } }
            current = s
        }
        s.soTimeout = 0
        val rate = this.rate
        var frame = 0L
        if (rangeStart < Wav.HEADER_BYTES) {
            out.write(Wav.header(rate), rangeStart.toInt(), Wav.HEADER_BYTES - rangeStart.toInt())
        } else {
            val off = rangeStart - Wav.HEADER_BYTES
            val pad = ((Wav.FRAME_BYTES - off % Wav.FRAME_BYTES) % Wav.FRAME_BYTES).toInt()
            if (pad > 0) out.write(ByteArray(pad))
            frame = (off + pad) / Wav.FRAME_BYTES
        }
        out.flush()
        val silence = ByteArray(rate / 4 * Wav.FRAME_BYTES)
        while (current === s && token == myToken) {
            val c = queue.poll(250, TimeUnit.MILLISECONDS)
            if (c != null) {
                queued.addAndGet(-c.pcm.size.toLong())
                out.write(c.pcm)
                out.flush()
                val frames = c.pcm.size / Wav.FRAME_BYTES
                timeline.add(frame, c.mediaUs, frames)
                frame += frames
            } else if (!holdSilence) {
                out.write(silence)
                out.flush()
                frame += silence.size / Wav.FRAME_BYTES
            }
        }
    }
}

/**
 * The player's sound on Sonos speakers: groups the chosen rooms, streams to the first one (the
 * others follow it in sync) and keeps [clock] up to date by asking it what it plays. The player
 * shows the picture at [heardPositionUs] – the film time the speakers play right now.
 */
class SonosLink(
    private val listener: Listener,
    private val control: SonosControl = SonosControl(),
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) {
    interface Listener {
        /** Sonos did not work out – the player switches back to its own sound. */
        fun failed(message: String)
        /** The speakers play (first time per session). */
        fun playing() {}
    }

    @Volatile var active = false; private set
    @Volatile var speakers: List<SonosSpeaker> = emptyList(); private set
    @Volatile var trimMs = 0
    @Volatile var title = "Couchpotato"
    val clock = SonosClock()

    private val ctl = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sonos-ctl").apply { isDaemon = true } }
    @Volatile private var stream: SonosStream? = null
    @Volatile private var coordinator: SonosSpeaker? = null
    @Volatile private var grouped: List<SonosSpeaker> = emptyList()
    @Volatile private var rate = 0
    @Volatile private var gen = 0
    @Volatile private var pausedByUs = false
    @Volatile private var sessionUri = ""
    @Volatile private var sessionAt = 0.0
    @Volatile private var lastOurs = 0.0
    @Volatile private var announced = false
    private var failures = 0
    private var pending: java.util.concurrent.ScheduledFuture<*>? = null
    private var polling = false

    // after release() the executor is gone: late calls from the player are simply dropped
    private fun onCtl(task: () -> Unit) { runCatching { ctl.execute(task) } }
    private fun later(ms: Long, task: () -> Unit) = runCatching { ctl.schedule(task, ms, TimeUnit.MILLISECONDS) }.getOrNull()

    /** Sound to [list] from now on (first entry leads the group). */
    @Synchronized fun start(list: List<SonosSpeaker>) {
        if (list.isEmpty()) { stop(); return }
        speakers = list
        active = true
        restart()
    }

    /** Back to the player's own sound. */
    @Synchronized fun stop() {
        if (!active) return
        active = false
        gen++
        pending?.cancel(false)
        stream?.endSession()
        clock.reset()
        sessionUri = ""
        val c = coordinator
        val members = grouped.drop(1)
        grouped = emptyList()
        if (c != null) onCtl {
            runCatching { control.stop(c.host) }
            for (m in members) runCatching { control.standalone(m.host) }   // the other rooms are on their own again
        }
    }

    fun release() {
        stop()
        onCtl { stream?.close(); stream = null }
        ctl.shutdown()
    }

    fun volume(delta: Int, done: (Int?) -> Unit) {
        val c = coordinator ?: return done(null)
        onCtl { done(runCatching { control.groupVolume(c.host, delta) }.getOrNull()) }
    }

    // ---- from the audio sink (playback thread) ----

    fun onFormat(sampleRate: Int) {
        if (sampleRate <= 0 || sampleRate == rate) return
        rate = sampleRate
        if (active) restart()
    }

    fun onPcm(pcm: ByteArray, mediaUs: Long) {
        if (active) stream?.offer(pcm, mediaUs)
    }

    /** Seek or new start: the speakers' buffer holds old sound – start a fresh stream. */
    fun onFlush() { if (active) restart() }

    fun onPause() {
        if (!active || pausedByUs) return
        pausedByUs = true
        stream?.holdSilence = true
        clock.pausedAt(now())
        val c = coordinator ?: return
        val g = gen
        onCtl {
            if (g != gen) return@onCtl
            val t0 = now()
            if (runCatching { control.pause(c.host) }.isSuccess && g == gen && pausedByUs) clock.pauseConfirmed(t0, now())
        }
    }

    fun onPlay() {
        if (!active || !pausedByUs) return
        pausedByUs = false
        stream?.holdSilence = false
        val c = coordinator ?: return
        val g = gen
        onCtl {
            if (g != gen) return@onCtl
            val t0 = now()
            if (runCatching { control.play(c.host) }.isSuccess) clock.resumedAround(t0, now())
        }
    }

    /** Film time the speakers play now (µs), null while unknown (the picture waits). */
    fun heardPositionUs(): Long? {
        if (!active) return null
        val s = stream ?: return null
        val pos = clock.position(now()) ?: return null
        val frame = ((pos - trimMs / 1000.0) * s.rate).toLong()
        return s.timeline.mediaAt(frame)
    }

    // ---- sessions ----

    @Synchronized private fun restart() {
        val g = ++gen
        val s = stream ?: SonosStream().also { stream = it }
        s.newSession(if (rate > 0) rate else 48_000)
        clock.reset()
        pausedByUs = false
        sessionUri = ""
        announced = false
        pending?.cancel(false)
        // seeks come in bursts: start the speakers once things settle
        pending = later(150) { begin(g) }
    }

    private fun begin(g: Int) {
        if (g != gen || !active) return
        val list = speakers
        val s = stream ?: return
        try {
            if (grouped != list) group(list)
            val c = coordinator ?: return
            val uri = "http://${SonosControl.localAddressFor(c.host)}:${s.port}${s.path}"
            control.setUri(c.host, uri, SonosXml.didl(uri, title))
            if (g != gen) return
            control.play(c.host)
            sessionUri = uri
            sessionAt = now()
            lastOurs = sessionAt
            failures = 0
            if (!polling) { polling = true; later(100) { poll() } }
        } catch (e: Exception) {
            fail("Sonos nicht erreichbar (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun group(list: List<SonosSpeaker>) {
        val lead = list.first()
        for (old in grouped) if (list.none { it.uuid == old.uuid }) runCatching { control.standalone(old.host) }
        runCatching { control.standalone(lead.host) }
        for (m in list.drop(1)) runCatching { control.join(m.host, lead.uuid) }
        coordinator = lead
        grouped = list
    }

    private fun poll() {
        if (!active) { polling = false; return }
        val c = coordinator
        val uri = sessionUri
        var delay = 100L
        if (c != null && uri.isNotEmpty()) {
            try {
                val t0 = now()
                val state = control.transportState(c.host)
                val (rel, track) = control.positionInfo(c.host)
                val t1 = now()
                failures = 0
                if (uri == sessionUri) {
                    val ours = track == uri
                    val playingNow = ours && state == "PLAYING"
                    clock.observe(t0, t1, playingNow, rel)
                    if (ours) lastOurs = t1
                    if (playingNow && !announced) { announced = true; listener.playing() }
                    when {
                        !announced && !pausedByUs && t1 - sessionAt > 15 -> fail("Sonos startet die Wiedergabe nicht")
                        !ours && t1 - lastOurs > 6 -> fail("Sonos spielt jetzt etwas anderes")
                    }
                    if (playingNow && clock.precision() < 0.08) delay = 400L
                }
            } catch (e: Exception) {
                if (++failures > 25) fail("Sonos nicht mehr erreichbar")
            }
        }
        if (active) later(delay) { poll() } else polling = false
    }

    private fun fail(message: String) {
        if (!active) return
        stop()
        listener.failed(message)
    }
}
