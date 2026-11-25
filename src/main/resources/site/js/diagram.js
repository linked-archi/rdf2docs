/* rdf2docs — a view's diagram, interactive: zoom with the wheel or a pinch, at the pointer; pan by dragging anywhere,
 * over the shapes too, a drag never taken for a click; the keys + − 0 1 and the arrows; Fit, 1:1, full screen. The
 * zoom and the pan change the drawing's viewBox, so the drawing stays sharp at any size. Also: the presentation
 * contexts, the names on lines, the legend, the SVG to download. */
(function () {
  'use strict';
  var MIN = 0.05, MAX = 8;

  function init(wrap) {
    var box = wrap.querySelector('.diagram');
    var variants = Array.prototype.slice.call(box.querySelectorAll('svg.la-diagram'));
    if (!variants.length) return;
    var zoomLabel = wrap.querySelector('.zoom');
    function parse(svg) { var v = svg.getAttribute('viewBox').split(/[\s,]+/).map(Number); return { x: v[0], y: v[1], w: v[2], h: v[3] }; }
    // Each drawing's whole extent, kept: Fit and the download go back to it.
    variants.forEach(function (svg) { svg.setAttribute('data-full', svg.getAttribute('viewBox')); });
    function current() { return variants.filter(function (v) { return !v.classList.contains('hidden'); })[0] || variants[0]; }
    function full() { var v = current().getAttribute('data-full').split(/[\s,]+/).map(Number); return { x: v[0], y: v[1], w: v[2], h: v[3] }; }
    var vb = parse(variants[0]);

    // The viewport: as tall as the drawing at the page's width, within the window.
    function size() {
      var f = full(), w = box.clientWidth || 800;
      var natural = f.h * Math.min(1, w / f.w);
      box.style.height = Math.round(Math.max(260, Math.min(window.innerHeight * 0.78, natural + 4))) + 'px';
    }
    function rect() { return box.getBoundingClientRect(); }
    function scale() { var r = rect(); return r.width / vb.w; }
    function apply() {
      variants.forEach(function (svg) { svg.setAttribute('viewBox', vb.x + ' ' + vb.y + ' ' + vb.w + ' ' + vb.h); });
      if (zoomLabel) zoomLabel.textContent = Math.round(scale() * 100) + '%';
    }
    /** The box's aspect kept: the drawing is laid out in the viewBox, the viewBox in the box. */
    function aspectFit(target) {
      var r = rect(), aspect = r.height / Math.max(1, r.width);
      var w = target.w, h = target.h;
      if (h / w < aspect) h = w * aspect; else w = h / aspect;
      return { x: target.x - (w - target.w) / 2, y: target.y - (h - target.h) / 2, w: w, h: h };
    }
    function fit() {
      var f = full(), r = rect();
      // Not past life size for a small drawing.
      var target = aspectFit(f);
      if (r.width / target.w > 1) { var cx = f.x + f.w / 2, cy = f.y + f.h / 2; target = { x: cx - r.width / 2, y: cy - r.height / 2, w: r.width, h: r.height }; }
      vb = target; apply();
    }
    function zoomAt(factor, clientX, clientY) {
      var r = rect();
      var s = scale() * factor;
      if (s < MIN) factor = MIN / scale(); else if (s > MAX) factor = MAX / scale();
      var fx = (clientX - r.left) / r.width, fy = (clientY - r.top) / r.height;
      var px = vb.x + fx * vb.w, py = vb.y + fy * vb.h;
      var w = vb.w / factor, h = vb.h / factor;
      vb = { x: px - fx * w, y: py - fy * h, w: w, h: h };
      apply();
    }
    function zoomCentre(factor) { var r = rect(); zoomAt(factor, r.left + r.width / 2, r.top + r.height / 2); }
    function pan(dx, dy) { var r = rect(); vb.x -= dx * vb.w / r.width; vb.y -= dy * vb.h / r.height; apply(); }
    function actual() { var r = rect(), cx = vb.x + vb.w / 2, cy = vb.y + vb.h / 2; vb = { x: cx - r.width / 2, y: cy - r.height / 2, w: r.width, h: r.height }; apply(); }

    // ── Wheel and trackpad ──
    box.addEventListener('wheel', function (e) {
      e.preventDefault();
      if (!e.ctrlKey && Math.abs(e.deltaX) > Math.abs(e.deltaY) * 1.5) { pan(-e.deltaX, -e.deltaY); return; }
      var unit = e.deltaMode === 1 ? 0.05 : e.deltaMode === 2 ? 0.5 : (e.ctrlKey ? 0.01 : 0.0015);
      zoomAt(Math.exp(-e.deltaY * unit), e.clientX, e.clientY);
    }, { passive: false });

    // ── Drag to pan, pinch to zoom ──
    var pointers = new Map(), dragged = false, start = null, pinch = null;
    box.addEventListener('pointerdown', function (e) {
      if (e.button !== 0 && e.pointerType === 'mouse') return;
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (pointers.size === 1) { start = { x: e.clientX, y: e.clientY }; dragged = false; }
      if (pointers.size === 2) {
        var p = Array.from(pointers.values());
        pinch = { d: Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y) };
      }
    });
    box.addEventListener('pointermove', function (e) {
      var last = pointers.get(e.pointerId);
      if (!last) return;
      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (pointers.size === 2 && pinch) {
        var p = Array.from(pointers.values());
        var d = Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y);
        if (pinch.d > 0) zoomAt(d / pinch.d, (p[0].x + p[1].x) / 2, (p[0].y + p[1].y) / 2);
        pinch.d = d; dragged = true;
        return;
      }
      if (!dragged && start && Math.hypot(e.clientX - start.x, e.clientY - start.y) < 4) return;
      if (!dragged) { dragged = true; box.classList.add('dragging'); try { box.setPointerCapture(e.pointerId); } catch (x) { /* nothing */ } }
      pan(e.clientX - last.x, e.clientY - last.y);
    });
    function up(e) {
      pointers.delete(e.pointerId);
      if (pointers.size < 2) pinch = null;
      if (pointers.size === 0) { box.classList.remove('dragging'); start = null; }
    }
    box.addEventListener('pointerup', up);
    box.addEventListener('pointercancel', up);
    // A drag is not a click: the shape it started on is not opened.
    box.addEventListener('click', function (e) { if (dragged) { e.preventDefault(); e.stopPropagation(); dragged = false; } }, true);
    box.addEventListener('dblclick', function (e) { if (!e.target.closest('a')) { e.preventDefault(); zoomAt(1.6, e.clientX, e.clientY); } });

    // ── Keys ──
    box.setAttribute('tabindex', '0');
    box.addEventListener('keydown', function (e) {
      var k = e.key, step = 60;
      if (k === '+' || k === '=') zoomCentre(1.25);
      else if (k === '-' || k === '_') zoomCentre(1 / 1.25);
      else if (k === '0') fit();
      else if (k === '1') actual();
      else if (k === 'ArrowLeft') pan(step, 0);
      else if (k === 'ArrowRight') pan(-step, 0);
      else if (k === 'ArrowUp') pan(0, step);
      else if (k === 'ArrowDown') pan(0, -step);
      else return;
      e.preventDefault();
    });

    // ── Buttons ──
    wrap.addEventListener('click', function (e) {
      var b = e.target.closest('[data-zoom]');
      if (!b) return;
      var z = b.getAttribute('data-zoom');
      if (z === 'in') zoomCentre(1.25);
      else if (z === 'out') zoomCentre(1 / 1.25);
      else if (z === '1') actual();
      else if (z === 'full') {
        if (document.fullscreenElement) document.exitFullscreen(); else if (wrap.requestFullscreen) wrap.requestFullscreen();
      } else fit();
    });
    document.addEventListener('fullscreenchange', function () {
      box.style.height = document.fullscreenElement === wrap ? '' : box.style.height;
      if (document.fullscreenElement !== wrap) size();
      setTimeout(fit, 50);
    });
    if (window.ResizeObserver) {
      var last = rect().width;
      new ResizeObserver(function () {
        var r = rect();
        if (!r.width || Math.abs(r.width - last) < 1) return;
        // Keep the middle and the zoom.
        var s = last / vb.w, cx = vb.x + vb.w / 2, cy = vb.y + vb.h / 2;
        last = r.width;
        vb = { x: cx - r.width / s / 2, y: cy - r.height / s / 2, w: r.width / s, h: r.height / s };
        apply();
      }).observe(box);
    }

    // ── Presentation contexts: each drawing is the view for one; the shown one carries the fragments as ids ──
    var select = wrap.querySelector('select.context');
    function show(index) {
      variants.forEach(function (svg, i) {
        var on = i === index;
        svg.classList.toggle('hidden', !on);
        svg.querySelectorAll('[data-frag]').forEach(function (el) { if (on) el.id = el.getAttribute('data-frag'); else el.removeAttribute('id'); });
      });
      apply();
      if (window.rdf2docsTree) window.rdf2docsTree.refreshPlaced();
    }
    if (select) {
      select.addEventListener('change', function () {
        show(parseInt(select.options[select.selectedIndex].getAttribute('data-variant'), 10) || 0);
        try { localStorage.setItem('rdf2docs-context', select.value); } catch (e) { /* not remembered */ }
      });
      var remembered = null;
      try { remembered = localStorage.getItem('rdf2docs-context'); } catch (e) { /* not remembered */ }
      if (remembered !== null && remembered !== select.value && select.querySelector('option[value="' + CSS.escape(remembered) + '"]')) {
        select.value = remembered;
        select.dispatchEvent(new Event('change'));
      }
    }
    var names = wrap.querySelector('input.names');
    if (names) names.addEventListener('change', function () { box.classList.toggle('names', names.checked); });

    // ── The legend points out every shape of a type ──
    wrap.querySelectorAll('.legend [data-type]').forEach(function (item) {
      item.addEventListener('click', function () {
        var type = item.getAttribute('data-type'), on = !item.classList.contains('on');
        wrap.querySelectorAll('.legend [data-type]').forEach(function (o) { o.classList.remove('on'); });
        box.querySelectorAll('.highlight').forEach(function (el) { el.classList.remove('highlight'); });
        if (on) {
          item.classList.add('on');
          current().querySelectorAll('[data-type-iri="' + CSS.escape(type) + '"]').forEach(function (el) { el.classList.add('highlight'); });
        }
      });
    });

    // ── The drawing shown, whole, to download ──
    var download = wrap.querySelector('[data-download]');
    if (download) download.addEventListener('click', function () {
      var svg = current().cloneNode(true), f = full();
      svg.setAttribute('viewBox', f.x + ' ' + f.y + ' ' + f.w + ' ' + f.h);
      svg.setAttribute('width', f.w); svg.setAttribute('height', f.h);
      svg.removeAttribute('data-full'); svg.removeAttribute('style'); svg.classList.remove('hidden');
      var blob = new Blob(['<?xml version="1.0" encoding="UTF-8"?>\n' + svg.outerHTML], { type: 'image/svg+xml' });
      var a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = (document.title || 'diagram').replace(/[^\w.-]+/g, '_') + '.svg';
      a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
    });

    // ── Opened at a shape (#node-…): zoomed to it ──
    function focusTarget() {
      var id = location.hash ? decodeURIComponent(location.hash.slice(1)) : '';
      var el = id && current().querySelector('[id="' + CSS.escape(id) + '"]');
      if (!el) return false;
      var r = rect(), er = el.getBoundingClientRect();
      if (!er.width && !er.height) return false;
      var x = vb.x + (er.left - r.left) / r.width * vb.w, y = vb.y + (er.top - r.top) / r.height * vb.h;
      var w = er.width / r.width * vb.w, h = er.height / r.height * vb.h, pad = Math.max(w, h) * 1.5 + 40;
      vb = aspectFit({ x: x - pad, y: y - pad, w: w + 2 * pad, h: h + 2 * pad });
      if (scale() > 1.5) { var cx = x + w / 2, cy = y + h / 2, rr = rect(); vb = { x: cx - rr.width / 3, y: cy - rr.height / 3, w: rr.width / 1.5, h: rr.height / 1.5 }; }
      apply();
      box.scrollIntoView({ block: 'center' });
      return true;
    }
    size();
    fit();
    focusTarget();
    window.addEventListener('hashchange', focusTarget);
  }

  document.addEventListener('DOMContentLoaded', function () { document.querySelectorAll('.diagram-wrap').forEach(init); });
})();
