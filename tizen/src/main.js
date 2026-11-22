// Couchpotato for Samsung TVs.
import './polyfills.js';
import { checkForUpdate, markHealthy } from './core/update.js';
import { app } from './ui/app.js';
import { registerKeys } from './ui/keys.js';
import { model } from './core/model.js';
import { CONFIG } from './core/config.js';
import { MOVIE, SERIES } from './core/catalog.js';
import { player } from './ui/player.js';
import { HomeScreen } from './ui/screens/home.js';
import { BrowseScreen } from './ui/screens/browse.js';
import { LiveScreen } from './ui/screens/live.js';
import { SearchScreen } from './ui/screens/search.js';
import { SettingsScreen } from './ui/screens/settings.js';
import { ProfilesScreen } from './ui/screens/profiles.js';

registerKeys();
if (CONFIG.version === 'dev' || location.protocol.startsWith('http')) window.__cp = { model, app, player }; // tests in a browser
app.player = player;
app.init({
  home: () => new HomeScreen(),
  live: () => new LiveScreen(),
  movies: () => new BrowseScreen(MOVIE),
  series: () => new BrowseScreen(SERIES),
  search: () => new SearchScreen(),
  settings: (p) => new SettingsScreen(p),
  profiles: () => new ProfilesScreen(),
});

// switched off, Smart Hub key or another app: like "Return" during playback (Samsung's multitasking rule) –
// playback stops, the position is saved and handed over to the iPhone
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    if (player.active) player.stop();
    else if (app.screen && app.screen.stopPreview) { app.screen.stopPreview(); app.refresh(); }
    model.data.flush();
  } else if (model.household.linked) {
    model.syncNow(true);
  }
});
window.addEventListener('pagehide', () => { if (player.active) player.stop(true); model.data.flush(); });

// network gone: say so, and take it back when it returns
const offline = document.createElement('div');
offline.className = 'offline';
offline.textContent = 'Keine Internetverbindung – Couchpotato versucht es weiter';
document.body.appendChild(offline);
const setOnline = (on) => {
  offline.classList.toggle('show', !on);
  if (on) { model.refreshCatalog(false); if (model.household.linked) model.syncNow(true); }
};
window.addEventListener('offline', () => setOnline(false));
window.addEventListener('online', () => setOnline(true));
try {
  // Samsung's own signal (cable or Wi-Fi gateway lost / back)
  webapis.network.addNetworkStateChangeListener((state) => {
    if (state === webapis.network.NetworkState.GATEWAY_DISCONNECTED) setOnline(false);
    else if (state === webapis.network.NetworkState.GATEWAY_CONNECTED) setOnline(true);
  });
} catch (e) { /* not on a TV */ }

// self-update: look for a newer version shortly after start and then every 6 hours
let offered = false;
async function lookForUpdate() {
  const r = await checkForUpdate();
  if (r.state !== 'ready' || offered) return;
  if (app.player && app.player.active) { setTimeout(lookForUpdate, 5 * 60e3); return; } // never interrupt watching
  offered = true;
  if (await app.confirm('Update bereit', `Version ${r.version} ist geladen. Jetzt neu starten?`, 'Neu starten', 'Später')) {
    model.data.flush();
    location.reload();
  }
}

model.start().then(() => {
  setTimeout(lookForUpdate, 20e3);
  setInterval(lookForUpdate, 6 * 3600e3);
  // more than one profile: ask who is watching, like on the TVs
  if (model.login && model.profiles.length > 1) app.openTab('home'), app.push(new ProfilesScreen());
});
app.openTab('home');
// got this far: the running code works (boot.js keeps a downloaded version only after this)
markHealthy();
