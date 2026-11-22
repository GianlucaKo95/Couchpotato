// "Für dich" – same scoring as the TVs (Recommender.kt) and the iPhone, computed from the catalogue.
const LIST_WEIGHT = 0.7;

export function genres(g) {
  if (!g) return [];
  return g.split(/[,/&|]/).map((x) => x.trim().toLowerCase()).filter((x) => x.length > 1);
}

export function recommend(entries, allowed, history, watchlist, limit = 30, now = Date.now()) {
  const byKey = new Map();
  for (const e of entries) if (e.kind !== 'l') byKey.set(e.id, e);
  const watched = Object.keys(history).map((k) => byKey.get(k)).filter(Boolean);
  const listed = Object.keys(watchlist).filter((k) => history[k] == null).map((k) => byKey.get(k)).filter(Boolean);
  const catLove = {}, genreLove = {};
  const love = (e, w) => {
    catLove[e.categoryKey] = (catLove[e.categoryKey] || 0) + w;
    for (const g of genres(e.genre)) genreLove[g] = (genreLove[g] || 0) + w;
  };
  for (const w of watched) love(w, Math.exp(-Math.max((now - (history[w.id] || now)) / 864e5, 0) / 120));
  for (const l of listed) love(l, LIST_WEIGHT * Math.exp(-Math.max((now - (watchlist[l.id] || now)) / 864e5, 0) / 180));
  const maxCat = Math.max(0, ...Object.values(catLove)), maxGenre = Math.max(0, ...Object.values(genreLove));
  const signals = watched.length + listed.length * 0.5;
  const personal = Math.min(signals / 12, 1) * 0.65;
  const nowSec = now / 1000;
  const seen = new Set();
  const candidates = entries.filter((e) => e.kind !== 'l' && history[e.id] == null && watchlist[e.id] == null && allowed(e) && !seen.has(e.id) && seen.add(e.id));
  const anyRatings = candidates.some((e) => e.rating > 0);
  const score = (e) => {
    const rating = e.rating > 0 ? e.rating / 10 : anyRatings ? 0.35 : 0.5;
    const age = e.added > 0 ? (nowSec - e.added) / 86400 : 3650;
    const base = 0.75 * rating + 0.25 * Math.exp(-Math.max(age, 0) / 365);
    if (personal === 0) return base;
    const cat = maxCat > 0 ? (catLove[e.categoryKey] || 0) / maxCat : 0;
    const g = genres(e.genre);
    const genre = maxGenre > 0 && g.length ? Math.max(...g.map((x) => (genreLove[x] || 0) / maxGenre)) : 0;
    const taste = 0.6 * cat + 0.4 * genre;
    return (1 - personal) * base + personal * (0.4 * base + 0.6 * taste);
  };
  const ranked = candidates.filter((e) => !anyRatings || e.rating === 0 || e.rating >= 5.5)
    .map((e) => [e, score(e)]).sort((a, b) => b[1] - a[1]);
  const perCat = {}, picked = [];
  for (const [e] of ranked) {
    const n = perCat[e.categoryKey] || 0;
    if (n >= 4) continue;
    perCat[e.categoryKey] = n + 1;
    picked.push(e);
    if (picked.length >= limit) break;
  }
  return { items: picked, personal: personal > 0 };
}
