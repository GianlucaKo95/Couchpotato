package de.koeven.couchpotato.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import de.koeven.couchpotato.data.XtreamClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * TEST: the live channel as a small window on top of any other app (RTL+, Netflix …) –
 * "Über anderen Apps einblenden", granted once via adb. Muted by default; controlled from the
 * bar on Couchpotato's home screen (the remote belongs to the app underneath).
 */
class FloatingPlayer : Service() {

    data class State(val active: Boolean = false, val title: String = "", val muted: Boolean = true, val corner: Int = 0, val size: Int = 1, val minimized: Boolean = false,
        val vod: Boolean = false, val paused: Boolean = false, val hasNext: Boolean = false) {
        val percent: Int get() = SIZES[size]
        val canGrow: Boolean get() = size < SIZES.lastIndex
        val canShrink: Boolean get() = size > 0
    }

    private var player: ExoPlayer? = null
    private val progress by lazy { de.koeven.couchpotato.data.ProgressStore(this) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTask = object : Runnable {
        override fun run() { saveCurrent(); main.postDelayed(this, 10_000) }
    }
    private var view: PlayerView? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** The channels you can switch through in the window (the list the player had) and the current one. */
    private var items: List<de.koeven.couchpotato.data.PlayItem> = emptyList()
    private var index = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        items = runCatching { Playback.decode(intent?.getStringExtra(Playback.EXTRA_ITEMS)) }.getOrDefault(emptyList())
        index = (intent?.getIntExtra(Playback.EXTRA_INDEX, 0) ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val item = items.getOrNull(index) ?: run { stopSelf(); return START_NOT_STICKY }
        goForeground(item.title)
        prefs = getSharedPreferences("floating", MODE_PRIVATE)
        // size and corner as last time
        _state.value = State(active = true, title = titleOf(item),
            corner = prefs?.getInt("corner", 0) ?: 0, size = (prefs?.getInt("size", 1) ?: 1).coerceIn(0, SIZES.lastIndex))
        play(item)
        show()
        main.postDelayed(saveTask, 10_000)
        WindowRemote.start(this)
        showQr()
        return START_NOT_STICKY
    }

    // ---------- QR code for the phone remote, next to the window for 20 seconds ----------

    private var qrView: android.view.View? = null
    private val hideQr = Runnable { removeQr() }

    private fun showQr() {
        removeQr()
        val bmp = WindowRemote.qr() ?: return
        val d = resources.displayMetrics.density
        val v = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(PlayerStyle.PANEL) }
            addView(android.widget.ImageView(this@FloatingPlayer).apply { setImageBitmap(bmp) },
                android.widget.LinearLayout.LayoutParams((150 * d).toInt(), (150 * d).toInt()))
            addView(android.widget.TextView(this@FloatingPlayer).apply {
                text = "Mit dem Handy steuern"; setTextColor(PlayerStyle.TEXT); textSize = 13f
                setPadding(0, (6 * d).toInt(), 0, 0)
            })
        }
        val win = params()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, win.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = win.gravity; x = win.x + win.width + (16 * d).toInt(); y = win.y }
        if (runCatching { wm.addView(v, p) }.isSuccess) {
            qrView = v
            v.postDelayed(hideQr, 20_000)
        }
    }

    private fun removeQr() {
        qrView?.let { it.removeCallbacks(hideQr); runCatching { wm.removeView(it) } }
        qrView = null
    }

    private fun playItems(list: List<de.koeven.couchpotato.data.PlayItem>, i: Int) {
        if (list.isEmpty()) return
        saveCurrent()
        items = list
        index = i.coerceIn(0, list.lastIndex)
        play(list[index])
    }

    private fun titleOf(item: de.koeven.couchpotato.data.PlayItem) =
        if (item.kind == de.koeven.couchpotato.data.PlayItem.KIND_EPISODE && item.subtitle != null) "${item.title} · ${item.subtitle}" else item.title

    private val isVod: Boolean get() = items.getOrNull(index)?.kind?.let { it != de.koeven.couchpotato.data.PlayItem.KIND_LIVE } == true

    /** Films and episodes: the position goes to "Weiterschauen", like in the big player. */
    private fun saveCurrent() {
        val p = player ?: return
        if (!isVod || _state.value.minimized) return
        val item = items.getOrNull(index) ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        when {
            dur > 0 && pos >= dur * 0.95 -> progress.finished(item, items.getOrNull(index + 1))
            pos > 5_000 -> progress.save(item, pos, dur)
        }
    }

    private fun startPositionFor(item: de.koeven.couchpotato.data.PlayItem): Long {
        val e = progress.get(item.key) ?: return 0L
        if (e.upNext) return 0L
        if (e.durationMs > 0 && e.positionMs >= e.durationMs * 0.95) return 0L
        return e.positionMs
    }

    private fun seek(deltaMs: Long) {
        val p = player ?: return
        if (!isVod) return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    private fun togglePause() {
        val p = player ?: return
        if (!isVod) return
        p.playWhenReady = !p.playWhenReady
        _state.value = _state.value.copy(paused = !p.playWhenReady)
        if (!p.playWhenReady) saveCurrent()
    }

    /** Next/previous channel in the window – the old stream is replaced, still one connection. */
    private fun zap(step: Int) {
        if (items.size < 2) return
        if (isVod) {
            // films/series: previous/next episode, no wrapping around
            val to = index + step
            if (to !in items.indices) return
            saveCurrent()
            index = to
        } else {
            index = LiveSession.zapStep(index, step, items.size)
        }
        play(items[index])
    }

    /** Back to the big player with the same channel (the window closes first: one connection). */
    private fun fullscreen() {
        saveCurrent()           // the big player starts where the window was
        val list = items
        val i = index
        stopSelf()
        player?.stop()
        Playback.start(applicationContext, list, i)
    }

    private fun goForeground(title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kleines Fenster", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setContentTitle("Couchpotato: $title im kleinen Fenster")
            .setSmallIcon(de.koeven.couchpotato.R.drawable.ic_launcher)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
    }

    private fun play(item: de.koeven.couchpotato.data.PlayItem) {
        val settings = de.koeven.couchpotato.data.SettingsStore(this)
        if (item.kind == de.koeven.couchpotato.data.PlayItem.KIND_LIVE) {
            settings.addRecentLive(de.koeven.couchpotato.data.LiveChannel(item.streamId, item.title, item.image, null))
        }
        val url = XtreamClient.streamUrl(settings.config(), item)
        val vod = item.kind != de.koeven.couchpotato.data.PlayItem.KIND_LIVE
        _state.value = _state.value.copy(title = titleOf(item), vod = vod, paused = false, hasNext = vod && index < items.lastIndex)
        if (_state.value.minimized) return      // hidden: the channel starts when the window comes back
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val p = player ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build().also { player = it }.apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        de.koeven.couchpotato.ui.Notice.show("Kleines Fenster: Wiedergabe fehlgeschlagen (${error.errorCodeName})", long = true)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state != Player.STATE_ENDED) return
                        val cur = items.getOrNull(index) ?: return
                        if (!isVod) { play(cur); return }          // live stream hiccup: start again
                        val next = items.getOrNull(index + 1)
                        progress.finished(cur, next)
                        if (next != null) { index++; play(next) }   // series: the next episode
                        else stopSelf()                             // film over: the window closes
                    }
                })
            }
        p.volume = if (_state.value.muted) 0f else 1f
        if (vod) p.setMediaItem(MediaItem.fromUri(url), startPositionFor(item)) else p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun params(): WindowManager.LayoutParams {
        val s = _state.value
        val screen = resources.displayMetrics
        val w = screen.widthPixels * s.percent / 100
        val margin = (screen.density * 28).toInt()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            w, w * 9 / 16, type,
            // never takes the remote or touches away from the app underneath
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (s.corner) {
                1 -> Gravity.BOTTOM or Gravity.START
                2 -> Gravity.TOP or Gravity.START
                3 -> Gravity.TOP or Gravity.END
                else -> Gravity.BOTTOM or Gravity.END
            }
            x = margin; y = margin
        }
    }

    private fun show() {
        if (view == null) {
            val v = PlayerView(this).apply {
                useController = false
                player = this@FloatingPlayer.player
                setBackgroundColor(0xFF000000.toInt())
            }
            runCatching { wm.addView(v, params()) }.onFailure {
                de.koeven.couchpotato.ui.Notice.show("Kleines Fenster nicht erlaubt – Freigabe per adb fehlt (siehe Hinweis)", long = true)
                stopSelf(); return
            }
            view = v
        } else {
            runCatching { wm.updateViewLayout(view, params()) }
        }
    }

    /**
     * Minimised: the window disappears and the stream stops (no connection, no work for the
     * stick) – the phone remote keeps running and brings it back with the same channel.
     */
    private fun setMinimized(on: Boolean) {
        if (_state.value.minimized == on) return
        _state.value = _state.value.copy(minimized = on)
        if (on) {
            saveCurrent()
            removeQr()
            view?.visibility = android.view.View.GONE
            player?.stop()
        } else {
            view?.visibility = android.view.View.VISIBLE
            items.getOrNull(index)?.let { play(it) }
        }
    }

    override fun onDestroy() {
        saveCurrent()
        main.removeCallbacks(saveTask)
        removeQr()
        WindowRemote.stop()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        player?.release()
        player = null
        instance = null
        _state.value = State()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "floating"
        private var instance: FloatingPlayer? = null
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        /** "Über anderen Apps einblenden" granted? (adb shell appops set de.koeven.couchpotato SYSTEM_ALERT_WINDOW allow) */
        fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

        fun start(context: Context, items: List<de.koeven.couchpotato.data.PlayItem>, index: Int) {
            val json = org.json.JSONArray(items.map { it.toJson() }).toString()
            val i = Intent(context, FloatingPlayer::class.java).putExtra(Playback.EXTRA_ITEMS, json).putExtra(Playback.EXTRA_INDEX, index)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) { context.stopService(Intent(context, FloatingPlayer::class.java)) }

        fun next() = instance?.zap(1)
        fun playList(list: List<de.koeven.couchpotato.data.PlayItem>, index: Int) = instance?.playItems(list, index)
        fun showPhoneQr() = instance?.showQr()
        fun minimize() = instance?.setMinimized(true)
        fun restore() = instance?.setMinimized(false)
        fun previous() = instance?.zap(-1)
        fun seekBy(ms: Long) = instance?.seek(ms)
        fun pauseToggle() = instance?.togglePause()
        fun fullscreen() = instance?.fullscreen()

        fun toggleSound() {
            val s = _state.value
            _state.value = s.copy(muted = !s.muted)
            instance?.player?.volume = if (s.muted) 1f else 0f
        }

        fun nextCorner() {
            _state.value = _state.value.let { it.copy(corner = (it.corner + 1) % 4) }
            instance?.show()
            prefs?.edit()?.putInt("corner", _state.value.corner)?.apply()
        }

        /** Width in % of the screen: 20 … 60. */
        val SIZES = listOf(20, 30, 40, 50, 60)

        fun resize(delta: Int) {
            _state.value = _state.value.let { it.copy(size = (it.size + delta).coerceIn(0, SIZES.lastIndex)) }
            instance?.show()
            prefs?.edit()?.putInt("size", _state.value.size)?.apply()
        }

        private var prefs: android.content.SharedPreferences? = null
    }
}
