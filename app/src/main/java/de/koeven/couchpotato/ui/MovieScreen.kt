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
import de.koeven.couchpotato.data.TitleDetails
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
    var info by remember(movieId) { mutableStateOf<TitleDetails?>(null) }
    var note by remember(movieId) { mutableStateOf<String?>(null) }
    var similar by remember(movieId) { mutableStateOf<List<SearchEntry>>(emptyList()) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(movieId) {
        similar = vm.similarTo(SearchEntry.KIND_MOVIE, movieId)
        val r = vm.titleDetails(SearchEntry.KIND_MOVIE, movieId, name)
        when {
            r == null -> note = if (vm.tmdb.enabled) "Keine Details zu diesem Film gefunden."
                else "Details sind ausgeschaltet (Einstellungen → Katalog & Anfragen)."
            r.isSuccess -> info = r.getOrNull()
            else -> note = when (val e = r.exceptionOrNull()) {
                is ProviderBlockedException, is RequestBudgetException ->
                    "Details gerade nicht abrufbar – Couchpotato schont die Anfragen beim Anbieter. Später nochmal öffnen."
                else -> e?.message ?: "Details konnten nicht geladen werden."
            }
        }
    }

    val cover = entry?.image ?: info?.poster
    LaunchedEffect(cover) { cover?.let { backdrop?.focusImage(it) } }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { playFocus.requestFocus() }
    }

    val title = entry?.name ?: name
    var also by remember(movieId) { mutableStateOf<List<de.koeven.couchpotato.data.AppEntry>>(emptyList()) }
    LaunchedEffect(info) { also = vm.alsoOn(info) }
    var unlockTick by remember { mutableStateOf(0) }
    var askPin by remember { mutableStateOf(false) }
    val gate = remember(info, note, unlockTick, state.profile) {
        vm.gate(SearchEntry.KIND_MOVIE, movieId, info?.fsk, detailsLoaded = info != null || note != null)
    }
    val key = "vod:$movieId"
    val progress = remember(state.tick, movieId) { vm.progressFor(key) }
    val item = remember(entry) {
        PlayItem.movie(VodItem(movieId, title, entry?.image, entry?.added ?: 0, entry?.ext ?: "mp4", entry?.categoryId))
    }
    val rating = info?.rating?.takeIf { it > 0f } ?: entry?.rating?.takeIf { it > 0f }
    val genre = info?.genres?.firstOrNull() ?: entry?.genre?.split(',', '/')?.firstOrNull()?.trim()
    val year = info?.year ?: entry?.year

    if (askPin) {
        PinDialog(
            title = "Für dieses Profil freigeben?",
            hint = "„$title“ ist für dieses Kinderprofil gesperrt. Mit der Eltern-PIN wird der Film dauerhaft freigegeben.",
            onDismiss = { askPin = false },
        ) { pin ->
            if (vm.checkPin(pin)) {
                vm.unlockTitle(SearchEntry.KIND_MOVIE, movieId)
                askPin = false
                unlockTick++
                true
            } else false
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTop(NavTab.Movies, "Filme") }
        item { Spacer(Modifier.height(24.dp)) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                Column(Modifier.weight(1f).padding(end = 36.dp)) {
                    val label = buildList {
                        add("FILM")
                        genre?.takeIf { it.isNotEmpty() }?.let { add(it.uppercase()) }
                        year?.let { add(it) }
                        info?.runtimeMin?.takeIf { it > 0 }?.let { add(duration(it)) }
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
                    FactsLine(info?.fsk, rating, info?.votes ?: 0, info?.country)
                    info?.tagline?.let {
                        Text(
                            "„$it“",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CpColors.Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
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
                    info?.cast?.takeIf { it.isNotEmpty() }?.let { Credits("Mit", it.take(5).joinToString(", ")) }
                    info?.director?.let { Credits("Regie", it) }
                    if (also.isNotEmpty()) {
                        Row(
                            Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Auch bei", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted)
                            also.forEach { a -> TabPill(a.label, false) { vm.openInStreamingApp(context, a, title) } }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (state.profile.kid && gate is HomeViewModel.Gate.Blocked) {
                        KidLockedBubble(
                            "Ohoh, dieser Film ist noch nichts für dich. Frag Mama oder Papa!",
                            spoken = "Oh oh! Dieser Film ist noch nichts für dich. Frag doch mal Mama oder Papa!",
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val remain = progress?.let { p ->
                            if (p.durationMs > 0) ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1) else null
                        }
                        val kid = state.profile.kid
                        when (val g = gate) {
                            HomeViewModel.Gate.Allowed -> MainAction(
                                when {
                                    progress != null -> "▶  " + (if (kid) "Weiterschauen" else "Fortsetzen") + (remain?.let { " · noch $it Min." } ?: "")
                                    kid -> "▶  Los geht’s!"
                                    else -> "▶  Abspielen"
                                },
                                Modifier.focusRequester(playFocus),
                            ) { Playback.start(context, listOf(item)) }
                            HomeViewModel.Gate.Checking -> MainAction(if (kid) "Moment …" else "Prüfe Freigabe …", Modifier.focusRequester(playFocus)) {}
                            is HomeViewModel.Gate.Blocked ->
                                if (kid) SideAction("Eltern-PIN eingeben", Modifier.focusRequester(playFocus), icon = de.koeven.couchpotato.R.drawable.ic_lock) { askPin = true }
                                else PrimaryPill("🔒  Gesperrt · ${g.reason}", Modifier.focusRequester(playFocus)) { askPin = true }
                        }
                        if (progress != null && gate == HomeViewModel.Gate.Allowed) {
                            SideAction("Von vorne") {
                                vm.restartFromBeginning(key)
                                Playback.start(context, listOf(item))
                            }
                        }
                        info?.trailer?.takeIf { gate == HomeViewModel.Gate.Allowed }?.let { yt ->
                            SideAction("Trailer") {
                                val ok = runCatching {
                                    de.koeven.couchpotato.data.ScreenTime.leaveLauncher(context)   // kids: screen time runs from here
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.isSuccess
                                if (!ok) Notice.show("Keine App für YouTube-Trailer installiert")
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
            }
        }
        if (info?.fromTmdb == true) item { TmdbNote() }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** "FSK 12   ★ 7,4 (12.345)   US" */
@Composable
fun FactsLine(fsk: String?, rating: Float?, votes: Int, country: String?) {
    if (fsk == null && rating == null && country == null) return
    androidx.compose.foundation.layout.Row(
        Modifier.padding(top = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        fsk?.let { FskBadge(it) }
        rating?.let {
            Text(
                "★ " + "%.1f".format(java.util.Locale.GERMANY, it) +
                    (if (votes > 0) "  (" + java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(votes) + ")" else ""),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        country?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = CpColors.TextMuted) }
    }
}

/** FSK in the official colours (0 white, 6 yellow, 12 green, 16 blue, 18 red). */
@Composable
fun FskBadge(fsk: String) {
    val bg = when (fsk) {
        "0" -> androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        "6" -> androidx.compose.ui.graphics.Color(0xFFFFD500)
        "12" -> androidx.compose.ui.graphics.Color(0xFF2BA84A)
        "16" -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
        "18" -> androidx.compose.ui.graphics.Color(0xFFE53935)
        else -> CpColors.SurfaceHigh
    }
    val fg = if (fsk == "0" || fsk == "6") androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
    Text(
        "FSK $fsk",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Required by TMDB's terms when their data is shown. */
@Composable
fun TmdbNote() {
    Text(
        "Beschreibung, Besetzung, FSK und Bewertung: TMDB (themoviedb.org)",
        style = MaterialTheme.typography.labelMedium,
        color = CpColors.TextMuted,
        modifier = Modifier.padding(start = ScreenPadding, top = 16.dp),
    )
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
