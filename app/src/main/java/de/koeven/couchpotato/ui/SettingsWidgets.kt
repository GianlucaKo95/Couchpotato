package de.koeven.couchpotato.ui

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.koeven.couchpotato.data.HomeRows
import de.koeven.couchpotato.data.MediaWatcher
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.XtreamClient
import de.koeven.couchpotato.data.XtreamConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = CpColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted)
}

@Composable
internal fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted)
        Text(
            value,
            style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            maxLines = if (small) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = CpColors.TextMuted, maxLines = 1)
    }
}

/** A whole-width row: title (+ description) on the left, current value as a pill on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingRow(
    title: String,
    value: String,
    description: String? = null,
    onStep: ((Int) -> Unit)? = null,     // ◀ ▶ change the value directly (like a TV menu)
    onClick: () -> Unit,
) {
    val supporting: (@Composable () -> Unit)? = if (description != null) {
        { Text(description, style = MaterialTheme.typography.bodyMedium, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f)) }
    } else null
    val stepKeys = if (onStep == null) Modifier else Modifier.onPreviewKeyEvent { e ->
        val d = when (e.key) {
            androidx.compose.ui.input.key.Key.DirectionLeft -> -1
            androidx.compose.ui.input.key.Key.DirectionRight -> 1
            else -> 0
        }
        if (d == 0) return@onPreviewKeyEvent false
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) onStep(d)
        true
    }
    ListItem(
        selected = false,
        onClick = onClick,
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        headlineContent = { Text(title, fontWeight = FontWeight.Medium, maxLines = 1) },
        supportingContent = supporting,
        trailingContent = {
            // the value quietly in the row's own colour (dark on the focused row), arrows where ◀ ▶ change it
            val c = androidx.tv.material3.LocalContentColor.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onStep != null) Icon(painterResource(de.koeven.couchpotato.R.drawable.ic_pl_shift_left), null, tint = c.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                if (onStep != null) Icon(painterResource(de.koeven.couchpotato.R.drawable.ic_pl_shift_right), null, tint = c.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        },
        modifier = Modifier.fillMaxWidth().then(stepKeys),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NavRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        shape = ListItemDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (muted) CpColors.TextMuted else CpColors.Text,
            selectedContainerColor = Color.White.copy(alpha = 0.14f),
            selectedContentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        headlineContent = {
            Text(label, maxLines = 1, fontWeight = FontWeight.Medium)
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Shows "Label: value" as a normal focusable row. Only pressing OK opens a small dialog
 * with the real text field (and the on-screen keyboard).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TextFieldButton(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var editing by remember { mutableStateOf(false) }
    val shown = when {
        value.isBlank() -> "nicht gesetzt"
        password -> "•".repeat(value.length.coerceAtMost(12))
        else -> value
    }
    ListItem(
        selected = false,
        onClick = { editing = true },
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = CpColors.Text,
            focusedContainerColor = CpColors.Text,
            focusedContentColor = CpColors.Background,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.01f),
        overlineContent = { Text(label) },
        headlineContent = { Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text("bearbeiten", style = MaterialTheme.typography.bodyMedium) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (editing) {
        // cursor at the end of what is already there ("Ich|"), not in front of it
        var draft by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
        var phone by remember { mutableStateOf(true) }      // QR code right away, next to the field
        val fieldFocus = remember { FocusRequester() }
        fun accept() {
            onValueChange(draft.text.trim())
            editing = false
        }
        LaunchedEffect(Unit) {
            delay(100)
            runCatching { fieldFocus.requestFocus() }
        }
        Dialog(
            onDismissRequest = { editing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
          // a low strip at the very top: the on-screen keyboard (Fire TV, Android TV / HY300) takes the rest
          androidx.compose.foundation.layout.Box(
              Modifier.fillMaxSize().padding(top = 12.dp),
              contentAlignment = androidx.compose.ui.Alignment.TopCenter,
          ) {
           Row(
                Modifier
                    .width(if (phone) 900.dp else 600.dp)
                    .background(CpColors.Panel, RoundedCornerShape(16.dp))
                    .border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
           ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { M3Text(hint) },
                    singleLine = true,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { accept() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryPill("Übernehmen") { accept() }
                    SecondaryPill(if (phone) "QR-Code ausblenden" else "Mit dem Handy eingeben") { phone = !phone }
                    SecondaryPill("Abbrechen") { editing = false }
                }
            }
            if (phone) {
                // beside the field (not below), so the keyboard can't cover it
                // live from the phone into the field; "Übernehmen" on the TV confirms
                // copied links often carry a line break or spaces at the end – one line in the field
                PhoneLinkPanel(label, password, compact = true, vertical = true, value = value) { text ->
                    val t = text.replace(Regex("[\r\n\t]+"), "").trim()
                    draft = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length))
                }
            }
           }
          }
        }
    }
}

// =====================================================================================
// Profiles & kids
// =====================================================================================
