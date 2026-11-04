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

    data class State(val active: Boolean = false, val title: String = "", val muted: Boolean = true, val corner: Int = 0, val big: Boolean = false)

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Live-TV"
        goForeground(title)
        play(url)
        _state.value = State(active = true, title = title)
        show()
        return START_NOT_STICKY
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

    private fun play(url: String) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val p = player ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build().also { player = it }
        p.volume = 0f
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                de.koeven.couchpotato.ui.Notice.show("Kleines Fenster: Wiedergabe fehlgeschlagen (${error.errorCodeName})", long = true)
                stopSelf()
            }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun params(): WindowManager.LayoutParams {
        val s = _state.value
        val screen = resources.displayMetrics
        val w = screen.widthPixels * (if (s.big) 45 else 30) / 100
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

    override fun onDestroy() {
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
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private var instance: FloatingPlayer? = null
        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        /** "Über anderen Apps einblenden" granted? (adb shell appops set de.koeven.couchpotato SYSTEM_ALERT_WINDOW allow) */
        fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

        fun start(context: Context, url: String, title: String) {
            val i = Intent(context, FloatingPlayer::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) { context.stopService(Intent(context, FloatingPlayer::class.java)) }

        fun toggleSound() {
            val s = _state.value
            _state.value = s.copy(muted = !s.muted)
            instance?.player?.volume = if (s.muted) 1f else 0f
        }

        fun nextCorner() {
            _state.value = _state.value.let { it.copy(corner = (it.corner + 1) % 4) }
            instance?.show()
        }

        fun toggleSize() {
            _state.value = _state.value.let { it.copy(big = !it.big) }
            instance?.show()
        }
    }
}
