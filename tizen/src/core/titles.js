// Series and film details: kept on the TV, asked from the provider only when needed (one request each).
import { big } from './storage.js';
import { display, episode as episodeName } from './names.js';
import { str } from './xtream.js';

const num = (v) => Number(String(v || '').replace(',', '.')) || 0;
const http = (s) => (s && s.startsWith('http') ? s : null);

/** Same rules as on the TVs and the iPhone. */
export function parseSeries(root, id, fallbackName) {
  if (!root || typeof root !== 'object') return null;
  const info = root.info || {};
  const seriesName = str(info.name) || fallbackName;
  let episodes = [];
  const add = (arr, seasonHint) => {
    (arr || []).forEach((o, i) => {
      const eid = Number(str(o && o.id));
      if (!eid && eid !== 0) return;
      const number = Number(str(o.episode_num)) || i + 1;
      const ei = o.info || {};
      episodes.push({
        id: eid, season: Number(str(o.season)) || seasonHint || 1, number,
        title: episodeName(str(o.title), seriesName, number),
        image: http(str(ei.movie_image)), ext: str(o.container_extension) || 'mp4',
        duration: Number(str(ei.duration_secs)) || 0, plot: str(ei.plot),
      });
    });
  };
  const eps = root.episodes;
  if (Array.isArray(eps)) eps.forEach((v, i) => Array.isArray(v) && add(v, i + 1));
  else if (eps && typeof eps === 'object') for (const k of Object.keys(eps)) if (Array.isArray(eps[k])) add(eps[k], Number(k));
  const seen = new Set();
  episodes = episodes.filter((e) => !seen.has(e.id) && seen.add(e.id)).sort((a, b) => a.season - b.season || a.number - b.number);
  const yearRaw = str(info.releaseDate) || str(info.release_date) || str(info.year);
  return {
    id, name: display(seriesName), cover: http(str(info.cover)), plot: str(info.plot), genre: str(info.genre),
    year: yearRaw && /^\d{4}/.test(yearRaw) ? yearRaw.slice(0, 4) : null, cast: str(info.cast),
    rating: Math.min(Math.max(num(str(info.rating)), 0), 10),
    backdrop: http(Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : str(info.backdrop_path)),
    episodes,
  };
}

export const seasonsOf = (s) => [...new Set(s.episodes.map((e) => e.season))].sort((a, b) => a - b);

export function episodeItem(s, e) {
  const pad = (n) => String(n).padStart(2, '0');
  return {
    key: 'ep:' + e.id, kind: 'episode', streamId: e.id, ext: e.ext, title: s.name,
    subtitle: `S${pad(e.season)} E${pad(e.number)} · ${e.title}`, image: e.image || s.cover, seriesId: s.id,
  };
}

export function parseMovie(root) {
  const i = root && root.info;
  if (!i || typeof i !== 'object') return null;
  const backdrop = Array.isArray(i.backdrop_path) ? i.backdrop_path[0] : str(i.backdrop_path);
  return {
    plot: str(i.plot) || str(i.description), cast: str(i.cast) || str(i.actors), director: str(i.director),
    genre: str(i.genre), duration: str(i.duration), backdrop: http(backdrop),
  };
}

export class TitleStore {
  async series(entry, client) {
    const key = 'series:' + entry.itemId;
    const cached = await big.get(key);
    let stale = null;
    if (cached) {
      const s = parseSeries(cached.data, entry.itemId, entry.name);
      if (s) {
        stale = s;
        const age = Date.now() - cached.at;
        const upToDate = entry.added > 0 ? cached.at >= entry.added * 1000 && age < 14 * 864e5 : age < 3 * 864e5;
        if (upToDate) return s;
      }
    }
    try {
      if (!client) throw new Error('Folgen gibt es nur mit IPTV-Zugang');
      const data = await client.seriesInfo(entry.itemId);
      const s = parseSeries(data, entry.itemId, entry.name);
      if (!s) throw new Error('Serie konnte nicht geladen werden');
      await big.set(key, { at: Date.now(), data });
      return s;
    } catch (e) {
      if (stale) return stale;
      throw e;
    }
  }

  async cachedMovie(id) {
    const c = await big.get('vod:' + id);
    return c ? parseMovie(c) : null;
  }

  /** ONE request, only when the user asks for it. */
  async loadMovie(id, client) {
    const data = await client.vodInfo(id);
    const m = parseMovie(data);
    if (!m) throw new Error('Keine Details');
    await big.set('vod:' + id, data);
    return m;
  }

  clear() { return Promise.all([big.clear('series:'), big.clear('vod:')]); }
}
