package de.koeven.couchpotato.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Short messages in Couchpotato's own style (instead of the small system toasts). */
object Notice {
    data class Message(val text: String, val id: Long = System.nanoTime(), val long: Boolean = false)

    val current = MutableStateFlow<Message?>(null)

    fun show(text: String, long: Boolean = false) {
        current.value = Message(text, long = long)
    }
}

/** Shown at the bottom centre of every screen; disappears by itself. */
@Composable
fun NoticeHost() {
    val msg by Notice.current.collectAsState()
    LaunchedEffect(msg?.id) {
        val m = msg ?: return@LaunchedEffect
        delay(if (m.long) 6_000 else 3_500)
        if (Notice.current.value?.id == m.id) Notice.current.value = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                msg?.text.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = CpColors.Text,
                modifier = Modifier
                    .padding(bottom = 40.dp)
                    .widthIn(max = 760.dp)
                    .background(Color(0xF20B3236), RoundedCornerShape(16.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}
