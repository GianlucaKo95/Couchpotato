package de.koeven.couchpotato.ui

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.MediaWatcher
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.XtreamClient
import de.koeven.couchpotato.data.XtreamConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/** Grouped by what you want to do, not by technology; rarely needed things under "Erweitert". */
private enum class Section(val label: String, val group: String) {
    Profiles("Profile & Kinder", "Familie"),
    CloudSync("Haushalt & Geräte", "Familie"),
    Hausgeist("Hausgeist", "Familie"),
    Home("Startbildschirm", "Fernsehen"),
    Continue("Weiterschauen", "Fernsehen"),
    Live("Live-TV", "Fernsehen"),
    Login("IPTV-Zugang", "Verbindung"),
    Catalog("Katalog & Anfragen", "Erweitert"),
    About("Über Couchpotato", "Erweitert"),
}

/** Einstellungen (design B): sections on the left, the chosen section on the right. */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    var section by remember {
        mutableStateOf(
            when {
                vm.settingsSection.also { vm.settingsSection = null } == "profiles" -> Section.Profiles
                state.configured -> Section.Home
                else -> Section.Login
            }
        )
    }
    var status by remember { mutableStateOf<String?>(null) }
    var jumpToContent by remember { mutableStateOf(false) }   // OK on a section: focus goes to its settings
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val navFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    // Back inside a section: to that section in the list on the left, not out of the settings
    androidx.activity.compose.BackHandler(enabled = contentHasFocus) { runCatching { navFocus.requestFocus() } }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { navFocus.requestFocus() }
    }

    LaunchedEffect(jumpToContent, section) {
        if (jumpToContent) {
            delay(80)
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
            jumpToContent = false
        }
    }

    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = CpColors.Accent,
            surface = CpColors.Surface,
            onSurface = CpColors.Text,
            background = CpColors.Background,
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(active = NavTab.None, showContentTabs = state.configured, activeIcon = "settings")
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                // ---------------- section list ----------------
                Column(Modifier.width(230.dp)) {
                    Text(
                        "Einstellungen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(Section.values().toList()) { s ->
                            // group heading above the first section of each group
                            if (s.ordinal == 0 || Section.values()[s.ordinal - 1].group != s.group) {
                                Text(
                                    s.group,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CpColors.TextMuted,
                                    modifier = Modifier.padding(start = 12.dp, top = if (s.ordinal == 0) 0.dp else 24.dp, bottom = 8.dp),
                                )
                            }
                            NavRow(
                                label = s.label,
                                selected = s == section,
                                onClick = { section = s; jumpToContent = true },
                                modifier = Modifier
                                    .onFocusChanged { if (it.isFocused) section = s }
                                    .then(if (s == section) Modifier.focusRequester(navFocus) else Modifier),
                            )
                        }
                        item {
                            Spacer(Modifier.height(10.dp))
                            NavRow(label = "← Zurück", selected = false, muted = true, onClick = onDone)
                        }
                    }
                }

                // ---------------- content ----------------
                // scroll only as far as needed (the TV default pulls the focused row up and cuts off the top)
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll,
                ) {
                Column(
                    Modifier
                        .onFocusChanged { contentHasFocus = it.hasFocus }
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 40.dp, end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (section) {
                        Section.Login -> LoginSection(vm) { status = it }
                        Section.Catalog -> CatalogSection(vm, state) { status = it }
                        Section.Home -> HomeSection(vm, state) { status = it }
                        Section.Live -> LiveSection(vm, state)
                        Section.Continue -> ContinueSection(vm, state)
                        Section.CloudSync -> CloudSyncSection(vm)
                        Section.Hausgeist -> HausgeistSection()
                        Section.Profiles -> ProfilesSection(vm, state)
                        Section.About -> AboutSection(state)
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (it.startsWith("Fehler")) CpColors.Error else CpColors.Accent,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                }
            }
        }
    }
}

// =====================================================================================
// Sections
// =====================================================================================

/** Scrolls a focused row just into view, like a phone – nothing moves while it is already visible. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val MinimalScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset >= 0f && offset + size <= containerSize -> 0f
        offset < 0f || size > containerSize -> offset
        else -> offset + size - containerSize
    }
}
