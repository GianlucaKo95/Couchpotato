// "Wer schaut?" – like on the TVs. Kids profiles are left only with the parents' PIN.
import { h, clear, icon } from '../dom.js';
import { app, avatar } from '../app.js';
import { model } from '../../core/model.js';
import { askPin } from '../widgets.js';

export async function chooseProfile(id) {
  const cur = model.profile;
  const target = model.profiles.find((p) => p.id === id);
  if (!target || target.id === cur.id) return true;
  if (cur.kid) {
    const pin = await askPin('Eltern-PIN, um das Kinderprofil zu verlassen', { setNew: !model.pinSet, check: (p) => model.checkPin(p) });
    if (pin == null) return false;
    if (!model.pinSet) model.setPin(pin);
  } else if (target.kid && !model.pinSet) {
    const pin = await askPin('Lege zuerst eine Eltern-PIN fest – damit verlässt man das Kinderprofil wieder', { setNew: true });
    if (pin == null) return false;
    model.setPin(pin);
  }
  model.chooseProfileDirect(id);
  return true;
}

export class ProfilesScreen {
  constructor() { this.defaultFid = 'pr-' + model.profile.id; this.fullscreen = true; }

  render() {
    const el = clear(this.el);
    el.className = 'screen profiles';
    el.append(
      h('h1', null, 'Wer schaut?'),
      h('div.profile-grid', { dataset: { row: 'profiles' } }, model.profiles.map((p) => h('button.profile.focusable', {
        class: p.id === model.profile.id ? 'current' : '', dataset: { fid: 'pr-' + p.id },
        onclick: async () => { if (await chooseProfile(p.id)) app.openTab('home'); },
      }, avatar(p, 'big'), h('div.p-name', null, p.name), p.kid ? h('div.p-kid', null, 'Kinderprofil') : null))),
      h('div.buttons.center', { dataset: { row: 'pbtns' } },
        h('button.btn.focusable', { dataset: { fid: 'manage' }, onclick: () => app.openTab('settings', { page: 'profiles' }) }, icon('gear'), h('span', null, 'Profile verwalten'))));
  }
}
