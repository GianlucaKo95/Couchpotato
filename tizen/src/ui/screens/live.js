// Live TV like on the TVs: categories, channels with what's on now, and a running preview with the
// programme guide. OK on the previewed channel goes full screen.
import { h, clear, icon } from '../dom.js';
import { app } from '../app.js';
import * as F from '../focus.js';
import { K } from '../keys.js';
import { model, liveItem } from '../../core/model.js';
import { LIVE } from '../../core/catalog.js';
import { engine } from '../engine.js';
import { player } from '../player.js';
import { img, emptyState } from '../parts.js';
import { hhmm, isNow, progressOf } from '../../core/epg.js';
import { local } from '../../core/storage.js';

const PREVIEW = [1160, 190, 700, 394];

export class LiveScreen {
  constructor() {
    const favs = model.favorites;
    this.cat = favs.length ? 'favs' : null;
    this.preview = null; // channel playing in the preview
    this.defaultFid = null;
  }

  get categories() {
    const list = [];
    if (model.favorites.length) list.push({ id: 'favs', name: 'Favoriten' });
    if (model.recent.length) list.push({ id: 'recent', name: 'Zuletzt gesehen' });
    return list.concat(model.categories(LIVE));
  }

  channels(id) {
    if (id === 'favs') return model.favorites;
    if (id === 'recent') return model.recent;
    const c = model.categories(LIVE).find((x) => x.id === id);
    return c ? model.channels(c) : [];
  }

  render() {
    const el = clear(this.el);
    el.className = 'screen live';
    const cats = this.categories;
    if (!this.cat || !cats.some((c) => c.id === this.cat)) this.cat = cats[0] ? cats[0].id : null;
    if (!cats.length) {
      el.appendChild(emptyState(model.catalog.entries.length ? 'Keine Sender' : 'Noch kein Katalog', model.status || null));
      return;
    }
    const channels = this.channels(this.cat);
    this.defaultFid = this.preview ? 'lch-' + this.preview.id : channels[0] ? 'lch-' + channels[0].id : null;
    el.append(
      h('aside.vscroll.cats', { dataset: { sid: 'lcats', pad: '200' } }, cats.map((c) => h('button.cat.focusable', {
        class: c.id === this.cat ? 'on' : '', dataset: { fid: 'lcat-' + c.id }, onclick: () => this.select(c.id),
      }, c.name))),
      h('div.vscroll.channels', { dataset: { sid: 'lch-' + this.cat, pad: '200' } }, channels.map((c, i) => this.channelRow(c, i, channels))),
      this.previewPanel());
  }

  channelRow(c, i, list) {
    const now = (model.epg.cached(c.id) || []).find(isNow);
    const btn = h('button.chrow.focusable', {
      class: this.preview && this.preview.id === c.id ? 'on' : '', dataset: { fid: 'lch-' + c.id },
      onclick: () => this.choose(c, list),
    },
    h('span.num', null, String(i + 1)),
    h('div.chrow-logo', null, img(c.logo, 'logo-pic', h('div.ch-name.small', null, c.name))),
    h('div.chrow-text', null, h('div.chrow-name', null, c.name, model.isFavorite(c) ? icon('star', 'fav') : null), now ? h('div.chrow-now', null, now.title) : null));
    btn.addEventListener('focused', () => this.focusChannel(c));
    return btn;
  }

  previewPanel() {
    const c = this.preview || this.focusedChannel;
    const epg = c ? model.epg.cached(c.id) || [] : [];
    const now = epg.find(isNow);
    return h('div.preview-panel', null,
      h('div.preview-frame', null, this.preview ? null : h('div.preview-hint', null, icon('tv'), h('span', null, 'OK startet die Vorschau'))),
      c ? h('div.preview-info', null,
        h('h2', null, c.name),
        now ? h('div.p-now', null, h('span.time', null, `${hhmm(now.start)}–${hhmm(now.stop)}`), h('b', null, now.title),
          h('div.b-bar', null, h('div.bar', { style: { width: (progressOf(now) * 100).toFixed(1) + '%' } })),
          now.info ? h('p.p-info', null, now.info) : null) : h('p.muted', null, local.get('pref.epg', true) ? 'Keine Programminfos' : 'Programminfos sind aus'),
        h('div.p-next', null, epg.filter((e) => !isNow(e)).slice(0, 4).map((e) => h('div.p-line', null, h('span.time', null, hhmm(e.start)), e.title))),
        h('p.hint', null, h('span.red', null, '●'), model.isFavorite(c) ? ' Favorit entfernen' : ' Als Favorit merken')) : null);
  }

  focusChannel(c) {
    this.focusedChannel = c;
    clearTimeout(this.epgTimer);
    // the guide costs a request per channel: only for channels you stay on
    this.epgTimer = setTimeout(async () => {
      if (!local.get('pref.epg', true) || model.epg.cached(c.id)) { this.updatePanel(); return; }
      await model.epg.load(c.id, model.client);
      if (this.focusedChannel === c) this.updatePanel();
    }, 900);
    this.updatePanel();
  }

  updatePanel() {
    const old = this.el.querySelector('.preview-panel');
    if (old) old.replaceWith(this.previewPanel());
  }

  choose(c, list) {
    if (this.preview && this.preview.id === c.id) {
      // Keep the preview stream alive and expand its display rectangle to full screen.
      this.preview = null;
      document.body.classList.remove('previewing');
      player.playLive(list, c, (this.categories.find((x) => x.id === this.cat) || {}).name, { reuseCurrent: true });
      return;
    }
    const reason = model.lockReason();
    if (reason) { app.toast(reason); return; }
    const url = model.streamURL(liveItem(c));
    if (!url) { app.toast('Zum Abspielen braucht es den IPTV-Zugang (Einstellungen).'); return; }
    this.preview = c;
    model.rememberWatched(c);
    document.body.classList.add('previewing');
    let retried = false;
    engine.on('error', () => {
      // some providers only offer MPEG-TS – one more try, then give up quietly
      if (retried || !this.preview || this.preview.id !== c.id || !model.client) return;
      retried = true;
      const ts = model.client.liveTSURL(c.id);
      if (engine.url === ts) return;
      local.set('pref.liveTs', true);
      engine.open(ts, { rect: PREVIEW }).catch(() => {});
    });
    engine.on('time', null); engine.on('ended', null); engine.on('buffering', null); engine.on('subtitle', null);
    engine.open(url, { rect: PREVIEW }).catch(() => {});
    this.el.querySelectorAll('.chrow.on').forEach((x) => x.classList.remove('on'));
    const row = F.focused();
    if (row) row.classList.add('on');
    this.updatePanel();
  }

  stopPreview() {
    if (!this.preview) return;
    this.preview = null;
    document.body.classList.remove('previewing');
    engine.close();
  }

  select(id) {
    this.cat = id;
    app.refresh();
    const first = this.el.querySelector('.channels .focusable');
    if (first) F.focus(first);
  }

  onKey(code) {
    if (code === K.RED && this.focusedChannel) { model.toggleFavorite(this.focusedChannel); return true; }
    if ((code === K.CH_UP || code === K.CH_DOWN) && this.preview) {
      const list = this.channels(this.cat);
      const i = list.findIndex((c) => c.id === this.preview.id);
      const next = list[(i + (code === K.CH_UP ? -1 : 1) + list.length) % list.length];
      if (next) {
        const row = this.el.querySelector(`[data-fid="lch-${next.id}"]`);
        if (row) F.focus(row);
        this.choose(next, list);
      }
      return true;
    }
    if (code === K.BACK && this.preview) { this.stopPreview(); app.refresh(); return true; }
    return false;
  }

  hide() { this.stopPreview(); }
  destroy() { this.stopPreview(); }
}
