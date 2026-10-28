package de.koeven.couchpotato.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.koeven.couchpotato.data.ImageGate
import de.koeven.couchpotato.data.SearchEntry
import de.koeven.couchpotato.data.SettingsStore
import de.koeven.couchpotato.data.XtreamConfig

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Search : Screen
    data class Browse(val kind: Char) : Screen
    data class Series(val id: Long, val name: String) : Screen
}

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()
    private val homeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImageGate.start(this)          // request brake + picture loader, before anything goes online
        applyConfigExtras(intent)
        setContent {
            CouchpotatoTheme {
                CouchpotatoApp(vm, homeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!applyConfigExtras(intent)) {
            // Home button / Launcher Manager brings us back: jump to the start screen.
            homeTick.intValue++
        }
    }

    /**
     * Lets you set the IPTV login from a computer instead of typing on the TV:
     *   adb shell am start -n de.koeven.couchpotato/.ui.MainActivity --es m3u "'http://host:8080/get.php?username=U&password=P'"
     * or with --es xtream_url / xtream_user / xtream_pass.
     */
    private fun applyConfigExtras(intent: Intent?): Boolean {
        intent ?: return false
        val cfg = intent.getStringExtra("m3u")?.let { XtreamConfig.fromLink(it) }
            ?: run {
                val url = intent.getStringExtra("xtream_url")
                val user = intent.getStringExtra("xtream_user")
                val pass = intent.getStringExtra("xtream_pass")
                if (url != null && user != null && pass != null) XtreamConfig(url, user, pass) else null
            }
            ?: return false
        if (vm.applyLogin(cfg)) {
            vm.refreshCatalog(force = true)
            Toast.makeText(this, "IPTV-Zugang gespeichert", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "IPTV-Zugang unverändert", Toast.LENGTH_SHORT).show()
        }
        return true
    }
}

@Composable
private fun CouchpotatoApp(vm: HomeViewModel, homeTick: Int) {
    // small back stack, so e.g. Search -> Series -> Back returns to the search results
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val go: (Screen) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    LaunchedEffect(homeTick) { stack = listOf(Screen.Home) }

    // The tabs of the top bar switch areas: Start = home, the others = Mediathek on that tab.
    val nav = remember {
        NavActions(
            home = { stack = listOf(Screen.Home) },
            browse = { kind ->
                vm.browseKind = kind
                vm.browseCategoryId = null
                stack = listOf(Screen.Home, Screen.Browse(kind))
            },
            search = { if (stack.lastOrNull() != Screen.Search) stack = stack + Screen.Search },
            settings = { if (stack.lastOrNull() != Screen.Settings) stack = stack + Screen.Settings },
        )
    }

    // A launcher must never close on Back; sub-screens go back one step.
    BackHandler(enabled = true) { back() }

    CompositionLocalProvider(LocalNav provides nav) {
        when (val s = stack.last()) {
            Screen.Home -> HomeScreen(
                vm = vm,
                homeTick = homeTick,
                onOpenSeries = { go(Screen.Series(it.id, it.name)) },
                onOpenSettings = nav.settings,
                onOpenLive = { nav.browse(SearchEntry.KIND_LIVE) },   // "Sender verwalten" = Mediathek Live-TV
                onOpenSearch = nav.search,
                onOpenBrowse = nav.browse,
            )
            is Screen.Browse -> key(s.kind) {
                BrowseScreen(vm = vm, kind = s.kind, onOpenSeries = { id, name -> go(Screen.Series(id, name)) })
            }
            Screen.Search -> SearchScreen(vm = vm, onOpenSeries = { id, name -> go(Screen.Series(id, name)) }, onClose = back)
            Screen.Settings -> SettingsScreen(vm = vm, onDone = back)
            is Screen.Series -> SeriesScreen(vm = vm, seriesId = s.id, name = s.name)
        }
    }
}
