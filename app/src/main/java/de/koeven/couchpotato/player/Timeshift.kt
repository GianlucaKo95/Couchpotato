package de.koeven.couchpotato.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import de.koeven.couchpotato.data.XtreamClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live timeshift: ONE connection to the provider records the channel into small files on the
 * stick, the player always reads from there. So you can pause and rewind up to [KEEP_MS] –
 * and the provider still only sees a single viewer, exactly like before.
 */
class TimeshiftBuffer(private val url: String, private val dir: File) {
    private val lock = Object()
    @Volatile var head = 0L; private set              // bytes recorded so far
    @Volatile var oldest = 0L; private set            // first byte still on disk
    @Volatile private var stopped = false
    @Volatile var failed: String? = null; private set
    private val index = ArrayList<LongArray>()        // [wall ms, byte offset], every ~0.5 s
    private var thread: Thread? = null
    val startedAt = System.currentTimeMillis()

    fun start(): TimeshiftBuffer {
        dir.deleteRecursively(); dir.mkdirs()
        thread = Thread({ record() }, "timeshift").apply { isDaemon = true; start() }
        return this
    }

    fun stop() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
        thread?.interrupt()
        Thread { dir.deleteRecursively() }.start()
    }

    /** Byte offset that was being recorded [delayMs] ago (or the oldest still kept). */
    fun offsetAt(delayMs: Long): Pair<Long, Long> = synchronized(lock) {
        val target = System.currentTimeMillis() - delayMs
        var best: LongArray? = null
        for (e in index) { if (e[0] <= target && e[1] >= oldest) best = e; if (e[0] > target) break }
        val pick = best ?: index.firstOrNull { it[1] >= oldest } ?: return@synchronized head to System.currentTimeMillis()
        pick[1] to pick[0]
    }

    /** Wall time at which [offset] was recorded. */
    fun wallAt(offset: Long): Long = synchronized(lock) {
        index.lastOrNull { it[1] <= offset }?.get(0) ?: startedAt
    }

    /** How far back the buffer currently reaches. */
    fun spanMs(): Long = synchronized(lock) {
        val first = index.firstOrNull { it[1] >= oldest } ?: return@synchronized 0L
        System.currentTimeMillis() - first[0]
    }

    private fun record() {
        var failures = 0
        while (!stopped) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", XtreamClient.USER_AGENT)
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.instanceFollowRedirects = true
                var conn = c
                // Xtream panels redirect to stream servers, sometimes http ↔ https
                repeat(5) {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return@repeat
                        conn.disconnect()
                        conn = (URL(URL(url), loc).openConnection() as HttpURLConnection).apply {
                            setRequestProperty("User-Agent", XtreamClient.USER_AGENT)
                            connectTimeout = 15_000; readTimeout = 20_000
                        }
                    }
                }
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (!stopped) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) { write(buf, n); failures = 0 }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                if (stopped) break
                failures++
                if (failures >= 6) {
                    failed = e.message ?: "Sender nicht erreichbar"
                    synchronized(lock) { lock.notifyAll() }
                    break
                }
            }
            if (!stopped) runCatching { Thread.sleep(1_500L * failures.coerceAtLeast(1)) }
        }
    }

    private var out: FileOutputStream? = null
    private var outChunk = -1L

    private fun write(b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val chunk = head / CHUNK
            if (chunk != outChunk) {
                out?.close()
                out = FileOutputStream(chunkFile(chunk), true)
                outChunk = chunk
            }
            val room = ((chunk + 1) * CHUNK - head).toInt()
            val len = minOf(room, n - off)
            out!!.write(b, off, len)
            off += len
            synchronized(lock) {
                head += len
                val now = System.currentTimeMillis()
                if (index.isEmpty() || now - index.last()[0] >= 500) index += longArrayOf(now, head)
                evict(now)
                lock.notifyAll()
            }
        }
    }

    /** Drops what is older than [KEEP_MS] (and keeps the disk use bounded). */
    private fun evict(now: Long) {
        val keepFrom = index.lastOrNull { it[0] <= now - KEEP_MS - 20_000 }?.get(1) ?: 0L
        val capFrom = head - MAX_BYTES
        val from = maxOf(keepFrom, capFrom, 0L)
        while (oldest / CHUNK < from / CHUNK && oldest / CHUNK < head / CHUNK) {
            chunkFile(oldest / CHUNK).delete()
            oldest = (oldest / CHUNK + 1) * CHUNK
        }
        while (index.size > 2 && index[1][1] < oldest) index.removeAt(0)
    }

    private fun chunkFile(n: Long) = File(dir, "c$n.ts")

    /**
     * Reads at [pos]; waits for the recording when it has caught up with live.
     * Returns -1 when the buffer was stopped, throws when the channel failed.
     */
    fun read(pos: Long, target: ByteArray, offset: Int, length: Int, cancelled: () -> Boolean): Int {
        val deadline = System.currentTimeMillis() + 20_000
        synchronized(lock) {
            while (pos >= head) {
                if (stopped || cancelled()) return C.RESULT_END_OF_INPUT
                failed?.let { throw IOException(it) }
                if (System.currentTimeMillis() > deadline) throw IOException("Kein Signal vom Sender")
                lock.wait(500)
            }
        }
        val chunk = pos / CHUNK
        val avail = minOf(head, (chunk + 1) * CHUNK) - pos
        RandomAccessFile(chunkFile(chunk), "r").use { f ->
            f.seek(pos - chunk * CHUNK)
            return f.read(target, offset, minOf(length.toLong(), avail).toInt())
        }
    }

    companion object {
        const val KEEP_MS = 5 * 60_000L
        private const val CHUNK = 4L * 1024 * 1024
        private const val MAX_BYTES = 900L * 1024 * 1024
    }
}

/** Plays from the timeshift files: "timeshift://live?from=<byte offset>". */
@UnstableApi
class TimeshiftDataSource(private val buffer: () -> TimeshiftBuffer?) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var pos = 0L
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        closed = false
        val buf = buffer() ?: throw IOException("Kein Live-Puffer")
        pos = (dataSpec.uri.getQueryParameter("from")?.toLongOrNull() ?: 0L) + dataSpec.position
        if (pos < buf.oldest) pos = buf.oldest
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val buf = buffer() ?: return C.RESULT_END_OF_INPUT
        if (pos < buf.oldest) pos = buf.oldest          // paused longer than the buffer reaches
        val n = buf.read(pos, target, offset, length) { closed }
        if (n > 0) { pos += n; bytesTransferred(n) }
        return n
    }

    /** Where the player is reading right now (ahead of what is on screen by the player's buffer). */
    val position: Long get() = pos

    override fun getUri(): Uri? = uri

    override fun close() {
        if (closed) return
        closed = true
        if (uri != null) { uri = null; transferEnded() }
    }
}
