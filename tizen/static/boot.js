/* Couchpotato loader. Starts the newest app version this TV has: the one inside the installed package,
 * or a newer one the app downloaded itself (self-update, like the Fire TV app). If a downloaded version
 * fails to start, the next start falls back to the installed one and skips that version. ES5 on purpose. */
(function () {
  var BUILD = Number('__BUILD__') || 0;
  var ls = null;
  try { ls = window.localStorage; } catch (e) { ls = null; }
  function get(k) { try { return ls ? ls.getItem(k) : null; } catch (e) { return null; } }
  function set(k, v) { try { if (ls) { if (v === null) ls.removeItem(k); else ls.setItem(k, v); } } catch (e) { /* full */ } }

  var cached = Number(get('cp.code.build')) || 0;
  var pending = Number(get('cp.code.pending')) || 0;
  if (pending && pending === cached) {
    // the downloaded version did not report a healthy start last time: never use it again
    set('cp.code.bad', String(cached));
    set('cp.code.build', null); set('cp.code.js', null); set('cp.code.css', null); set('cp.code.pending', null);
    cached = 0;
  }
  var js = cached > BUILD ? get('cp.code.js') : null;
  var css = cached > BUILD ? get('cp.code.css') : null;
  window.__cpBoot = { bundled: BUILD, running: js && css ? cached : BUILD };

  if (js && css) {
    set('cp.code.pending', String(cached));
    var st = document.createElement('style');
    st.textContent = css;
    document.head.appendChild(st);
    var sc = document.createElement('script');
    sc.text = js;
    document.body.appendChild(sc);
  } else {
    var l = document.createElement('link');
    l.rel = 'stylesheet'; l.href = 'style.css';
    document.head.appendChild(l);
    var s = document.createElement('script');
    s.src = 'app.js';
    document.body.appendChild(s);
  }
})();
