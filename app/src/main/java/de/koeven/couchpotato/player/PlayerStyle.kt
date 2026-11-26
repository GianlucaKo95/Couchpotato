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
    val PANEL = CpColors.PanelGlass.toArgb()
    val ACCENT = CpColors.Accent.toArgb()
    val ON_ACCENT = CpColors.OnAccent.toArgb()
    val TEXT = CpColors.Text.toArgb()
    val MUTED = CpColors.TextMuted.toArgb()
}

internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Inter 500 – the launcher's font in the player's own views too. */
internal fun Context.interMedium(): Typeface =
    runCatching { androidx.core.content.res.ResourcesCompat.getFont(this, de.koeven.couchpotato.R.font.inter_500) }.getOrNull() ?: Typeface.DEFAULT

/** Couchpotato's look in the player too: petrol panels, gold focus (like the menus in the launcher). */
internal fun Context.panelBackground() = GradientDrawable().apply {
    cornerRadius = dp(16).toFloat(); setColor(PlayerStyle.PANEL); setStroke(dp(1), 0x1AFFFFFF)
}

/**
 * Menu/panel button. With [icon] it is a menu row: the icon in one column on the left, the label
 * next to it – both take the text colour (dark on the gold focus).
 */
internal fun Context.pillButton(text: String, icon: Int? = null): Button {
    fun pill(bg: Int) = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(bg) }
    return Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        typeface = interMedium()
        setPadding(dp(20), 0, dp(20), 0)
        minHeight = dp(44); minimumHeight = dp(44)
        stateListAnimator = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(PlayerStyle.ACCENT))
            addState(intArrayOf(), pill(0x14FFFFFF))
        }
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(PlayerStyle.ON_ACCENT, PlayerStyle.TEXT),
        ))
        isFocusable = true
        if (icon != null) {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(20), 0)
            androidx.core.content.ContextCompat.getDrawable(this@pillButton, icon)?.mutate()?.let { d ->
                d.setBounds(0, 0, dp(20), dp(20))
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(12)
                compoundDrawableTintList = textColors
            }
        }
    }
}
