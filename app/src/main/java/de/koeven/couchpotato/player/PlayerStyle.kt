package de.koeven.couchpotato.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import androidx.compose.ui.graphics.toArgb
import de.koeven.couchpotato.ui.CpColors

/** Couchpotato's colours (ui/Theme.kt) for the player's own views: menu petrol, gold accent, cream text. */
internal object PlayerStyle {
    const val PANEL = 0xF20B3236.toInt()       // like the Notice panel in the launcher
    val ACCENT = CpColors.Accent.toArgb()
    val ON_ACCENT = CpColors.OnAccent.toArgb()
    val TEXT = CpColors.Text.toArgb()
}

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Couchpotato's look in the player too: petrol panels, gold focus (like the menus in the launcher). */
internal fun Context.panelBackground() = GradientDrawable().apply {
    cornerRadius = dp(18).toFloat(); setColor(PlayerStyle.PANEL); setStroke(dp(2), 0x40FFFFFF)
}

internal fun Context.pillButton(text: String): Button {
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
            addState(intArrayOf(android.R.attr.state_focused), pill(PlayerStyle.ACCENT))
            addState(intArrayOf(), pill(0x1FFFFFFF))
        }
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(PlayerStyle.ON_ACCENT, PlayerStyle.TEXT),
        ))
        isFocusable = true
    }
}
