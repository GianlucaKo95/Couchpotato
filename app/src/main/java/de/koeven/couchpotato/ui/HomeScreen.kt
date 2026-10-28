package de.koeven.couchpotato.ui

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.AppsRepository
import de.koeven.couchpotato.data.PlayItem
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
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }

    // Which row gets the initial focus: "Weiterschauen" if there is something, otherwise the apps.
    val focusContinue = state.continueWatching.isNotEmpty()
    val hasFocusTarget = focusContinue || state.apps.isNotEmpty()

    // keep "Jetzt läuft" fresh while the home screen is open
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            vm.refreshEpg(force = false)
        }
    }

    LaunchedEffect(homeTick, hasFocusTarget) {
        if (!hasFocusTarget) return@LaunchedEffect
        listState.scrollToItem(0)
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_mark),
                    contentDescription = "Couchpotato",
                    modifier = Modifier.height(76.dp),
                )
                if (state.loading) {
                    Text(
                        "  · aktualisiere …",
                        style = MaterialTheme.typography.bodySmall,
                        color = CpColors.TextMuted,
                    )
                }
                Spacer(Modifier.weight(1f))
                Clock()
                Spacer(Modifier.width(24.dp))
                Button(onClick = onOpenSearch) { Text("Suchen") }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onOpenSettings) { Text("Einstellungen") }
            }
        }

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

        if (state.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                Shelf("Weiterschauen") {
                    itemsIndexed(state.continueWatching, key = { _, c -> c.key }) { i, item ->
                        val focusMod = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier
                        val onLong = {
                            vm.removeFromContinue(item)
                            Toast.makeText(context, "Aus „Weiterschauen“ entfernt", Toast.LENGTH_SHORT).show()
                        }
                        when (item) {
                            is ContinueItem.Iptv -> {
                                val e = item.entry
                                ContinueCard(
                                    title = e.item.title,
                                    subtitle = e.item.subtitle,
                                    image = e.item.image,
                                    fraction = if (e.upNext) 0f else e.fraction,
                                    badge = if (e.upNext) "Nächste Folge · IPTV" else "IPTV",
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
                        }
                    }
                }
            }
        }

        if (state.configured) {
            item(key = "live") {
                Shelf("Live-TV – Favoriten") {
                    itemsIndexed(state.favorites, key = { _, ch -> ch.id }) { i, ch ->
                        ChannelCard(
                            name = ch.name,
                            logo = ch.logo,
                            nowPlaying = state.nowPlaying[ch.id],
                            onClick = { vm.playLive(context, state.favorites, i) },
                            onLongClick = {
                                vm.moveFavoriteLeft(ch)
                                Toast.makeText(context, "${ch.name} nach vorne verschoben", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    item(key = "manage-live") {
                        TileCard(
                            label = if (state.favorites.isEmpty()) "Sender\nauswählen" else "Sender\nverwalten",
                            onClick = onOpenLive,
                            width = 208.dp,
                        )
                    }
                }
            }
        }

        if (state.apps.isNotEmpty()) {
            item(key = "apps") {
                Shelf("Apps") {
                    itemsIndexed(state.apps, key = { _, a -> a.packageName }) { i, app ->
                        AppCard(
                            app = app,
                            onClick = { vm.launchApp(app.packageName) },
                            onLongClick = {
                                vm.hideApp(app.packageName)
                                Toast.makeText(
                                    context,
                                    "${app.label} ausgeblendet (Einstellungen → zurücksetzen)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = if (i == 0 && !focusContinue) Modifier.focusRequester(firstFocus) else Modifier,
                        )
                    }
                    item(key = "system-settings") {
                        TileCard("Fire TV\nEinstellungen", onClick = { AppsRepository.openSystemSettings(context) })
                    }
                }
            }
        }

        if (state.movies.isNotEmpty()) {
            item(key = "movies") {
                Shelf("Neue Filme") {
                    items(state.movies, key = { it.id }) { movie ->
                        PosterCard(
                            title = movie.name,
                            image = movie.image,
                            onClick = { Playback.start(context, listOf(PlayItem.movie(movie))) },
                        )
                    }
                }
            }
        }

        if (state.series.isNotEmpty()) {
            item(key = "series") {
                Shelf("Neue Serien") {
                    items(state.series, key = { it.id }) { series ->
                        PosterCard(
                            title = series.name,
                            image = series.image,
                            onClick = { onOpenSeries(series) },
                        )
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
