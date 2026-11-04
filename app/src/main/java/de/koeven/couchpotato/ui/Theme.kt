package de.koeven.couchpotato.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object CpColors {
    // dark petrol: on a TV (and even more on a projector) the content should glow, not the background
    val Background = Color(0xFF0E3E44)
    val Surface = Color(0xFF165A61)      // dialogs, plain tiles – the former background
    val SurfaceHigh = Color(0x1FFFFFFF)  // translucent tile background, lets the glow shine through
    val Accent = Color(0xFFE8B86D)
    val OnAccent = Color(0xFF1B1408)
    val Text = Color(0xFFF4F1EC)
    val TextMuted = Color(0xFFC3D6D7)
    val Error = Color(0xFFFFA197)
    val Panel = Color(0xFF0B3236)        // menus, dialogs, the player's panels
    val PanelGlass = Panel.copy(alpha = 0.95f)   // notices floating over a picture
}

/** Inter everywhere – the same crisp look on Fire TV and on the projector (instead of each device's system font). */
val CpFont = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(de.koeven.couchpotato.R.font.inter_400, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(de.koeven.couchpotato.R.font.inter_500, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(de.koeven.couchpotato.R.font.inter_600, androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(de.koeven.couchpotato.R.font.inter_700, androidx.compose.ui.text.font.FontWeight.Bold),
    androidx.compose.ui.text.font.Font(de.koeven.couchpotato.R.font.inter_800, androidx.compose.ui.text.font.FontWeight.ExtraBold),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CouchpotatoTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = CpColors.Accent,
        onPrimary = CpColors.OnAccent,
        background = CpColors.Background,
        onBackground = CpColors.Text,
        surface = CpColors.Surface,
        onSurface = CpColors.Text,
        surfaceVariant = CpColors.SurfaceHigh,
        onSurfaceVariant = CpColors.Text,
        border = CpColors.Text,
        error = CpColors.Error,
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backdrop = remember { BackdropState(context.applicationContext, scope) }

    val t = androidx.tv.material3.Typography()
    val type = t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = CpFont),
        displayMedium = t.displayMedium.copy(fontFamily = CpFont),
        displaySmall = t.displaySmall.copy(fontFamily = CpFont),
        headlineLarge = t.headlineLarge.copy(fontFamily = CpFont),
        headlineMedium = t.headlineMedium.copy(fontFamily = CpFont),
        headlineSmall = t.headlineSmall.copy(fontFamily = CpFont),
        titleLarge = t.titleLarge.copy(fontFamily = CpFont),
        titleMedium = t.titleMedium.copy(fontFamily = CpFont),
        titleSmall = t.titleSmall.copy(fontFamily = CpFont),
        bodyLarge = t.bodyLarge.copy(fontFamily = CpFont),
        bodyMedium = t.bodyMedium.copy(fontFamily = CpFont),
        bodySmall = t.bodySmall.copy(fontFamily = CpFont),
        labelLarge = t.labelLarge.copy(fontFamily = CpFont),
        labelMedium = t.labelMedium.copy(fontFamily = CpFont),
        labelSmall = t.labelSmall.copy(fontFamily = CpFont),
    )
    MaterialTheme(colorScheme = scheme, typography = type) {
        CompositionLocalProvider(
            LocalContentColor provides CpColors.Text,
            LocalBackdrop provides backdrop,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .backdropGlow(backdrop.colors)
            ) {
                content()
            }
        }
    }
}

/**
 * Petrol background with a soft glow in the top right, like the hero area of Fire OS.
 * The glow takes the colours of whatever is focused and blends over smoothly.
 */
@Composable
private fun Modifier.backdropGlow(colors: BackdropColors): Modifier {
    val spec = tween<Color>(durationMillis = 700)
    val c1 by animateColorAsState(colors.start, spec, label = "glow1")
    val c2 by animateColorAsState(colors.middle, spec, label = "glow2")
    val c3 by animateColorAsState(colors.end, spec, label = "glow3")
    val bg = CpColors.Background
    return drawBehind {
        drawRect(bg)
        val w = size.width * 0.68f
        val h = size.height * 0.66f
        val left = size.width - w
        // the coloured glow
        drawRect(
            brush = Brush.linearGradient(listOf(c1, c2, c3), start = Offset(left, 0f), end = Offset(size.width, h)),
            topLeft = Offset(left, 0f),
            size = Size(w, h),
        )
        // fade to the left into the petrol
        drawRect(
            brush = Brush.horizontalGradient(
                0f to bg,
                0.3f to bg.copy(alpha = 0.85f),
                0.75f to bg.copy(alpha = 0.15f),
                1f to bg.copy(alpha = 0.15f),
                startX = left,
                endX = size.width,
            ),
            topLeft = Offset(left, 0f),
            size = Size(w, h),
        )
        // fade downwards
        val fadeTop = h * 0.55f
        drawRect(
            brush = Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg), startY = fadeTop, endY = h + 1f),
            topLeft = Offset(0f, fadeTop),
            size = Size(size.width, h - fadeTop + 1f),
        )
    }
}
