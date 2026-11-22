// Household keys and sealing – byte for byte like the TVs (CloudSync.kt) and the iPhone:
// PBKDF2-HMAC-SHA256, 210 000 rounds, 64 bytes: first half = access key (the server only sees it),
// second half = AES-256-GCM key that never leaves the devices. Blob: 1 | iv(12) | ciphertext | tag(16),
// AAD = SHA-256(access key), payload = gzip(JSON).
// The TV's own crypto (WebCrypto) is used when the browser offers it; otherwise a pure-JS fallback.
import { pbkdf2Async } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';
import { hmac } from '@noble/hashes/hmac';
import { gcm } from '@noble/ciphers/aes';
import { gzipSync, gunzipSync, strToU8, strFromU8 } from 'fflate';

export const ITERATIONS = 210000;
const SALT = strToU8('couchpotato-household-v1');
const FORMAT = 1;

const subtle = (typeof crypto !== 'undefined' && crypto.subtle) || null;

export const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
export const fromHex = (s) => new Uint8Array((s.match(/../g) || []).map((h) => parseInt(h, 16)));

export function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export function unb64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function randomBytes(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return b;
}

/** Slow on purpose. Native PBKDF2 when available (well under a second), otherwise pure JS (may take a while). */
export async function deriveKeys(code, onProgress) {
  const pw = strToU8(code);
  let out = null;
  if (subtle) {
    try {
      const base = await subtle.importKey('raw', pw, 'PBKDF2', false, ['deriveBits']);
      out = new Uint8Array(await subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: SALT, iterations: ITERATIONS }, base, 512));
    } catch (e) {
      out = null;
    }
  }
  if (!out) {
    out = await pbkdf2Async(sha256, pw, SALT, { c: ITERATIONS, dkLen: 64, asyncTick: 50, onProgress });
  }
  return { auth: out.slice(0, 32), enc: out.slice(32, 64) };
}

export const authHex = (keys) => hex(keys.auth);
const aad = (keys) => sha256(keys.auth);

/** Key for talking to Couchpotato TVs in the same Wi-Fi (TvLink). */
export const linkKey = (keys) => hmac(sha256, keys.enc, strToU8('couchpotato-link-v1'));
export const linkTag = (lk) => hex(sha256(lk).slice(0, 6));

async function gcmEncrypt(key, iv, data, ad) {
  if (subtle) {
    try {
      const k = await subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
      return new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: ad, tagLength: 128 }, k, data));
    } catch (e) { /* fall through */ }
  }
  return gcm(key, iv, ad).encrypt(data);
}

async function gcmDecrypt(key, iv, data, ad) {
  if (subtle) {
    try {
      const k = await subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']);
      return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv, additionalData: ad, tagLength: 128 }, k, data));
    } catch (e) {
      if (e && e.name === 'OperationError') throw e; // wrong key – don't retry
    }
  }
  return gcm(key, iv, ad).decrypt(data);
}

/** JSON string → sealed base64 blob. */
export async function seal(keys, json) {
  const iv = randomBytes(12);
  const zipped = gzipSync(strToU8(json), { mtime: 0 });
  const box = await gcmEncrypt(keys.enc, iv, zipped, aad(keys)); // ciphertext | tag
  const out = new Uint8Array(1 + 12 + box.length);
  out[0] = FORMAT;
  out.set(iv, 1);
  out.set(box, 13);
  return b64(out);
}

/** Sealed blob → JSON string, or null when it isn't ours. */
export async function open(keys, blob) {
  try {
    const packed = unb64(blob);
    if (packed.length <= 1 + 12 + 16 || packed[0] !== FORMAT) return null;
    const plain = await gcmDecrypt(keys.enc, packed.slice(1, 13), packed.slice(13), aad(keys));
    return strFromU8(gunzipSync(plain));
  } catch (e) {
    return null;
  }
}

export function hmacHex(key, text) {
  return hex(hmac(sha256, key, strToU8(text)));
}

export function sha256Hex(text) {
  return hex(sha256(typeof text === 'string' ? strToU8(text) : text));
}

/** Same rules as the TVs: no I, L, O, U – typed letters that look alike are fixed. */
export function normalizeCode(code) {
  const fixed = String(code).toUpperCase().replace(/O/g, '0').replace(/I/g, '1').replace(/L/g, '1');
  return fixed.replace(/[^0-9ABCDEFGHJKMNPQRSTVWXYZ]/g, '');
}

export const CODE_LENGTH = 10;
export const displayCode = (c) => c.slice(0, 5) + '-' + c.slice(5);
