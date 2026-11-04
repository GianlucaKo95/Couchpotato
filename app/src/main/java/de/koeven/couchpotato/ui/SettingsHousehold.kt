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
internal fun CloudSyncSection(vm: HomeViewModel) {
    val scope = rememberCoroutineScope()
    var joinCode by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(vm.household.code()) }
    var linked by remember { mutableStateOf(vm.household.linked) }
    var isMain by remember { mutableStateOf(vm.household.isMain) }
    var shareIptv by remember { mutableStateOf(vm.household.shareIptv) }
    var status by remember { mutableStateOf(vm.household.status()) }
    var busy by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRenew by remember { mutableStateOf(false) }

    fun act(block: suspend () -> String) {
        scope.launch {
            busy = true
            status = "Einen Moment …"
            status = try { block() } catch (e: Exception) { "Fehler: ${e.message ?: "unerwarteter Fehler"}" }
            code = vm.household.code(); linked = vm.household.linked; isMain = vm.household.isMain
            shareIptv = vm.household.shareIptv
            busy = false
        }
    }

    SectionTitle(
        "Haushalt",
        "Profile, Weiterschauen, Verlauf, Favoriten und Einstellungen auf allen Fire TVs gleich – mit einem " +
            "Haushalts-Code, ohne Konto und ohne E-Mail. Alles wird auf dem Fire TV verschlüsselt, der Server sieht nur Datensalat.",
    )
    Text(status, color = if (status.startsWith("Fehler")) CpColors.Error else CpColors.Accent)

    if (linked && code != null) {
        // big and spaced, so it can be read from the sofa and typed on the other TV
        Text("Haushalts-Code – gilt dauerhaft, auf weiteren Fire TVs eingeben", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted)
        Text(
            code!!.replace("-", "  –  "),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = CpColors.Text,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
        if (isMain) {
            SettingRow(
                title = "IPTV-Zugang im Haushalt teilen",
                description = if (shareIptv) "Andere Fire TVs ohne Zugang übernehmen ihn – verschlüsselt, der Server kann ihn nicht lesen"
                    else "Aus: Zugangsdaten bleiben nur auf diesem Fire TV",
                value = if (shareIptv) "an" else "aus",
            ) {
                vm.household.shareIptv = !shareIptv
                shareIptv = vm.household.shareIptv
                act { vm.household.syncNow() + if (shareIptv) " IPTV-Zugang wird geteilt." else " IPTV-Zugang wird nicht mehr geteilt." }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryPill("Jetzt synchronisieren", enabled = !busy) { act { vm.household.syncNow() } }
            if (isMain) {
                SecondaryPill(if (confirmRenew) "Wirklich? Alle müssen neu eingeben" else "Neuen Code erzeugen", enabled = !busy) {
                    if (!confirmRenew) { confirmRenew = true; return@SecondaryPill }
                    confirmRenew = false
                    act { "Neuer Code: ${vm.household.renewCode()} – der alte gilt nicht mehr." }
                }
            }
            SecondaryPill(
                when {
                    confirmLeave && isMain -> "Wirklich? Löscht den Haushalt"
                    confirmLeave -> "Wirklich verlassen?"
                    isMain -> "Haushalt auflösen"
                    else -> "Haushalt verlassen"
                },
                enabled = !busy,
            ) {
                if (!confirmLeave) { confirmLeave = true; return@SecondaryPill }
                confirmLeave = false
                act { vm.household.leave() }
            }
        }
        Hint(
            if (isMain) "Dieses Gerät ist das Hauptgerät. Wird ein Fire TV verkauft oder verliehen: „Neuen Code erzeugen“ – " +
                "dann kommen nur Geräte rein, die den neuen Code kennen."
            else "Den IPTV-Zugang teilt nur das Hauptgerät – wenn es dort eingeschaltet ist."
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryPill("Haushalt auf diesem Fire TV anlegen", enabled = !busy) {
                act { "Haushalt angelegt. Code ${vm.household.create()} auf den anderen Fire TVs eingeben." }
            }
        }
        Hint("Schon einen Haushalt? Dann hier den Code vom Hauptgerät eingeben – das Hauptgerät muss dafür nicht an sein.")
        TextFieldButton(
            label = "Haushalts-Code",
            value = joinCode,
            hint = "10 Zeichen, z. B. 7KQ2M-X9P4T",
            onValueChange = { joinCode = it.filter { c -> c.isLetterOrDigit() }.take(10).uppercase() },
        )
        PrimaryPill("Beitreten", enabled = !busy && joinCode.length == 10) {
            act { vm.household.join(joinCode).also { joinCode = "" } }
        }
    }
}
