package de.koeven.couchpotato.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.koeven.couchpotato.data.Episode
import de.koeven.couchpotato.data.ProgressEntry
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SeriesInfo
import de.koeven.couchpotato.data.TitleDetails
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay

/** Serien-Detail (design B): info and buttons on top, seasons as pills, episodes as 16:9 tiles. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeriesScreen(vm: HomeViewModel, seriesId: Long, name: String) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val backdrop = LocalBackdrop.current
    var info by remember(seriesId) { mutableStateOf<SeriesInfo?>(null) }
    var error by remember(seriesId) { mutableStateOf<String?>(null) }
    var season by remember(seriesId) { mutableIntStateOf(-1) }
    var details by remember(seriesId) { mutableStateOf<TitleDetails?>(null) }
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

    // FSK, German text, cast, rating: TMDB (costs the IPTV provider nothing)
    var detailsDone by remember(seriesId) { mutableStateOf(false) }
    var also by remember(seriesId) { mutableStateOf<List<de.koeven.couchpotato.data.AppEntry>>(emptyList()) }
    LaunchedEffect(details) { also = vm.alsoOn(details) }
    LaunchedEffect(seriesId) {
        details = runCatching { vm.titleDetails(SearchEntry.KIND_SERIES, seriesId, name)?.getOrNull() }.getOrNull()
        detailsDone = true
    }

    // kids profile: FSK check before anything plays
    var unlockTick by remember { mutableStateOf(0) }
    var askPin by remember { mutableStateOf(false) }
    val gate = remember(details, detailsDone, unlockTick, state.profile) {
        vm.gate(SearchEntry.KIND_SERIES, seriesId, details?.fsk, detailsDone)
    }
    fun guardedPlay(action: () -> Unit) {
        when (gate) {
            HomeViewModel.Gate.Allowed -> action()
            is HomeViewModel.Gate.Blocked -> askPin = true
            HomeViewModel.Gate.Checking -> Unit
        }
    }
    if (askPin) {
        PinDialog(
            title = "Für dieses Profil freigeben?",
            hint = "„$name“ ist für dieses Kinderprofil gesperrt. Mit der Eltern-PIN wird die Serie dauerhaft freigegeben.",
            onDismiss = { askPin = false },
        ) { pin ->
            if (vm.checkPin(pin)) {
                vm.unlockTitle(SearchEntry.KIND_SERIES, seriesId)
                askPin = false
                unlockTick++
                true
            } else false
        }
    }

    // the series cover colours the background
    LaunchedEffect(info?.cover) { info?.cover?.let { backdrop?.focusImage(it) } }

    // re-read progress whenever we come back from the player (tick changes on resume)
    val resume = remember(state.tick, seriesId) { vm.progressForSeries(seriesId) }

    LaunchedEffect(info) {
        if (info != null) {
            delay(150)
            runCatching { focus.requestFocus() }
        }
    }

    val loaded = info
    val seasons = remember(loaded) { loaded?.episodes?.map { it.season }?.distinct().orEmpty() }
    val watchedSet = remember(state.tick, loaded) { vm.watchedEpisodes(seriesId) }
    var menuEp by remember { mutableStateOf<Episode?>(null) }
    val progressMap = remember(state.tick, loaded) {
        loaded?.episodes?.associate { it.id to vm.progressFor("ep:${it.id}") }.orEmpty()
    }
    val resumeEp = loaded?.episodes?.firstOrNull { it.id == resume?.item?.streamId }
    val episodeWidth = ContentWidth

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item { ScreenTop(NavTab.Series, "Serien") }
        item { Spacer(Modifier.height(26.dp)) }

        // ---------------- header ----------------
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                Column(Modifier.weight(1f).padding(end = 40.dp)) {
                    val label = buildList {
                        add("SERIE")
                        (details?.genres?.firstOrNull() ?: loaded?.genre?.substringBefore(',')?.substringBefore('/')?.trim())
                            ?.takeIf { it.isNotEmpty() }?.let { add(it.uppercase()) }
                        (details?.year ?: loaded?.year)?.let { add(it) }
                        if (seasons.isNotEmpty()) add(if (seasons.size == 1) "1 STAFFEL" else "${seasons.size} STAFFELN")
                    }.joinToString("  ·  ")
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = CpColors.Accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        loaded?.name ?: name,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FactsLine(
                        fsk = details?.fsk,
                        rating = details?.rating?.takeIf { it > 0f } ?: loaded?.rating?.takeIf { it > 0f },
                        votes = details?.votes ?: 0,
                        country = details?.country,
                    )
                    (details?.plot ?: loaded?.plot)?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CpColors.TextMuted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (also.isNotEmpty()) {
                        Row(
                            Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Auch bei", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted)
                            also.forEach { a -> TabPill(a.label, false) { vm.openInStreamingApp(context, a, loaded?.name ?: name) } }
                        }
                    }
                    (details?.cast?.takeIf { it.isNotEmpty() }?.take(5)?.joinToString(", ") ?: loaded?.cast)?.let {
                        Text(
                            "Mit: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = CpColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    when {
                        error != null -> Text(error ?: "", color = CpColors.Error)
                        loaded == null -> Text("Lade Folgen …", color = CpColors.TextMuted)
                        loaded.episodes.isEmpty() -> Text("Keine Folgen gefunden.", color = CpColors.TextMuted)
                        else -> Column {
                        if (state.profile.kid && gate is HomeViewModel.Gate.Blocked) {
                            KidLockedBubble("Diese Serie ist noch nichts für dich. Frag Mama oder Papa!")
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val first = loaded.episodes.first()
                            val kid = state.profile.kid
                            val primary = if (resume != null && resumeEp != null) {
                                val code = "S${resumeEp.season} E${resumeEp.number}"
                                if (resume.upNext) "▶  Weiter mit $code"
                                else "▶  Fortsetzen · $code" + remaining(resume)?.let { " · noch $it Min." }.orEmpty()
                            } else {
                                "▶  Abspielen · S${first.season} E${first.number}"
                            }
                            val label = when (val g = gate) {
                                HomeViewModel.Gate.Allowed -> primary
                                HomeViewModel.Gate.Checking -> "Prüfe Freigabe …"
                                is HomeViewModel.Gate.Blocked -> if (kid) "Eltern-PIN eingeben" else "🔒  Gesperrt · ${g.reason}"
                            }
                            MainAction(
                                if (kid && gate == HomeViewModel.Gate.Allowed && resume == null) "▶  Los geht’s!" else label,
                                Modifier.focusRequester(focus),
                            ) {
                                guardedPlay {
                                    val start = resumeEp?.id ?: first.id
                                    Playback.start(context, Playback.episodePlaylist(loaded, start))
                                }
                            }
                            if (resumeEp != null && resumeEp.id != first.id && gate == HomeViewModel.Gate.Allowed) {
                                SideAction("Von vorne") {
                                    Playback.start(context, Playback.episodePlaylist(loaded, first.id))
                                }
                            }
                        }
                        }
                    }
                }
                AsyncImage(
                    model = loaded?.cover,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(150.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CpColors.SurfaceHigh),
                )
            }
        }

        if (loaded != null && loaded.episodes.isNotEmpty()) {
            // ---------------- seasons ----------------
            if (seasons.size > 1) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 22.dp),
                    ) {
                        items(seasons) { s -> TabPill("Staffel $s", s == season) { season = s } }
                    }
                }
            }

            // ---------------- episodes ----------------
            item {
                val episodes = loaded.episodes.filter { it.season == season }
                val rowState = rememberLazyListState()
                LaunchedEffect(season) {
                    val idx = episodes.indexOfFirst { it.id == resumeEp?.id }
                    if (idx > 0) rowState.scrollToItem(idx)
                }
                Column(Modifier.padding(top = 18.dp)) {
                    Text(
                        "Folgen · Staffel $season",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = ScreenPadding),
                    )
                    LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(episodes, key = { _, e -> e.id }) { _, ep ->
                            EpisodeCard(
                                episode = ep,
                                fallbackImage = loaded.cover,
                                progress = progressMap[ep.id],
                                seen = ep.id in watchedSet,
                                width = episodeWidth,
                                onClick = { guardedPlay { Playback.start(context, Playback.episodePlaylist(loaded, ep.id)) } },
                                onLongClick = { menuEp = ep },
                            )
                        }
                    }
                }
            }
        }
        if (details?.fromTmdb == true) item { TmdbNote() }
    }

    // OK lang drücken auf einer Folge: als gesehen / ungesehen markieren – einzeln oder die ganze Staffel
    val ep = menuEp
    if (ep != null && loaded != null) {
        val seen = ep.id in watchedSet || progressMap[ep.id]?.watched() == true
        val seasonIds = loaded.episodes.filter { it.season == ep.season }.map { it.id }
        val seasonSeen = seasonIds.all { it in watchedSet }
        ContextMenu(
            title = "${ep.number}. ${ep.title}",
            actions = listOf(
                if (seen) MenuAction("Als ungesehen markieren") { vm.markWatched(loaded, listOf(ep.id), false) }
                else MenuAction("Als gesehen markieren") { vm.markWatched(loaded, listOf(ep.id), true) },
                MenuAction("Bis hier alles als gesehen markieren") {
                    val upTo = loaded.episodes.indexOfFirst { it.id == ep.id }
                    vm.markWatched(loaded, loaded.episodes.take(upTo + 1).map { it.id }, true)
                },
                if (seasonSeen) MenuAction("Staffel ${ep.season} als ungesehen markieren") { vm.markWatched(loaded, seasonIds, false) }
                else MenuAction("Staffel ${ep.season} als gesehen markieren") { vm.markWatched(loaded, seasonIds, true) },
            ),
            onDismiss = { menuEp = null },
        )
    }
}

private fun remaining(p: ProgressEntry): Long? =
    if (p.durationMs > 0 && p.positionMs > 0) ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1) else null

private fun ProgressEntry.watched(): Boolean = !upNext && fraction >= 0.92f

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: Episode,
    fallbackImage: String?,
    progress: ProgressEntry?,
    seen: Boolean,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val watched = seen && progress?.upNext != true || progress?.watched() == true
    val image = episode.image ?: fallbackImage
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .width(width)
                .aspectRatio(16f / 9f)
                .glowFrom(image),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh)) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (watched) 0.45f else 1f),
                    )
                }
                Text(
                    "E${episode.number}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = CpColors.Text,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                if (watched) {
                    Text(
                        "✓ gesehen",
                        style = MaterialTheme.typography.labelSmall,
                        color = CpColors.Text,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    )
                }
                if (progress != null && !progress.upNext && progress.fraction > 0f) {
                    ProgressBar(
                        fraction = progress.fraction,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text(
            "${episode.number}. ${episode.title}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (watched) CpColors.TextMuted else CpColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        val minutes = episode.durationSec / 60
        val meta = when {
            progress?.upNext == true -> "als Nächstes"
            progress != null && !watched && remaining(progress) != null -> "noch ${remaining(progress)} Min."
            minutes > 0 -> "$minutes Min."
            else -> " "
        }
        Text(
            meta,
            style = MaterialTheme.typography.labelSmall,
            color = if (progress?.upNext == true) CpColors.Accent else CpColors.TextMuted,
            maxLines = 1,
        )
    }
}
