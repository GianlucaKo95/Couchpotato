package de.koeven.couchpotato.player

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import de.koeven.couchpotato.player.PlayerStyle.ACCENT
import de.koeven.couchpotato.player.PlayerStyle.PANEL
import de.koeven.couchpotato.player.PlayerStyle.TEXT

/**
 * The player's own overlays: notices in Couchpotato's style, the label top right
 * ("⏪ −1:30 · ☾ 30 Min."), the "≡ Menü" hint and the ≡ menu (last channel, live, sleep timer).
 */
internal class PlayerOverlays(private val a: PlayerActivity, root: FrameLayout) {
    private val notice: TextView
    private val label: TextView
    private val menuHint: TextView
    private val menu: LinearLayout
    private val hideNotice = Runnable { notice.visibility = View.GONE }

    val menuVisible: Boolean get() = menu.visibility == View.VISIBLE

    init {
        label = TextView(a).apply {
            setTextColor(TEXT); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6))
            background = GradientDrawable().apply { cornerRadius = a.dp(14).toFloat(); setColor(PANEL) }
            visibility = View.GONE
        }
        // notices in Couchpotato's style instead of the small system toasts
        notice = TextView(a).apply {
            setTextColor(TEXT); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            setPadding(a.dp(22), a.dp(12), a.dp(22), a.dp(12))
            background = a.panelBackground()
            visibility = View.GONE
        }
        root.addView(notice, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, a.dp(28), 0, 0) })
        // "≡ Menü" while the controls are visible, so the menu can be found
        menuHint = TextView(a).apply {
            text = "≡  Menü: Sleep-Timer" + if (a.isLive) " · letzter Sender · zurückspulen" +
                (if (de.koeven.couchpotato.data.Profiles.active(a).kid) "" else " · Splitscreen") else ""
            setTextColor(TEXT); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6))
            background = GradientDrawable().apply { cornerRadius = a.dp(14).toFloat(); setColor(PANEL) }
            visibility = View.GONE
        }
        root.addView(menuHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(a.dp(40), a.dp(28), 0, 0) })
        root.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, a.dp(28), a.dp(40), 0) })
        menu = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(18))
            background = a.panelBackground()
            visibility = View.GONE
        }
        root.addView(menu, FrameLayout.LayoutParams(
            a.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END,
        ).apply { setMargins(0, 0, a.dp(48), 0) })
        a.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            menuHint.visibility = if (v == View.VISIBLE && menu.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        })
    }

    fun toast(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        a.handler.removeCallbacks(hideNotice)
        a.handler.postDelayed(hideNotice, 2_600L)
    }

    fun updateLabel() {
        val behind = if (a.isLive) a.live.behindMs() else 0L
        val parts = listOfNotNull(
            if (behind > 10_000L) "⏪ −${LiveSession.fmt(behind)}" else null,
            a.sleep.label(),
        )
        label.text = parts.joinToString("   ·   ")
        label.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    fun refreshMenu() {
        if (menu.visibility != View.VISIBLE) return
        val focused = menu.indexOfChild(menu.findFocus())
        fillMenu()
        menu.getChildAt(focused.coerceAtLeast(1))?.requestFocus()
    }

    private fun fillMenu() {
        menu.removeAllViews()
        val live = a.live
        menu.addView(TextView(a).apply {
            text = if (a.isLive) a.items.getOrNull(live.index)?.title ?: "Live-TV" else "Wiedergabe"
            setTextColor(ACCENT); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, a.dp(10))
        })
        fun add(label: String, action: () -> Unit) {
            menu.addView(a.pillButton(label).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
        }
        if (a.isLive) {
            a.items.getOrNull(live.previous)?.let { prev -> add("↩ Zurück zu ${prev.title}") { hideMenu(); live.switchTo(live.previous) } }
            if (live.behindMs() > 8_000L) add("▶▶ Zurück zu Live") { hideMenu(); live.goLive() }
            if (live.canRewind) {
                add("⏪ 30 Sekunden zurück") { live.shiftBy(-30_000L); refreshMenu() }
                add("⏪ 5 Minuten zurück") { live.shiftBy(-5 * 60_000L); refreshMenu() }
            }
        }
        if (a.isLive && !de.koeven.couchpotato.data.Profiles.active(a).kid) {
            val split = a.split
            if (split.active) {
                add(if (split.soundLeft) "🔈 Ton auf Live-TV" else "🔈 Ton auf Twitch") { split.setSound(!split.soundLeft); refreshMenu() }
                add("▥ Anderer Twitch-Kanal") { hideMenu(); split.showPicker() }
                add("▥ Splitscreen beenden") { hideMenu(); split.stop() }
            } else {
                add("▥ Splitscreen mit Twitch") { hideMenu(); split.showPicker() }
            }
        }
        add("☾ Sleep-Timer: ${a.sleep.label()?.removePrefix("☾ ") ?: "aus"}") { a.cycleSleep() }
        add("Schließen") { hideMenu() }
    }

    fun showMenu() {
        fillMenu()
        a.playerView.hideController()
        menuHint.visibility = View.GONE
        menu.visibility = View.VISIBLE
        menu.getChildAt(1)?.requestFocus()
    }

    fun hideMenu() {
        menu.visibility = View.GONE
        a.playerView.requestFocus()
    }

    fun release() = a.handler.removeCallbacks(hideNotice)
}
