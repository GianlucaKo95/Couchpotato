// Settings: IPTV access, household, profiles, categories, playback, parents' PIN, info.
import { h, clear, icon } from '../dom.js';
import { app, avatar } from '../app.js';
import * as F from '../focus.js';
import { model, ANIMALS, PROFILE_COLORS } from '../../core/model.js';
import { MOVIE, SERIES, LIVE } from '../../core/catalog.js';
import { XtreamClient, loginFromLink, loginValid } from '../../core/xtream.js';
import { gate } from '../../core/gate.js';
import { local } from '../../core/storage.js';
import { HouseholdSync } from '../../core/household.js';
import { CONFIG } from '../../core/config.js';
import { field, toggle, action, choice, askPin, section } from '../widgets.js';

const PAGES = [
  ['iptv', 'IPTV-Zugang'], ['household', 'Haushalt'], ['profiles', 'Profile'], ['categories', 'Kategorien'],
  ['playback', 'Wiedergabe'], ['parents', 'Kinder & PIN'], ['about', 'Info'],
];
const KIND_NAMES = { [MOVIE]: 'Filme', [SERIES]: 'Serien', [LIVE]: 'Live TV' };
const when = (t) => (t ? new Date(t).toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : 'nie');

export class SettingsScreen {
  constructor(params = {}) {
    this.params = params;
    this.page = params.page || 'iptv';
    this.defaultFid = 'page-' + this.page;
    this.draft = null;
    this.editing = null;   // profile being edited
    this.catKind = MOVIE;
  }

  get live() { return !this.editing && this.page !== 'iptv'; }

  /** Opened for a specific page (e.g. from the welcome screen): start in its content. */
  onShown() {
    if (!this.params.page || this.entered) return;
    this.entered = true;
    const first = this.el.querySelector('.settings-body .focusable');
    if (first) F.focus(first);
  }

  render() {
    if (model.profile.kid) return this.renderKid();
    const el = clear(this.el);
    el.className = 'screen settings';
    el.append(
      h('aside.settings-menu', null, PAGES.map(([id, label]) => h('button.cat.focusable', {
        class: this.page === id ? 'on' : '', dataset: { fid: 'page-' + id }, onclick: () => this.open(id),
      }, label))),
      h('div.vscroll.settings-body', { dataset: { sid: 'sb-' + this.page, pad: '180' } }, this.body()));
  }

  renderKid() {
    const el = clear(this.el);
    el.className = 'screen settings kid';
    el.append(h('div.empty', null, icon('lock', 'big-icon'), h('h2', null, 'Einstellungen sind für Eltern'),
      h('button.btn.primary.focusable', { dataset: { autofocus: '' }, onclick: () => app.openProfiles() }, 'Profil wechseln')));
  }

  open(id) {
    this.page = id;
    this.editing = null;
    app.refresh();
    const first = this.el.querySelector('.settings-body .focusable');
    if (first) F.focus(first);
  }

  body() {
    switch (this.page) {
      case 'iptv': return this.iptv();
      case 'household': return this.household();
      case 'profiles': return this.editing ? this.profileEditor() : this.profiles();
      case 'categories': return this.categories();
      case 'playback': return this.playback();
      case 'parents': return this.parents();
      default: return this.about();
    }
  }

  // MARK: IPTV
  iptv() {
    const d = this.draft || (this.draft = Object.assign({ server: '', user: '', password: '' }, model.login || {}));
    return [
      section('IPTV-Zugang',
        h('p.muted', null, 'Die Zugangsdaten deines Anbieters (Xtream Codes). Sie bleiben auf diesem Fernseher.'),
        field('Server', d.server, { fid: 'srv', placeholder: 'http://anbieter.example:8080', onchange: (v) => { d.server = v; } }),
        field('Benutzername', d.user, { fid: 'usr', onchange: (v) => { d.user = v; } }),
        field('Passwort', d.password, { fid: 'pwd', type: 'password', onchange: (v) => { d.password = v; } }),
        field('… oder Link vom Anbieter einfügen', '', { fid: 'lnk', placeholder: 'http://…/get.php?username=…&password=…', onsubmit: (v) => {
          const l = loginFromLink(v);
          if (!l) { app.toast('In diesem Link stecken keine Zugangsdaten'); return; }
          this.draft = l;
          app.refresh();
        } }),
        h('div.buttons', { dataset: { row: 'iptvb' } },
          h('button.btn.primary.focusable', { dataset: { fid: 'save' }, onclick: () => this.saveLogin() }, 'Prüfen & speichern'),
          model.login ? h('button.btn.focusable', { dataset: { fid: 'logout' }, onclick: () => this.removeLogin() }, 'Entfernen') : null)),
      model.login ? section('Katalog',
        h('p.muted', null, `${model.catalog.entries.length.toLocaleString('de-DE')} Titel · aktualisiert ${when(model.catalog.fetchedAt)}. ` +
          'Couchpotato lädt den Katalog höchstens einmal am Tag (6 Anfragen) – so bleibt der Anbieter entspannt.'),
        model.status ? h('p', null, model.status) : null,
        action('Jetzt aktualisieren', async () => {
          if (await app.confirm('Katalog neu laden?', 'Das sind 6 Anfragen an deinen Anbieter.', 'Neu laden')) model.refreshCatalog(true);
        }, { fid: 'reload', sub: 'Filme, Serien und Sender neu vom Anbieter holen' })) : null,
    ];
  }

  async saveLogin() {
    const d = this.draft;
    if (!loginValid(d)) { app.toast('Bitte Server, Benutzername und Passwort eintragen'); return; }
    app.toast('Prüfe Zugang …');
    try {
      const line = await new XtreamClient(d).checkLogin();
      await model.setLogin(d);
      this.draft = null;
      app.toast(line, 5000);
      app.refresh();
    } catch (e) {
      app.toast(e.message, 6000);
    }
  }

  async removeLogin() {
    if (!(await app.confirm('IPTV-Zugang entfernen?', 'Katalog und Details auf diesem Fernseher werden gelöscht.', 'Entfernen'))) return;
    this.draft = null;
    await model.setLogin(null);
    app.refresh();
  }

  // MARK: household
  household() {
    const hs = model.household;
    if (!HouseholdSync.configured) return section('Haushalt', h('p.muted', null, 'Der Abgleich ist in dieser Version nicht eingerichtet.'));
    if (hs.linked) {
      return section('Haushalt',
        h('p', null, `Verbunden mit Haushalt ${hs.code || ''}.`),
        h('p.muted', null, `Profile, Weiterschauen, Meine Liste, Favoriten und „Für dich“ werden Ende-zu-Ende-verschlüsselt mit deinen anderen Fernsehern und dem iPhone abgeglichen. Zuletzt: ${when(hs.lastSync)}.`),
        action('Jetzt abgleichen', async () => { await model.syncNow(false); app.toast('Abgeglichen'); }, { fid: 'syncnow' }),
        field('Name dieses Fernsehers', local.get('device.name') || model.deviceName, { fid: 'devname', onsubmit: (v) => { local.set('device.name', v.trim()); app.toast('Gespeichert'); } }),
        action('Haushalt verlassen', async () => {
          if (await app.confirm('Haushalt verlassen?', 'Nur dieser Fernseher verlässt den Haushalt; die anderen Geräte behalten alles.', 'Verlassen')) model.leaveHousehold();
        }, { fid: 'leave', danger: true }));
    }
    let code = '';
    return section('Haushalt beitreten',
      h('p.muted', null, 'Den Haushalts-Code findest du am Couchpotato-Fernseher unter Einstellungen → Haushalt (10 Zeichen). ' +
        'Danach teilen sich dieser Fernseher, deine Fire TVs und das iPhone Profile, Weiterschauen und Favoriten.'),
      field('Haushalts-Code', '', { fid: 'code', placeholder: 'XXXXX-XXXXX', onchange: (v) => { code = v; }, onsubmit: (v) => { code = v; } }),
      h('div.buttons', { dataset: { row: 'hhb' } }, h('button.btn.primary.focusable', { dataset: { fid: 'join' }, onclick: () => this.join(code) }, 'Beitreten')));
  }

  async join(code) {
    app.toast('Verbinde … (kann auf älteren Fernsehern bis zu einer Minute dauern)', 60000);
    try {
      const share = await model.join(code);
      app.toast('Verbunden mit dem Haushalt');
      if (share && !model.login && (await app.confirm('IPTV-Zugang übernehmen?', 'Dein Hauptfernseher teilt seinen IPTV-Zugang mit dem Haushalt. Soll dieser Fernseher ihn auch nutzen?', 'Übernehmen', 'Nicht jetzt'))) {
        await model.setLogin(share);
      }
      app.refresh();
    } catch (e) {
      app.toast(e.message, 6000);
    }
  }

  // MARK: profiles
  profiles() {
    return section('Profile',
      h('p.muted', null, 'Profile gelten im ganzen Haushalt – auf allen Fernsehern und dem iPhone.'),
      model.profiles.map((p) => h('button.setting.focusable', { dataset: { fid: 'pe-' + p.id }, onclick: () => { this.editing = Object.assign({}, p); app.refresh(); F.restore('pname', this.el); } },
        avatar(p, 'tiny'), h('span.s-label', null, p.name, p.kid ? h('small', null, 'Kinderprofil') : null), h('span.chev', null, '›'))),
      action('Neues Profil', () => { this.editing = model.newProfile(); this.editing.isNew = true; app.refresh(); F.restore('pname', this.el); }, { fid: 'pnew' }));
  }

  profileEditor() {
    const p = this.editing;
    const save = () => {
      if (!p.name.trim()) { app.toast('Bitte einen Namen eingeben'); return; }
      model.saveProfile(p);
      this.editing = null;
      app.refresh();
    };
    const minutes = [[0, 'Unbegrenzt'], [30, '30 Minuten'], [45, '45 Minuten'], [60, '1 Stunde'], [90, '1,5 Stunden'], [120, '2 Stunden'], [180, '3 Stunden']];
    const beds = [[-1, 'Keine']].concat([18, 18.5, 19, 19.5, 20, 20.5, 21, 21.5, 22].map((x) => [x * 60, `${Math.floor(x)}:${x % 1 ? '30' : '00'} Uhr`]));
    return [
      section(p.isNew ? 'Neues Profil' : 'Profil bearbeiten',
        h('div.editor-head', null, avatar(p, 'big')),
        field('Name', p.name, { fid: 'pname', onchange: (v) => { p.name = v; } }),
        h('div.swatches', { dataset: { row: 'animals' } }, Array.from({ length: ANIMALS }, (_, i) => h('button.swatch.focusable', {
          class: i === (p.animal >= 0 ? p.animal : -1) ? 'on' : '', dataset: { fid: 'an-' + i },
          onclick: () => { p.animal = i; app.refresh(); },
        }, h('img', { src: `img/animal${i}.png`, alt: '' })))),
        h('div.swatches', { dataset: { row: 'colors' } }, PROFILE_COLORS.map((c, i) => h('button.swatch.color.focusable', {
          class: i === p.color ? 'on' : '', style: { backgroundColor: c }, dataset: { fid: 'co-' + i }, onclick: () => { p.color = i; app.refresh(); },
        }))),
        toggle('Kinderprofil', p.kid, (v) => { p.kid = v; app.refresh(); }, { fid: 'pkid', sub: 'Nur freigegebene Kategorien, Profil wechseln nur mit Eltern-PIN' }),
        p.kid ? choice('Fernsehzeit pro Tag', minutes, p.limit, (v) => { p.limit = v; app.refresh(); }, { fid: 'plimit' }) : null,
        p.kid ? choice('Schlafenszeit', beds, p.bed, (v) => { p.bed = v; app.refresh(); }, { fid: 'pbed' }) : null),
      h('div.buttons', { dataset: { row: 'peb' } },
        h('button.btn.primary.focusable', { dataset: { fid: 'psave' }, onclick: save }, 'Speichern'),
        h('button.btn.focusable', { dataset: { fid: 'pcancel' }, onclick: () => { this.editing = null; app.refresh(); } }, 'Abbrechen'),
        !p.isNew && p.id !== 'main' ? h('button.btn.danger.focusable', { dataset: { fid: 'pdel' }, onclick: async () => {
          if (await app.confirm(`„${p.name}“ löschen?`, 'Das Profil verschwindet auf allen Geräten des Haushalts.', 'Löschen')) { model.deleteProfile(p.id); this.editing = null; app.refresh(); }
        } }, 'Löschen') : null),
    ];
  }

  // MARK: categories (hide for everyone; kids' categories)
  categories() {
    const kind = this.catKind;
    const cats = model.allCategories(kind);
    const kidSet = model.data.stringSet('profiles', 'kidCats');
    return [
      h('div.chips', { dataset: { row: 'kinds' } }, [MOVIE, SERIES, LIVE].map((k) => h('button.chip.focusable', {
        class: k === kind ? 'on' : '', dataset: { fid: 'kind-' + k }, onclick: () => { this.catKind = k; app.refresh(); },
      }, KIND_NAMES[k]))),
      section(`${KIND_NAMES[kind]}: Kategorien`,
        h('p.muted', null, 'Ausgeblendete Kategorien verschwinden im ganzen Haushalt. „Für Kinder“ gibt eine Kategorie für Kinderprofile frei.'),
        h('div.buttons', { dataset: { row: 'bulk' } },
          h('button.btn.focusable', { dataset: { fid: 'hideall' }, onclick: () => model.setHidden(kind, cats, true) }, 'Alle ausblenden'),
          h('button.btn.focusable', { dataset: { fid: 'showall' }, onclick: () => model.setHidden(kind, cats, false) }, 'Alle einblenden')),
        cats.map((c) => h('div.cat-line', { dataset: { row: 'cl-' + c.id } },
          toggle(c.name, !model.isHidden(kind, c), (v) => model.setHidden(kind, [c], !v), { fid: 'ch-' + c.id }),
          h('button.chip.focusable', { class: kidSet.has(kind + ':' + c.id) ? 'on' : '', dataset: { fid: 'kc-' + c.id }, onclick: () => {
            const set = model.data.stringSet('profiles', 'kidCats');
            const key = kind + ':' + c.id;
            if (set.has(key)) set.delete(key); else set.add(key);
            model.data.setStringSet('profiles', 'kidCats', set);
            model.syncSoon();
          } }, 'Für Kinder')))),
    ];
  }

  // MARK: playback
  playback() {
    const flip = (key, def) => (v) => { local.set(key, v); app.refresh(); };
    return section('Wiedergabe',
      toggle('Programminfos laden', local.get('pref.epg', true), flip('pref.epg'), { fid: 'epg', sub: 'Eine Anfrage pro Sender, nur wenn du auf ihm verweilst' }),
      toggle('Bilder vom Anbieter laden', local.get('pref.providerImages', true), flip('pref.providerImages'), { fid: 'imgs', sub: 'Höchstens 60 pro Stunde – Bilder von TMDB zählen nicht' }),
      toggle('Live-TV als MPEG-TS', local.get('pref.liveTs', false), flip('pref.liveTs'), { fid: 'ts', sub: 'Falls dein Anbieter kein HLS (m3u8) liefert' }),
      choice('Bildformat', [['fit', 'Ganzes Bild'], ['zoom', 'Bildschirm füllen'], ['auto', 'Automatisch']], local.get('pref.fit', 'fit'), (v) => { local.set('pref.fit', v); app.refresh(); }, { fid: 'fit' }),
      action('Weiterschauen leeren', async () => { if (await app.confirm('„Weiterschauen“ leeren?', `Für das Profil „${model.profile.name}“, auf allen Geräten.`, 'Leeren')) model.clearProgress(); }, { fid: 'clrp' }),
      action('„Für dich“ zurücksetzen', async () => { if (await app.confirm('„Für dich“ zurücksetzen?', 'Der Verlauf, aus dem „Für dich“ lernt, wird auf allen Geräten gelöscht.', 'Zurücksetzen')) model.clearHistory(); }, { fid: 'clrh' }));
  }

  // MARK: parents
  parents() {
    return section('Kinder & Eltern-PIN',
      h('p.muted', null, 'Mit der Eltern-PIN verlässt man ein Kinderprofil. Sie gilt nur auf diesem Fernseher.'),
      action(model.pinSet ? 'Eltern-PIN ändern' : 'Eltern-PIN festlegen', async () => {
        if (model.pinSet && (await askPin('Aktuelle Eltern-PIN', { check: (p) => model.checkPin(p) })) == null) return;
        const pin = await askPin('Neue Eltern-PIN', { setNew: true });
        if (pin) { model.setPin(pin); app.toast('Eltern-PIN gespeichert'); app.refresh(); }
      }, { fid: 'pin' }),
      action('Heute 30 Minuten mehr', async () => {
        const kids = model.profiles.filter((p) => p.kid);
        if (!kids.length) { app.toast('Es gibt kein Kinderprofil'); return; }
        if ((await askPin('Eltern-PIN', { setNew: !model.pinSet, check: (p) => model.checkPin(p) })) == null) return;
        const cur = model.profileId;
        for (const k of kids) { model.profileId = k.id; model.addBonus(30); }
        model.profileId = cur;
        app.toast('Heute 30 Minuten mehr für alle Kinderprofile');
      }, { fid: 'bonus' }));
  }

  // MARK: about
  about() {
    return section('Info',
      h('p', null, `Couchpotato für Samsung · Version ${CONFIG.version}`),
      h('p.muted', null, `Anfragen an den Anbieter in der letzten Stunde: ${gate.requestsLastHour()} von 40 · Bilder: ${gate.imagesLastHour()} von 60`),
      h('p.muted', null, 'Couchpotato bremst alle Anfragen an deinen Anbieter (höchstens eine alle 2,5 Sekunden, 40 pro Stunde, nach einer Sperre 15 Minuten Pause), damit dein Zugang nicht gesperrt wird.'),
      h('p.muted', null, 'Schrift: Inter und Baloo 2 (SIL Open Font License).'));
  }
}
