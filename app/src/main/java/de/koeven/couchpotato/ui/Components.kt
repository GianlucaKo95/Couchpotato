package de.koeven.couchpotato.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.koeven.couchpotato.data.AppEntry
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val ScreenPadding = 56.dp
private val CardShape = RoundedCornerShape(10.dp)

@Composable
fun Shelf(
    title: String,
    state: LazyListState = rememberLazyListState(),
    hint: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = ScreenPadding, bottom = if (hint == null) 8.dp else 2.dp),
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.Accent,
                modifier = Modifier.padding(start = ScreenPadding, bottom = 6.dp),
            )
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .width(width)
                .aspectRatio(2f / 3f),
            shape = CardDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh)) {
                if (image == null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(10.dp),
                    )
                } else {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = CpColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueCard(
    title: String,
    subtitle: String?,
    image: String?,
    fraction: Float,
    badge: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(300.dp)
            .aspectRatio(16f / 9f),
        shape = CardDefaults.shape(CardShape),
    ) {
        Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh)) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 80f))
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                badge?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = CpColors.Accent, maxLines = 1)
                }
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = CpColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (fraction > 0f) ProgressBar(fraction, Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(CpColors.Accent, RoundedCornerShape(2.dp))
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppCard(
    app: AppEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.width(192.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(192.dp)
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                when {
                    // Fit keeps the banner's own proportions (no stretching, nothing cut off)
                    app.banner != null -> Image(
                        bitmap = app.banner,
                        contentDescription = app.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    app.icon != null -> Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(64.dp),
                    )
                    else -> Text(app.label, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
                }
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodySmall,
            color = CpColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelCard(
    name: String,
    logo: String?,
    nowPlaying: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.width(208.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(208.dp)
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                } else {
                    Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(10.dp))
                }
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = nowPlaying?.let { "Jetzt: $it" } ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = CpColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TileCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 192.dp) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .width(width)
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.Surface), contentAlignment = Alignment.Center) {
                Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier) {
    fun now(): Pair<String, String> {
        val d = Date()
        return SimpleDateFormat("HH:mm", Locale.GERMANY).format(d) to
            SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(d)
    }
    var value by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) {
        while (true) {
            value = now()
            delay(5_000)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(value.first, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(value.second, style = MaterialTheme.typography.bodySmall, color = CpColors.TextMuted)
    }
}
