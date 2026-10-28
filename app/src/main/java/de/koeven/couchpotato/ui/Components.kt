package de.koeven.couchpotato.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
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

/*
 * Sizes follow the Fire OS 8 home screen: small app tiles (~10 per row) and large
 * landscape content tiles (~5 per row). They are computed from the real screen width,
 * so they look the same whatever density the stick reports.
 */
val ScreenPadding = 48.dp
internal val CardShape = RoundedCornerShape(12.dp)

/** Design B: focused tile gets a light frame and grows a little. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(border = BorderStroke(3.dp, CpColors.Text), inset = 0.dp, shape = CardShape),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun cardScale() = CardDefaults.scale(focusedScale = 1.06f)

/** Tells the background glow which picture is focused. */
@Composable
fun Modifier.glowFrom(image: String?): Modifier {
    val backdrop = LocalBackdrop.current ?: return this
    return onFocusChanged { if (it.isFocused) backdrop.focusImage(image) }
}

@Composable
fun Modifier.glowFrom(key: String, bitmap: ImageBitmap?): Modifier {
    val backdrop = LocalBackdrop.current ?: return this
    return onFocusChanged { if (it.isFocused) backdrop.focusBitmap(key, bitmap) }
}
private val RowGap = 12.dp

@Composable
private fun tileWidth(perRow: Float): Dp {
    val usable = LocalConfiguration.current.screenWidthDp.dp - ScreenPadding * 2
    return (usable - RowGap * (perRow - 1f)) / perRow
}

val AppTileWidth: Dp @Composable get() = tileWidth(10.4f)     // "Meine Apps und Sender"
val ContentWidth: Dp @Composable get() = tileWidth(5.4f)      // Weiterschauen, Filme, Serien (16:9)
val ContinueWidth: Dp @Composable get() = ContentWidth
val ChannelWidth: Dp @Composable get() = tileWidth(7.4f)      // Live-TV
val PosterWidth: Dp @Composable get() = tileWidth(10.4f)      // portrait covers: Neue Filme/Serien, Suche, Mediathek

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
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = ScreenPadding),
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
            // just enough vertical room for the focus zoom of the cards
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(RowGap),
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
    width: Dp = PosterWidth,
) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .width(width)
                .aspectRatio(2f / 3f)
                .glowFrom(image),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh)) {
                if (image == null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(6.dp),
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
            style = MaterialTheme.typography.labelSmall,
            color = CpColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Landscape 16:9 tile like Fire OS, built from a portrait IPTV cover:
 * the cover dimmed as background, sharp on the left, the title next to it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LandscapePosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .width(ContentWidth)
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
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.35f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(ContinueWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(ContinueWidth)
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused }
                .glowFrom(image),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
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
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CpColors.Text,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color(0x99000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (fraction > 0f) {
                    ProgressBar(
                        fraction,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Text(
            text = listOfNotNull(title, subtitle).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) CpColors.Text else CpColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(0x4DFFFFFF), RoundedCornerShape(2.dp))
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
    // like Fire OS 8: compact banner tiles, the app name only shows on the focused one
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(AppTileWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(AppTileWidth)
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused }
                .glowFrom(app.packageName, app.banner ?: app.icon),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
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
                        modifier = Modifier.fillMaxHeight(0.7f).aspectRatio(1f),
                    )
                    else -> Text(
                        app.label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) CpColors.Text else Color.Transparent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
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
    width: Dp = ChannelWidth,
) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(width)
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                } else {
                    Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
                }
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
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
fun TileCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = AppTileWidth) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .width(width)
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.Surface), contentAlignment = Alignment.Center) {
                Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
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
        Text(value.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
