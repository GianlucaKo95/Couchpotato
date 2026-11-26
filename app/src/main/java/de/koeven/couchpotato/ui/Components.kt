package de.koeven.couchpotato.ui

import androidx.compose.foundation.basicMarquee
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
/** Buttons and other controls (cards 12, panels 16). */
internal val ControlShape = RoundedCornerShape(10.dp)

/** Design B: focused tile gets a light frame and grows a little. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(border = BorderStroke(3.dp, CpColors.Text), inset = 0.dp, shape = CardShape),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun cardScale() = CardDefaults.scale(focusedScale = 1.06f)

/**
 * The focused tile lifts off the background: a soft dark shadow under it. Drawn only for the
 * one focused card, so it costs the stick nothing while scrolling through a row.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun cardGlow() = CardDefaults.glow(
    focusedGlow = androidx.tv.material3.Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 14.dp),
)

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
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = ScreenPadding),
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = CpColors.TextMuted,
                modifier = Modifier.padding(start = ScreenPadding, top = 4.dp),
            )
        }
        LazyRow(
            state = state,
            // just enough vertical room for the focus zoom of the cards
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            content = content,
        )
    }
}

/**
 * One line under a tile. Too long? While the tile is focused it scrolls slowly after a moment
 * (like the Fire TV home screen), otherwise it ends with "…".
 */
@Composable
fun CaptionText(
    text: String,
    focused: Boolean,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (focused) modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 900,
            repeatDelayMillis = 1_800,
            velocity = 32.dp,
        ) else modifier,
    )
}

/**
 * Big picture in the right part of the screen (behind everything), fading into the petrol to the
 * left, bottom and top – home screen info area and the film/series pages.
 */
@Composable
fun WideBackdrop(url: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        androidx.compose.animation.Crossfade(
            targetState = url,
            label = "wideBackdrop",
            modifier = Modifier.fillMaxWidth(0.44f).fillMaxHeight(0.56f),
        ) { u ->
            if (u == null) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val bg = CpColors.Background
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to bg, 0.14f to bg.copy(alpha = 0.6f), 0.34f to bg.copy(alpha = 0f))))
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to bg.copy(alpha = 0.6f), 0.22f to bg.copy(alpha = 0f), 0.68f to bg.copy(alpha = 0f), 1f to bg)))
            }
        }
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
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(width)
                .aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused }
                .glowFrom(image),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
            glow = cardGlow(),
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
        CaptionText(
            text = title,
            focused = focused,
            color = if (focused) CpColors.Text else CpColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
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
    bitmap: ImageBitmap? = null,
    showCaption: Boolean = true,      // home screen: the info area above shows the details
) {
    var focused by remember { mutableStateOf(false) }
    val glow = if (image == null && bitmap != null) Modifier.glowFrom(title, bitmap) else Modifier.glowFrom(image)
    Column(Modifier.width(ContinueWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(ContinueWidth)
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused }
                .then(glow),
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
            glow = cardGlow(),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.SurfaceHigh)) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CpColors.Text,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color(0x99000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (fraction > 0f) {
                    // dark shade at the bottom edge: the bar stays visible on bright pictures too
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.32f)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xCC0D1012))))
                    )
                    ProgressBar(
                        fraction,
                        onPicture = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (showCaption) CaptionText(
            text = listOfNotNull(title, subtitle).joinToString(" · "),
            focused = focused,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) CpColors.Text else CpColors.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, onPicture: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            // on a picture: a solid dark track, so you always see how much is left
            .background(if (onPicture) Color(0xE6262C2F) else Color(0x40FFFFFF), RoundedCornerShape(2.dp))
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
            glow = cardGlow(),
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
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelMedium,
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
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(width)
                .aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(CardShape),
            border = cardBorder(),
            scale = cardScale(),
            glow = cardGlow(),
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
        CaptionText(text = name, focused = focused, color = CpColors.Text, modifier = Modifier.padding(top = 6.dp))
        CaptionText(text = nowPlaying?.let { "Jetzt: $it" } ?: " ", focused = focused, color = CpColors.TextMuted)
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
            glow = cardGlow(),
        ) {
            Box(Modifier.fillMaxSize().background(CpColors.Surface), contentAlignment = Alignment.Center) {
                Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
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
        Text(value.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
}
