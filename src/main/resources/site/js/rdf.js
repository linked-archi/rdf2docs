/* rdf2docs — the RDF page: the model's TriG, a section per named graph, chosen by graph and searched by text.
 * Reads window.LA_MODEL: the prefixes and, for each graph, each subject's statements as a Turtle block. */
(function () {
  'use strict';
  var M = window.LA_MODEL, R = window.rdf2docs;
  if (!M) { document.getElementById('graphs').textContent = 'The model data did not load.'; return; }
  var $ = function (id) { return document.getElementById(id); };
  var esc = R.escapeHtml;
  var PAGE = 300;

  var prefixText = Object.keys(M.prefixes).map(function (p) { return '@prefix ' + p + ': <' + M.prefixes[p] + '> .'; }).join('\n');
  $('prefixes').innerHTML = R.highlight(prefixText);

  // Which graphs are shown: all, unless the address names some (#graph=<iri>, repeated).
  var shown = new Set(M.graphs.map(function (g, i) { return i; }));
  var hash = new URLSearchParams(location.hash.slice(1));
  var wanted = hash.getAll('graph');
  if (wanted.length) shown = new Set(M.graphs.map(function (g, i) { return wanted.indexOf(g.iri || '') >= 0 ? i : -1; }).filter(function (i) { return i >= 0; }));
  if (hash.get('q')) $('q').value = hash.get('q');

  function graphList() {
    var f = $('graph-filter').value.trim().toLowerCase();
    $('graph-list').innerHTML = M.graphs.map(function (g, i) {
      var name = g.label + (g.iri && g.iri !== g.label ? ' ' + g.iri : '');
      if (f && name.toLowerCase().indexOf(f) < 0) return '';
      return '<label title="' + esc(g.iri || 'the default graph') + '"><input type="checkbox" data-i="' + i + '"' + (shown.has(i) ? ' checked' : '') + '>' +
        '<span class="name">' + esc(g.label) + '</span><small>' + g.size + '</small></label>';
    }).join('');
  }
  $('graph-list').addEventListener('change', function (e) {
    var i = e.target.getAttribute('data-i'); if (i == null) return;
    if (e.target.checked) shown.add(+i); else shown.delete(+i);
    render();
  });
  $('graph-filter').addEventListener('input', graphList);
  $('all').addEventListener('click', function (e) { e.preventDefault(); M.graphs.forEach(function (g, i) { shown.add(i); }); graphList(); render(); });
  $('none').addEventListener('click', function (e) { e.preventDefault(); shown.clear(); graphList(); render(); });

  function matcher() {
    var q = $('q').value.trim();
    if (!q) return null;
    if ($('regex').checked) { try { return new RegExp(q, 'gi'); } catch (e) { return null; } }
    return new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi');
  }

  function blockHtml(block, mark, inGraph) {
    var page = M.pages[block[0]];
    var open = page ? '<a class="open" href="' + esc(page[0]) + '">' + (page[2] || '') + esc(page[1]) + ' →</a>' : '';
    var text = inGraph ? block[1].split('\n').map(function (l) { return '  ' + l; }).join('\n') : block[1];
    return '<div class="block">' + R.highlight(text, mark) + open + '</div>';
  }

  function render() {
    var mark = matcher(), subjectsOnly = $('subjects-only').checked, total = 0, out = '';
    M.graphs.forEach(function (g, gi) {
      if (!shown.has(gi)) return;
      var blocks = g.blocks;
      if (mark) blocks = blocks.filter(function (b) { mark.lastIndex = 0; return mark.test(subjectsOnly ? b[0] : b[1]); });
      if (!blocks.length) return;
      total += blocks.length;
      var head = g.iri ? '<span class="graph-head">' + R.highlight('<' + g.iri + '> {') + '</span>' : '<span class="graph-head faint"># the default graph</span>';
      var link = g.page ? ' <a href="' + esc(g.page) + '">about this graph</a>' : '';
      var body = '<pre class="code" data-g="' + gi + '">' + head + '\n' + blocks.slice(0, PAGE).map(function (b) { return blockHtml(b, mark, !!g.iri); }).join('') +
        (g.iri ? '<span class="tok-punc">}</span>' : '') + '</pre>' +
        (blocks.length > PAGE ? '<div class="more"><button class="btn" data-more="' + gi + '" data-at="' + PAGE + '">Show ' + Math.min(PAGE, blocks.length - PAGE) + ' more of ' + (blocks.length - PAGE) + '</button></div>' : '');
      out += '<details class="group" open><summary>' + esc(g.label) + ' <span class="count">' + blocks.length + (mark ? '/' + g.blocks.length : '') + '</span>' +
        '<span class="group-tools faint">' + g.size + ' statements' + link + '</span></summary><div class="group-body">' + body + '</div></details>';
      render.filtered[gi] = blocks;
    });
    $('graphs').innerHTML = out || '<p class="empty">Nothing matches.</p>';
    $('hits').textContent = mark ? total + ' subjects match' : '';
  }
  render.filtered = {};

  $('graphs').addEventListener('click', function (e) {
    var b = e.target.closest('[data-more]');
    if (!b) return;
    var gi = +b.getAttribute('data-more'), at = +b.getAttribute('data-at');
    var g = M.graphs[gi], blocks = render.filtered[gi] || g.blocks, mark = matcher();
    var pre = $('graphs').querySelector('pre[data-g="' + gi + '"]');
    var close = pre.lastElementChild && pre.lastElementChild.classList.contains('tok-punc') ? pre.lastElementChild : null;
    var html = blocks.slice(at, at + PAGE).map(function (x) { return blockHtml(x, mark, !!g.iri); }).join('');
    if (close) close.insertAdjacentHTML('beforebegin', html); else pre.insertAdjacentHTML('beforeend', html);
    var next = at + PAGE;
    if (next >= blocks.length) b.parentElement.remove();
    else { b.setAttribute('data-at', next); b.textContent = 'Show ' + Math.min(PAGE, blocks.length - next) + ' more of ' + (blocks.length - next); }
  });

  var timer = null;
  function later() { clearTimeout(timer); timer = setTimeout(render, 180); }
  $('q').addEventListener('input', later);
  $('regex').addEventListener('change', render);
  $('subjects-only').addEventListener('change', render);

  // The graphs shown, as one TriG document.
  $('download-selected').addEventListener('click', function () {
    var parts = [prefixText, ''];
    M.graphs.forEach(function (g, gi) {
      if (!shown.has(gi)) return;
      var text = g.blocks.map(function (b) { return b[1]; }).join('\n\n');
      parts.push(g.iri ? '<' + g.iri + '> {\n' + text.split('\n').map(function (l) { return '  ' + l; }).join('\n') + '\n}\n' : text + '\n');
    });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([parts.join('\n')], { type: 'application/trig' }));
    a.download = 'model-selection.trig';
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
  });

  graphList();
  render();
})();
