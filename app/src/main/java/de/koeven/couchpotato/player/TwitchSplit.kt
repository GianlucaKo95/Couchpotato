package de.koeven.couchpotato.player

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.koeven.couchpotato.player.PlayerStyle.ACCENT
import de.koeven.couchpotato.player.PlayerStyle.TEXT

/**
 * Splitscreen in the live player: Twitch on the left (the official embedded Twitch player in a
 * WebView), the TV channel on the right. Only one side has sound; ◀ ▶ moves it.
 * The IPTV provider still sees a single connection.
 */
internal class TwitchSplit(private val a: PlayerActivity, private val root: FrameLayout) {
    private var web: WebView? = null
    private var picker: LinearLayout? = null
    private val prefs = a.getSharedPreferences("twitch", android.content.Context.MODE_PRIVATE)

    var channel: String? = null; private set
    val active: Boolean get() = web != null
    val pickerVisible: Boolean get() = picker != null
    /** true = Twitch has the sound, false = the TV channel. */
    var soundLeft = false; private set

    private fun recent(): List<String> =
        prefs.getString("recent", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    // ---------- choosing the channel ----------

    fun showPicker() {
        hidePicker()
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(20))
            background = a.panelBackground()
        }
        panel.addView(TextView(a).apply {
            text = "Splitscreen mit Twitch"
            setTextColor(PlayerStyle.MUTED); textSize = 16f; typeface = context.interMedium()
            setPadding(0, 0, 0, a.dp(12))
        })
        fun row() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(8) }
        recent().take(5).forEach { ch ->
            panel.addView(a.pillButton(ch).apply { setOnClickListener { start(ch) } }, row())
        }
        val input = EditText(a).apply {
            hint = "Twitch-Kanal, z. B. papaplatte"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(TEXT); setHintTextColor(0x99F4F1EC.toInt()); textSize = 17f
            setOnEditorActionListener { v, _, _ -> start(v.text.toString()); true }
        }
        panel.addView(input, row())
        panel.addView(a.pillButton("Starten").apply { setOnClickListener { start(input.text.toString()) } }, row())
        panel.addView(a.pillButton("Abbrechen").apply { setOnClickListener { hidePicker() } }, row())
        root.addView(panel, FrameLayout.LayoutParams(a.dp(440), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        picker = panel
        (panel.getChildAt(1) ?: input).requestFocus()
    }

    fun hidePicker() {
        picker?.let { root.removeView(it) }
        picker = null
        a.playerView.requestFocus()
    }

    // ---------- the split itself ----------

    @SuppressLint("SetJavaScriptEnabled")
    fun start(rawChannel: String) {
        // "https://www.twitch.tv/papaplatte" or "papaplatte"
        val ch = rawChannel.trim().substringAfterLast('/').substringBefore('?').lowercase()
            .filter { it.isLetterOrDigit() || it == '_' }
        if (ch.length < 2) { a.toast("Bitte einen Twitch-Kanal eingeben"); return }
        hidePicker()
        prefs.edit().putString("recent", (listOf(ch) + recent().filterNot { it == ch }).take(8).joinToString(",")).apply()
        channel = ch
        val view = web ?: WebView(a).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            setBackgroundColor(0xFF000000.toInt())
            // the remote stays with Couchpotato (zapping, menu, sound); the web page never gets the focus
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.addView(this, 0, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        }
        web = view
        layout()
        // the embedded player only runs on the page it was told about ("parent")
        view.loadDataWithBaseURL("https://$HOST/", page(ch, muted = !soundLeft), "text/html", "utf-8", null)
        applySound()
        a.toast("◀ ▶ Ton links/rechts · Zurück beendet den Splitscreen")
    }

    fun stop() {
        hidePicker()
        val view = web ?: return
        web = null
        channel = null
        root.removeView(view)
        view.loadUrl("about:blank")
        view.destroy()
        a.playerView.layoutParams = (a.playerView.layoutParams as FrameLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT; gravity = Gravity.NO_GRAVITY
        }
        a.player.volume = 1f
        a.playerView.requestFocus()
    }

    fun setSound(left: Boolean) {
        if (!active) return
        soundLeft = left
        applySound()
        a.toast(if (left) "Ton: Twitch" else "Ton: Live-TV")
    }

    private fun applySound() {
        a.player.volume = if (soundLeft) 0f else 1f
        web?.evaluateJavascript("setMuted(${!soundLeft})", null)
    }

    /** Two halves side by side. */
    private fun layout() {
        val half = root.width.takeIf { it > 0 }?.div(2) ?: (a.resources.displayMetrics.widthPixels / 2)
        web?.layoutParams = (web?.layoutParams as FrameLayout.LayoutParams).apply { width = half; gravity = Gravity.START }
        a.playerView.layoutParams = (a.playerView.layoutParams as FrameLayout.LayoutParams).apply {
            width = half; gravity = Gravity.END
        }
    }

    private fun page(channel: String, muted: Boolean) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100vw;height:100vh}</style></head>
        <body><div id="p"></div>
        <script src="https://player.twitch.tv/js/embed/v1.js"></script>
        <script>
          var player = new Twitch.Player("p", { channel: "$channel", parent: ["$HOST"], width: "100%", height: "100%", autoplay: true, muted: $muted });
          function setMuted(m) { try { player.setMuted(m); if (!m) player.setVolume(1.0); } catch (e) {} }
        </script></body></html>
    """.trimIndent()

    private companion object {
        const val HOST = "couchpotato.local"
    }
}
