package de.koeven.couchpotato.player

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import de.koeven.couchpotato.data.Profiles
import de.koeven.couchpotato.data.ScreenTime
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import android.net.Uri
import java.io.File

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
        playerView.setShowSubtitleButton(true)
        this.cfg = cfg
        applyTrackPrefs()
        player.addListener(listener)
        buildUpNext()
        buildOverlays()
        handler.post(upNextTick)

        if (isLive) {
            startChannel(currentIndex)
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

    private lateinit var cfg: de.koeven.couchpotato.data.XtreamConfig

    // ---------- audio track & subtitles: remembered per profile ----------

    private val trackPrefs by lazy { getSharedPreferences("player" + Profiles.suffix(this), MODE_PRIVATE) }
    private var applyingTracks = false
    private var userChangedTracks = false

    private fun applyTrackPrefs() {
        val audio = trackPrefs.getString("audio", null)
        val text = trackPrefs.getString("text", null)
        applyingTracks = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (audio != null) setPreferredAudioLanguage(audio)
            when (text) {
                null -> Unit
                "off" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false); setPreferredTextLanguage(text) }
            }
        }.build()
        applyingTracks = false
    }

    /** Called after the viewer picked something in the player's audio/subtitle menu. */
    private fun rememberTracks(tracks: Tracks) {
        fun selectedLang(type: Int): String? = tracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language }
        }
        val edit = trackPrefs.edit()
        selectedLang(C.TRACK_TYPE_AUDIO)?.takeIf { it != "und" }?.let { edit.putString("audio", it) }
        val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        if (hasText) {
            val text = selectedLang(C.TRACK_TYPE_TEXT)
            when {
                text == null -> edit.putString("text", "off")
                text != "und" -> edit.putString("text", text)
            }
        }
        edit.apply()
    }

    // ---------- live: timeshift buffer, last channel ----------

    private var timeshift: TimeshiftBuffer? = null
    private var timeshiftOn = true          // switched off for a channel the buffer can't handle
    private var liveIndex = -1
    private var previousChannel = -1
    private var liveStartWall = 0L          // wall time of the frame the current playback started with

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun startChannel(index: Int, fromOffset: Long? = null) {
        val item = items.getOrNull(index) ?: return
        if (index != liveIndex) {
            if (liveIndex >= 0) previousChannel = liveIndex
            liveIndex = index
            currentIndex = index
            timeshift?.stop()
            timeshift = if (timeshiftOn) {
                TimeshiftBuffer(XtreamClient.streamUrl(cfg, item), File(cacheDir, "timeshift")).start()
            } else null
        }
        val meta = MediaMetadata.Builder().setTitle(item.title).build()
        val buf = timeshift
        if (buf == null) {
            // direct stream (fallback): no rewinding
            player.setMediaItem(MediaItem.Builder().setUri(XtreamClient.streamUrl(cfg, item)).setMediaId(item.key).setMediaMetadata(meta).build())
            liveStartWall = System.currentTimeMillis()
        } else {
            val from = fromOffset ?: buf.head
            liveStartWall = if (fromOffset == null) System.currentTimeMillis() else buf.wallAt(from)
            val source = ProgressiveMediaSource.Factory(
                { TimeshiftDataSource { timeshift } },
                DefaultExtractorsFactory(),
            ).createMediaSource(
                MediaItem.Builder().setUri(Uri.parse("timeshift://live?from=$from&ch=${item.streamId}"))
                    .setMediaId(item.key).setMediaMetadata(meta).build()
            )
            player.setMediaSource(source)
        }
        player.prepare()
        player.playWhenReady = true
        updateLiveLabel()
    }

    /** How far behind live the picture is (0 = live). */
    private fun behindMs(): Long {
        if (timeshift == null || !::player.isInitialized) return 0L
        return ((System.currentTimeMillis() - liveStartWall) - player.currentPosition).coerceAtLeast(0L)
    }

    private fun timeshiftBy(deltaMs: Long) {
        val buf = timeshift ?: run { toast("Zurückspulen geht bei diesem Sender nicht"); return }
        val target = (behindMs() - deltaMs).coerceIn(0L, minOf(TimeshiftBuffer.KEEP_MS, buf.spanMs()))
        if (target < 8_000L) {
            goLive(); return
        }
        val (offset, _) = buf.offsetAt(target)
        startChannel(liveIndex, offset)
        toast("−${fmt(target)} · ▶▶ Richtung Live")
    }

    private fun goLive() {
        if (timeshift != null && behindMs() > 8_000L) {
            startChannel(liveIndex, null)
            toast("Live")
        }
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private lateinit var notice: TextView
    private lateinit var menuHint: TextView
    private val hideNotice = Runnable { if (::notice.isInitialized) notice.visibility = View.GONE }

    private fun toast(text: String) {
        if (!::notice.isInitialized) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); return
        }
        notice.text = text
        notice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, 2_600L)
    }

    private lateinit var liveLabel: TextView

    private fun updateLiveLabel() {
        if (!::liveLabel.isInitialized) return
        val behind = if (isLive) behindMs() else 0L
        val sleep = sleepLabel()
        val parts = listOfNotNull(
            if (behind > 10_000L) "⏪ −${fmt(behind)}" else null,
            sleep,
        )
        liveLabel.text = parts.joinToString("   ·   ")
        liveLabel.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- sleep timer ----------

    private var sleepAt = 0L                 // wall clock, 0 = off
    private var sleepEndOfEpisode = false

    private fun sleepLabel(): String? = when {
        sleepEndOfEpisode -> "☾ nach dieser Folge"
        sleepAt > 0 -> "☾ ${((sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} Min."
        else -> null
    }

    private fun cycleSleep() {
        val steps = buildList {
            add(0); add(15); add(30); add(60); add(90)
            if (!isLive && items.getOrNull(player.currentMediaItemIndex)?.kind == PlayItem.KIND_EPISODE) add(-1)
        }
        val current = when { sleepEndOfEpisode -> -1; sleepAt > 0 -> ((sleepAt - System.currentTimeMillis()) / 60_000 + 1).toInt(); else -> 0 }
        // next step after the current one (a running timer counts as its rounded-up step)
        val idx = steps.indexOfFirst { it == current }.let { if (it < 0) steps.indexOfFirst { s -> s > current }.coerceAtLeast(0) - 1 else it }
        val next = steps[(idx + 1).mod(steps.size)]
        sleepEndOfEpisode = next == -1
        sleepAt = if (next > 0) System.currentTimeMillis() + next * 60_000L else 0L
        if (!sleepEndOfEpisode && !stillWatching) player.pauseAtEndOfMediaItems = false
        if (sleepEndOfEpisode) player.pauseAtEndOfMediaItems = true
        toast(when { next == -1 -> "Sleep-Timer: nach dieser Folge"; next > 0 -> "Sleep-Timer: $next Minuten"; else -> "Sleep-Timer aus" })
        updateLiveLabel()
        refreshMenu()
    }

    private fun goToSleep(message: String) {
        saveCurrent()
        player.playWhenReady = false
        de.koeven.couchpotato.ui.Notice.show(message, long = true)   // shown in the launcher
        finish()
    }

    // ---------- menu (≡ key): last channel, live, sleep timer ----------

    private lateinit var menuPanel: LinearLayout

    private fun buildOverlays() {
        liveLabel = TextView(this).apply {
            setTextColor(TEXT); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(PANEL) }
            visibility = View.GONE
        }
        // notices in Couchpotato's style instead of the small system toasts
        notice = TextView(this).apply {
            setTextColor(TEXT); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = panelBackground()
            visibility = View.GONE
        }
        (playerView.parent as FrameLayout).addView(notice, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, dp(28), 0, 0) })
        // "≡ Menü" while the controls are visible, so the menu can be found
        menuHint = TextView(this).apply {
            text = "≡  Menü: Sleep-Timer" + if (isLive) " · letzter Sender · zurückspulen" else ""
            setTextColor(TEXT); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(PANEL) }
            visibility = View.GONE
        }
        (playerView.parent as FrameLayout).addView(menuHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(40), dp(28), 0, 0) })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            menuHint.visibility = if (v == View.VISIBLE && menuPanel.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        })
        (playerView.parent as FrameLayout).addView(liveLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, dp(28), dp(40), 0) })
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = panelBackground()
            visibility = View.GONE
        }
        (playerView.parent as FrameLayout).addView(menuPanel, FrameLayout.LayoutParams(
            dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END,
        ).apply { setMargins(0, 0, dp(48), 0) })
    }

    private fun refreshMenu() {
        if (!::menuPanel.isInitialized || menuPanel.visibility != View.VISIBLE) return
        val focused = menuPanel.indexOfChild(menuPanel.findFocus())
        fillMenu()
        menuPanel.getChildAt(focused.coerceAtLeast(1))?.requestFocus()
    }

    private fun fillMenu() {
        menuPanel.removeAllViews()
        menuPanel.addView(TextView(this).apply {
            text = if (isLive) items.getOrNull(liveIndex)?.title ?: "Live-TV" else "Wiedergabe"
            setTextColor(ACCENT); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10))
        })
        fun add(label: String, action: () -> Unit) {
            menuPanel.addView(pillButton(label).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (isLive) {
            items.getOrNull(previousChannel)?.let { prev -> add("↩ Zurück zu ${prev.title}") { hideMenu(); switchTo(previousChannel) } }
            if (behindMs() > 8_000L) add("▶▶ Zurück zu Live") { hideMenu(); goLive() }
            if (timeshift != null) {
                add("⏪ 30 Sekunden zurück") { timeshiftBy(-30_000L); refreshMenu() }
                add("⏪ 5 Minuten zurück") { timeshiftBy(-5 * 60_000L); refreshMenu() }
            }
        }
        add("☾ Sleep-Timer: ${sleepLabel()?.removePrefix("☾ ") ?: "aus"}") { cycleSleep() }
        add("Schließen") { hideMenu() }
    }

    private fun showMenu() {
        fillMenu()
        playerView.hideController()
        menuHint.visibility = View.GONE
        menuPanel.visibility = View.VISIBLE
        menuPanel.getChildAt(1)?.requestFocus()
    }

    private fun hideMenu() {
        menuPanel.visibility = View.GONE
        playerView.requestFocus()
    }

    private fun switchTo(index: Int) {
        handler.removeCallbacks(zapCommit)
        zapTarget = -1
        toast(items[index].title)
        startChannel(index)
    }

    // ---------- "Nächste Folge" like Netflix ----------

    private lateinit var upNext: LinearLayout
    private lateinit var upNextTitle: TextView
    private lateinit var upNextSub: TextView
    private lateinit var upNextPlay: Button
    private lateinit var upNextCredits: Button
    private var upNextShownFor = -1          // media index the panel was shown (or dismissed) for
    private var upNextDeadline = 0L
    private var stillWatching = false        // "Schaust du noch?" instead of a countdown
    private var autoInARow = 0               // episodes started without any key press in between

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Couchpotato's look in the player too: petrol panels, gold focus (like the menus in the launcher). */
    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat(); setColor(PANEL); setStroke(dp(2), 0x40FFFFFF)
    }

    private fun pillButton(text: String): Button {
        fun pill(bg: Int) = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(bg) }
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(22), 0, dp(22), 0)
            minHeight = dp(44); minimumHeight = dp(44)
            stateListAnimator = null
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pill(ACCENT))
                addState(intArrayOf(), pill(0x1FFFFFFF))
            }
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(ON_ACCENT, TEXT),
            ))
            isFocusable = true
        }
    }

    private fun buildUpNext() {
        fun button(text: String) = pillButton(text)
        upNextTitle = TextView(this).apply {
            setTextColor(ACCENT); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }
        upNextSub = TextView(this).apply {
            setTextColor(TEXT); textSize = 21f; setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; setPadding(0, dp(2), 0, dp(14))
        }
        upNextPlay = button("Jetzt abspielen").apply { setOnClickListener { playNextNow() } }
        upNextCredits = button("Abspann ansehen").apply { setOnClickListener { dismissUpNext() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(upNextPlay)
            addView(upNextCredits, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        upNext = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(20), dp(26), dp(22))
            background = panelBackground()
            visibility = View.GONE
            addView(upNextTitle); addView(upNextSub); addView(row)
        }
        (playerView.parent as FrameLayout).addView(upNext, FrameLayout.LayoutParams(
            dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(48), dp(56)) })
    }

    private val upNextTick = object : Runnable {
        override fun run() {
            updateUpNext()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateUpNext() {
        if (!::player.isInitialized) return
        periodicChecks()
        if (isLive || !::upNext.isInitialized) return
        if (sleepEndOfEpisode) return
        val i = player.currentMediaItemIndex
        val next = items.getOrNull(i + 1)
        val dur = player.duration
        if (next == null || items[i].kind != PlayItem.KIND_EPISODE || dur == C.TIME_UNSET || dur < 120_000) {
            if (upNext.visibility == View.VISIBLE) hideUpNext()
            return
        }
        val remaining = dur - player.currentPosition
        if (upNext.visibility != View.VISIBLE) {
            if (remaining in 1..UP_NEXT_BEFORE_END_MS && upNextShownFor != i && player.isPlaying) showUpNext(i, next)
            return
        }
        if (remaining > UP_NEXT_BEFORE_END_MS + 5_000) { hideUpNext(); return }   // seeked back
        if (stillWatching) return
        val left = ((upNextDeadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        upNextPlay.text = "Nächste Folge in $left s"
        if (left <= 0) playNextNow(auto = true)
    }

    private var lastChecks = 0L

    /** Every few seconds: sleep timer, kids' screen time, the "behind live" label. */
    private fun periodicChecks() {
        val now = System.currentTimeMillis()
        if (now - lastChecks < 2_000L) return
        lastChecks = now
        updateLiveLabel()
        if (sleepAt in 1..now) { sleepAt = 0; goToSleep("Sleep-Timer: Gute Nacht"); return }
        when (val st = ScreenTime.state(this)) {
            ScreenTime.State.LimitReached -> goToSleep("Bildschirmzeit für heute ist vorbei")
            is ScreenTime.State.Bedtime -> goToSleep("Schlafenszeit – ab ${st.at} Uhr ist Schluss")
            is ScreenTime.State.Allowed -> if (st.remainingMin == 5 && !warned5) { warned5 = true; toast("Noch 5 Minuten Bildschirmzeit") }
        }
    }
    private var warned5 = false

    private fun showUpNext(index: Int, next: PlayItem) {
        upNextShownFor = index
        stillWatching = autoInARow >= STILL_WATCHING_AFTER
        upNextTitle.text = if (stillWatching) "Schaust du noch?" else "Nächste Folge"
        upNextSub.text = next.subtitle ?: next.title
        upNextPlay.text = if (stillWatching) "Weiterschauen" else "Nächste Folge in ${UP_NEXT_COUNTDOWN_MS / 1000} s"
        upNextCredits.text = if (stillWatching) "Beenden" else "Abspann ansehen"
        // "Schaust du noch?": stop at the end of this episode instead of rolling on all night
        player.pauseAtEndOfMediaItems = stillWatching
        upNextDeadline = System.currentTimeMillis() + UP_NEXT_COUNTDOWN_MS
        playerView.hideController()
        upNext.visibility = View.VISIBLE
        upNextPlay.requestFocus()
    }

    private fun hideUpNext() {
        upNext.visibility = View.GONE
        player.pauseAtEndOfMediaItems = sleepEndOfEpisode
        playerView.requestFocus()
    }

    private fun dismissUpNext() {
        if (stillWatching) { saveCurrent(); finish(); return }
        hideUpNext()   // credits play on; ExoPlayer still moves on at the very end
    }

    private fun playNextNow(auto: Boolean = false) {
        val i = player.currentMediaItemIndex
        if (i + 1 > items.lastIndex) return
        autoInARow = if (auto) autoInARow + 1 else 0
        hideUpNext()
        progress.finished(items[i], items[i + 1])
        player.seekToDefaultPosition(i + 1)
        player.playWhenReady = true
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
                autoInARow++
            }
            if (::upNext.isInitialized && upNext.visibility == View.VISIBLE) hideUpNext()
            currentIndex = player.currentMediaItemIndex
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            if (!applyingTracks) userChangedTracks = true
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!userChangedTracks) return
            userChangedTracks = false
            rememberTracks(tracks)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // sleep timer "nach dieser Folge": the player stopped at the end of the episode
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfEpisode) {
                sleepEndOfEpisode = false
                goToSleep("Sleep-Timer: Gute Nacht")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && isLive) {
                // live stream ended (provider hiccup): start the channel again
                startChannel(liveIndex.coerceAtLeast(0), null)
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
            // Live: stay, so you can zap to the next channel. VOD: back to the launcher.
            if (!isLive) finish()
            else if (timeshift != null) {
                // the buffer didn't work for this channel: play it directly (without rewinding)
                timeshiftOn = false
                timeshift?.stop(); timeshift = null
                val i = liveIndex; liveIndex = -1
                startChannel(i)
                timeshiftOn = true
            }
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

    private var resumeChannel = -1

    override fun onStart() {
        super.onStart()
        handler.postDelayed(saveTask, SAVE_INTERVAL_MS)
        if (isLive && resumeChannel >= 0 && ::player.isInitialized) {
            val ch = resumeChannel; resumeChannel = -1
            startChannel(ch)
        }
    }

    override fun onStop() {
        handler.removeCallbacks(saveTask)
        saveCurrent()
        if (::player.isInitialized) player.playWhenReady = false
        // live in the background: close the provider connection (most allow only one or two)
        if (isLive && liveIndex >= 0) {
            resumeChannel = liveIndex
            timeshift?.stop(); timeshift = null
            liveIndex = -1
            if (::player.isInitialized) player.stop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(zapCommit)
        handler.removeCallbacks(saveTask)
        handler.removeCallbacks(upNextTick)
        handler.removeCallbacks(hideNotice)
        timeshift?.stop(); timeshift = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) autoInARow = 0   // someone is on the couch
        // "Nächste Folge" panel: D-pad moves between its buttons, Back closes it
        if (::upNext.isInitialized && upNext.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismissUpNext()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                hideUpNext()
            } else {
                return super.dispatchKeyEvent(event)
            }
        }
        // ≡ menu panel: last channel, live, sleep timer
        if (::menuPanel.isInitialized && menuPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) hideMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && ::player.isInitialized) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        if (isLive && ::player.isInitialized && event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL) {
            if (event.action == KeyEvent.ACTION_DOWN && previousChannel >= 0) switchTo(previousChannel)
            return true
        }
        // Live timeshift: ◀ / ⏪ back 30 s, ▶ / ⏩ forward 30 s (towards live) while the controls are hidden
        if (isLive && ::player.isInitialized && !playerView.isControllerFullyVisible) {
            val delta = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -30_000L
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 30_000L
                else -> 0L
            }
            if (delta != 0L) {
                if (event.action == KeyEvent.ACTION_DOWN) timeshiftBy(delta)
                return true
            }
        }
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
        if (target < 0 || target == liveIndex) return@Runnable
        startChannel(target)
    }

    private fun zap(step: Int) {
        if (items.size < 2) return
        val n = items.size
        val from = if (zapTarget >= 0) zapTarget else liveIndex.coerceAtLeast(0)
        zapTarget = ((from + step) % n + n) % n
        toast(items[zapTarget].title)
        handler.removeCallbacks(zapCommit)
        handler.postDelayed(zapCommit, ZAP_DELAY_MS)
    }

    private companion object {
        const val ZAP_DELAY_MS = 800L
        // Couchpotato colours (ui/Theme.kt): menu petrol, gold accent, cream text
        const val PANEL = 0xF20B3236.toInt()
        const val ACCENT = 0xFFE8B86D.toInt()
        const val ON_ACCENT = 0xFF1B1408.toInt()
        const val TEXT = 0xFFF4F1EC.toInt()
        const val UP_NEXT_BEFORE_END_MS = 25_000L   // panel appears 25 s before the end (credits)
        const val UP_NEXT_COUNTDOWN_MS = 10_000L
        const val STILL_WATCHING_AFTER = 3          // episodes in a row without touching the remote
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
