// Application UI description for the Samsung Apps TV Seller Office (PPTX).
// node store/ui-description.js  (from tizen/)
const pptxgen = require('pptxgenjs');
const { applyTheme } = require(process.env.PPTX_SKILL + '/scripts/apply_theme.js');
const path = require('path');
const dir = __dirname;
const THEME = {
  name: 'Couchpotato', headFontFace: 'Arial', bodyFontFace: 'Arial',
  colors: { dk1: '0E3E44', lt1: 'FFFFFF', dk2: '0B3236', lt2: 'F4F1EC', accent1: 'E8B86D', accent2: '165A61', accent3: 'C8372D',
    accent4: 'C3D6D7', accent5: '1B1408', accent6: '8FC9A9', hlink: 'E8B86D', folHlink: 'C3D6D7' },
};
const pres = new pptxgen();
pres.layout = 'LAYOUT_16x9';
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
pres.title = 'Couchpotato – Application UI description';
pres.author = 'KoeVen GbR';
const C = pres.SchemeColor;

pres.defineSlideMaster({ title: 'TITLE', background: { color: '0E3E44' },
  objects: [{ placeholder: { options: { name: 'title', type: 'title', x: 0.6, y: 3.0, w: 8.8, h: 0.9, fontSize: 30, bold: true, align: 'left', color: C.background2 }, text: '' } },
    { placeholder: { options: { name: 'body', type: 'body', x: 0.6, y: 4.0, w: 8.8, h: 0.9, fontSize: 16, align: 'left', valign: 'top', color: C.accent4 }, text: '' } }] });
pres.defineSlideMaster({ title: 'SCREEN', background: { color: '0E3E44' },
  objects: [{ placeholder: { options: { name: 'title', type: 'title', x: 0.5, y: 0.3, w: 9, h: 0.6, fontSize: 28, bold: true, align: 'left', color: C.background2 }, text: '' } }],
  slideNumber: { x: 9.3, y: 5.2, fontSize: 10, color: C.accent4 } });
pres.defineSlideMaster({ title: 'TEXT', background: { color: '0E3E44' },
  objects: [{ placeholder: { options: { name: 'title', type: 'title', x: 0.5, y: 0.3, w: 9, h: 0.6, fontSize: 28, bold: true, align: 'left', color: C.background2 }, text: '' } },
    { placeholder: { options: { name: 'body', type: 'body', x: 0.5, y: 1.1, w: 9, h: 4.1, fontSize: 15, color: C.background2, valign: 'top' }, text: '' } }],
  slideNumber: { x: 9.3, y: 5.2, fontSize: 10, color: C.accent4 } });

pres.addSection({ title: 'Overview' });
let s = pres.addSlide({ masterName: 'TITLE', sectionTitle: 'Overview' });
s.addImage({ path: path.join(dir, 'icon-512x423.png'), x: 0.6, y: 0.6, w: 2.6, h: 2.15, objectName: 'App icon' });
s.addText('Couchpotato for Samsung Smart TV', { placeholder: 'title' });
s.addText('Application UI description · player for the user’s own IPTV sources (Xtream API) · no content included', { placeholder: 'body' });

const bullets = (items) => items.map((t, i) => ({ text: t, options: { bullet: true, breakLine: i < items.length - 1, paraSpaceAfter: 6 } }));

s = pres.addSlide({ masterName: 'TEXT', sectionTitle: 'Overview' });
s.addText('How to test (no account needed)', { placeholder: 'title' });
s.addText(bullets([
  'Couchpotato contains no channels, films or series. Users connect their own IPTV subscription (Xtream Codes API).',
  'For testing without an account: on the start screen choose “Ohne Zugang ausprobieren” (try without access). The app then offers free Apple HLS test streams: 2 live channels (Live TV → Demo) and 2 films (Filme → Demo).',
  'Everything else (profiles, favourites with the RED key, “Weiterschauen” = continue watching, search, settings) works with these test streams.',
  'Optional “Haushalt” (household): end-to-end encrypted sync with the user’s other Couchpotato devices via a 10-character code – not needed for testing.',
  'Exit: RETURN on the start screen shows an exit pop-up; during playback RETURN stops the video. When the app is hidden (Smart Hub key) playback stops like RETURN and the position is saved.',
]), { placeholder: 'body' });

s = pres.addSlide({ masterName: 'TEXT', sectionTitle: 'Overview' });
s.addText('Remote control', { placeholder: 'title' });
const rows = [['Key', 'Where', 'Action'],
  ['Arrows / OK', 'everywhere', 'move focus / select'],
  ['RETURN', 'everywhere', 'one level back; on the start screen: exit pop-up'],
  ['PLAY/PAUSE, PLAY, PAUSE, STOP', 'film player', 'play, pause, stop'],
  ['LEFT / RIGHT (hold = faster), REW / FF', 'film player', 'seek −10 s / +30 s'],
  ['UP / DOWN, CH+ / CH−', 'live player, live list', 'previous / next channel'],
  ['OK', 'live player', 'channel list'],
  ['RED', 'live TV', 'add / remove favourite channel'],
  ['0–9', 'PIN dialog', 'parents’ PIN (kids profiles)']];
s.addTable(rows.map((r, i) => r.map((t) => ({ text: t, options: { bold: i === 0, color: i === 0 ? '1B1408' : 'F4F1EC', fill: { color: i === 0 ? 'E8B86D' : i % 2 ? '165A61' : '0B3236' } } }))),
  { x: 0.5, y: 1.15, w: 9, colW: [2.9, 2.2, 3.9], fontSize: 13, border: { type: 'none' }, rowH: 0.38, objectName: 'Key table' });

pres.addSection({ title: 'Screens' });
const screens = [
  ['screenshot-1-start.jpg', 'Start', ['Hero: next title to continue', 'Rows: Weiterschauen (continue watching), favourite channels, Für dich (recommendations), Meine Liste (my list), new films and series', 'Top bar: Start · Live TV · Filme · Serien · Search · Settings · profile']],
  ['screenshot-2-live.jpg', 'Live TV', ['Left: categories incl. favourites and recently watched', 'Middle: channels with what’s on now', 'Right: running preview (OK on a channel) with programme guide; OK again = full screen', 'RED: favourite']],
  ['screenshot-3-serie.jpg', 'Series page', ['Continue at the last episode or start episode 1', 'Seasons as chips, episodes with progress and watched marks', 'Meine Liste: add / remove']],
  ['screenshot-4-player.jpg', 'Player', ['OK shows the controls; LEFT / RIGHT seek on the timeline', 'Audio track & subtitles, picture format', '“Nächste Folge” (next episode) appears during the end credits']],
  ['screenshot-5-profile.jpg', 'Who is watching?', ['Profiles shared in the user’s household', 'Kids profiles: only released categories, screen time and bedtime, leaving them needs the parents’ PIN']],
];
for (const [file, title, items] of screens) {
  s = pres.addSlide({ masterName: 'SCREEN', sectionTitle: 'Screens' });
  s.addText(title, { placeholder: 'title' });
  s.addImage({ path: path.join(dir, file), x: 0.5, y: 1.1, w: 6.0, h: 3.375, objectName: title + ' screenshot' });
  s.addText(bullets(items), { x: 6.8, y: 1.1, w: 2.8, h: 3.9, fontSize: 13, color: C.background2, valign: 'top', isTextBox: true, objectName: title + ' notes' });
}

pres.addSection({ title: 'Information' });
s = pres.addSlide({ masterName: 'TEXT', sectionTitle: 'Information' });
s.addText('Settings, privacy, requests', { placeholder: 'title' });
s.addText(bullets([
  'Settings: IPTV access, Haushalt (household), profiles, categories (hide / release for kids), playback (programme guide, provider images, MPEG-TS), kids & PIN, info.',
  'Privacy policy: https://axdnmlqdvewmtmwfovgr.supabase.co/functions/v1/couchpotato-legal?p=privacy-tv',
  'Help: https://axdnmlqdvewmtmwfovgr.supabase.co/functions/v1/couchpotato-legal?p=support-tv',
  'No ads, no tracking, no analytics. Requests to the user’s provider are throttled (≥ 2.5 s apart, ≤ 40 per hour, 15 min pause after a lock response).',
  'Network loss shows a message at the bottom of the screen; it disappears when the connection returns.',
]), { placeholder: 'body' });

pres.writeFile({ fileName: path.join(dir, 'couchpotato-ui-description.pptx') }).then(async (f) => {
  await applyTheme(f, THEME);
  console.log('written', f);
});
