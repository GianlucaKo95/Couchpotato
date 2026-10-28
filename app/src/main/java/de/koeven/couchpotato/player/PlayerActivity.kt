package de.koeven.couchpotato.player

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.ProgressStore
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.XtreamClient

/**
 * Fullscreen VOD player. Saves the position every 10 s and when leaving,
 * so "Weiterschauen" on the home screen always knows where you stopped.
 */
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressStore
    private var items: List<PlayItem> = emptyList()
    private var currentIndex = 0
    private var ended = false
    private val isLive: Boolean get() = items.firstOrNull()?.kind == PlayItem.KIND_LIVE

    private val handler = Handler(Looper.getMainLooper())
    private val saveTask = object : Runnable {
        override fun run() {
            saveCurrent()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        progress = ProgressStore(this)

        val cfg = SettingsStore(this).config()
        items = runCatching { Playback.decode(intent.getStringExtra(Playback.EXTRA_ITEMS)) }.getOrDefault(emptyList())
        if (items.isEmpty() || !cfg.isValid) {
            finish()
            return
        }
        currentIndex = intent.getIntExtra(Playback.EXTRA_INDEX, 0).coerceIn(0, items.lastIndex)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)   // Xtream panels often redirect to other hosts
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        playerView.player = player
        playerView.keepScreenOn = true

        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setUri(XtreamClient.streamUrl(cfg, item))
                .setMediaId(item.key)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setSubtitle(item.subtitle)
                        .build()
                )
                .build()
        }
        player.addListener(listener)
        player.setMediaItems(mediaItems, currentIndex, startPositionFor(items[currentIndex]))
        player.prepare()
        player.playWhenReady = true
        playerView.requestFocus()
    }

    private fun startPositionFor(item: PlayItem): Long {
        if (item.kind == PlayItem.KIND_LIVE) return C.TIME_UNSET   // live edge
        val e = progress.get(item.key) ?: return 0L
        if (e.upNext) return 0L
        if (e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95) return 0L
        return e.positionMs
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isLive && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && currentIndex in items.indices) {
                // previous episode played to the end
                progress.finished(items[currentIndex], items.getOrNull(currentIndex + 1))
            }
            currentIndex = player.currentMediaItemIndex
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                ended = true
                if (!isLive) items.getOrNull(currentIndex)?.let { progress.finished(it, null) }
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            saveCurrent()
            Toast.makeText(
                this@PlayerActivity,
                "Wiedergabe fehlgeschlagen (${error.errorCodeName})",
                Toast.LENGTH_LONG
            ).show()
            // Live: stay, so you can zap to the next channel. VOD: back to the launcher.
            if (!isLive) finish()
        }
    }

    private fun saveCurrent() {
        if (!::player.isInitialized || ended || isLive) return
        val i = player.currentMediaItemIndex
        val item = items.getOrNull(i) ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        when {
            dur > 0 && pos >= dur * 0.95 -> progress.finished(item, items.getOrNull(i + 1))
            pos > 5_000 -> progress.save(item, pos, dur)
        }
    }

    // saved here too: onPause runs before the launcher comes back, onStop only afterwards
    override fun onPause() {
        saveCurrent()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(saveTask, SAVE_INTERVAL_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(saveTask)
        saveCurrent()
        if (::player.isInitialized) player.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(zapCommit)
        handler.removeCallbacks(saveTask)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Live: up/down (while the controls are hidden) and channel keys zap through the list.
        if (isLive && ::player.isInitialized) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> 1
                KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
                KeyEvent.KEYCODE_DPAD_UP -> if (playerView.isControllerFullyVisible) 0 else 1
                KeyEvent.KEYCODE_DPAD_DOWN -> if (playerView.isControllerFullyVisible) 0 else -1
                else -> 0
            }
            if (step != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) zap(step)
                return true
            }
        }
        // Let the PlayerView show its controls on D-pad and handle play/pause/seek keys.
        if (::playerView.isInitialized && playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Zapping: every key press only moves the target and shows its name; the stream is switched
     * once you stay on a channel for a moment. Hammering up/down therefore opens ONE connection
     * to the provider instead of one per channel you skip over.
     */
    private var zapTarget = -1
    private var zapToast: Toast? = null
    private val zapCommit = Runnable {
        val target = zapTarget
        zapTarget = -1
        if (target < 0 || target == player.currentMediaItemIndex) return@Runnable
        player.seekToDefaultPosition(target)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
    }

    private fun zap(step: Int) {
        if (items.size < 2) return
        val n = items.size
        val from = if (zapTarget >= 0) zapTarget else player.currentMediaItemIndex
        zapTarget = ((from + step) % n + n) % n
        zapToast?.cancel()
        zapToast = Toast.makeText(this, items[zapTarget].title, Toast.LENGTH_SHORT).also { it.show() }
        handler.removeCallbacks(zapCommit)
        handler.postDelayed(zapCommit, ZAP_DELAY_MS)
    }

    private companion object {
        const val ZAP_DELAY_MS = 800L
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
