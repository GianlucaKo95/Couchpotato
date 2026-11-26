package de.koeven.couchpotato.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay

private val SearchBackground = CpColors.Panel
private val KeyGap = 6.dp
private const val KEY_COLUMNS = 7

private val LETTERS = "abcdefghijklmnopqrstuvwxyzäöüß".map { it.toString() }
private val DIGITS = "1234567890-.:'&!?+#/()".map { it.toString() }

/**
 * Search as its own full-screen window, like on Fire OS 8: a keyboard made for the remote
 * on the left (no system keyboard), suggestions and results on the right.
 * Searches only the catalogue stored on the stick – typing never reaches the provider.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: HomeViewModel,
    onOpenSeries: (Long, String) -> Unit,
    onOpenMovie: (Long, String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val results by vm.search.results.collectAsState()
    val query by vm.search.query.collectAsState()
    var digits by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(vm.search.recent()) }
    val firstKey = remember { FocusRequester() }
    val favIds = state.favorites.map { it.id }.toSet()

    BackHandler { onClose() }

    LaunchedEffect(Unit) {
        vm.search.prepare()
        delay(150)
        runCatching { firstKey.requestFocus() }
    }

    LaunchedEffect(query) {
        delay(250)
        vm.search.run(query)
    }

    // cross-app search via TMDB/JustWatch: only after a short pause in typing (internet round trip)
    var streams by remember { mutableStateOf<List<Discovery.StreamResult>>(emptyList()) }
    var streamMenu by remember { mutableStateOf<Discovery.StreamResult?>(null) }
    // theme search ("Weihnachtsfilm", "Film mit Fußball") via TMDB keywords
    var themed by remember { mutableStateOf<Discovery.ThemeResult?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 4) { themed = null; return@LaunchedEffect }
        delay(800)
        themed = vm.discovery.themeSearch(query)
    }
    LaunchedEffect(query) {
        if (query.trim().length < 3) { streams = emptyList(); return@LaunchedEffect }
        delay(700)
        streams = vm.discovery.streamingSearch(query)
    }

    fun type(s: String) = vm.search.setQuery(query + s)
    var phoneOpen by remember { mutableStateOf(false) }

    // voice search – only if this Fire TV offers speech recognition to apps (Alexa itself is reserved for Amazon)
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Was möchtest du schauen?")
    }
    val voiceAvailable = remember {
        runCatching { context.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false)
    }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.search.setQuery(it) }
    }
    fun saveQuery(q: String = query) {
        vm.search.remember(q)
        recent = vm.search.recent()
    }

    val q = query.trim()
    val showResults = q.length >= 2
    val titles: List<SearchEntry> = remember(results) {
        // films and series together, alternating so both kinds show up front
        val m = results.movies
        val s = results.series
        buildList {
            for (i in 0 until maxOf(m.size, s.size)) {
                m.getOrNull(i)?.let { add(it) }
                s.getOrNull(i)?.let { add(it) }
            }
        }
    }
    val liveChannels = results.live.map { LiveChannel(it.id, it.name, it.image, null) }
    val suggestions: List<String> = if (showResults) {
        (recent.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) } +
            titles.map { it.name.substringBefore(" (").trim() })
            .distinctBy { it.lowercase() }
            .filterNot { it.equals(q, ignoreCase = true) }
            .take(6)
    } else {
        recent.take(6)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (LocalProfile.current?.kid == true) KidColors.Sky else SearchBackground)
    ) {
        Row(Modifier.fillMaxSize().padding(start = ScreenPadding, top = 32.dp)) {
            // ---------------- search field + keyboard ----------------
            val keyWidth = 50.dp
            val boardWidth = keyWidth * KEY_COLUMNS + KeyGap * (KEY_COLUMNS - 1)
            Column(Modifier.width(boardWidth).fillMaxHeight()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = CpColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (query.isEmpty()) "Filme, Serien, Sender …" else "${query.takeLast(26)}▏",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (query.isEmpty()) CpColors.TextMuted else CpColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                Spacer(Modifier.height(16.dp))

                val keys = if (digits) DIGITS else LETTERS
                Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                    keys.chunked(KEY_COLUMNS).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                            row.forEachIndexed { c, k ->
                                Key(
                                    k,
                                    keyWidth,
                                    Modifier.then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                                ) { type(k) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        Key("Leerzeichen", keyWidth * 3 + KeyGap * 2) { if (query.isNotEmpty() && !query.endsWith(" ")) type(" ") }
                        Key("⌫ Löschen", keyWidth * 2 + KeyGap) { vm.search.setQuery(query.dropLast(1)) }
                        Key(if (digits) "abc" else "123", keyWidth * 2 + KeyGap) { digits = !digits }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        Key(if (phoneOpen) "Handy-Eingabe beenden" else "Mit dem Handy tippen", keyWidth * 7 + KeyGap * 6, muted = true) { phoneOpen = !phoneOpen }
                    }
                    if (phoneOpen) {
                        // live: the search runs while you type on the phone
                        PhoneLinkPanel("Suche") { text -> vm.search.setQuery(text.replace(Regex("\\s+"), " ").trimStart()) }
                    }
                    if (voiceAvailable) Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        Key("Sprechen", keyWidth * 7 + KeyGap * 6) { runCatching { voice.launch(voiceIntent) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        Key("Alles löschen", keyWidth * 4 + KeyGap * 3, muted = true) { vm.search.setQuery("") }
                        Key("Schließen", keyWidth * 3 + KeyGap * 2, muted = true) { onClose() }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Zurück = schließen",
                    style = MaterialTheme.typography.labelMedium,
                    color = CpColors.TextMuted,
                )
                Text(
                    state.indexStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = CpColors.TextMuted,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.width(36.dp))

            // ---------------- suggestions + results ----------------
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                if (suggestions.isNotEmpty()) {
                    item(key = "suggest") {
                        Section(if (showResults) "Vorschläge" else "Zuletzt gesucht") {
                            items(suggestions, key = { "sg-$it" }) { s ->
                                TabPill(s, selected = false) { vm.search.setQuery(s) }
                            }
                        }
                    }
                } else if (!showResults) {
                    item(key = "hint") {
                        Text(
                            "Tippe mit der Fernbedienung – Treffer erscheinen hier, schon ab zwei Buchstaben.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CpColors.TextMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                if (showResults) {
                    if (titles.isNotEmpty()) {
                        item(key = "titles") {
                            Section("Filme & Serien (${titles.size})") {
                                items(titles, key = { "${it.kind}${it.id}" }) { e ->
                                    PosterCard(
                                        title = e.name,
                                        image = e.image,
                                        onClick = {
                                            saveQuery()
                                            if (e.kind == SearchEntry.KIND_SERIES) onOpenSeries(e.id, e.name)
                                            else onOpenMovie(e.id, e.name)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    themed?.let { t ->
                        if (t.iptv.isNotEmpty()) {
                            item(key = "theme-iptv") {
                                Section("Thema „${t.label}“ in deinem IPTV (${t.iptv.size})") {
                                    items(t.iptv, key = { "th${it.kind}${it.id}" }) { e ->
                                        PosterCard(
                                            title = e.name,
                                            image = e.image,
                                            onClick = {
                                                saveQuery()
                                                if (e.kind == SearchEntry.KIND_SERIES) onOpenSeries(e.id, e.name) else onOpenMovie(e.id, e.name)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (t.streaming.isNotEmpty()) {
                            item(key = "theme-streams") {
                                Section("Thema „${t.label}“ bei deinen Streamingdiensten") {
                                    items(t.streaming, key = { "ts${it.hit.tv}${it.hit.tmdbId}" }) { r ->
                                        Column(Modifier.width(PosterWidth)) {
                                            PosterCard(
                                                title = r.hit.title,
                                                image = r.hit.poster,
                                                onClick = {
                                                    saveQuery()
                                                    if (r.apps.size == 1) vm.search.openInStreamingApp(context, r.apps[0], r.hit.title)
                                                    else streamMenu = r
                                                },
                                            )
                                            Text(
                                                r.apps.joinToString(" · ") { it.label },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = CpColors.TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (streams.isNotEmpty()) {
                        item(key = "streams") {
                            Section("Bei deinen Streamingdiensten") {
                                items(streams, key = { "t${it.hit.tv}${it.hit.tmdbId}" }) { r ->
                                    Column(Modifier.width(PosterWidth)) {
                                        PosterCard(
                                            title = r.hit.title,
                                            image = r.hit.poster,
                                            onClick = {
                                                saveQuery()
                                                if (r.apps.size == 1) vm.search.openInStreamingApp(context, r.apps[0], r.hit.title)
                                                else streamMenu = r
                                            },
                                        )
                                        Text(
                                            r.apps.joinToString(" · ") { it.label },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CpColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Text(
                                "Wo es läuft: JustWatch über TMDB · ein Klick sucht den Titel in der App",
                                style = MaterialTheme.typography.labelMedium,
                                color = CpColors.TextMuted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    if (liveChannels.isNotEmpty()) {
                        item(key = "live") {
                            Section("Live-TV (${liveChannels.size}) · lange OK = Favorit") {
                                itemsIndexed(liveChannels, key = { _, ch -> "l${ch.id}" }) { i, ch ->
                                    ChannelCard(
                                        name = (if (ch.id in favIds) "★ " else "") + ch.name,
                                        logo = ch.logo,
                                        nowPlaying = state.nowPlaying[ch.id],
                                        onClick = {
                                            saveQuery()
                                            vm.live.play(context, liveChannels, i)
                                        },
                                        onLongClick = {
                                            val wasFav = ch.id in favIds
                                            vm.live.toggleFavorite(ch)
                                            Notice.show(if (wasFav) "${ch.name} aus Favoriten entfernt" else "${ch.name} zu Favoriten hinzugefügt")
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (results.isEmpty && results.query == query && state.configured && themed?.iptv.isNullOrEmpty()) {
                        item(key = "none") {
                            Text("Keine Treffer bei deinem IPTV-Anbieter.", color = CpColors.TextMuted)
                        }
                    }
                    item(key = "apps") {
                        Section("„$q“ in Apps suchen") {
                            item(key = "global") {
                                TileCard(
                                    label = "Fire TV\nGesamtsuche",
                                    onClick = {
                                        saveQuery()
                                        vm.search.openGlobal(context, q)
                                    },
                                )
                            }
                            items(state.searchApps, key = { it.app.packageName }) { sa ->
                                AppCard(
                                    app = sa.app,
                                    onClick = {
                                        saveQuery()
                                        vm.search.inApp(context, sa, q)
                                    },
                                    onLongClick = { vm.search.inApp(context, sa, q) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    streamMenu?.let { r ->
        ContextMenu(
            title = r.hit.title,
            actions = r.apps.map { a -> MenuAction("In ${a.label} öffnen") { vm.search.openInStreamingApp(context, a, r.hit.title) } },
            onDismiss = { streamMenu = null },
        )
    }
}

/** A titled horizontal row inside the result column (no screen padding – the column already has it). */
@Composable
private fun Section(title: String, content: LazyListScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        LazyRow(
            contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp, end = ScreenPadding, start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Key(
    label: String,
    width: Dp,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = if (muted) 0.05f else 0.09f),
            contentColor = if (muted) CpColors.TextMuted else CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = SearchBackground,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.width(width).height(42.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = if (label.length == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
