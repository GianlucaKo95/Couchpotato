// Film and series pages: big picture, facts, buttons; series with seasons and episodes.
import { h, clear, icon, clock } from '../dom.js';
import { app } from '../app.js';
import { model, movieItem, isFinished } from '../../core/model.js';
import { episodeItem, seasonsOf } from '../../core/titles.js';
import { img, button } from '../parts.js';
import { playMovie, playEpisode } from './actions.js';

function facts(e, extra) {
  return [e.year, e.genre, e.rating > 0 ? '★ ' + e.rating.toFixed(1) : null, extra].filter(Boolean).join('  ·  ');
}

function listButton(e) {
  const on = model.onList(e);
  return button(on ? 'In Meiner Liste' : 'Meine Liste', () => { model.toggleList(e); }, { icon: on ? 'check' : 'plus', fid: 'list' });
}

export class MovieScreen {
  constructor(e) {
    this.e = e;
    this.info = null;
    this.defaultFid = 'play';
    model.titles.cachedMovie(e.itemId).then((m) => { if (m) { this.info = m; app.refresh(); } });
  }

  render() {
    const e = this.e, info = this.info;
    const p = model.progress(movieItem(e).key);
    const resumable = p && p.position > 10 && !isFinished(p.position, p.duration);
    const el = clear(this.el);
    el.className = 'screen detail';
    el.append(
      img((info && info.backdrop) || e.image, 'backdrop'),
      h('div.backdrop-shade'),
      h('div.detail-body', null,
        h('div.poster-big', null, img(e.image, 'poster-pic')),
        h('div.detail-text', null,
          h('h1', null, e.name),
          h('p.facts', null, facts(e, info && info.duration)),
          h('div.buttons', { dataset: { row: 'dbtn' } },
            button(resumable ? `Fortsetzen (${clock(p.position)})` : 'Abspielen', () => playMovie(e, false), { primary: true, icon: 'play', fid: 'play' }),
            resumable ? button('Von vorne', () => playMovie(e, true), { icon: 'restart', fid: 'restart' }) : null,
            listButton(e),
            !info && model.client ? button('Details laden', () => this.loadInfo(), { fid: 'info' }) : null),
          info && info.plot ? h('p.plot', null, info.plot) : null,
          info && info.director ? h('p.credits', null, h('b', null, 'Regie  '), info.director) : null,
          info && info.cast ? h('p.credits', null, h('b', null, 'Mit  '), info.cast) : null)));
  }

  async loadInfo() {
    try {
      this.info = await model.titles.loadMovie(this.e.itemId, model.client);
      app.refresh();
    } catch (err) {
      app.toast(err.message);
    }
  }
}

export class SeriesScreen {
  constructor(e) {
    this.e = e;
    this.info = null;
    this.error = null;
    this.season = null;
    this.defaultFid = 'play';
    model.titles.series(e, model.client).then((s) => {
      this.info = s;
      const cont = model.continueWatching.find((p) => p.item.seriesId === e.itemId);
      const ep = cont && s.episodes.find((x) => x.id === cont.item.streamId);
      this.season = ep ? ep.season : seasonsOf(s)[0];
      app.refresh();
    }).catch((err) => { this.error = err.message; app.refresh(); });
  }

  render() {
    const e = this.e, s = this.info;
    const el = clear(this.el);
    el.className = 'screen detail series';
    const cont = model.continueWatching.find((p) => p.item.seriesId === e.itemId);
    const contEp = cont && s && s.episodes.find((x) => x.id === cont.item.streamId);
    const first = s && s.episodes[0];
    const watched = model.watchedEpisodes(e.itemId);
    const seasons = s ? seasonsOf(s) : [];
    const eps = s ? s.episodes.filter((x) => x.season === this.season) : [];
    el.append(
      img((s && s.backdrop) || (s && s.cover) || e.image, 'backdrop'),
      h('div.backdrop-shade'),
      h('div.vscroll.detail-scroll', { dataset: { sid: 'series', pad: '200' } },
        h('div.detail-body', null,
          h('div.poster-big', null, img((s && s.cover) || e.image, 'poster-pic')),
          h('div.detail-text', null,
            h('h1', null, e.name),
            h('p.facts', null, facts(e, s ? `${seasons.length} ${seasons.length === 1 ? 'Staffel' : 'Staffeln'}` : null)),
            h('div.buttons', { dataset: { row: 'dbtn' } },
              contEp ? button(cont.upNext ? `S${contEp.season} E${contEp.number} abspielen` : `Fortsetzen: S${contEp.season} E${contEp.number}`,
                () => playEpisode(s, contEp, false, cont.upNext ? 0 : cont.position), { primary: true, icon: 'play', fid: 'play' })
                : first ? button('Folge 1 abspielen', () => playEpisode(s, first, true), { primary: true, icon: 'play', fid: 'play' }) : null,
              listButton(e)),
            this.error ? h('p.error', null, this.error) : !s ? h('p.muted', null, 'Folgen werden geladen …') : null,
            s && s.plot ? h('p.plot', null, s.plot) : null)),
        s && seasons.length > 1 ? h('div.hscroll.seasons', { dataset: { row: 'seasons', sid: 'seasons' } }, h('div.row-items', null,
          seasons.map((n) => h('button.chip.focusable', { class: n === this.season ? 'on' : '', dataset: { fid: 'season-' + n }, onclick: () => { this.season = n; app.refresh(); } }, 'Staffel ' + n)))) : null,
        h('div.episodes', null, eps.map((ep) => this.episodeRow(s, ep, watched)))));
  }

  episodeRow(s, ep, watched) {
    const p = model.progress(episodeItem(s, ep).key);
    const frac = p && p.duration > 0 && !p.upNext ? Math.min(p.position / p.duration, 1) : 0;
    const done = watched.has(String(ep.id));
    return h('button.episode.focusable', { dataset: { fid: 'ep-' + ep.id }, onclick: () => playEpisode(s, ep, false) },
      h('div.ep-pic', null, img(ep.image || s.cover, 'wide-pic'), frac > 0 ? h('div.progress', null, h('div.bar', { style: { width: (frac * 100).toFixed(1) + '%' } })) : null),
      h('div.ep-text', null,
        h('div.ep-title', null, `${ep.number}. ${ep.title}`, done ? icon('check', 'done') : null),
        h('div.ep-sub', null, [ep.duration ? Math.round(ep.duration / 60) + ' Min.' : null, p && !p.upNext && p.position > 10 ? 'bei ' + clock(p.position) : null].filter(Boolean).join(' · ')),
        ep.plot ? h('div.ep-plot', null, ep.plot) : null));
  }
}
