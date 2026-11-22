import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as cc from '../src/core/crypto.js';
import { merge, canonical } from '../src/core/household.js';
import { parseTSV, tsvLine, search } from '../src/core/catalog.js';
import { display, episode, normalize } from '../src/core/names.js';
import { loginFromLink } from '../src/core/xtream.js';
import { parseSeries } from '../src/core/titles.js';
import { animalIndex, isFinished } from '../src/core/model.js';
import { pbkdf2 } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';

// made on a TV (CloudSync.kt) – the same vector the iPhone tests use
const code = '7KQ2MX9P4T';
const authHex = '77aba12f21861a8389581737da736ae93649154df234345422b724feec6c7961';
const blob = 'AQABAgMEBQYHCAkKC2qASesEniRlHeRe59cbeTGPsV6xFMKGJM3tQA1GqqgFne9qt6pn0Qgw5Z/bgW/AIIU2dimxPwjeBEheqRSCt/lO+tfaWBgrR2Cz7xQMh0WVujNp/U9SIe6nqcnzl9+K4jWOqHMeGbhMpt6ijGwrUsWbXuDhrvQTZp3c9nHDCt/S8OezgS3JhKY/gekLaNYfHDvZ/sXkEWCeAluHpFIpOdsuvwsdclFESzxUICla2H2nDuW6xC+lMKTup0MgADrPwP2vQQYAOR2egK2Q1q8V7qd5t3EHL5jaycOXnnXwZNkQEt+Xrd5D00PEb2OJrQiGQ0MsO+mqgbNDA5/U';

test('keys match the TVs', async () => {
  const k = await cc.deriveKeys(code);
  assert.equal(cc.authHex(k), authHex);
  // the pure-JS fallback gives the same bytes
  const js = pbkdf2(sha256, new TextEncoder().encode(code), new TextEncoder().encode('couchpotato-household-v1'), { c: cc.ITERATIONS, dkLen: 64 });
  assert.equal(cc.hex(js.slice(0, 32)), authHex);
});

test('opens a blob from a TV and round-trips', async () => {
  const k = await cc.deriveKeys(code);
  const snap = JSON.parse(await cc.open(k, blob));
  assert.ok(snap.preferences.couchpotato.liveFavs);
  assert.equal(snap.iptv.user, 'u');
  const json = '{"version":1,"preferences":{"a":{"b":{"type":"string","value":"ü"}}}}';
  const sealed = await cc.seal(k, json);
  assert.equal(cc.unb64(sealed)[0], 1);
  assert.equal(await cc.open(k, sealed), json);
  assert.equal(await cc.open(await cc.deriveKeys('0000000000'), sealed), null);
});

test('link key tag like TvLink.kt', async () => {
  const k = await cc.deriveKeys(code);
  assert.equal(cc.linkTag(cc.linkKey(k)), 'dfaf2b0d5859');
});

test('code normalising', () => {
  assert.equal(cc.normalizeCode('7kq2m-x9p4t'), '7KQ2MX9P4T');
  assert.equal(cc.normalizeCode('o1l-I'), '0111');
});

test('merge keeps local change and remote rest', () => {
  const s = (v) => ({ type: 'string', value: v });
  const m = merge({ preferences: { f: { a: s('1'), b: s('1') } } }, { preferences: { f: { a: s('2'), b: s('1') } } }, { preferences: { f: { a: s('1'), b: s('3'), c: s('9') } } }).preferences.f;
  assert.equal(m.a.value, '2'); assert.equal(m.b.value, '3'); assert.equal(m.c.value, '9');
  assert.equal(canonical({ b: 1, a: [1, { y: 2, x: 1 }] }), canonical({ a: [1, { x: 1, y: 2 }], b: 1 }));
});

test('tsv like the TV', () => {
  const e = parseTSV('m\t42\tmkv\thttp://img/x.jpg\tDE: Der Pate\tder pate\t7\t1700000000\t9.2\tDrama\t1972\t238\n');
  assert.equal(e.length, 1);
  assert.equal(e[0].name, 'Der Pate'); assert.equal(e[0].ext, 'mkv'); assert.equal(e[0].tmdb, 238); assert.equal(e[0].categoryKey, 'm:7');
  const again = parseTSV(tsvLine(e[0]))[0];
  assert.equal(again.rating, 9.2); assert.equal(again.year, '1972');
});

test('names and search', () => {
  assert.equal(display('DE: Das Erste HD'), 'Das Erste HD');
  assert.equal(display('|DE| ZDF'), 'ZDF');
  assert.equal(display('CSI: Miami'), 'CSI: Miami');
  assert.equal(normalize('Café – Größe!'), 'cafe grosse');
  assert.equal(episode('Breaking Bad - S01E03 - Der Beutel', 'Breaking Bad', 3), 'Der Beutel');
  assert.equal(episode(null, 'X', 4), 'Folge 4');
  const c = parseTSV('m\t1\tmp4\t\tDer Pate\tder pate\t1\t0\nm\t2\tmp4\t\tPate\tpate\t1\t0\n');
  assert.equal(search(c, 'pate', () => true).m[0].itemId, 2);
});

test('provider links', () => {
  const a = loginFromLink('http://tv.example:8080/get.php?username=max&password=geheim&type=m3u_plus');
  assert.equal(a.server, 'http://tv.example:8080'); assert.equal(a.user, 'max');
  assert.equal(loginFromLink('tv.example:8080/live/max/geheim/123.ts').password, 'geheim');
});

test('series info and profiles', () => {
  const s = parseSeries({ info: { name: 'Show' }, episodes: { 1: [{ id: '5', episode_num: '2', title: 'Show - S01E02 - Zwei', container_extension: 'mkv' }, { id: '4', episode_num: '1', title: 'Eins' }] } }, 9, 'Show');
  assert.deepEqual(s.episodes.map((e) => e.id), [4, 5]);
  assert.equal(s.episodes[1].title, 'Zwei');
  assert.equal(animalIndex({ id: 'main', animal: -1 }), ((javaHash('main') % 12) + 12) % 12);
  assert.ok(isFinished(5500, 6000)); assert.ok(!isFinished(100, 6000));
});

function javaHash(s) { let h = 0; for (const ch of s) h = (Math.imul(h, 31) + ch.charCodeAt(0)) | 0; return h; }
