// The app shell: top bar, a stack of screens per tab, dialogs, notices and key handling.
import { h, clear, icon } from './dom.js';
import { K, keyOf } from './keys.js';
import * as F from './focus.js';
import { model, animalIndex, PROFILE_COLORS } from '../core/model.js';

export const TABS = [
  ['home', 'Start'], ['live', 'Live TV'], ['movies', 'Filme'], ['series', 'Serien'],
];

class App {
  constructor() {
    this.root = document.getElementById('app');
    this.stack = [];
    this.dialogs = [];
    this.factories = {};
    this.player = null;
    this.tab = 'home';
  }

  init(factories) {
    this.factories = factories;
    this.topbar = h('header.topbar');
    this.main = h('main.screen-host');
    this.toastEl = h('div.toast');
    clear(this.root).append(this.topbar, this.main, this.toastEl);
    document.addEventListener('keydown', (ev) => this.onKeyDown(ev), true);
    let pending = null;
    model.on(() => {
      // many small changes in a row (a sync, a save) → one refresh
      clearTimeout(pending);
      pending = setTimeout(() => this.refresh(false), 150);
    });
  }

  get screen() { return this.stack[this.stack.length - 1] || null; }

  // MARK: navigation
  openTab(name, params) {
    this.tab = name;
    for (const s of this.stack) s.destroy && s.destroy();
    this.stack = [];
    this.show(this.factories[name](params || {}));
  }

  push(screen) {
    const cur = this.screen;
    if (cur) { cur.lastFid = F.focused() && F.focused().dataset.fid; cur.hide && cur.hide(); }
    this.show(screen);
  }

  show(screen) {
    this.stack.push(screen);
    this.mount(screen);
  }

  mount(screen, fid) {
    clear(this.main);
    if (!screen.el) screen.el = h('section.screen');
    this.main.appendChild(screen.el);
    screen.render();
    this.renderTopbar(screen);
    // the top bar belongs to the focus area too (up from the content reaches the tabs)
    F.setLayer(this.root);
    if (!this.dialogs.length) F.restore(fid || screen.lastFid || screen.defaultFid, this.main);
    if (typeof screen.onShown === 'function') screen.onShown();
  }

  pop() {
    if (this.stack.length <= 1) return false;
    const s = this.stack.pop();
    s.destroy && s.destroy();
    const back = this.screen;
    this.mount(back, back.lastFid);
    return true;
  }

  /** Model changed: the visible screen redraws, keeping the focused element. */
  refresh(force = true) {
    const s = this.screen;
    if (!s || (this.player && this.player.active)) return;
    const fid = F.focused() && F.focused().dataset.fid;
    if (!force && s.live === false) { this.renderTopbar(s); this.refocusBar(fid); return; }
    const scroll = Array.from(s.el.querySelectorAll('.vscroll, .hscroll')).map((x) => [x.dataset.sid, x.scrollTop, x.scrollLeft]);
    s.render();
    for (const [sid, top, left] of scroll) {
      const x = sid && s.el.querySelector(`[data-sid="${sid}"]`);
      if (x) { x.scrollTop = top; x.scrollLeft = left; }
    }
    this.renderTopbar(s);
    if (!this.dialogs.length) {
      const el = fid && F.candidates(this.main).find((c) => c.dataset.fid === fid);
      const inBar = F.focused() && this.topbar.contains(F.focused());
      if (el) F.focus(el, { noScroll: true });
      else if (fid && fid.startsWith('tab-')) this.refocusBar(fid);
      else if (!inBar) F.restore(null, this.main);
    }
  }

  refocusBar(fid) {
    if (!fid || !fid.startsWith('tab-')) return;
    const t = F.candidates(this.topbar).find((c) => c.dataset.fid === fid);
    if (t) F.focus(t, { noScroll: true });
  }

  renderTopbar(screen) {
    clear(this.topbar);
    if (screen && screen.fullscreen) { this.topbar.style.display = 'none'; this.main.classList.add('full'); return; }
    this.topbar.style.display = '';
    this.main.classList.remove('full');
    const p = model.profile;
    const tabs = TABS.map(([id, label]) => h('button.tab.focusable', {
      class: this.tab === id ? 'active' : '', dataset: { fid: 'tab-' + id }, onclick: () => this.openTab(id),
    }, label));
    this.topbar.append(
      h('div.logo', null, 'Couchpotato'),
      h('nav.tabs', { dataset: { row: 'tabs' } }, tabs,
        h('button.tab.icon-tab.focusable', { class: this.tab === 'search' ? 'active' : '', dataset: { fid: 'tab-search' }, onclick: () => this.openTab('search') }, icon('search')),
        h('button.tab.icon-tab.focusable', { class: this.tab === 'settings' ? 'active' : '', dataset: { fid: 'tab-settings' }, onclick: () => this.openTab('settings') }, icon('gear')),
      ),
      h('div.top-right', null,
        model.status ? h('div.status', null, model.status) : null,
        avatar(p, 'small focusable', { dataset: { fid: 'tab-profile' }, onclick: () => this.openProfiles() }),
      ),
    );
  }

  openProfiles() { this.push(this.factories.profiles({})); }

  // MARK: dialogs
  dialog(content, { onBack, cls = '' } = {}) {
    const box = h('div.dialog-backdrop', null, h('div.dialog', { class: cls }, content));
    document.body.appendChild(box);
    const prevFid = F.focused() && F.focused().dataset.fid;
    const d = { box, onBack, prevFid, close: () => this.closeDialog(d) };
    this.dialogs.push(d);
    F.setLayer(box);
    F.restore(null, box);
    return d;
  }

  closeDialog(d) {
    const i = this.dialogs.indexOf(d);
    if (i < 0) return;
    this.dialogs.splice(i, 1);
    d.box.remove();
    const top = this.dialogs[this.dialogs.length - 1];
    if (top) { F.setLayer(top.box); F.restore(null, top.box); return; }
    if (this.player && this.player.active) { F.setLayer(this.player.el); this.player.refocus && this.player.refocus(); return; }
    F.setLayer(this.root);
    const el = d.prevFid && F.candidates(this.root).find((c) => c.dataset.fid === d.prevFid);
    if (el) F.focus(el); else F.restore(null, this.main);
  }

  confirm(title, text, yes = 'OK', no = 'Abbrechen') {
    return new Promise((resolve) => {
      let d;
      const done = (v) => { d.close(); resolve(v); };
      d = this.dialog([
        h('h2', null, title), text ? h('p.muted', null, text) : null,
        h('div.buttons', { dataset: { row: 'dlg' } },
          h('button.btn.primary.focusable', { dataset: { autofocus: '' }, onclick: () => done(true) }, yes),
          no ? h('button.btn.focusable', { onclick: () => done(false) }, no) : null),
      ], { onBack: () => done(false) });
    });
  }

  toast(msg, ms = 3500) {
    this.toastEl.textContent = msg;
    this.toastEl.classList.add('show');
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toastEl.classList.remove('show'), ms);
  }

  // MARK: keys
  onKeyDown(ev) {
    const code = keyOf(ev);
    const t = ev.target;
    // typing in a text field: the TV's keyboard owns the keys until it is closed
    if (t && t.tagName === 'INPUT') {
      if (code === K.IME_DONE || code === K.ENTER || code === K.IME_CANCEL || code === K.BACK || code === K.DOWN || code === K.UP) {
        ev.preventDefault();
        t.blur();
        if (code === K.ENTER || code === K.IME_DONE) t.dispatchEvent(new CustomEvent('submit'));
        if (code === K.DOWN) F.move('down');
        if (code === K.UP) F.move('up');
      }
      return;
    }
    if (!code) return;
    if (this.dialogs.length) {
      const d = this.dialogs[this.dialogs.length - 1];
      if (code === K.BACK) { ev.preventDefault(); if (d.onBack) d.onBack(); else d.close(); return; }
      if (d.onKey && d.onKey(code)) { ev.preventDefault(); return; }
      if (this.navigate(code)) ev.preventDefault();
      return;
    }
    if (this.player && this.player.active) {
      ev.preventDefault();
      this.player.onKey(code);
      return;
    }
    const s = this.screen;
    if (s && s.onKey && s.onKey(code)) { ev.preventDefault(); return; }
    if (code === K.BACK) {
      ev.preventDefault();
      if (!this.pop()) {
        if (this.tab !== 'home') this.openTab('home');
        else this.confirm('Couchpotato beenden?', null, 'Beenden').then((yes) => yes && exitApp());
      }
      return;
    }
    if (this.navigate(code)) ev.preventDefault();
  }

  navigate(code) {
    switch (code) {
      case K.LEFT: F.move('left'); return true;
      case K.RIGHT: F.move('right'); return true;
      case K.UP: F.move('up'); return true;
      case K.DOWN: F.move('down'); return true;
      case K.ENTER: {
        const el = F.focused();
        if (el) el.click();
        return true;
      }
      default: return false;
    }
  }
}

export function exitApp() {
  try { tizen.application.getCurrentApplication().exit(); } catch (e) { window.close(); }
}

/** Profile picture: one of the 12 animals on the profile's colour, like on the TVs and the iPhone. */
export function avatar(p, cls = '', props = {}) {
  const color = PROFILE_COLORS[(p.color | 0) % PROFILE_COLORS.length];
  return h('div.avatar', Object.assign({ class: cls, style: { backgroundColor: color } }, props),
    h('img', { src: `img/animal${animalIndex(p)}.png`, alt: '' }));
}

export const app = new App();
