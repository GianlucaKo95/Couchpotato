// Spatial navigation for the remote: arrows move to the nearest focusable element in that direction.
// Rows (data-row) remember their last item, so going up and down between rows feels like on the TVs.
const remembered = new Map();
let current = null;
let layer = null; // the element that currently owns the focus (a screen or a dialog)

export function setLayer(el) { layer = el; }
export function getLayer() { return layer; }
export const focused = () => (current && current.isConnected ? current : null);

function visible(el) {
  if (!el.isConnected || el.offsetParent === null) return false;
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0;
}

export function candidates(root = layer) {
  if (!root) return [];
  return Array.from(root.querySelectorAll('.focusable')).filter((el) => !el.classList.contains('disabled') && visible(el));
}

export function focus(el, opts = {}) {
  if (!el) return;
  if (current && current !== el) { current.classList.remove('focused'); current.dispatchEvent(new CustomEvent('blurred')); }
  current = el;
  el.classList.add('focused');
  const row = el.closest('[data-row]');
  if (row) remembered.set(row.dataset.row, el.dataset.fid || null);
  if (!opts.noScroll) reveal(el);
  el.dispatchEvent(new CustomEvent('focused'));
}

/** Focuses the element with this data-fid, or the first focusable. */
export function restore(fid, root = layer) {
  const list = candidates(root);
  const el = (fid && list.find((x) => x.dataset.fid === fid)) || list.find((x) => x.dataset.autofocus != null) || list[0];
  if (el) focus(el);
  return el;
}

export function move(dir) {
  const from = focused();
  const list = candidates();
  if (!from || !list.includes(from)) { if (list[0]) focus(list[0]); return !!list[0]; }
  const a = from.getBoundingClientRect();
  const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bestScore = Infinity;
  for (const el of list) {
    if (el === from) continue;
    const b = el.getBoundingClientRect();
    const bx = b.left + b.width / 2, by = b.top + b.height / 2;
    let main, gap, off;
    switch (dir) {
      case 'left': if (b.right > a.left + 1) continue; main = a.left - b.right; gap = Math.max(0, b.top - a.bottom, a.top - b.bottom); off = Math.abs(by - ay); break;
      case 'right': if (b.left < a.right - 1) continue; main = b.left - a.right; gap = Math.max(0, b.top - a.bottom, a.top - b.bottom); off = Math.abs(by - ay); break;
      case 'up': if (b.bottom > a.top + 1) continue; main = a.top - b.bottom; gap = Math.max(0, b.left - a.right, a.left - b.right); off = Math.abs(bx - ax); break;
      default: if (b.top < a.bottom - 1) continue; main = b.top - a.bottom; gap = Math.max(0, b.left - a.right, a.left - b.right); off = Math.abs(bx - ax); break;
    }
    // elements that overlap sideways are "in line"; among those the nearest wins
    const score = Math.max(main, 0) + gap * 4 + off * 0.15;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  if (!best) return false;
  // entering another row: back to the item that was focused there last
  if (dir === 'up' || dir === 'down') {
    const row = best.closest('[data-row]');
    const fromRow = from.closest('[data-row]');
    if (row && row !== fromRow) {
      const fid = remembered.get(row.dataset.row);
      const again = fid && list.find((x) => x.dataset.fid === fid && row.contains(x));
      // a row visited before: back to its last item; a new one: its first item
      if (again) best = again;
      else { const first = list.find((x) => row.contains(x)); if (first) best = first; }
    }
  }
  focus(best);
  return true;
}

/** Keeps the focused element in view: horizontal rows scroll sideways, pages scroll up and down. */
export function reveal(el) {
  const hs = el.closest('.hscroll');
  if (hs) {
    const pad = 80;
    const left = el.offsetLeft - hs.scrollLeft;
    if (left < pad) hs.scrollLeft = Math.max(el.offsetLeft - pad, 0);
    else if (left + el.offsetWidth > hs.clientWidth - pad) hs.scrollLeft = el.offsetLeft + el.offsetWidth - hs.clientWidth + pad;
  }
  const vs = el.closest('.vscroll');
  if (vs) {
    const top = offsetIn(el, vs);
    const pad = vs.dataset.pad ? Number(vs.dataset.pad) : 120;
    if (top - vs.scrollTop < pad) vs.scrollTop = Math.max(top - pad, 0);
    else if (top + el.offsetHeight - vs.scrollTop > vs.clientHeight - pad) vs.scrollTop = top + el.offsetHeight - vs.clientHeight + pad;
  }
}

function offsetIn(el, ancestor) {
  let y = 0, n = el;
  while (n && n !== ancestor) { y += n.offsetTop; n = n.offsetParent; if (n && !ancestor.contains(n) && n !== ancestor) break; }
  return y;
}
