package de.koeven.couchpotato.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object CpColors {
    val Background = Color(0xFF0F3B40)   // petrol
    val Surface = Color(0xFF164C53)
    val SurfaceHigh = Color(0xFF1D5A61)
    val Accent = Color(0xFFE8B86D)
    val OnAccent = Color(0xFF1B1408)
    val Text = Color(0xFFF2EFEA)
    val TextMuted = Color(0xFFBCCDCE)
    val Error = Color(0xFFFF8A80)
}

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
        border = CpColors.Accent,
        error = CpColors.Error,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides CpColors.Text) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(CpColors.Background)
            ) {
                content()
            }
        }
    }
}
