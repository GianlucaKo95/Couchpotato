package de.koeven.couchpotato.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Start screen of a kids profile (design "Kinderprofil"). */
@Composable
fun KidHomeScreen(
    vm: HomeViewModel,
    homeTick: Int,
    onOpenSeries: (SeriesItem) -> Unit,
    onOpenMovie: (Long, String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = LocalNav.current
    val first = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<ContinueItem?>(null) }

    LaunchedEffect(homeTick) {
        delay(150)
        runCatching { first.requestFocus() }
    }

    val wide = kidTileWidth(3.3f)
    val poster = kidTileWidth(6.4f)
    val app = kidTileWidth(4.4f)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            KidHeader(state.profile, "Hallo ${state.profile.name}!", "Was möchtest du heute schauen?")
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = ScreenPadding, end = ScreenPadding, top = 18.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                KidBigButton("Filme", R.drawable.ic_kid_film, KidColors.Orange, Modifier.weight(1f).focusRequester(first)) {
                    nav?.browse?.invoke(SearchEntry.KIND_MOVIE)
                }
                KidBigButton("Serien", R.drawable.ic_kid_series, KidColors.Pink, Modifier.weight(1f)) {
                    nav?.browse?.invoke(SearchEntry.KIND_SERIES)
                }
                KidBigButton("Fernsehen", R.drawable.ic_kid_tv, KidColors.Green, Modifier.weight(1f)) {
                    nav?.browse?.invoke(SearchEntry.KIND_LIVE)
                }
                KidBigButton("Suchen", R.drawable.ic_search, KidColors.Purple, Modifier.weight(1f)) {
                    nav?.search?.invoke()
                }
            }
        }

        if (state.kidCategories.isEmpty()) {
            item {
                Text(
                    "Hier ist noch nichts freigegeben. Mama oder Papa können unter „Eltern“ Kategorien für dich auswählen.",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = KidColors.Soft,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 20.dp),
                )
            }
        }

        val cw = state.continueWatching.filterIsInstance<ContinueItem.Iptv>()
        if (cw.isNotEmpty()) {
            item {
                Spacer(Modifier.height(18.dp))
                KidRowTitle("Weiterschauen")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(cw, key = { it.key }) { c ->
                        val e = c.entry
                        KidWideCard(
                            title = e.item.title,
                            image = e.item.image,
                            width = wide,
                            fraction = if (e.upNext) 0f else e.fraction,
                            onClick = { scope.launch { Playback.start(context, vm.playlistFor(e)) } },
                            onLongClick = { menuFor = c },
                        )
                    }
                }
            }
        }

        if (state.recommended.isNotEmpty()) {
            item {
                Spacer(Modifier.height(18.dp))
                KidRowTitle("Für dich")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.recommended, key = { "${it.kind}${it.id}" }) { e ->
                        KidPosterCard(e.name, e.image, poster, onClick = {
                            if (e.kind == SearchEntry.KIND_SERIES) onOpenSeries(SeriesItem(e.id, e.name, e.image, e.added, e.categoryId))
                            else onOpenMovie(e.id, e.name)
                        })
                    }
                }
            }
        }

        if (state.favorites.isNotEmpty()) {
            item {
                Spacer(Modifier.height(18.dp))
                KidRowTitle("Fernsehen")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(state.favorites, key = { _, c -> c.id }) { i, ch ->
                        KidWideCard(
                            title = ch.name,
                            image = ch.logo,
                            width = app,
                            fraction = 0f,
                            onClick = { vm.playLive(context, state.favorites, i) },
                        )
                    }
                }
            }
        }

        if (state.apps.isNotEmpty()) {
            item {
                Spacer(Modifier.height(18.dp))
                KidRowTitle("Deine Apps")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.apps, key = { it.packageName }) { a ->
                        KidWideCard(
                            title = a.label,
                            image = null,
                            bitmap = a.banner ?: a.icon,
                            width = app,
                            fraction = 0f,
                            onClick = { vm.launchApp(a.packageName) },
                        )
                    }
                }
                Text(
                    "Nur Apps, die Mama oder Papa freigeschaltet haben.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = KidColors.Soft,
                    modifier = Modifier.padding(start = ScreenPadding, top = 8.dp),
                )
            }
        }
    }

    menuFor?.let { c ->
        ContextMenu(
            title = (c as? ContinueItem.Iptv)?.entry?.item?.title ?: "",
            actions = listOf(MenuAction("Aus „Weiterschauen“ entfernen") { vm.removeFromContinue(c) }),
            onDismiss = { menuFor = null },
        )
    }
}

/** Kids' Mediathek: category bubbles on top, big covers below. */
@Composable
fun KidBrowseScreen(
    vm: HomeViewModel,
    kind: Char,
    onOpenSeries: (Long, String) -> Unit,
    onOpenMovie: (Long, String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var categories by remember { mutableStateOf<List<LiveCategory>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }    // null = alle
    var titles by remember { mutableStateOf<List<SearchEntry>?>(null) }
    val first = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var jumpDown by remember { mutableStateOf(false) }      // OK on a bubble: focus goes to the covers
    val (title, sub) = when (kind) {
        SearchEntry.KIND_MOVIE -> "Filme" to "Such dir einen Film aus!"
        SearchEntry.KIND_SERIES -> "Serien" to "Welche Serie möchtest du schauen?"
        else -> "Fernsehen" to "Was läuft gerade?"
    }

    LaunchedEffect(kind) {
        categories = runCatching { vm.visibleCategories(kind) }.getOrDefault(emptyList())
        delay(150)
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(kind, selected, state.hiddenCategories) {
        titles = null
        titles = runCatching { vm.visibleTitles(kind, selected) }.getOrDefault(emptyList())
    }
    LaunchedEffect(jumpDown, titles) {
        if (!jumpDown || titles == null) return@LaunchedEffect
        if (titles?.isNotEmpty() == true) {
            delay(80)
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        }
        jumpDown = false
    }

    Column(Modifier.fillMaxSize()) {
        KidHeader(state.profile, title, sub, avatar = 52.dp)
        LazyRow(
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { KidChip("Alle", selected == null, Modifier.focusRequester(first)) { selected = null; jumpDown = true } }
            items(categories, key = { it.id }) { c ->
                KidChip(c.name, selected == c.id) { selected = c.id; jumpDown = true }
            }
        }
        val list = titles
        Box(Modifier.fillMaxSize().padding(top = 10.dp)) {
            when {
                categories.isEmpty() -> Text(
                    "Hier ist noch nichts freigegeben.",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = KidColors.Soft,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
                list == null -> Unit
                list.isEmpty() -> Text(
                    "Hier ist gerade nichts.",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = KidColors.Soft,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
                kind == SearchEntry.KIND_LIVE -> {
                    val channels = list.map { LiveChannel(it.id, it.name, it.image, it.categoryId) }
                    val w = kidTileWidth(4f, 18.dp)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                            KidWideCard(ch.name, ch.logo, w, 0f, onClick = { vm.playLive(context, channels, i) })
                        }
                    }
                }
                else -> {
                    val w = kidTileWidth(6f, 18.dp)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(list, key = { it.id }) { e ->
                            KidPosterCard(e.name, e.image, w, onClick = {
                                if (kind == SearchEntry.KIND_SERIES) onOpenSeries(e.id, e.name) else onOpenMovie(e.id, e.name)
                            })
                        }
                    }
                }
            }
        }
    }
}
