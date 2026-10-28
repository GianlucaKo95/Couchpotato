package de.koeven.couchpotato.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(vm: HomeViewModel, onOpenSeries: (Long, String) -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val results by vm.search.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val favIds = state.favorites.map { it.id }.toSet()

    LaunchedEffect(Unit) {
        vm.prepareSearch()
        // only jump into the text field (and pop up the keyboard) for a fresh search
        if (vm.searchQuery.value.isBlank()) {
            delay(150)
            runCatching { fieldFocus.requestFocus() }
        }
    }

    // search while typing (short pause so the stick isn't busy on every key)
    LaunchedEffect(query) {
        delay(300)
        vm.runSearch(query)
    }

    val liveChannels = results.live.map { LiveChannel(it.id, it.name, it.image, null) }
    val showResults = query.trim().length >= 2

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 36.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding)) {
                Text("Suchen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                M3Theme(
                    colorScheme = m3DarkColorScheme(
                        primary = CpColors.Accent,
                        surface = CpColors.Surface,
                        onSurface = CpColors.Text,
                        background = CpColors.Background,
                    )
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        label = { M3Text("Film, Serie oder Sender") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .width(640.dp)
                            .focusRequester(fieldFocus),
                    )
                }
                Text(
                    state.indexStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = CpColors.TextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (showResults) {
            if (results.movies.isNotEmpty()) {
                item(key = "movies") {
                    Shelf("Filme (${results.movies.size})") {
                        items(results.movies, key = { "m${it.id}" }) { e ->
                            PosterCard(
                                title = e.name,
                                image = e.image,
                                onClick = {
                                    Playback.start(context, listOf(PlayItem.movie(VodItem(e.id, e.name, e.image, 0, e.ext))))
                                },
                            )
                        }
                    }
                }
            }
            if (results.series.isNotEmpty()) {
                item(key = "series") {
                    Shelf("Serien (${results.series.size})") {
                        items(results.series, key = { "s${it.id}" }) { e ->
                            PosterCard(title = e.name, image = e.image, onClick = { onOpenSeries(e.id, e.name) })
                        }
                    }
                }
            }
            if (results.live.isNotEmpty()) {
                item(key = "live") {
                    Shelf("Live-TV (${results.live.size}) · lange OK = Favorit") {
                        itemsIndexed(liveChannels, key = { _, ch -> "l${ch.id}" }) { i, ch ->
                            ChannelCard(
                                name = ch.name,
                                logo = ch.logo,
                                nowPlaying = null,
                                onClick = { vm.playLive(context, liveChannels, i) },
                                onLongClick = {
                                    val wasFav = ch.id in favIds
                                    vm.toggleFavorite(ch)
                                    Toast.makeText(
                                        context,
                                        if (wasFav) "${ch.name} aus Favoriten entfernt" else "${ch.name} zu Favoriten hinzugefügt",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                            )
                        }
                    }
                }
            }
            if (results.isEmpty && results.query == query && state.configured) {
                item {
                    Text(
                        "Keine Treffer bei deinem IPTV-Anbieter.",
                        color = CpColors.TextMuted,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
            }

            item(key = "apps") {
                Shelf("„${query.trim()}“ in anderen Apps suchen") {
                    item(key = "global") {
                        TileCard(
                            label = "Fire TV\nGesamtsuche",
                            onClick = { vm.openGlobalSearch(context, query.trim()) },
                        )
                    }
                    items(state.searchApps, key = { it.app.packageName }) { sa ->
                        AppCard(
                            app = sa.app,
                            onClick = { vm.searchInApp(context, sa, query.trim()) },
                            onLongClick = { vm.searchInApp(context, sa, query.trim()) },
                        )
                    }
                }
            }
        }
    }
}
