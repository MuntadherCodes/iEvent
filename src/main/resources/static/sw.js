// Minimal service worker: exists only to satisfy PWA installability criteria
// (Chrome/Android won't fire beforeinstallprompt without a registered SW that
// has a fetch handler). Deliberately does no caching — a stale cache would be
// a worse experience than the ticket/checkout flows staying always-network,
// and offline support isn't what was asked for here.
self.addEventListener('install', function () {
  self.skipWaiting();
});

self.addEventListener('activate', function (event) {
  event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', function () {
  // Intentionally not calling event.respondWith — every request passes
  // straight through to the network unmodified.
});
