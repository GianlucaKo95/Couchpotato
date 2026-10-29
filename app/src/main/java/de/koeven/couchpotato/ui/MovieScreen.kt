package de.koeven.couchpotato.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.koeven.couchpotato.data.MovieInfo
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.data.ProviderBlockedException
import de.koeven.couchpotato.data.RequestBudgetException
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.VodItem
import de.koeven.couchpotato.player.Playback
import kotlinx.coroutines.delay

/**
 * Info screen of a film (before it starts): cover, rating, plot, cast, director, trailer and
 * similar titles. Name, cover and rating come from the local catalogue right away; the rest is
 * fetched ONCE per film (through the request brake) and then kept on the stick.
 */
@Composable
fun MovieScreen(
    vm: HomeViewModel,
    movieId: Long,
    name: String,
    onOpenMovie: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val backdrop = LocalBackdrop.current
    val entry = remember(movieId) { vm.catalogEntry(SearchEntry.KIND_MOVIE, movieId) }
    var info by remember(movieId) { mutableStateOf<MovieInfo?>(null) }
    var note by remember(movieId) { mutableStateOf<String?>(null) }
    var similar by remember(movieId) { mutableStateOf<List<SearchEntry>>(emptyList()) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(movieId) {
        similar = vm.similarTo(SearchEntry.KIND_MOVIE, movieId)
        val r = vm.movieInfo(movieId)
        when {
            r == null -> note = "Details sind ausgeschaltet (Einstellungen → Katalog & Anfragen)."
            r.isSuccess -> info = r.getOrNull()
            else -> note = when (val e = r.exceptionOrNull()) {
                is ProviderBlockedException, is RequestBudgetException ->
                    "Details gerade nicht abrufbar – Couchpotato schont die Anfragen beim Anbieter. Später nochmal öffnen."
                else -> e?.message ?: "Details konnten nicht geladen werden."
            }
        }
    }

    val cover = info?.cover ?: entry?.image
    LaunchedEffect(cover) { cover?.let { backdrop?.focusImage(it) } }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }

    val title = entry?.name ?: name
    val key = "vod:$movieId"
    val progress = remember(state.tick, movieId) { vm.progressFor(key) }
    val item = remember(entry) {
        PlayItem.movie(VodItem(movieId, title, entry?.image, entry?.added ?: 0, entry?.ext ?: "mp4", entry?.categoryId))
    }
    val rating = info?.rating?.takeIf { it > 0f } ?: entry?.rating?.takeIf { it > 0f }
    val genre = info?.genre ?: entry?.genre
    val year = info?.year ?: entry?.year

    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar(active = NavTab.Movies) }
        item { Spacer(Modifier.height(24.dp)) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                Column(Modifier.weight(1f).padding(end = 36.dp)) {
                    val label = buildList {
                        add("FILM")
                        genre?.split(',', '/')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.uppercase()) }
                        year?.let { add(it) }
                        info?.durationMin?.takeIf { it > 0 }?.let { add(duration(it)) }
                    }.joinToString("  ·  ")
                    Text(label, style = MaterialTheme.typography.labelMedium, color = CpColors.Accent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rating != null || info?.country != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                rating?.let { "★ " + "%.1f".format(java.util.Locale.GERMANY, it) + " / 10" },
                                info?.country,
                            ).joinToString("   ·   "),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        info?.plot ?: note ?: "Lade Beschreibung …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (info?.plot == null && note != null) CpColors.TextMuted else CpColors.Text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    info?.cast?.let { Credits("Mit", it) }
                    info?.director?.let { Credits("Regie", it) }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val remain = progress?.let { p ->
                            if (p.durationMs > 0) ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1) else null
                        }
                        PrimaryPill(
                            if (progress != null) "▶  Fortsetzen" + (remain?.let { " · noch $it Min." } ?: "") else "▶  Abspielen",
                            Modifier.focusRequester(playFocus),
                        ) { Playback.start(context, listOf(item)) }
                        if (progress != null) {
                            SecondaryPill("Von vorne") {
                                vm.restartFromBeginning(key)
                                Playback.start(context, listOf(item))
                            }
                        }
                        info?.trailer?.let { yt ->
                            SecondaryPill("Trailer") {
                                val ok = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.isSuccess
                                if (!ok) Toast.makeText(context, "Keine App für YouTube-Trailer installiert", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .width(170.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CpColors.SurfaceHigh)
                ) {
                    AsyncImage(
                        model = cover,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (similar.isNotEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                Shelf("Ähnliche Filme") {
                    items(similar, key = { it.id }) { e ->
                        PosterCard(title = e.name, image = e.image, onClick = { onOpenMovie(e.id, e.name) })
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Credits(label: String, text: String) {
    Text(
        "$label: $text",
        style = MaterialTheme.typography.bodySmall,
        color = CpColors.TextMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun duration(min: Int): String =
    if (min >= 60) "${min / 60} Std. ${min % 60} Min." else "$min Min."
