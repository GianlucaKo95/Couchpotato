package de.koeven.couchpotato.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesItem
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface BrowseContent {
    data object Loading : BrowseContent
    data class Error(val message: String) : BrowseContent
    data class Movies(val items: List<VodItem>) : BrowseContent
    data class Series(val items: List<SeriesItem>) : BrowseContent
    data class Live(val items: List<LiveChannel>) : BrowseContent
}

private sealed interface BrowseMenu {
    data class Category(val category: LiveCategory, val hidden: Boolean) : BrowseMenu
    data class Channel(val channel: LiveChannel) : BrowseMenu
}

/**
 * "Mediathek": browse the provider's catalogue by category.
 * Focusing a category (briefly) loads it; long OK on a category hides/shows it everywhere.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(vm: HomeViewModel, onOpenSeries: (Long, String) -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    var kind by remember { mutableStateOf(vm.browseKind) }
    var categories by remember { mutableStateOf<List<LiveCategory>?>(null) }
    var catError by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf(vm.browseCategoryId) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<BrowseContent>(BrowseContent.Loading) }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<BrowseMenu?>(null) }
    var editing by remember { mutableStateOf(false) }
    val selectedFocus = remember { FocusRequester() }

    val hiddenSet = state.hiddenCategories
    fun isHidden(c: LiveCategory) = "$kind:${c.id}" in hiddenSet
    val visibleCats = categories.orEmpty().filter { editing || showHidden || !isHidden(it) }
    val hiddenCount = categories.orEmpty().count { isHidden(it) }
    val favIds = state.favorites.map { it.id }.toSet()

    // leaving the screen while editing: apply the changes (refill "Neue Filme/Serien")
    DisposableEffect(Unit) {
        onDispose { vm.applyCategoryChanges() }
    }
    fun finishEditing() {
        editing = false
        vm.applyCategoryChanges()
    }

    // load the category list of the current tab
    LaunchedEffect(kind) {
        vm.browseKind = kind
        categories = null
        catError = null
        try {
            val cats = vm.allCategories(kind)
            categories = cats
            val firstVisible = cats.firstOrNull { "$kind:${it.id}" !in vm.state.value.hiddenCategories }
            if (selectedId == null || cats.none { it.id == selectedId }) selectedId = firstVisible?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            catError = e.message ?: "Kategorien konnten nicht geladen werden"
        }
    }

    // focusing a category selects it after a short pause (no request for every category you scroll past)
    LaunchedEffect(focusedId) {
        val id = focusedId ?: return@LaunchedEffect
        delay(700)   // only load when you stay on a category (the provider limits requests)
        selectedId = id
    }

    // load the selected category
    LaunchedEffect(kind, selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        vm.browseCategoryId = id
        content = BrowseContent.Loading
        content = try {
            when (kind) {
                SearchEntry.KIND_MOVIE -> BrowseContent.Movies(vm.moviesIn(id))
                SearchEntry.KIND_SERIES -> BrowseContent.Series(vm.seriesIn(id))
                else -> BrowseContent.Live(vm.liveChannels(id))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BrowseContent.Error(e.message ?: "Konnte nicht geladen werden")
        }
    }

    LaunchedEffect(categories) {
        if (categories != null) {
            delay(150)
            runCatching { selectedFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 32.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Mediathek",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 20.dp),
            )
            for ((k, label) in listOf(
                SearchEntry.KIND_LIVE to "Live-TV",
                SearchEntry.KIND_MOVIE to "Filme",
                SearchEntry.KIND_SERIES to "Serien",
            )) {
                FilterChip(selected = kind == k, onClick = {
                    if (kind != k) {
                        kind = k
                        selectedId = null
                        focusedId = null
                        showHidden = false
                    }
                }) { Text(label) }
            }
            Spacer(Modifier.weight(1f))
            val cats = categories.orEmpty()
            if (editing) {
                Button(onClick = {
                    vm.setCategoriesHidden(kind, cats.map { it.id }, hidden = true, refresh = false)
                }) { Text("Alle ausblenden") }
                Button(onClick = {
                    vm.setCategoriesHidden(kind, cats.map { it.id }, hidden = false, refresh = false)
                }) { Text("Alle einblenden") }
                Button(onClick = { finishEditing() }) { Text("Fertig") }
            } else if (cats.isNotEmpty()) {
                Button(onClick = { editing = true }) { Text("Kategorien bearbeiten") }
            }
        }
        Text(
            if (editing) {
                val visible = categories.orEmpty().count { !isHidden(it) }
                "OK schaltet eine Kategorie an oder aus · $visible von ${categories.orEmpty().size} sichtbar · " +
                    "gilt auch für Neue Filme/Serien, Live-TV und Suche"
            } else {
                "Kategorien ein- oder ausblenden: „Kategorien bearbeiten“ oben rechts oder lange OK auf einer Kategorie."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (editing) CpColors.Accent else CpColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // ---- categories ----
            LazyColumn(
                modifier = Modifier.width(300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                when {
                    catError != null -> item { Text(catError ?: "", color = CpColors.Error) }
                    categories == null -> item { Text("Lade Kategorien …", color = CpColors.TextMuted) }
                }
                itemsIndexed(visibleCats, key = { _, c -> "$kind-${c.id}" }) { i, cat ->
                    val hidden = isHidden(cat)
                    val isSelected = cat.id == selectedId
                    val wantsFocus = isSelected || (selectedId == null && i == 0)
                    val trailing: (@Composable () -> Unit)? = if (editing) {
                        {
                            Text(
                                if (hidden) "aus" else "✓ an",
                                color = if (hidden) CpColors.TextMuted else CpColors.Accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else null
                    val supporting: (@Composable () -> Unit)? =
                        if (hidden) { { Text("ausgeblendet", color = CpColors.TextMuted) } } else null
                    ListItem(
                        selected = isSelected && !editing,
                        onClick = {
                            if (editing) vm.setCategoriesHidden(kind, listOf(cat.id), !hidden, refresh = false)
                            else selectedId = cat.id
                        },
                        onLongClick = { if (!editing) menu = BrowseMenu.Category(cat, hidden) },
                        trailingContent = trailing,
                        headlineContent = {
                            Text(
                                cat.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (hidden) CpColors.TextMuted else CpColors.Text,
                            )
                        },
                        supportingContent = supporting,
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) focusedId = cat.id }
                            .then(if (wantsFocus) Modifier.focusRequester(selectedFocus) else Modifier),
                    )
                }
                if (!editing && categories != null && (hiddenCount > 0 || showHidden)) {
                    item(key = "toggle-hidden") {
                        Spacer(Modifier.height(8.dp))
                        ListItem(
                            selected = false,
                            onClick = { showHidden = !showHidden },
                            headlineContent = {
                                Text(
                                    if (showHidden) "Ausgeblendete verbergen" else "Ausgeblendete anzeigen ($hiddenCount)",
                                    color = CpColors.Accent,
                                )
                            },
                        )
                    }
                }
            }

            // ---- items ----
            Box(Modifier.weight(1f)) {
                when (val c = content) {
                    BrowseContent.Loading ->
                        if (selectedId != null) Text("Lade …", color = CpColors.TextMuted) else Unit
                    is BrowseContent.Error -> Text(c.message, color = CpColors.Error)
                    is BrowseContent.Movies -> if (c.items.isEmpty()) {
                        Text("Keine Filme in dieser Kategorie.", color = CpColors.TextMuted)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(PosterWidth + 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, end = 8.dp),
                        ) {
                            itemsIndexed(c.items, key = { _, m -> m.id }) { _, movie ->
                                PosterCard(
                                    title = movie.name,
                                    image = movie.image,
                                    onClick = { Playback.start(context, listOf(PlayItem.movie(movie))) },
                                )
                            }
                        }
                    }
                    is BrowseContent.Series -> if (c.items.isEmpty()) {
                        Text("Keine Serien in dieser Kategorie.", color = CpColors.TextMuted)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(PosterWidth + 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, end = 8.dp),
                        ) {
                            itemsIndexed(c.items, key = { _, s -> s.id }) { _, series ->
                                PosterCard(
                                    title = series.name,
                                    image = series.image,
                                    onClick = { onOpenSeries(series.id, series.name) },
                                )
                            }
                        }
                    }
                    is BrowseContent.Live -> if (c.items.isEmpty()) {
                        Text("Keine Sender in dieser Kategorie.", color = CpColors.TextMuted)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(ChannelWidth + 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp, end = 8.dp),
                        ) {
                            itemsIndexed(c.items, key = { _, ch -> ch.id }) { i, ch ->
                                ChannelCard(
                                    name = (if (ch.id in favIds) "★ " else "") + ch.name,
                                    logo = ch.logo,
                                    nowPlaying = null,
                                    onClick = { vm.playLive(context, c.items, i) },
                                    onLongClick = { menu = BrowseMenu.Channel(ch) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val m = menu) {
        is BrowseMenu.Category -> ContextMenu(
            title = m.category.name,
            actions = listOf(
                if (m.hidden) {
                    MenuAction("Wieder einblenden") { vm.setCategoryHidden(kind, m.category.id, hidden = false) }
                } else {
                    MenuAction("Kategorie ausblenden") {
                        vm.setCategoryHidden(kind, m.category.id, hidden = true)
                        Toast.makeText(
                            context,
                            "„${m.category.name}“ ausgeblendet – über „Ausgeblendete anzeigen“ wieder einblenden",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
            ),
            onDismiss = { menu = null },
        )
        is BrowseMenu.Channel -> ContextMenu(
            title = m.channel.name,
            actions = listOf(
                MenuAction(if (m.channel.id in favIds) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") {
                    vm.toggleFavorite(m.channel)
                },
            ),
            onDismiss = { menu = null },
        )
        null -> Unit
    }
}
