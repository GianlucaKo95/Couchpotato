// The request brake, like on the TVs and the iPhone: one request at a time, at least 2.5 s apart,
// at most 40 per hour (20 for work nobody waits for), and 15 minutes of complete silence after the
// provider answered with a lock code. Kept across app starts.
import { local } from './storage.js';

export const MIN_GAP = 2500;
export const MAX_PER_HOUR = 40;
export const BACKGROUND_PER_HOUR = 20;
export const COOLDOWN = 15 * 60 * 1000;
export const IMAGE_GAP = 1200;
export const IMAGES_PER_HOUR = 60;
export const LOCK_CODES = [401, 403, 404, 429, 509];

export class GateError extends Error {
  constructor(message, kind, until) { super(message); this.kind = kind; this.until = until; }
}

const hhmm = (t) => { const d = new Date(t); return d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0'); };

class RequestGate {
  constructor() {
    const now = Date.now();
    const recent = (a) => (Array.isArray(a) ? a : []).filter((t) => now - t < 3600e3 && t - now < 300e3);
    this.requests = recent(local.get('gate.requests'));
    this.images = recent(local.get('gate.images'));
    this.last = local.get('gate.last', 0);
    const until = local.get('gate.blocked', 0);
    this.blockedUntil = until - now <= COOLDOWN + 300e3 ? until : 0;
    this.queue = Promise.resolve();
  }

  run(op, { background = false, image = false } = {}) {
    const next = this.queue.then(() => this.pass(op, background, image));
    this.queue = next.catch(() => {});
    return next;
  }

  async pass(op, background, image) {
    const now = Date.now();
    if (now < this.blockedUntil) {
      throw new GateError(`Anbieter sperrt gerade – vermutlich wegen zu vieler Anfragen. Couchpotato pausiert und versucht es ab ${hhmm(this.blockedUntil)} Uhr wieder.`, 'blocked', this.blockedUntil);
    }
    this.prune(now);
    if (image) {
      if (this.images.length >= IMAGES_PER_HOUR) throw new GateError('Bilder-Limit erreicht', 'budget');
    } else if (this.requests.length >= (background ? BACKGROUND_PER_HOUR : MAX_PER_HOUR)) {
      throw new GateError(background
        ? 'Hintergrund-Abfragen pausiert (Stundenlimit erreicht) – geht später automatisch weiter.'
        : 'Zu viele Anfragen in der letzten Stunde – Couchpotato bremst, um eine Sperre beim Anbieter zu vermeiden. Bitte etwas später erneut versuchen.', 'budget');
    }
    const wait = this.last + (image ? IMAGE_GAP : MIN_GAP) - now;
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    try {
      return await op();
    } finally {
      this.last = Date.now();
      (image ? this.images : this.requests).push(this.last);
      this.persist();
    }
  }

  blockNow() {
    this.blockedUntil = Date.now() + COOLDOWN;
    this.persist();
    return this.blockedUntil;
  }

  clearBlock() { this.blockedUntil = 0; this.persist(); }

  /** Counts an image that the TV's picture element loads itself (provider host only). */
  takeImage() {
    const now = Date.now();
    this.prune(now);
    if (now < this.blockedUntil || this.images.length >= IMAGES_PER_HOUR) return false;
    this.images.push(now);
    this.persist();
    return true;
  }

  requestsLastHour() { this.prune(Date.now()); return this.requests.length; }
  imagesLastHour() { this.prune(Date.now()); return this.images.length; }

  prune(now) {
    const keep = (t) => now - t < 3600e3 && t - now < 300e3;
    this.requests = this.requests.filter(keep);
    this.images = this.images.filter(keep);
  }

  persist() {
    local.set('gate.requests', this.requests);
    local.set('gate.images', this.images);
    local.set('gate.last', this.last);
    local.set('gate.blocked', this.blockedUntil);
  }
}

export const gate = new RequestGate();
