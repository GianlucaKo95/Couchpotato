package de.koeven.couchpotato.player

import de.koeven.couchpotato.R.drawable as D

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
            setTextColor(TEXT); textSize = 16f; typeface = context.interMedium()
            setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6))
            background = GradientDrawable().apply { cornerRadius = a.dp(10).toFloat(); setColor(PANEL) }
            visibility = View.GONE
        }
        // notices in Couchpotato's style instead of the small system toasts
        notice = TextView(a).apply {
            setTextColor(TEXT); textSize = 18f; typeface = context.interMedium()
            setPadding(a.dp(22), a.dp(12), a.dp(22), a.dp(12))
            background = a.panelBackground()
            visibility = View.GONE
        }
        root.addView(notice, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(0, a.dp(28), 0, 0) })
        // "≡ Menü" while the controls are visible, so the menu can be found
        menuHint = TextView(a).apply {
            text = (if (a.isLive) "OK  Senderliste   ·   " else "") + "≡  Menü: Ton, Sleep-Timer" + if (a.isLive) " · letzter Sender · zurückspulen" +
                (if (de.koeven.couchpotato.data.Profiles.active(a).kid) "" else " · Splitscreen") else ""
            setTextColor(TEXT); textSize = 15f; typeface = context.interMedium()
            setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6))
            background = GradientDrawable().apply { cornerRadius = a.dp(10).toFloat(); setColor(PANEL) }
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
            if (behind > 10_000L) "−${LiveSession.fmt(behind)} hinter live" else null,
            a.sleep.label()?.let { "Sleep-Timer $it" },
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

    /** 0 = ≡ menu, 1 = "Ton über" (Fire TV or Sonos rooms). */
    private var page = 0
    private var autoSearched = false
    private var statusView: TextView? = null

    /** Every few seconds while "Ton über" is open: the Sonos status line. */
    fun updateSoundStatus() {
        val v = statusView ?: return
        if (menu.visibility != View.VISIBLE || page != 1 || v.parent == null) return
        v.text = a.sonosStatus
    }

    private fun fillMenu() {
        menu.removeAllViews()
        statusView = null
        if (page == 1) { fillSoundMenu(); return }
        val live = a.live
        menu.addView(TextView(a).apply {
            text = if (a.isLive) a.items.getOrNull(live.index)?.title ?: "Live-TV" else "Wiedergabe"
            setTextColor(PlayerStyle.MUTED); textSize = 15f; typeface = context.interMedium(); setPadding(0, 0, 0, a.dp(10))
        })
        fun add(label: String, icon: Int, action: () -> Unit) {
            menu.addView(a.pillButton(label, icon).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
        }
        if (a.isLive) {
            add("Senderliste", D.ic_pl_list) { hideMenu(); a.channels.show() }
            live.previousItem?.let { prev -> add("Zurück zu ${prev.title}", D.ic_pl_undo) { hideMenu(); live.backToPrevious() } }
            if (live.behindMs() > 8_000L) add("Zurück zu Live", D.ic_pl_live) { hideMenu(); live.goLive() }
            if (live.canRewind) {
                add("30 Sekunden zurück", D.ic_pl_back) { live.shiftBy(-30_000L); refreshMenu() }
                add("5 Minuten zurück", D.ic_pl_back) { live.shiftBy(-5 * 60_000L); refreshMenu() }
            }
        }
        if (a.isLive && !de.koeven.couchpotato.data.Profiles.active(a).kid) {
            val split = a.split
            if (split.active) {
                add(if (split.soundLeft) "Ton auf Live-TV" else "Ton auf Twitch", D.ic_pl_sound) { split.setSound(!split.soundLeft); refreshMenu() }
                add("Anderer Twitch-Kanal", D.ic_pl_split) { hideMenu(); split.showPicker() }
                add("Splitscreen beenden", D.ic_pl_close) { hideMenu(); split.stop() }
            } else {
                add("Splitscreen mit Twitch", D.ic_pl_split) { hideMenu(); split.showPicker() }
            }
            add("Als kleines Fenster weiterlaufen", D.ic_pl_pip) { hideMenu(); a.floatAway() }
        }
        add("Ton: ${a.soundLabel}", D.ic_pl_sound) { page = 1; fillMenu(); menu.getChildAt(1)?.requestFocus() }
        add("Sleep-Timer: ${a.sleep.label() ?: "aus"}", D.ic_pl_sleep) { a.cycleSleep() }
        add("Schließen", D.ic_pl_close) { hideMenu() }
    }

    /** Where the sound goes: the stick (projector/TV) or one or more Sonos rooms. */
    private fun fillSoundMenu() {
        menu.addView(TextView(a).apply {
            text = "Ton über"
            setTextColor(PlayerStyle.MUTED); textSize = 15f; typeface = context.interMedium(); setPadding(0, 0, 0, a.dp(10))
        })
        fun add(label: String, icon: Int, action: () -> Unit) {
            menu.addView(a.pillButton(label, icon).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) })
        }
        val sonos = a.sonos
        val chosen = if (sonos.active) sonos.speakers.map { it.uuid } else emptyList()
        add("Fire TV / Beamer", if (!sonos.active) D.ic_pl_check else D.ic_pl_tv) { a.useSonos(emptyList()); refreshMenu() }
        val rooms = de.koeven.couchpotato.sonos.SonosPrefs.known(a)
        for (r in rooms) {
            add(r.room, if (r.uuid in chosen) D.ic_pl_check else D.ic_pl_speaker) {
                val now = if (r.uuid in chosen) chosen - r.uuid else chosen + r.uuid
                a.useSonos(now.mapNotNull { id -> rooms.find { it.uuid == id } })
                refreshMenu()
            }
        }
        if (a.sonosSearching) {
            menu.addView(TextView(a).apply { text = "Suche Sonos im WLAN …"; setTextColor(TEXT); textSize = 15f; setPadding(0, a.dp(10), 0, 0) })
        } else {
            add(if (rooms.isEmpty()) "Sonos suchen" else "Neu suchen", D.ic_search) { a.findSonos { refreshMenu() }; refreshMenu() }
        }
        if (sonos.active) {
            menu.addView(TextView(a).apply {
                text = a.sonosStatus
                setTextColor(TEXT); textSize = 13f; alpha = 0.75f; setPadding(0, a.dp(8), 0, 0)
            }.also { statusView = it })
            add("Leiser", D.ic_pl_volume_down) { a.sonosVolume(-3) }
            add("Lauter", D.ic_pl_sound) { a.sonosVolume(3) }
            val t = sonos.trimMs
            menu.addView(TextView(a).apply {
                text = "Bild passt nicht zum Ton? Feinjustieren (jetzt ${if (t > 0) "+" else ""}$t ms)"
                setTextColor(TEXT); textSize = 14f; setPadding(0, a.dp(12), 0, 0)
            })
            add("Bild früher", D.ic_pl_shift_left) { a.shiftSonos(-40); refreshMenu() }
            add("Bild später", D.ic_pl_shift_right) { a.shiftSonos(40); refreshMenu() }
        }
        add("Zurück", D.ic_pl_undo) { page = 0; fillMenu(); menu.getChildAt(1)?.requestFocus() }
        // nothing found yet: look right away
        if (rooms.isEmpty() && !a.sonosSearching && !autoSearched) { autoSearched = true; a.findSonos { refreshMenu() } }
    }

    fun showMenu() {
        page = 0
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
