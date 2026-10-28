package de.koeven.couchpotato.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import kotlinx.coroutines.delay

/**
 * "Sender verwalten": categories on the left, channels on the right.
 * OK toggles a favourite, long OK plays the channel right away.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveScreen(vm: HomeViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var categories by remember { mutableStateOf<List<LiveCategory>?>(null) }
    var selected by remember { mutableStateOf<LiveCategory?>(null) }
    var channels by remember { mutableStateOf<List<LiveChannel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val favIds = state.favorites.map { it.id }.toSet()

    LaunchedEffect(Unit) {
        try {
            val cats = vm.liveCategories()
            categories = cats
            selected = cats.firstOrNull()
        } catch (e: Exception) {
            error = e.message ?: "Live-Kategorien konnten nicht geladen werden"
        }
    }

    LaunchedEffect(selected) {
        val cat = selected ?: return@LaunchedEffect
        channels = null
        channels = try {
            vm.liveChannels(cat.id)
        } catch (e: Exception) {
            error = e.message
            emptyList()
        }
    }

    LaunchedEffect(categories != null) {
        if (categories != null) {
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 36.dp)
    ) {
        Text("Live-TV – Sender verwalten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "OK: Favorit an/aus · lange OK: sofort ansehen · ${state.favorites.size} Favoriten",
            style = MaterialTheme.typography.bodySmall,
            color = CpColors.TextMuted,
        )
        Spacer(Modifier.height(20.dp))

        error?.let {
            Text(it, color = CpColors.Error)
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LazyColumn(
                modifier = Modifier.width(300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                itemsIndexed(categories ?: emptyList(), key = { _, c -> c.id }) { i, cat ->
                    ListItem(
                        selected = cat.id == selected?.id,
                        onClick = { selected = cat },
                        headlineContent = { Text(cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                val list = channels
                when {
                    categories == null && error == null -> Text("Lade Kategorien …", color = CpColors.TextMuted)
                    list == null -> Text("Lade Sender …", color = CpColors.TextMuted)
                    list.isEmpty() -> Text("Keine Sender in dieser Kategorie.", color = CpColors.TextMuted)
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        itemsIndexed(list, key = { _, ch -> ch.id }) { i, ch ->
                            val fav = ch.id in favIds
                            ListItem(
                                selected = false,
                                onClick = { vm.toggleFavorite(ch) },
                                onLongClick = { vm.playLive(context, list, i) },
                                headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = {
                                    Text(
                                        if (fav) "★ Favorit" else "☆",
                                        color = if (fav) CpColors.Accent else CpColors.TextMuted,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
