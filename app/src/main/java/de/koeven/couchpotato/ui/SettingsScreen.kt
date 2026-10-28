package de.koeven.couchpotato.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.XtreamConfig
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, onDone: () -> Unit) {
    val initial = remember { vm.config() }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var asLink by remember { mutableStateOf(vm.loginAsLink) }
    var link by remember { mutableStateOf(initial.toM3uLink()) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = CpColors.Accent,
            surface = CpColors.Surface,
            onSurface = CpColors.Text,
            background = CpColors.Background,
        )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 96.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Text("IPTV-Zugang", style = MaterialTheme.typography.titleMedium, color = CpColors.Accent)
            Text(
                "Die Daten findest du in der Mail deines Anbieters oder in IsiPro – " +
                    "entweder als Server/Benutzer/Passwort oder als kompletter M3U-Link.",
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.TextMuted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !asLink, onClick = { asLink = false; vm.loginAsLink = false; status = null }) {
                    Text("Zugangsdaten")
                }
                FilterChip(selected = asLink, onClick = {
                    asLink = true
                    vm.loginAsLink = true
                    if (link.isBlank()) link = XtreamConfig(url, user, pass).toM3uLink()
                    status = null
                }) {
                    Text("Kompletter Link")
                }
            }

            // Fields are buttons: the keyboard only opens when you press OK on one,
            // not every time the focus passes over it.
            if (asLink) {
                TextFieldButton(
                    label = "M3U-Link",
                    value = link,
                    hint = "http://anbieter.tv:8080/get.php?username=…&password=…",
                    keyboardType = KeyboardType.Uri,
                    width = 820,
                    onValueChange = { link = it },
                )
                Text(
                    "Am einfachsten vom PC aus: adb shell \"am start -n de.koeven.couchpotato/.ui.MainActivity --es m3u 'LINK'\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = CpColors.TextMuted,
                )
            } else {
                TextFieldButton(
                    label = "Server",
                    value = url,
                    hint = "http://anbieter.tv:8080",
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { url = it },
                )
                TextFieldButton(label = "Benutzername", value = user, onValueChange = { user = it })
                TextFieldButton(
                    label = "Passwort",
                    value = pass,
                    password = true,
                    keyboardType = KeyboardType.Password,
                    onValueChange = { pass = it },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    val cfg: XtreamConfig? = if (asLink) {
                        XtreamConfig.fromLink(link)
                    } else {
                        // a full link pasted into the server field still works
                        XtreamConfig.fromLink(url)?.takeIf { user.isBlank() || pass.isBlank() }
                            ?: XtreamConfig(url, user, pass)
                    }
                    when {
                        cfg == null -> status = "Fehler: In diesem Link stecken kein Benutzername und Passwort. " +
                            "Bitte den Link mit „username=…&password=…“ nehmen oder die Zugangsdaten einzeln eingeben."
                        !cfg.isValid -> status = "Fehler: Server, Benutzername und Passwort werden alle gebraucht."
                        else -> {
                            url = XtreamConfig.normalizeBase(cfg.baseUrl)
                            user = cfg.user
                            pass = cfg.pass
                            status = "Prüfe Verbindung …"
                            scope.launch {
                                val result = vm.saveAndTest(cfg)
                                status = if (asLink && !result.startsWith("Fehler")) {
                                    "$result\nErkannt: Server $url · Benutzer $user"
                                } else result
                            }
                        }
                    }
                }) { Text("Speichern & testen") }
                Button(onClick = onDone) { Text("Zurück") }
            }
            status?.let {
                Text(it, color = if (it.startsWith("Fehler")) CpColors.Error else CpColors.Text)
            }

            Spacer(Modifier.height(16.dp))
            val wn by vm.state.collectAsState()
            Text("Weiterschauen aus anderen Apps", style = MaterialTheme.typography.titleMedium, color = CpColors.Accent)
            Text(
                "Status: ${wn.externalStatus}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Couchpotato liest die offene Android-„Watch Next“-Liste. Die „Weiterschauen“-Reihe von Fire OS " +
                    "ist dagegen Amazons eigene und für andere Apps nicht lesbar. Steht hier „0 Einträge“, " +
                    "melden Netflix & Co. ihren Fortschritt nur an Amazon.",
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.TextMuted,
            )
            Button(onClick = { vm.onResume() }) { Text("Erneut prüfen") }
            Spacer(Modifier.height(16.dp))
            Text("Startbildschirm", style = MaterialTheme.typography.titleMedium, color = CpColors.Accent)
            Text(
                "Lange OK auf einer App, einem Sender oder einem Titel in „Weiterschauen“ öffnet ein Menü " +
                    "(Verschieben, Ausblenden, Entfernen). Beim Verschieben mit ◀ ▶ schieben, OK = fertig.",
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.TextMuted,
            )
            val home by vm.state.collectAsState()

            Button(onClick = { vm.setAppsCustomOrder(!home.appsCustomOrder) }) {
                Text("Apps sortieren: " + if (home.appsCustomOrder) "eigene Reihenfolge" else "zuletzt genutzt zuerst")
            }

            Text("Reihenfolge der Reihen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            home.rowOrder.forEachIndexed { i, id ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}. ${HomeRows.label(id)}", modifier = Modifier.width(260.dp))
                    Button(onClick = { vm.moveRow(id, -1) }, enabled = i > 0) { Text("▲ nach oben") }
                    Button(onClick = { vm.moveRow(id, 1) }, enabled = i < home.rowOrder.lastIndex) { Text("▼ nach unten") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Live-TV", style = MaterialTheme.typography.titleMedium, color = CpColors.Accent)
            Button(onClick = { vm.setLiveInExternalApp(!home.liveInExternalApp) }) {
                Text("Sender öffnen mit: " + if (home.liveInExternalApp) "IsiPro" else "Couchpotato-Player")
            }
            Text(
                "Im Couchpotato-Player schaltest du mit Hoch/Runter durch deine Favoriten. " +
                    "IsiPro bekommt nur den Stream-Link – ob es den annimmt, hängt von IsiPro ab.",
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.TextMuted,
            )
            Button(onClick = {
                vm.resetHiddenApps()
                status = "Alle Apps sind wieder sichtbar."
            }) { Text("Ausgeblendete Apps zurücksetzen") }
        }
    }
}

/**
 * Shows "Label: value" as a normal focusable button. Only pressing OK opens a small dialog
 * with the real text field (and the on-screen keyboard).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TextFieldButton(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    width: Int = 620,
) {
    var editing by remember { mutableStateOf(false) }
    val shown = when {
        value.isBlank() -> "nicht gesetzt"
        password -> "•".repeat(value.length.coerceAtMost(12))
        else -> value
    }
    ListItem(
        selected = false,
        onClick = { editing = true },
        headlineContent = { Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(label) },
        trailingContent = { Text("bearbeiten", color = CpColors.TextMuted) },
        modifier = Modifier.width(width.dp),
    )

    if (editing) {
        var draft by remember { mutableStateOf(value) }
        val fieldFocus = remember { FocusRequester() }
        fun accept() {
            onValueChange(draft.trim())
            editing = false
        }
        LaunchedEffect(Unit) {
            delay(100)
            runCatching { fieldFocus.requestFocus() }
        }
        Dialog(onDismissRequest = { editing = false }) {
            Column(
                Modifier
                    .width((width + 48).dp)
                    .background(CpColors.Surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { M3Text(hint) },
                    singleLine = true,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { accept() }),
                    modifier = Modifier
                        .width(width.dp)
                        .focusRequester(fieldFocus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { accept() }) { Text("Übernehmen") }
                    Button(onClick = { editing = false }) { Text("Abbrechen") }
                }
            }
        }
    }
}
