package de.koeven.couchpotato.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.AppEntry
import de.koeven.couchpotato.data.AppsRepository
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    homeTick: Int,
    onOpenSeries: (SeriesItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBrowse: (Char) -> Unit,
    onOpenMovie: (Long, String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }

    val liveRowState = rememberLazyListState()
    val appsRowState = rememberLazyListState()
    var menu by remember { mutableStateOf<HomeMenu?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }

    // The first visible row (in the user's order) gets the initial focus.
    val firstRow = state.rowOrder.firstOrNull { id ->
        when (id) {
            HomeRows.CONTINUE -> state.continueWatching.isNotEmpty()
            HomeRows.LIVE -> state.configured
            HomeRows.APPS -> state.apps.isNotEmpty()
            HomeRows.MOVIES -> state.movies.isNotEmpty()
            HomeRows.SERIES -> state.series.isNotEmpty()
            HomeRows.RECOMMENDED -> state.recommended.isNotEmpty()
            else -> false
        }
    }
    val hasFocusTarget = firstRow != null

    /** While this card is being moved: left/right moves it, OK or Back finishes. */
    fun Modifier.movable(row: String, key: String, onMove: (Int) -> Unit): Modifier {
        val m = moving
        if (m == null || m.row != row || m.key != key) return this
        return this
            .border(3.dp, CpColors.Accent, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { e ->
                val finishKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape)
                when {
                    e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {
                        if (e.type == KeyEventType.KeyDown) onMove(if (e.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    e.key == Key.DirectionUp || e.key == Key.DirectionDown -> true   // stay in the row
                    e.key in finishKeys -> {
                        // finish on release, so the card doesn't also see the click
                        if (e.type == KeyEventType.KeyUp) moving = null
                        true
                    }
                    else -> false
                }
            }
    }

    // keep "Jetzt läuft" fresh while the home screen is open
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            vm.refreshEpg(force = false)
        }
    }

    LaunchedEffect(homeTick) { moving = null; menu = null }

    LaunchedEffect(homeTick, hasFocusTarget) {
        if (!hasFocusTarget) return@LaunchedEffect
        listState.scrollToItem(0)
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 0.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),   // groups sit close together, like Fire OS
    ) {
        item {
            TopBar(
                active = NavTab.Start,
                showContentTabs = state.configured,
                status = if (state.loading) "aktualisiere …" else null,
                onStart = { scope.launch { listState.animateScrollToItem(0) } },
            )
        }
        item { Spacer(Modifier.height(18.dp)) }   // space between tab bar and the first row

        if (!state.configured) {
            item {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    Text("IPTV ist noch nicht eingerichtet.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Unter Einstellungen Server, Benutzername und Passwort deines Anbieters eintragen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CpColors.TextMuted,
                    )
                }
            }
        }

        for (rowId in state.rowOrder) {
            when (rowId) {
                HomeRows.CONTINUE -> if (state.continueWatching.isNotEmpty()) {
                    item(key = HomeRows.CONTINUE) {
                        Shelf(HomeRows.label(HomeRows.CONTINUE)) {
                            itemsIndexed(state.continueWatching, key = { _, c -> c.key }) { i, item ->
                                val focusMod = if (i == 0 && firstRow == HomeRows.CONTINUE) Modifier.focusRequester(firstFocus) else Modifier
                                val onLong = { menu = HomeMenu.Continue(item) }
                                when (item) {
                                    is ContinueItem.Iptv -> {
                                        val e = item.entry
                                        ContinueCard(
                                            title = e.item.title,
                                            subtitle = e.item.subtitle,
                                            image = e.item.image,
                                            fraction = if (e.upNext) 0f else e.fraction,
                                            badge = when {
                                                item.newEpisodes -> "Neue Folgen · IPTV"
                                                e.upNext -> "Nächste Folge · IPTV"
                                                else -> "IPTV"
                                            },
                                            onClick = { scope.launch { Playback.start(context, vm.playlistFor(e)) } },
                                            onLongClick = onLong,
                                            modifier = focusMod,
                                        )
                                    }
                                    is ContinueItem.External -> {
                                        val p = item.program
                                        ContinueCard(
                                            title = p.title,
                                            subtitle = p.subtitle,
                                            image = p.image,
                                            fraction = p.fraction,
                                            badge = item.appLabel,
                                            onClick = { vm.openExternal(item) },
                                            onLongClick = onLong,
                                            modifier = focusMod,
                                        )
                                    }
                                    is ContinueItem.Media -> {
                                        val p = item.play
                                        ContinueCard(
                                            title = p.subtitle ?: p.title,
                                            subtitle = if (p.subtitle != null) p.title else null,
                                            image = p.image,
                                            bitmap = if (p.image == null) state.apps.firstOrNull { it.packageName == p.packageName }?.let { it.banner ?: it.icon } else null,
                                            fraction = p.fraction,
                                            badge = item.appLabel,
                                            onClick = { vm.openMedia(item) },
                                            onLongClick = onLong,
                                            modifier = focusMod,
                                        )
                                    }
                                    is ContinueItem.NewEpisodes -> {
                                        ContinueCard(
                                            title = item.series.name,
                                            subtitle = "Neue Folgen verfügbar",
                                            image = item.series.image,
                                            fraction = 0f,
                                            badge = "Neue Folgen · IPTV",
                                            onClick = { onOpenSeries(SeriesItem(item.series.id, item.series.name, item.series.image, item.series.added, item.series.categoryId)) },
                                            onLongClick = onLong,
                                            modifier = focusMod,
                                        )
                                    }
                                    is ContinueItem.AppTile -> {
                                        ContinueCard(
                                            title = item.app.label,
                                            subtitle = "dort weiterschauen",
                                            image = null,
                                            bitmap = item.app.banner ?: item.app.icon,
                                            fraction = 0f,
                                            badge = null,
                                            onClick = { vm.launchApp(item.app.packageName) },
                                            onLongClick = onLong,
                                            modifier = focusMod,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HomeRows.LIVE -> if (state.configured) {
                    item(key = HomeRows.LIVE) {
                        Shelf(
                            HomeRows.label(HomeRows.LIVE),
                            state = liveRowState,
                            hint = if (moving?.row == HomeRows.LIVE) MOVE_HINT else null,
                        ) {
                            itemsIndexed(state.favorites, key = { _, ch -> ch.id }) { i, ch ->
                                ChannelCard(
                                    name = ch.name,
                                    logo = ch.logo,
                                    nowPlaying = state.nowPlaying[ch.id],
                                    onClick = { vm.playLive(context, state.favorites, i) },
                                    onLongClick = { menu = HomeMenu.Channel(ch) },
                                    modifier = Modifier
                                        .then(if (i == 0 && firstRow == HomeRows.LIVE) Modifier.focusRequester(firstFocus) else Modifier)
                                        .movable(HomeRows.LIVE, ch.id.toString()) { delta ->
                                            val j = vm.moveFavorite(ch.id, delta)
                                            scope.launch { liveRowState.keepVisible(j) }
                                        },
                                )
                            }
                            item(key = "manage-live") {
                                TileCard(
                                    label = if (state.favorites.isEmpty()) "Sender\nauswählen" else "Sender\nverwalten",
                                    onClick = onOpenLive,
                                    width = ChannelWidth,
                                    modifier = if (state.favorites.isEmpty() && firstRow == HomeRows.LIVE) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                    }
                }

                HomeRows.APPS -> if (state.apps.isNotEmpty()) {
                    item(key = HomeRows.APPS) {
                        Shelf(
                            HomeRows.label(HomeRows.APPS),
                            state = appsRowState,
                            hint = if (moving?.row == HomeRows.APPS) MOVE_HINT else null,
                        ) {
                            itemsIndexed(state.apps, key = { _, a -> a.packageName }) { i, app ->
                                AppCard(
                                    app = app,
                                    onClick = { vm.launchApp(app.packageName) },
                                    onLongClick = { menu = HomeMenu.App(app) },
                                    modifier = Modifier
                                        .then(if (i == 0 && firstRow == HomeRows.APPS) Modifier.focusRequester(firstFocus) else Modifier)
                                        .movable(HomeRows.APPS, app.packageName) { delta ->
                                            val j = vm.moveApp(app.packageName, delta)
                                            scope.launch { appsRowState.keepVisible(j) }
                                        },
                                )
                            }
                            item(key = "system-settings") {
                                TileCard("Fire TV\nEinstellungen", onClick = { AppsRepository.openSystemSettings(context) })
                            }
                        }
                    }
                }

                HomeRows.MOVIES -> if (state.movies.isNotEmpty()) {
                    item(key = HomeRows.MOVIES) {
                        Shelf(HomeRows.label(HomeRows.MOVIES)) {
                            itemsIndexed(state.movies, key = { _, m -> m.id }) { i, movie ->
                                PosterCard(
                                    title = movie.name,
                                    image = movie.image,
                                    onClick = { onOpenMovie(movie.id, movie.name) },
                                    modifier = if (i == 0 && firstRow == HomeRows.MOVIES) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                    }
                }

                HomeRows.SERIES -> if (state.series.isNotEmpty()) {
                    item(key = HomeRows.SERIES) {
                        Shelf(HomeRows.label(HomeRows.SERIES)) {
                            itemsIndexed(state.series, key = { _, s -> s.id }) { i, series ->
                                PosterCard(
                                    title = series.name,
                                    image = series.image,
                                    onClick = { onOpenSeries(series) },
                                    modifier = if (i == 0 && firstRow == HomeRows.SERIES) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                    }
                }

                HomeRows.RECOMMENDED -> if (state.recommended.isNotEmpty()) {
                    item(key = HomeRows.RECOMMENDED) {
                        Shelf(
                            HomeRows.label(HomeRows.RECOMMENDED),
                            hint = if (state.recommendedPersonal) null else "Nach Bewertung – wird mit jedem Film und jeder Serie persönlicher",
                        ) {
                            itemsIndexed(state.recommended, key = { _, e -> "${e.kind}${e.id}" }) { i, e ->
                                PosterCard(
                                    title = e.name,
                                    image = e.image,
                                    onClick = {
                                        if (e.kind == SearchEntry.KIND_SERIES) {
                                            onOpenSeries(SeriesItem(e.id, e.name, e.image, e.added, e.categoryId))
                                        } else {
                                            onOpenMovie(e.id, e.name)
                                        }
                                    },
                                    modifier = if (i == 0 && firstRow == HomeRows.RECOMMENDED) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { err ->
            item {
                Text(
                    "IPTV konnte nicht geladen werden: $err",
                    color = CpColors.Error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }
    }

    when (val m = menu) {
        is HomeMenu.App -> ContextMenu(
            title = m.app.label,
            actions = listOf(
                MenuAction("Verschieben") { moving = Moving(HomeRows.APPS, m.app.packageName) },
                MenuAction("Ausblenden") {
                    vm.hideApp(m.app.packageName)
                    Toast.makeText(context, "${m.app.label} ausgeblendet (Einstellungen → zurücksetzen)", Toast.LENGTH_SHORT).show()
                },
            ),
            onDismiss = { menu = null },
        )
        is HomeMenu.Channel -> ContextMenu(
            title = m.channel.name,
            actions = listOf(
                MenuAction("Verschieben") { moving = Moving(HomeRows.LIVE, m.channel.id.toString()) },
                MenuAction("Aus Favoriten entfernen") { vm.removeFavorite(m.channel) },
            ),
            onDismiss = { menu = null },
        )
        is HomeMenu.Continue -> ContextMenu(
            title = when (val c = m.item) {
                is ContinueItem.Iptv -> c.entry.item.title
                is ContinueItem.External -> c.program.title
                is ContinueItem.AppTile -> c.app.label
                is ContinueItem.NewEpisodes -> c.series.name
                is ContinueItem.Media -> c.play.subtitle ?: c.play.title
            },
            actions = listOf(
                MenuAction("Aus „Weiterschauen“ entfernen") { vm.removeFromContinue(m.item) },
            ),
            onDismiss = { menu = null },
        )
        null -> Unit
    }
}

private const val MOVE_HINT = "◀ ▶ verschieben · OK oder Zurück = fertig"

private data class Moving(val row: String, val key: String)

private sealed interface HomeMenu {
    data class App(val app: AppEntry) : HomeMenu
    data class Channel(val channel: LiveChannel) : HomeMenu
    data class Continue(val item: ContinueItem) : HomeMenu
}

/** Scrolls the row so the moved card stays on screen. */
private suspend fun LazyListState.keepVisible(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return
    val first = visible.first().index
    val last = visible.last().index
    when {
        index <= first -> animateScrollToItem((index - 1).coerceAtLeast(0))
        index >= last -> animateScrollToItem((index - visible.size + 2).coerceAtLeast(0))
    }
}
