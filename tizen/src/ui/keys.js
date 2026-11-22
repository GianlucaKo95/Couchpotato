// Remote control keys. On a Samsung TV the media and colour keys must be registered first.
export const K = {
  LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009,
  PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412, PREV: 10232, NEXT: 10233,
  CH_UP: 427, CH_DOWN: 428, RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406, INFO: 457,
  IME_DONE: 65376, IME_CANCEL: 65385,
};

const NAMES = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
  'MediaTrackPrevious', 'MediaTrackNext', 'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green',
  'ColorF2Yellow', 'ColorF3Blue', 'Info', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];

export function registerKeys() {
  try {
    const dev = window.tizen && tizen.tvinputdevice;
    if (!dev) return;
    for (const n of NAMES) { try { dev.registerKey(n); } catch (e) { /* not on this remote */ } }
    const code = (n, fallback) => { try { return dev.getKey(n).code; } catch (e) { return fallback; } };
    K.PLAYPAUSE = code('MediaPlayPause', K.PLAYPAUSE); K.PLAY = code('MediaPlay', K.PLAY); K.PAUSE = code('MediaPause', K.PAUSE);
    K.STOP = code('MediaStop', K.STOP); K.FF = code('MediaFastForward', K.FF); K.RW = code('MediaRewind', K.RW);
    K.CH_UP = code('ChannelUp', K.CH_UP); K.CH_DOWN = code('ChannelDown', K.CH_DOWN);
  } catch (e) { /* plain browser */ }
}

/** Normalises keyboard keys in a desktop browser (for testing) to the remote's codes. */
export function keyOf(ev) {
  switch (ev.key) {
    case 'Escape': case 'Backspace': return ev.target && /INPUT|TEXTAREA/.test(ev.target.tagName) && ev.key === 'Backspace' ? 0 : K.BACK;
    case ' ': return ev.target && /INPUT|TEXTAREA/.test(ev.target.tagName) ? 0 : K.PLAYPAUSE;
    case 'PageUp': return K.CH_UP;
    case 'PageDown': return K.CH_DOWN;
    case 'r': return ev.target && /INPUT/.test(ev.target.tagName) ? 0 : K.RED;
    default: return ev.keyCode;
  }
}
