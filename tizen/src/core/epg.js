// Programme guide per channel. Every channel costs the provider a request, so: kept 20 minutes and
// asked only while plenty of the hourly budget is left.
import { gate } from './gate.js';
import { str } from './xtream.js';

function text(v) {
  const raw = str(v);
  if (!raw) return null;
  try {
    const bin = atob(raw);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const s = new TextDecoder('utf-8', { fatal: true }).decode(bytes).trim();
    if (s) return s;
  } catch (e) { /* not base64 */ }
  return raw;
}

export function parseEpg(root) {
  const list = root && root.epg_listings;
  if (!Array.isArray(list)) return [];
  const now = Date.now();
  return list.map((o) => {
    const start = Number(str(o.start_timestamp)) * 1000, stop = Number(str(o.stop_timestamp)) * 1000;
    const title = text(o.title);
    if (!title || !start || !stop || stop <= start) return null;
    return { title, info: text(o.description), start, stop };
  }).filter((e) => e && e.stop > now).sort((a, b) => a.start - b.start);
}

export const isNow = (e) => Date.now() >= e.start && Date.now() < e.stop;
export const progressOf = (e) => Math.min(Math.max((Date.now() - e.start) / Math.max(e.stop - e.start, 1), 0), 1);
export const hhmm = (t) => { const d = new Date(t); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0'); };

export class EpgStore {
  constructor() { this.cache = new Map(); }

  cached(id) {
    const c = this.cache.get(id);
    if (!c || Date.now() - c.at > 20 * 60e3) return null;
    return c.entries.filter((e) => e.stop > Date.now());
  }

  async load(id, client) {
    const c = this.cached(id);
    if (c) return c;
    let entries = [];
    if (client && gate.requestsLastHour() < 25) {
      try { entries = parseEpg(await client.shortEpg(id)); } catch (e) { entries = []; }
    }
    this.cache.set(id, { at: Date.now(), entries });
    return entries;
  }
}
