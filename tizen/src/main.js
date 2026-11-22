// Couchpotato for Samsung TVs.
import './polyfills.js';
import { app } from './ui/app.js';
import { registerKeys } from './ui/keys.js';
import { model } from './core/model.js';
import { MOVIE, SERIES } from './core/catalog.js';
import { player } from './ui/player.js';
import { HomeScreen } from './ui/screens/home.js';
import { BrowseScreen } from './ui/screens/browse.js';
import { LiveScreen } from './ui/screens/live.js';
import { SearchScreen } from './ui/screens/search.js';
import { SettingsScreen } from './ui/screens/settings.js';
import { ProfilesScreen } from './ui/screens/profiles.js';

registerKeys();
window.__cp = { model, app, player };
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

// switched off, Home button or another app: save where we are and hand it over to the iPhone
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    player.suspend();
    model.data.flush();
  } else if (model.household.linked) {
    model.syncNow(true);
  }
});
window.addEventListener('pagehide', () => { player.suspend(); model.data.flush(); });

model.start().then(() => {
  // more than one profile: ask who is watching, like on the TVs
  if (model.login && model.profiles.length > 1) app.openTab('home'), app.push(new ProfilesScreen());
});
app.openTab('home');
