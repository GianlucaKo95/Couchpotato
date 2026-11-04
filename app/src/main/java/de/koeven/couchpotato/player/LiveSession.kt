package de.koeven.couchpotato.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import de.koeven.couchpotato.data.XtreamClient
import java.io.File

/**
 * Live TV in the player: the timeshift buffer (pause / rewind), the last channel and zapping.
 * Zapping only moves a target while keys are pressed – ONE connection to the provider per stop.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LiveSession(private val a: PlayerActivity) {
    private var timeshift: TimeshiftBuffer? = null
    private var timeshiftOn = true          // switched off for a channel the buffer can't handle
    var index = -1; private set
    var previous = -1; private set
    private var startWall = 0L              // wall time of the frame the current playback started with
    private var resumeChannel = -1

    val canRewind: Boolean get() = timeshift != null

    fun start(index: Int, fromOffset: Long? = null) {
        val item = a.items.getOrNull(index) ?: return
        if (index != this.index) {
            if (this.index >= 0) previous = this.index
            this.index = index
            a.onChannel(index)
            de.koeven.couchpotato.data.SettingsStore(a)
                .addRecentLive(de.koeven.couchpotato.data.LiveChannel(item.streamId, item.title, item.image, null))
            timeshift?.stop()
            timeshift = if (timeshiftOn) {
                TimeshiftBuffer(XtreamClient.streamUrl(a.cfg, item), File(a.cacheDir, "timeshift")).start()
            } else null
        }
        val meta = MediaMetadata.Builder().setTitle(item.title).build()
        val buf = timeshift
        if (buf == null) {
            // direct stream (fallback): no rewinding
            a.player.setMediaItem(MediaItem.Builder().setUri(XtreamClient.streamUrl(a.cfg, item)).setMediaId(item.key).setMediaMetadata(meta).build())
            startWall = System.currentTimeMillis()
        } else {
            val from = fromOffset ?: buf.head
            startWall = if (fromOffset == null) System.currentTimeMillis() else buf.wallAt(from)
            val source = ProgressiveMediaSource.Factory(
                { TimeshiftDataSource { timeshift } },
                DefaultExtractorsFactory(),
            ).createMediaSource(
                MediaItem.Builder().setUri(Uri.parse("timeshift://live?from=$from&ch=${item.streamId}"))
                    .setMediaId(item.key).setMediaMetadata(meta).build()
            )
            a.player.setMediaSource(source)
        }
        a.player.prepare()
        a.player.playWhenReady = true
        a.overlays.updateLabel()
    }

    /** How far behind live the picture is (0 = live). */
    fun behindMs(): Long {
        if (timeshift == null) return 0L
        return ((System.currentTimeMillis() - startWall) - a.player.currentPosition).coerceAtLeast(0L)
    }

    fun shiftBy(deltaMs: Long) {
        val buf = timeshift ?: run { a.toast("Zurückspulen geht bei diesem Sender nicht"); return }
        val target = (behindMs() - deltaMs).coerceIn(0L, minOf(TimeshiftBuffer.KEEP_MS, buf.spanMs()))
        if (target < 8_000L) {
            goLive(); return
        }
        val (offset, _) = buf.offsetAt(target)
        start(index, offset)
        a.toast("−${fmt(target)} · ▶▶ Richtung Live")
    }

    fun goLive() {
        if (timeshift != null && behindMs() > 8_000L) {
            start(index, null)
            a.toast("Live")
        }
    }

    fun switchTo(target: Int) {
        a.handler.removeCallbacks(zapCommit)
        zapTarget = -1
        a.toast(a.items[target].title)
        start(target)
    }

    /** The stream ended (provider hiccup): start the channel again. */
    fun restart() = start(index.coerceAtLeast(0), null)

    /** The buffer didn't work for this channel: play it directly (without rewinding). */
    fun fallbackToDirect(): Boolean {
        if (timeshift == null) return false
        timeshiftOn = false
        timeshift?.stop(); timeshift = null
        val i = index; index = -1
        start(i)
        timeshiftOn = true
        return true
    }

    /** Live in the background: close the provider connection (most allow only one or two). */
    fun pause() {
        if (index < 0) return
        resumeChannel = index
        timeshift?.stop(); timeshift = null
        index = -1
        a.player.stop()
    }

    fun resume() {
        if (resumeChannel < 0) return
        val ch = resumeChannel; resumeChannel = -1
        start(ch)
    }

    fun release() {
        a.handler.removeCallbacks(zapCommit)
        timeshift?.stop(); timeshift = null
    }

    private var zapTarget = -1
    private val zapCommit = Runnable {
        val target = zapTarget
        zapTarget = -1
        if (target < 0 || target == index) return@Runnable
        start(target)
    }

    fun zap(step: Int) {
        val n = a.items.size
        if (n < 2) return
        zapTarget = zapStep(if (zapTarget >= 0) zapTarget else index.coerceAtLeast(0), step, n)
        a.toast(a.items[zapTarget].title)
        a.handler.removeCallbacks(zapCommit)
        a.handler.postDelayed(zapCommit, ZAP_DELAY_MS)
    }

    companion object {
        const val ZAP_DELAY_MS = 800L

        /** Next channel in a list of [n], wrapping around at both ends. */
        fun zapStep(from: Int, step: Int, n: Int): Int = ((from + step) % n + n) % n

        fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
    }
}
