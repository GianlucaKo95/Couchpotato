package de.koeven.couchpotato.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.SearchEntry

enum class NavTab { Start, Live, Movies, Series, None }

/** Navigation shared by every screen's top bar (set up in MainActivity). */
class NavActions(
    val home: () -> Unit,
    val browse: (Char) -> Unit,
    val search: () -> Unit,
    val settings: () -> Unit,
    val profiles: () -> Unit = {},
)

val LocalNav = staticCompositionLocalOf<NavActions?> { null }

/**
 * Design B top bar: logo · Start / Live-TV / Filme / Serien · … · search · settings · clock.
 * The same on every screen; the tab of the current area is highlighted.
 */
@Composable
fun TopBar(
    active: NavTab,
    showContentTabs: Boolean = true,
    status: String? = null,
    activeIcon: String? = null,               // "search" / "settings"
    onStart: (() -> Unit)? = null,            // home overrides this (scroll to top)
) {
    val nav = LocalNav.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = "Couchpotato",
            modifier = Modifier
                .height(46.dp)
                .aspectRatio(228f / 148f),
        )
        Spacer(Modifier.width(24.dp))
        TabPill("Start", active == NavTab.Start) { (onStart ?: nav?.home)?.invoke() }
        if (showContentTabs) {
            TabPill("Live-TV", active == NavTab.Live) { nav?.browse?.invoke(SearchEntry.KIND_LIVE) }
            TabPill("Filme", active == NavTab.Movies) { nav?.browse?.invoke(SearchEntry.KIND_MOVIE) }
            TabPill("Serien", active == NavTab.Series) { nav?.browse?.invoke(SearchEntry.KIND_SERIES) }
        }
        status?.let {
            Text("  $it", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted)
        }
        Spacer(Modifier.weight(1f))
        ProfileButton { nav?.profiles?.invoke() }
        Spacer(Modifier.width(8.dp))
        RoundIcon(R.drawable.ic_search, "Suchen", activeIcon == "search") { nav?.search?.invoke() }
        Spacer(Modifier.width(8.dp))
        RoundIcon(R.drawable.ic_settings, "Einstellungen", activeIcon == "settings") { nav?.settings?.invoke() }
        Spacer(Modifier.width(20.dp))
        Clock()
    }
}

/** Pill-shaped tab / chip. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TabPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) CpColors.Text else Color.White.copy(alpha = 0.06f),
            contentColor = if (selected) CpColors.Background else CpColors.Text,
            focusedContainerColor = CpColors.Accent,
            focusedContentColor = CpColors.OnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = modifier.padding(end = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Round translucent icon button (magnifier, gear). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RoundIcon(icon: Int, description: String, highlighted: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.colors(
            containerColor = if (highlighted) CpColors.Text else Color.White.copy(alpha = 0.10f),
            contentColor = if (highlighted) CpColors.Background else CpColors.Text,
            focusedContainerColor = CpColors.Accent,
            focusedContentColor = CpColors.OnAccent,
        ),
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** Leading "▶" in a label becomes the play icon (one icon set, no text glyphs). */
private fun splitIcon(label: String, icon: Int?): Pair<Int?, String> =
    if (icon == null && label.startsWith("▶")) R.drawable.ic_pl_play to label.removePrefix("▶").trim() else icon to label

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ButtonLabel(label: String, icon: Int?, weight: FontWeight) {
    val (ic, text) = splitIcon(label, icon)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ic != null) Icon(painter = painterResource(ic), contentDescription = if (text.isEmpty()) label else null, modifier = Modifier.size(20.dp))
        if (ic != null && text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = weight, maxLines = 1)
    }
}

/** Primary button (white), e.g. "Fortsetzen". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrimaryPill(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonDefaults.shape(ControlShape),
        colors = ButtonDefaults.colors(
            containerColor = CpColors.Text,
            contentColor = CpColors.Background,
            focusedContainerColor = CpColors.Accent,
            focusedContentColor = CpColors.OnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        ButtonLabel(label, icon, FontWeight.SemiBold)
    }
}

/** Secondary button (translucent). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SecondaryPill(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonDefaults.shape(ControlShape),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = CpColors.Text,
            focusedContainerColor = CpColors.Accent,
            focusedContentColor = CpColors.OnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        ButtonLabel(label, icon, FontWeight.Medium)
    }
}

/** Avatar of the active profile – opens "Wer schaut?". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileButton(onClick: () -> Unit) {
    val profile = LocalProfile.current ?: return
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val pill = RoundedCornerShape(50)
    // just the animal; with the pointer on it, it opens into a pill with the profile's name
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(pill),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = CpColors.Text,
            contentColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier
            .height(40.dp)
            .onFocusChanged { focused = it.isFocused },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile, 40.dp)
            if (focused) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp, end = 16.dp),
                )
            }
        }
    }
}

/** The active profile, for the avatar in the top bar. */
val LocalProfile = staticCompositionLocalOf<de.koeven.couchpotato.data.Profile?> { null }
