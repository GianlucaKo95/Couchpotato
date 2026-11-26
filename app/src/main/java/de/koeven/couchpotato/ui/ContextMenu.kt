package de.koeven.couchpotato.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

data class MenuAction(val label: String, val onClick: () -> Unit)

/**
 * Small popup opened by a long OK press.
 *
 * The popup appears while OK is still held. The remote keeps sending *repeated* key-downs
 * until the button is released, followed by one key-up – all of which would land on the first
 * entry and "click" it. So the popup stays deaf until the held button has been released
 * (or a genuinely new press – repeatCount 0 – arrives).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContextMenu(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(400.dp)
                // solid dark panel with a light edge, so it stands out over any background
                .background(CpColors.Panel, RoundedCornerShape(16.dp))
                .border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(22.dp)
                .onPreviewKeyEvent { e ->
                    if (armed) return@onPreviewKeyEvent false
                    when {
                        // release of the long press: swallow it, from now on the menu reacts
                        e.type == KeyEventType.KeyUp -> { armed = true; true }
                        // repeats of the still-held button: swallow
                        e.nativeKeyEvent.repeatCount > 0 -> true
                        // a fresh press (the long press was already released before the menu showed)
                        e.type == KeyEventType.KeyDown -> { armed = true; false }
                        else -> true
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = CpColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val all = actions + MenuAction("Abbrechen") {}
            all.forEachIndexed { i, action ->
                val cancel = i == all.lastIndex
                Button(
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = if (cancel) 0.05f else 0.12f),
                        contentColor = if (cancel) CpColors.TextMuted else CpColors.Text,
                        focusedContainerColor = CpColors.Text,
                        focusedContentColor = CpColors.Panel,
                    ),
                    scale = ButtonDefaults.scale(focusedScale = 1.03f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                ) {
                    Text(action.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
