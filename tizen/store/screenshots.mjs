// Store screenshots (1920×1080 JPG < 500 kB) from the app running against the test provider.
// node test/mock-server.mjs dist/app 8765 &  then  node store/screenshots.mjs <path-to-playwright>
const pw = await import(process.argv[2] || 'playwright');
const { chromium } = pw.default || pw;
const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
const wait = (ms) => p.waitForTimeout(ms);
const key = async (k, n = 1) => { for (let i = 0; i < n; i++) { await p.keyboard.press(k); await wait(160); } };
const shot = (n) => p.screenshot({ path: `store/screenshot-${n}.jpg`, type: 'jpeg', quality: 82 });
await p.goto('http://localhost:8765/');
await p.evaluate(() => localStorage.setItem('cp.iptv.login', JSON.stringify({ server: 'http://localhost:8765', user: 'demo', password: 'demo' })));
await p.reload();
await wait(17500);
// a household with a few things going on
await p.evaluate(() => {
  const m = __cp.model;
  for (const [id, name, animal, color, kid] of [['anna', 'Anna', 5, 1, false], ['nelio', 'Nelio', 8, 3, true]]) m.saveProfile({ id, name, color, kid, animal, limit: 0, bed: -1, maxFsk: 6 });
  const ch = m.allChannels;
  for (const c of [ch[0], ch[1], ch[2], ch[6], ch[8]]) m.toggleFavorite(c);
  const e = m.entry('m', 1001), e2 = m.entry('m', 1004);
  m.saveProgress({ key: 'vod:1001', kind: 'movie', streamId: 1001, ext: 'mp4', title: e.name, image: e.image }, 2500, 6200);
  m.saveProgress({ key: 'vod:1004', kind: 'movie', streamId: 1004, ext: 'mp4', title: e2.name, image: e2.image }, 900, 5400);
  m.saveProgress({ key: 'ep:5002', kind: 'episode', streamId: 5002, ext: 'mp4', title: 'Hafenwache', subtitle: 'S01 E03 · Fracht ohne Papiere', image: m.entry('s', 2000).image, seriesId: 2000 }, 1300, 2700);
  m.toggleList(m.entry('s', 2003)); m.toggleList(m.entry('m', 1007));
});
await wait(600);
await key('ArrowDown'); await key('ArrowRight'); await key('ArrowUp'); await key('ArrowLeft');
await wait(800); await shot('1-start');
// live TV with running preview
await p.evaluate(() => __cp.app.openTab('live')); await wait(500);
await key('ArrowDown'); await wait(1300); await key('ArrowUp'); await wait(1300); await key('Enter'); await wait(3000);
await shot('2-live');
await key('Escape'); await wait(300);
// series page
await p.evaluate(() => { __cp.app.openTab('series'); }); await wait(400);
await key('ArrowRight'); await key('Enter'); await wait(3500);
await shot('3-serie');
// player
await p.evaluate(() => __cp.app.openTab('movies')); await wait(400);
await key('ArrowRight'); await key('Enter'); await wait(600); await key('Enter'); await wait(3000);
await key('ArrowRight'); await wait(900);
await shot('4-player');
await key('Escape'); await key('Escape'); await wait(500);
// who is watching
await p.evaluate(() => __cp.app.openProfiles()); await wait(500);
await shot('5-profile');
await b.close();
