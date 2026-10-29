package de.koeven.couchpotato.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.Profile
import kotlinx.coroutines.delay

private val AVATAR_COLORS = listOf(
    Color(0xFFE8B86D), Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784),
    Color(0xFFBA68C8), Color(0xFFFFB74D), Color(0xFF4DD0E1), Color(0xFFF06292),
)

fun avatarColor(p: Profile): Color = AVATAR_COLORS[Math.floorMod(p.color, AVATAR_COLORS.size)]

/** Round avatar with the first letter of the name. */
@Composable
fun Avatar(p: Profile, size: Dp) {
    Box(
        Modifier
            .size(size)
            .background(avatarColor(p), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.name.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color(0xFF1B1408),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

/** "Wer schaut?" – shown after every start of the stick (with more than one profile) and from the avatar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfilePicker(
    profiles: List<Profile>,
    active: Profile,
    onPick: (Profile) -> Unit,
    onManage: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E4247)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Wer schaut?", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEach { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
                        Surface(
                            onClick = { onPick(p) },
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    androidx.compose.foundation.BorderStroke(4.dp, CpColors.Text),
                                    shape = CircleShape,
                                ),
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                            modifier = Modifier
                                .size(104.dp)
                                .then(if (p.id == active.id) Modifier.focusRequester(first) else Modifier),
                        ) {
                            Avatar(p, 104.dp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (p.kid) {
                            Text("Kinder · bis FSK ${p.maxFsk}", style = MaterialTheme.typography.labelSmall, color = CpColors.Accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            SecondaryPill("Profile verwalten") { onManage() }
        }
    }
}

/**
 * PIN entry with an on-screen number pad (the Fire TV remote has no digits).
 * [onPin] returns false for a wrong PIN; the field is cleared and a message shown.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PinDialog(title: String, hint: String? = null, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    fun press(d: String) {
        error = null
        if (pin.length < 4) pin += d
        if (pin.length == 4) {
            val ok = onPin(pin)
            if (!ok) {
                error = "Falsche PIN"
                pin = ""
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(CpColors.Surface, RoundedCornerShape(18.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = CpColors.TextMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp).width(300.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(if (i < pin.length) CpColors.Accent else Color.White.copy(alpha = 0.18f), CircleShape)
                    )
                }
            }
            Text(
                error ?: " ",
                color = CpColors.Error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            if (k.isEmpty()) {
                                Spacer(Modifier.size(64.dp, 48.dp))
                            } else {
                                Button(
                                    onClick = { if (k == "⌫") pin = pin.dropLast(1) else press(k) },
                                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ButtonDefaults.colors(
                                        containerColor = Color.White.copy(alpha = 0.10f),
                                        contentColor = CpColors.Text,
                                        focusedContainerColor = CpColors.Text,
                                        focusedContentColor = CpColors.Background,
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier
                                        .size(64.dp, 48.dp)
                                        .then(if (k == "1") Modifier.focusRequester(first) else Modifier),
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(k, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SecondaryPill("Abbrechen") { onDismiss() }
        }
    }
}

/** Set a new PIN: enter it twice. */
@Composable
fun SetPinDialog(onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    val f = firstPin
    if (f == null) {
        PinDialog(
            title = "Neue Eltern-PIN",
            hint = "4 Ziffern. Nötig, um ein Kinderprofil zu verlassen und die Einstellungen zu öffnen.",
            onDismiss = onDismiss,
        ) { firstPin = it; true }
    } else {
        androidx.compose.runtime.key("repeat") {
            PinDialog(title = "PIN wiederholen", onDismiss = onDismiss) { second ->
                if (second == f) {
                    onSet(second)
                    true
                } else {
                    firstPin = null
                    false
                }
            }
        }
    }
}
