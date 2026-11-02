package de.koeven.couchpotato.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import de.koeven.couchpotato.R
import de.koeven.couchpotato.data.Profile

/** Kids profile look: sky blue, sunny yellow, rounded "Baloo 2", big targets. */
object KidColors {
    val Sky = Color(0xFF2A7DE1)
    val Sun = Color(0xFFFFD23F)
    val Ink = Color(0xFF0D2B55)
    val Soft = Color(0xFFDCEBFF)
    val Orange = Color(0xFFFF8A3D)
    val Pink = Color(0xFFF25C9A)
    val Green = Color(0xFF2FB36B)
    val Purple = Color(0xFF8E6CF0)
}

val KidFont = FontFamily(
    Font(R.font.baloo2_600, FontWeight.SemiBold),
    Font(R.font.baloo2_700, FontWeight.Bold),
    Font(R.font.baloo2_800, FontWeight.ExtraBold),
)

private val KidShape = RoundedCornerShape(22.dp)

/** Sky background with a few soft bubbles, and Baloo 2 for all text inside. */
@Composable
fun KidTheme(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    val kidType = t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = KidFont),
        displayMedium = t.displayMedium.copy(fontFamily = KidFont),
        displaySmall = t.displaySmall.copy(fontFamily = KidFont),
        headlineLarge = t.headlineLarge.copy(fontFamily = KidFont),
        headlineMedium = t.headlineMedium.copy(fontFamily = KidFont),
        headlineSmall = t.headlineSmall.copy(fontFamily = KidFont),
        titleLarge = t.titleLarge.copy(fontFamily = KidFont),
        titleMedium = t.titleMedium.copy(fontFamily = KidFont),
        titleSmall = t.titleSmall.copy(fontFamily = KidFont),
        bodyLarge = t.bodyLarge.copy(fontFamily = KidFont),
        bodyMedium = t.bodyMedium.copy(fontFamily = KidFont),
        bodySmall = t.bodySmall.copy(fontFamily = KidFont),
        labelLarge = t.labelLarge.copy(fontFamily = KidFont),
        labelMedium = t.labelMedium.copy(fontFamily = KidFont),
        labelSmall = t.labelSmall.copy(fontFamily = KidFont),
    )
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = kidType) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(KidColors.Sky)
                    drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.55f, center = Offset(size.width * 0.92f, -size.height * 0.05f))
                    drawCircle(Color.White.copy(alpha = 0.06f), radius = size.minDimension * 0.32f, center = Offset(size.width * 0.02f, size.height * 1.02f))
                    drawCircle(KidColors.Sun.copy(alpha = 0.16f), radius = size.minDimension * 0.1f, center = Offset(size.width * 0.74f, size.height * 0.24f))
                }
        ) { content() }
    }
}

/** "Hallo Nelio!" with the profile animal, the parents' button (PIN) and the clock. */
@Composable
fun KidHeader(profile: Profile, title: String, subtitle: String, avatar: Dp = 60.dp) {
    val nav = LocalNav.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(avatar)
                .border(3.dp, Color.White, CircleShape)
        ) { Avatar(profile, avatar) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = KidColors.Soft, maxLines = 1)
        }
        KidSmallButton("Eltern", R.drawable.ic_lock) { nav?.settings?.invoke() }
        Spacer(Modifier.width(14.dp))
        Clock()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KidSmallButton(label: String, icon: Int?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
        colors = ButtonDefaults.colors(
            containerColor = KidColors.Ink.copy(alpha = 0.35f),
            contentColor = KidColors.Soft,
            focusedContainerColor = Color.White,
            focusedContentColor = KidColors.Ink,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Big colourful area button (Filme, Serien, Fernsehen, Suchen). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KidBigButton(label: String, icon: Int, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = color,
            contentColor = Color.White,
            focusedContainerColor = color,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = shape)),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(78.dp),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** Big yellow "Los geht's!" / white secondary. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KidActionButton(label: String, primary: Boolean, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) KidColors.Sun else Color.White.copy(alpha = 0.18f),
            contentColor = if (primary) KidColors.Ink else Color.White,
            focusedContainerColor = if (primary) KidColors.Sun else Color.White,
            focusedContentColor = KidColors.Ink,
        ),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = shape)),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(horizontal = if (primary) 30.dp else 22.dp, vertical = 10.dp),
        modifier = modifier,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = if (primary) 24.sp else 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun kidCardBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(4.dp, Color.White), inset = 0.dp, shape = KidShape),
)

/** Poster with a big rounded frame; the title below in the kids' font. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KidPosterCard(title: String, image: String?, width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = modifier
                .width(width)
                .aspectRatio(1f / 1.45f),
            shape = CardDefaults.shape(KidShape),
            border = kidCardBorder(),
            scale = CardDefaults.scale(focusedScale = 1.07f),
        ) {
            Box(Modifier.fillMaxSize().background(KidColors.Ink.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                if (image != null) {
                    AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
                }
            }
        }
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Big landscape tile for "Weiterschauen" with a yellow progress bar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KidWideCard(
    title: String,
    image: String?,
    width: Dp,
    fraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bitmap: ImageBitmap? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(Modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(width)
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(KidShape),
            border = kidCardBorder(),
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(Modifier.fillMaxSize().background(KidColors.Ink.copy(alpha = 0.3f))) {
                when {
                    image != null -> AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    bitmap != null -> Image(bitmap, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(14.dp))
                }
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .height(7.dp)
                            .background(KidColors.Ink.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).height(7.dp).background(KidColors.Sun, RoundedCornerShape(7.dp)))
                    }
                }
            }
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Category bubble in the kids' Mediathek. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KidChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
            contentColor = if (selected) KidColors.Ink else Color.White,
            focusedContainerColor = KidColors.Sun,
            focusedContentColor = KidColors.Ink,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
        modifier = modifier,
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** Section title in the kids' screens. */
@Composable
fun KidRowTitle(text: String) {
    Text(
        text,
        fontSize = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White,
        modifier = Modifier.padding(start = ScreenPadding, bottom = 8.dp),
    )
}

/** The bear explains that a title is locked. */
@Composable
fun KidLockedBubble(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.animal_baer), contentDescription = "Bär", modifier = Modifier.size(84.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = KidColors.Ink,
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(22.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

/** Tile width so that [perRow] tiles fit next to each other. */
@Composable
fun kidTileWidth(perRow: Float, gap: Dp = 16.dp): Dp {
    val usable = LocalConfiguration.current.screenWidthDp.dp - ScreenPadding * 2
    return (usable - gap * (perRow - 1f)) / perRow
}

/** Primary action: yellow kids button in a kids profile, white pill otherwise. */
@Composable
fun MainAction(label: String, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    if (LocalProfile.current?.kid == true) KidActionButton(label, primary = true, modifier = modifier, icon = icon, onClick = onClick)
    else PrimaryPill(label, modifier, onClick = onClick)
}

@Composable
fun SideAction(label: String, modifier: Modifier = Modifier, icon: Int? = null, onClick: () -> Unit) {
    if (LocalProfile.current?.kid == true) KidActionButton(label, primary = false, modifier = modifier, icon = icon, onClick = onClick)
    else SecondaryPill(label, modifier, onClick = onClick)
}

/** Top of the info screens: kids greeting or the normal tab bar. */
@Composable
fun ScreenTop(tab: NavTab, kidSubtitle: String) {
    val p = LocalProfile.current
    if (p?.kid == true) KidHeader(p, "Hallo ${p.name}!", kidSubtitle, avatar = 52.dp) else TopBar(active = tab)
}
