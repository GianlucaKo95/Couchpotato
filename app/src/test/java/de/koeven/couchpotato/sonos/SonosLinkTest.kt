package de.koeven.couchpotato.sonos

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A pretend Sonos speaker: SOAP control like the real one, fetches the stream, buffers [prebufferS]
 * before it starts and then plays in real time. The sound carries the film time in its samples,
 * so the test knows exactly which film moment the "speaker" plays.
 */
private class FakeSonos(private val prebufferS: Double = 1.5) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile var uri = ""
    @Volatile var state = "STOPPED"
    @Volatile var playedFrames = 0L
    @Volatile var playingMediaMs = -1L
    @Volatile private var session = 0
    private val buffer = java.util.concurrent.LinkedBlockingDeque<Long>()   // film ms per frame (0 = silence)
    private var rate = 16_000

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: continue
                thread(isDaemon = true) { runCatching { s.use { handle(it) } } }
            }
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = input.read(b, off, n - off); if (r < 0) return null; off += r }
        return b
    }

    /** One SOAP request in, one answer out (just enough HTTP for HttpURLConnection). */
    private fun handle(s: java.net.Socket) {
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) return; head.append(c.toChar()) }
        val headers = head.lines()
        val len = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
        val action = headers.first { it.startsWith("SOAPACTION:", true) }.substringAfter(':').trim().trim('"').substringAfter('#')
        val body = String(readFully(input, len) ?: return, Charsets.UTF_8)
        val reply = when (action) {
            "SetAVTransportURI" -> { stopAll(); uri = SonosXml.unescape(SonosXml.value(body, "CurrentURI")!!); "" }
            "Play" -> { if (state == "PAUSED_PLAYBACK") state = "PLAYING" else startPlaying(); "" }
            "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
            "Stop" -> { stopAll(); "" }
            "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
            "GetPositionInfo" -> {
                val sec = playedFrames / rate
                "<RelTime>${sec / 3600}:${"%02d".format(sec / 60 % 60)}:${"%02d".format(sec % 60)}</RelTime><TrackURI>${SonosXml.escape(uri)}</TrackURI>"
            }
            else -> ""
        }
        val out = "<s:Envelope><s:Body><u:${action}Response>$reply</u:${action}Response></s:Body></s:Envelope>".toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(out); flush()
        }
    }

    private fun stopAll() { session++; state = "STOPPED"; buffer.clear(); playedFrames = 0; playingMediaMs = -1 }

    private fun startPlaying() {
        val my = ++session
        state = "TRANSITIONING"
        val u = uri
        thread(isDaemon = true) {   // network: reads as fast as it can, up to 4 s ahead
            runCatching {
                val input: InputStream = URL(u).openStream()
                val head = readFully(input, 44) ?: return@runCatching
                rate = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
                while (my == session) {
                    if (buffer.size > rate * 4) { Thread.sleep(5); continue }
                    val frame = readFully(input, 4) ?: break
                    val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
                    val l = b.getShort(0).toLong() and 0xffff
                    val r = b.getShort(2).toLong() and 0xffff
                    buffer.add(r * 32_000 + l)
                }
            }
        }
        thread(isDaemon = true) {   // playback: starts after the pre-buffer, then real time
            while (my == session && buffer.size < prebufferS * rate) Thread.sleep(2)
            if (my != session) return@thread
            state = "PLAYING"
            var next = System.nanoTime()
            while (my == session) {
                next += 5_000_000
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                if (state != "PLAYING") { next = System.nanoTime(); continue }
                repeat(rate / 200) {
                    val v = buffer.poll()
                    if (v != null) { playedFrames++; playingMediaMs = v }
                }
            }
        }
    }

    fun dropOut() { session++; state = "STOPPED"; buffer.clear() }

    fun close() { stopAll(); server.close() }
}

class SonosLinkTest {
    private val fake = FakeSonos()
    private val failures = mutableListOf<String>()
    private val link = SonosLink(object : SonosLink.Listener {
        override fun failed(message: String) { failures += message }
    }, SonosControl(port = fake.port))
    private val rate = 16_000
    @Volatile private var feeding = true
    @Volatile private var mediaUs = 10_000_000L
    @Volatile private var stopFeeder = false

    private fun feeder() = thread(isDaemon = true) {
        var next = System.nanoTime()
        while (!stopFeeder) {
            next += 20_000_000
            val wait = next - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000)
            if (!feeding) continue
            val frames = rate / 50
            val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            val start = mediaUs
            for (i in 0 until frames) {
                val ms = (start + i * 1_000_000L / rate) / 1000
                b.putShort((ms % 32_000).toInt().toShort()); b.putShort((ms / 32_000).toInt().toShort())
            }
            link.onPcm(b.array(), start)
            mediaUs = start + frames * 1_000_000L / rate
        }
    }

    private fun assertInSync(label: String) {
        val truth = fake.playingMediaMs
        val heard = link.heardPositionUs()!! / 1000
        assertTrue("$label: speaker plays $truth ms, picture at $heard ms", truth > 0 && abs(truth - heard) < 60)
    }

    @After fun tearDown() { stopFeeder = true; link.release(); fake.close() }

    @Test fun `picture follows the speaker through start, pause and seek`() {
        link.onFormat(rate)
        link.start(listOf(SonosSpeaker("RINCON_X", "Test", "127.0.0.1")))
        // ExoPlayer pauses and resumes the sink while it buffers at the start – before the speaker is even set up
        link.onPause(); Thread.sleep(30); link.onPlay()
        feeder()
        Thread.sleep(400)
        assertTrue("waits while the speaker buffers", link.heardPositionUs() == null || fake.state != "PLAYING")
        Thread.sleep(3_700)
        assertTrue(failures.toString(), failures.isEmpty())
        assertTrue("speaker plays", fake.state == "PLAYING")
        assertInSync("after start")
        // the film plays ahead of what is heard by the speaker's buffer + the cushion
        assertTrue("${mediaUs / 1000 - fake.playingMediaMs}", mediaUs / 1000 - fake.playingMediaMs in 1_800..3_800)

        link.onPause(); feeding = false
        Thread.sleep(1_000)
        val frozen = link.heardPositionUs()!!
        Thread.sleep(500)
        assertTrue(frozen == link.heardPositionUs())
        assertInSync("paused")

        link.onPlay(); feeding = true
        Thread.sleep(2_500)
        assertInSync("after resume")

        feeding = false
        link.onFlush()                       // seek to film second 100
        mediaUs = 100_000_000L
        feeding = true
        Thread.sleep(4_500)
        assertInSync("after seek")
        assertTrue(fake.playingMediaMs > 100_000)
        assertTrue(failures.toString(), failures.isEmpty())

        fake.dropOut()                       // the speaker stops by itself (Wi-Fi gone)
        Thread.sleep(10_000)
        assertTrue("plays again", fake.state == "PLAYING")
        assertInSync("after a drop-out")
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
