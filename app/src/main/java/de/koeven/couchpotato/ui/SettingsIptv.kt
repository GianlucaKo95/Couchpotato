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
internal fun LoginSection(vm: HomeViewModel, setStatus: (String?) -> Unit) {
    val initial = remember { vm.catalog.config() }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var asLink by remember { mutableStateOf(vm.catalog.loginAsLink) }
    var link by remember { mutableStateOf(initial.toM3uLink()) }
    var result by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf(false) }
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
        vm.catalog.loginAsLink = asLink
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
    // the whole login from the phone with one QR code – live into the fields above
    SettingRow(
        title = "Mit dem Handy eingeben",
        description = if (asLink) "M3U-Link auf dem Handy einfügen" else "Server, Benutzername und Passwort auf dem Handy eintippen",
        value = if (phone) "QR-Code ausblenden" else "QR-Code zeigen",
    ) { phone = !phone }
    if (phone) {
        key(asLink) {
            if (asLink) {
                PhoneLinkPanel(listOf(de.koeven.couchpotato.data.PhoneLink.Field("link", "M3U-Link", value = link))) { v ->
                    link = v["link"].orEmpty().replace(Regex("[\r\n\t]+"), "").trim()
                }
            } else {
                PhoneLinkPanel(
                    listOf(
                        de.koeven.couchpotato.data.PhoneLink.Field("url", "Server", value = url),
                        de.koeven.couchpotato.data.PhoneLink.Field("user", "Benutzername", value = user),
                        de.koeven.couchpotato.data.PhoneLink.Field("pass", "Passwort", password = true, value = pass),
                    )
                ) { v ->
                    fun clean(s: String?) = s.orEmpty().replace(Regex("[\r\n\t]+"), "").trim()
                    val server = clean(v["url"])
                    // a whole M3U link pasted into "Server": split it into the three fields
                    val parsed = XtreamConfig.fromLink(server)
                    if (parsed != null) {      // the link carries user and password itself
                        url = XtreamConfig.normalizeBase(parsed.baseUrl); user = parsed.user; pass = parsed.pass
                    } else {
                        url = server; user = clean(v["user"]); pass = clean(v["pass"])
                    }
                }
            }
        }
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
                    val r = vm.catalog.saveAndTest(cfg)
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
internal fun CatalogSection(vm: HomeViewModel, state: HomeState, setStatus: (String?) -> Unit) {
    // re-read the counters whenever the screen state changes (they live in the request brake)
    val last = remember(state.syncInfo) { vm.catalog.lastSyncAt() }
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
    ) { setStatus(vm.catalog.syncNow()) }
    var syncHours by remember { mutableStateOf(vm.catalog.syncIntervalHours) }
    SettingRow(
        title = "Automatischer Abgleich",
        description = "Holt neue Filme, Serien und Folgen · 6 Anfragen pro Abgleich",
        value = "alle $syncHours Stunden",
        onStep = { syncHours = if (syncHours == 24) 12 else 24; vm.catalog.syncIntervalHours = syncHours },
    ) { syncHours = if (syncHours == 24) 12 else 24; vm.catalog.syncIntervalHours = syncHours }
    SettingRow(
        title = "Bilder vom Anbieter-Server",
        description = "Einzeln, mit Pause, max. ${XtreamClient.IMAGES_PER_HOUR} pro Stunde · jedes Bild nur einmal",
        value = if (state.loadProviderImages) "laden (gebremst)" else "nicht laden",
    ) { vm.catalog.setLoadProviderImages(!state.loadProviderImages) }
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
