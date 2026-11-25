/* rdf2docs — what every page does: tabs, sortable and filterable tables, collapsible groups, copying, and the
 * diagram's zoom, pan, presentation contexts and names on lines. Plain script, no library: it runs from disk too. */
(function () {
  'use strict';

  // ── Tabs ────────────────────────────────────────────────────────────────
  window.showTab = function (btn, id) {
    var bar = btn.closest('.tabs');
    var scope = bar ? bar.parentElement : document;
    scope.querySelectorAll(':scope > .tab-panel').forEach(function (p) { p.style.display = 'none'; });
    if (bar) bar.querySelectorAll('.tab-btn').forEach(function (b) { b.classList.remove('active'); });
    var panel = document.getElementById(id);
    if (panel) panel.style.display = 'block';
    btn.classList.add('active');
    try { sessionStorage.setItem('rdf2docs-tab:' + location.pathname, btn.textContent.replace(/\s*\(\d+\)$/, '')); } catch (e) { /* storage off */ }
  };

  function restoreTab() {
    // A fragment inside a tab opens that tab; else the tab last chosen on this page.
    var target = location.hash ? document.getElementById(decodeURIComponent(location.hash.slice(1))) : null;
    var panel = target && target.closest('.tab-panel');
    var wanted = null;
    try { wanted = sessionStorage.getItem('rdf2docs-tab:' + location.pathname); } catch (e) { /* storage off */ }
    document.querySelectorAll('.tabs').forEach(function (bar) {
      var buttons = Array.prototype.slice.call(bar.querySelectorAll('.tab-btn'));
      var pick = null;
      if (panel) pick = buttons.filter(function (b) { return (b.getAttribute('onclick') || '').indexOf("'" + panel.id + "'") >= 0; })[0];
      if (!pick && wanted) pick = buttons.filter(function (b) { return b.textContent.replace(/\s*\(\d+\)$/, '') === wanted; })[0];
      if (pick) pick.click();
    });
    if (target) target.scrollIntoView({ block: 'center' });
  }

  // ── Sortable tables ─────────────────────────────────────────────────────
  function cellValue(row, i) {
    var cell = row.children[i];
    if (!cell) return '';
    return (cell.getAttribute('data-sort') || cell.textContent || '').trim().toLowerCase();
  }
  function sortTable(table, i, th) {
    var asc = !th.classList.contains('asc');
    table.querySelectorAll('th').forEach(function (h) { h.classList.remove('asc', 'desc'); });
    th.classList.add(asc ? 'asc' : 'desc');
    Array.prototype.forEach.call(table.tBodies, function (body) {
      var rows = Array.prototype.slice.call(body.rows);
      rows.sort(function (a, b) {
        var x = cellValue(a, i), y = cellValue(b, i);
        var nx = parseFloat(x), ny = parseFloat(y);
        var c = (!isNaN(nx) && !isNaN(ny) && String(nx) === x && String(ny) === y) ? nx - ny : x.localeCompare(y);
        return asc ? c : -c;
      });
      rows.forEach(function (r) { body.appendChild(r); });
    });
  }
  function initSortable(root) {
    (root || document).querySelectorAll('table.sortable').forEach(function (table) {
      if (table.dataset.sortReady) return;
      table.dataset.sortReady = '1';
      table.querySelectorAll('thead th').forEach(function (th, i) {
        th.addEventListener('click', function () { sortTable(table, i, th); });
      });
    });
  }

  // ── Filters: an input filters the rows of what it names, and hides the groups left empty ──
  function applyFilter(input) {
    var q = input.value.trim().toLowerCase();
    var scope = document.querySelector(input.getAttribute('data-filter'));
    if (!scope) return;
    scope.querySelectorAll('tbody tr, li.item').forEach(function (row) {
      row.classList.toggle('hidden', !!q && row.textContent.toLowerCase().indexOf(q) < 0);
    });
    // Groups from the inside out, so that an outer group sees what its inner ones show.
    var groups = Array.prototype.slice.call(scope.querySelectorAll('details.group, section.folder-section')).reverse();
    groups.forEach(function (g) {
      var visible = g.querySelectorAll('tbody tr:not(.hidden), li.item:not(.hidden)').length;
      g.classList.toggle('hidden', !!q && visible === 0);
      if (q && visible > 0 && g.tagName === 'DETAILS') g.open = true;
      var count = g.querySelector(':scope > summary .count');
      if (count) {
        if (!count.dataset.total) count.dataset.total = count.textContent;
        count.textContent = q ? visible + '/' + count.dataset.total : count.dataset.total;
      }
    });
  }
  function initFilters() {
    document.querySelectorAll('input[data-filter]').forEach(function (input) {
      input.addEventListener('input', function () { applyFilter(input); });
    });
  }

  // ── Expand and collapse every group in a scope ──
  function initGroupButtons() {
    document.addEventListener('click', function (e) {
      var b = e.target.closest('[data-expand],[data-collapse]');
      if (!b) return;
      e.preventDefault();
      var open = b.hasAttribute('data-expand');
      var scope = document.querySelector(b.getAttribute(open ? 'data-expand' : 'data-collapse'));
      if (scope) scope.querySelectorAll('details').forEach(function (d) { d.open = open; });
    });
  }

  // ── Copy ──
  function initCopy() {
    document.addEventListener('click', function (e) {
      var b = e.target.closest('button.copy');
      if (!b) return;
      var text = b.getAttribute('data-copy') || '';
      var done = function () { var t = b.textContent; b.textContent = '✓'; setTimeout(function () { b.textContent = t; }, 900); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, function () {});
      else { var ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select(); try { document.execCommand('copy'); done(); } catch (x) { /* nothing */ } ta.remove(); }
    });
  }

  // ── View switches: buttons in a .seg show one of several blocks ──
  function initSeg() {
    document.querySelectorAll('.seg[data-switch]').forEach(function (seg) {
      var buttons = seg.querySelectorAll('button[data-show]');
      buttons.forEach(function (b) {
        b.addEventListener('click', function () {
          buttons.forEach(function (o) {
            o.classList.toggle('on', o === b);
            var block = document.querySelector(o.getAttribute('data-show'));
            if (block) block.classList.toggle('hidden', o !== b);
          });
          try { localStorage.setItem('rdf2docs-seg:' + seg.getAttribute('data-switch'), b.getAttribute('data-show')); } catch (e) { /* storage off */ }
        });
      });
      var remembered = null;
      try { remembered = localStorage.getItem('rdf2docs-seg:' + seg.getAttribute('data-switch')); } catch (e) { /* storage off */ }
      var pick = remembered && seg.querySelector('button[data-show="' + remembered + '"]');
      if (pick) pick.click();
    });
  }

  // ── Index: a folder's section, from the tree or the header's Folders menu (index.html#sec-…) ──
  window.selectFolder = function (secId) {
    var target = document.getElementById(secId);
    if (!target) return;
    var seg = document.querySelector('.seg [data-show="#by-folder"]');
    var byFolder = document.getElementById('by-folder');
    if (seg && byFolder && byFolder.classList.contains('hidden')) seg.click();
    document.querySelectorAll('#by-folder > section').forEach(function (s) { s.classList.toggle('hidden', s !== target && !s.contains(target)); });
    target.classList.remove('hidden');
    for (var d = target; d; d = d.parentElement && d.parentElement.closest('details')) if (d.tagName === 'DETAILS') d.open = true;
    target.querySelectorAll(':scope > details').forEach(function (d) { d.open = true; });
    var all = document.getElementById('show-all');
    if (all) all.classList.remove('hidden');
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  window.showAll = function () {
    document.querySelectorAll('#by-folder > section').forEach(function (s) { s.classList.remove('hidden'); });
    var all = document.getElementById('show-all');
    if (all) all.classList.add('hidden');
  };
  function openFolderFromHash() {
    var id = location.hash ? decodeURIComponent(location.hash.slice(1)) : '';
    if (id.indexOf('sec-') === 0 && document.getElementById(id)) window.selectFolder(id);
  }
  window.addEventListener('hashchange', openFolderFromHash);

  // ── Chips: a row of type chips filters the rows of a scope by type ──
  function initChips() {
    document.querySelectorAll('.chips[data-scope]').forEach(function (chips) {
      var scope = document.querySelector(chips.getAttribute('data-scope'));
      if (!scope) return;
      chips.addEventListener('click', function (e) {
        var chip = e.target.closest('.chip[data-type]');
        if (!chip) return;
        chip.classList.toggle('off');
        var off = Array.prototype.map.call(chips.querySelectorAll('.chip.off'), function (c) { return c.getAttribute('data-type'); });
        scope.querySelectorAll('[data-row-type]').forEach(function (row) {
          row.classList.toggle('type-off', off.indexOf(row.getAttribute('data-row-type')) >= 0);
          row.style.display = row.classList.contains('type-off') ? 'none' : '';
        });
      });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    initSortable();
    initFilters();
    initGroupButtons();
    initCopy();
    initSeg();
    initChips();
    openFolderFromHash();
    restoreTab();
  });
  // ── Highlighting Turtle, TriG and SPARQL ───────────────────────────────
  var TOKENS = /(#[^\n]*)|(<[^<>"{}|^`\\\s]*>)|("""[\s\S]*?"""|'''[\s\S]*?'''|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')|(@[a-zA-Z]+(?:-[a-zA-Z0-9]+)*|\^\^)|([?$][A-Za-z_]\w*)|\b(PREFIX|BASE|SELECT|CONSTRUCT|DESCRIBE|ASK|WHERE|FILTER|OPTIONAL|UNION|GRAPH|DISTINCT|REDUCED|ORDER|BY|GROUP|LIMIT|OFFSET|COUNT|SUM|MIN|MAX|AVG|SAMPLE|GROUP_CONCAT|AS|BIND|VALUES|NOT|EXISTS|MINUS|SERVICE|FROM|NAMED|HAVING|DESC|ASC|STR|LANG|REGEX|CONTAINS|STRSTARTS|COALESCE|IF|BOUND|ISIRI|ISLITERAL|true|false|a)\b|((?:[A-Za-z][\w.-]*)?:(?:[\w-](?:[\w.-]*[\w-])?)?)|(-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)/g;
  var CLASSES = [null, 'tok-com', 'tok-iri', 'tok-lit', 'tok-lang', 'tok-var', 'tok-kw', 'tok-pn', 'tok-num'];
  function escapeHtml(s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  /** The text as highlighted HTML; [mark], a global regular expression, marks what it matches in each token. */
  function highlight(text, mark) {
    var out = '', last = 0, m;
    function plain(s) {
      if (!mark || !s) return escapeHtml(s);
      var r = '', at = 0, x;
      mark.lastIndex = 0;
      while ((x = mark.exec(s)) !== null) {
        if (x[0] === '') { mark.lastIndex++; continue; }
        r += escapeHtml(s.slice(at, x.index)) + '<mark>' + escapeHtml(x[0]) + '</mark>';
        at = x.index + x[0].length;
      }
      return r + escapeHtml(s.slice(at));
    }
    TOKENS.lastIndex = 0;
    while ((m = TOKENS.exec(text)) !== null) {
      if (m[0] === '') { TOKENS.lastIndex++; continue; }
      out += plain(text.slice(last, m.index));
      var cls = null;
      for (var i = 1; i < CLASSES.length; i++) if (m[i] !== undefined) { cls = CLASSES[i]; break; }
      out += cls ? '<span class="' + cls + '">' + plain(m[0]) + '</span>' : plain(m[0]);
      last = m.index + m[0].length;
    }
    return out + plain(text.slice(last));
  }

  window.rdf2docs = { initSortable: initSortable, highlight: highlight, escapeHtml: escapeHtml, applyFilter: applyFilter };
})();
