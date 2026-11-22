// Start: a big picture of what's next, then the rows – Weiterschauen, Live TV, Für dich, Meine Liste, Neu.
import { h, clear, icon } from '../dom.js';
import { app } from '../app.js';
import { model } from '../../core/model.js';
import { MOVIE, SERIES } from '../../core/catalog.js';
import { row, posterCard, continueCard, channelCard, img, button, emptyState } from '../parts.js';
import { player } from '../player.js';
import { openEntry, resume } from './actions.js';

export class HomeScreen {
  constructor() { this.defaultFid = null; }

  render() {
    const el = clear(this.el);
    el.className = 'screen home';
    if (!model.login && !model.demo) {
      el.appendChild(emptyState('Willkommen bei Couchpotato',
        'Verbinde diesen Fernseher mit deinem Haushalt oder trage deinen IPTV-Zugang ein – dann geht es los.',
        h('div.buttons', { dataset: { row: 'welcome' } },
          button('Haushalt beitreten', () => app.openTab('settings', { page: 'household' }), { primary: true, autofocus: true, fid: 'w-house' }),
          button('IPTV-Zugang eintragen', () => app.openTab('settings', { page: 'iptv' }), { fid: 'w-iptv' }),
          button('Ohne Zugang ausprobieren', () => model.setDemo(true), { fid: 'w-demo' })),
        h('p.muted.small', null, 'Couchpotato enthält keine Sender, Filme oder Serien. Du nutzt die App mit deinem eigenen IPTV-Zugang.')));
      return;
    }
    const cont = model.continueWatching;
    const forYou = model.forYou.items;
    const live = model.favorites.length ? model.favorites : model.recent;
    const hero = cont[0] ? { kind: 'continue', p: cont[0] } : forYou[0] ? { kind: 'entry', e: forYou[0] } : null;
    const scroller = h('div.vscroll.home-scroll', { dataset: { sid: 'home', pad: '160' } },
      hero ? this.hero(hero) : null,
      row('continue', 'Weiterschauen', cont.map((p) => continueCard(p, () => resume(p)))),
      row('live', model.favorites.length ? 'Lieblingssender' : 'Zuletzt gesehen', live.map((c) => channelCard(c, () => player.playLive(live, c, 'Live TV')))),
      row('foryou', 'Für dich', forYou.map((e) => posterCard(e, () => openEntry(e), 'fy-' + e.id))),
      row('mylist', 'Meine Liste', model.myList.map((e) => posterCard(e, () => openEntry(e), 'ml-' + e.id))),
      row('newm', 'Neue Filme', model.newest(MOVIE).map((e) => posterCard(e, () => openEntry(e), 'nm-' + e.id))),
      row('news', 'Neue Serien', model.newest(SERIES).map((e) => posterCard(e, () => openEntry(e), 'ns-' + e.id))),
    );
    if (!scroller.querySelector('.row')) {
      // nothing to show yet: say why
      el.appendChild(!model.catalog.entries.length
        ? emptyState(model.catalogBusy ? 'Katalog wird geladen …' : 'Noch kein Katalog', model.status || null)
        : model.profile.kid
          ? emptyState('Hier ist noch nichts freigegeben', 'Eltern geben Kategorien für Kinderprofile unter Einstellungen → Kategorien frei („Für Kinder“).',
            h('div.buttons', { dataset: { row: 'kidempty' } }, button('Profil wechseln', () => app.openProfiles(), { primary: true, fid: 'k-switch' })))
          : emptyState('Hier ist nichts', 'Alle Kategorien sind ausgeblendet.'));
      return;
    }
    el.appendChild(scroller);
  }

  hero(hero) {
    if (hero.kind === 'continue') {
      const p = hero.p;
      const e = p.item.kind === 'episode' ? model.entry(SERIES, p.item.seriesId) : model.entry(MOVIE, p.item.streamId);
      return h('section.hero', null,
        img(p.item.image || (e && e.image), 'hero-pic'),
        h('div.hero-shade'),
        h('div.hero-text', null,
          h('div.eyebrow', null, p.upNext ? 'Nächste Folge' : 'Weiterschauen'),
          h('h1', null, p.item.title),
          p.item.subtitle ? h('p.hero-sub', null, p.item.subtitle) : null,
          h('div.buttons', { dataset: { row: 'hero' } },
            button(p.upNext ? 'Abspielen' : 'Fortsetzen', () => resume(p), { primary: true, icon: 'play', fid: 'hero-play' }),
            e ? button('Details', () => openEntry(e), { fid: 'hero-info' }) : null)));
    }
    const e = hero.e;
    return h('section.hero', null,
      img(e.image, 'hero-pic'),
      h('div.hero-shade'),
      h('div.hero-text', null,
        h('div.eyebrow', null, 'Für dich'),
        h('h1', null, e.name),
        h('p.hero-sub', null, [e.year, e.genre, e.rating > 0 ? '★ ' + e.rating.toFixed(1) : null].filter(Boolean).join(' · ')),
        h('div.buttons', { dataset: { row: 'hero' } },
          button('Ansehen', () => openEntry(e), { primary: true, icon: 'play', fid: 'hero-play' }))));
  }
}
