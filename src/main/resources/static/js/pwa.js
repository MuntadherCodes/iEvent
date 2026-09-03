(function () {
  if ('serviceWorker' in navigator) { navigator.serviceWorker.register('/sw.js').catch(function () {}); }
  window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); });

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
})();
