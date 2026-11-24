package de.koeven.couchpotato.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.player.HausgeistLink

/** Pairing with the family's Hausgeist: voice control ("Spiel Pokémon im Kinderzimmer"). */
@Composable
internal fun HausgeistSection() {
    val status by HausgeistLink.status.collectAsState()
    var url by remember { mutableStateOf(HausgeistLink.url()) }

    SectionTitle(
        "Hausgeist",
        "Den Fernseher per Sprache über den Hausgeist steuern: suchen, abspielen, Apps öffnen, Lautstärke. " +
            "Im Kinderprofil – und wenn ein Kind fragt – gibt es nur freigegebene Inhalte.",
    )

    when (val s = status) {
        is HausgeistLink.Status.Pairing -> {
            Text("Code in der Hausgeist-App eingeben (Verwaltung → Fernseher) – gilt 10 Minuten", style = MaterialTheme.typography.labelMedium, color = CpColors.TextMuted)
            Text(
                s.code.chunked(3).joinToString("  "),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = CpColors.Text,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
            SettingRow(title = "Abbrechen", value = "") { HausgeistLink.unpair() }
        }
        is HausgeistLink.Status.Connected -> {
            Text("Verbunden als „${s.name}“", color = CpColors.Accent)
            Hint("Sag dem Hausgeist z. B. „Spiel Pokémon auf ${s.name}“ oder „Mach YouTube an“.")
            SettingRow(title = "Kopplung aufheben", value = "Trennen") { HausgeistLink.unpair() }
        }
        is HausgeistLink.Status.Trouble -> {
            Text(s.message, color = CpColors.Error)
            if (HausgeistLink.pairedName() == null) {
                TextFieldButton(label = "Adresse des Hausgeists", value = url, onValueChange = { url = it }, hint = HausgeistLink.DEFAULT_URL)
                SettingRow(title = "Neu koppeln", value = "Code anzeigen") { HausgeistLink.pair(url) }
            } else {
                Hint("Der Fernseher versucht es automatisch weiter.")
                SettingRow(title = "Kopplung aufheben", value = "Trennen") { HausgeistLink.unpair() }
            }
        }
        HausgeistLink.Status.Off -> {
            if (HausgeistLink.pairedName() != null) {
                Text("Verbinde …", color = CpColors.TextMuted)
            } else {
                TextFieldButton(label = "Adresse des Hausgeists", value = url, onValueChange = { url = it }, hint = HausgeistLink.DEFAULT_URL)
                SettingRow(title = "Mit Hausgeist koppeln", value = "Code anzeigen") { HausgeistLink.pair(url) }
            }
        }
    }
}
