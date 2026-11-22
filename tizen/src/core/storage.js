// Small values: localStorage. Big ones (catalogue, series details): IndexedDB.
const mem = new Map();
const ls = (() => {
  try {
    const t = '__cp';
    localStorage.setItem(t, '1');
    localStorage.removeItem(t);
    return localStorage;
  } catch (e) {
    return null;
  }
})();

export const local = {
  get(key, fallback = null) {
    const raw = ls ? ls.getItem('cp.' + key) : mem.get(key);
    if (raw == null) return fallback;
    try { return JSON.parse(raw); } catch (e) { return fallback; }
  },
  set(key, value) {
    const raw = JSON.stringify(value);
    if (ls) {
      try { ls.setItem('cp.' + key, raw); } catch (e) { mem.set(key, raw); }
    } else mem.set(key, raw);
  },
  remove(key) {
    if (ls) ls.removeItem('cp.' + key);
    mem.delete(key);
  },
};

let dbPromise = null;
function db() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve) => {
    if (typeof indexedDB === 'undefined') return resolve(null);
    const req = indexedDB.open('couchpotato', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('kv');
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => resolve(null);
  });
  return dbPromise;
}

const bigMem = new Map();

export const big = {
  async get(key) {
    const d = await db();
    if (!d) return bigMem.has(key) ? bigMem.get(key) : null;
    return new Promise((resolve) => {
      const r = d.transaction('kv', 'readonly').objectStore('kv').get(key);
      r.onsuccess = () => resolve(r.result == null ? null : r.result);
      r.onerror = () => resolve(null);
    });
  },
  async set(key, value) {
    const d = await db();
    if (!d) { bigMem.set(key, value); return; }
    return new Promise((resolve) => {
      const tx = d.transaction('kv', 'readwrite');
      tx.objectStore('kv').put(value, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  },
  async remove(key) {
    const d = await db();
    if (!d) { bigMem.delete(key); return; }
    return new Promise((resolve) => {
      const tx = d.transaction('kv', 'readwrite');
      tx.objectStore('kv').delete(key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  },
  async clear(prefix) {
    const d = await db();
    if (!d) { for (const k of [...bigMem.keys()]) if (k.startsWith(prefix)) bigMem.delete(k); return; }
    return new Promise((resolve) => {
      const tx = d.transaction('kv', 'readwrite');
      const store = tx.objectStore('kv');
      const r = store.openCursor();
      r.onsuccess = () => {
        const c = r.result;
        if (!c) return;
        if (String(c.key).startsWith(prefix)) c.delete();
        c.continue();
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  },
};
