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
            .padding(start = ScreenPadding, end = ScreenPadding, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = "Couchpotato",
            modifier = Modifier
                .height(46.dp)
                .aspectRatio(228f / 148f),
        )
        Spacer(Modifier.width(22.dp))
        TabPill("Start", active == NavTab.Start) { (onStart ?: nav?.home)?.invoke() }
        if (showContentTabs) {
            TabPill("Live-TV", active == NavTab.Live) { nav?.browse?.invoke(SearchEntry.KIND_LIVE) }
            TabPill("Filme", active == NavTab.Movies) { nav?.browse?.invoke(SearchEntry.KIND_MOVIE) }
            TabPill("Serien", active == NavTab.Series) { nav?.browse?.invoke(SearchEntry.KIND_SERIES) }
        }
        status?.let {
            Text("  $it", style = MaterialTheme.typography.labelSmall, color = CpColors.TextMuted)
        }
        Spacer(Modifier.weight(1f))
        ProfileButton { nav?.profiles?.invoke() }
        Spacer(Modifier.width(8.dp))
        RoundIcon(R.drawable.ic_search, "Suchen", activeIcon == "search") { nav?.search?.invoke() }
        Spacer(Modifier.width(8.dp))
        RoundIcon(R.drawable.ic_settings, "Einstellungen", activeIcon == "settings") { nav?.settings?.invoke() }
        Spacer(Modifier.width(18.dp))
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        modifier = modifier.padding(end = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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

/** Primary pill button (white), e.g. "Fortsetzen". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrimaryPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = CpColors.Text,
            contentColor = CpColors.Background,
            focusedContainerColor = CpColors.Accent,
            focusedContentColor = CpColors.OnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Secondary pill button (translucent). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SecondaryPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = CpColors.Text,
            focusedContainerColor = CpColors.Accent,
            focusedContentColor = CpColors.OnAccent,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Avatar of the active profile – opens "Wer schaut?". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileButton(onClick: () -> Unit) {
    val profile = LocalProfile.current ?: return
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                androidx.compose.foundation.BorderStroke(3.dp, CpColors.Text),
                shape = androidx.compose.foundation.shape.CircleShape,
            ),
        ),
        modifier = Modifier.size(40.dp),
    ) {
        Avatar(profile, 40.dp)
    }
}

/** The active profile, for the avatar in the top bar. */
val LocalProfile = staticCompositionLocalOf<de.koeven.couchpotato.data.Profile?> { null }
