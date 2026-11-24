/* Injected into every page the TV loads. Idempotent. Talks to the page's own
 * <video> element (main frame + same-origin iframes). Cross-origin player
 * iframes cannot be scripted; the app falls back to real taps / key presses. */
(function () {
  if (window.__nc) return;
  var nc = {};

  function area(el) {
    var r = el.getBoundingClientRect();
    return Math.max(0, r.width) * Math.max(0, r.height);
  }

  function frac(el) {
    var r = el.getBoundingClientRect();
    return {
      x: (r.left + r.width / 2) / window.innerWidth,
      y: (r.top + r.height / 2) / window.innerHeight
    };
  }

  function allVideos() {
    var list = Array.prototype.slice.call(document.querySelectorAll('video'));
    var frames = document.querySelectorAll('iframe');
    for (var i = 0; i < frames.length; i++) {
      try {
        var d = frames[i].contentDocument;
        if (d) list = list.concat(Array.prototype.slice.call(d.querySelectorAll('video')));
      } catch (e) { /* cross-origin */ }
    }
    return list;
  }

  nc.video = function () {
    var v = allVideos();
    if (!v.length) return null;
    for (var i = 0; i < v.length; i++) if (!v[i].paused) return v[i];
    v.sort(function (a, b) { return area(b) - area(a); });
    return v[0];
  };

  /** Biggest visible <video> or <iframe>: our best guess at "the player". */
  nc.player = function () {
    var els = Array.prototype.slice.call(document.querySelectorAll('video, iframe'))
      .filter(function (e) { return area(e) > 40000; });
    els.sort(function (a, b) { return area(b) - area(a); });
    return els[0] || null;
  };

  var FILL_CSS =
    '.__nc_fill{position:fixed!important;left:0!important;top:0!important;' +
    'width:100vw!important;height:100vh!important;max-width:none!important;' +
    'max-height:none!important;z-index:2147483646!important;background:#000!important;' +
    'object-fit:contain!important;margin:0!important;border:0!important;transform:none!important}' +
    'html.__nc_lock,html.__nc_lock body{overflow:hidden!important}';

  nc.fill = function () {
    var on = document.querySelector('.__nc_fill');
    if (on) {
      on.classList.remove('__nc_fill');
      document.documentElement.classList.remove('__nc_lock');
      return { handled: true, filled: false };
    }
    var p = nc.player();
    if (!p) return { handled: false, filled: false };
    if (!document.getElementById('__nc_style')) {
      var st = document.createElement('style');
      st.id = '__nc_style';
      st.textContent = FILL_CSS;
      (document.head || document.documentElement).appendChild(st);
    }
    p.classList.add('__nc_fill');
    document.documentElement.classList.add('__nc_lock');
    return { handled: true, filled: true };
  };

  nc.act = function (action, value) {
    if (action === 'fill') return nc.fill();
    var v = nc.video();
    if (!v) {
      var p = nc.player();
      return { handled: false, player: p ? frac(p) : null };
    }
    switch (action) {
      case 'toggle': if (v.paused) v.play(); else v.pause(); break;
      case 'play': v.play(); break;
      case 'pause': v.pause(); break;
      case 'seek': v.currentTime = Math.max(0, Math.min((v.duration || 1e9), v.currentTime + value)); break;
      case 'seekto': if (v.duration) v.currentTime = v.duration * value; break;
      case 'rate': v.playbackRate = value; break;
      case 'mute': v.muted = !v.muted; break;
    }
    return { handled: true };
  };

  nc.state = function () {
    var v = nc.video();
    var p = nc.player();
    var s = {
      hasVideo: !!v,
      player: !!p,
      filled: !!document.querySelector('.__nc_fill')
    };
    if (v) {
      s.paused = v.paused;
      s.time = v.currentTime || 0;
      s.duration = isFinite(v.duration) ? v.duration : 0;
      s.rate = v.playbackRate;
      s.muted = v.muted;
    }
    return s;
  };

  /** Scroll the nearest scrollable thing under (fx,fy), else the page. */
  nc.scroll = function (fx, fy, dy) {
    var el = document.elementFromPoint(fx * window.innerWidth, fy * window.innerHeight);
    while (el && el !== document.body && el !== document.documentElement) {
      var cs = window.getComputedStyle(el);
      if (/(auto|scroll)/.test(cs.overflowY) && el.scrollHeight > el.clientHeight + 2) {
        el.scrollBy({ top: dy, behavior: 'smooth' });
        return true;
      }
      el = el.parentElement;
    }
    (document.scrollingElement || document.body).scrollBy({ top: dy, behavior: 'smooth' });
    return true;
  };

  /** Fallback typing for characters the virtual keyboard map can't produce. */
  nc.insertText = function (t) {
    var a = document.activeElement;
    if (a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA')) {
      var proto = a.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      var start = a.selectionStart == null ? a.value.length : a.selectionStart;
      var end = a.selectionEnd == null ? a.value.length : a.selectionEnd;
      setter.call(a, a.value.slice(0, start) + t + a.value.slice(end));
      a.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    }
    return document.execCommand('insertText', false, t);
  };

  window.__nc = nc;
})();
