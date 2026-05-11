// dark.realm.watch — system-aware dark/light theming engine.
// Public API: window.Dark.{current, effective, set, cycle, config}.
// Three states: 'light', 'dark', 'system' (system = absence of stored value).

(function () {
  var VALID = ['light', 'dark', 'system'];
  var storageKey = 'drw-theme';
  var mediaQuery = null;

  function readPref() {
    try {
      var v = localStorage.getItem(storageKey);
      return v === 'light' || v === 'dark' ? v : 'system';
    } catch (_) {
      return 'system';
    }
  }

  function writePref(v) {
    try {
      if (v === 'system') localStorage.removeItem(storageKey);
      else localStorage.setItem(storageKey, v);
    } catch (_) {}
  }

  function effective() {
    var p = readPref();
    if (p !== 'system') return p;
    if (!mediaQuery) mediaQuery = matchMedia('(prefers-color-scheme: dark)');
    return mediaQuery.matches ? 'dark' : 'light';
  }

  function apply() {
    var p = readPref();
    var root = document.documentElement;
    if (p === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', p);
    document.dispatchEvent(new CustomEvent('dark:change', {
      detail: { theme: p, effective: effective() },
    }));
  }

  function set(v) {
    if (VALID.indexOf(v) === -1) {
      throw new Error('Dark.set: invalid value: ' + v);
    }
    writePref(v);
    apply();
  }

  function cycle() {
    var p = readPref();
    var next = p === 'system' ? 'dark' : p === 'dark' ? 'light' : 'system';
    set(next);
  }

  function config(opts) {
    if (opts && typeof opts.storage === 'string') {
      storageKey = opts.storage;
      apply();
    }
  }

  function init() {
    if (!mediaQuery) mediaQuery = matchMedia('(prefers-color-scheme: dark)');
    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener('change', function () {
        if (readPref() === 'system') apply();
      });
    }
    apply();
  }

  window.Dark = {
    current: readPref,
    effective: effective,
    set: set,
    cycle: cycle,
    config: config,
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
