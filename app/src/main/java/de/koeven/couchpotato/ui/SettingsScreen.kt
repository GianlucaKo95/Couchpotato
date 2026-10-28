package de.koeven.couchpotato.ui

import androidx.compose.foundation.layout.Arrangement
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

            if (asLink) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { M3Text("M3U-Link, z. B. http://anbieter.tv:8080/get.php?username=…&password=…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.width(820.dp),
                )
                Text(
                    "Am einfachsten vom PC aus: adb shell \"am start -n de.koeven.couchpotato/.ui.MainActivity --es m3u 'LINK'\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = CpColors.TextMuted,
                )
            } else {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { M3Text("Server, z. B. http://anbieter.tv:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.width(620.dp),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { M3Text("Benutzername") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.width(620.dp),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { M3Text("Passwort") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.width(620.dp),
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
            Text("Startbildschirm", style = MaterialTheme.typography.titleMedium, color = CpColors.Accent)
            Text(
                "Lange OK drücken auf einer App blendet sie aus, auf einem Titel in „Weiterschauen“ entfernt ihn. " +
                    "Apps sind nach zuletzt geöffnet sortiert.",
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.TextMuted,
            )
            val home by vm.state.collectAsState()
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
            Text(
                "Andere Apps in „Weiterschauen“ (experimentell): ${home.externalStatus}",
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
