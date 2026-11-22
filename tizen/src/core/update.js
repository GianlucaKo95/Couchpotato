// Self-update like the Fire TV app: the installed package is only a loader (boot.js). Newer app code is
// downloaded from the repo's tizen-update branch, checked against its SHA-256 and kept in localStorage;
// boot.js starts it on the next launch and falls back to the installed version if it ever fails to start.
/* global __CP_SELF_UPDATE__ */
import { httpGet } from './xtream.js';

async function get(url, timeout) {
  const r = await httpGet(url, timeout);
  if (r.status !== 200) throw new Error('Update-Server: HTTP ' + r.status);
  return r.text;
}
import { sha256Hex } from './crypto.js';

const BASE = 'https://raw.githubusercontent.com/GianlucaKo95/Couchpotato/tizen-update/';
export const SELF_UPDATE = typeof __CP_SELF_UPDATE__ === 'undefined' ? true : __CP_SELF_UPDATE__;

const ls = () => { try { return window.localStorage; } catch (e) { return null; } };
const read = (k) => { try { return ls().getItem(k); } catch (e) { return null; } };
const write = (k, v) => { const s = ls(); if (v == null) s.removeItem(k); else s.setItem(k, v); };

const boot = () => (typeof window !== 'undefined' && window.__cpBoot) || { bundled: 0, running: 0 };
/** Build number of the code that is running right now. */
export const runningBuild = () => boot().running;

/** Called once the app is up: the downloaded code (if any) started fine, keep using it. */
export function markHealthy() {
  try { write('cp.code.pending', null); } catch (e) { /* no storage */ }
}

let busy = null;
/**
 * Looks for a newer version and downloads it. Resolves to
 *  { state: 'ready', version } – downloaded, starts with the next launch
 *  { state: 'current' } | { state: 'off' } | { state: 'error', message }
 */
export function checkForUpdate() {
  if (!SELF_UPDATE || !boot().bundled) return Promise.resolve({ state: 'off' });
  if (!busy) busy = run().finally(() => { busy = null; });
  return busy;
}

async function run() {
  try {
    const info = JSON.parse(await get(BASE + 'latest.json?t=' + Date.now(), 20000));
    const build = Number(info.build) || 0;
    if (build <= runningBuild() || String(build) === read('cp.code.bad')) return { state: 'current' };
    if (Number(read('cp.code.build')) === build) return { state: 'ready', version: info.version };
    const [js, css] = await Promise.all([
      get(BASE + info.js + '?b=' + build, 60000),
      get(BASE + info.css + '?b=' + build, 30000),
    ]);
    if (sha256Hex(js) !== info.jsSha256 || sha256Hex(css) !== info.cssSha256) throw new Error('Prüfsumme stimmt nicht');
    // old copy out first (space), the build number last: boot.js only uses a complete set
    write('cp.code.build', null);
    write('cp.code.js', js);
    write('cp.code.css', css);
    write('cp.code.build', String(build));
    return { state: 'ready', version: info.version };
  } catch (e) {
    try { write('cp.code.build', null); write('cp.code.js', null); write('cp.code.css', null); } catch (e2) { /* ignore */ }
    return { state: 'error', message: (e && e.message) || String(e) };
  }
}

/** Throws away downloaded code: the next launch uses the installed version again. */
export function resetToInstalled() {
  for (const k of ['cp.code.build', 'cp.code.js', 'cp.code.css', 'cp.code.pending']) write(k, null);
}
