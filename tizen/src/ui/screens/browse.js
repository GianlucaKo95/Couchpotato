// Films / Serien: categories on the left, posters on the right (loaded in pages as you scroll).
import { h, clear } from '../dom.js';
import { app } from '../app.js';
import * as F from '../focus.js';
import { model } from '../../core/model.js';
import { MOVIE } from '../../core/catalog.js';
import { posterCard, emptyState } from '../parts.js';
import { openEntry } from './actions.js';

const PAGE = 60;

export class BrowseScreen {
  constructor(kind) {
    this.kind = kind;
    this.cat = null; // null = Neu
    this.shown = PAGE;
    this.defaultFid = 'cat-new';
  }

  get list() {
    if (this.cat === 'mylist') return model.myList.filter((e) => e.kind === this.kind);
    if (!this.cat) return model.newest(this.kind, 300);
    const c = model.categories(this.kind).find((x) => x.id === this.cat);
    return c ? model.titlesIn(this.kind, c) : [];
  }

  render() {
    const el = clear(this.el);
    el.className = 'screen browse';
    const cats = model.categories(this.kind);
    const catBtn = (id, name, fid) => h('button.cat.focusable', {
      class: this.cat === id ? 'on' : '', dataset: { fid },
      onclick: () => this.select(id),
      onfocused: () => {},
    }, name);
    const list = this.list;
    const items = list.slice(0, this.shown);
    const grid = h('div.vscroll.grid-scroll', { dataset: { sid: 'grid-' + this.kind, pad: '140' } },
      h('h1.grid-title', null, this.title()),
      items.length ? h('div.grid', null, items.map((e, i) => {
        const card = posterCard(e, () => openEntry(e), 'g-' + e.id);
        if (i >= items.length - 12 && items.length < list.length) card.addEventListener('focused', () => this.more());
        return card;
      })) : emptyState(model.catalog.entries.length ? 'Hier ist nichts' : 'Noch kein Katalog', model.status || null));
    el.append(
      h('aside.vscroll.cats', { dataset: { sid: 'cats-' + this.kind, pad: '200' } },
        catBtn(null, 'Neu', 'cat-new'),
        catBtn('mylist', 'Meine Liste', 'cat-mylist'),
        cats.map((c) => catBtn(c.id, c.name, 'cat-' + c.id))),
      grid);
  }

  title() {
    if (this.cat === 'mylist') return 'Meine Liste';
    if (!this.cat) return this.kind === MOVIE ? 'Neue Filme' : 'Neue Serien';
    const c = model.categories(this.kind).find((x) => x.id === this.cat);
    return c ? c.name : '';
  }

  select(id) {
    this.cat = id;
    this.shown = PAGE;
    app.refresh();
    const grid = this.el.querySelector('.grid-scroll');
    if (grid) grid.scrollTop = 0;
    const first = this.el.querySelector('.grid .focusable');
    if (first) F.focus(first);
  }

  more() {
    if (this.shown >= this.list.length) return;
    this.shown += PAGE;
    app.refresh();
  }
}
