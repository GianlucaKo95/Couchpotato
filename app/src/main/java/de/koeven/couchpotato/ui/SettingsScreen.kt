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

private enum class Section(val label: String) {
    Login("IPTV-Zugang"),
    Profiles("Profile & Kinder"),
    Catalog("Katalog & Anfragen"),
    Home("Startbildschirm"),
    Live("Live-TV"),
    Continue("Weiterschauen"),
    CloudSync("Synchronisierung"),
    About("Über Couchpotato"),
}

/** Einstellungen (design B): sections on the left, the chosen section on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(vm: HomeViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    var section by remember {
        mutableStateOf(
            when {
                vm.settingsSection.also { vm.settingsSection = null } == "profiles" -> Section.Profiles
                state.configured -> Section.Catalog
                else -> Section.Login
            }
        )
    }
    var status by remember { mutableStateOf<String?>(null) }
    var jumpToContent by remember { mutableStateOf(false) }   // OK on a section: focus goes to its settings
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val navFocus = remember { FocusRequester() }

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
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(Section.values().toList()) { s ->
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
                Column(
                    Modifier
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

// =====================================================================================
// Sections
// =====================================================================================

@Composable
private fun LoginSection(vm: HomeViewModel, setStatus: (String?) -> Unit) {
    val initial = remember { vm.config() }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var asLink by remember { mutableStateOf(vm.loginAsLink) }
    var link by remember { mutableStateOf(initial.toM3uLink()) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SectionTitle(
        "IPTV-Zugang",
        "Steht in der Mail deines Anbieters oder in IsiPro – als Server/Benutzer/Passwort oder als kompletter M3U-Link.",
    )
    SettingRow(
        title = "Anmelden mit",
        value = if (asLink) "Kompletter Link" else "Zugangsdaten",
    ) {
        asLink = !asLink
        vm.loginAsLink = asLink
        if (asLink && link.isBlank()) link = XtreamConfig(url, user, pass).toM3uLink()
        result = null
    }
    if (asLink) {
        TextFieldButton(
            label = "M3U-Link",
            value = link,
            hint = "http://anbieter.tv:8080/get.php?username=…&password=…",
            keyboardType = KeyboardType.Uri,
            onValueChange = { link = it },
        )
        Hint("Am einfachsten: „M3U-Link“ öffnen → „Mit dem Handy eingeben“ → QR-Code scannen und den Link auf dem Handy einfügen.")
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
    Spacer(Modifier.height(4.dp))
    PrimaryPill("Speichern & testen") {
        setStatus(null)
        val cfg: XtreamConfig? = if (asLink) {
            XtreamConfig.fromLink(link)
        } else {
            // a full link pasted into the server field still works
            XtreamConfig.fromLink(url)?.takeIf { user.isBlank() || pass.isBlank() }
                ?: XtreamConfig(url, user, pass)
        }
        when {
            cfg == null -> result = "Fehler: In diesem Link stecken kein Benutzername und Passwort. " +
                "Bitte den Link mit „username=…&password=…“ nehmen oder die Zugangsdaten einzeln eingeben."
            !cfg.isValid -> result = "Fehler: Server, Benutzername und Passwort werden alle gebraucht."
            else -> {
                url = XtreamConfig.normalizeBase(cfg.baseUrl)
                user = cfg.user
                pass = cfg.pass
                result = "Prüfe Verbindung …"
                scope.launch {
                    val r = vm.saveAndTest(cfg)
                    result = if (asLink && !r.startsWith("Fehler")) "$r\nErkannt: Server $url · Benutzer $user" else r
                }
            }
        }
    }
    result?.let {
        Text(it, color = if (it.startsWith("Fehler")) CpColors.Error else CpColors.Text)
    }
}

@Composable
private fun CatalogSection(vm: HomeViewModel, state: HomeState, setStatus: (String?) -> Unit) {
    // re-read the counters whenever the screen state changes (they live in the request brake)
    val last = remember(state.syncInfo) { vm.lastSyncAt() }
    val requests = remember(state.syncInfo) { XtreamClient.requestsLastHour() }
    val images = remember(state.syncInfo) { XtreamClient.imagesLastHour() }

    SectionTitle(
        "Katalog & Anfragen",
        "Der Katalog wird höchstens einmal am Tag abgeglichen (6 Anfragen). Mediathek, Suche, Sender und " +
            "Ausblenden laufen danach komplett auf dem Stick.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            "Letzter Abgleich",
            if (last == 0L) "noch nie" else SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(last)),
            if (last == 0L) " " else SimpleDateFormat("EEEE, dd.MM.", Locale.GERMANY).format(Date(last)),
            Modifier.weight(1f),
        )
        StatCard(
            "Anfragen",
            "$requests / ${XtreamClient.MAX_PER_HOUR}",
            "in der letzten Stunde",
            Modifier.weight(1f),
        )
        StatCard(
            "Bilder vom Anbieter",
            "$images / ${XtreamClient.IMAGES_PER_HOUR}",
            if (state.loadProviderImages) "in der letzten Stunde" else "ausgeschaltet",
            Modifier.weight(1f),
        )
    }
    if (state.tmdbOn) {
        val tmdbHour = remember(state.syncInfo, state.tick) { de.koeven.couchpotato.data.TmdbGate.lastHour() }
        val tmdbToday = remember(state.syncInfo, state.tick) { de.koeven.couchpotato.data.TmdbGate.today() }
        val last429 = de.koeven.couchpotato.data.TmdbGate.last429
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("TMDB-Anfragen", "$tmdbHour / Std.", "heute $tmdbToday · Grenze ~40 pro Sekunde", Modifier.weight(2f))
            StatCard(
                "TMDB gebremst",
                if (last429 == 0L) "nie" else SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(last429)),
                if (last429 == 0L) "alles im grünen Bereich" else "danach kurz pausiert",
                Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    SettingRow(
        title = "Katalog jetzt abgleichen",
        description = "Höchstens alle 10 Minuten · bei einer Sperre pausiert die App 15 Minuten",
        value = "Abgleichen",
    ) { setStatus(vm.syncNow()) }
    SettingRow(
        title = "Bilder vom Anbieter-Server",
        description = "Einzeln, mit Pause, max. ${XtreamClient.IMAGES_PER_HOUR} pro Stunde · jedes Bild nur einmal",
        value = if (state.loadProviderImages) "laden (gebremst)" else "nicht laden",
    ) { vm.setLoadProviderImages(!state.loadProviderImages) }
    SettingRow(
        title = "Film-Details (Beschreibung, Besetzung)",
        description = "1 Anfrage beim ersten Öffnen eines Films, danach dauerhaft auf dem Stick",
        value = if (state.loadMovieDetails) "laden" else "nicht laden",
    ) { vm.setLoadMovieDetails(!state.loadMovieDetails) }
    Hint(
        if (state.tmdbOn) "TMDB aktiv: Beschreibung, FSK, Besetzung und Bewertung kommen von themoviedb.org – " +
            "das kostet deinen IPTV-Anbieter keine Anfrage. Der Anbieter wird nur gefragt, wenn TMDB einen Film nicht kennt."
        else "TMDB aus (kein Schlüssel). Setzen per PowerShell: adb shell am start -n " +
            "de.koeven.couchpotato/.ui.MainActivity --es tmdb 'SCHLÜSSEL' – oder als Repo-Secret TMDB_TOKEN."
    )
    Hint(state.imageInfo)
    if (state.indexStatus.isNotBlank()) Hint(state.indexStatus)
}

@Composable
private fun HomeSection(vm: HomeViewModel, state: HomeState, setStatus: (String?) -> Unit) {
    SectionTitle(
        "Startbildschirm",
        "Lange OK auf einer App, einem Sender oder einem Titel öffnet ein Menü (Verschieben, Ausblenden, Entfernen).",
    )
    SettingRow(
        title = "Apps sortieren",
        value = if (state.appsCustomOrder) "eigene Reihenfolge" else "zuletzt genutzt zuerst",
    ) { vm.setAppsCustomOrder(!state.appsCustomOrder) }
    SettingRow(
        title = "Ausgeblendete Apps",
        description = "Alle versteckten Apps wieder anzeigen",
        value = "Zurücksetzen",
    ) {
        vm.resetHiddenApps()
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
            if (i > 0) TabPill("▲ hoch", false) { vm.moveRow(id, -1) }
            if (i < state.rowOrder.lastIndex) TabPill("▼ runter", false) { vm.moveRow(id, 1) }
        }
    }
}

@Composable
private fun LiveSection(vm: HomeViewModel, state: HomeState) {
    val nav = LocalNav.current
    SectionTitle(
        "Live-TV",
        "Im Couchpotato-Player schaltest du mit Hoch/Runter durch deine Favoriten. " +
            "IsiPro bekommt nur den Stream-Link – ob es den annimmt, hängt von IsiPro ab.",
    )
    SettingRow(
        title = "Sender öffnen mit",
        value = if (state.liveInExternalApp) "IsiPro" else "Couchpotato-Player",
    ) { vm.setLiveInExternalApp(!state.liveInExternalApp) }
    SettingRow(
        title = "Favoriten",
        description = "${state.favorites.size} Sender · „Jetzt läuft“ für die ersten 8, alle 30 Minuten",
        value = "Sender verwalten",
    ) { nav?.browse?.invoke(SearchEntry.KIND_LIVE) }
}

@Composable
private fun ContinueSection(vm: HomeViewModel, state: HomeState) {
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
}


@Composable
private fun CloudSyncSection(vm: HomeViewModel) {
    val scope = rememberCoroutineScope()
    var joinCode by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(vm.householdCode()) }
    var linked by remember { mutableStateOf(vm.cloudLinked) }
    var isMain by remember { mutableStateOf(vm.cloudIsMain) }
    var shareIptv by remember { mutableStateOf(vm.shareIptvWithHousehold) }
    var status by remember { mutableStateOf(vm.cloudSyncStatus()) }
    var busy by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRenew by remember { mutableStateOf(false) }

    fun act(block: suspend () -> String) {
        scope.launch {
            busy = true
            status = "Einen Moment …"
            status = try { block() } catch (e: Exception) { "Fehler: ${e.message ?: "unerwarteter Fehler"}" }
            code = vm.householdCode(); linked = vm.cloudLinked; isMain = vm.cloudIsMain
            shareIptv = vm.shareIptvWithHousehold
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
                vm.shareIptvWithHousehold = !shareIptv
                shareIptv = vm.shareIptvWithHousehold
                act { vm.syncCloudNow() + if (shareIptv) " IPTV-Zugang wird geteilt." else " IPTV-Zugang wird nicht mehr geteilt." }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryPill("Jetzt synchronisieren", enabled = !busy) { act { vm.syncCloudNow() } }
            if (isMain) {
                SecondaryPill(if (confirmRenew) "Wirklich? Alle müssen neu eingeben" else "Neuen Code erzeugen", enabled = !busy) {
                    if (!confirmRenew) { confirmRenew = true; return@SecondaryPill }
                    confirmRenew = false
                    act { "Neuer Code: ${vm.renewHouseholdCode()} – der alte gilt nicht mehr." }
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
                act { vm.leaveHousehold() }
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
                act { "Haushalt angelegt. Code ${vm.createHousehold()} auf den anderen Fire TVs eingeben." }
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
            act { vm.joinHousehold(joinCode).also { joinCode = "" } }
        }
    }
}

/** In-app update: look for a newer build, download it, let Android install it. */
@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf(de.koeven.couchpotato.data.Updater.known(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val updater = de.koeven.couchpotato.data.Updater
    if (!updater.configured) return
    val r = release
    SettingRow(
        title = if (r != null) "Update verfügbar: Build ${r.build}" else "Updates",
        description = status ?: if (r != null) "Du hast Build ${updater.currentBuild}. Deine Daten bleiben beim Update erhalten."
            else "Build ${updater.currentBuild} – sucht alle 6 Stunden selbst nach neuen Versionen",
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
                    updater.install(context, r) { pct -> status = "Lade herunter … $pct %" }
                    "Gleich fragt Fire TV, ob installiert werden soll – mit „Installieren“ bestätigen."
                } catch (e: Exception) { "Fehler: ${e.message ?: "Update fehlgeschlagen"}" }
                busy = false
            }
        }
    }
}

@Composable
private fun AboutSection(state: HomeState) {
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

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = CpColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted)
}

@Composable
private fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted)
        Text(
            value,
            style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = if (small) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted, maxLines = 1)
    }
}

/** A whole-width row: title (+ description) on the left, current value as a pill on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingRow(
    title: String,
    value: String,
    description: String? = null,
    onStep: ((Int) -> Unit)? = null,     // ◀ ▶ change the value directly (like a TV menu)
    onClick: () -> Unit,
) {
    val supporting: (@Composable () -> Unit)? = if (description != null) {
        { Text(description, style = MaterialTheme.typography.bodyMedium) }
    } else null
    val stepKeys = if (onStep == null) Modifier else Modifier.onPreviewKeyEvent { e ->
        val d = when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionLeft -> -1
            androidx.compose.ui.input.key.Key.DirectionRight -> 1
            else -> 0
        }
        if (d == 0) return@onPreviewKeyEvent false
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) onStep(d)
        true
    }
    ListItem(
        selected = false,
        onClick = onClick,
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
        supportingContent = supporting,
        trailingContent = {
            Text(
                if (onStep != null) "◀  $value  ▶" else value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = CpColors.OnAccent,
                maxLines = 1,
                modifier = Modifier
                    .background(CpColors.Accent, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        },
        modifier = Modifier.fillMaxWidth().then(stepKeys),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        shape = ListItemDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (muted) CpColors.TextMuted else CpColors.Text,
            selectedContainerColor = Color.White.copy(alpha = 0.14f),
            selectedContentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        headlineContent = {
            Text(label, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Shows "Label: value" as a normal focusable row. Only pressing OK opens a small dialog
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
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        overlineContent = { Text(label) },
        headlineContent = { Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text("bearbeiten", style = MaterialTheme.typography.bodyMedium) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (editing) {
        var draft by remember { mutableStateOf(value) }
        var phone by remember { mutableStateOf(true) }      // QR code right away, next to the field
        val fieldFocus = remember { FocusRequester() }
        fun accept() {
            onValueChange(draft.trim())
            editing = false
        }
        LaunchedEffect(Unit) {
            delay(100)
            runCatching { fieldFocus.requestFocus() }
        }
        Dialog(
            onDismissRequest = { editing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
          // at the top of the screen: the Fire TV keyboard takes the lower half
          androidx.compose.foundation.layout.Box(
              Modifier.fillMaxSize().padding(top = 28.dp),
              contentAlignment = androidx.compose.ui.Alignment.TopCenter,
          ) {
            Column(
                Modifier
                    .width(if (phone) 860.dp else 600.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFF0B3236), RoundedCornerShape(18.dp))
                    .border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
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
                        .fillMaxWidth()
                        .focusRequester(fieldFocus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryPill("Übernehmen") { accept() }
                    SecondaryPill(if (phone) "QR-Code ausblenden" else "Mit dem Handy eingeben") { phone = !phone }
                    SecondaryPill("Abbrechen") { editing = false }
                }
                if (phone) {
                    // live from the phone into the field; "Übernehmen" on the TV confirms
                    PhoneLinkPanel(label, password, compact = true) { text -> draft = text }
                }
            }
          }
        }
    }
}

// =====================================================================================
// Profiles & kids
// =====================================================================================

@Composable
private fun ProfilesSection(vm: HomeViewModel, state: HomeState) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var showSetPin by remember { mutableStateOf(false) }
    var kidList by remember { mutableStateOf<String?>(null) }   // "cats" / "apps"

    if (showSetPin) {
        SetPinDialog(onDismiss = { showSetPin = false }) { pin ->
            vm.setPin(pin)
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
                    onValueChange = { vm.updateProfile(p.copy(name = it.ifBlank { p.name })) },
                )
                if (p.id != de.koeven.couchpotato.data.Profile.MAIN) {
                    SettingRow(title = "Kinderprofil", value = if (p.kid) "ja" else "nein") {
                        vm.updateProfile(p.copy(kid = !p.kid))
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
                            steps.getOrNull(steps.indexOf(p.maxFsk) + d)?.let { vm.updateProfile(p.copy(maxFsk = it)) }
                        },
                    ) {
                        val steps = de.koeven.couchpotato.data.Profile.FSK_STEPS
                        val next = steps[(steps.indexOf(p.maxFsk) + 1) % steps.size]
                        vm.updateProfile(p.copy(maxFsk = next))
                    }
                    SettingRow(
                        title = "Bildschirmzeit pro Tag",
                        description = "Zählt Filme, Serien und Apps wie Netflix – ${vm.screenTimeInfo(p)}. Danach nur mit Eltern-PIN weiter.",
                        value = if (p.dailyMinutes <= 0) "unbegrenzt" else "${p.dailyMinutes} Min.",
                        onStep = { d ->
                            val steps = listOf(0, 30, 45, 60, 90, 120, 180)
                            steps.getOrNull(steps.indexOf(p.dailyMinutes).coerceAtLeast(0) + d)?.let { vm.updateProfile(p.copy(dailyMinutes = it)) }
                        },
                    ) {
                        val steps = listOf(0, 30, 45, 60, 90, 120, 180)
                        vm.updateProfile(p.copy(dailyMinutes = steps[(steps.indexOf(p.dailyMinutes) + 1).mod(steps.size)]))
                    }
                    SettingRow(
                        title = "Schlafenszeit",
                        description = "Ab dieser Uhrzeit bis 6 Uhr ist das Kinderprofil gesperrt",
                        value = if (p.bedtime < 0) "aus" else "${de.koeven.couchpotato.data.ScreenTime.clock(p.bedtime)} Uhr",
                        onStep = { d ->
                            val steps = listOf(-1) + (17 * 60..21 * 60 step 30).toList()
                            steps.getOrNull(steps.indexOf(p.bedtime).coerceAtLeast(0) + d)?.let { vm.updateProfile(p.copy(bedtime = it)) }
                        },
                    ) {
                        val steps = listOf(-1) + (17 * 60..21 * 60 step 30).toList()
                        vm.updateProfile(p.copy(bedtime = steps[(steps.indexOf(p.bedtime) + 1).mod(steps.size)]))
                    }
                }
                SettingRow(title = "Tier", value = animalName(p)) {
                    vm.updateProfile(p.copy(animal = (p.animalIndex + 1) % de.koeven.couchpotato.data.Profile.ANIMAL_COUNT))
                }
                SettingRow(title = "Farbe", value = "ändern") { vm.updateProfile(p.copy(color = p.color + 1)) }
                SettingRow(title = "Neu würfeln", description = if (p.kid) "Zufälliges Kindertier und Farbe" else "Zufällige Figur und Farbe", value = "🎲 Würfeln") {
                    vm.updateProfile(
                        p.copy(
                            // kids: cute animal on a soft colour, grown-ups: cool animal on a dark one
                            animal = (0 until de.koeven.couchpotato.data.Profile.ANIMAL_COUNT).random(),
                            color = (0 until de.koeven.couchpotato.data.Profile.COLOR_COUNT).random(),
                        )
                    )
                }
                if (p.id != de.koeven.couchpotato.data.Profile.MAIN) {
                    SettingRow(title = "Profil löschen", description = "Sein „Weiterschauen“ und Verlauf werden gelöscht", value = "Löschen") {
                        vm.removeProfile(p.id)
                        editingId = null
                    }
                }
            }
        }
    }
    SettingRow(title = "Neues Profil", value = "Anlegen") {
        editingId = vm.addProfile("", kid = false).id
    }
    SettingRow(title = "Neues Kinderprofil", value = "Anlegen") {
        editingId = vm.addProfile("", kid = true).id
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
private fun KidCategories(vm: HomeViewModel, state: HomeState, onBack: () -> Unit) {
    SectionTitle("Kategorien für Kinder", "OK schaltet eine Kategorie frei oder sperrt sie.")
    SettingRow(title = "← Zurück", value = "${state.kidCategories.size} freigegeben") { onBack() }
    for ((kind, label) in listOf(
        SearchEntry.KIND_MOVIE to "Filme",
        SearchEntry.KIND_SERIES to "Serien",
        SearchEntry.KIND_LIVE to "Live-TV",
    )) {
        val cats = vm.categoriesFor(kind)
        if (cats.isEmpty()) continue
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        cats.forEach { c ->
            val key = "$kind:${c.id}"
            val allowed = key in state.kidCategories
            SettingRow(title = c.name, value = if (allowed) "✓ frei" else "gesperrt") {
                vm.setKidCategory(key, !allowed)
            }
        }
    }
}

@Composable
private fun KidApps(vm: HomeViewModel, state: HomeState, onBack: () -> Unit) {
    SectionTitle("Apps für Kinder", "Nur diese Apps erscheinen in Kinderprofilen.")
    SettingRow(title = "← Zurück", value = "${state.kidApps.size} freigegeben") { onBack() }
    state.allApps.forEach { a ->
        val allowed = a.packageName in state.kidApps
        SettingRow(title = a.label, value = if (allowed) "✓ frei" else "gesperrt") {
            vm.setKidApp(a.packageName, !allowed)
        }
    }
}
