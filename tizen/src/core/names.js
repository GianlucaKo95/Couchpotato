// Display clean-up for provider names, like on the TVs: "DE: Das Erste", "|DE| Das Erste",
// "[DE] Das Erste", "DE - HD - Das Erste" → "Das Erste". Only known tags are removed.
const TAGS = 'DE|GER|DEU|GERMAN|AT|CH|EN|ENG|UK|US|USA|FR|IT|ES|NL|PL|TR|RU|AR|EX|EU|MULTI|DUAL|' +
  '4K|UHD|FHD|HD|SD|HEVC|NF|NETFLIX|AMZ|AMAZON|PRIME|DSNP|DISNEY|SKY|ATV|APPLE|HBO|MAX|VOD|SERIE|SERIES|FILM|MOVIE|KIDS';
const LEADING = new RegExp('^\\s*(?:\\|[^|]{1,14}\\||\\[[^\\]]{1,14}]|\\((?:' + TAGS + ')\\)|(?:' + TAGS + ')(?:\\s*[:|]\\s*|\\s+-\\s+|\\s+(?=\\|)))');
const SEP = ' -:|·–—_';

function trimChars(s, chars) {
  let a = 0, b = s.length;
  while (a < b && chars.indexOf(s[a]) >= 0) a++;
  while (b > a && chars.indexOf(s[b - 1]) >= 0) b--;
  return s.slice(a, b);
}

export function display(raw) {
  let s = String(raw || '');
  for (;;) {
    const m = LEADING.exec(s);
    if (!m || m[0].length >= s.length) break; // never strip everything
    s = s.slice(m[0].length);
  }
  const out = trimChars(s, SEP);
  return out || String(raw || '').trim();
}

const SXXEXX = /\bS\s?(\d{1,3})\s?[ ._-]?\s?E\s?(\d{1,4})\b/i;

/** "Breaking Bad - S01E03 - Der Beutel" → "Der Beutel". */
export function episode(raw, series, number) {
  const fallback = 'Folge ' + number;
  if (!raw) return fallback;
  let s = display(raw);
  const name = display(series).trim();
  if (name.length >= 2 && s.toLowerCase().startsWith(name.toLowerCase())) s = s.slice(name.length);
  s = trimChars(s, SEP);
  const m = SXXEXX.exec(s);
  if (m && m.index <= 40) s = s.slice(m.index + m[0].length);
  s = trimChars(s, SEP);
  return s || fallback;
}

/** "Café – Größe!" → "cafe grosse": case, accents and punctuation don't matter. */
export function normalize(s) {
  const folded = String(s).replace(/ß/g, 'ss').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  return folded.replace(NON_WORD, ' ').trim().replace(/\s+/g, ' ');
}

// letters and digits of every script where the TV's browser knows Unicode classes (Chromium 64+),
// otherwise Latin, Greek, Cyrillic, Arabic, Hebrew and CJK
const NON_WORD = (() => {
  try { return new RegExp('[^\\p{L}\\p{N}]+', 'gu'); } catch (e) {
    return /[^0-9a-z\u00c0-\u024f\u0370-\u03ff\u0400-\u04ff\u0590-\u06ff\u3040-\u30ff\u4e00-\u9fff\uac00-\ud7af]+/g;
  }
})();
