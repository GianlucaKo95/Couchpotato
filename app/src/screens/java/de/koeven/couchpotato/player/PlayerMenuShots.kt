package de.koeven.couchpotato.player

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import de.koeven.couchpotato.R
import org.junit.Rule
import org.junit.Test

/** The ≡ menu over a (stand-in) picture, as on the 1080p Fire TV. */
class PlayerMenuShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1080, screenWidth = 1920, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 1.0,
    )

    @Test fun menu() {
        val c = paparazzi.context
        val root = FrameLayout(c).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3B5A6E.toInt(), 0xFF1A2228.toInt(), 0xFF6E5236.toInt()))
        }
        val menu = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(22), c.dp(18), c.dp(22), c.dp(18))
            background = c.panelBackground()
        }
        menu.addView(TextView(c).apply {
            text = "Das Erste HD"; setTextColor(PlayerStyle.MUTED); textSize = 15f; typeface = context.interMedium(); setPadding(0, 0, 0, c.dp(10))
        })
        fun add(label: String, icon: Int, focused: Boolean = false) {
            menu.addView(c.pillButton(label, icon).apply { if (focused) { isFocusableInTouchMode = true; requestFocus() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(8) })
        }
        add("Senderliste", R.drawable.ic_pl_list)
        add("Zurück zu ZDF", R.drawable.ic_pl_undo)
        add("30 Sekunden zurück", R.drawable.ic_pl_back)
        add("Splitscreen mit Twitch", R.drawable.ic_pl_split)
        add("Ton: Sonos · Wohnzimmer", R.drawable.ic_pl_sound, focused = true)
        add("Sleep-Timer: aus", R.drawable.ic_pl_sleep)
        add("Schließen", R.drawable.ic_pl_close)
        root.addView(menu, FrameLayout.LayoutParams(c.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END)
            .apply { setMargins(0, 0, c.dp(48), 0) })
        paparazzi.snapshot(root)
    }
}
