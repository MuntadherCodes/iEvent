/* iEvent embeddable sales widget — https://ievent.iq
 * Usage:
 *   <script src="https://ievent.iq/js/widget.js"
 *           data-event="baghdad-nights-music-festival"
 *           data-type="button|card|checkout"      (default: button)
 *           data-label="Get tickets"              (optional, overrides the language default)
 *           data-color="#8f7ac9"                  (accent color)
 *           data-lang="en|ar|ku"                  (default: en; ar/ku render RTL)
 *           data-price="true|false"               (default: false; shows "· from X" when
 *                                                  data-price-from="25,000 IQD" is present)
 *           data-price-from="25,000 IQD"          (optional price label used by data-price)
 *           data-rounded="true|false"></script>   (default: true)
 */
(function () {
  var script = document.currentScript;
  if (!script) return;
  var slug = script.getAttribute('data-event');
  if (!slug) return;

  var type = script.getAttribute('data-type') || 'button';
  var color = script.getAttribute('data-color') || '#8f7ac9';
  var lang = script.getAttribute('data-lang') || 'en';
  if (lang !== 'ar' && lang !== 'ku') lang = 'en';
  var rounded = script.getAttribute('data-rounded') !== 'false';
  var showPrice = script.getAttribute('data-price') === 'true';
  var priceFrom = script.getAttribute('data-price-from') || '';

  // Language strings. Arabic and Kurdish (Sorani) both use Arabic script → RTL.
  var STR = {
    en: { label: 'Get tickets', from: 'from', dir: 'ltr' },
    ar: { label: 'احجز الآن', from: 'من', dir: 'rtl' },
    ku: { label: 'بلیت بکڕە', from: 'لە', dir: 'rtl' }
  };
  var t = STR[lang];
  var label = script.getAttribute('data-label') || t.label; // backward compat: data-label wins
  var priceSuffix = showPrice && priceFrom ? ' · ' + t.from + ' ' + priceFrom : '';

  var origin = (function () {
    try { return new URL(script.src).origin; } catch (e) { return 'https://ievent.iq'; }
  })();
  var url = origin + '/e/' + encodeURIComponent(slug);
  var title = slug.replace(/-/g, ' ');
  var r = function (px) { return rounded ? px + 'px' : '3px'; };

  var container = document.createElement('div');
  container.style.cssText = 'display:inline-block;font-family:Inter,Arial,sans-serif;';
  container.dir = t.dir;

  function poweredBy(node) {
    var p = document.createElement('p');
    p.style.cssText = 'margin:10px 0 0;font-size:10px;color:#908fa3;letter-spacing:.04em;';
    p.textContent = 'POWERED BY IEVENT';
    node.appendChild(p);
  }

  if (type === 'checkout') {
    // Full checkout: the event page embedded in an iframe.
    var frame = document.createElement('iframe');
    frame.src = url;
    frame.title = 'iEvent checkout · ' + title;
    frame.style.cssText =
      'width:420px;max-width:100%;height:640px;border:1px solid #ececf1;' +
      'border-radius:' + r(14) + ';background:#fff;box-shadow:0 6px 20px -8px rgba(35,34,47,.12);';
    frame.setAttribute('loading', 'lazy');
    container.appendChild(frame);
    poweredBy(container);
  } else if (type === 'card') {
    var card = document.createElement('a');
    card.href = url;
    card.target = '_blank';
    card.rel = 'noopener';
    card.style.cssText =
      'display:block;width:280px;text-decoration:none;border:1px solid #ececf1;' +
      'border-radius:' + r(14) + ';overflow:hidden;background:#fff;' +
      'box-shadow:0 6px 20px -8px rgba(35,34,47,.12);';
    var head = document.createElement('div');
    head.style.cssText = 'height:96px;background:linear-gradient(135deg,' + color + 'cc,' + color + ');';
    card.appendChild(head);
    var body = document.createElement('div');
    body.style.cssText = 'padding:14px 16px;';
    var titleEl = document.createElement('p');
    titleEl.style.cssText = 'margin:0;font-weight:700;color:#23222f;font-size:15px;';
    titleEl.textContent = title;
    body.appendChild(titleEl);
    if (showPrice && priceFrom) {
      var priceEl = document.createElement('p');
      priceEl.style.cssText = 'margin:8px 0 0;font-size:13px;font-weight:600;color:#57566b;';
      priceEl.textContent = t.from + ' ' + priceFrom;
      body.appendChild(priceEl);
    }
    var btnEl = document.createElement('p');
    btnEl.style.cssText =
      'margin:10px 0 0;display:inline-block;background:' + color + ';color:#fff;' +
      'font-weight:700;font-size:13px;padding:8px 14px;border-radius:' + r(9) + ';';
    btnEl.textContent = label;
    body.appendChild(btnEl);
    poweredBy(body);
    card.appendChild(body);
    container.appendChild(card);
  } else {
    var btn = document.createElement('a');
    btn.href = url;
    btn.target = '_blank';
    btn.rel = 'noopener';
    btn.textContent = label + priceSuffix;
    btn.style.cssText =
      'display:inline-block;background:' + color + ';color:#ffffff;font-weight:700;' +
      'font-size:14px;padding:12px 22px;border-radius:' + r(11) + ';text-decoration:none;' +
      'box-shadow:0 8px 30px -6px rgba(72,55,105,.28);';
    container.appendChild(btn);
    poweredBy(container);
  }
  script.parentNode.insertBefore(container, script);
})();
