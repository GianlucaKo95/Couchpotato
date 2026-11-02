package de.koeven.couchpotato.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Kids profile after the daily screen time or at bedtime: friendly, but closed – parents can extend. */
@Composable
fun ScreenTimeLock(vm: HomeViewModel, message: String, hasPin: Boolean) {
    var askPin by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    KidTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("🌙", fontSize = 84.sp)
                Text("Für heute ist Schluss", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = KidColors.Soft, fontFamily = KidFont)
                Text(message, fontSize = 22.sp, color = KidColors.Soft, textAlign = TextAlign.Center, fontFamily = KidFont)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 18.dp)) {
                    KidActionButton("Eltern: 30 Minuten mehr", primary = true, modifier = Modifier.focusRequester(first)) {
                        if (hasPin) askPin = true else vm.extendScreenTime(30)
                    }
                    KidActionButton("Profil wechseln", primary = false) { vm.showProfilePicker() }
                }
            }
        }
    }
    if (askPin) {
        PinDialog(title = "Eltern-PIN", hint = "30 Minuten mehr für heute", onDismiss = { askPin = false }) { pin ->
            if (vm.checkPin(pin)) { askPin = false; vm.extendScreenTime(30); true } else false
        }
    }
}
