/* iEvent embeddable sales widget — https://ievent.iq
 * Usage:
 *   <script src="https://ievent.iq/js/widget.js"
 *           data-event="baghdad-nights-music-festival"
 *           data-type="button|card"
 *           data-label="Get tickets"
 *           data-color="#8f7ac9"></script>
 */
(function () {
  var script = document.currentScript;
  if (!script) return;
  var slug = script.getAttribute('data-event');
  if (!slug) return;
  var type = script.getAttribute('data-type') || 'button';
  var label = script.getAttribute('data-label') || 'Get tickets';
  var color = script.getAttribute('data-color') || '#8f7ac9';
  var origin = (function () {
    try { return new URL(script.src).origin; } catch (e) { return 'https://ievent.iq'; }
  })();
  var url = origin + '/e/' + encodeURIComponent(slug);

  var container = document.createElement('div');
  container.style.cssText = 'display:inline-block;font-family:Inter,Arial,sans-serif;';

  if (type === 'card') {
    var card = document.createElement('a');
    card.href = url;
    card.target = '_blank';
    card.rel = 'noopener';
    card.style.cssText =
      'display:block;width:280px;text-decoration:none;border:1px solid #ececf1;' +
      'border-radius:14px;overflow:hidden;background:#fff;box-shadow:0 6px 20px -8px rgba(35,34,47,.12);';
    card.innerHTML =
      '<div style="height:96px;background:linear-gradient(135deg,' + color + ',#574382);"></div>' +
      '<div style="padding:14px 16px;">' +
      '<p style="margin:0;font-weight:700;color:#23222f;font-size:15px;">' + slug.replace(/-/g, ' ') + '</p>' +
      '<p style="margin:10px 0 0;display:inline-block;background:' + color + ';color:#fff;' +
      'font-weight:700;font-size:13px;padding:8px 14px;border-radius:9px;">' + label + '</p>' +
      '<p style="margin:10px 0 0;font-size:10px;color:#908fa3;letter-spacing:.04em;">POWERED BY IEVENT</p>' +
      '</div>';
    container.appendChild(card);
  } else {
    var btn = document.createElement('a');
    btn.href = url;
    btn.target = '_blank';
    btn.rel = 'noopener';
    btn.textContent = label;
    btn.style.cssText =
      'display:inline-block;background:' + color + ';color:#ffffff;font-weight:700;' +
      'font-size:14px;padding:12px 22px;border-radius:11px;text-decoration:none;' +
      'box-shadow:0 8px 30px -6px rgba(72,55,105,.28);';
    container.appendChild(btn);
  }
  script.parentNode.insertBefore(container, script);
})();
