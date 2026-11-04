package de.koeven.couchpotato.ui

import androidx.compose.foundation.border
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
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

private val GridGap = 14.dp

/**
 * "Mediathek" (design B): categories on the left with their number of titles,
 * the chosen category as a poster grid on the right. Everything comes from the
 * local catalogue – browsing never sends a request to the provider.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BrowseScreen(
    vm: HomeViewModel,
    kind: Char,
    onOpenSeries: (Long, String) -> Unit,
    onOpenMovie: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    var categories by remember { mutableStateOf<List<LiveCategory>?>(null) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var catError by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf(vm.library.browseCategoryId) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<BrowseContent>(BrowseContent.Loading) }
    var showHidden by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<BrowseMenu?>(null) }
    var editing by remember { mutableStateOf(false) }
    var jumpToContent by remember { mutableStateOf(false) }   // OK on a category: focus goes to its titles
    var movingId by remember { mutableStateOf<String?>(null) }   // category being moved with ▲ ▼
    var sortMode by remember { mutableStateOf(vm.library.browseSort) }
    val catListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val selectedFocus = remember { FocusRequester() }

    val hiddenSet = state.hiddenCategories
    val kidMode = state.profile.kid      // kids: only released categories, nothing to edit
    fun isHidden(c: LiveCategory) = "$kind:${c.id}" in hiddenSet
    val visibleCats = categories.orEmpty().filter { editing || showHidden || !isHidden(it) }
    val hiddenCount = categories.orEmpty().count { isHidden(it) }
    val favIds = state.favorites.map { it.id }.toSet()
    val unit = when (kind) {
        SearchEntry.KIND_MOVIE -> "Filme"
        SearchEntry.KIND_SERIES -> "Serien"
        else -> "Sender"
    }

    DisposableEffect(Unit) { onDispose { vm.library.applyCategoryChanges() } }

    /** Moves a category one place up/down among the visible ones and saves the order. */
    fun moveCategory(id: String, delta: Int) {
        val full = categories ?: return
        val vis = visibleCats.map { it.id }
        val vi = vis.indexOf(id)
        val targetId = vis.getOrNull(vi + delta) ?: return
        val list = full.toMutableList()
        val item = list.removeAt(list.indexOfFirst { it.id == id })
        val to = list.indexOfFirst { it.id == targetId }
        list.add(if (delta > 0) to + 1 else to, item)
        categories = list
        vm.library.setCategoryOrder(kind, list.map { it.id })
        scope.launch {
            val idx = vi + delta
            val visible = catListState.layoutInfo.visibleItemsInfo
            if (visible.isNotEmpty() && (idx <= visible.first().index || idx >= visible.last().index)) {
                catListState.animateScrollToItem((idx - 3).coerceAtLeast(0))
            }
        }
    }
    fun finishEditing() {
        editing = false
        vm.library.applyCategoryChanges()
    }

    LaunchedEffect(kind) {
        vm.library.browseKind = kind
        try {
            val cats = vm.library.allCategories(kind)
            counts = vm.library.categoryCounts(kind)
            categories = cats
            val firstVisible = cats.firstOrNull { "$kind:${it.id}" !in vm.state.value.hiddenCategories }
            if (selectedId == null || cats.none { it.id == selectedId }) selectedId = firstVisible?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            catError = e.message ?: "Kategorien konnten nicht geladen werden"
        }
    }

    // focusing a category shows it after a short pause, so scrolling through the list stays smooth
    LaunchedEffect(focusedId) {
        val id = focusedId ?: return@LaunchedEffect
        if (editing) return@LaunchedEffect
        delay(450)
        selectedId = id
    }

    LaunchedEffect(kind, selectedId, sortMode) {
        val id = selectedId ?: return@LaunchedEffect
        vm.library.browseCategoryId = id
        content = BrowseContent.Loading
        content = try {
            when (kind) {
                SearchEntry.KIND_MOVIE -> BrowseContent.Movies(vm.library.moviesIn(id))
                SearchEntry.KIND_SERIES -> BrowseContent.Series(vm.library.seriesIn(id))
                else -> BrowseContent.Live(vm.library.liveChannels(id))
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

    LaunchedEffect(jumpToContent, content) {
        if (!jumpToContent) return@LaunchedEffect
        val ready = when (val c = content) {
            is BrowseContent.Movies -> c.items.isNotEmpty()
            is BrowseContent.Series -> c.items.isNotEmpty()
            is BrowseContent.Live -> c.items.isNotEmpty()
            BrowseContent.Loading -> return@LaunchedEffect      // wait until the titles are there
            else -> false
        }
        if (ready) {
            delay(80)
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
        }
        jumpToContent = false
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            active = when (kind) {
                SearchEntry.KIND_MOVIE -> NavTab.Movies
                SearchEntry.KIND_SERIES -> NavTab.Series
                else -> NavTab.Live
            },
        )
        Spacer(Modifier.height(22.dp))

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // ---------------- categories ----------------
            Column(Modifier.width(250.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Kategorien",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (categories.orEmpty().isNotEmpty() && !kidMode) {
                        TabPill(if (editing) "Fertig" else "Bearbeiten", selected = editing) {
                            if (editing) finishEditing() else editing = true
                        }
                    }
                }
                if (editing) {
                    val cats = categories.orEmpty()
                    Row(Modifier.padding(top = 10.dp)) {
                        TabPill("Alle aus", false) {
                            vm.library.setCategoriesHidden(kind, cats.map { it.id }, hidden = true, refresh = false)
                        }
                        TabPill("Alle an", false) {
                            vm.library.setCategoriesHidden(kind, cats.map { it.id }, hidden = false, refresh = false)
                        }
                    }
                    Text(
                        "OK schaltet eine Kategorie an oder aus · ${cats.count { !isHidden(it) }} von ${cats.size} sichtbar",
                        style = MaterialTheme.typography.labelMedium,
                        color = CpColors.Accent,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (movingId != null) {
                    Text(
                        "▲ ▼ verschieben · OK oder Zurück = fertig",
                        style = MaterialTheme.typography.labelMedium,
                        color = CpColors.Accent,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                LazyColumn(
                    state = catListState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    when {
                        catError != null -> item { Text(catError ?: "", color = CpColors.Error) }
                        categories == null -> item { Text("Lade Kategorien …", color = CpColors.TextMuted) }
                    }
                    itemsIndexed(visibleCats, key = { _, c -> "$kind-${c.id}" }) { i, cat ->
                        val hidden = isHidden(cat)
                        val isSelected = cat.id == selectedId
                        val wantsFocus = isSelected || (selectedId == null && i == 0)
                        CategoryRow(
                            name = cat.name,
                            trailing = if (editing) (if (hidden) "aus" else "✓ an") else (counts[cat.id]?.toString() ?: ""),
                            trailingAccent = editing && !hidden,
                            selected = isSelected && !editing,
                            dimmed = hidden,
                            onClick = {
                                if (editing) vm.library.setCategoriesHidden(kind, listOf(cat.id), !hidden, refresh = false)
                                else {
                                    selectedId = cat.id
                                    jumpToContent = true
                                }
                            },
                            onLongClick = { if (!editing && !kidMode) menu = BrowseMenu.Category(cat, hidden) },
                            modifier = Modifier
                                .onFocusChanged { if (it.isFocused) focusedId = cat.id }
                                .then(if (wantsFocus) Modifier.focusRequester(selectedFocus) else Modifier)
                                .then(
                                    if (movingId != cat.id) Modifier
                                    else Modifier
                                        .border(3.dp, CpColors.Accent, RoundedCornerShape(10.dp))
                                        .onPreviewKeyEvent { e ->
                                            val finish = setOf(
                                                androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter,
                                                androidx.compose.ui.input.key.Key.NumPadEnter, androidx.compose.ui.input.key.Key.Back,
                                                androidx.compose.ui.input.key.Key.Escape,
                                            )
                                            when (e.key) {
                                                androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.DirectionDown -> {
                                                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                                                        moveCategory(cat.id, if (e.key == androidx.compose.ui.input.key.Key.DirectionUp) -1 else 1)
                                                    }
                                                    true
                                                }
                                                androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight -> true
                                                in finish -> {
                                                    // finish on release, so the row doesn't also see the click
                                                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) movingId = null
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                ),
                        )
                    }
                    if (!editing && !kidMode && categories != null && (hiddenCount > 0 || showHidden)) {
                        item(key = "toggle-hidden") {
                            Spacer(Modifier.height(8.dp))
                            CategoryRow(
                                name = if (showHidden) "Ausgeblendete verbergen" else "Ausgeblendete anzeigen",
                                trailing = if (showHidden) "" else hiddenCount.toString(),
                                trailingAccent = true,
                                selected = false,
                                dimmed = false,
                                accent = true,
                                onClick = { showHidden = !showHidden },
                                onLongClick = {},
                            )
                        }
                    }
                }
            }

            // ---------------- titles ----------------
            Column(Modifier.weight(1f)) {
                val selectedCat = categories.orEmpty().firstOrNull { it.id == selectedId }
                val total = when (val c = content) {
                    is BrowseContent.Movies -> c.items.size
                    is BrowseContent.Series -> c.items.size
                    is BrowseContent.Live -> c.items.size
                    else -> null
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        selectedCat?.name ?: "Mediathek",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (total != null) {
                        Text(
                            "  ·  $total $unit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CpColors.TextMuted,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    if (kind != SearchEntry.KIND_LIVE) {
                        Spacer(Modifier.weight(1f))
                        // OK switches the order: newest → A–Z → best rating
                        TabPill(
                            "Sortiert: " + when (sortMode) { 1 -> "A–Z"; 2 -> "Bewertung"; else -> "Neueste" },
                            selected = false,
                        ) {
                            sortMode = (sortMode + 1) % 3
                            vm.library.browseSort = sortMode
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val posterCols = 8
                    val liveCols = 5
                    val posterW = cellWidth(maxWidth, posterCols)
                    val liveW = cellWidth(maxWidth, liveCols)
                    when (val c = content) {
                        BrowseContent.Loading ->
                            if (selectedId != null) Text("Lade …", color = CpColors.TextMuted)
                        is BrowseContent.Error -> Text(c.message, color = CpColors.Error)
                        is BrowseContent.Movies -> if (c.items.isEmpty()) {
                            Text("Keine Filme in dieser Kategorie.", color = CpColors.TextMuted)
                        } else {
                            TitleGrid(posterCols) {
                                itemsIndexed(c.items, key = { _, m -> m.id }) { _, movie ->
                                    PosterCard(
                                        title = movie.name,
                                        image = movie.image,
                                        width = posterW,
                                        onClick = { onOpenMovie(movie.id, movie.name) },
                                    )
                                }
                            }
                        }
                        is BrowseContent.Series -> if (c.items.isEmpty()) {
                            Text("Keine Serien in dieser Kategorie.", color = CpColors.TextMuted)
                        } else {
                            TitleGrid(posterCols) {
                                itemsIndexed(c.items, key = { _, s -> s.id }) { _, series ->
                                    PosterCard(
                                        title = series.name,
                                        image = series.image,
                                        width = posterW,
                                        onClick = { onOpenSeries(series.id, series.name) },
                                    )
                                }
                            }
                        }
                        is BrowseContent.Live -> if (c.items.isEmpty()) {
                            Text("Keine Sender in dieser Kategorie.", color = CpColors.TextMuted)
                        } else {
                            TitleGrid(liveCols) {
                                itemsIndexed(c.items, key = { _, ch -> ch.id }) { i, ch ->
                                    ChannelCard(
                                        name = (if (ch.id in favIds) "★ " else "") + ch.name,
                                        logo = ch.logo,
                                        nowPlaying = state.nowPlaying[ch.id],
                                        width = liveW,
                                        onClick = { vm.live.play(context, c.items, i) },
                                        onLongClick = { menu = BrowseMenu.Channel(ch) },
                                    )
                                }
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
                    MenuAction("Wieder einblenden") { vm.library.setCategoryHidden(kind, m.category.id, hidden = false) }
                } else {
                    MenuAction("Kategorie ausblenden") {
                        vm.library.setCategoryHidden(kind, m.category.id, hidden = true)
                        Notice.show("„${m.category.name}“ ausgeblendet – über „Ausgeblendete anzeigen“ wieder einblenden", long = true)
                    }
                },
                MenuAction("Verschieben") { movingId = m.category.id },
                MenuAction("Kategorien bearbeiten") { editing = true },
            ) + if (vm.library.hasOwnCategoryOrder(kind)) listOf(
                MenuAction("Reihenfolge des Anbieters wiederherstellen") {
                    vm.library.setCategoryOrder(kind, null)
                    scope.launch { categories = vm.library.allCategories(kind) }
                },
            ) else emptyList(),
            onDismiss = { menu = null },
        )
        is BrowseMenu.Channel -> ContextMenu(
            title = m.channel.name,
            actions = listOf(
                MenuAction(if (m.channel.id in favIds) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") {
                    vm.live.toggleFavorite(m.channel)
                },
            ),
            onDismiss = { menu = null },
        )
        null -> Unit
    }
}

private fun cellWidth(total: Dp, columns: Int): Dp =
    (total - GridGap * (columns - 1) - 8.dp) / columns

@Composable
private fun TitleGrid(
    columns: Int,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(GridGap),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 48.dp, end = 8.dp, start = 2.dp),
        content = content,
    )
}

/** One line of the category list: name left, number (or on/off while editing) right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryRow(
    name: String,
    trailing: String,
    trailingAccent: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ListItemDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (dimmed) CpColors.TextMuted else CpColors.Text,
            selectedContainerColor = Color.White.copy(alpha = 0.14f),
            selectedContentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        headlineContent = {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (accent) CpColors.Accent else Color.Unspecified,
            )
        },
        trailingContent = {
            if (trailing.isNotEmpty()) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (trailingAccent) CpColors.Accent else Color.Unspecified,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
