package de.koeven.couchpotato.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.PhoneLink

/**
 * QR code + address + code: type on the phone, it lands on the TV.
 * The little web server only runs while this panel is on screen.
 */
@Composable
fun PhoneLinkPanel(title: String, password: Boolean = false, compact: Boolean = false, vertical: Boolean = false, value: String = "", onText: (String) -> Unit) =
    PhoneLinkPanel(listOf(PhoneLink.Field("t", title, password, value)), compact, vertical) { onText(it["t"].orEmpty()) }

/** Several fields on the phone at once (e.g. server, user name, password). */
@Composable
fun PhoneLinkPanel(fields: List<PhoneLink.Field>, compact: Boolean = false, vertical: Boolean = false, onValues: (Map<String, String>) -> Unit) {
    val session = remember { PhoneLink.start(fields) }
    DisposableEffect(Unit) { onDispose { PhoneLink.stop() } }
    val got by PhoneLink.received.collectAsState()
    LaunchedEffect(got?.at) { got?.let { onValues(it.values); PhoneLink.received.value = null } }

    if (session == null) {
        Text("Kein WLAN gefunden – der Fernseher muss im selben Netz sein wie das Handy.", color = CpColors.Error)
        return
    }
    if (vertical) {
        // narrow column next to an input field: QR code with the code below it
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Image(
                session.qr.asImageBitmap(),
                contentDescription = "QR-Code für die Handy-Eingabe",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(112.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(5.dp),
            )
            Text("Mit dem Handy scannen", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("Code ${session.pin}", style = MaterialTheme.typography.labelMedium, color = CpColors.Accent, fontWeight = FontWeight.Bold)
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            session.qr.asImageBitmap(),
            contentDescription = "QR-Code für die Handy-Eingabe",
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .size(if (compact) 120.dp else 170.dp)
                .background(Color.White, RoundedCornerShape(10.dp))
                .padding(6.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bequemer mit dem Handy: Code scannen und live mittippen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("oder im Handy-Browser öffnen:  ${session.url}", style = MaterialTheme.typography.bodyMedium, color = CpColors.Accent)
            Text("Code: ${session.pin}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!compact) Text("Handy und Fernseher müssen im selben WLAN sein.", style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted)
        }
    }
}
