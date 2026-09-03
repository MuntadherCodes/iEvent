// Service-worker registration only.
// R26 retired the "install the app" banner; R27 retired the "enable
// notifications" banner as well (product decision: no prompts). The
// service worker still registers so the site stays installable from the
// browser menu, and beforeinstallprompt is swallowed so Chrome's own
// mini-infobar stays quiet too.
(function () {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(function () {});
  }
  window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); });
})();
