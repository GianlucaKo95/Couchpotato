// The provider's catalogue – same columns as the TVs' catalog_v3.tsv:
// kind(m/s/l) id ext image name norm cat added rating genre year tmdb
import { big, local } from './storage.js';
import { display, normalize } from './names.js';
import { str } from './xtream.js';

export const MOVIE = 'm', SERIES = 's', LIVE = 'l';

export function parseTSV(text) {
  const out = [];
  const seen = new Set();
  for (const line of text.split('\n')) {
    if (!line) continue;
    const p = line.split('\t');
    if (p.length < 6) continue;
    const id = Number(p[1]);
    if (!p[0] || !isFinite(id) || p[1] === '') continue;
    const col = (i) => (i < p.length && p[i] !== '' ? p[i].replace(/\r$/, '') : null);
    const e = {
      kind: p[0][0], itemId: id, ext: col(2) || 'mp4', image: col(3), name: display(p[4]), norm: p[5], cat: col(6),
      added: Number(col(7)) || 0, rating: Number(col(8)) || 0, genre: col(9), year: col(10), tmdb: Number(col(11)) || null,
    };
    e.id = e.kind + ':' + e.itemId;
    e.categoryKey = e.kind + ':' + (e.cat || '');
    if (!seen.has(e.id)) { seen.add(e.id); out.push(e); }
  }
  return out;
}

const clean = (s) => String(s == null ? '' : s).replace(/[\t\n\r]/g, ' ');
export function tsvLine(e) {
  return [e.kind, e.itemId, clean(e.ext), clean(e.image), clean(e.name), e.norm, clean(e.cat), e.added,
    e.rating > 0 ? e.rating.toFixed(1) : '', clean(e.genre), e.year || '', e.tmdb || ''].join('\t');
}

/** Index built once per catalogue: by kind and category (newest first), newest per kind, by id. */
export function buildIndex(entries) {
  const byCategory = { m: {}, s: {}, l: {} };
  const newest = { m: [], s: [], l: [] };
  const byId = new Map();
  for (const e of entries) {
    const k = byCategory[e.kind] || (byCategory[e.kind] = {});
    (k[e.cat || ''] || (k[e.cat || ''] = [])).push(e);
    (newest[e.kind] || (newest[e.kind] = [])).push(e);
    byId.set(e.id, e);
  }
  const byNew = (a, b) => b.added - a.added;
  for (const kind of [MOVIE, SERIES]) {
    for (const c of Object.keys(byCategory[kind])) byCategory[kind][c].sort(byNew);
    newest[kind].sort(byNew);
  }
  return { byCategory, newest, byId };
}

export function search(entries, query, allowed, limit = 40) {
  const q = normalize(query);
  const tokens = q.split(' ').filter(Boolean);
  if (q.length < 2 || !tokens.length) return { m: [], s: [], l: [] };
  const first = tokens[0];
  const score = (e) => {
    if (e.norm.startsWith(q)) return 0;
    if ((' ' + e.norm).includes(' ' + q)) return 1;
    if ((' ' + e.norm).includes(' ' + first)) return 2;
    return 3;
  };
  const hits = entries.filter((e) => tokens.every((t) => e.norm.includes(t)) && allowed(e))
    .map((e) => [score(e), e.name.length, e])
    .sort((a, b) => a[0] - b[0] || a[1] - b[1]).map((x) => x[2]);
  const out = { m: [], s: [], l: [] };
  for (const e of hits) if (out[e.kind] && out[e.kind].length < limit) out[e.kind].push(e);
  return out;
}

/** Keeps the catalogue on this TV: own download from the provider, at most every 24 hours (6 requests). */
export class CatalogStore {
  async load() {
    const text = await big.get('catalog_v3.tsv');
    const cats = await big.get('categories_v3.json');
    if (!text) return null;
    const entries = parseTSV(text);
    if (!entries.length) return null;
    return { entries, categories: cats ? parseCategories(cats) : { m: [], s: [], l: [] }, fetchedAt: local.get('catalog.at', 0) };
  }

  async download(client, onStep) {
    const categories = {};
    const steps = [[MOVIE, 'get_vod_categories'], [SERIES, 'get_series_categories'], [LIVE, 'get_live_categories']];
    let n = 0;
    for (const [kind, action] of steps) {
      if (onStep) onStep(++n, 6);
      categories[kind] = (await client.categories(action)).map((c) => ({ id: c.id, name: display(c.name) }));
    }
    const entries = [];
    const lists = [[MOVIE, 'get_vod_streams', 'stream_id', 'stream_icon'], [SERIES, 'get_series', 'series_id', 'cover'], [LIVE, 'get_live_streams', 'stream_id', 'stream_icon']];
    for (const [kind, action, idField, imageField] of lists) {
      if (onStep) onStep(++n, 6);
      const list = await client.list(action);
      for (const o of list) {
        const id = Number(str(o[idField]));
        if (!id && id !== 0) continue;
        const name = display(str(o.name) || '');
        if (!name) continue;
        const num = (v) => Number(String(v || '').replace(',', '.')) || 0;
        const rating = num(str(o.rating)), rating5 = num(str(o.rating_5based));
        const yearRaw = str(o.year) || str(o.releaseDate) || str(o.release_date) || str(o.releasedate);
        const year = yearRaw && /^\d{4}/.test(yearRaw) ? yearRaw.slice(0, 4) : null;
        const image = str(o[imageField]);
        const tmdb = Number(str(o.tmdb) || str(o.tmdb_id)) || null;
        const e = {
          kind, itemId: id, ext: str(o.container_extension) || (kind === LIVE ? 'ts' : 'mp4'),
          image: image && image.startsWith('http') ? image : null, name, norm: normalize(name), cat: str(o.category_id),
          added: Number(str(o.added) || str(o.last_modified)) || 0,
          rating: Math.min(Math.max(rating > 0 ? rating : rating5 * 2, 0), 10),
          genre: str(o.genre), year, tmdb: tmdb && tmdb > 0 ? tmdb : null,
        };
        entries.push(e);
      }
    }
    if (!entries.length) throw new Error('Der Anbieter hat keine Titel geliefert');
    const text = entries.map(tsvLine).join('\n') + '\n';
    await big.set('catalog_v3.tsv', text);
    await big.set('categories_v3.json', JSON.stringify(categories));
    local.set('catalog.at', Date.now());
    return { entries: parseTSV(text), categories, fetchedAt: Date.now() };
  }

  async clear() {
    await big.remove('catalog_v3.tsv');
    await big.remove('categories_v3.json');
    local.remove('catalog.at');
  }
}

function parseCategories(raw) {
  try {
    const o = JSON.parse(raw);
    const out = {};
    for (const k of [MOVIE, SERIES, LIVE]) out[k] = (o[k] || []).map((c) => ({ id: str(c.id), name: display(str(c.name) || str(c.id)) })).filter((c) => c.id);
    return out;
  } catch (e) {
    return { m: [], s: [], l: [] };
  }
}
