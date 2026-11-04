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
internal fun ProfilesSection(vm: HomeViewModel, state: HomeState) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var showSetPin by remember { mutableStateOf(false) }
    var kidList by remember { mutableStateOf<String?>(null) }   // "cats" / "apps"

    if (showSetPin) {
        SetPinDialog(onDismiss = { showSetPin = false }) { pin ->
            vm.profiles.setPin(pin)
            showSetPin = false
        }
    }

    when (kidList) {
        "cats" -> { KidCategories(vm, state) { kidList = null }; return }
        "apps" -> { KidApps(vm, state) { kidList = null }; return }
    }

    SectionTitle(
        "Profile & Kinder",
        "Jedes Profil hat sein eigenes „Weiterschauen“, „Für dich“, eigene Favoriten und Suchen. " +
            "Anmeldung, Katalog und Apps teilen sich alle.",
    )
    state.profiles.forEach { p ->
        val active = p.id == state.profile.id
        SettingRow(
            title = p.name + if (active) "  (aktiv)" else "",
            description = if (editingId == p.id) "OK = zuklappen" else null,
            value = if (p.kid) "Kinder · bis FSK ${p.maxFsk}" else "Erwachsen",
        ) { editingId = if (editingId == p.id) null else p.id }
        if (editingId == p.id) {
            Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldButton(
                    label = "Name",
                    value = p.name,
                    onValueChange = { vm.profiles.update(p.copy(name = it.ifBlank { p.name })) },
                )
                if (p.id != de.koeven.couchpotato.data.Profile.MAIN) {
                    SettingRow(title = "Kinderprofil", value = if (p.kid) "ja" else "nein") {
                        vm.profiles.update(p.copy(kid = !p.kid))
                        if (!p.kid && !state.hasPin) showSetPin = true
                    }
                }
                if (p.kid) {
                    SettingRow(
                        title = "Freigegeben bis",
                        description = "Titel mit höherer oder unbekannter FSK sind gesperrt (mit PIN freigebbar)",
                        value = "FSK ${p.maxFsk}",
                        onStep = { d ->
                            val steps = de.koeven.couchpotato.data.Profile.FSK_STEPS
                            steps.getOrNull(steps.indexOf(p.maxFsk) + d)?.let { vm.profiles.update(p.copy(maxFsk = it)) }
                        },
                    ) {
                        val steps = de.koeven.couchpotato.data.Profile.FSK_STEPS
                        val next = steps[(steps.indexOf(p.maxFsk) + 1) % steps.size]
                        vm.profiles.update(p.copy(maxFsk = next))
                    }
                    SettingRow(
                        title = "Bildschirmzeit pro Tag",
                        description = "Zählt Filme, Serien und Apps wie Netflix – ${vm.profiles.screenTimeInfo(p)}. Danach nur mit Eltern-PIN weiter.",
                        value = if (p.dailyMinutes <= 0) "unbegrenzt" else "${p.dailyMinutes} Min.",
                        onStep = { d ->
                            val steps = listOf(0, 30, 45, 60, 90, 120, 180)
                            steps.getOrNull(steps.indexOf(p.dailyMinutes).coerceAtLeast(0) + d)?.let { vm.profiles.update(p.copy(dailyMinutes = it)) }
                        },
                    ) {
                        val steps = listOf(0, 30, 45, 60, 90, 120, 180)
                        vm.profiles.update(p.copy(dailyMinutes = steps[(steps.indexOf(p.dailyMinutes) + 1).mod(steps.size)]))
                    }
                    SettingRow(
                        title = "Schlafenszeit",
                        description = "Ab dieser Uhrzeit bis 6 Uhr ist das Kinderprofil gesperrt",
                        value = if (p.bedtime < 0) "aus" else "${de.koeven.couchpotato.data.ScreenTime.clock(p.bedtime)} Uhr",
                        onStep = { d ->
                            val steps = listOf(-1) + (17 * 60..21 * 60 step 30).toList()
                            steps.getOrNull(steps.indexOf(p.bedtime).coerceAtLeast(0) + d)?.let { vm.profiles.update(p.copy(bedtime = it)) }
                        },
                    ) {
                        val steps = listOf(-1) + (17 * 60..21 * 60 step 30).toList()
                        vm.profiles.update(p.copy(bedtime = steps[(steps.indexOf(p.bedtime) + 1).mod(steps.size)]))
                    }
                }
                SettingRow(title = "Tier", value = animalName(p)) {
                    vm.profiles.update(p.copy(animal = (p.animalIndex + 1) % de.koeven.couchpotato.data.Profile.ANIMAL_COUNT))
                }
                SettingRow(title = "Farbe", value = "ändern") { vm.profiles.update(p.copy(color = p.color + 1)) }
                SettingRow(title = "Neu würfeln", description = if (p.kid) "Zufälliges Kindertier und Farbe" else "Zufällige Figur und Farbe", value = "🎲 Würfeln") {
                    vm.profiles.update(
                        p.copy(
                            // kids: cute animal on a soft colour, grown-ups: cool animal on a dark one
                            animal = (0 until de.koeven.couchpotato.data.Profile.ANIMAL_COUNT).random(),
                            color = (0 until de.koeven.couchpotato.data.Profile.COLOR_COUNT).random(),
                        )
                    )
                }
                if (p.id != de.koeven.couchpotato.data.Profile.MAIN) {
                    SettingRow(title = "Profil löschen", description = "Sein „Weiterschauen“ und Verlauf werden gelöscht", value = "Löschen") {
                        vm.profiles.remove(p.id)
                        editingId = null
                    }
                }
            }
        }
    }
    SettingRow(title = "Neues Profil", value = "Anlegen") {
        editingId = vm.profiles.add("", kid = false).id
    }
    SettingRow(title = "Neues Kinderprofil", value = "Anlegen") {
        editingId = vm.profiles.add("", kid = true).id
        if (!state.hasPin) showSetPin = true
    }

    Text(
        "Kinderschutz",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    SettingRow(
        title = "Eltern-PIN",
        description = "Nötig, um ein Kinderprofil zu verlassen und dort die Einstellungen zu öffnen",
        value = if (state.hasPin) "ändern" else "festlegen",
    ) { showSetPin = true }
    if (!state.hasPin && state.profiles.any { it.kid }) {
        Text("Ohne PIN kann das Kinderprofil frei verlassen werden.", color = CpColors.Error, style = MaterialTheme.typography.bodySmall)
    }
    SettingRow(
        title = "Kategorien für Kinder",
        description = "Nur diese Kategorien sehen Kinderprofile – in Mediathek, Suche, Reihen und Live-TV",
        value = "${state.kidCategories.size} freigegeben",
    ) { kidList = "cats" }
    SettingRow(
        title = "Apps für Kinder",
        value = "${state.kidApps.size} freigegeben",
    ) { kidList = "apps" }
    Hint(
        "Vor dem Abspielen prüft Couchpotato die FSK über TMDB. Ist sie unbekannt oder höher als erlaubt, " +
            "bleibt der Titel gesperrt – mit der Eltern-PIN lässt er sich einzeln freigeben."
    )
}

@Composable
internal fun KidCategories(vm: HomeViewModel, state: HomeState, onBack: () -> Unit) {
    SectionTitle("Kategorien für Kinder", "OK schaltet eine Kategorie frei oder sperrt sie.")
    SettingRow(title = "← Zurück", value = "${state.kidCategories.size} freigegeben") { onBack() }
    for ((kind, label) in listOf(
        SearchEntry.KIND_MOVIE to "Filme",
        SearchEntry.KIND_SERIES to "Serien",
        SearchEntry.KIND_LIVE to "Live-TV",
    )) {
        val cats = vm.library.categoriesFor(kind)
        if (cats.isEmpty()) continue
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        cats.forEach { c ->
            val key = "$kind:${c.id}"
            val allowed = key in state.kidCategories
            SettingRow(title = c.name, value = if (allowed) "✓ frei" else "gesperrt") {
                vm.profiles.setKidCategory(key, !allowed)
            }
        }
    }
}

@Composable
internal fun KidApps(vm: HomeViewModel, state: HomeState, onBack: () -> Unit) {
    SectionTitle("Apps für Kinder", "Nur diese Apps erscheinen in Kinderprofilen.")
    SettingRow(title = "← Zurück", value = "${state.kidApps.size} freigegeben") { onBack() }
    state.allApps.forEach { a ->
        val allowed = a.packageName in state.kidApps
        SettingRow(title = a.label, value = if (allowed) "✓ frei" else "gesperrt") {
            vm.profiles.setKidApp(a.packageName, !allowed)
        }
    }
}
