package de.koeven.couchpotato.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** "＋ Meine Liste" / "✓ Auf meiner Liste" on the film and series screens. */
@Composable
fun MyListAction(vm: HomeViewModel, kind: Char, id: Long) {
    var on by remember(kind, id) { mutableStateOf(vm.titles.onMyList(kind, id)) }
    SideAction(if (on) "✓  Auf meiner Liste" else "＋  Meine Liste") {
        on = vm.titles.toggleMyList(kind, id)
    }
}

/** Long-press menu entry for a film or series tile. */
fun myListMenuAction(vm: HomeViewModel, kind: Char, id: Long): MenuAction =
    if (vm.titles.onMyList(kind, id)) MenuAction("Von „Meine Liste“ entfernen") { vm.titles.toggleMyList(kind, id) }
    else MenuAction("Zu „Meine Liste“ hinzufügen") { vm.titles.toggleMyList(kind, id) }
