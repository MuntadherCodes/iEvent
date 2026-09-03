// Mobile "install the app" + "enable notifications" prompts.
// - Android/Chrome: captures beforeinstallprompt, shows a custom banner that
//   triggers the real native install prompt on tap.
// - iOS Safari: beforeinstallprompt doesn't exist there, so it gets a
//   manual-steps banner instead (Share -> Add to Home Screen) — there is no
//   API to trigger that programmatically.
// - Notifications: Notification.requestPermission() must run from a user
//   gesture, so this only ever offers a button — it never auto-prompts.
//   This wires up the browser permission grant; it does not implement Web
//   Push delivery (VAPID keys, subscription storage, server-triggered
//   pushes) — that's a separate backend feature.
(function () {
  'use strict';

  if ('serviceWorker' in navigator && window.isSecureContext) {
    navigator.serviceWorker.register('/sw.js').catch(function () {});
  }

  function isStandalone() {
    return window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
  }
  if (isStandalone()) return;

  var INSTALL_DISMISS_KEY = 'ievent-install-dismissed';
  var NOTIF_DISMISS_KEY = 'ievent-notif-dismissed';
  var DISMISS_DAYS = 14;

  function dismissedRecently(key) {
    try {
      var ts = localStorage.getItem(key);
      return !!ts && (Date.now() - parseInt(ts, 10)) < DISMISS_DAYS * 86400000;
    } catch (e) { return false; }
  }
  function markDismissed(key) {
    try { localStorage.setItem(key, String(Date.now())); } catch (e) {}
  }

  var isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
  var isMobile = isIOS || /Mobi|Android/i.test(navigator.userAgent);
  if (!isMobile) return;

  var rtl = document.documentElement.getAttribute('dir') === 'rtl';
  var STR = rtl ? {
    installTitle: 'ثبّت تطبيق iEvent',
    installBodyAndroid: 'أضِفه إلى شاشتك الرئيسية لفتح أسرع وتجربة أشبه بالتطبيق.',
    installBodyIOS: 'اضغط على زر المشاركة، ثم اختر "إضافة إلى الشاشة الرئيسية".',
    installBtn: 'تثبيت', later: 'لاحقًا',
    notifTitle: 'فعّل الإشعارات',
    notifBody: 'اعرف فور تأكيد طلبك أو صدور تذاكر جديدة.',
    enable: 'تفعيل', notNow: 'ليس الآن'
  } : {
    installTitle: 'Install the iEvent app',
    installBodyAndroid: 'Add it to your home screen for faster, app-like access.',
    installBodyIOS: 'Tap the Share button, then "Add to Home Screen".',
    installBtn: 'Install', later: 'Later',
    notifTitle: 'Turn on notifications',
    notifBody: 'Know the moment your order is confirmed or new tickets go live.',
    enable: 'Enable', notNow: 'Not now'
  };

  // R26: the "install the app" banner is retired for now (product decision).
  // We still swallow beforeinstallprompt so Chrome's own mini-infobar doesn't
  // pop up either; the site stays installable from the browser menu.
  window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); });

  function baseCard(iconSvg, title, body, buttonsHtml) {
    var div = document.createElement('div');
    div.id = 'pwaBanner';
    div.setAttribute('role', 'dialog');
    div.style.cssText = 'position:fixed;inset-inline:1rem;bottom:calc(1rem + env(safe-area-inset-bottom));z-index:70;max-width:26rem;margin-inline:auto';
    div.innerHTML =
      '<div style="background:#fff;border:1px solid #ececf1;border-radius:1rem;box-shadow:0 8px 30px -6px rgba(72,55,105,.28);padding:1rem;display:flex;gap:.75rem;align-items:flex-start;font-family:Inter,sans-serif" dir="' + (rtl ? 'rtl' : 'ltr') + '">' +
        '<span style="flex:none;display:grid;place-items:center;width:2.5rem;height:2.5rem;border-radius:.75rem;background:#f6f4fb;color:#7b64b6">' + iconSvg + '</span>' +
        '<div style="flex:1;min-width:0">' +
          '<p style="margin:0;font-weight:700;font-size:.875rem;color:#23222f">' + title + '</p>' +
          '<p style="margin:.25rem 0 0;font-size:.8rem;color:#6b6a80;line-height:1.4">' + body + '</p>' +
          '<div style="margin-top:.6rem;display:flex;gap:.5rem">' + buttonsHtml + '</div>' +
        '</div>' +
        '<button id="pwaCloseBtn" aria-label="Close" style="flex:none;background:none;border:0;color:#908fa3;cursor:pointer;font-size:1.1rem;line-height:1;padding:.25rem">&times;</button>' +
      '</div>';
    return div;
  }

  // Offered only for signed-in users — #nbBtn (the
  // notification bell) only renders when a session is authenticated.
  function maybeOfferNotifications() {
    if (!('Notification' in window) || !window.isSecureContext) return;
    if (Notification.permission !== 'default') return;
    if (!document.getElementById('nbBtn')) return;
    if (dismissedRecently(NOTIF_DISMISS_KEY)) return;
    setTimeout(showNotifBanner, 600);
  }

  function showNotifBanner() {
    if (document.getElementById('pwaBanner')) return;
    var bellIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/></svg>';
    var buttons = '<button id="pwaNotifEnableBtn" style="background:#8f7ac9;color:#fff;border:0;border-radius:.6rem;padding:.45rem .9rem;font-size:.8rem;font-weight:600;cursor:pointer">' + STR.enable + '</button>' +
      '<button id="pwaNotifLaterBtn" style="background:#fff;color:#6b6a80;border:1px solid #ececf1;border-radius:.6rem;padding:.45rem .9rem;font-size:.8rem;font-weight:600;cursor:pointer">' + STR.notNow + '</button>';
    var div = baseCard(bellIcon, STR.notifTitle, STR.notifBody, buttons);
    document.body.appendChild(div);
    function close() { div.remove(); markDismissed(NOTIF_DISMISS_KEY); }
    document.getElementById('pwaCloseBtn').addEventListener('click', close);
    document.getElementById('pwaNotifLaterBtn').addEventListener('click', close);
    document.getElementById('pwaNotifEnableBtn').addEventListener('click', function () {
      Notification.requestPermission().finally(function () { div.remove(); });
    });
  }

  // With the install banner gone, the notifications offer stands on its own.
  setTimeout(maybeOfferNotifications, 1500);
})();
