// Minimal Xtream client. Every request goes through the brake – the provider locks the account
// when it gets too many, so this TV is as careful as the others.
import { gate, GateError, LOCK_CODES } from './gate.js';

export function normalizeServer(input) {
  let s = String(input || '').trim();
  if (!s) return s;
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  for (const marker of ['/player_api.php', '/get.php', '/xmltv.php']) {
    const i = s.indexOf(marker);
    if (i >= 0) s = s.slice(0, i);
  }
  return s.replace(/\/+$/, '');
}

export const loginValid = (l) => !!(l && String(l.server || '').trim() && String(l.user || '').trim() && String(l.password || '').trim());
export const loginHost = (l) => { try { return new URL(normalizeServer(l.server)).hostname.toLowerCase(); } catch (e) { return null; } };

/** Reads server, user and password out of a provider link (get.php?username=…, /live/U/P/123.ts …). */
export function loginFromLink(input) {
  const link = String(input || '').trim().replace(/^["'<]+|["'>]+$/g, '');
  if (!link) return null;
  const withScheme = /^https?:\/\//i.test(link) ? link : 'http://' + link;
  let u;
  try { u = new URL(withScheme); } catch (e) { return null; }
  if (!u.hostname) return null;
  const root = `${u.protocol}//${u.host}`;
  const user = (u.searchParams.get('username') || '').trim(), pass = (u.searchParams.get('password') || '').trim();
  if (user && pass) return { server: normalizeServer(withScheme.split('?')[0]), user, password: pass };
  const seg = u.pathname.split('/').filter(Boolean);
  if (seg.length >= 3 && ['playlist', 'get', 'live', 'movie', 'series', 'timeshift'].includes(seg[0].toLowerCase())) {
    return { server: root, user: decodeURIComponent(seg[1]), password: decodeURIComponent(seg[2]) };
  }
  if (seg.length >= 3 && /^\d+$/.test(seg[2].split('.')[0])) return { server: root, user: decodeURIComponent(seg[0]), password: decodeURIComponent(seg[1]) };
  return null;
}

export const str = (v) => {
  if (typeof v === 'string') { const t = v.trim(); return t ? t : null; }
  if (typeof v === 'number' && isFinite(v)) return String(v);
  return null;
};

export class XtreamClient {
  constructor(login, background = false) {
    this.login = login;
    this.base = normalizeServer(login.server);
    this.background = background;
  }

  api(action, extra = {}) {
    const q = new URLSearchParams({ username: this.login.user, password: this.login.password });
    if (action) q.set('action', action);
    for (const k of Object.keys(extra)) q.set(k, extra[k]);
    return `${this.base}/player_api.php?${q}`;
  }

  async get(url, timeout = 45000) {
    const text = await gate.run(async () => {
      const res = await httpGet(url, timeout);
      if (LOCK_CODES.includes(res.status)) {
        const until = gate.blockNow();
        const t = new Date(until);
        throw new GateError(`Anbieter sperrt gerade (HTTP ${res.status}) – vermutlich wegen zu vieler Anfragen. Couchpotato pausiert und versucht es ab ${t.getHours()}:${String(t.getMinutes()).padStart(2, '0')} Uhr wieder.`, 'blocked', until);
      }
      if (res.status < 200 || res.status >= 300) throw new Error(`Server antwortet mit HTTP ${res.status}`);
      return res.text;
    }, { background: this.background });
    try {
      return JSON.parse(text);
    } catch (e) {
      throw new Error('Keine gültige Antwort vom Anbieter (' + describe(text) + ') – Server-Adresse prüfen');
    }
  }

  async checkLogin() {
    const root = await this.get(this.api(null));
    const ui = root && root.user_info;
    if (!ui) throw new Error('Keine Kontodaten erhalten');
    if (str(ui.auth) !== '1') throw new Error('Login abgelehnt – Benutzername/Passwort prüfen');
    let line = 'Verbunden · Status: ' + (str(ui.status) || 'unbekannt');
    const exp = Number(str(ui.exp_date));
    if (exp) line += ' · gültig bis ' + new Date(exp * 1000).toLocaleDateString('de-DE');
    return line;
  }

  async categories(action) {
    const cats = await this.get(this.api(action));
    if (!Array.isArray(cats)) throw new Error('Kategorien konnten nicht geladen werden');
    return cats.map((o) => ({ id: str(o.category_id), name: str(o.category_name) || str(o.category_id) })).filter((c) => c.id);
  }

  async list(action) {
    const list = await this.get(this.api(action), 180000); // big catalogues are several MB
    if (!Array.isArray(list)) throw new Error('Liste konnte nicht geladen werden');
    return list;
  }

  async seriesInfo(id) {
    const o = await this.get(this.api('get_series_info', { series_id: id }));
    if (!o || typeof o !== 'object' || Array.isArray(o)) throw new Error('Serie konnte nicht geladen werden');
    return o;
  }

  async vodInfo(id) {
    const o = await this.get(this.api('get_vod_info', { vod_id: id }));
    if (!o || !o.info || typeof o.info !== 'object') throw new Error('Der Anbieter hat zu diesem Film keine Details');
    return o;
  }

  shortEpg(id, limit = 8) { return this.get(this.api('get_short_epg', { stream_id: id, limit })); }

  stream(segment, id, ext) {
    const e = (s) => encodeURIComponent(s);
    return `${this.base}/${segment}/${e(this.login.user)}/${e(this.login.password)}/${id}.${ext}`;
  }
  liveURL(id) { return this.stream('live', id, 'm3u8'); }
  liveTSURL(id) { return this.stream('live', id, 'ts'); }
  movieURL(id, ext) { return this.stream('movie', id, ext || 'mp4'); }
  episodeURL(id, ext) { return this.stream('series', id, ext || 'mp4'); }
}

/**
 * One GET with a time limit. XMLHttpRequest instead of fetch: on Samsung TVs it follows the app's
 * network permission (config.xml <access>) for any server, also ones that send no CORS headers.
 */
export function httpGet(url, timeout) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('GET', url, true);
    x.timeout = timeout;
    x.onload = () => resolve({ status: x.status, text: x.responseText || '' });
    x.ontimeout = () => reject(new Error('Der Anbieter antwortet nicht (keine Antwort nach ' + Math.round(timeout / 1000) + ' Sekunden)'));
    x.onerror = () => reject(new Error('Server nicht erreichbar – Adresse, Port und Internet prüfen'));
    try { x.send(); } catch (e) { reject(new Error('Anfrage nicht möglich: ' + (e && e.message ? e.message : e))); }
  });
}

function describe(text) {
  const t = String(text || '').trim();
  if (!t) return 'leer';
  if (/^<!doctype|^<html/i.test(t)) return 'eine Webseite statt Daten';
  return t.slice(0, 60);
}
