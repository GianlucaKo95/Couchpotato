// Full-screen player for films, episodes and live TV – remote first: OK shows the controls,
// left/right seek (10 s, faster when held), up/down and P+/P- zap on live TV.
import { h, clear, icon, clock } from './dom.js';
import { K } from './keys.js';
import * as F from './focus.js';
import { app } from './app.js';
import { engine } from './engine.js';
import { model, isFinished, liveItem } from '../core/model.js';
import { hhmm, isNow, progressOf } from '../core/epg.js';
import { local } from '../core/storage.js';
import { img } from './parts.js';

class Player {
  constructor() {
    this.active = false;
    this.el = null;
  }

  // MARK: start / stop
  playVod(item, start = 0, queue = []) {
    const reason = model.lockReason();
    if (reason) { app.toast(reason); return; }
    const url = model.streamURL(item);
    if (!url) { app.toast('Zum Abspielen braucht es den IPTV-Zugang (Einstellungen).'); return; }
    this.begin('vod');
    this.item = item;
    this.queue = queue.slice();
    this.lastSave = 0;
    this.time = start;
    this.renderVod();
    this.load(url, start);
  }

  playLive(list, channel, category) {
    const reason = model.lockReason();
    if (reason) { app.toast(reason); return; }
    if (!model.client) { app.toast('Zum Abspielen braucht es den IPTV-Zugang (Einstellungen).'); return; }
    this.begin('live');
    this.list = list.length ? list : [channel];
    this.category = category || 'Live TV';
    this.tune(channel);
  }

  begin(mode) {
    if (this.active) this.stop(true);
    this.mode = mode;
    this.active = true;
    this.retried = false;
    document.body.classList.add('playing');
    this.el = h('div.player-ui');
    document.body.appendChild(this.el);
    F.setLayer(this.el);
    engine.on('time', (t) => { this.time = t; this.tick(); });
    engine.on('buffering', (b) => this.el && this.el.classList.toggle('buffering', b));
    engine.on('ended', () => this.ended());
    engine.on('error', (e) => this.failed(e));
    engine.on('subtitle', (text) => this.showSubtitle(text));
    engine.setFit(local.get('pref.fit', 'fit'));
    clearInterval(this.watchTimer);
    this.watchTimer = setInterval(() => this.countWatchTime(), 30000);
  }

  async load(url, start) {
    this.el.classList.add('buffering');
    try {
      await engine.open(url, { start });
    } catch (e) {
      /* reported through the error listener */
    }
  }

  /** Leaves the player; saves the position and hands it over to the iPhone. */
  stop(silent) {
    if (!this.active) return;
    if (this.mode === 'vod') this.save(true);
    const item = this.mode === 'vod' ? this.item : this.channel && liveItem(this.channel);
    const pos = engine.time || this.time || 0, dur = engine.duration;
    engine.close();
    clearInterval(this.watchTimer);
    clearTimeout(this.hideTimer);
    clearTimeout(this.seekTimer);
    this.active = false;
    document.body.classList.remove('playing');
    if (this.el) this.el.remove();
    this.el = null;
    if (item) model.handOver(item, pos, dur);
    if (!silent) {
      F.setLayer(app.main);
      app.refresh();
    }
  }

  /** The TV is switched off or another app comes to the front. */
  suspend() {
    if (!this.active) return;
    if (this.mode === 'vod') this.save(true);
    const item = this.mode === 'vod' ? this.item : this.channel && liveItem(this.channel);
    if (item) model.handOver(item, engine.time || this.time, engine.duration);
    engine.pause();
  }

  countWatchTime() {
    if (!this.active || engine.paused) return;
    model.addWatchTime(30);
    const reason = model.lockReason();
    if (reason) { this.stop(); app.toast(reason, 6000); }
  }

  failed(e) {
    if (!this.active) return;
    if (this.mode === 'live' && !this.retried && this.channel) {
      // some providers only offer MPEG-TS
      this.retried = true;
      const c = model.client;
      if (c) { this.load(c.liveTSURL(this.channel.id), 0); return; }
    }
    app.toast('Wiedergabe nicht möglich' + (e ? ` (${String(e).slice(0, 60)})` : ''), 6000);
    this.stop();
  }

  ended() {
    if (this.mode !== 'vod') return;
    const dur = engine.duration || this.time;
    const next = this.queue[0];
    model.saveProgress(this.item, dur, dur, next);
    if (next) this.playNext();
    else this.stop();
  }

  playNext() {
    const next = this.queue.shift();
    if (!next) return;
    this.save(true);
    const url = model.streamURL(next);
    this.item = next;
    this.time = 0;
    this.lastSave = 0;
    this.renderVod();
    this.load(url, model.startPosition(next.key));
  }

  save(force) {
    if (this.mode !== 'vod' || !this.item) return;
    const now = Date.now();
    if (!force && now - this.lastSave < 10000) return;
    const pos = engine.time || this.time, dur = engine.duration;
    if (!(pos > 0)) return;
    this.lastSave = now;
    model.saveProgress(this.item, pos, dur, isFinished(pos, dur) ? this.queue[0] : null);
  }

  // MARK: VOD controls
  renderVod() {
    clear(this.el);
    this.el.className = 'player-ui vod';
    this.subs = h('div.subs');
    this.timeEl = h('span.t-now', null, '0:00');
    this.durEl = h('span.t-dur', null, '');
    this.fill = h('div.fill');
    this.knob = h('div.knob');
    this.scrub = h('div.scrub.focusable', { dataset: { fid: 'scrub' } }, h('div.track', null, this.fill, this.knob));
    this.nextBtn = this.queue.length ? h('button.btn.focusable.next-btn', { dataset: { fid: 'next' }, onclick: () => this.playNext() }, icon('next'), h('span', null, 'Nächste Folge')) : null;
    this.playBtn = h('button.round.focusable', { dataset: { fid: 'play' }, onclick: () => { engine.toggle(); this.update(); } }, icon('pause'));
    this.controls = h('div.controls', null,
      h('div.top-info', null, h('h1', null, this.item.title), this.item.subtitle ? h('p', null, this.item.subtitle) : null),
      h('div.bottom', null,
        h('div.timeline', { dataset: { row: 'timeline' } }, this.timeEl, this.scrub, this.durEl),
        h('div.buttons', { dataset: { row: 'pbuttons' } },
          h('button.round.focusable', { dataset: { fid: 'rw' }, onclick: () => this.jump(-10) }, h('span.jump', null, '−10')),
          this.playBtn,
          h('button.round.focusable', { dataset: { fid: 'ff' }, onclick: () => this.jump(30) }, h('span.jump', null, '+30')),
          h('div.spacer'),
          h('button.btn.focusable', { dataset: { fid: 'tracks' }, onclick: () => this.tracksPanel() }, icon('sub'), h('span', null, 'Ton & Untertitel')),
          h('button.btn.focusable', { dataset: { fid: 'fit' }, onclick: () => this.fitPanel() }, h('span', null, 'Bildformat')),
          this.nextBtn)));
    this.spinner = h('div.spinner');
    this.el.append(this.subs, this.spinner, this.controls);
    this.showControls('scrub');
  }

  showControls(fid) {
    if (!this.el) return;
    this.el.classList.add('show');
    F.setLayer(this.el);
    if (fid || !F.focused() || !this.el.contains(F.focused())) F.restore(fid || 'scrub', this.el);
    this.update();
    this.armHide();
  }

  armHide() {
    clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(() => { if (!engine.paused && this.pendingSeek == null) this.hideControls(); }, 5000);
  }

  hideControls() { if (this.el) this.el.classList.remove('show'); }
  get controlsShown() { return this.el && this.el.classList.contains('show'); }
  refocus() { this.showControls(); }

  tick() {
    if (this.mode === 'vod') {
      this.save(false);
      if (this.controlsShown) this.update();
      // end credits: offer the next episode
      if (this.nextBtn && isFinished(this.time, engine.duration) && !this.creditsShown) {
        this.creditsShown = true;
        this.showControls('next');
      }
    }
  }

  update() {
    if (this.mode !== 'vod' || !this.el) return;
    const dur = engine.duration, t = this.pendingSeek != null ? this.pendingSeek : engine.time || this.time;
    const frac = dur > 0 ? Math.min(Math.max(t / dur, 0), 1) : 0;
    this.fill.style.width = (frac * 100).toFixed(2) + '%';
    this.knob.style.left = (frac * 100).toFixed(2) + '%';
    this.timeEl.textContent = clock(t);
    this.durEl.textContent = dur > 0 ? '−' + clock(dur - t) : '';
    clear(this.playBtn).appendChild(icon(engine.paused ? 'play' : 'pause'));
  }

  jump(sec) {
    const base = this.pendingSeek != null ? this.pendingSeek : engine.time || this.time;
    const dur = engine.duration;
    this.pendingSeek = Math.max(0, dur > 0 ? Math.min(base + sec, dur - 3) : base + sec);
    this.update();
    clearTimeout(this.seekTimer);
    this.seekTimer = setTimeout(() => {
      engine.seek(this.pendingSeek);
      this.time = this.pendingSeek;
      this.pendingSeek = null;
      this.update();
      this.armHide();
    }, 700);
  }

  seekStep() {
    // held left/right: 10 s steps, then 30 s, then 60 s
    const now = Date.now();
    this.repeat = now - (this.lastStep || 0) < 400 ? (this.repeat || 0) + 1 : 0;
    this.lastStep = now;
    return this.repeat > 12 ? 60 : this.repeat > 4 ? 30 : 10;
  }

  tracksPanel() {
    const tracks = engine.tracks();
    const audio = tracks.filter((t) => t.kind === 'AUDIO'), text = tracks.filter((t) => t.kind === 'TEXT');
    const curA = engine.currentTrack('AUDIO'), curT = local.get('pref.subsOff', true) ? -1 : engine.currentTrack('TEXT');
    let d;
    const pick = (kind, index) => {
      if (kind === 'TEXT') local.set('pref.subsOff', index < 0);
      engine.selectTrack(kind, index);
      d.close();
    };
    const opt = (label, on, fn) => h('button.option.focusable', { class: on ? 'on' : '', onclick: fn }, on ? icon('check') : h('span.ic-space'), h('span', null, label));
    d = app.dialog([
      h('h2', null, 'Ton & Untertitel'),
      h('div.cols', null,
        h('div.col', null, h('h3', null, 'Tonspur'), audio.length ? audio.map((t) => opt(t.label, t.index === curA, () => pick('AUDIO', t.index))) : h('p.muted', null, 'Nur eine Tonspur')),
        h('div.col', null, h('h3', null, 'Untertitel'), opt('Aus', curT < 0, () => pick('TEXT', -1)), text.map((t) => opt(t.label, t.index === curT, () => pick('TEXT', t.index))))),
    ], { cls: 'panel-right' });
  }

  fitPanel() {
    const cur = local.get('pref.fit', 'fit');
    let d;
    const pick = (m) => { local.set('pref.fit', m); engine.setFit(m); d.close(); };
    const opt = (m, label, sub) => h('button.option.focusable', { class: cur === m ? 'on' : '', onclick: () => pick(m) },
      cur === m ? icon('check') : h('span.ic-space'), h('span', null, label, h('small', null, sub)));
    d = app.dialog([h('h2', null, 'Bildformat'),
      opt('fit', 'Ganzes Bild', 'mit schwarzen Rändern, nichts abgeschnitten'),
      opt('zoom', 'Bildschirm füllen', 'ohne Ränder, Bild ggf. gestreckt'),
      opt('auto', 'Automatisch', 'wie vom Sender vorgesehen')], { cls: 'panel-right' });
  }

  showSubtitle(text) {
    if (!this.subs) return;
    this.subs.textContent = text ? String(text).replace(/<[^>]+>/g, '') : '';
  }

  // MARK: live TV
  tune(channel) {
    this.channel = channel;
    this.retried = false;
    model.rememberWatched(channel);
    this.renderLive();
    this.load(model.streamURL(liveItem(channel)), 0);
    this.loadEpg();
  }

  zap(step) {
    const i = this.list.findIndex((c) => c.id === this.channel.id);
    const next = this.list[(i + step + this.list.length) % this.list.length];
    if (next) this.tune(next);
  }

  async loadEpg() {
    const id = this.channel.id;
    this.epg = model.epg.cached(id) || [];
    this.renderBanner();
    if (!local.get('pref.epg', true)) return;
    const entries = await model.epg.load(id, model.client);
    if (this.active && this.channel && this.channel.id === id) { this.epg = entries; this.renderBanner(); }
  }

  renderLive() {
    clear(this.el);
    this.el.className = 'player-ui live';
    this.subs = h('div.subs');
    this.banner = h('div.banner');
    this.spinner = h('div.spinner');
    this.el.append(this.subs, this.spinner, this.banner);
    this.renderBanner();
    this.flashBanner();
  }

  renderBanner() {
    if (!this.banner) return;
    const c = this.channel;
    const now = (this.epg || []).find(isNow), next = (this.epg || []).find((e) => now && e.start >= now.stop);
    const pos = this.list.findIndex((x) => x.id === c.id) + 1;
    clear(this.banner).append(
      h('div.b-logo', null, img(c.logo, 'logo-pic', h('div.ch-name', null, c.name))),
      h('div.b-text', null,
        h('div.b-channel', null, h('span.num', null, String(pos)), c.name, model.isFavorite(c) ? icon('star', 'fav') : null),
        now ? h('div.b-now', null, h('span.time', null, `${hhmm(now.start)}–${hhmm(now.stop)}`), now.title) : h('div.b-now.muted', null, 'Keine Programminfos'),
        now ? h('div.b-bar', null, h('div.bar', { style: { width: (progressOf(now) * 100).toFixed(1) + '%' } })) : null,
        next ? h('div.b-next', null, h('span.time', null, 'Danach ' + hhmm(next.start)), next.title) : null),
      h('div.b-hints', null, h('span', null, 'OK  Senderliste'), h('span', null, '▲ ▼  umschalten'),
        h('span', null, h('span.red', null, '● '), model.isFavorite(c) ? 'Favorit entfernen' : 'Als Favorit merken')));
  }

  flashBanner() {
    if (!this.el) return;
    this.el.classList.add('show');
    clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(() => this.el && this.el.classList.remove('show'), 5000);
  }

  channelList() {
    let d;
    const items = this.list.map((c, i) => h('button.option.focusable.ch-option', {
      class: c.id === this.channel.id ? 'on' : '', dataset: { fid: 'lc-' + c.id, autofocus: c.id === this.channel.id ? '' : null },
      onclick: () => { d.close(); this.tune(c); },
    }, h('span.num', null, String(i + 1)), h('span.name', null, c.name), model.isFavorite(c) ? icon('star', 'fav') : null));
    d = app.dialog([h('h2', null, this.category), h('div.vscroll.list', { dataset: { pad: '200' } }, items)], { cls: 'panel-left' });
    const cur = d.box.querySelector('.ch-option.on');
    if (cur) F.focus(cur);
  }

  // MARK: keys
  onKey(code) {
    if (this.mode === 'live') return this.liveKey(code);
    const shown = this.controlsShown;
    switch (code) {
      case K.BACK:
        if (shown && !engine.paused) { this.hideControls(); return; }
        this.stop();
        return;
      case K.PLAYPAUSE: engine.toggle(); this.showControls(); return;
      case K.PLAY: engine.play(); this.showControls(); return;
      case K.PAUSE: engine.pause(); this.showControls(); return;
      case K.STOP: this.stop(); return;
      case K.FF: this.showControls('scrub'); this.jump(30); return;
      case K.RW: this.showControls('scrub'); this.jump(-10); return;
      case K.NEXT: if (this.queue.length) this.playNext(); return;
      case K.LEFT: case K.RIGHT: {
        const onScrub = shown && F.focused() && F.focused().dataset.fid === 'scrub';
        if (!shown || onScrub) {
          if (!shown) this.showControls('scrub');
          const step = this.seekStep();
          this.jump(code === K.LEFT ? -step : step);
          return;
        }
        F.move(code === K.LEFT ? 'left' : 'right');
        this.armHide();
        return;
      }
      case K.UP: case K.DOWN:
        if (!shown) { this.showControls(); return; }
        F.move(code === K.UP ? 'up' : 'down');
        this.armHide();
        return;
      case K.ENTER:
        if (!shown) { this.showControls(); return; }
        if (F.focused() && F.focused().dataset.fid === 'scrub') { engine.toggle(); this.update(); this.armHide(); return; }
        if (F.focused()) F.focused().click();
        this.armHide();
        return;
      default:
    }
  }

  liveKey(code) {
    switch (code) {
      case K.BACK: case K.STOP: this.stop(); return;
      case K.UP: case K.CH_UP: this.zap(-1); return;
      case K.DOWN: case K.CH_DOWN: this.zap(1); return;
      case K.ENTER: this.channelList(); return;
      case K.INFO: case K.LEFT: case K.RIGHT: this.flashBanner(); return;
      case K.RED: model.toggleFavorite(this.channel); this.renderBanner(); this.flashBanner(); return;
      case K.PLAYPAUSE: engine.toggle(); return;
      default:
    }
  }
}

export const player = new Player();
