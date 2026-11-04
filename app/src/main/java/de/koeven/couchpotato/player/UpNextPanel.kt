package de.koeven.couchpotato.player

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.player.PlayerStyle.ACCENT
import de.koeven.couchpotato.player.PlayerStyle.TEXT

/**
 * "Nächste Folge" like Netflix: 25 s before the end a panel with the next episode's picture and
 * a 10 s countdown. After 3 episodes without a key press it asks "Schaust du noch?" instead.
 */
internal class UpNextPanel(private val a: PlayerActivity, root: FrameLayout) {
    private val panel: LinearLayout
    private val title: TextView
    private val sub: TextView
    private val image: ImageView
    private val play: Button
    private val credits: Button
    private val images by lazy { coil.ImageLoader.Builder(a).crossfade(false).build() }
    private var shownFor = -1          // media index the panel was shown (or dismissed) for
    private var deadline = 0L
    var stillWatching = false; private set       // "Schaust du noch?" instead of a countdown
    var autoInARow = 0                 // episodes started without any key press in between

    val visible: Boolean get() = panel.visibility == View.VISIBLE

    init {
        title = TextView(a).apply {
            setTextColor(ACCENT); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }
        sub = TextView(a).apply {
            setTextColor(TEXT); textSize = 21f; setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; setPadding(0, a.dp(2), 0, a.dp(14))
        }
        play = a.pillButton("Jetzt abspielen").apply { setOnClickListener { playNextNow() } }
        credits = a.pillButton("Abspann ansehen").apply { setOnClickListener { dismiss() } }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(play)
            addView(credits, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = a.dp(12) })
        }
        // picture of the next episode on the left, like Netflix
        image = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, a.dp(12).toFloat())
                }
            }
            setBackgroundColor(0x1FFFFFFF)
        }
        val texts = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(sub); addView(row)
        }
        panel = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(20), a.dp(20), a.dp(26), a.dp(20))
            background = a.panelBackground()
            visibility = View.GONE
            addView(image, LinearLayout.LayoutParams(a.dp(224), a.dp(126)).apply { marginEnd = a.dp(20) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(panel, FrameLayout.LayoutParams(
            a.dp(760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, a.dp(48), a.dp(56)) })
    }

    /** Twice a second: show, count down or hide the panel. */
    fun update() {
        if (a.sleep.endOfEpisode) return
        val player = a.player
        val i = player.currentMediaItemIndex
        val next = a.items.getOrNull(i + 1)
        val dur = player.duration
        if (next == null || a.items[i].kind != PlayItem.KIND_EPISODE || dur == C.TIME_UNSET || dur < 120_000) {
            if (visible) hide()
            return
        }
        val remaining = dur - player.currentPosition
        if (!visible) {
            if (remaining in 1..BEFORE_END_MS && shownFor != i && player.isPlaying) show(i, next)
            return
        }
        if (remaining > BEFORE_END_MS + 5_000) { hide(); return }   // seeked back
        if (stillWatching) return
        val left = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        play.text = "Nächste Folge in $left s"
        if (left <= 0) playNextNow(auto = true)
    }

    private fun show(index: Int, next: PlayItem) {
        shownFor = index
        stillWatching = autoInARow >= STILL_WATCHING_AFTER
        title.text = if (stillWatching) "Schaust du noch?" else "Nächste Folge"
        sub.text = next.subtitle ?: next.title
        image.setImageDrawable(null)
        image.visibility = if (next.image == null) View.GONE else View.VISIBLE
        next.image?.let { url ->
            images.enqueue(coil.request.ImageRequest.Builder(a).data(url).size(448, 252).target(image).build())
        }
        play.text = if (stillWatching) "Weiterschauen" else "Nächste Folge in ${COUNTDOWN_MS / 1000} s"
        credits.text = if (stillWatching) "Beenden" else "Abspann ansehen"
        // "Schaust du noch?": stop at the end of this episode instead of rolling on all night
        a.player.pauseAtEndOfMediaItems = stillWatching
        deadline = System.currentTimeMillis() + COUNTDOWN_MS
        a.playerView.hideController()
        a.controls?.hide()
        panel.visibility = View.VISIBLE
        play.requestFocus()
    }

    fun hide() {
        panel.visibility = View.GONE
        a.player.pauseAtEndOfMediaItems = a.sleep.endOfEpisode
        a.playerView.requestFocus()
    }

    fun dismiss() {
        if (stillWatching) { a.saveCurrent(); a.finish(); return }
        hide()   // credits play on; ExoPlayer still moves on at the very end
    }

    fun playNextNow(auto: Boolean = false) {
        val player = a.player
        val i = player.currentMediaItemIndex
        if (i + 1 > a.items.lastIndex) return
        autoInARow = if (auto) autoInARow + 1 else 0
        hide()
        a.progress.finished(a.items[i], a.items[i + 1])
        player.seekToDefaultPosition(i + 1)
        player.playWhenReady = true
    }

    private companion object {
        const val BEFORE_END_MS = 25_000L     // panel appears 25 s before the end (credits)
        const val COUNTDOWN_MS = 10_000L
        const val STILL_WATCHING_AFTER = 3    // episodes in a row without touching the remote
    }
}
