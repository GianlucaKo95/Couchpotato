// The household's data exactly as the TVs keep it (their SharedPreferences files):
// file → key → {"type": "string"|"boolean"|"int"|"long"|"float"|"set", "value": …}.
// Only what this app needs is read or written; everything else passes through untouched.
import { local } from './storage.js';
import * as cc from './crypto.js';
import { CONFIG } from './config.js';

export class HouseholdData {
  constructor() {
    this.files = filesOf(local.get('household', {}));
    this.listeners = new Set();
    this.lastChanged = null;
    this.saveTimer = null;
  }

  // reading
  value(file, key) { const f = this.files[file]; const t = f && f[key]; return t ? t.value : undefined; }
  string(file, key) { const v = this.value(file, key); return typeof v === 'string' ? v : null; }
  stringSet(file, key) { const v = this.value(file, key); return new Set(Array.isArray(v) ? v : []); }
  keys(file) { return Object.keys(this.files[file] || {}); }
  json(file, key) {
    const s = this.string(file, key);
    if (s == null) return null;
    try { return JSON.parse(s); } catch (e) { return null; }
  }
  long(file, key) { const v = this.value(file, key); return typeof v === 'number' ? v : 0; }

  // writing
  setString(file, key, v) { this.put(file, key, v == null ? null : { type: 'string', value: v }); }
  setJSON(file, key, v) { this.setString(file, key, JSON.stringify(v)); }
  setStringSet(file, key, set) { this.put(file, key, { type: 'set', value: [...set].sort() }); }
  setLong(file, key, v) { this.put(file, key, { type: 'long', value: Math.round(v) }); }
  remove(file, key) { this.put(file, key, null); }

  put(file, key, typed) {
    const f = Object.assign({}, this.files[file] || {});
    if (typed) f[key] = typed; else delete f[key];
    this.files = Object.assign({}, this.files, { [file]: f });
    this.lastChanged = file;
    this.changed();
    this.save();
  }

  get snapshot() { return { version: 1, preferences: this.files }; }

  replace(snapshot) {
    const iptv = snapshot.iptv;
    this.files = filesOf(snapshot);
    this.iptv = iptv || null;
    this.lastChanged = null;
    this.changed();
    this.save();
  }

  clear() {
    this.files = {};
    this.lastChanged = null;
    local.remove('household');
    this.changed();
  }

  onChange(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  changed() { for (const fn of this.listeners) fn(this.lastChanged); }

  save() {
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.flush(), 800);
  }

  flush() {
    clearTimeout(this.saveTimer);
    local.set('household', { version: 1, preferences: this.files });
  }
}

export function filesOf(snapshot) {
  const p = snapshot && snapshot.preferences;
  return p && typeof p === 'object' ? p : {};
}

/** Three-way merge as on the TVs: a key changed here wins, otherwise the household's value counts. */
export function merge(base, localSnap, remote) {
  const b = filesOf(base), l = filesOf(localSnap), r = filesOf(remote);
  const out = {};
  const files = new Set([...Object.keys(b), ...Object.keys(l), ...Object.keys(r)]);
  for (const file of files) {
    const bf = b[file] || {}, lf = l[file] || {}, rf = r[file] || {};
    const merged = {};
    const keys = new Set([...Object.keys(bf), ...Object.keys(lf), ...Object.keys(rf)]);
    for (const key of keys) {
      const localChanged = (lf[key] === undefined) !== (bf[key] === undefined) || canonical(lf[key]) !== canonical(bf[key]);
      const v = localChanged ? lf[key] : rf[key];
      if (v !== undefined) merged[key] = v;
    }
    out[file] = merged;
  }
  return { version: 1, preferences: out };
}

export function canonical(v) {
  if (v === undefined || v === null) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (typeof v === 'object') return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  return JSON.stringify(v);
}

export class SyncError extends Error {}

/** Joins the household of the TVs and keeps profiles, favourites and "Weiterschauen" in step. */
export class HouseholdSync {
  constructor(data) {
    this.data = data;
    const k = local.get('household.keys');
    this.keys = k ? { auth: cc.fromHex(k.auth), enc: cc.fromHex(k.enc) } : null;
    this.lastSync = local.get('household.at', 0);
    this.busy = false;
  }

  get linked() { return !!this.keys; }
  static get configured() { return !!(CONFIG.supabaseUrl && CONFIG.supabaseKey); }
  get code() { const c = local.get('household.code'); return c ? cc.displayCode(c) : null; }

  /** Returns the shared IPTV login (if the main TV shares it); the caller asks before using it. */
  async join(raw, onProgress) {
    if (!HouseholdSync.configured) throw new SyncError('Der Abgleich ist in dieser Version nicht eingerichtet.');
    const code = cc.normalizeCode(raw);
    if (code.length !== cc.CODE_LENGTH) throw new SyncError(`Der Haushalts-Code hat ${cc.CODE_LENGTH} Zeichen.`);
    this.busy = true;
    try {
      const keys = await cc.deriveKeys(code, onProgress);
      let res;
      try {
        res = await call('pull', { auth: cc.authHex(keys) });
      } catch (e) {
        if (e.status === 404) throw new SyncError('Diesen Haushalts-Code gibt es nicht (mehr). Bitte am Fernseher unter Einstellungen → Haushalt nachsehen.');
        throw e;
      }
      const remote = res.blob ? await openSnap(keys, res.blob) : null;
      if (!remote) throw new SyncError('Der Code passt nicht – bitte noch einmal eingeben.');
      this.keys = keys;
      local.set('household.keys', { auth: cc.hex(keys.auth), enc: cc.hex(keys.enc) });
      local.set('household.code', code);
      this.data.replace(remote);
      saveBaseline(remote);
      this.lastSync = Date.now();
      local.set('household.at', this.lastSync);
      return iptvOf(remote);
    } finally {
      this.busy = false;
    }
  }

  async sync() {
    if (!HouseholdSync.configured || !this.keys || this.busy) return null;
    this.busy = true;
    try {
      const keys = this.keys;
      const baseline = local.get('household.base');
      for (let attempt = 0; attempt < 3; attempt++) {
        const localSnap = this.data.snapshot;
        let res;
        try {
          res = await call('pull', { auth: cc.authHex(keys) });
        } catch (e) {
          if (e.status === 404) throw new SyncError('Der Haushalts-Code wurde am Hauptgerät erneuert – bitte den neuen Code eingeben.');
          throw e;
        }
        const remote = res.blob ? await openSnap(keys, res.blob) : null;
        if (!remote) throw new SyncError('Die Haushaltsdaten lassen sich nicht entschlüsseln.');
        const prefs = baseline ? merge(baseline, localSnap, remote).preferences : filesOf(remote);
        // everything else (the IPTV login of the main TV, newer fields) is passed through as it is
        const merged = Object.assign({}, remote, { preferences: prefs });
        if (canonical(prefs) !== canonical(filesOf(remote))) {
          const sealed = await cc.seal(keys, JSON.stringify(merged));
          try {
            await call('push', { auth: cc.authHex(keys), blob: sealed, base_version: Number(res.version || 0) });
          } catch (e) {
            if (e.status === 409) continue; // a TV was faster – fetch its state and merge again
            throw e;
          }
        }
        this.data.replace(merged);
        saveBaseline(merged);
        this.lastSync = Date.now();
        local.set('household.at', this.lastSync);
        return iptvOf(merged);
      }
      throw new SyncError('Andere Geräte gleichen gerade ab – bitte gleich noch einmal versuchen.');
    } finally {
      this.busy = false;
    }
  }

  leave() {
    for (const k of ['household.keys', 'household.code', 'household.base', 'household.at']) local.remove(k);
    this.keys = null;
    this.lastSync = 0;
    this.data.clear();
  }
}

async function openSnap(keys, blob) {
  const json = await cc.open(keys, blob);
  if (!json) return null;
  try { return JSON.parse(json); } catch (e) { return null; }
}

function saveBaseline(snap) {
  const s = Object.assign({}, snap);
  delete s.iptv; // the login is never kept in plain storage
  local.set('household.base', s);
}

function iptvOf(snap) {
  const o = snap && snap.iptv;
  if (!o || !o.url || !o.user || !o.pass) return null;
  return { server: o.url, user: o.user, password: o.pass };
}

async function call(action, extra) {
  const body = Object.assign({}, extra, { action, v: 3 });
  let res;
  try {
    res = await fetch(CONFIG.supabaseUrl + '/functions/v1/couchpotato-sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: CONFIG.supabaseKey, Authorization: 'Bearer ' + CONFIG.supabaseKey },
      body: JSON.stringify(body),
    });
  } catch (e) {
    throw new SyncError('Keine Verbindung zum Abgleich – Internet prüfen.');
  }
  if (res.ok) return res.json().catch(() => ({}));
  if (res.status === 426) throw new SyncError('Bitte Couchpotato aktualisieren – der Haushalt nutzt ein neueres Format.');
  const err = new SyncError(`Der Abgleich antwortet mit Fehler ${res.status}.`);
  err.status = res.status;
  throw err;
}
