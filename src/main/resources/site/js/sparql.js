/* rdf2docs — the SPARQL page: Oxigraph, in the page, over the whole model. The store is loaded from
 * window.LA_MODEL as TriG; results link to the pages of what they name. */
(function () {
  'use strict';
  var M = window.LA_MODEL, R = window.rdf2docs, OX = window.oxigraph;
  var $ = function (id) { return document.getElementById(id); };
  var esc = R.escapeHtml;
  var status = $('status'), store = null, last = null;
  var MAX_ROWS = 2000;
  var HISTORY = 'rdf2docs-sparql-history';

  function setStatus(text, error) { status.textContent = text; status.classList.toggle('error', !!error); }
  if (!M || !OX || !window.LA_OXIGRAPH_WASM) { setStatus('The model or the query engine did not load.', true); return; }

  var prefixDecl = Object.keys(M.prefixes).map(function (p) { return 'PREFIX ' + p + ': <' + M.prefixes[p] + '>'; }).join('\n');

  // ── Load ────────────────────────────────────────────────────────────────
  function load() {
    var t0 = performance.now();
    var bytes = Uint8Array.from(atob(window.LA_OXIGRAPH_WASM), function (c) { return c.charCodeAt(0); });
    OX.initSync({ module: bytes });
    store = new OX.Store();
    var head = Object.keys(M.prefixes).map(function (p) { return '@prefix ' + p + ': <' + M.prefixes[p] + '> .'; }).join('\n');
    var parts = [head];
    M.graphs.forEach(function (g) {
      var text = g.blocks.map(function (b) { return b[1]; }).join('\n');
      parts.push(g.iri ? '<' + g.iri + '> {\n' + text + '\n}' : text);
    });
    store.load(parts.join('\n'), { format: 'application/trig' });
    setStatus(store.size + ' statements in ' + M.graphs.length + ' graph' + (M.graphs.length === 1 ? '' : 's') + ', loaded in ' + Math.round(performance.now() - t0) + ' ms');
  }

  // ── Showing terms ───────────────────────────────────────────────────────
  var prefixes = Object.keys(M.prefixes).map(function (p) { return [p, M.prefixes[p]]; }).sort(function (a, b) { return b[1].length - a[1].length; });
  function compact(iri) {
    for (var i = 0; i < prefixes.length; i++) {
      var ns = prefixes[i][1];
      if (ns && iri.indexOf(ns) === 0 && /^[\w.-]*$/.test(iri.slice(ns.length))) return prefixes[i][0] + ':' + iri.slice(ns.length);
    }
    return '<' + iri + '>';
  }
  /** Where an IRI's page is, its name and its type's icon; a shape's is its view's page at the shape. */
  function pageOf(iri) {
    var p = M.pages[iri];
    if (p) return { href: p[0], name: p[1], icon: p[2] };
    var hashAt = iri.indexOf('#'), view = hashAt > 0 && M.pages[iri.slice(0, hashAt)];
    if (view) return { href: view[0] + iri.slice(hashAt), name: view[1] + ' ' + iri.slice(hashAt), icon: null };
    return null;
  }
  function termHtml(t) {
    if (!t) return '';
    if (t.type === 'uri') {
      var page = pageOf(t.value);
      return page ? '<a href="' + esc(page.href) + '" title="' + esc(t.value) + '">' + (page.icon || '') + esc(page.name) + '</a>'
        : '<a href="' + esc(t.value) + '" target="_blank" rel="noopener" title="' + esc(t.value) + '">' + esc(compact(t.value)) + '</a>';
    }
    if (t.type === 'literal' || t.type === 'typed-literal') {
      var extra = t['xml:lang'] ? '<span class="dt">@' + esc(t['xml:lang']) + '</span>'
        : (t.datatype && t.datatype !== 'http://www.w3.org/2001/XMLSchema#string' ? '<span class="dt">^^' + esc(compact(t.datatype)) + '</span>' : '');
      return '<span class="lit">' + esc(t.value) + '</span>' + extra;
    }
    if (t.type === 'bnode') return '<span class="bn">_:' + esc(t.value) + '</span>';
    if (t.type === 'triple') return '&lt;&lt;( ' + termHtml(t.value.subject) + ' ' + termHtml(t.value.predicate) + ' ' + termHtml(t.value.object) + ' )&gt;&gt;';
    return esc(String(t.value));
  }
  function termText(t) {
    if (!t) return '';
    if (t.type === 'triple') return '<<( ' + termText(t.value.subject) + ' ' + termText(t.value.predicate) + ' ' + termText(t.value.object) + ' )>>';
    return String(t.value);
  }
  function quadTerm(term) {
    if (term.termType === 'NamedNode') return { type: 'uri', value: term.value };
    if (term.termType === 'BlankNode') return { type: 'bnode', value: term.value };
    if (term.termType === 'Literal') return { type: 'literal', value: term.value, 'xml:lang': term.language || undefined, datatype: term.datatype && term.datatype.value };
    if (term.termType === 'Quad' || term.termType === 'Triple') return { type: 'triple', value: { subject: quadTerm(term.subject), predicate: quadTerm(term.predicate), object: quadTerm(term.object) } };
    return { type: 'literal', value: String(term.value) };
  }

  // ── Running a query ─────────────────────────────────────────────────────
  function form(q) {
    var body = q.replace(/#[^\n]*/g, '').replace(/(PREFIX|BASE)\s+[^\n]*/gi, '');
    var m = /\b(SELECT|ASK|CONSTRUCT|DESCRIBE)\b/i.exec(body);
    return m ? m[1].toUpperCase() : 'SELECT';
  }
  function run() {
    var q = $('query').value.trim();
    if (!q || !store) return;
    remember(q);
    var t0 = performance.now(), kind = form(q);
    try {
      var options = { use_default_graph_as_union: $('union').checked };
      if (kind === 'SELECT' || kind === 'ASK') options.results_format = 'application/sparql-results+json';
      var result = store.query(q, options);
      var took = Math.round(performance.now() - t0);
      if (kind === 'ASK') showAsk(JSON.parse(result).boolean);
      else if (kind === 'SELECT') showSelect(JSON.parse(result));
      else showGraph(result);
      $('took').textContent = (last && last.count != null ? last.count + ' result' + (last.count === 1 ? '' : 's') + ' · ' : '') + took + ' ms';
      setStatus(status.textContent.replace(/^Error.*$/, ''), false);
      try { history.replaceState(null, '', '#query=' + encodeURIComponent(q)); } catch (e) { /* a page from disk */ }
    } catch (e) {
      $('results').innerHTML = '';
      $('took').textContent = '';
      setStatus('Error: ' + (e && e.message ? e.message : e), true);
      last = null;
    }
    ['filter', 'csv', 'tsv', 'json', 'ttl'].forEach(function (id) { $(id).classList.add('hidden'); });
    if (last && last.kind === 'SELECT') ['filter', 'csv', 'tsv', 'json'].forEach(function (id) { $(id).classList.remove('hidden'); });
    if (last && last.kind === 'GRAPH') ['filter', 'ttl'].forEach(function (id) { $(id).classList.remove('hidden'); });
  }
  function showAsk(answer) {
    last = { kind: 'ASK', count: null };
    $('results').innerHTML = '<p style="font-size:1.4em"><strong>' + (answer ? 'Yes' : 'No') + '</strong> <span class="faint">(' + answer + ')</span></p>';
  }
  function showSelect(json) {
    var vars = json.head.vars || [], rows = json.results.bindings || [];
    last = { kind: 'SELECT', vars: vars, rows: rows, count: rows.length, json: json };
    var html = '<table class="sortable"><thead><tr>' + vars.map(function (v) { return '<th>?' + esc(v) + '</th>'; }).join('') + '</tr></thead><tbody>';
    rows.slice(0, MAX_ROWS).forEach(function (b) {
      html += '<tr>' + vars.map(function (v) { return '<td data-sort="' + esc(termText(b[v])) + '">' + termHtml(b[v]) + '</td>'; }).join('') + '</tr>';
    });
    html += '</tbody></table>';
    if (rows.length > MAX_ROWS) html += '<p class="faint">The first ' + MAX_ROWS + ' of ' + rows.length + ' rows are shown; the downloads have them all.</p>';
    if (!rows.length) html = '<p class="empty">No results.</p>';
    $('results').innerHTML = html;
    R.initSortable($('results'));
  }
  function showGraph(quads) {
    var list = Array.isArray(quads) ? quads : Array.from(quads || []);
    last = { kind: 'GRAPH', quads: list, count: list.length };
    var html = '<table class="sortable"><thead><tr><th>Subject</th><th>Predicate</th><th>Object</th></tr></thead><tbody>';
    list.slice(0, MAX_ROWS).forEach(function (q) {
      html += '<tr><td>' + termHtml(quadTerm(q.subject)) + '</td><td>' + termHtml(quadTerm(q.predicate)) + '</td><td>' + termHtml(quadTerm(q.object)) + '</td></tr>';
    });
    html += '</tbody></table>';
    if (!list.length) html = '<p class="empty">No statements.</p>';
    $('results').innerHTML = html;
    R.initSortable($('results'));
  }

  // ── Downloads ───────────────────────────────────────────────────────────
  function download(text, name, type) {
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([text], { type: type }));
    a.download = name; a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
  }
  function table(sep) {
    var quote = function (s) { return sep === ',' ? '"' + String(s).replace(/"/g, '""') + '"' : String(s).replace(/[\t\n]/g, ' '); };
    return [last.vars.map(quote).join(sep)].concat(last.rows.map(function (b) { return last.vars.map(function (v) { return quote(termText(b[v])); }).join(sep); })).join('\n');
  }
  $('csv').addEventListener('click', function () { download(table(','), 'results.csv', 'text/csv'); });
  $('tsv').addEventListener('click', function () { download(table('\t'), 'results.tsv', 'text/tab-separated-values'); });
  $('json').addEventListener('click', function () { download(JSON.stringify(last.json, null, 2), 'results.json', 'application/sparql-results+json'); });
  $('ttl').addEventListener('click', function () {
    var s = new OX.Store();
    last.quads.forEach(function (q) { s.add(OX.quad(q.subject, q.predicate, q.object, OX.defaultGraph())); });
    var text;
    try { text = s.dump({ format: 'text/turtle', prefixes: M.prefixes }); } catch (e) { text = s.dump({ format: 'text/turtle' }); }
    download(text, 'results.ttl', 'text/turtle');
  });

  // ── Examples, history, prefixes ─────────────────────────────────────────
  M.examples.forEach(function (ex, i) { var o = document.createElement('option'); o.value = String(i); o.textContent = ex.title; $('examples').appendChild(o); });
  $('examples').addEventListener('change', function () {
    var ex = M.examples[+this.value];
    if (ex) {
      $('query').value = ex.query;
      if (/all graphs off/.test(ex.title)) $('union').checked = false;
      run();
    }
    this.value = '';
  });
  function remember(q) {
    try {
      var list = JSON.parse(localStorage.getItem(HISTORY) || '[]').filter(function (x) { return x !== q; });
      list.unshift(q);
      localStorage.setItem(HISTORY, JSON.stringify(list.slice(0, 20)));
    } catch (e) { /* storage off: no history */ }
    fillHistory();
  }
  function fillHistory() {
    var select = $('history'), list = [];
    try { list = JSON.parse(localStorage.getItem(HISTORY) || '[]'); } catch (e) { list = []; }
    select.innerHTML = '<option value="">Recent queries…</option>' + list.map(function (q, i) {
      return '<option value="' + i + '">' + esc(q.replace(/PREFIX[^\n]*\n/gi, '').replace(/\s+/g, ' ').trim().slice(0, 80)) + '</option>';
    }).join('');
    select.onchange = function () { if (this.value !== '') { $('query').value = list[+this.value]; } this.value = ''; };
  }
  $('prefixes').addEventListener('click', function () {
    var q = $('query').value;
    var missing = prefixDecl.split('\n').filter(function (line) { return q.indexOf(line) < 0; });
    $('query').value = missing.join('\n') + (missing.length ? '\n\n' : '') + q;
  });
  $('run').addEventListener('click', run);
  $('query').addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(); }
    if (e.key === 'Tab' && !e.shiftKey) {
      e.preventDefault();
      var ta = this, s = ta.selectionStart;
      ta.value = ta.value.slice(0, s) + '  ' + ta.value.slice(ta.selectionEnd);
      ta.selectionStart = ta.selectionEnd = s + 2;
    }
  });

  fillHistory();
  // Load after the page has painted, so the status shows while the store fills.
  setTimeout(function () {
    try { load(); } catch (e) { setStatus('The store could not load the model: ' + (e && e.message ? e.message : e), true); return; }
    var hash = new URLSearchParams(location.hash.slice(1));
    var q = hash.get('query');
    if (q) { $('query').value = q; run(); }
    else if (!$('query').value) $('query').value = M.examples.length ? M.examples[0].query : prefixDecl + '\n\nSELECT * WHERE { ?s ?p ?o } LIMIT 100';
  }, 30);
})();
