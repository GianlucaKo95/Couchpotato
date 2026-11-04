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
import androidx.compose.runtime.collectAsState
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
    data class Movie(val id: Long, val name: String) : Screen
}

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()
    private val homeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        de.koeven.couchpotato.data.CrashLog.install(this)   // first: so even a crash during start is recorded
        ImageGate.start(this)          // request brake + picture loader, before anything goes online
        applyConfigExtras(intent)
        setContent {
            CouchpotatoTheme {
                CouchpotatoApp(vm, homeTick.intValue)
                NoticeHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        de.koeven.couchpotato.data.ScreenTime.backInLauncher(this)   // kids: book the time spent in apps/player
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
        // TMDB key/token: adb shell am start -n de.koeven.couchpotato/.ui.MainActivity --es tmdb 'TOKEN'
        intent.getStringExtra("tmdb")?.let { t ->
            vm.setTmdbToken(t)
            Notice.show(if (t.isBlank()) "TMDB-Schlüssel entfernt" else "TMDB-Schlüssel gespeichert")
            intent.removeExtra("tmdb")
            if (intent.getStringExtra("m3u") == null && intent.getStringExtra("xtream_url") == null) return true
        }
        val cfg = intent.getStringExtra("m3u")?.let { XtreamConfig.fromLink(it) }
            ?: run {
                val url = intent.getStringExtra("xtream_url")
                val user = intent.getStringExtra("xtream_user")
                val pass = intent.getStringExtra("xtream_pass")
                if (url != null && user != null && pass != null) XtreamConfig(url, user, pass) else null
            }
            ?: return false
        if (vm.catalog.applyLogin(cfg)) {
            vm.catalog.refresh(force = true)
            Notice.show("IPTV-Zugang gespeichert")
        } else {
            Notice.show("IPTV-Zugang unverändert")
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

    val state by vm.state.collectAsState()
    // something that needs the parents' PIN first (leaving a kids profile, settings in a kids profile)
    var pinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guarded: (() -> Unit) -> Unit = { action -> if (vm.profiles.pinNeeded()) pinAction = action else action() }

    // The tabs of the top bar switch areas: Start = home, the others = Mediathek on that tab.
    val nav = remember {
        NavActions(
            home = { stack = listOf(Screen.Home) },
            browse = { kind ->
                vm.library.browseKind = kind
                vm.library.browseCategoryId = null
                stack = listOf(Screen.Home, Screen.Browse(kind))
            },
            search = { if (stack.lastOrNull() != Screen.Search) stack = stack + Screen.Search },
            settings = {
                guarded { if (stack.lastOrNull() != Screen.Settings) stack = stack + Screen.Settings }
            },
            profiles = { vm.profiles.showPicker() },
        )
    }

    // A launcher must never close on Back; sub-screens go back one step.
    BackHandler(enabled = true) { back() }

    pinAction?.let { action ->
        PinDialog(title = "Eltern-PIN", onDismiss = { pinAction = null }) { pin ->
            if (vm.profiles.checkPin(pin)) {
                pinAction = null
                action()
                true
            } else false
        }
    }

    if (state.needsProfilePick) {
        BackHandler { vm.profiles.closePicker() }
        ProfilePicker(
            profiles = state.profiles,
            active = state.profile,
            onPick = { p ->
                if (p.id == state.profile.id) {
                    vm.profiles.closePicker()
                } else {
                    guarded {
                        vm.profiles.switchTo(p.id)
                        stack = listOf(Screen.Home)
                    }
                }
            },
            onManage = {
                guarded {
                    vm.profiles.closePicker()
                    vm.settingsSection = "profiles"
                    stack = listOf(Screen.Home, Screen.Settings)
                }
            },
        )
        return
    }

    // kids: screen time used up or bedtime – checked every minute while the launcher is open
    LaunchedEffect(state.profile.id) {
        while (true) { vm.profiles.checkScreenTime(); kotlinx.coroutines.delay(60_000) }
    }
    if (state.profile.kid && state.screenLock != null) {
        BackHandler { }
        ScreenTimeLock(vm, state.screenLock!!, state.hasPin, state.screenLockNight)
        return
    }

    val kid = state.profile.kid
    CompositionLocalProvider(LocalNav provides nav, LocalProfile provides state.profile) {
      KidThemeIf(kid) {
        when (val s = stack.last()) {
            Screen.Home -> if (kid) KidHomeScreen(
                vm = vm,
                homeTick = homeTick,
                onOpenSeries = { go(Screen.Series(it.id, it.name)) },
                onOpenMovie = { id, name -> go(Screen.Movie(id, name)) },
            ) else HomeScreen(
                vm = vm,
                homeTick = homeTick,
                onOpenSeries = { go(Screen.Series(it.id, it.name)) },
                onOpenSettings = nav.settings,
                onOpenLive = { nav.browse(SearchEntry.KIND_LIVE) },   // "Sender verwalten" = Mediathek Live-TV
                onOpenSearch = nav.search,
                onOpenBrowse = nav.browse,
                onOpenMovie = { id, name -> go(Screen.Movie(id, name)) },
            )
            is Screen.Browse -> key(s.kind) {
                if (kid) KidBrowseScreen(
                    vm = vm,
                    kind = s.kind,
                    onOpenSeries = { id, name -> go(Screen.Series(id, name)) },
                    onOpenMovie = { id, name -> go(Screen.Movie(id, name)) },
                ) else BrowseScreen(
                    vm = vm,
                    kind = s.kind,
                    onOpenSeries = { id, name -> go(Screen.Series(id, name)) },
                    onOpenMovie = { id, name -> go(Screen.Movie(id, name)) },
                )
            }
            Screen.Search -> SearchScreen(
                vm = vm,
                onOpenSeries = { id, name -> go(Screen.Series(id, name)) },
                onOpenMovie = { id, name -> go(Screen.Movie(id, name)) },
                onClose = back,
            )
            Screen.Settings -> SettingsScreen(vm = vm, onDone = back)
            is Screen.Series -> key(s.id) { SeriesScreen(vm = vm, seriesId = s.id, name = s.name) }
            is Screen.Movie -> key(s.id) {
                MovieScreen(vm = vm, movieId = s.id, name = s.name, onOpenMovie = { id, name -> go(Screen.Movie(id, name)) })
            }
        }
      }
    }
}

/** Kids profile: sky background and the kids' font around every screen. */
@Composable
private fun KidThemeIf(kid: Boolean, content: @Composable () -> Unit) {
    if (kid) KidTheme(content) else content()
}
