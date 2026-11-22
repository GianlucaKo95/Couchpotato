// What 2018 Samsung TVs (Chromium 56) lack.
if (!String.prototype.padStart) {
  String.prototype.padStart = function padStart(len, fill) {
    let s = String(this);
    const f = fill == null ? ' ' : String(fill);
    while (s.length < len) s = f + s;
    return s.length > len && f.length > 1 ? s.slice(s.length - len) : s;
  };
}
if (!Object.values) Object.values = (o) => Object.keys(o).map((k) => o[k]);
if (!Object.entries) Object.entries = (o) => Object.keys(o).map((k) => [k, o[k]]);
if (!Element.prototype.closest) {
  Element.prototype.closest = function closest(sel) {
    let el = this;
    while (el && el.nodeType === 1) { if (el.matches(sel)) return el; el = el.parentElement; }
    return null;
  };
}
if (!('isConnected' in Node.prototype)) {
  Object.defineProperty(Node.prototype, 'isConnected', { get() { return document.documentElement.contains(this); } });
}
if (!Element.prototype.append) {
  Element.prototype.append = function append(...nodes) {
    for (const n of nodes) this.appendChild(typeof n === 'string' ? document.createTextNode(n) : n);
  };
}
if (!Element.prototype.replaceWith) {
  Element.prototype.replaceWith = function replaceWith(n) { if (this.parentNode) this.parentNode.replaceChild(n, this); };
}
if (!Element.prototype.remove) {
  Element.prototype.remove = function remove() { if (this.parentNode) this.parentNode.removeChild(this); };
}
