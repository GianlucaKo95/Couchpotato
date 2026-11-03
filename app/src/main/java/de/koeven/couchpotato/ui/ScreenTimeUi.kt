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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import de.koeven.couchpotato.R

/** The bear from the FSK bubble, dozing under a moon – same flat style as the profile animals. */
@Composable
private fun SleepingBear() {
    Box(Modifier.size(width = 220.dp, height = 150.dp)) {
        // crescent moon: a sun-yellow circle with a sky-coloured circle taken out
        Canvas(Modifier.size(70.dp).align(Alignment.TopEnd)) {
            val r = size.minDimension / 2
            drawCircle(KidColors.Sun, radius = r, center = Offset(r, r))
            drawCircle(KidColors.Sky, radius = r * 0.82f, center = Offset(r * 1.45f, r * 0.75f))
        }
        Box(Modifier.align(Alignment.BottomStart).offset(x = 30.dp)) {
            // the bear leans to the side, dozing
            Image(
                painterResource(R.drawable.animal_baer),
                contentDescription = "Schlafender Bär",
                modifier = Modifier.size(120.dp).rotate(-14f),
            )
        }
        Text("z", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = KidFont,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 140.dp, y = 30.dp))
        Text("z", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = KidFont,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 156.dp, y = 2.dp))
        Text("Z", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = KidFont,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 120.dp, y = (-24).dp))
    }
}

/** Kids profile after the daily screen time or at bedtime: friendly, but closed – parents can extend. */
@Composable
fun ScreenTimeLock(vm: HomeViewModel, message: String, hasPin: Boolean) {
    var askPin by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { first.requestFocus() } }
    KidTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SleepingBear()
                Text("Für heute ist Schluss", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, fontFamily = KidFont)
                // dark text on a white bubble – easy to read on the blue sky
                Text(
                    message,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = KidColors.Ink,
                    textAlign = TextAlign.Center,
                    fontFamily = KidFont,
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(22.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
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
