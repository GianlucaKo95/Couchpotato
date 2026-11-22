// Shared building blocks: pictures (provider images go through the brake), poster and card rows.
import { h, icon } from './dom.js';
import { model } from '../core/model.js';
import { gate } from '../core/gate.js';
import { local } from '../core/storage.js';

const allowedImages = new Set();

/** Picture that respects the provider's limits: images from the provider host count against 60/hour. */
export function img(url, cls = '', fallback = null) {
  const box = h('div.pic', { class: cls });
  if (fallback) box.appendChild(fallback);
  if (!url || !/^https?:/.test(url)) return box;
  let host = null;
  try { host = new URL(url).hostname.toLowerCase(); } catch (e) { return box; }
  if (host && host === model.providerHost && !allowedImages.has(url)) {
    if (!local.get('pref.providerImages', true) || !gate.takeImage()) return box;
    allowedImages.add(url);
  }
  const i = h('img', { alt: '' });
  i.onload = () => box.classList.add('loaded');
  i.onerror = () => i.remove();
  i.src = url;
  box.appendChild(i);
  return box;
}

const initials = (name) => h('div.initials', null, String(name || '').trim());

export function posterCard(e, onclick, fid) {
  return h('button.poster.focusable', { dataset: { fid: fid || 'p-' + e.id }, onclick },
    img(e.image, 'poster-pic', initials(e.name)),
    model.onList(e) ? h('div.badge', null, icon('check')) : null,
    h('div.caption', null, e.name));
}

export function continueCard(p, onclick, fid) {
  const frac = p.duration > 0 ? Math.min(Math.max(p.position / p.duration, 0), 1) : 0;
  const remaining = p.duration > 0 ? Math.max(Math.round((p.duration - p.position) / 60), 1) : null;
  return h('button.wide.focusable', { dataset: { fid: fid || 'c-' + p.item.key }, onclick },
    img(p.item.image, 'wide-pic', h('div.wide-ph', null, icon('film'))),
    frac > 0 ? h('div.progress', null, h('div.bar', { style: { width: (frac * 100).toFixed(1) + '%' } })) : null,
    h('div.caption', null, p.item.title),
    h('div.sub', null, p.upNext ? 'Nächste Folge' : p.item.subtitle || (remaining ? `noch ${remaining} Min.` : '')));
}

export function channelCard(c, onclick, fid, now) {
  return h('button.channel.focusable', { dataset: { fid: fid || 'ch-' + c.id }, onclick },
    h('div.logo-tile', null, img(c.logo, 'logo-pic', h('div.ch-name', null, c.name))),
    h('div.caption', null, c.name),
    now ? h('div.sub', null, now) : null);
}

/** A titled horizontal row. */
export function row(id, title, cards) {
  if (!cards.length) return null;
  return h('section.row', null,
    h('h2.row-title', null, title),
    h('div.hscroll', { dataset: { row: id, sid: 'row-' + id } }, h('div.row-items', null, cards)));
}

export function button(label, onclick, opts = {}) {
  return h('button.btn.focusable', { class: opts.primary ? 'primary' : '', dataset: { fid: opts.fid, autofocus: opts.autofocus ? '' : null }, onclick },
    opts.icon ? icon(opts.icon) : null, label ? h('span', null, label) : null);
}

export function emptyState(title, text, action) {
  return h('div.empty', null, h('h2', null, title), text ? h('p.muted', null, text) : null, action || null);
}
