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

@Composable
internal fun HomeSection(vm: HomeViewModel, state: HomeState, setStatus: (String?) -> Unit) {
    SectionTitle(
        "Startbildschirm",
        "Lange OK auf einer App, einem Sender oder einem Titel öffnet ein Menü (Verschieben, Ausblenden, Entfernen).",
    )
    SettingRow(
        title = "Apps sortieren",
        value = if (state.appsCustomOrder) "eigene Reihenfolge" else "zuletzt genutzt zuerst",
    ) { vm.apps.setCustomOrder(!state.appsCustomOrder) }
    SettingRow(
        title = "Ausgeblendete Apps",
        description = "Alle versteckten Apps wieder anzeigen",
        value = "Zurücksetzen",
    ) {
        vm.apps.resetHidden()
        setStatus("Alle Apps sind wieder sichtbar.")
    }
    Text(
        "Reihenfolge der Reihen",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp),
    )
    state.rowOrder.forEachIndexed { i, id ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${i + 1}.  ${HomeRows.label(id)}", modifier = Modifier.weight(1f))
            if (i > 0) TabPill("▲ hoch", false) { vm.apps.moveRow(id, -1) }
            if (i < state.rowOrder.lastIndex) TabPill("▼ runter", false) { vm.apps.moveRow(id, 1) }
        }
    }
}

@Composable
internal fun LiveSection(vm: HomeViewModel, state: HomeState) {
    val nav = LocalNav.current
    SectionTitle(
        "Live-TV",
        "Im Couchpotato-Player schaltest du mit Hoch/Runter durch deine Favoriten. " +
            "IsiPro bekommt nur den Stream-Link – ob es den annimmt, hängt von IsiPro ab.",
    )
    SettingRow(
        title = "Sender öffnen mit",
        value = if (state.liveInExternalApp) "IsiPro" else "Couchpotato-Player",
    ) { vm.live.setLiveInExternalApp(!state.liveInExternalApp) }
    SettingRow(
        title = "Favoriten",
        description = "${state.favorites.size} Sender · „Jetzt läuft“ für die ersten 8, alle 30 Minuten",
        value = "Sender verwalten",
    ) { nav?.browse?.invoke(SearchEntry.KIND_LIVE) }
}

@Composable
internal fun ContinueSection(vm: HomeViewModel, state: HomeState) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val watching = remember(refresh) { MediaWatcher.isEnabled(context) }
    SectionTitle(
        "Weiterschauen",
        "Filme und Folgen aus Couchpotato merken sich die Stelle ab 10 Sekunden. Was du in IsiPro, der " +
            "ARD Mediathek und anderen Apps schaust, die ihre Wiedergabe melden, landet ebenfalls hier. " +
            "Netflix meldet nichts und erscheint deshalb als App-Kachel.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("Einträge in Weiterschauen", state.continueWatching.size.toString(), " ", Modifier.weight(1f))
        StatCard("Andere Apps mitlesen", if (watching) "aktiv" else "Freigabe fehlt", " ", Modifier.weight(1f))
    }
    if (!watching) {
        Hint(
            "Einmalig per PowerShell freigeben:\nadb shell cmd notification allow_listener " +
                "de.koeven.couchpotato/de.koeven.couchpotato.data.MediaWatcher"
        )
    }
    SettingRow(title = "Status neu prüfen", value = "Prüfen") {
        refresh++
        vm.onResume()
    }
    // what other apps hand over and what TMDB made of it (picture and plot in the info area)
    val lookups = remember(refresh) { vm.tmdb.lookupLog() }
    if (lookups.isNotEmpty()) {
        Hint("Andere Apps – zuletzt bei TMDB gesucht:\n" + lookups.take(6).joinToString("\n"))
    }
}
