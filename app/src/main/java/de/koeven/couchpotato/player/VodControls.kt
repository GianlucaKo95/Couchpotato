package de.koeven.couchpotato.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.SeekParameters
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.PlayItem
import java.util.Locale

/**
 * Netflix-like controls for films and series: title on top, a timeline with a scrub preview and
 * big buttons below (play/pause, ±10 s, next episode, episodes, audio & subtitles, sleep timer).
 *
 * Scrubbing preview: the provider streams carry no preview images, and making some would mean
 * extra connections to the provider. So while you hold ◀ / ▶ the main picture jumps to the
 * nearest keyframe – but only once you pause for a moment, so it's one request, not dozens.
 */
internal class VodControls(private val a: PlayerActivity, root: FrameLayout) {

    private val player get() = a.player
    private val ctx: Context = a
    private fun dp(v: Int) = a.dp(v)

    private val overlay = FrameLayout(ctx).apply { visibility = View.GONE }
    private val title = TextView(ctx)
    private val subtitle = TextView(ctx)
    private val bar = ScrubBar(ctx)
    private val remaining = TextView(ctx)
    private val buttons = LinearLayout(ctx)
    private lateinit var playBtn: LinearLayout
    private lateinit var nextBtn: LinearLayout
    private lateinit var episodesBtn: LinearLayout
    private lateinit var sleepBtn: LinearLayout
    private val panel = LinearLayout(ctx)
    private val panelScroll = ScrollView(ctx)

    val visible: Boolean get() = overlay.visibility == View.VISIBLE
    private val panelOpen: Boolean get() = panelScroll.visibility == View.VISIBLE

    // scrubbing
    private var scrubPos: Long? = null
    private var scrubOrigin = 0L
    private var wasPlaying = false
    private val previewSeek = Runnable {
        scrubPos?.let { player.setSeekParameters(SeekParameters.CLOSEST_SYNC); player.seekTo(it) }
    }
    private val autoHide = Runnable { if (scrubPos == null && !panelOpen && player.isPlaying) hide() }

    init {
        // dark gradients top and bottom, so text and buttons stay readable over any picture
        overlay.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200), Gravity.TOP))
        overlay.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM))

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            title.apply { setTextColor(TEXT); textSize = 30f; setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            subtitle.apply { setTextColor(MUTED); textSize = 19f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            addView(title); addView(subtitle)
        }
        overlay.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { setMargins(dp(56), dp(36), dp(56), 0) })

        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val barRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bar, LinearLayout.LayoutParams(0, dp(78), 1f))
            remaining.apply { setTextColor(TEXT); textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(16), dp(22), 0, 0) }
            addView(remaining)
        }
        bottom.addView(barRow)
        buttons.orientation = LinearLayout.HORIZONTAL
        playBtn = iconButton(R.drawable.ic_pl_pause, "Pause") { togglePlay() }
        val back10 = iconButton(R.drawable.ic_pl_rewind, "10 s") { jump(-10_000) }
        val fwd10 = iconButton(R.drawable.ic_pl_forward, "10 s") { jump(10_000) }
        nextBtn = iconButton(R.drawable.ic_pl_next, "Nächste Folge") { hide(); a.playNextNow() }
        episodesBtn = iconButton(R.drawable.ic_pl_episodes, "Folgen") { showEpisodes() }
        val tracks = iconButton(R.drawable.ic_pl_subtitles, "Audio & Untertitel") { showTracks() }
        sleepBtn = iconButton(R.drawable.ic_pl_sleep, "Sleep-Timer") { a.cycleSleep(); refresh() }
        listOf(playBtn, back10, fwd10, nextBtn, episodesBtn, tracks, sleepBtn).forEach { b ->
            buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        }
        bottom.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        overlay.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(48), 0, dp(56), dp(40)) })

        // side panel: episodes / audio & subtitles
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(22), dp(18), dp(22), dp(18))
        panelScroll.apply {
            background = a.panelBackground()
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        overlay.addView(panelScroll, FrameLayout.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { setMargins(0, dp(40), dp(48), dp(40)) })

        bar.isFocusable = true
        bar.setOnKeyListener { _, code, e -> onBarKey(code, e) }
        bar.setOnFocusChangeListener { _, _ -> bar.invalidate() }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------------------------------------------------------------- show / hide

    fun show(focus: View? = null) {
        refresh()
        overlay.visibility = View.VISIBLE
        (focus ?: if (overlay.findFocus() == null) playBtn else null)?.requestFocus()
        poke()
    }

    fun hide() {
        cancelScrub()
        panelScroll.visibility = View.GONE
        overlay.visibility = View.GONE
        a.playerView.requestFocus()
    }

    /** Any key: keep the controls up a bit longer. */
    private fun poke() {
        a.handler.removeCallbacks(autoHide)
        a.handler.postDelayed(autoHide, 5_000L)
    }

    /** Called twice a second by the player. */
    fun refresh() {
        val item = a.items.getOrNull(player.currentMediaItemIndex) ?: return
        val episode = item.kind == PlayItem.KIND_EPISODE
        title.text = item.title
        subtitle.text = item.subtitle.orEmpty()
        subtitle.visibility = if (item.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        bar.duration = dur
        bar.position = player.currentPosition
        bar.buffered = player.bufferedPosition
        bar.scrub = scrubPos
        bar.invalidate()
        val shown = scrubPos ?: player.currentPosition
        remaining.text = if (dur > 0) "−" + fmt(dur - shown) else fmt(shown)
        setButton(playBtn, if (player.playWhenReady) R.drawable.ic_pl_pause else R.drawable.ic_pl_play,
            if (player.playWhenReady) "Pause" else "Abspielen")
        nextBtn.visibility = if (episode && a.items.getOrNull(player.currentMediaItemIndex + 1) != null) View.VISIBLE else View.GONE
        episodesBtn.visibility = if (episode && a.items.size > 1) View.VISIBLE else View.GONE
        setButton(sleepBtn, R.drawable.ic_pl_sleep, a.sleepLabel()?.removePrefix("☾ ")?.let { "Sleep: $it" } ?: "Sleep-Timer")
    }

    // ---------------------------------------------------------------- keys

    /** Returns true when the key was used by the controls. */
    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (!visible) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down && e.repeatCount == 0) show()
                    return true
                }
                // ◀ ▶ while hidden: start scrubbing right away, like Netflix
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (down) { show(bar); onBarKey(e.keyCode, e) }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (down && e.repeatCount == 0) { togglePlay(); show() }
                    return true
                }
            }
            return false
        }
        poke()
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (!down) when {
                    scrubPos != null -> cancelScrub()
                    panelOpen -> closePanel()
                    else -> hide()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && e.repeatCount == 0) { if (scrubPos != null) commitScrub() else togglePlay() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) { if (!bar.isFocused) bar.requestFocus(); onBarKey(e.keyCode, e) }
                return true
            }
        }
        return false     // D-pad between the buttons: normal focus handling
    }

    private fun onBarKey(code: Int, e: KeyEvent): Boolean {
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
            else -> 0
        }
        if (dir != 0) {
            if (e.action == KeyEvent.ACTION_DOWN) scrubStep(dir, e.repeatCount)
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (e.action == KeyEvent.ACTION_UP) { if (scrubPos != null) commitScrub() else togglePlay() }
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- scrubbing

    private fun scrubStep(dir: Int, repeat: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (scrubPos == null) {
            scrubOrigin = player.currentPosition
            wasPlaying = player.playWhenReady
            player.pause()
        }
        // holding the button goes faster: 10 s, then 30 s, then 1 min steps
        val step = when { repeat < 4 -> 10_000L; repeat < 14 -> 30_000L; else -> 60_000L }
        scrubPos = ((scrubPos ?: scrubOrigin) + dir * step).coerceIn(0L, dur - 1_000L)
        a.handler.removeCallbacks(previewSeek)
        a.handler.postDelayed(previewSeek, 450L)
        refresh()
    }

    private fun commitScrub() {
        val target = scrubPos ?: return
        a.handler.removeCallbacks(previewSeek)
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.seekTo(target)
        scrubPos = null
        player.playWhenReady = true      // like Netflix: OK on the timeline plays from there
        refresh()
    }

    private fun cancelScrub() {
        if (scrubPos == null) return
        a.handler.removeCallbacks(previewSeek)
        player.setSeekParameters(SeekParameters.DEFAULT)
        player.seekTo(scrubOrigin)
        scrubPos = null
        player.playWhenReady = wasPlaying
        refresh()
    }

    private fun jump(ms: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0L, dur))
        refresh()
    }

    private fun togglePlay() {
        player.playWhenReady = !player.playWhenReady
        refresh()
    }

    // ---------------------------------------------------------------- side panels

    private fun openPanel(heading: String, entries: List<Triple<String, Boolean, () -> Unit>>) {
        panel.removeAllViews()
        panel.addView(TextView(ctx).apply {
            text = heading; setTextColor(ACCENT); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(8))
        })
        var focusMe: View? = null
        entries.forEach { (label, selected, action) ->
            val b = a.pillButton((if (selected) "✓  " else "") + label).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 2
                setOnClickListener { action(); closePanel() }
            }
            panel.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            if (selected && focusMe == null) focusMe = b
        }
        panelScroll.visibility = View.VISIBLE
        (focusMe ?: panel.getChildAt(1))?.let { v -> v.requestFocus(); panelScroll.post { panelScroll.smoothScrollTo(0, v.top - dp(60)) } }
    }

    private fun closePanel() {
        panelScroll.visibility = View.GONE
        playBtn.requestFocus()
        poke()
    }

    private fun showEpisodes() {
        val now = player.currentMediaItemIndex
        openPanel("Folgen", a.items.mapIndexed { i, it ->
            Triple(it.subtitle ?: it.title, i == now) { if (i != now) { player.seekToDefaultPosition(i); player.playWhenReady = true } }
        })
    }

    private fun showTracks() {
        val groups = player.currentTracks.groups
        val entries = mutableListOf<Triple<String, Boolean, () -> Unit>>()
        groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val name = "Ton: " + langName(f.language, f.label) + (if (f.channelCount >= 6) " (5.1)" else "")
                entries += Triple(name, g.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        val texts = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (texts.isNotEmpty()) {
            val anyOn = texts.any { it.isSelected }
            entries += Triple("Untertitel: Aus", !anyOn) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            texts.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    entries += Triple("Untertitel: " + langName(f.language, f.label), g.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
        }
        if (entries.isEmpty()) { a.toast("Dieser Titel hat nur eine Tonspur und keine Untertitel"); return }
        openPanel("Audio & Untertitel", entries)
    }

    private fun langName(code: String?, label: String?): String {
        val fromCode = code?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.GERMAN).takeIf { n -> n.isNotBlank() } }
        return label?.takeIf { it.isNotBlank() } ?: fromCode ?: "Spur"
    }

    // ---------------------------------------------------------------- views

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        fun pill(bg: Int) = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(bg) }
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(ON_ACCENT, TEXT))
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(10), dp(20), dp(10))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pill(ACCENT))
                addState(intArrayOf(), pill(0x26FFFFFF))
            }
            isDuplicateParentStateEnabled = false
            addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = colors; isDuplicateParentStateEnabled = true },
                LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(TextView(ctx).apply {
                text = label; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(colors)
                isDuplicateParentStateEnabled = true; setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { onClick(); poke() }
        }
    }

    private fun setButton(b: LinearLayout, icon: Int, label: String) {
        (b.getChildAt(0) as ImageView).setImageResource(icon)
        (b.getChildAt(1) as TextView).text = label
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The timeline: played part in gold, buffered lighter, a bubble with the time while scrubbing. */
    private inner class ScrubBar(c: Context) : View(c) {
        var duration = 0L
        var position = 0L
        var buffered = 0L
        var scrub: Long? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = dp(18).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val left = dp(10).toFloat(); val right = width - dp(10).toFloat()
            val cy = height - dp(18).toFloat()
            val h = if (isFocused) dp(8).toFloat() else dp(5).toFloat()
            fun x(ms: Long) = if (duration <= 0) left else left + (right - left) * (ms.coerceIn(0, duration).toFloat() / duration)
            paint.color = 0x4DFFFFFF
            r.set(left, cy - h / 2, right, cy + h / 2); canvas.drawRoundRect(r, h, h, paint)
            paint.color = 0x80FFFFFF.toInt()
            r.set(left, cy - h / 2, x(buffered), cy + h / 2); canvas.drawRoundRect(r, h, h, paint)
            val shown = scrub ?: position
            paint.color = ACCENT
            r.set(left, cy - h / 2, x(shown), cy + h / 2); canvas.drawRoundRect(r, h, h, paint)
            val tx = x(shown)
            canvas.drawCircle(tx, cy, if (isFocused) dp(12).toFloat() else dp(8).toFloat(), paint)
            if (scrub != null || isFocused) {
                // time bubble above the thumb (the picture behind it is the preview)
                val label = fmt(shown)
                val w = text.measureText(label) + dp(24)
                val bx = tx.coerceIn(left + w / 2, right - w / 2)
                paint.color = PANEL
                r.set(bx - w / 2, cy - dp(58), bx + w / 2, cy - dp(22)); canvas.drawRoundRect(r, dp(10).toFloat(), dp(10).toFloat(), paint)
                canvas.drawText(label, bx, cy - dp(33), text)
            }
        }
    }

    companion object {
        const val PANEL = 0xF20B3236.toInt()
        const val ACCENT = 0xFFE8B86D.toInt()
        const val ON_ACCENT = 0xFF1B1408.toInt()
        const val TEXT = 0xFFF4F1EC.toInt()
        const val MUTED = 0xFFC3D6D7.toInt()
    }
}
