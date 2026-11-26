package de.koeven.couchpotato.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.composed
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

    // Start 2.0: the info area at the top shows whatever tile has the focus
    var hero by remember { mutableStateOf<Hero?>(null) }
    fun Modifier.heroOn(h: () -> Hero): Modifier = onFocusChanged { if (it.isFocused) hero = h() }

    // The first visible row (in the user's order) gets the initial focus.
    val firstRow = state.rowOrder.firstOrNull { id ->
        when (id) {
            HomeRows.CONTINUE -> state.continueWatching.isNotEmpty()
            HomeRows.LIVE -> state.configured
            HomeRows.APPS -> state.apps.isNotEmpty()
            HomeRows.MOVIES -> state.movies.isNotEmpty()
            HomeRows.SERIES -> state.series.isNotEmpty()
            HomeRows.RECOMMENDED -> state.recommended.isNotEmpty()
            HomeRows.MY_LIST -> state.myList.isNotEmpty()
            else -> false
        }
    }
    // wait for apps / favourites / "Weiterschauen" (tick > 0), else the focus lands on whatever exists first
    val hasFocusTarget = firstRow != null && state.tick > 0

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
            vm.live.refreshEpg(force = false)
        }
    }

    LaunchedEffect(homeTick) { moving = null; menu = null }

    // Back from a film or series: the same tile gets the focus again (the scroll positions
    // are kept by MainActivity). Otherwise – first start, Home button – the first row.
    var lastFocus by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val enteredTick = remember { homeTick }
    var restoring by remember { mutableStateOf(lastFocus != null) }
    var refocusTick by remember { mutableStateOf(0) }
    var refocused by remember { mutableStateOf(0) }
    fun Modifier.remembered(key: String): Modifier = composed {
        val fr = remember { FocusRequester() }
        if (restoring && key == lastFocus) {
            LaunchedEffect(Unit) {
                // the rows are restored a moment later than the screen: try a few times
                for (wait in longArrayOf(120, 180, 300)) {
                    delay(wait)
                    if (runCatching { fr.requestFocus() }.isSuccess) { restoring = false; break }
                }
            }
        }
        // after something was removed via the long-press menu: the tile next to it gets the focus
        LaunchedEffect(refocusTick) {
            if (refocusTick > 0 && key == lastFocus) {
                delay(150)
                if (runCatching { fr.requestFocus() }.isSuccess) refocused = refocusTick
            }
        }
        focusRequester(fr).onFocusChanged { if (it.isFocused) lastFocus = key }
    }

    /** The tile removed: focus moves to its right neighbour, or the left one at the end of the row. */
    fun removed(key: String, row: List<String>) {
        val i = row.indexOf(key)
        lastFocus = row.getOrNull(i + 1) ?: row.getOrNull(i - 1)
        refocusTick++
    }
    // the row is empty now: the first row instead of the top bar
    LaunchedEffect(refocusTick) {
        if (refocusTick == 0) return@LaunchedEffect
        delay(400)
        if (refocused != refocusTick) runCatching { firstFocus.requestFocus() }
    }

    LaunchedEffect(homeTick, hasFocusTarget) {
        if (!hasFocusTarget) return@LaunchedEffect
        if (restoring && homeTick == enteredTick) {
            delay(900)
            if (!restoring) return@LaunchedEffect        // the tile got its focus back
            restoring = false                             // tile is gone (e.g. other profile): start at the top
        }
        listState.scrollToItem(0)
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
    // big picture of the focused title in the right part of the screen (behind everything)
    HeroBackdrop(vm, hero)
    Column(Modifier.fillMaxSize()) {
    TopBar(
        active = NavTab.Start,
        showContentTabs = state.configured,
        status = if (state.loading) "aktualisiere …" else null,
        onStart = { scope.launch { listState.animateScrollToItem(0) } },
    )
    // the same picture on several "Weiterschauen" tiles from other apps is the app's logo, not a scene
    val sharedPictures = remember(state.continueWatching) {
        state.continueWatching.mapNotNull {
            when (it) {
                is ContinueItem.External -> it.program.image
                is ContinueItem.Media -> it.play.image
                else -> null
            }
        }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }
    // TEST: a channel runs as a small window on top of other apps – its remote is here
    val floating by de.koeven.couchpotato.player.FloatingPlayer.state.collectAsState()
    if (floating.active) {
        val fp = de.koeven.couchpotato.player.FloatingPlayer
        Row(
            Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = androidx.compose.ui.res.painterResource(de.koeven.couchpotato.R.drawable.ic_pl_pip), contentDescription = null,
                tint = CpColors.Accent, modifier = Modifier.size(18.dp))
            Text(floating.title, style = MaterialTheme.typography.labelLarge, color = CpColors.Text, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            // short labels: everything fits in one row on the TV
            SecondaryPill("", icon = de.koeven.couchpotato.R.drawable.ic_pl_shift_left) { fp.previous() }
            SecondaryPill("", icon = de.koeven.couchpotato.R.drawable.ic_pl_shift_right) { fp.next() }
            SecondaryPill("Vollbild") { fp.fullscreen() }
            SecondaryPill(if (floating.muted) "Ton an" else "Ton aus") { fp.toggleSound() }
            SecondaryPill("Ecke") { fp.nextCorner() }
            SecondaryPill("−", enabled = floating.canShrink) { fp.resize(-1) }
            Text("${floating.percent} %", style = MaterialTheme.typography.labelLarge, color = CpColors.TextMuted)
            SecondaryPill("+", enabled = floating.canGrow) { fp.resize(+1) }
            SecondaryPill(if (floating.minimized) "Einblenden" else "Minimieren") {
                if (floating.minimized) fp.restore() else fp.minimize()
            }
            SecondaryPill("Handy") { fp.showPhoneQr() }
            SecondaryPill("Schließen") { fp.stop(context) }
        }
    }
    HeroArea(vm, hero)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),   // groups sit close together, like Fire OS
    ) {

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
                                val focusMod = (if (i == 0 && firstRow == HomeRows.CONTINUE) Modifier.focusRequester(firstFocus) else Modifier)
                                    .remembered("c:${item.key}")
                                val onLong = { menu = HomeMenu.Continue(item) }
                                when (item) {
                                    is ContinueItem.Iptv -> {
                                        val e = item.entry
                                        ContinueCard(
                                            showCaption = false,
                                            title = e.item.title,
                                            subtitle = e.item.subtitle,
                                            image = e.item.image,
                                            fraction = if (e.upNext) 0f else e.fraction,
                                            badge = when {
                                                item.newEpisodes -> "Neue Folgen"
                                                e.upNext -> "Nächste Folge"
                                                else -> null
                                            },
                                            onClick = { scope.launch { Playback.start(context, vm.titles.playlistFor(e)) } },
                                            onLongClick = onLong,
                                            modifier = focusMod.heroOn {
                                                val episode = e.item.kind == de.koeven.couchpotato.data.PlayItem.KIND_EPISODE
                                                val left = if (e.durationMs > 0 && !e.upNext) ((e.durationMs - e.positionMs) / 60_000).coerceAtLeast(1) else null
                                                Hero(
                                                    kicker = when {
                                                        item.newEpisodes -> "Neue Folgen"
                                                        e.upNext -> "Nächste Folge"
                                                        else -> "Weiterschauen · " + if (episode) "Serie" else "Film"
                                                    },
                                                    title = e.item.title,
                                                    sub = e.item.subtitle?.let { s -> Regex("""^S0?(\d+) E0?(\d+)""").replace(s, "Staffel $1 · Folge $2") },
                                                    fraction = if (e.upNext) 0f else e.fraction,
                                                    remaining = left?.let { m -> "noch $m Min." },
                                                    image = e.item.image,
                                                    plotKind = if (episode) SearchEntry.KIND_SERIES else SearchEntry.KIND_MOVIE,
                                                    plotId = if (episode) e.item.seriesId else e.item.streamId,
                                                    season = e.item.subtitle?.let { s -> Regex("""^S(\d+) E""").find(s)?.groupValues?.get(1)?.toIntOrNull() },
                                                    episode = e.item.subtitle?.let { s -> Regex("""^S\d+ E(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull() },
                                                )
                                            },
                                        )
                                    }
                                    is ContinueItem.External -> {
                                        val p = item.program
                                        // the app's scene picture if it sends one; its logo (RTL+) is replaced by TMDB's picture
                                        val picture = rememberContinuePicture(vm, p.title, p.subtitle, p.image, p.image in sharedPictures)
                                        ContinueCard(
                                            showCaption = false,
                                            title = p.title,
                                            subtitle = p.subtitle,
                                            image = picture,
                                            fraction = p.fraction,
                                            badge = item.appLabel,
                                            onClick = { vm.openExternal(item) },
                                            onLongClick = onLong,
                                            modifier = focusMod.heroOn {
                                                Hero("Weiterschauen" + (item.appLabel?.let { " · $it" } ?: ""), p.title, p.subtitle, p.fraction, image = p.image,
                                                    lookup = de.koeven.couchpotato.data.Names.lookupNames(p.title, p.subtitle))
                                            },
                                        )
                                    }
                                    is ContinueItem.Media -> {
                                        val p = item.play
                                        val picture = rememberContinuePicture(vm, p.title, p.subtitle, p.image, p.image in sharedPictures)
                                        ContinueCard(
                                            showCaption = false,
                                            title = p.subtitle ?: p.title,
                                            subtitle = if (p.subtitle != null) p.title else null,
                                            image = picture,
                                            bitmap = if (picture == null) state.apps.firstOrNull { it.packageName == p.packageName }?.let { it.banner ?: it.icon } else null,
                                            fraction = p.fraction,
                                            badge = item.appLabel,
                                            onClick = { vm.openMedia(item) },
                                            onLongClick = onLong,
                                            modifier = focusMod.heroOn {
                                                Hero("Weiterschauen" + (item.appLabel?.let { " · $it" } ?: ""), p.subtitle ?: p.title, if (p.subtitle != null) p.title else null, p.fraction, image = p.image,
                                                    lookup = de.koeven.couchpotato.data.Names.lookupNames(p.title, p.subtitle))
                                            },
                                        )
                                    }
                                    is ContinueItem.NewEpisodes -> {
                                        ContinueCard(
                                            showCaption = false,
                                            title = item.series.name,
                                            subtitle = "Neue Folgen verfügbar",
                                            image = item.series.image,
                                            fraction = 0f,
                                            badge = "Neue Folgen",
                                            onClick = { onOpenSeries(SeriesItem(item.series.id, item.series.name, item.series.image, item.series.added, item.series.categoryId)) },
                                            onLongClick = onLong,
                                            modifier = focusMod.heroOn {
                                                Hero("Neue Folgen", item.series.name, "Seit deinem letzten Mal sind neue Folgen dazugekommen",
                                                    plotKind = SearchEntry.KIND_SERIES, plotId = item.series.id)
                                            },
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
                                            onClick = { vm.apps.launch(item.app.packageName) },
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
                            // the favourites, then the 3 channels watched last that aren't favourites
                            val favIds = state.favorites.map { it.id }.toSet()
                            val recent = state.recentLive.filter { it.id !in favIds }.take(3)
                            val channels = state.favorites + recent
                            itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                                val isRecent = ch.id !in favIds
                                ChannelCard(
                                    name = ch.name,
                                    logo = ch.logo,
                                    nowPlaying = if (!isRecent) state.nowPlaying[ch.id]
                                        else state.nowPlaying[ch.id]?.let { "Zuletzt · $it" } ?: "Zuletzt geschaut",
                                    onClick = { vm.live.play(context, channels, i) },
                                    onLongClick = { menu = if (isRecent) HomeMenu.Recent(ch) else HomeMenu.Channel(ch) },
                                    modifier = Modifier
                                        .then(if (i == 0 && firstRow == HomeRows.LIVE) Modifier.focusRequester(firstFocus) else Modifier)
                                        .remembered("l:${ch.id}")
                                        .heroOn { Hero(if (isRecent) "Live-TV · zuletzt geschaut" else "Live-TV · jetzt", ch.name, state.nowPlaying[ch.id]) }
                                        .movable(HomeRows.LIVE, ch.id.toString()) { delta ->
                                            val j = vm.live.moveFavorite(ch.id, delta)
                                            scope.launch { liveRowState.keepVisible(j) }
                                        },
                                )
                            }
                            item(key = "manage-live") {
                                // management, not a channel: a small button at the end of the row
                                Box(Modifier.height(ChannelWidth * 9 / 16), contentAlignment = Alignment.Center) {
                                    SecondaryPill(
                                        if (state.favorites.isEmpty()) "Sender auswählen" else "Sender verwalten",
                                        modifier = (if (channels.isEmpty() && firstRow == HomeRows.LIVE) Modifier.focusRequester(firstFocus) else Modifier)
                                            .heroOn { Hero("Live-TV", "Sender verwalten", "Lieblingssender auswählen und sortieren") },
                                        onClick = onOpenLive,
                                    )
                                }
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
                                    onClick = { vm.apps.launch(app.packageName) },
                                    onLongClick = { menu = HomeMenu.App(app) },
                                    modifier = Modifier
                                        .then(if (i == 0 && firstRow == HomeRows.APPS) Modifier.focusRequester(firstFocus) else Modifier)
                                        .remembered("a:${app.packageName}")
                                        .heroOn { Hero("App", app.label) }
                                        .movable(HomeRows.APPS, app.packageName) { delta ->
                                            val j = vm.apps.move(app.packageName, delta)
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
                                    onLongClick = { menu = HomeMenu.Title(SearchEntry.KIND_MOVIE, movie.id, movie.name, HomeRows.MOVIES) },
                                    modifier = (if (i == 0 && firstRow == HomeRows.MOVIES) Modifier.focusRequester(firstFocus) else Modifier)
                                        .remembered("m:${movie.id}")
                                        .heroOn { Hero("Neu · Film", movie.name, plotKind = SearchEntry.KIND_MOVIE, plotId = movie.id) },
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
                                    onLongClick = { menu = HomeMenu.Title(SearchEntry.KIND_SERIES, series.id, series.name, HomeRows.SERIES) },
                                    modifier = (if (i == 0 && firstRow == HomeRows.SERIES) Modifier.focusRequester(firstFocus) else Modifier)
                                        .remembered("s:${series.id}")
                                        .heroOn { Hero("Neu · Serie", series.name, plotKind = SearchEntry.KIND_SERIES, plotId = series.id) },
                                )
                            }
                        }
                    }
                }

                HomeRows.MY_LIST -> if (state.myList.isNotEmpty()) {
                    item(key = HomeRows.MY_LIST) {
                        Shelf(HomeRows.label(HomeRows.MY_LIST)) {
                            itemsIndexed(state.myList, key = { _, e -> "${e.kind}${e.id}" }) { i, e ->
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
                                    onLongClick = { menu = HomeMenu.Title(e.kind, e.id, e.name, HomeRows.MY_LIST) },
                                    modifier = (if (i == 0 && firstRow == HomeRows.MY_LIST) Modifier.focusRequester(firstFocus) else Modifier)
                                        .remembered("L:${e.kind}${e.id}")
                                        .heroOn {
                                            Hero(
                                                "Meine Liste · " + if (e.kind == SearchEntry.KIND_SERIES) "Serie" else "Film",
                                                e.name,
                                                listOfNotNull(e.year, e.genre, e.rating.takeIf { it > 0f }?.let { r -> "★ %.1f".format(r) }).joinToString(" · ").ifBlank { null },
                                                plotKind = e.kind, plotId = e.id,
                                            )
                                        },
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
                                    onLongClick = { menu = HomeMenu.Title(e.kind, e.id, e.name) },
                                    modifier = (if (i == 0 && firstRow == HomeRows.RECOMMENDED) Modifier.focusRequester(firstFocus) else Modifier)
                                        .remembered("r:${e.kind}${e.id}")
                                        .heroOn {
                                            Hero(
                                                "Für dich · " + if (e.kind == SearchEntry.KIND_SERIES) "Serie" else "Film",
                                                e.name,
                                                listOfNotNull(e.year, e.genre, e.rating.takeIf { it > 0f }?.let { r -> "★ %.1f".format(r) }).joinToString(" · ").ifBlank { null },
                                                plotKind = e.kind, plotId = e.id,
                                            )
                                        },
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
    }
    }

    when (val m = menu) {
        is HomeMenu.App -> ContextMenu(
            title = m.app.label,
            actions = listOf(
                MenuAction("Verschieben") { moving = Moving(HomeRows.APPS, m.app.packageName) },
                MenuAction("Ausblenden") {
                    removed("a:${m.app.packageName}", state.apps.map { "a:${it.packageName}" })
                    vm.apps.hide(m.app.packageName)
                    Notice.show("${m.app.label} ausgeblendet (Einstellungen → zurücksetzen)")
                },
            ),
            onDismiss = { menu = null },
        )
        is HomeMenu.Channel -> ContextMenu(
            title = m.channel.name,
            actions = listOf(
                MenuAction("Verschieben") { moving = Moving(HomeRows.LIVE, m.channel.id.toString()) },
                MenuAction("Aus Favoriten entfernen") {
                    removed("l:${m.channel.id}", liveKeys(state))
                    vm.live.removeFavorite(m.channel)
                },
            ),
            onDismiss = { menu = null },
        )
        is HomeMenu.Recent -> ContextMenu(
            title = m.channel.name,
            actions = listOf(
                MenuAction("Zu Favoriten hinzufügen") { vm.live.toggleFavorite(m.channel) },
                MenuAction("Aus „Zuletzt geschaut“ entfernen") {
                    removed("l:${m.channel.id}", liveKeys(state))
                    vm.live.removeRecent(m.channel)
                },
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
                MenuAction("Aus „Weiterschauen“ entfernen") {
                    removed("c:${m.item.key}", state.continueWatching.map { "c:${it.key}" })
                    vm.removeFromContinue(m.item)
                },
            ),
            onDismiss = { menu = null },
        )
        is HomeMenu.Title -> ContextMenu(
            title = m.name,
            actions = listOf(myListMenuAction(vm, m.kind, m.id).let { a ->
                // taken off "Meine Liste" in that row: the tile next to it gets the focus
                if (m.row == HomeRows.MY_LIST) a.copy(onClick = { removed("L:${m.kind}${m.id}", state.myList.map { "L:${it.kind}${it.id}" }); a.onClick() }) else a
            }),
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
    data class Recent(val channel: LiveChannel) : HomeMenu
    data class Continue(val item: ContinueItem) : HomeMenu
    data class Title(val kind: Char, val id: Long, val name: String, val row: String = "") : HomeMenu
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


/** What the info area shows. */
private data class Hero(
    val kicker: String,
    val title: String,
    val sub: String? = null,
    val fraction: Float = 0f,
    val remaining: String? = null,
    val plotKind: Char? = null,
    val plotId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val image: String? = null,      // wide picture (episode still) – films/series use TMDB's backdrop
    val lookup: List<String>? = null,   // title from another app: plot and picture from TMDB by name
)

/**
 * The big picture on the right: TMDB's wide backdrop if it's on the stick, else the episode's
 * picture the tile already loaded. It fades into the petrol to the left, bottom and top so the
 * text stays readable – on bright pictures and on a projector too.
 */
@Composable
private fun HeroBackdrop(vm: HomeViewModel, hero: Hero?) {
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hero?.plotKind, hero?.plotId, hero?.image, hero?.lookup) {
        val h = hero
        if (h == null) { url = null; return@LaunchedEffect }
        delay(250)   // only where you stop, not while flying through a row
        val tmdb = when {
            h.plotKind != null && h.plotId != null -> vm.titles.heroBackdrop(h.plotKind, h.plotId)
            !h.lookup.isNullOrEmpty() -> vm.titles.heroByTitle(h.lookup)?.backdrop?.replace("/w1280/", "/w780/")
            else -> null
        }
        url = tmdb ?: h.image
    }
    WideBackdrop(url)
}

/**
 * Start 2.0: big, readable info about the focused tile – like Netflix or Google TV. The plot comes
 * only from what is already on the stick (TMDB cache / series info), never from a new request.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HeroArea(vm: HomeViewModel, hero: Hero?) {
    var plot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hero?.plotKind, hero?.plotId, hero?.episode, hero?.lookup) {
        plot = null
        hero?.lookup?.takeIf { it.isNotEmpty() }?.let { t ->
            delay(250)
            plot = vm.titles.heroByTitle(t)?.plot?.takeIf { it.isNotBlank() }
            return@LaunchedEffect
        }
        val k = hero?.plotKind ?: return@LaunchedEffect
        val id = hero.plotId ?: return@LaunchedEffect
        delay(250)   // scrolling quickly through a row: only look it up where you stop
        plot = vm.titles.heroPlot(k, id, hero.season, hero.episode)
    }
    // compact, so two rows still fit below it
    Column(
        Modifier
            .fillMaxWidth()
            .height(138.dp)
            // breathing room below the tab bar, like in the mockup
            .padding(start = ScreenPadding, end = ScreenPadding * 3, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (hero == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hero.kicker,
                style = MaterialTheme.typography.labelLarge,
                color = CpColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // the long press does a lot here – say so, quietly
            Text("OK lang: Optionen", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted, maxLines = 1)
        }
        // long titles (other apps often send "Serie – Staffel 2 – Folge 5: …") run through instead of "…"
        Text(
            hero.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, velocity = 40.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            hero.sub?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, velocity = 40.dp))
            }
            if (hero.fraction > 0f) ProgressBar(hero.fraction, Modifier.width(140.dp))
            hero.remaining?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
        plot?.let {
            // two short lines instead of one endless one – easier to read from the sofa
            Text(it, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 540.dp))
        }
    }
}

/**
 * Picture for a tile from another app: the app's own one when it is a real scene picture
 * (landscape, big enough, not the same for several titles); otherwise – usually the app's logo,
 * e.g. RTL+ – TMDB's wide picture of the title, found by its name and kept on the stick.
 */
@Composable
private fun rememberContinuePicture(vm: HomeViewModel, title: String?, subtitle: String?, appPicture: String?, shared: Boolean): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val names = remember(title, subtitle) { de.koeven.couchpotato.data.Names.lookupNames(title, subtitle) }
    val picture by androidx.compose.runtime.produceState(appPicture, names, appPicture, shared) {
        if (appPicture != null && !shared && isScenePicture(context, appPicture)) return@produceState
        val tmdb = if (names.isEmpty()) null else vm.titles.heroByTitle(names)?.let { d -> d.backdrop?.replace("/w1280/", "/w780/") ?: d.poster }
        value = tmdb ?: appPicture
    }
    return picture
}

/**
 * A scene picture is landscape (≥ 4:3), at least 320 px wide and colourful. App logos – even
 * wide ones like RTL+'s – are square/tall/tiny or made of a few flat colours.
 */
private suspend fun isScenePicture(context: android.content.Context, url: String): Boolean = runCatching {
    val result = coil.Coil.imageLoader(context).execute(
        coil.request.ImageRequest.Builder(context).data(url).size(coil.size.Size.ORIGINAL).allowHardware(false).build()
    )
    val d = result.drawable ?: return@runCatching false
    val w = d.intrinsicWidth
    val h = d.intrinsicHeight
    if (w < 320 || h <= 0 || w.toFloat() / h < 1.3f) return@runCatching false
    colourful(d)
}.getOrDefault(false)

/** Counts the colours of a small copy: a photo has many, a logo on a plain background only a few. */
internal fun colourful(d: android.graphics.drawable.Drawable): Boolean {
    val bmp = android.graphics.Bitmap.createBitmap(48, 27, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val old = d.bounds
    d.setBounds(0, 0, 48, 27)
    d.draw(canvas)
    d.bounds = old
    val colours = HashSet<Int>()
    for (y in 0 until 27) for (x in 0 until 48) {
        val p = bmp.getPixel(x, y)
        // 4 bits per channel: tiny shading differences don't count as a new colour
        colours += ((p shr 20) and 0xF shl 8) or ((p shr 12) and 0xF shl 4) or ((p shr 4) and 0xF)
    }
    bmp.recycle()
    return colours.size >= LOGO_MAX_COLOURS
}

/** Fewer distinct colours than this in 48×27 pixels: a logo, not a scene. */
private const val LOGO_MAX_COLOURS = 90

/** Keys of the live row as shown: favourites, then the 3 channels watched last that aren't favourites. */
private fun liveKeys(state: HomeState): List<String> {
    val favIds = state.favorites.map { it.id }.toSet()
    return (state.favorites + state.recentLive.filter { it.id !in favIds }.take(3)).map { "l:${it.id}" }
}
