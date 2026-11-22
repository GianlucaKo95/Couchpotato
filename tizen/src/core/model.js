// Central state of the TV app – same data and rules as the Fire TV launcher and the iPhone,
// so profiles, "Weiterschauen", "Meine Liste", favourites and "Für dich" sync across all of them.
import { local } from './storage.js';
import { HouseholdData, HouseholdSync } from './household.js';
import { XtreamClient, loginValid, loginHost, normalizeServer } from './xtream.js';
import { CatalogStore, buildIndex, search as searchCatalog, MOVIE, SERIES, LIVE } from './catalog.js';
import { TitleStore } from './titles.js';
import { EpgStore } from './epg.js';
import { gate } from './gate.js';
import { recommend } from './recommender.js';
import { sha256Hex } from './crypto.js';
import { display } from './names.js';

export const ANIMALS = 12;

// Free test streams (Apple's HLS examples) – lets anyone, including Samsung's testers, try the app
// without an IPTV account. Negative ids never clash with a provider's.
const DEMO_URLS = {
  '-1': 'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8',
  '-2': 'https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8',
  '-11': 'https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8',
  '-12': 'https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8',
};
function demoCatalog() {
  const e = (kind, id, name, cat, extra = {}) => Object.assign({ kind, itemId: id, ext: 'm3u8', image: null, name, norm: name.toLowerCase(), cat,
    added: 0, rating: 0, genre: null, year: null, tmdb: null, id: kind + ':' + id, categoryKey: kind + ':' + cat }, extra);
  return {
    entries: [
      e('l', -1, 'Demo · HLS fMP4', 'demo'), e('l', -2, 'Demo · HLS 16:9', 'demo'),
      e('m', -11, 'Testvideo (HLS, fMP4)', 'demo', { genre: 'Demo', year: '2024' }),
      e('m', -12, 'Testvideo (HLS, 4:3)', 'demo', { genre: 'Demo', year: '2024' }),
    ],
    categories: { m: [{ id: 'demo', name: 'Demo' }], s: [], l: [{ id: 'demo', name: 'Demo' }] },
    fetchedAt: Date.now(),
  };
}
export const PROFILE_COLORS = ['#F6D9A8', '#F7C1C1', '#BFD9F2', '#C8E6C0', '#DCC7EE', '#FBE3B5', '#BDE9EE', '#F8C9DE'];

/** Watched to the end – the last 10 % (end credits) or the last 2 minutes. Same rule as on the TVs. */
export function isFinished(posSec, durSec) {
  return durSec > 0 && (posSec >= durSec * 0.9 || (durSec >= 600 && durSec - posSec <= 120));
}

function javaHash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  return h;
}

export function animalIndex(p) {
  if (p.animal >= 0) return p.animal % ANIMALS;
  return ((javaHash(p.id) % ANIMALS) + ANIMALS) % ANIMALS;
}

export const suffixOf = (p) => (p.id === 'main' ? '' : '_' + p.id);

export function itemFromJson(o) {
  if (!o || typeof o.key !== 'string' || typeof o.kind !== 'string') return null;
  const id = Number(o.streamId);
  if (!isFinite(id)) return null;
  const sid = o.seriesId == null ? null : Number(o.seriesId);
  return { key: o.key, kind: o.kind, streamId: id, ext: o.ext || 'mp4', title: display(o.title || ''), subtitle: o.subtitle || null, image: o.image || null, seriesId: isFinite(sid) ? sid : null };
}

export function itemJson(i) {
  return { key: i.key, kind: i.kind, streamId: i.streamId, ext: i.ext, title: i.title, subtitle: i.subtitle || null, image: i.image || null, seriesId: i.seriesId == null ? null : i.seriesId };
}

export const movieItem = (e) => ({ key: 'vod:' + e.itemId, kind: 'movie', streamId: e.itemId, ext: e.ext, title: e.name, subtitle: null, image: e.image, seriesId: null });
export const liveItem = (c) => ({ key: 'live:' + c.id, kind: 'live', streamId: c.id, ext: 'm3u8', title: c.name, subtitle: null, image: c.logo, seriesId: null });
export const channelOf = (e) => ({ id: e.itemId, name: e.name, logo: e.image, cat: e.cat });

const today = () => { const d = new Date(); return '' + d.getFullYear() + (d.getMonth() + 1) + '-' + d.getDate(); };

export class Model {
  constructor() {
    this.data = new HouseholdData();
    this.household = new HouseholdSync(this.data);
    this.catalogStore = new CatalogStore();
    this.titles = new TitleStore();
    this.epg = new EpgStore();
    this.login = local.get('iptv.login');
    this.demo = local.get('demo', false);
    this.profileId = local.get('profile', 'main');
    this.catalog = { entries: [], categories: { m: [], s: [], l: [] }, fetchedAt: 0 };
    this.index = buildIndex([]);
    this.memo = new Map();
    this.listeners = new Set();
    this.status = '';          // catalogue / sync status line
    this.catalogBusy = false;
    this.pendingIptv = null;   // login shared by the main TV, waiting for the user's yes
    this.data.onChange((file) => this.householdChanged(file));
  }

  // MARK: events
  on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit() { for (const fn of this.listeners) fn(); }

  memoized(key, fn) {
    if (this.memo.has(key)) return this.memo.get(key);
    const v = fn();
    this.memo.set(key, v);
    return v;
  }

  householdChanged(file) {
    const s = this.profile.suffix;
    if (file === 'progress' + s || file === 'history' + s || file === 'watched' + s) {
      this.memo.delete('continue'); this.memo.delete('foryou');
    } else if (file === 'watchlist' + s) {
      this.memo.delete('mylist'); this.memo.delete('foryou');
    } else if (file && /^(progress|history|watched|watchlist)/.test(file)) {
      return; // another profile's data
    } else {
      this.memo.clear();
    }
    this.emit();
  }

  async start() {
    const c = this.demoOnly ? demoCatalog() : await this.catalogStore.load();
    if (c) this.setCatalog(c);
    this.emit();
    if (this.household.linked) this.syncNow(true);
    this.refreshCatalog(false);
    setInterval(() => this.household.linked && this.syncNow(true), 10 * 60e3);
    setInterval(() => this.refreshCatalog(false), 60 * 60e3);
  }

  setCatalog(c) {
    this.catalog = c;
    this.index = buildIndex(c.entries);
    this.memo.clear();
  }

  // MARK: demo (no IPTV account)
  get demoOnly() { return this.demo && !loginValid(this.login); }

  setDemo(on) {
    this.demo = on;
    local.set('demo', on);
    this.setCatalog(on && !loginValid(this.login) ? demoCatalog() : { entries: [], categories: { m: [], s: [], l: [] }, fetchedAt: 0 });
    this.emit();
    if (!on) this.catalogStore.load().then((c) => { if (c) { this.setCatalog(c); this.emit(); } });
  }

  // MARK: IPTV login
  get client() { return loginValid(this.login) ? new XtreamClient(this.login) : null; }
  get providerHost() { return this.login ? loginHost(this.login) : null; }

  async setLogin(login) {
    const l = login ? { server: normalizeServer(login.server), user: login.user.trim(), password: login.password.trim() } : null;
    const changed = JSON.stringify(l) !== JSON.stringify(this.login);
    this.login = l;
    if (l) local.set('iptv.login', l); else local.remove('iptv.login');
    if (l && this.demo) { this.demo = false; local.set('demo', false); }
    if (changed) {
      gate.clearBlock();
      await this.catalogStore.clear();
      await this.titles.clear();
      this.setCatalog({ entries: [], categories: { m: [], s: [], l: [] }, fetchedAt: 0 });
      this.emit();
      if (l) this.refreshCatalog(true);
    }
  }

  // MARK: catalogue (6 requests, at most every 24 hours)
  async refreshCatalog(force) {
    const client = this.client;
    if (!client || this.catalogBusy) return;
    const age = Date.now() - (this.catalog.fetchedAt || 0);
    if (!force && this.catalog.entries.length && age < 24 * 3600e3) return;
    this.catalogBusy = true;
    this.status = 'Katalog wird geladen …';
    this.emit();
    try {
      const c = await this.catalogStore.download(new XtreamClient(this.login, this.catalog.entries.length > 0), (n, total) => {
        this.status = `Katalog wird geladen (${n}/${total}) …`;
        this.emit();
      });
      this.setCatalog(c);
      this.status = '';
    } catch (e) {
      this.status = e.message || String(e);
    } finally {
      this.catalogBusy = false;
      this.emit();
    }
  }

  // MARK: household sync
  async syncNow(quiet) {
    if (!this.household.linked) return;
    try {
      const share = await this.household.sync();
      if (share && !loginValid(this.login) && !quiet) this.pendingIptv = share;
      this.emit();
    } catch (e) {
      if (!quiet) { this.status = e.message; this.emit(); }
    }
  }

  syncSoon() {
    if (!this.household.linked) return;
    clearTimeout(this.syncTimer);
    this.syncTimer = setTimeout(() => this.syncNow(true), 5000);
  }

  async join(code, onProgress) {
    const share = await this.household.join(code, onProgress);
    this.memo.clear();
    if (!this.profiles.some((p) => p.id === this.profileId)) this.chooseProfileDirect('main');
    this.emit();
    return share;
  }

  leaveHousehold() { this.household.leave(); this.memo.clear(); this.emit(); }

  // MARK: profiles & kids
  get profiles() {
    return this.memoized('profiles', () => {
      const raw = this.data.json('profiles', 'list');
      const list = (Array.isArray(raw) ? raw : []).filter((o) => o && typeof o.id === 'string' && typeof o.name === 'string').map((o) => ({
        id: o.id, name: o.name, color: o.color | 0, kid: !!o.kid, limit: o.limit | 0, bed: o.bed == null ? -1 : o.bed | 0,
        animal: o.animal == null ? -1 : o.animal | 0, maxFsk: o.maxFsk == null ? 6 : o.maxFsk | 0,
      }));
      const out = list.length ? list : [{ id: 'main', name: 'Ich', color: 0, kid: false, limit: 0, bed: -1, animal: -1, maxFsk: 6 }];
      for (const p of out) p.suffix = suffixOf(p);
      return out;
    });
  }

  get profile() { return this.profiles.find((p) => p.id === this.profileId) || this.profiles[0]; }

  chooseProfileDirect(id) {
    this.profileId = id;
    local.set('profile', id);
    this.memo.clear();
    this.emit();
  }

  saveProfile(p) {
    let raw = this.data.json('profiles', 'list');
    if (!Array.isArray(raw) || !raw.length) raw = [{ id: 'main', name: 'Ich', color: 0, kid: false }];
    const i = raw.findIndex((o) => o.id === p.id);
    const o = Object.assign({}, i >= 0 ? raw[i] : {}, { id: p.id, name: p.name, color: p.color, kid: p.kid, maxFsk: p.maxFsk, animal: p.animal, limit: p.limit, bed: p.bed });
    if (i >= 0) raw[i] = o; else raw.push(o);
    this.data.setJSON('profiles', 'list', raw);
    this.syncSoon();
  }

  deleteProfile(id) {
    if (id === 'main') return;
    const raw = (this.data.json('profiles', 'list') || []).filter((o) => o.id !== id);
    this.data.setJSON('profiles', 'list', raw);
    if (this.profileId === id) this.chooseProfileDirect('main');
    this.syncSoon();
  }

  newProfile() {
    const used = new Set(this.profiles.map(animalIndex));
    let animal = 0;
    while (used.has(animal) && animal < ANIMALS - 1) animal++;
    return { id: Math.random().toString(16).slice(2, 10), name: '', color: this.profiles.length % 8, kid: false, limit: 0, bed: -1, animal, maxFsk: 6 };
  }

  // parents' PIN on this TV (never synced, like on the other devices)
  get pinSet() { return !!local.get('kids.pin'); }
  checkPin(pin) { return local.get('kids.pin') === sha256Hex('couchpotato:' + pin); }
  setPin(pin) { local.set('kids.pin', sha256Hex('couchpotato:' + pin)); }

  // screen time for kids (counted on this TV)
  usedMinutes(p) { return local.get(`st.used.${p.id}.${today()}`, 0) / 60; }
  addWatchTime(seconds) {
    const p = this.profile;
    if (!p.kid) return;
    const k = `st.used.${p.id}.${today()}`;
    local.set(k, local.get(k, 0) + seconds);
  }
  addBonus(minutes) {
    const k = `st.bonus.${this.profile.id}.${today()}`;
    local.set(k, local.get(k, 0) + minutes);
  }
  /** null = may watch, otherwise the reason why not. */
  lockReason(p = this.profile) {
    if (!p.kid) return null;
    const bonus = local.get(`st.bonus.${p.id}.${today()}`, 0);
    const d = new Date();
    const now = d.getHours() * 60 + d.getMinutes();
    if (p.bed >= 0 && (now >= p.bed + bonus || now < 6 * 60)) {
      return `Ab ${Math.floor(p.bed / 60)}:${String(p.bed % 60).padStart(2, '0')} Uhr ist Schlafenszeit`;
    }
    if (p.limit > 0 && p.limit + bonus - this.usedMinutes(p) <= 0) {
      return d.getHours() >= 19 || d.getHours() < 6 ? 'Gute Nacht – für heute ist die Fernsehzeit vorbei' : 'Die Fernsehzeit für heute ist aufgebraucht';
    }
    return null;
  }

  get kidAllowed() { return this.memoized('kidAllowed', () => (this.profile.kid ? this.data.stringSet('profiles', 'kidCats') : null)); }
  get hiddenSet() { return this.memoized('hidden', () => this.data.stringSet('couchpotato', 'hiddenCats')); }

  allowed(e) {
    const key = e.categoryKey;
    if (this.hiddenSet.has(key)) return false;
    const kid = this.kidAllowed;
    return kid ? kid.has(key) : true;
  }

  isHidden(kind, cat) { return this.hiddenSet.has(kind + ':' + cat.id); }
  setHidden(kind, cats, hidden) {
    const set = this.data.stringSet('couchpotato', 'hiddenCats');
    for (const c of cats) { const k = kind + ':' + c.id; if (hidden) set.add(k); else set.delete(k); }
    this.data.setStringSet('couchpotato', 'hiddenCats', set);
    this.syncSoon();
  }

  // MARK: catalogue views
  categories(kind) {
    return this.memoized('cats' + kind, () => {
      const used = this.index.byCategory[kind] || {};
      const visible = (this.catalog.categories[kind] || []).filter((c) => {
        const key = kind + ':' + c.id;
        return !this.hiddenSet.has(key) && (!this.kidAllowed || this.kidAllowed.has(key)) && used[c.id];
      });
      const order = this.data.json('couchpotato', 'catOrder_' + kind);
      if (!Array.isArray(order) || !order.length) return visible;
      const rank = new Map(order.map((id, i) => [id, i]));
      return visible.map((c, i) => [c, i]).sort((a, b) => (rank.has(a[0].id) ? rank.get(a[0].id) : 1e9) - (rank.has(b[0].id) ? rank.get(b[0].id) : 1e9) || a[1] - b[1]).map((x) => x[0]);
    });
  }

  allCategories(kind) { return this.catalog.categories[kind] || []; }

  titlesIn(kind, cat) {
    return this.memoized(`t${kind}:${cat.id}`, () => ((this.index.byCategory[kind] || {})[cat.id] || []).filter((e) => this.allowed(e)));
  }

  newest(kind, limit = 30) {
    return this.memoized('new' + kind, () => {
      const out = [];
      for (const e of this.index.newest[kind] || []) {
        if (this.allowed(e)) out.push(e);
        if (out.length >= limit) break;
      }
      return out;
    });
  }

  entry(kind, id) { return this.index.byId.get(kind + ':' + id) || null; }

  search(q) { return searchCatalog(this.catalog.entries, q, (e) => this.allowed(e)); }

  // MARK: live TV
  channels(cat) { return this.memoized('ch' + cat.id, () => this.titlesIn(LIVE, cat).map(channelOf)); }
  get allChannels() { return this.memoized('allch', () => (this.index.newest[LIVE] || []).filter((e) => this.allowed(e)).map(channelOf)); }

  channelList(key) {
    const arr = this.data.json('couchpotato', key + this.profile.suffix);
    const seen = new Set();
    const kid = this.kidAllowed;
    return (Array.isArray(arr) ? arr : []).map((o) => {
      const id = Number(o && o.id);
      if (!isFinite(id) || seen.has(id)) return null;
      seen.add(id);
      const c = { id, name: display(o.name || ''), logo: o.logo || null, cat: o.cat || null };
      if (kid && !kid.has('l:' + (c.cat || ''))) return null;
      return c;
    }).filter(Boolean);
  }

  saveChannels(key, list) {
    this.data.setJSON('couchpotato', key + this.profile.suffix, list.map((c) => {
      const o = { id: c.id, name: c.name };
      if (c.logo) o.logo = c.logo;
      if (c.cat) o.cat = c.cat;
      return o;
    }));
  }

  get favorites() { return this.memoized('favs', () => this.channelList('liveFavs')); }
  get recent() { return this.memoized('recent', () => this.channelList('liveRecent')); }
  isFavorite(c) { return this.favorites.some((f) => f.id === c.id); }
  toggleFavorite(c) {
    const list = this.favorites.filter((f) => f.id !== c.id);
    if (list.length === this.favorites.length) list.push(c);
    this.saveChannels('liveFavs', list);
    this.syncSoon();
  }
  rememberWatched(c) {
    if (!(c.id > 0)) return;
    const list = [c].concat(this.recent.filter((r) => r.id !== c.id)).slice(0, 10);
    this.saveChannels('liveRecent', list);
    this.syncSoon();
  }

  // MARK: Meine Liste ("m:<id>" / "s:<id>" → added at)
  get watchlistFile() { return 'watchlist' + this.profile.suffix; }
  onList(e) { return this.data.value(this.watchlistFile, e.id) !== undefined; }
  toggleList(e) {
    if (this.onList(e)) this.data.remove(this.watchlistFile, e.id);
    else this.data.setLong(this.watchlistFile, e.id, Date.now());
    this.syncSoon();
  }
  get myList() {
    return this.memoized('mylist', () => {
      const f = this.watchlistFile;
      return this.data.keys(f).map((k) => [this.index.byId.get(k), this.data.long(f, k)])
        .filter((x) => x[0] && this.allowed(x[0])).sort((a, b) => b[1] - a[1]).map((x) => x[0]);
    });
  }

  // MARK: Weiterschauen
  get progressFile() { return 'progress' + this.profile.suffix; }

  progress(key) {
    const o = this.data.json(this.progressFile, key);
    const item = o && itemFromJson(o.item);
    if (!item) return null;
    return { item, position: (o.pos || 0) / 1000, duration: (o.dur || 0) / 1000, updatedAt: o.at || 0, upNext: !!o.upNext };
  }

  get continueWatching() {
    return this.memoized('continue', () => {
      const hidden = this.data.stringSet('couchpotato', 'hiddenContinue' + this.profile.suffix);
      return this.data.keys(this.progressFile).map((k) => (hidden.has(k) ? null : this.progress(k)))
        .filter((e) => e && (e.upNext || (e.position > 10 && !isFinished(e.position, e.duration))) && (!this.profile.kid || this.kidMayWatch(e.item)))
        .sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 30);
    });
  }

  kidMayWatch(item) {
    const e = item.kind === 'episode' ? item.seriesId != null && this.entry(SERIES, item.seriesId) : this.entry(MOVIE, item.streamId);
    return e ? this.allowed(e) : false;
  }

  hideContinue(e) {
    const key = 'hiddenContinue' + this.profile.suffix;
    const set = this.data.stringSet('couchpotato', key);
    set.add(e.item.key);
    this.data.setStringSet('couchpotato', key, set);
    this.syncSoon();
  }

  startPosition(key) {
    const p = this.progress(key);
    if (!p || p.upNext || isFinished(p.position, p.duration)) return 0;
    return p.position;
  }

  /** Same rules as on the TVs: one entry per series; finished = gone (next episode queued). */
  saveProgress(item, position, duration, next) {
    if (item.kind === 'live' || !(position > 0)) return;
    const file = this.progressFile;
    const finished = isFinished(position, duration);
    if (item.seriesId != null) {
      for (const k of this.data.keys(file)) {
        if (k === item.key) continue;
        const o = this.data.json(file, k);
        if (o && o.item && Number(o.item.seriesId) === item.seriesId) this.data.remove(file, k);
      }
    }
    this.addHistory(item, finished);
    if (finished) {
      this.data.remove(file, item.key);
      if (next) this.data.setJSON(file, next.key, { item: itemJson(next), pos: 0, dur: 0, at: Date.now(), upNext: true });
    } else {
      this.data.setJSON(file, item.key, { item: itemJson(item), pos: Math.round(position * 1000), dur: Math.round(Math.max(duration, 0) * 1000), at: Date.now(), upNext: false });
    }
    this.syncSoon();
  }

  // MARK: Für dich
  get historyFile() { return 'history' + this.profile.suffix; }

  addHistory(item, finished) {
    let key;
    if (item.kind === 'movie') key = 'm:' + item.streamId;
    else if (item.seriesId != null) key = 's:' + item.seriesId;
    else return;
    const now = Date.now();
    if (now - this.data.long(this.historyFile, key) > 3600e3) {
      this.data.setLong(this.historyFile, key, now);
      const keys = this.data.keys(this.historyFile);
      if (keys.length > 400) {
        keys.map((k) => [k, this.data.long(this.historyFile, k)]).sort((a, b) => a[1] - b[1])
          .slice(0, keys.length - 400).forEach(([k]) => this.data.remove(this.historyFile, k));
      }
    }
    if (finished && item.kind === 'episode' && item.seriesId != null) {
      const file = 'watched' + this.profile.suffix, wkey = 's:' + item.seriesId;
      const set = this.data.stringSet(file, wkey);
      if (!set.has(String(item.streamId))) { set.add(String(item.streamId)); this.data.setStringSet(file, wkey, set); }
    }
  }

  watchedEpisodes(seriesId) { return this.data.stringSet('watched' + this.profile.suffix, 's:' + seriesId); }

  longMap(file) {
    const out = {};
    for (const k of this.data.keys(file)) out[k] = this.data.long(file, k);
    return out;
  }

  get forYou() {
    return this.memoized('foryou', () => recommend(this.catalog.entries, (e) => this.allowed(e), this.longMap(this.historyFile), this.longMap(this.watchlistFile)));
  }

  // MARK: hand-over to the iPhone ("Weiterschauen vom Fernseher")
  get deviceId() {
    let id = local.get('device.id');
    if (!id) { id = 'tizen-' + Math.random().toString(16).slice(2, 12); local.set('device.id', id); }
    return id;
  }

  get deviceName() {
    try {
      if (window.webapis && webapis.productinfo) {
        const name = webapis.productinfo.getRealModel ? webapis.productinfo.getRealModel() : webapis.productinfo.getModel();
        return local.get('device.name') || ('Samsung ' + (name || 'TV'));
      }
    } catch (e) { /* not on a TV */ }
    return local.get('device.name') || 'Samsung TV';
  }

  handOver(item, posSec, durSec) {
    if (!this.household.linked) return;
    if (item.kind !== 'live' && (isFinished(posSec, durSec) || posSec < 5)) {
      const last = this.data.json('handoff', 'last');
      if (last && last.device === this.deviceId) this.data.remove('handoff', 'last');
    } else {
      this.data.setJSON('handoff', 'last', Object.assign(itemJson(item), {
        device: this.deviceId, from: this.deviceName, profile: this.profile.id,
        pos: item.kind === 'live' ? 0 : Math.round(posSec * 1000), dur: item.kind === 'live' ? 0 : Math.round(durSec * 1000), at: Date.now(),
      }));
    }
    this.data.flush();
    this.syncNow(true); // right away – the TV may be switched off next
  }

  // MARK: playback URLs
  streamURL(item) {
    if (this.demoOnly) return DEMO_URLS[String(item.streamId)] || null;
    const c = this.client;
    if (!c) return null;
    if (item.kind === 'live') return local.get('pref.liveTs', false) ? c.liveTSURL(item.streamId) : c.liveURL(item.streamId);
    if (item.kind === 'episode') return c.episodeURL(item.streamId, item.ext);
    return c.movieURL(item.streamId, item.ext);
  }

  // MARK: clearing (current profile)
  clearProgress() { for (const k of this.data.keys(this.progressFile)) this.data.remove(this.progressFile, k); this.syncSoon(); }
  clearHistory() {
    for (const f of [this.historyFile, 'watched' + this.profile.suffix]) for (const k of this.data.keys(f)) this.data.remove(f, k);
    this.syncSoon();
  }
}

export const model = new Model();
