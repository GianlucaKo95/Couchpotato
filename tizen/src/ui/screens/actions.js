// What happens when a title is chosen – shared by all screens.
import { app } from '../app.js';
import { model, movieItem } from '../../core/model.js';
import { MOVIE, SERIES } from '../../core/catalog.js';
import { episodeItem } from '../../core/titles.js';
import { player } from '../player.js';
import { MovieScreen, SeriesScreen } from './detail.js';

export function openEntry(e) {
  if (e.kind === MOVIE) app.push(new MovieScreen(e));
  else if (e.kind === SERIES) app.push(new SeriesScreen(e));
  else player.playLive(model.allChannels, { id: e.itemId, name: e.name, logo: e.image, cat: e.cat }, 'Live TV');
}

export function playMovie(e, fromStart) {
  const item = movieItem(e);
  player.playVod(item, fromStart ? 0 : model.startPosition(item.key));
}

export function playEpisode(series, ep, fromStart, start) {
  const item = episodeItem(series, ep);
  const rest = series.episodes.slice(series.episodes.findIndex((x) => x.id === ep.id) + 1, series.episodes.findIndex((x) => x.id === ep.id) + 41).map((x) => episodeItem(series, x));
  player.playVod(item, start != null ? start : fromStart ? 0 : model.startPosition(item.key), rest);
}

/** "Weiterschauen": episodes with the rest of the series queued. */
export async function resume(p) {
  const start = p.upNext ? 0 : p.position;
  if (p.item.kind === 'episode' && p.item.seriesId != null) {
    const s = model.entry(SERIES, p.item.seriesId);
    if (s) {
      try {
        const info = await model.titles.series(s, model.client);
        const ep = info.episodes.find((x) => x.id === p.item.streamId);
        if (ep) { playEpisode(info, ep, false, start); return; }
      } catch (e) { /* play the single episode */ }
    }
  }
  player.playVod(p.item, start);
}
