package de.koeven.couchpotato.player

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.Handoff
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.ProgressStore
import de.koeven.couchpotato.data.ScreenTime
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.XtreamClient
import de.koeven.couchpotato.data.XtreamConfig

/**
 * Fullscreen player for films, series and live TV. Saves the position every 10 s and when
 * leaving, so "Weiterschauen" on the home screen always knows where you stopped.
 *
 * The parts live in their own files: [VodControls] (Netflix-like controls), [UpNextPanel],
 * [LiveSession] (timeshift, last channel, zapping), [PlayerOverlays] (notices, ≡ menu),
 * [SleepTimer] and [TrackMemory].
 */
class PlayerActivity : Activity() {

    internal lateinit var player: ExoPlayer
    internal lateinit var playerView: PlayerView
    internal lateinit var progress: ProgressStore
    internal lateinit var cfg: XtreamConfig
    internal var items: List<PlayItem> = emptyList()
    private var currentIndex = 0
    private var ended = false
    internal val isLive: Boolean get() = items.firstOrNull()?.kind == PlayItem.KIND_LIVE

    internal val handler = Handler(Looper.getMainLooper())
    internal val sleep = SleepTimer()
    internal var controls: VodControls? = null
    internal lateinit var live: LiveSession
    internal lateinit var overlays: PlayerOverlays
    internal lateinit var split: TwitchSplit
    internal lateinit var channels: ChannelPanel
    internal lateinit var upNext: UpNextPanel
    private lateinit var tracks: TrackMemory

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

        cfg = SettingsStore(this).config()
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

        val upstream = DefaultDataSource.Factory(this, http)
        // films & series: through the cache on the stick (rewinding needs no new request), bigger read-ahead
        val sourceFactory = if (isLive) upstream else {
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(PlayerCache.get(this))
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            // up to 3 minutes ahead, but never more than 64 MB in memory (4K: about 30 s, HD: 2–3 min)
            .setBufferDurationsMs(30_000, 180_000, 2_500, 5_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sourceFactory))
            .setLoadControl(if (isLive) androidx.media3.exoplayer.DefaultLoadControl() else loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        playerView.player = player
        playerView.keepScreenOn = true
        playerView.setShowSubtitleButton(true)
        tracks = TrackMemory(this, player).also { it.apply() }
        player.addListener(listener)
        val root = playerView.parent as FrameLayout
        if (!isLive) {
            // films & series: our own Netflix-like controls instead of the standard ones
            playerView.useController = false
            controls = VodControls(this, root)
        }
        live = LiveSession(this)
        upNext = UpNextPanel(this, root)
        overlays = PlayerOverlays(this, root)
        split = TwitchSplit(this, root)
        channels = ChannelPanel(this, root)
        handler.post(tick)

        if (isLive) {
            live.start(currentIndex)
            playerView.requestFocus()
            return
        }

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
        player.setMediaItems(mediaItems, currentIndex, startPositionFor(items[currentIndex]))
        player.prepare()
        player.playWhenReady = true
        playerView.requestFocus()
    }

    /** Live: the session switched to another channel. */
    internal fun onChannel(index: Int) {
        currentIndex = index
    }

    /**
     * TEST: the channel keeps running as a small window on top of other apps; the player closes.
     * The stream here is stopped first, so the provider still sees one connection.
     */
    internal fun floatAway() {
        if (!FloatingPlayer.allowed(this)) {
            toast("Erst freigeben: adb shell appops set de.koeven.couchpotato SYSTEM_ALERT_WINDOW allow")
            return
        }
        val i = live.index.coerceAtLeast(0)
        if (items.getOrNull(i) == null) return
        live.release()
        player.stop()
        FloatingPlayer.start(this, items, i)
        finish()
    }

    internal fun toast(text: String) {
        if (!::overlays.isInitialized) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); return
        }
        overlays.toast(text)
    }

    // ---------- sleep timer ----------

    internal fun cycleSleep() {
        val episode = !isLive && items.getOrNull(player.currentMediaItemIndex)?.kind == PlayItem.KIND_EPISODE
        val step = sleep.cycle(episode)
        if (!sleep.endOfEpisode && !upNext.stillWatching) player.pauseAtEndOfMediaItems = false
        if (sleep.endOfEpisode) player.pauseAtEndOfMediaItems = true
        toast(SleepTimer.message(step))
        overlays.updateLabel()
        overlays.refreshMenu()
    }

    private fun goToSleep(message: String) {
        saveCurrent()
        player.playWhenReady = false
        de.koeven.couchpotato.ui.Notice.show(message, long = true)   // shown in the launcher
        finish()
    }

    // ---------- every 0.5 s: up next, controls; every 2 s: sleep timer, screen time ----------

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 500)
        }
    }

    private fun onTick() {
        if (!::player.isInitialized) return
        periodicChecks()
        controls?.takeIf { it.visible }?.refresh()
        if (!isLive) upNext.update()
    }

    private var lastChecks = 0L
    private var warned5 = false

    /** Every few seconds: sleep timer, kids' screen time, the "behind live" label. */
    private fun periodicChecks() {
        val now = System.currentTimeMillis()
        if (now - lastChecks < 2_000L) return
        lastChecks = now
        overlays.updateLabel()
        if (sleep.due()) { goToSleep("Sleep-Timer: Gute Nacht"); return }
        when (val st = ScreenTime.state(this)) {
            is ScreenTime.State.LimitReached -> goToSleep(if (st.evening) "Schlafenszeit – gute Nacht!" else "Bildschirmzeit für heute ist vorbei")
            is ScreenTime.State.Bedtime -> goToSleep("Schlafenszeit – ab ${st.at} Uhr ist Schluss")
            is ScreenTime.State.Allowed -> if (st.remainingMin == 5 && !warned5) { warned5 = true; toast("Noch 5 Minuten Bildschirmzeit") }
        }
    }

    private fun startPositionFor(item: PlayItem): Long {
        if (item.kind == PlayItem.KIND_LIVE) return C.TIME_UNSET   // live edge
        val e = progress.get(item.key) ?: return 0L
        if (e.upNext) return 0L
        if (ProgressStore.isFinished(e.positionMs, e.durationMs)) return 0L
        return e.positionMs
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isLive && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && currentIndex in items.indices) {
                // previous episode played to the end
                progress.finished(items[currentIndex], items.getOrNull(currentIndex + 1))
                upNext.autoInARow++
            }
            if (::upNext.isInitialized && upNext.visible) upNext.hide()
            currentIndex = player.currentMediaItemIndex
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = tracks.onParametersChanged()

        override fun onTracksChanged(tracks: Tracks) = this@PlayerActivity.tracks.onTracksChanged(tracks)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // sleep timer "nach dieser Folge": the player stopped at the end of the episode
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleep.endOfEpisode) {
                sleep.endOfEpisode = false
                goToSleep("Sleep-Timer: Gute Nacht")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && isLive) {
                // live stream ended (provider hiccup): start the channel again
                live.restart()
                return
            }
            if (playbackState == Player.STATE_ENDED) {
                ended = true
                if (!isLive) items.getOrNull(currentIndex)?.let { progress.finished(it, null) }
                finish()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            saveCurrent()
            if (isLive) toast("Wiedergabe fehlgeschlagen (${error.errorCodeName})")
            else de.koeven.couchpotato.ui.Notice.show("Wiedergabe fehlgeschlagen (${error.errorCodeName})", long = true)
            // Live: stay, so you can zap to the next channel (and try without the buffer). VOD: back to the launcher.
            if (!isLive) finish() else live.fallbackToDirect()
        }
    }

    internal fun saveCurrent() {
        if (!::player.isInitialized || ended || isLive) return
        val i = player.currentMediaItemIndex
        val item = items.getOrNull(i) ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        when {
            ProgressStore.isFinished(pos, dur) -> progress.finished(item, items.getOrNull(i + 1))
            pos > 5_000 -> progress.save(item, pos, dur)
        }
    }

    /** What was playing goes into the household right away, so the iPhone can continue from here. */
    private fun handOver() {
        if (!::player.isInitialized) return
        runCatching {
            if (isLive) {
                if (!::live.isInitialized) return
                items.getOrNull(live.index)?.let { Handoff.record(this, it, 0, 0) }
            } else {
                val item = items.getOrNull(player.currentMediaItemIndex) ?: return
                val pos = player.currentPosition
                val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                if (ended || ProgressStore.isFinished(pos, dur)) Handoff.clear(this)
                else if (pos > 5_000) Handoff.record(this, item, pos, dur)
                else return
            }
            Handoff.pushNow(this)
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
        if (isLive && ::live.isInitialized) live.resume()
    }

    override fun onStop() {
        handler.removeCallbacks(saveTask)
        saveCurrent()
        handOver()
        if (::player.isInitialized) player.playWhenReady = false
        // live in the background: close the provider connection (most allow only one or two)
        if (isLive && ::live.isInitialized) live.pause()
        if (::split.isInitialized) split.stop()            // Twitch doesn't keep running in the background
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTask)
        handler.removeCallbacks(tick)
        if (::overlays.isInitialized) overlays.release()
        if (::live.isInitialized) live.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) upNext.autoInARow = 0   // someone is on the couch
        // "Nächste Folge" panel: D-pad moves between its buttons, Back closes it
        if (upNext.visible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) upNext.dismiss()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                upNext.hide()
            } else {
                return super.dispatchKeyEvent(event)
            }
        }
        // Twitch channel picker: the keyboard and its buttons get the keys, Back closes it
        if (split.pickerVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) split.hidePicker()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // channel list: D-pad moves through categories and channels, Back closes it
        if (channels.visible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) channels.hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // live: OK opens the channel list (switch channels without leaving the player)
        if (isLive && !playerView.isControllerFullyVisible && !overlays.menuVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (event.action == KeyEvent.ACTION_UP) channels.show()
            return true
        }
        // ≡ menu panel: last channel, live, sleep timer
        if (overlays.menuVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) overlays.hideMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) overlays.showMenu()
            return true
        }
        // films & series: Netflix-like controls
        controls?.let { c ->
            if (c.handleKey(event)) return true
            if (c.visible) return super.dispatchKeyEvent(event)      // D-pad between the buttons
        }
        // Splitscreen: ◀ ▶ moves the sound to Twitch / the TV, Back ends the split (not the player)
        if (split.active && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) split.setSound(event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) split.stop()
                    return true
                }
            }
        }
        if (isLive && event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL) {
            if (event.action == KeyEvent.ACTION_DOWN) live.backToPrevious()
            return true
        }
        // Live timeshift: ⏪ / ⏩ always, ◀ / ▶ while the controls are hidden – 30 s back / towards live
        if (isLive) {
            val dpad = !playerView.isControllerFullyVisible
            val delta = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> -30_000L
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 30_000L
                KeyEvent.KEYCODE_DPAD_LEFT -> if (dpad) -30_000L else 0L
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (dpad) 30_000L else 0L
                else -> 0L
            }
            if (delta != 0L) {
                if (event.action == KeyEvent.ACTION_DOWN) live.shiftBy(delta)
                return true
            }
        }
        // Live: up/down (while the controls are hidden) and channel keys zap through the list.
        if (isLive) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> 1
                KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
                KeyEvent.KEYCODE_DPAD_UP -> if (playerView.isControllerFullyVisible) 0 else 1
                KeyEvent.KEYCODE_DPAD_DOWN -> if (playerView.isControllerFullyVisible) 0 else -1
                else -> 0
            }
            if (step != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) live.zap(step)
                return true
            }
        }
        // Let the PlayerView show its controls on D-pad and handle play/pause/seek keys.
        if (playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
