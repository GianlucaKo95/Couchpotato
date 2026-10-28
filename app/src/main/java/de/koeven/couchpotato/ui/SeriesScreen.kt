package de.koeven.couchpotato.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import de.koeven.couchpotato.data.SeriesInfo
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesScreen(vm: HomeViewModel, seriesId: Long, name: String) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var info by remember(seriesId) { mutableStateOf<SeriesInfo?>(null) }
    var error by remember(seriesId) { mutableStateOf<String?>(null) }
    var season by remember(seriesId) { mutableIntStateOf(-1) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(seriesId) {
        try {
            val loaded = vm.loadSeries(seriesId, name)
            info = loaded
            val resume = vm.progressForSeries(seriesId)
            season = loaded.episodes.firstOrNull { it.id == resume?.item?.streamId }?.season
                ?: loaded.episodes.firstOrNull()?.season ?: 1
        } catch (e: Exception) {
            error = e.message ?: "Serie konnte nicht geladen werden"
        }
    }

    // re-read progress whenever we come back from the player (tick changes on resume)
    val resume = remember(state.tick, seriesId) { vm.progressForSeries(seriesId) }

    LaunchedEffect(info) {
        if (info != null) {
            delay(150)
            runCatching { focus.requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 40.dp)
    ) {
        Column(Modifier.width(240.dp)) {
            AsyncImage(
                model = info?.cover,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(220.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CpColors.SurfaceHigh),
            )
            Spacer(Modifier.height(16.dp))
            Text(info?.name ?: name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            info?.plot?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = CpColors.TextMuted,
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(40.dp))

        val loaded = info
        Column(Modifier.weight(1f)) {
            when {
                error != null -> Text(error ?: "", color = CpColors.Error)
                loaded == null -> Text("Lade Folgen …", color = CpColors.TextMuted)
                loaded.episodes.isEmpty() -> Text("Keine Folgen gefunden.", color = CpColors.TextMuted)
                else -> {
                    val seasons = remember(loaded) { loaded.episodes.map { it.season }.distinct() }
                    val episodes = loaded.episodes.filter { it.season == season }
                    val progressMap = remember(state.tick, loaded) {
                        loaded.episodes.associate { it.id to vm.progressFor("ep:${it.id}") }
                    }

                    if (resume != null) {
                        Button(
                            onClick = { Playback.start(context, Playback.episodePlaylist(loaded, resume.item.streamId)) },
                            modifier = Modifier.focusRequester(focus),
                        ) {
                            val label = if (resume.upNext) "Weiter mit" else "Fortsetzen"
                            Text("$label: ${resume.item.subtitle ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    if (seasons.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 6.dp),
                        ) {
                            items(seasons) { s ->
                                FilterChip(selected = s == season, onClick = { season = s }) {
                                    Text("Staffel $s")
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
                            val p = progressMap[ep.id]
                            val minutes = ep.durationSec / 60
                            val meta = buildList {
                                if (minutes > 0) add("$minutes Min.")
                                if (p != null && !p.upNext && p.positionMs > 0) add("bei ${p.positionMs / 60_000} Min.")
                                if (p?.upNext == true) add("als Nächstes")
                            }.joinToString(" · ")
                            val supporting: (@Composable () -> Unit)? =
                                if (meta.isNotEmpty()) { { Text(meta) } } else null
                            ListItem(
                                selected = false,
                                onClick = { Playback.start(context, Playback.episodePlaylist(loaded, ep.id)) },
                                headlineContent = {
                                    Text("${ep.number}. ${ep.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = supporting,
                                modifier = if (i == 0 && resume == null) Modifier.focusRequester(focus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
