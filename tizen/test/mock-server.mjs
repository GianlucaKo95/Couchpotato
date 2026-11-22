// A pretend provider + the built app on one port, for trying the UI in a desktop browser.
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';

const root = process.argv[2] || 'dist/app';
const port = Number(process.argv[3] || 8765);
const now = Math.floor(Date.now() / 1000);
const cats = (p, names) => names.map((n, i) => ({ category_id: String(p + i), category_name: n }));
const vodCats = cats(10, ['DE: Neu im Kino', 'DE: Action', 'DE: Komödie', 'DE: Kinder']);
const seriesCats = cats(20, ['DE: Drama Serien', 'DE: Krimi', 'DE: Kinder Serien']);
const liveCats = cats(30, ['DE: Vollprogramm', 'DE: Nachrichten', 'DE: Sport']);
const color = (i) => ['c0392b', '2980b9', '27ae60', '8e44ad', 'd35400', '16a085'][i % 6];
const vod = Array.from({ length: 90 }, (_, i) => ({ stream_id: 1000 + i, name: `DE: Film Nummer ${i + 1}`, stream_icon: `http://localhost:${port}/img/p${i % 6}.svg`, category_id: String(10 + (i % 4)), added: String(now - i * 86400), rating: String((5 + (i % 5)).toFixed(1)), genre: ['Action', 'Drama', 'Komödie'][i % 3], container_extension: 'mp4', year: String(2000 + (i % 25)) }));
const series = Array.from({ length: 30 }, (_, i) => ({ series_id: 2000 + i, name: `Serie ${i + 1}`, cover: `http://localhost:${port}/img/p${(i + 2) % 6}.svg`, category_id: String(20 + (i % 3)), last_modified: String(now - i * 3600), rating: '7.5', genre: 'Drama' }));
const live = ['Das Erste HD', 'ZDF HD', 'RTL', 'SAT.1', 'ProSieben', 'VOX', 'kabel eins', 'n-tv', 'WELT', 'Sky Sport'].map((n, i) => ({ stream_id: 3000 + i, name: `DE: ${n}`, stream_icon: `http://localhost:${port}/img/l${i % 6}.svg`, category_id: String(30 + (i % 3)) }));

function api(q) {
  switch (q.get('action')) {
    case null: return { user_info: { auth: 1, status: 'Active', exp_date: String(now + 90 * 86400) } };
    case 'get_vod_categories': return vodCats;
    case 'get_series_categories': return seriesCats;
    case 'get_live_categories': return liveCats;
    case 'get_vod_streams': return vod;
    case 'get_series': return series;
    case 'get_live_streams': return live;
    case 'get_series_info': return { info: { name: 'Serie', plot: 'Eine spannende Geschichte über Menschen, Orte und Dinge.', cover: '/img/p3.svg' },
      episodes: { 1: Array.from({ length: 6 }, (_, i) => ({ id: String(5000 + i), episode_num: String(i + 1), season: '1', title: `Serie - S01E0${i + 1} - Kapitel ${i + 1}`, container_extension: 'mp4', info: { duration_secs: '2700', plot: 'Folge mit Handlung.' } })),
        2: Array.from({ length: 4 }, (_, i) => ({ id: String(5100 + i), episode_num: String(i + 1), season: '2', title: `Neues Kapitel ${i + 1}`, container_extension: 'mp4', info: {} })) } };
    case 'get_vod_info': return { info: { plot: 'Ein Film über etwas sehr Wichtiges.', cast: 'Anna Beispiel, Bernd Muster', director: 'Clara Regie', duration: '01:52:00' } };
    case 'get_short_epg': {
      const b64 = (s) => Buffer.from(s).toString('base64');
      return { epg_listings: [0, 1, 2, 3].map((i) => ({ title: b64(['Tagesschau', 'Wetter', 'Spielfilm', 'Nachtmagazin'][i]), description: b64('Beschreibung'), start_timestamp: String(now - 1200 + i * 1800), stop_timestamp: String(now + 600 + i * 1800) })) };
    }
    default: return [];
  }
}

createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/player_api.php') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(api(u.searchParams)));
    return;
  }
  if (/^\/(movie|series|live)\//.test(u.pathname)) {
    const buf = readFileSync('/tmp/test.mp4');
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
    if (m) {
      const a = +m[1], b = m[2] ? +m[2] : buf.length - 1;
      res.writeHead(206, { 'content-type': 'video/mp4', 'accept-ranges': 'bytes', 'content-range': `bytes ${a}-${b}/${buf.length}`, 'content-length': b - a + 1 });
      res.end(buf.subarray(a, b + 1));
    } else {
      res.writeHead(200, { 'content-type': 'video/mp4', 'accept-ranges': 'bytes', 'content-length': buf.length });
      res.end(buf);
    }
    return;
  }
  const m = /^\/img\/([pl])(\d)\.svg$/.exec(u.pathname);
  if (m) {
    res.writeHead(200, { 'content-type': 'image/svg+xml' });
    res.end(m[1] === 'p'
      ? `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#${color(+m[2])}"/><stop offset="1" stop-color="#111"/></linearGradient></defs><rect width="400" height="600" fill="url(#g)"/><circle cx="200" cy="240" r="90" fill="rgba(255,255,255,.25)"/></svg>`
      : `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="160"><text x="150" y="100" font-size="56" text-anchor="middle" font-family="sans-serif" font-weight="700" fill="#${color(+m[2])}">TV${m[2]}</text></svg>`);
    return;
  }
  const file = join(root, u.pathname === '/' ? 'index.html' : u.pathname);
  if (!existsSync(file)) { res.writeHead(404); res.end(); return; }
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.ttf': 'font/ttf' };
  res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
}).listen(port, () => console.log('mock on ' + port));
