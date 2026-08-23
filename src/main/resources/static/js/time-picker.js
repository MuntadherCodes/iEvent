// Replaces a native <input type="time"> with a friendlier 12h text field
// (accepts free typing in 12h "6:15 PM" or 24h "18:15") plus a dropdown
// limited to half-hour picks. Chromium's native time picker has no way to
// restrict its own dropdown list to half-hour steps (the "step" attribute
// only affects spinner increments/validity, never what it renders), so this
// replaces the control entirely rather than fighting the browser.
//
// The original <input name="..."> becomes a hidden field holding the
// canonical 24h "HH:mm" value the server expects (LocalTime.parse) — that
// keeps it correct at all times for both real form submits AND any
// FormData(form) snapshot taken mid-session (e.g. this app's autosave),
// not just at the moment of clicking submit. A separate, unnamed visible
// text field is what the user actually reads/types/picks from.
(function () {
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function to24(h, m) { return pad(h) + ':' + pad(m); }
  function to12Display(h, m) {
    var h12 = h % 12;
    if (h12 === 0) h12 = 12;
    return h12 + ':' + pad(m) + ' ' + (h < 12 ? 'AM' : 'PM');
  }

  // Accepts "18:15", "1815", "615", "6:15pm", "6:15 PM", "6pm", etc.
  function parse(raw) {
    if (!raw) return null;
    var s = raw.trim().toUpperCase();
    var ampm = null;
    var am = s.match(/(AM|PM)\s*$/);
    if (am) { ampm = am[1]; s = s.slice(0, am.index); }
    s = s.replace(/\s+/g, '');
    if (!s) return null;
    var hh, mm;
    if (s.indexOf(':') >= 0) {
      var parts = s.split(':');
      if (parts.length !== 2 || !/^\d{1,2}$/.test(parts[0]) || !/^\d{1,2}$/.test(parts[1])) return null;
      hh = parseInt(parts[0], 10);
      mm = parseInt(parts[1], 10);
    } else if (/^\d{3,4}$/.test(s)) {
      hh = parseInt(s.slice(0, s.length - 2), 10);
      mm = parseInt(s.slice(-2), 10);
    } else if (/^\d{1,2}$/.test(s)) {
      hh = parseInt(s, 10);
      mm = 0;
    } else {
      return null;
    }
    if (isNaN(hh) || isNaN(mm) || mm < 0 || mm > 59) return null;
    if (ampm) {
      if (hh < 1 || hh > 12) return null;
      if (ampm === 'AM') { if (hh === 12) hh = 0; }
      else if (hh !== 12) hh += 12;
    } else if (hh < 0 || hh > 23) {
      return null;
    }
    return { h: hh, m: mm };
  }

  var OPTIONS = (function () {
    var out = [];
    for (var h = 0; h < 24; h++) {
      for (var m = 0; m < 60; m += 30) out.push({ h: h, m: m, label: to12Display(h, m) });
    }
    return out;
  })();

  function markInvalid(input, on) {
    input.classList.toggle('border-rose-400', on);
    input.classList.toggle('ring-4', on);
    input.classList.toggle('ring-rose-100', on);
  }

  function init(id) {
    var original = document.getElementById(id);
    if (!original || original.dataset.timePickerReady) return;
    original.dataset.timePickerReady = '1';

    var initialRaw = original.value;
    var wasRequired = original.hasAttribute('required');
    original.removeAttribute('required'); // a hidden input can't natively block submission
    original.type = 'hidden';

    var display = document.createElement('input');
    display.type = 'text';
    display.id = id + '-display';
    display.autocomplete = 'off';
    display.setAttribute('inputmode', 'text');
    display.placeholder = '--:-- --';
    display.className = original.className.replace('appearance-none', '').trim() + ' pe-10';
    var ariaLabel = original.getAttribute('aria-label');
    if (ariaLabel) display.setAttribute('aria-label', ariaLabel);
    original.dataset.displayId = display.id;

    var wrapper = document.createElement('div');
    wrapper.className = 'relative';
    original.parentNode.insertBefore(wrapper, original);
    wrapper.appendChild(display);
    wrapper.appendChild(original);

    var toggleBtn = document.createElement('button');
    toggleBtn.type = 'button';
    toggleBtn.className = 'absolute end-3 top-1/2 -translate-y-1/2 text-ink-400 transition hover:text-brand-500';
    toggleBtn.setAttribute('aria-label', ariaLabel || 'Open time picker');
    toggleBtn.innerHTML = '<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>';
    wrapper.appendChild(toggleBtn);

    var panel = document.createElement('div');
    panel.className = 'absolute z-30 mt-1 hidden max-h-56 w-full overflow-y-auto rounded-xl border border-ink-100 bg-white p-1.5 shadow-cardHover';
    panel.setAttribute('role', 'listbox');
    OPTIONS.forEach(function (opt) {
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'time-picker-option block w-full rounded-lg px-3 py-1.5 text-start text-sm text-ink-700 transition hover:bg-brand-50';
      btn.textContent = opt.label;
      btn.addEventListener('click', function () {
        display.value = opt.label;
        setCanonical(opt.h, opt.m);
        markInvalid(display, false);
        closePanel();
      });
      panel.appendChild(btn);
    });
    wrapper.appendChild(panel);

    function setCanonical(h, m) {
      original.value = to24(h, m);
      original.dispatchEvent(new Event('input', { bubbles: true }));
      original.dispatchEvent(new Event('change', { bubbles: true }));
    }
    function clearCanonical() {
      if (!original.value) return;
      original.value = '';
      original.dispatchEvent(new Event('input', { bubbles: true }));
      original.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function openPanel() {
      panel.classList.remove('hidden');
      var cur = parse(original.value);
      if (cur) {
        var idx = OPTIONS.findIndex(function (o) { return o.h === cur.h && o.m === cur.m; });
        if (idx >= 0 && panel.children[idx]) panel.scrollTop = panel.children[idx].offsetTop - panel.clientHeight / 2;
      }
    }
    function closePanel() { panel.classList.add('hidden'); }

    // Best-effort parse on every keystroke so the hidden/canonical field is
    // never more than one valid keystroke stale — this is what any
    // mid-session FormData snapshot (e.g. autosave) actually reads, not just
    // whatever's true at the moment of a real form submit.
    function tryLiveParse() {
      var parsed = parse(display.value);
      if (parsed) setCanonical(parsed.h, parsed.m);
    }

    function commit() {
      if (!display.value.trim()) { markInvalid(display, false); clearCanonical(); return; }
      var parsed = parse(display.value);
      if (!parsed) { markInvalid(display, true); clearCanonical(); return; }
      display.value = to12Display(parsed.h, parsed.m);
      setCanonical(parsed.h, parsed.m);
      markInvalid(display, false);
    }

    toggleBtn.addEventListener('click', function () {
      if (panel.classList.contains('hidden')) { openPanel(); display.focus(); } else closePanel();
    });
    display.addEventListener('focus', openPanel);
    display.addEventListener('input', function () { markInvalid(display, false); tryLiveParse(); });
    display.addEventListener('blur', function () {
      // Deferred so a click on a dropdown option (which also blurs the
      // display field) has a chance to register before we validate/close.
      setTimeout(function () {
        if (!wrapper.contains(document.activeElement)) { commit(); closePanel(); }
      }, 120);
    });
    document.addEventListener('click', function (e) {
      if (!wrapper.contains(e.target)) closePanel();
    });

    if (initialRaw) {
      var initial = parse(initialRaw);
      if (initial) display.value = to12Display(initial.h, initial.m);
    }

    // Making the real field hidden lifts the browser's own "required"
    // enforcement (hidden inputs are exempt from constraint validation) —
    // restore an equivalent guard at the enclosing form's submit, since nothing
    // else in event-edit.html / event-console.html's postpone form re-checks
    // this the way the create wizard's own step validation already does.
    if (wasRequired) {
      var form = original.closest('form');
      if (form) {
        if (!form.dataset.timePickerRequired) form.dataset.timePickerRequired = '';
        form.dataset.timePickerRequired += ' ' + id;
        if (!form.dataset.timePickerHooked) {
          form.dataset.timePickerHooked = '1';
          form.addEventListener('submit', function (e) {
            var ids = form.dataset.timePickerRequired.trim().split(/\s+/);
            for (var i = 0; i < ids.length; i++) {
              var hidden = document.getElementById(ids[i]);
              if (hidden && !hidden.value.trim()) {
                e.preventDefault();
                var proxy = document.getElementById(hidden.dataset.displayId);
                markInvalid(proxy, true);
                proxy.focus();
                return;
              }
            }
          });
        }
      }
    }
  }

  window.iEventTimePicker = { init: init, parse: parse, to24: to24, to12Display: to12Display };
})();
