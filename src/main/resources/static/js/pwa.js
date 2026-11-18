(function () {
  // The wording of the install prompt arrives as data-install-* attributes on this
  // script's own tag (both page layouts fill them from the i18n bundles), so it must be
  // read while the script runs: document.currentScript is null afterwards.
  var selfTag = document.currentScript;

  if ('serviceWorker' in navigator) { navigator.serviceWorker.register('/sw.js').catch(function () {}); }

  // ---- R43: "Install the iEvent app" prompt for phone and tablet browsers ----
  // (R16 had a banner, R26 retired it; the owner asked for it back.)
  // Android / Chromium: the browser fires beforeinstallprompt when the site can be
  //   installed. Its own mini-infobar is suppressed and our card takes its place; the
  //   Install button then opens the real system dialog.
  // iPhone / iPad Safari: there is no such event and no way to open the dialog from a
  //   page, so the card explains the two taps instead (Share, then Add to Home Screen).
  // Never shown: when already running as an installed app, on a desktop browser (it has
  //   its own install button in the address bar), inside in-app browsers that cannot
  //   install (Instagram, Facebook...), and on the pages where someone is filling in a
  //   form (sign-in, sign-up, checkout, organizer onboarding, the event wizard and the
  //   edit page), where it would sit on top of the work in hand.
  // "Not now" (or Close) hides it for 14 days; once shown and left alone it stays away
  //   for the rest of the browser session; after an install it is gone for good.
  (function installPrompt() {
    var DISMISS_KEY = 'ievent.installDismissedAt';
    var SESSION_KEY = 'ievent.installShown';
    var DISMISS_DAYS = 14;
    var deferred = null;
    var card = null;

    window.addEventListener('beforeinstallprompt', function (e) {
      e.preventDefault();
      deferred = e;
      if (eligible()) setTimeout(function () { show('android'); }, 1200);
    });
    window.addEventListener('appinstalled', function () {
      deferred = null;
      hide();
      store(localStorage, DISMISS_KEY, String(Date.now() + 3650 * 86400000));
    });

    function read(storage, key) { try { return storage.getItem(key); } catch (err) { return null; } }
    function store(storage, key, value) { try { storage.setItem(key, value); } catch (err) { /* private mode */ } }
    function text(name) { return (selfTag && selfTag.getAttribute('data-install-' + name)) || ''; }

    var ua = navigator.userAgent || '';
    // iPadOS reports itself as a Mac; the touch points give it away
    var isIOS = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
    var isAndroid = /Android/.test(ua);
    // Safari proper carries "Version/x Safari/y"; Chrome, Firefox, Edge on iOS and the
    // in-app browsers (which cannot add to the home screen) do not match
    var isIOSSafari = isIOS && /Safari\//.test(ua) && /Version\//.test(ua) && !/CriOS|FxiOS|EdgiOS|OPiOS|Instagram|FBAN|FBAV|Line\//.test(ua);

    function standalone() {
      return (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
    }
    function eligible() {
      if (!selfTag || !text('title')) return false;
      if (standalone() || !(isAndroid || isIOS)) return false;
      if (/\/auth\/|\/checkout(\/|$)|\/host\/start|\/host\/events\/new|\/edit(\/|$)/.test(location.pathname)) return false;
      if (read(sessionStorage, SESSION_KEY)) return false;
      var at = parseInt(read(localStorage, DISMISS_KEY) || '0', 10) || 0;
      return Date.now() - at > DISMISS_DAYS * 86400000;
    }

    // Tallest bar pinned to the bottom of the screen (the organizer tab bar, the ticket
    // bar on an event page): the card floats above it instead of covering it.
    function bottomBarHeight() {
      var tallest = 0;
      document.querySelectorAll('.fixed.bottom-0').forEach(function (el) {
        var cs = window.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden' || cs.position !== 'fixed') return;
        var r = el.getBoundingClientRect();
        if (r.height > 0 && r.height < window.innerHeight / 2 && r.bottom >= window.innerHeight - 1) tallest = Math.max(tallest, r.height);
      });
      return tallest;
    }

    function el(tag, cls, content) {
      var node = document.createElement(tag);
      if (cls) node.className = cls;
      if (content) node.textContent = content;
      return node;
    }

    function show(kind) {
      if (card || !document.body || !eligible()) return;
      if (kind === 'android' && !deferred) return;
      card = el('div', 'fixed inset-x-3 z-[55] mx-auto max-w-md rounded-2xl border border-ink-100 bg-white p-4 shadow-cardHover');
      card.id = 'installPrompt';
      card.setAttribute('role', 'dialog');
      card.setAttribute('aria-labelledby', 'installPromptTitle');
      // a pinned bar already pads itself for the phone's home indicator
      var bar = bottomBarHeight();
      card.style.bottom = bar > 0 ? (bar + 12) + 'px' : 'calc(12px + env(safe-area-inset-bottom, 0px))';

      var row = el('div', 'flex items-start gap-3');
      var icon = document.createElement('img');
      icon.src = text('icon');
      icon.alt = '';
      icon.width = 48;
      icon.height = 48;
      icon.className = 'h-12 w-12 shrink-0 rounded-xl border border-ink-100 bg-white';
      var copy = el('div', 'min-w-0 flex-1');
      var title = el('p', 'font-display text-sm font-bold text-ink-900', text('title'));
      title.id = 'installPromptTitle';
      copy.appendChild(title);
      copy.appendChild(el('p', 'mt-0.5 text-xs leading-relaxed text-ink-500', kind === 'ios' ? text('ios') : text('body')));
      row.appendChild(icon);
      row.appendChild(copy);
      card.appendChild(row);

      var actions = el('div', 'mt-3 flex items-center justify-end gap-2');
      var later = el('button', 'rounded-xl border border-ink-200 bg-white px-4 py-2 text-sm font-semibold text-ink-700 transition hover:border-brand-400 hover:text-brand-600', kind === 'ios' ? text('close') : text('later'));
      later.type = 'button';
      later.setAttribute('data-install-dismiss', '');
      later.addEventListener('click', function () {
        store(localStorage, DISMISS_KEY, String(Date.now()));
        hide();
      });
      actions.appendChild(later);
      if (kind === 'android') {
        var go = el('button', 'rounded-xl bg-brand-500 px-4 py-2 text-sm font-semibold text-white shadow-pop transition hover:bg-brand-600 active:scale-[.98]', text('cta'));
        go.type = 'button';
        go.setAttribute('data-install-accept', '');
        go.addEventListener('click', function () {
          var prompt = deferred;
          deferred = null;
          hide();
          if (!prompt) return;
          prompt.prompt();
          if (prompt.userChoice && prompt.userChoice.then) {
            prompt.userChoice.then(function (choice) {
              // declined in the system dialog: same as "Not now"
              if (choice && choice.outcome === 'dismissed') store(localStorage, DISMISS_KEY, String(Date.now()));
            }).catch(function () {});
          }
        });
        actions.appendChild(go);
      }
      card.appendChild(actions);
      document.body.appendChild(card);
      store(sessionStorage, SESSION_KEY, '1');
    }

    function hide() {
      if (card && card.parentNode) card.parentNode.removeChild(card);
      card = null;
    }

    if (isIOSSafari) {
      var start = function () { setTimeout(function () { show('ios'); }, 2500); };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
      else start();
    }
  })();

  // Double-submit guard for every form on the site (checkout, wizard publish,
  // onboarding, payment approvals...). Runs after the form's own handlers, so
  // a submit they cancelled (validation error) is not counted; a second
  // submit of the same form within 10 s is swallowed and the submit buttons
  // are disabled meanwhile. Opt out per form with data-allow-resubmit.
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (!f || f.tagName !== 'FORM' || e.defaultPrevented || f.hasAttribute('data-allow-resubmit')) return;
    if (f.getAttribute('data-submitted') === '1') { e.preventDefault(); return; }
    f.setAttribute('data-submitted', '1');
    var buttons = f.querySelectorAll('button[type=submit], input[type=submit]');
    setTimeout(function () {
      buttons.forEach(function (b) { b.setAttribute('aria-disabled', 'true'); b.classList.add('pointer-events-none', 'opacity-70'); });
    }, 0);
    setTimeout(function () {
      f.removeAttribute('data-submitted');
      buttons.forEach(function (b) { b.removeAttribute('aria-disabled'); b.classList.remove('pointer-events-none', 'opacity-70'); });
    }, 10000);
  });
  // bfcache restore (back button): clear any stale guard
  window.addEventListener('pageshow', function () {
    document.querySelectorAll('form[data-submitted]').forEach(function (f) {
      f.removeAttribute('data-submitted');
      f.querySelectorAll('[aria-disabled]').forEach(function (b) { b.removeAttribute('aria-disabled'); b.classList.remove('pointer-events-none', 'opacity-70'); });
    });
  });

  // R37 #1: cover-image shimmer. A .img-shimmer box sweeps a highlight until its
  // <img> has loaded (or failed), then the image fades in (see layout.html CSS).
  // Load/error events do not bubble, so they are caught in the capture phase, which
  // also covers cards appended later (browse infinite scroll); images already
  // complete by the time this runs (cache) are marked by the scan.
  function markLoaded(img) {
    var box = img.closest ? img.closest('.img-shimmer') : null;
    if (box) box.classList.add('is-loaded');
  }
  function scanShimmer(root) {
    (root || document).querySelectorAll('.img-shimmer img').forEach(function (img) {
      if (img.complete) markLoaded(img);
    });
  }
  document.addEventListener('load', function (e) { if (e.target && e.target.tagName === 'IMG') markLoaded(e.target); }, true);
  document.addEventListener('error', function (e) { if (e.target && e.target.tagName === 'IMG') markLoaded(e.target); }, true);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { scanShimmer(); });
  else scanShimmer();
  window.ieventScanShimmer = scanShimmer;
})();
