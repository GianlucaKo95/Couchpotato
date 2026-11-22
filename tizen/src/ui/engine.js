// Video engine: Samsung's AVPlay on the TV (plays HLS, MPEG-TS, MP4, MKV with all audio tracks),
// a plain <video> in a desktop browser for testing. One player for the whole app.
const hasAvplay = () => !!(window.webapis && webapis.avplay);

class Engine {
  constructor() {
    this.listeners = {};
    this.url = null;
    this.rect = null;
    this.video = null;
  }

  on(ev, fn) { this.listeners[ev] = fn; }
  emit(ev, ...a) { const fn = this.listeners[ev]; if (fn) fn(...a); }

  get native() { return hasAvplay(); }

  /** Opens and plays; rect = [x, y, w, h] in 1920×1080 or null for full screen. */
  open(url, { start = 0, rect = null } = {}) {
    this.close();
    this.url = url;
    this.rect = rect;
    this.ended = false;
    if (hasAvplay()) return this.openAvplay(url, start);
    return this.openVideo(url, start);
  }

  openAvplay(url, start) {
    const av = webapis.avplay;
    const obj = document.getElementById('av');
    obj.style.display = 'block';
    av.open(url);
    this.applyRect();
    try { av.setDisplayMethod(this.fit || 'PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch (e) { /* older TVs */ }
    av.setListener({
      onbufferingstart: () => this.emit('buffering', true),
      onbufferingcomplete: () => this.emit('buffering', false),
      onbufferingprogress: () => {},
      oncurrentplaytime: (ms) => this.emit('time', ms / 1000),
      onstreamcompleted: () => { this.ended = true; this.emit('ended'); },
      onerror: (e) => this.emit('error', String(e)),
      onevent: () => {},
      onsubtitlechange: (dur, text) => this.emit('subtitle', text, dur),
      ondrmevent: () => {},
    });
    return new Promise((resolve, reject) => {
      av.prepareAsync(() => {
        try {
          if (start > 1) av.seekTo(Math.floor(start * 1000));
          av.play();
          this.emit('buffering', false);
          resolve();
        } catch (e) { reject(e); }
      }, (e) => { this.emit('error', String(e)); reject(new Error(String(e))); });
    });
  }

  openVideo(url, start) {
    const v = this.video || (this.video = document.getElementById('video'));
    v.style.display = 'block';
    this.applyRect();
    v.onwaiting = () => this.emit('buffering', true);
    v.onplaying = () => this.emit('buffering', false);
    v.ontimeupdate = () => this.emit('time', v.currentTime);
    v.onended = () => { this.ended = true; this.emit('ended'); };
    v.onerror = () => this.emit('error', 'Video konnte nicht geladen werden');
    v.src = url;
    if (start > 1) v.currentTime = start;
    // a failed start is reported once, through onerror
    return v.play().catch(() => {});
  }

  setRect(rect) { this.rect = rect; this.applyRect(); }

  applyRect() {
    const [x, y, w, h] = this.rect || [0, 0, 1920, 1080];
    if (hasAvplay()) {
      const obj = document.getElementById('av');
      Object.assign(obj.style, { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' });
      // AVPlay's rectangle is based on 1920×1080 like the page (also on 4K TVs)
      try { webapis.avplay.setDisplayRect(Math.round(x), Math.round(y), Math.round(w), Math.round(h)); } catch (e) { /* not ready */ }
    } else if (this.video) {
      Object.assign(this.video.style, { left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px' });
    }
  }

  get time() {
    try { return hasAvplay() ? webapis.avplay.getCurrentTime() / 1000 : (this.video ? this.video.currentTime : 0); } catch (e) { return 0; }
  }

  get duration() {
    try {
      if (hasAvplay()) { const d = webapis.avplay.getDuration(); return d > 0 ? d / 1000 : 0; }
      return this.video && isFinite(this.video.duration) ? this.video.duration : 0;
    } catch (e) { return 0; }
  }

  get paused() {
    try { return hasAvplay() ? webapis.avplay.getState() === 'PAUSED' : (this.video ? this.video.paused : true); } catch (e) { return false; }
  }

  get active() {
    try { return hasAvplay() ? !['NONE', 'IDLE'].includes(webapis.avplay.getState()) : !!(this.video && this.video.src); } catch (e) { return false; }
  }

  play() { try { if (hasAvplay()) webapis.avplay.play(); else this.video.play(); } catch (e) { /* ignore */ } }
  pause() { try { if (hasAvplay()) webapis.avplay.pause(); else this.video.pause(); } catch (e) { /* ignore */ } }
  toggle() { if (this.paused) this.play(); else this.pause(); }

  seek(sec) {
    const target = Math.max(0, this.duration ? Math.min(sec, this.duration - 2) : sec);
    try {
      if (hasAvplay()) webapis.avplay.seekTo(Math.floor(target * 1000), () => {}, () => {});
      else this.video.currentTime = target;
    } catch (e) { /* ignore */ }
  }

  /** Audio / subtitle tracks: [{index, kind:'AUDIO'|'TEXT', label}] */
  tracks() {
    if (!hasAvplay()) return [];
    try {
      return webapis.avplay.getTotalTrackInfo().filter((t) => t.type === 'AUDIO' || t.type === 'TEXT').map((t) => {
        let info = {};
        try { info = JSON.parse(t.extra_info || '{}'); } catch (e) { /* plain text */ }
        const lang = info.language || info.track_lang || '';
        return { index: t.index, kind: t.type, label: languageName(lang) || (t.type === 'AUDIO' ? 'Tonspur ' : 'Untertitel ') + (t.index + 1) };
      });
    } catch (e) { return []; }
  }

  currentTrack(kind) {
    if (!hasAvplay()) return -1;
    try {
      const cur = webapis.avplay.getCurrentStreamInfo().find((t) => t.type === kind);
      return cur ? cur.index : -1;
    } catch (e) { return -1; }
  }

  selectTrack(kind, index) {
    if (!hasAvplay()) return;
    try {
      if (kind === 'TEXT' && index < 0) { webapis.avplay.setSilentSubtitle(true); this.emit('subtitle', ''); return; }
      if (kind === 'TEXT') webapis.avplay.setSilentSubtitle(false);
      webapis.avplay.setSelectTrack(kind, index);
    } catch (e) { /* not supported by this stream */ }
  }

  /** Picture format: letterbox (whole picture), zoom (fill the screen), stretch. */
  setFit(mode) {
    this.fit = { fit: 'PLAYER_DISPLAY_MODE_LETTER_BOX', zoom: 'PLAYER_DISPLAY_MODE_FULL_SCREEN', auto: 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO' }[mode] || 'PLAYER_DISPLAY_MODE_LETTER_BOX';
    if (hasAvplay()) { try { webapis.avplay.setDisplayMethod(this.fit); } catch (e) { /* ignore */ } }
    else if (this.video) this.video.style.objectFit = mode === 'zoom' ? 'cover' : 'contain';
  }

  close() {
    if (hasAvplay()) {
      try { webapis.avplay.stop(); } catch (e) { /* ignore */ }
      try { webapis.avplay.close(); } catch (e) { /* ignore */ }
      const obj = document.getElementById('av');
      if (obj) obj.style.display = 'none';
    } else if (this.video) {
      // detach first: emptying the source fires an error we must not report
      for (const ev of ['onwaiting', 'onplaying', 'ontimeupdate', 'onended', 'onerror']) this.video[ev] = null;
      this.video.pause();
      this.video.removeAttribute('src');
      try { this.video.load(); } catch (e) { /* ignore */ }
      this.video.style.display = 'none';
    }
    this.url = null;
  }
}

const LANGS = { de: 'Deutsch', ger: 'Deutsch', deu: 'Deutsch', en: 'Englisch', eng: 'Englisch', fr: 'Französisch', fre: 'Französisch', fra: 'Französisch',
  it: 'Italienisch', ita: 'Italienisch', es: 'Spanisch', spa: 'Spanisch', tr: 'Türkisch', tur: 'Türkisch', pl: 'Polnisch', pol: 'Polnisch',
  ru: 'Russisch', rus: 'Russisch', nl: 'Niederländisch', dut: 'Niederländisch', nld: 'Niederländisch', ar: 'Arabisch', ara: 'Arabisch', ja: 'Japanisch', jpn: 'Japanisch' };
function languageName(code) {
  const c = String(code || '').toLowerCase();
  return LANGS[c] || (c && c !== 'und' ? c.toUpperCase() : '');
}

export const engine = new Engine();
