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

/** In-app update: look for a newer build, download it, let Android install it. */
@Composable
internal fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf(de.koeven.couchpotato.data.Updater.known(context)) }
    var status by remember { mutableStateOf<String?>(de.koeven.couchpotato.data.Updater.lastProblem(context)) }
    var busy by remember { mutableStateOf(false) }
    val updater = de.koeven.couchpotato.data.Updater
    if (!updater.configured) return
    // opening "Über Couchpotato" always asks GitHub for the newest build (1 small request) –
    // otherwise an older build found hours ago would be offered here
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { updater.check(context, force = true) }
            .onSuccess { release = it }
            .onFailure { status = "Suche fehlgeschlagen: ${it.message ?: it.javaClass.simpleName}" }
    }
    val r = release
    SettingRow(
        title = if (r != null) "Update verfügbar: Build ${r.build}" else "Updates",
        description = status ?: if (r != null) "Du hast Build ${updater.currentBuild}. Deine Daten bleiben beim Update erhalten."
            else "Build ${updater.currentBuild} – sucht stündlich selbst nach neuen Versionen",
        value = when {
            busy -> "…"
            r == null -> "Jetzt suchen"
            !updater.canInstall(context) -> "Installieren erlauben"
            else -> "Installieren"
        },
    ) {
        if (busy) return@SettingRow
        when {
            r == null -> scope.launch {
                busy = true
                status = try {
                    release = updater.check(context, force = true)
                    if (release == null) "Couchpotato ist aktuell." else null
                } catch (e: Exception) { "Fehler: ${e.message ?: "Update-Server nicht erreichbar"}" }
                busy = false
            }
            !updater.canInstall(context) -> {
                status = "Bei Couchpotato „Apps unbekannter Herkunft“ einschalten, dann zurück und noch einmal OK."
                updater.openInstallPermission(context)
            }
            else -> scope.launch {
                busy = true
                status = try {
                    // the newest build, not the one found earlier
                    val newest = runCatching { updater.check(context, force = true) }.getOrNull() ?: r
                    release = newest
                    updater.install(context, newest) { pct -> status = "Lade herunter … $pct %" }
                    "Gleich fragt Fire TV, ob installiert werden soll – mit „Installieren“ bestätigen."
                } catch (e: Exception) {
                    "Fehler: ${e.message ?: e.javaClass.simpleName}".also { updater.setProblem(context, it) }
                }
                busy = false
            }
        }
    }
}

@Composable
internal fun AboutSection(state: HomeState) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    SectionTitle("Über Couchpotato", "Dein eigener Startbildschirm für Fire TV – ohne Werbung.")
    UpdateRow()
    Hint(
        "Film- und Serieninfos: Dieses Produkt nutzt die TMDB-API, ist aber nicht von TMDB " +
            "unterstützt oder zertifiziert (themoviedb.org)."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("Version", version, " ", Modifier.weight(1f))
        StatCard("IPTV", if (state.configured) "verbunden" else "nicht eingerichtet", " ", Modifier.weight(1f))
    }
    Hint(
        "Schutz vor Sperren: Anfragen gehen einzeln mit 2,5 s Pause raus, höchstens " +
            "${XtreamClient.MAX_PER_HOUR} pro Stunde (Hintergrund 20). Antwortet der Anbieter mit einer Sperre, " +
            "pausiert Couchpotato 15 Minuten – auch über einen Neustart hinweg."
    )
    // last crash (if any) – the reason, readable on the TV
    var crash by remember { mutableStateOf(de.koeven.couchpotato.data.CrashLog.last(context)) }
    crash?.let { report ->
        Text(
            "Letzter Absturz",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = CpColors.Error,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            report.lineSequence().take(14).joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
            color = CpColors.TextMuted,
        )
        SettingRow(title = "Absturzbericht löschen", value = "Löschen") {
            de.koeven.couchpotato.data.CrashLog.clear(context)
            crash = null
        }
    }
}

// =====================================================================================
// Building blocks
// =====================================================================================
