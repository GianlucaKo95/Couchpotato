// Search across films, series and channels – in the catalogue on this TV, no request to the provider.
import { h, clear } from '../dom.js';
import { app } from '../app.js';
import { model } from '../../core/model.js';
import { row, posterCard, channelCard } from '../parts.js';
import { field } from '../widgets.js';
import { openEntry } from './actions.js';
import { player } from '../player.js';

export class SearchScreen {
  constructor() { this.query = ''; this.defaultFid = 'q'; this.live = false; }

  render() {
    const el = clear(this.el);
    el.className = 'screen search';
    const res = this.query.trim().length >= 2 ? model.search(this.query) : null;
    const channels = res ? res.l.map((e) => ({ id: e.itemId, name: e.name, logo: e.image, cat: e.cat })) : [];
    let timer;
    el.append(
      h('div.search-bar', null, field('Suche', this.query, {
        fid: 'q', placeholder: 'Film, Serie oder Sender',
        onchange: (v) => { this.query = v; clearTimeout(timer); timer = setTimeout(() => this.update(), 400); },
        onsubmit: (v) => { this.query = v; this.update(); },
      })),
      h('div.vscroll.results', { dataset: { sid: 'results', pad: '160' } },
        res ? [
          row('rm', 'Filme', res.m.map((e) => posterCard(e, () => openEntry(e), 'sm-' + e.id))),
          row('rs', 'Serien', res.s.map((e) => posterCard(e, () => openEntry(e), 'ss-' + e.id))),
          row('rl', 'Sender', channels.map((c) => channelCard(c, () => player.playLive(channels, c, 'Suche'), 'sl-' + c.id))),
          !res.m.length && !res.s.length && !res.l.length ? h('p.muted.pad', null, 'Nichts gefunden.') : null,
        ] : h('p.muted.pad', null, 'OK drücken und mit der Bildschirmtastatur tippen.')));
  }

  update() {
    // keep typing undisturbed: only the results are redrawn
    const old = this.el.querySelector('.results');
    const input = this.el.querySelector('input');
    const keep = document.activeElement === input;
    const scratch = document.createElement('section');
    const saved = this.el;
    this.el = scratch;
    this.render();
    this.el = saved;
    const fresh = scratch.querySelector('.results');
    if (old && fresh) old.replaceWith(fresh);
    if (keep && input) input.focus();
  }
}
