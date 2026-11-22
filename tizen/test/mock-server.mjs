// A pretend provider + the built app on one port, for trying the UI in a desktop browser.
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';

const root = process.argv[2] || 'dist/app';
const port = Number(process.argv[3] || 8765);
const now = Math.floor(Date.now() / 1000);
const cats = (p, names) => names.map((n, i) => ({ category_id: String(p + i), category_name: n }));
const vodCats = cats(10, ['DE: Neu im Kino', 'DE: Drama', 'DE: Komödie', 'DE: Kinder']);
const seriesCats = cats(20, ['DE: Drama Serien', 'DE: Krimi', 'DE: Kinder Serien']);
const liveCats = cats(30, ['DE: Vollprogramm', 'DE: Nachrichten', 'DE: Sport']);
const color = (i) => ['c0392b', '2980b9', '27ae60', '8e44ad', 'd35400', '16a085'][i % 6];
// invented titles – the screenshots must not show anybody's real programmes
const films = ['Nordlicht', 'Die letzte Fähre', 'Sommer in Ligurien', 'Stadt aus Glas', 'Der Leuchtturmwärter', 'Zwischen den Gezeiten',
  'Kupferherz', 'Abendrot über Kiel', 'Das stille Haus', 'Fünf Tage im März', 'Wolkenbruch', 'Die Kartografin', 'Salz & Pfeffer',
  'Grenzgänger', 'Mondscheinbahn', 'Der zweite Frühling', 'Eisblau', 'Hinterhof-Helden'];
const shows = ['Hafenwache', 'Die Werkstatt', 'Kommissarin Brandt', 'Inselärzte', 'Lagerfeuer-Geschichten', 'Die Nachbarn von Nr. 7', 'Grünzeug', 'Funkstille'];
const stations = ['Kanal Eins', 'Nord TV', 'Westwelle', 'Info 24', 'Sport Arena', 'Kinderkanal Plus', 'Kultur HD', 'Regio Live', 'Doku Welt', 'Musik Box'];
const vod = Array.from({ length: 90 }, (_, i) => ({ stream_id: 1000 + i, name: `DE: ${films[i % films.length]}${i >= films.length ? ' ' + (Math.floor(i / films.length) + 1) : ''}`, stream_icon: `http://localhost:${port}/img/p${i % 6}.svg`, category_id: String(10 + (i % 4)), added: String(now - i * 86400), rating: String((6 + (i % 4)).toFixed(1)), genre: ['Drama', 'Komödie', 'Thriller'][i % 3], container_extension: 'mp4', year: String(2018 + (i % 8)) }));
const series = Array.from({ length: 30 }, (_, i) => ({ series_id: 2000 + i, name: shows[i % shows.length] + (i >= shows.length ? ' ' + (Math.floor(i / shows.length) + 1) : ''), cover: `http://localhost:${port}/img/p${(i + 2) % 6}.svg`, category_id: String(20 + (i % 3)), last_modified: String(now - i * 3600), rating: '7.8', genre: 'Krimi' }));
const live = stations.map((n, i) => ({ stream_id: 3000 + i, name: `DE: ${n}`, stream_icon: `http://localhost:${port}/img/l${i}.svg`, category_id: String(30 + (i % 3)) }));

function api(q) {
  switch (q.get('action')) {
    case null: return { user_info: { auth: 1, status: 'Active', exp_date: String(now + 90 * 86400) } };
    case 'get_vod_categories': return vodCats;
    case 'get_series_categories': return seriesCats;
    case 'get_live_categories': return liveCats;
    case 'get_vod_streams': return vod;
    case 'get_series': return series;
    case 'get_live_streams': return live;
    case 'get_series_info': return { info: { name: 'Hafenwache', plot: 'Zwischen Containerkränen und Fischkuttern ermittelt ein kleines Team der Wasserschutzpolizei – mal geht es um Schmuggel, mal um verschwundene Boote, immer um die Menschen am Hafen.', cover: `http://localhost:${port}/img/p3.svg`, genre: 'Krimi', releaseDate: '2023' },
      episodes: { 1: Array.from({ length: 6 }, (_, i) => ({ id: String(5000 + i), episode_num: String(i + 1), season: '1', title: `Hafenwache - S01E0${i + 1} - ${['Ebbe', 'Nebelhorn', 'Fracht ohne Papiere', 'Leinen los', 'Die Lotsin', 'Sturmwarnung'][i]}`, container_extension: 'mp4', info: { duration_secs: '2700', plot: 'Ein Container steht seit Tagen unbeachtet am Kai – bis jemand nachts das Siegel bricht.' } })),
        2: Array.from({ length: 4 }, (_, i) => ({ id: String(5100 + i), episode_num: String(i + 1), season: '2', title: `Kapitel ${i + 1}`, container_extension: 'mp4', info: {} })) } };
    case 'get_vod_info': return { info: { plot: 'Ein Film über etwas sehr Wichtiges.', cast: 'Anna Beispiel, Bernd Muster', director: 'Clara Regie', duration: '01:52:00' } };
    case 'get_short_epg': {
      const b64 = (s) => Buffer.from(s).toString('base64');
      return { epg_listings: [0, 1, 2, 3].map((i) => ({ title: b64(['Abendjournal', 'Das Wetter', 'Nordlicht – Spielfilm', 'Spätmagazin'][i]), description: b64(['Nachrichten aus der Region und der Welt, mit Sport und Wetter.', '', '', ''][i]), start_timestamp: String(now - 1200 + i * 1800), stop_timestamp: String(now + 600 + i * 1800) })) };
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
  const m = /^\/img\/([pl])(\d+)\.svg$/.exec(u.pathname);
  if (m) {
    res.writeHead(200, { 'content-type': 'image/svg+xml' });
    res.end(m[1] === 'p'
      ? `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#${color(+m[2])}"/><stop offset="1" stop-color="#111"/></linearGradient></defs><rect width="400" height="600" fill="url(#g)"/><circle cx="200" cy="240" r="90" fill="rgba(255,255,255,.25)"/></svg>`
      : `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="160"><rect x="60" y="34" width="180" height="92" rx="20" fill="#${color(+m[2])}"/><text x="150" y="97" font-size="48" text-anchor="middle" font-family="sans-serif" font-weight="800" fill="#fff">${['K1', 'NORD', 'WEST', 'i24', 'ARENA', 'KiKa+', 'KULT', 'REGIO', 'DOKU', 'BOX'][+m[2]] || 'TV'}</text></svg>`);
    return;
  }
  const file = join(root, u.pathname === '/' ? 'index.html' : u.pathname);
  if (!existsSync(file)) { res.writeHead(404); res.end(); return; }
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.ttf': 'font/ttf' };
  res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
}).listen(port, () => console.log('mock on ' + port));
