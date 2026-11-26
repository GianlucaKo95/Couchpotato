package de.koeven.couchpotato.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** Screens rendered on the JVM, like on the 1080p Fire TV (960 × 540 dp). Pictures are drawn stand-ins. */
class HomeShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1080, screenWidth = 1920, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 1.0,
    )

    private fun scene(a: Int, b: Int, c: Int): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, 480f, 270f, Paint().apply { shader = LinearGradient(0f, 0f, 480f, 270f, a, b, Shader.TileMode.CLAMP) })
        cv.drawCircle(340f, 110f, 120f, Paint().apply { shader = RadialGradient(340f, 110f, 120f, c, 0x00000000, Shader.TileMode.CLAMP) })
        cv.drawRect(0f, 200f, 480f, 270f, Paint().apply { color = 0x55000000 })
        return bmp.asImageBitmap()
    }

    private val backdrop by lazy { scene(0xFF2A4E66.toInt(), 0xFF0C1A24.toInt(), 0xFFF0C890.toInt()) }

    @Composable
    private fun Home() {
        CouchpotatoTheme {
            Box(Modifier.fillMaxSize()) {
            BackdropFrame(large = true) { androidx.compose.foundation.Image(backdrop, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Text("OK lang drücken: Optionen", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = ScreenPadding, bottom = 16.dp))
            Column(Modifier.fillMaxSize()) {
                TopBar(active = NavTab.Start, showContentTabs = true)
                HeroInfo(Hero(kicker = "Serie · Weiterschauen", title = "Die Nordsee-Wache", sub = "S2 E4 · Sturmflut", fraction = 0.62f, remaining = "noch 17 Min."),
                    "Ein Containerschiff treibt auf die Küste zu, und die Crew hat nur eine Nacht, um es aufzuhalten. Währenddessen kehrt Jana nach Jahren auf die Insel zurück.")
                Shelf("Weiterschauen") {
                    val items = listOf(
                        Triple("Die Nordsee-Wache", 0.62f, scene(0xFF1D3B52.toInt(), 0xFF0B1620.toInt(), 0xFF6FA6C8.toInt())),
                        Triple("Küchenduell", 0.2f, scene(0xFF6B3A1E.toInt(), 0xFF1E120B.toInt(), 0xFFE0A060.toInt())),
                        Triple("Bergdoktor", 0.85f, scene(0xFF2F4A2A.toInt(), 0xFF0E160D.toInt(), 0xFFB5D08A.toInt())),
                        Triple("Tatort", 0.4f, scene(0xFF3A2A44.toInt(), 0xFF120C16.toInt(), 0xFFB08AD0.toInt())),
                        Triple("Sportschau", 0.1f, scene(0xFF1E4A3A.toInt(), 0xFF08140F.toInt(), 0xFF7AD0A8.toInt())),
                        Triple("Doku Alpen", 0.5f, scene(0xFF44403A.toInt(), 0xFF14130F.toInt(), 0xFFD8CBB0.toInt())),
                    )
                    items(items.size) { i ->
                        val (t, f, b) = items[i]
                        ContinueCard(t, "Folge ${i + 3}", null, f, if (i == 1) "Neu" else null, {}, {}, bitmap = b, showCaption = false)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Shelf("Live-TV") {
                    val ch = listOf("Das Erste" to "Tagesschau", "ZDF" to "heute journal", "arte" to "Doku", "3sat" to "Kulturzeit", "WDR" to "Lokalzeit", "ProSieben" to "Galileo", "RTL" to "Wer wird Millionär", "VOX" to "Kitchen Impossible")
                    items(ch.size) { i -> ChannelCard(ch[i].first, null, ch[i].second, {}, {}) }
                }
            }
            }
        }
    }

    @Test fun home() = paparazzi.snapshot { Home() }
}

class SettingsShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1080, screenWidth = 1920, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 1.0,
    )

    @Test fun settings() = paparazzi.snapshot {
        CouchpotatoTheme {
            Column(Modifier.fillMaxSize()) {
                TopBar(active = NavTab.None, showContentTabs = true, activeIcon = "settings")
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 24.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.androidx_width(260)) {
                        Text("Einstellungen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
                        Text("Familie", style = MaterialTheme.typography.labelLarge, color = CpColors.TextMuted, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                        NavRow("Profile & Kinderschutz", false, {}, summary = "3 Profile · PIN an")
                        Text("Fernsehen", style = MaterialTheme.typography.labelLarge, color = CpColors.TextMuted, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp))
                        NavRow("Startbildschirm", true, {}, summary = "6 Reihen · Weiterschauen")
                        NavRow("Live-TV", false, {}, summary = "12 Favoriten")
                        Text("Verbindung", style = MaterialTheme.typography.labelLarge, color = CpColors.TextMuted, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp))
                        NavRow("IPTV-Zugang", false, {}, summary = "verbunden")
                        NavRow("Geräte & Haushalt", false, {}, summary = "Haushalt, Hausgeist")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Startbildschirm", "Welche Reihen der Startbildschirm zeigt und in welcher Reihenfolge")
                        SettingRow("Weiterschauen", "an", "Was du angefangen hast, auch aus anderen Apps", onStep = {}) {}
                        SettingRow("Live-TV", "Favoriten", null, onStep = {}) {}
                        SettingRow("Für dich", "an", "Empfehlungen aus deinem Verlauf") {}
                        SettingRow("Reihenfolge ändern", "6 Reihen") {}
                        SubPage {
                            SectionTitle("Weiterschauen", "Was du angefangen hast – auch aus Netflix, ARD und Co.")
                            SettingRow("Status neu prüfen", "Prüfen") {}
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.androidx_width(dp: Int) = with(androidx.compose.foundation.layout.Arrangement) { this@androidx_width.then(Modifier.width(dp.dp)) }
