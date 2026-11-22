// Form pieces for the remote: text fields (open the TV keyboard), toggles, choices, PIN pad.
import { h, clear, icon } from './dom.js';
import { app } from './app.js';
import { K } from './keys.js';
import * as F from './focus.js';

/** A text field: OK opens the TV's keyboard; Enter / "Fertig" closes it. */
export function field(label, value, { type = 'text', placeholder = '', fid, onchange, onsubmit } = {}) {
  const input = h('input', { type, value: value || '', placeholder, autocomplete: 'off', spellcheck: 'false' });
  input.addEventListener('input', () => onchange && onchange(input.value));
  input.addEventListener('submit', () => onsubmit && onsubmit(input.value));
  const wrap = h('div.field.focusable', { dataset: { fid }, onclick: () => { input.focus(); try { input.setSelectionRange(input.value.length, input.value.length); } catch (e) { /* number fields */ } } },
    h('label', null, label), input);
  return wrap;
}

export function toggle(label, on, onflip, { fid, sub } = {}) {
  return h('button.setting.focusable', { dataset: { fid }, onclick: () => onflip(!on) },
    h('span.s-label', null, label, sub ? h('small', null, sub) : null),
    h('span.switch', { class: on ? 'on' : '' }, h('span.dot')));
}

export function action(label, onclick, { fid, sub, value, danger } = {}) {
  return h('button.setting.focusable', { class: danger ? 'danger' : '', dataset: { fid }, onclick },
    h('span.s-label', null, label, sub ? h('small', null, sub) : null),
    value != null ? h('span.s-value', null, value) : h('span.chev', null, '›'));
}

/** Picks one of several values in a dialog. */
export function choice(label, options, current, onpick, { fid, sub } = {}) {
  const cur = options.find((o) => o[0] === current);
  return action(label, () => {
    let d;
    d = app.dialog([h('h2', null, label), h('div.vscroll.list', null, options.map(([v, text]) => h('button.option.focusable', {
      class: v === current ? 'on' : '', dataset: { autofocus: v === current ? '' : null },
      onclick: () => { d.close(); onpick(v); },
    }, v === current ? icon('check') : h('span.ic-space'), h('span', null, text))))], { cls: 'panel-right' });
  }, { fid, sub, value: cur ? cur[1] : '' });
}

/** Asks for the parents' 4-digit PIN. resolve(pin) or resolve(null). */
export function askPin(title, { setNew = false, check } = {}) {
  return new Promise((resolve) => {
    let pin = '', first = null, d;
    const dots = h('div.pin-dots');
    const msg = h('p.muted', null, setNew ? 'Neue Eltern-PIN (4 Ziffern)' : '');
    const draw = () => { clear(dots); for (let i = 0; i < 4; i++) dots.appendChild(h('span', { class: i < pin.length ? 'on' : '' })); };
    const add = (digit) => {
      if (pin.length >= 4) return;
      pin += digit;
      draw();
      if (pin.length < 4) return;
      setTimeout(() => {
        if (setNew && first == null) { first = pin; pin = ''; msg.textContent = 'Zur Sicherheit noch einmal'; draw(); return; }
        if (setNew && first !== pin) { first = null; pin = ''; msg.textContent = 'Die PINs waren nicht gleich – bitte neu eingeben'; draw(); return; }
        if (!setNew && check && !check(pin)) { pin = ''; msg.textContent = 'Falsche PIN'; draw(); return; }
        d.close();
        resolve(pin);
      }, 150);
    };
    const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', '✓'].map((k) => h('button.key.focusable', {
      dataset: { fid: 'k' + k, autofocus: k === '5' ? '' : null },
      onclick: () => { if (k === '⌫') { pin = pin.slice(0, -1); draw(); } else if (k !== '✓') add(k); },
    }, k));
    draw();
    d = app.dialog([h('h2', null, title), msg, dots, h('div.keypad', null, keys)], { onBack: () => { d.close(); resolve(null); } });
    d.onKey = (code) => {
      if (code >= 48 && code <= 57) { add(String(code - 48)); return true; }
      return false;
    };
    F.restore('k5', d.box);
  });
}

export function section(title, ...children) {
  return h('section.settings-section', null, title ? h('h2', null, title) : null, children);
}

export const KEYS = K;
