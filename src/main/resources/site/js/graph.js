/* rdf2docs — the graph page. One data set (window.LA_GRAPH), two renderers: D3's force layout, with its forces on
 * sliders, and Cytoscape.js, each node drawn as its notation's figure and each edge in its notation's line style with
 * its end decorations. Filters, scope, search and the folder tree are shared. */
(function () {
  'use strict';
  var G = window.LA_GRAPH;
  if (!G) { document.getElementById('canvas').textContent = 'The graph data did not load.'; return; }

  // ── State, remembered per viewer ──────────────────────────────────────────
  // The settings kept per reader. Version 2: relationship types are no longer hidden by the graph config's
  // excludedNodeTypes, so what an earlier page remembered as hidden is not carried over.
  var KEY = 'rdf2docs-graph-2';
  var saved = {};
  try { saved = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch (e) { saved = {}; }
  var DEFAULT_FORCES = { distance: 90, strength: 0.5, charge: -250, collide: 26, gravity: 0.05, friction: 0.4 };
  var S = {
    renderer: saved.renderer || 'd3',
    scope: saved.scope || 'ego',
    depth: saved.depth || 2,
    showViews: !!saved.showViews,
    hideIsolated: saved.hideIsolated !== false,
    nodeLabels: saved.nodeLabels !== false,
    edgeLabels: !!saved.edgeLabels,
    sizeDegree: saved.sizeDegree !== false,
    forces: Object.assign({}, DEFAULT_FORCES, saved.forces || {}),
    cyLayout: saved.cyLayout || 'cose',
    cyScale: saved.cyScale || 0.8,
    cySpacing: saved.cySpacing || 1.2,
    centre: null,
    typesOff: new Set(saved.typesOff || G.types.filter(function (t) { return !t.checked; }).map(function (t) { return t.iri; })),
    relsOff: new Set(saved.relsOff || G.relTypes.filter(function (t) { return !t.checked; }).map(function (t) { return t.iri; })),
    modelsOff: new Set(saved.modelsOff || []),
  };
  function save() {
    try {
      localStorage.setItem(KEY, JSON.stringify({
        renderer: S.renderer, scope: S.scope, depth: S.depth, showViews: S.showViews, hideIsolated: S.hideIsolated,
        nodeLabels: S.nodeLabels, edgeLabels: S.edgeLabels, sizeDegree: S.sizeDegree, forces: S.forces,
        cyLayout: S.cyLayout, cyScale: S.cyScale, cySpacing: S.cySpacing,
        typesOff: Array.from(S.typesOff), relsOff: Array.from(S.relsOff), modelsOff: Array.from(S.modelsOff),
      }));
    } catch (e) { /* storage off: nothing is remembered */ }
  }

  var byId = new Map(G.nodes.map(function (n) { return [n.id, n]; }));
  var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
  var $ = function (id) { return document.getElementById(id); };
  var degreeAll = new Map();
  G.edges.forEach(function (e) {
    if (e.contains) return;
    degreeAll.set(e.source, (degreeAll.get(e.source) || 0) + 1);
    degreeAll.set(e.target, (degreeAll.get(e.target) || 0) + 1);
  });
  // The centre to start from: the remembered one, else the best-connected element.
  S.centre = (saved.centre && byId.has(saved.centre)) ? saved.centre : (G.nodes.filter(function (n) { return n.kind === 'element'; })
    .sort(function (a, b) { return (degreeAll.get(b.id) || 0) - (degreeAll.get(a.id) || 0); })[0] || G.nodes[0] || {}).id;

  // ── What is shown ────────────────────────────────────────────────────────
  function nodeVisible(n) {
    if (n.kind === 'view' && !S.showViews) return false;
    if (n.typeIri && S.typesOff.has(n.typeIri)) return false;
    if (n.model && S.modelsOff.has(n.model)) return false;
    return true;
  }
  /** The view in the middle, when views are not nodes: it stands for what it shows. */
  function viewCentre() {
    var c = byId.get(S.centre);
    return S.scope === 'ego' && c && c.kind === 'view' && !S.showViews ? c : null;
  }
  function visible() {
    var vc = viewCentre();
    var ns = new Set(G.nodes.filter(nodeVisible).map(function (n) { return n.id; }));
    if (vc) {
      // A view in the middle, views not nodes: the elements it shows and the relationships between them.
      var shown = new Set(G.edges.filter(function (e) { return e.contains && e.source === vc.id && ns.has(e.target); }).map(function (e) { return e.target; }));
      var within = G.edges.filter(function (e) { return !e.contains && shown.has(e.source) && shown.has(e.target) && !(e.typeIri && S.relsOff.has(e.typeIri)); });
      return { nodes: G.nodes.filter(function (n) { return shown.has(n.id); }), edges: within };
    }
    if (S.centre && byId.has(S.centre)) ns.add(S.centre);
    var es = G.edges.filter(function (e) {
      if (!ns.has(e.source) || !ns.has(e.target)) return false;
      if (e.contains) return S.showViews;
      return !(e.typeIri && S.relsOff.has(e.typeIri));
    });
    if (S.scope === 'ego' && S.centre && byId.has(S.centre)) {
      var adj = new Map();
      es.forEach(function (e) {
        (adj.get(e.source) || adj.set(e.source, []).get(e.source)).push(e.target);
        (adj.get(e.target) || adj.set(e.target, []).get(e.target)).push(e.source);
      });
      var dist = new Map([[S.centre, 0]]), queue = [S.centre];
      while (queue.length) {
        var id = queue.shift(), d = dist.get(id);
        if (d >= S.depth) continue;
        (adj.get(id) || []).forEach(function (nb) { if (!dist.has(nb)) { dist.set(nb, d + 1); queue.push(nb); } });
      }
      ns = new Set(dist.keys());
      es = es.filter(function (e) { return ns.has(e.source) && ns.has(e.target); });
    }
    if (S.hideIsolated) {
      var linked = new Set();
      es.forEach(function (e) { linked.add(e.source); linked.add(e.target); });
      ns.forEach(function (id) { if (!linked.has(id) && id !== S.centre) ns.delete(id); });
    }
    var nodes = G.nodes.filter(function (n) { return ns.has(n.id); });
    return { nodes: nodes, edges: es };
  }

  // ── The side panel ───────────────────────────────────────────────────────
  function checklist(container, items, off, groupKey, onChange) {
    var html = '', last = null;
    items.forEach(function (t, i) {
      var g = t[groupKey] || '';
      if (groupKey && g !== last) { html += '<div class="group-title">' + esc(g || 'Other') + '</div>'; last = g; }
      html += '<label title="' + esc(t.iri) + '"><input type="checkbox" data-i="' + i + '"' + (off.has(t.iri) ? '' : ' checked') + '>' +
        (t.icon || '') + esc(t.label) + (t.count != null ? '<small>' + t.count + '</small>' : '') + '</label>';
    });
    container.innerHTML = html || '<span class="faint">None</span>';
    container.addEventListener('change', function (e) {
      var i = e.target.getAttribute('data-i');
      if (i == null) return;
      var iri = items[+i].iri;
      if (e.target.checked) off.delete(iri); else off.add(iri);
      onChange();
    });
    return function (all) {
      items.forEach(function (t) { if (all) off.delete(t.iri); else off.add(t.iri); });
      container.querySelectorAll('input').forEach(function (cb) { cb.checked = all; });
      onChange();
    };
  }
  // The groups the graph config names (--graph-config filterGroups), when it names any; else the languages' own.
  if (G.filterGroups && G.filterGroups.length) {
    var configured = new Map();
    G.filterGroups.forEach(function (g) { g.types.forEach(function (iri) { if (!configured.has(iri)) configured.set(iri, g.label); }); });
    var order = G.filterGroups.map(function (g) { return g.label; }).concat(['Other']);
    G.types.forEach(function (t) { t.group = configured.get(t.iri) || 'Other'; });
    G.relTypes.forEach(function (t) { if (configured.has(t.iri)) t.group = configured.get(t.iri); });
    G.types.sort(function (a, b) { return order.indexOf(a.group) - order.indexOf(b.group) || a.label.localeCompare(b.label); });
  }
  var setTypes = checklist($('f-types'), G.types, S.typesOff, 'group', update);
  var setRels = checklist($('f-rels'), G.relTypes, S.relsOff, 'group', update);
  if (G.models.length > 1) checklist($('f-models'), G.models, S.modelsOff, null, update); else $('models-block').classList.add('hidden');
  document.querySelectorAll('[data-all],[data-none]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      e.preventDefault(); e.stopPropagation();
      var which = a.getAttribute('data-all') || a.getAttribute('data-none');
      (which === 'types' ? setTypes : setRels)(a.hasAttribute('data-all'));
    });
  });

  function folderTree(folders) {
    return '<ul>' + folders.map(function (f) {
      var members = f.members.map(function (id) {
        var n = byId.get(id); if (!n) return '';
        return '<li class="fnode" data-id="' + esc(id) + '">' + (n.icon || '') + esc(n.label) + '</li>';
      }).join('');
      return '<li><span class="fname">' + esc(f.name) + '</span><ul>' + members + (f.children.length ? folderTree(f.children).slice(4, -5) : '') + '</ul></li>';
    }).join('') + '</ul>';
  }
  $('folders').innerHTML = G.folders.length ? folderTree(G.folders) : '<span class="faint">No folders</span>';
  $('folders').addEventListener('click', function (e) {
    var name = e.target.closest('.fname');
    if (name) { name.parentElement.classList.toggle('open'); return; }
    var node = e.target.closest('.fnode');
    if (node) centreOn(node.getAttribute('data-id'));
  });

  function bindRange(id, get, set) {
    var input = $(id), out = input.nextElementSibling;
    input.value = get();
    if (out) out.textContent = input.value;
    input.addEventListener('input', function () { if (out) out.textContent = input.value; set(parseFloat(input.value), false); });
    input.addEventListener('change', function () { set(parseFloat(input.value), true); save(); });
  }
  bindRange('depth', function () { return S.depth; }, function (v, done) { S.depth = v; $('depth-val').textContent = v; if (done) update(); });
  ['distance', 'strength', 'charge', 'collide', 'gravity', 'friction'].forEach(function (k) {
    bindRange('f-' + k, function () { return S.forces[k]; }, function (v) { S.forces[k] = v; if (S.renderer === 'd3') d3r.forces(); });
  });
  bindRange('cy-scale', function () { return S.cyScale; }, function (v, done) { S.cyScale = v; if (done && S.renderer === 'cy') cyr.restyle(); });
  bindRange('cy-spacing', function () { return S.cySpacing; }, function (v, done) { S.cySpacing = v; if (done && S.renderer === 'cy') cyr.layout(); });
  $('cy-layout').value = S.cyLayout;
  $('cy-layout').addEventListener('change', function () { S.cyLayout = this.value; save(); cyr.layout(); });
  $('cy-relayout').addEventListener('click', function () { cyr.layout(); });
  $('reheat').addEventListener('click', function () { d3r.reheat(); });
  $('freeze').addEventListener('click', function () { d3r.freeze(); });
  $('reset-forces').addEventListener('click', function () {
    S.forces = Object.assign({}, DEFAULT_FORCES);
    ['distance', 'strength', 'charge', 'collide', 'gravity', 'friction'].forEach(function (k) {
      var input = $('f-' + k); input.value = S.forces[k]; input.nextElementSibling.textContent = input.value;
    });
    save(); d3r.forces(); d3r.reheat();
  });
  function bindCheck(id, key, redraw) {
    var cb = $(id); cb.checked = S[key];
    cb.addEventListener('change', function () { S[key] = cb.checked; save(); redraw(); });
  }
  bindCheck('show-views', 'showViews', update);
  bindCheck('hide-isolated', 'hideIsolated', update);
  bindCheck('node-labels', 'nodeLabels', function () { current().labels(); });
  bindCheck('edge-labels', 'edgeLabels', function () { current().labels(); });
  bindCheck('size-degree', 'sizeDegree', update);

  function seg(id, attr, get, set) {
    var el = $(id);
    el.querySelectorAll('button').forEach(function (b) {
      b.classList.toggle('on', b.getAttribute(attr) === get());
      b.addEventListener('click', function () {
        el.querySelectorAll('button').forEach(function (o) { o.classList.toggle('on', o === b); });
        set(b.getAttribute(attr)); save();
      });
    });
  }
  seg('scope', 'data-s', function () { return S.scope; }, function (v) { S.scope = v; update(); });
  seg('renderer', 'data-r', function () { return S.renderer; }, function (v) { switchRenderer(v); });

  // ── Search ───────────────────────────────────────────────────────────────
  var results = $('search-results'), active = -1, matches = [];
  $('search').addEventListener('input', function () {
    var q = this.value.trim().toLowerCase();
    active = -1;
    matches = !q ? [] : G.nodes.filter(function (n) { return n.label.toLowerCase().indexOf(q) >= 0 || (n.type || '').toLowerCase().indexOf(q) >= 0; }).slice(0, 30);
    results.innerHTML = matches.map(function (n, i) {
      return '<div data-i="' + i + '">' + (n.icon || '') + esc(n.label) + '<small>' + esc(n.type) + '</small></div>';
    }).join('');
  });
  $('search').addEventListener('keydown', function (e) {
    if (!matches.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      active = Math.max(0, Math.min(matches.length - 1, active + (e.key === 'ArrowDown' ? 1 : -1)));
      results.querySelectorAll('div').forEach(function (d, i) { d.classList.toggle('active', i === active); });
    } else if (e.key === 'Enter') {
      e.preventDefault();
      centreOn(matches[Math.max(0, active)].id);
    } else if (e.key === 'Escape') { results.innerHTML = ''; matches = []; }
  });
  results.addEventListener('click', function (e) {
    var d = e.target.closest('[data-i]');
    if (d) centreOn(matches[+d.getAttribute('data-i')].id);
  });

  function centreOn(id) {
    if (!byId.has(id)) return;
    S.centre = id;
    try { localStorage.setItem(KEY, JSON.stringify(Object.assign(JSON.parse(localStorage.getItem(KEY) || '{}'), { centre: id }))); } catch (e) { /* storage off */ }
    results.innerHTML = ''; matches = [];
    // A node a filter hides is shown as the centre all the same.
    update();
    showInfo(id);
  }

  // ── The info panel ───────────────────────────────────────────────────────
  function showInfo(id) {
    var n = byId.get(id), info = $('info');
    if (!n) { info.classList.add('hidden'); return; }
    var out = [], inc = [];
    G.edges.forEach(function (e) {
      if (e.source === id && byId.has(e.target)) out.push(e);
      else if (e.target === id && byId.has(e.source)) inc.push(e);
    });
    function list(edges, end) {
      return '<ul>' + edges.slice(0, 200).map(function (e) {
        var o = byId.get(e[end]);
        return '<li data-id="' + esc(o.id) + '">' + (o.icon || '') + esc(o.label) + ' <span class="rel">' + esc(e.contains ? 'in view' : e.type) + '</span></li>';
      }).join('') + '</ul>';
    }
    info.innerHTML = '<button class="close" title="Close">×</button>' +
      '<h3>' + (n.icon || '') + esc(n.label) + '</h3>' +
      '<div class="faint">' + esc(n.type) + (n.group ? ' · ' + esc(n.group) : '') + '</div>' +
      '<div class="actions"><a class="btn" href="' + esc(n.href) + '">Open page</a><button class="btn" data-centre="' + esc(id) + '">Centre here</button></div>' +
      (out.length ? '<strong>Outgoing (' + out.length + ')</strong>' + list(out, 'target') : '') +
      (inc.length ? '<strong>Incoming (' + inc.length + ')</strong>' + list(inc, 'source') : '');
    info.classList.remove('hidden');
  }
  $('info').addEventListener('click', function (e) {
    if (e.target.closest('.close')) { $('info').classList.add('hidden'); return; }
    var c = e.target.closest('[data-centre]');
    if (c) { centreOn(c.getAttribute('data-centre')); return; }
    var li = e.target.closest('li[data-id]');
    if (li) centreOn(li.getAttribute('data-id'));
  });

  var tooltip = $('tooltip');
  function tip(n, x, y) {
    if (!n) { tooltip.style.display = 'none'; return; }
    tooltip.innerHTML = (n.icon || '') + '<strong>' + esc(n.label) + '</strong><br>' + esc(n.type) +
      '<br><small>Click: details · Double-click: centre</small>';
    tooltip.style.left = (x + 14) + 'px'; tooltip.style.top = (y + 10) + 'px'; tooltip.style.display = 'block';
  }

  // ── Decorations, as la-gram's graph draws them ───────────────────────────
  function arrow(decoration) {
    switch (decoration) {
      case 'OpenArrowhead': case 'HalfArrowhead': return { shape: 'vee', fill: 'filled' };
      case 'ClosedArrowhead': return { shape: 'triangle', fill: 'filled' };
      case 'HollowArrowhead': return { shape: 'triangle', fill: 'hollow' };
      case 'FilledDiamond': return { shape: 'diamond', fill: 'filled' };
      case 'HollowDiamond': return { shape: 'diamond', fill: 'hollow' };
      case 'CircleDot': return { shape: 'circle', fill: 'filled' };
      default: return { shape: 'none', fill: 'filled' };
    }
  }
  var DASH = { DashedLine: '8 4', DottedLine: '2 3' };
  /** The core shapes, as Cytoscape.js names them. */
  var SHAPES = { Rectangle: 'rectangle', RoundedRectangle: 'round-rectangle', Ellipse: 'ellipse', Circle: 'ellipse', Hexagon: 'hexagon', Octagon: 'octagon', Diamond: 'diamond', Parallelogram: 'rhomboid' };

  // ── D3 ───────────────────────────────────────────────────────────────────
  var d3r = (function () {
    var svg = d3.select('#d3'), root = svg.append('g'), defs = svg.append('defs');
    var gEdges = root.append('g'), gLabels = root.append('g'), gNodes = root.append('g');
    var sim = null, positions = new Map(), zoom = d3.zoom().scaleExtent([0.05, 6]).on('zoom', function (e) { root.attr('transform', e.transform); });
    svg.call(zoom).on('dblclick.zoom', null);
    var markers = new Set();
    function marker(decoration, colour, end) {
      if (!decoration || decoration === 'NoDecoration') return null;
      var id = 'm-' + decoration + '-' + end + '-' + colour.replace(/[^a-z0-9]/gi, '');
      if (markers.has(id)) return id;
      markers.add(id);
      var shapes = {
        OpenArrowhead: ['path', 'M1,1 L10,6 L1,11', 'none'], HalfArrowhead: ['path', 'M1,1 L11,6 L3,6', 'none'],
        ClosedArrowhead: ['path', 'M1,1 L11,6 L1,11 z', colour], HollowArrowhead: ['path', 'M1,1 L13,7 L1,13 z', '#fff'],
        FilledDiamond: ['path', 'M1,7 L8,1 L15,7 L8,13 z', colour], HollowDiamond: ['path', 'M1,7 L8,1 L15,7 L8,13 z', '#fff'],
        CircleDot: ['circle', null, colour],
      };
      var s = shapes[decoration]; if (!s) return null;
      var m = defs.append('marker').attr('id', id).attr('viewBox', '0 0 16 14').attr('markerWidth', 14).attr('markerHeight', 12)
        .attr('refX', decoration === 'CircleDot' ? 12 : (decoration.indexOf('Diamond') >= 0 ? 15 : 11)).attr('refY', decoration === 'HollowArrowhead' ? 7 : 6)
        .attr('markerUnits', 'userSpaceOnUse').attr('orient', end === 'source' ? 'auto-start-reverse' : 'auto');
      if (s[0] === 'circle') m.append('circle').attr('cx', 8.5).attr('cy', 6).attr('r', 3.5).attr('fill', colour);
      else m.append('path').attr('d', s[1]).attr('fill', s[2]).attr('stroke', colour).attr('stroke-width', 1.2);
      return id;
    }
    var nodeSel, edgeSel, labelSel, data;
    function radius(n) {
      if (!S.sizeDegree) return 11;
      return 8 + Math.min(16, Math.sqrt(degreeAll.get(n.id) || 0) * 3);
    }
    function forces() {
      if (!sim) return;
      var f = S.forces;
      sim.force('link').distance(f.distance).strength(f.strength);
      sim.force('charge').strength(f.charge);
      sim.force('collide').radius(function (n) { return radius(n) + f.collide / 2; });
      sim.force('x').strength(f.gravity); sim.force('y').strength(f.gravity);
      sim.velocityDecay(f.friction);
      sim.alpha(Math.max(sim.alpha(), 0.3)).restart();
    }
    function draw(view) {
      var W = $('canvas').clientWidth || 800, H = $('canvas').clientHeight || 600;
      data = {
        nodes: view.nodes.map(function (n) { var p = positions.get(n.id) || {}; return Object.assign({}, n, { x: p.x != null ? p.x : W / 2 + (Math.random() - .5) * 200, y: p.y != null ? p.y : H / 2 + (Math.random() - .5) * 200 }); }),
        edges: view.edges.map(function (e) { return Object.assign({}, e); }),
      };
      edgeSel = gEdges.selectAll('line').data(data.edges, function (e) { return e.id; }).join('line')
        .attr('class', 'edge')
        .attr('stroke', function (e) { return e.contains ? '#c4c9cf' : (e.line || '#6b7280'); })
        .attr('stroke-width', function (e) { return e.contains ? 1 : 1.4; })
        .attr('stroke-dasharray', function (e) { return e.contains ? '2 4' : (DASH[e.lineStyle] || null); })
        .attr('marker-end', function (e) { var m = marker(e.contains ? null : (e.targetDecor || 'OpenArrowhead'), e.line || '#6b7280', 'target'); return m ? 'url(#' + m + ')' : null; })
        .attr('marker-start', function (e) { var m = marker(e.contains ? null : e.sourceDecor, e.line || '#6b7280', 'source'); return m ? 'url(#' + m + ')' : null; });
      edgeSel.selectAll('title').data(function (e) { return [e]; }).join('title').text(function (e) { return (e.name ? e.name + ' — ' : '') + e.type; });
      labelSel = gLabels.selectAll('text').data(data.edges.filter(function (e) { return !e.contains; }), function (e) { return e.id; }).join('text')
        .attr('class', 'edge-label').attr('text-anchor', 'middle').text(function (e) { return e.name || e.type; });
      nodeSel = gNodes.selectAll('g.node').data(data.nodes, function (n) { return n.id; }).join(function (enter) {
        var g = enter.append('g').attr('class', 'node');
        g.append('circle');
        g.append('image').attr('width', 16).attr('height', 16).attr('x', -8).attr('y', -8);
        g.append('text').attr('dy', 4);
        return g;
      });
      nodeSel.classed('centre', function (n) { return n.id === S.centre; });
      nodeSel.select('circle').attr('r', radius)
        .attr('fill', function (n) { return n.kind === 'view' ? '#eef3fb' : (n.fill || '#e8eaed'); })
        .attr('stroke', function (n) { return n.kind === 'view' ? '#0b63ce' : (n.line || '#5f6368'); })
        .attr('stroke-width', function (n) { return n.id === S.centre ? 3 : 1.2; });
      nodeSel.select('image').attr('href', function (n) { return n.glyph || null; });
      nodeSel.select('text').attr('x', function (n) { return radius(n) + 4; }).text(function (n) { return n.label; });
      nodeSel.on('click', function (e, n) { e.stopPropagation(); showInfo(n.id); })
        .on('dblclick', function (e, n) { e.stopPropagation(); centreOn(n.id); })
        .on('mouseover', function (e, n) { highlight(n.id); tip(n, e.offsetX, e.offsetY); })
        .on('mousemove', function (e, n) { tip(n, e.offsetX, e.offsetY); })
        .on('mouseout', function () { highlight(null); tip(null); })
        .call(d3.drag()
          .on('start', function (e, n) { if (!e.active) sim.alphaTarget(0.3).restart(); n.fx = n.x; n.fy = n.y; })
          .on('drag', function (e, n) { n.fx = e.x; n.fy = e.y; })
          .on('end', function (e, n) { if (!e.active) sim.alphaTarget(0); n.fx = null; n.fy = null; }));
      labels();
      if (sim) sim.stop();
      var f = S.forces;
      sim = d3.forceSimulation(data.nodes)
        .force('link', d3.forceLink(data.edges).id(function (n) { return n.id; }).distance(f.distance).strength(f.strength))
        .force('charge', d3.forceManyBody().strength(f.charge))
        .force('collide', d3.forceCollide(function (n) { return radius(n) + f.collide / 2; }))
        .force('x', d3.forceX(W / 2).strength(f.gravity))
        .force('y', d3.forceY(H / 2).strength(f.gravity))
        .velocityDecay(f.friction)
        .on('tick', function () {
          // Each line stops at its nodes' edges, so that its end decorations are not under the circles.
          function end(e, at, from) {
            var dx = at.x - from.x, dy = at.y - from.y, d = Math.sqrt(dx * dx + dy * dy) || 1, r = radius(at) + 2;
            return { x: at.x - dx / d * r, y: at.y - dy / d * r };
          }
          edgeSel.each(function (e) {
            var a = end(e, e.source, e.target), b = end(e, e.target, e.source);
            this.setAttribute('x1', a.x); this.setAttribute('y1', a.y); this.setAttribute('x2', b.x); this.setAttribute('y2', b.y);
          });
          labelSel.attr('x', function (e) { return (e.source.x + e.target.x) / 2; }).attr('y', function (e) { return (e.source.y + e.target.y) / 2 - 3; });
          nodeSel.attr('transform', function (n) { positions.set(n.id, { x: n.x, y: n.y }); return 'translate(' + n.x + ',' + n.y + ')'; });
        });
      if (!draw.fitted) { draw.fitted = true; setTimeout(fit, 900); }
    }
    function highlight(id) {
      if (!nodeSel) return;
      if (!id) { nodeSel.classed('dim', false); edgeSel.classed('dim', false).classed('hl', false); labelSel.classed('dim', false); return; }
      var near = new Set([id]);
      data.edges.forEach(function (e) { if (e.source.id === id) near.add(e.target.id); if (e.target.id === id) near.add(e.source.id); });
      nodeSel.classed('dim', function (n) { return !near.has(n.id); });
      edgeSel.classed('dim', function (e) { return e.source.id !== id && e.target.id !== id; }).classed('hl', function (e) { return e.source.id === id || e.target.id === id; });
      labelSel.classed('dim', function (e) { return e.source.id !== id && e.target.id !== id; });
    }
    function labels() {
      if (nodeSel) nodeSel.select('text').style('display', S.nodeLabels ? null : 'none');
      if (labelSel) labelSel.style('display', S.edgeLabels ? null : 'none');
    }
    function fit() {
      if (!data || !data.nodes.length) return;
      var xs = data.nodes.map(function (n) { return n.x; }), ys = data.nodes.map(function (n) { return n.y; });
      var x0 = Math.min.apply(null, xs) - 60, x1 = Math.max.apply(null, xs) + 160, y0 = Math.min.apply(null, ys) - 60, y1 = Math.max.apply(null, ys) + 60;
      var W = $('canvas').clientWidth, H = $('canvas').clientHeight;
      var k = Math.min(2, 0.95 * Math.min(W / (x1 - x0), H / (y1 - y0)));
      svg.transition().duration(400).call(zoom.transform, d3.zoomIdentity.translate(W / 2 - k * (x0 + x1) / 2, H / 2 - k * (y0 + y1) / 2).scale(k));
    }
    svg.on('click', function () { $('info').classList.add('hidden'); });
    return {
      draw: draw, labels: labels, fit: fit, forces: forces,
      reheat: function () { if (sim) sim.alpha(1).restart(); },
      freeze: function () { if (sim) sim.stop(); },
      show: function (on) { $('d3').classList.toggle('hidden', !on); if (!on && sim) sim.stop(); },
      exportImage: function () {
        var clone = svg.node().cloneNode(true);
        clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        clone.setAttribute('width', $('canvas').clientWidth); clone.setAttribute('height', $('canvas').clientHeight);
        download(new Blob([clone.outerHTML], { type: 'image/svg+xml' }), 'graph.svg');
      },
    };
  })();

  // ── Cytoscape ────────────────────────────────────────────────────────────
  var cyr = (function () {
    var cy = null;
    function style() {
      var k = S.cyScale;
      return [
        // Each node in its type's notation, from what meta.linked.archi publishes: the notation's picture of the type —
        // its whole figure — where it has one; else its shape in its colours, with its glyph as a badge, or the glyph
        // alone for a type drawn as its icon.
        { selector: 'node', style: {
          shape: function (n) { return SHAPES[n.data('shape')] || 'rectangle'; },
          width: function (n) { return (n.data('w') || 120) * k; }, height: function (n) { return (n.data('h') || 55) * k; },
          'background-color': function (n) { return n.data('fill') || '#ffffff'; }, 'border-width': 1, 'border-color': function (n) { return n.data('line') || '#5f6368'; },
          'background-image-crossorigin': 'null', 'background-clip': 'none', 'background-image-containment': 'over',
          label: function (n) { return S.nodeLabels ? n.data('label') : ''; }, 'font-size': 11, 'font-family': 'system-ui, -apple-system, "Segoe UI", sans-serif',
          color: '#202124', 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'wrap',
          'text-max-width': function (n) { return ((n.data('w') || 120) * k - 26) + 'px'; },
        } },
        { selector: 'node[glyph]', style: { 'background-image': 'data(glyph)', 'background-width': 16 * k + 2, 'background-height': 16 * k + 2,
          'background-position-x': '94%', 'background-position-y': '10%' } },
        { selector: 'node[mode = "IconCentric"][glyph]', style: { 'background-opacity': 0, 'border-width': 0, 'background-width': '100%', 'background-height': '75%',
          'background-position-x': '50%', 'background-position-y': '0%', 'text-valign': 'bottom', 'text-margin-y': -4 } },
        { selector: 'node[picture]', style: { shape: 'rectangle', 'background-image': 'data(picture)', 'background-fit': 'contain', 'background-width': 'auto',
          'background-height': 'auto', 'background-position-x': '50%', 'background-position-y': '50%', 'background-opacity': 0, 'border-width': 0 } },
        { selector: 'node[kind = "view"]', style: { shape: 'round-rectangle', 'background-opacity': 1, 'background-color': '#eef3fb', 'border-width': 1.5, 'border-color': '#0b63ce', 'background-image': 'none' } },
        { selector: 'node.centre', style: { 'overlay-color': '#0b63ce', 'overlay-opacity': 0.14, 'overlay-padding': 6, 'font-weight': 'bold' } },
        { selector: 'node.dim, edge.dim', style: { opacity: 0.15 } },
        { selector: 'edge', style: {
          width: 1.4, 'curve-style': 'bezier', 'line-color': function (e) { return e.data('line') || '#5f6368'; },
          'line-style': function (e) { return e.data('lineStyle') === 'DashedLine' ? 'dashed' : e.data('lineStyle') === 'DottedLine' ? 'dotted' : 'solid'; },
          'target-arrow-shape': function (e) { return arrow(e.data('contains') ? null : (e.data('targetDecor') || 'OpenArrowhead')).shape; },
          'target-arrow-fill': function (e) { return arrow(e.data('targetDecor') || 'OpenArrowhead').fill; },
          'source-arrow-shape': function (e) { return arrow(e.data('sourceDecor')).shape; },
          'source-arrow-fill': function (e) { return arrow(e.data('sourceDecor')).fill; },
          'target-arrow-color': function (e) { return e.data('line') || '#5f6368'; }, 'source-arrow-color': function (e) { return e.data('line') || '#5f6368'; },
          'arrow-scale': 1.1, label: function (e) { return S.edgeLabels && !e.data('contains') ? (e.data('name') || e.data('type')) : ''; },
          'font-size': 9, color: '#5f6368', 'text-rotation': 'autorotate', 'text-background-color': '#ffffff', 'text-background-opacity': 0.85, 'text-background-padding': '1px',
        } },
        { selector: 'edge[?contains]', style: { 'line-color': '#c4c9cf', 'line-style': 'dotted', width: 1 } },
        { selector: 'edge.hl', style: { width: 3, 'line-color': '#0b63ce', 'target-arrow-color': '#0b63ce', 'source-arrow-color': '#0b63ce' } },
      ];
    }
    function layoutOptions() {
      var sp = S.cySpacing;
      switch (S.cyLayout) {
        case 'concentric': return { name: 'concentric', concentric: function (n) { return n.id() === S.centre ? 100 : n.degree(); }, levelWidth: function () { return 2; }, spacingFactor: sp, animate: false, padding: 30 };
        case 'breadthfirst': return { name: 'breadthfirst', roots: S.centre && cy.getElementById(S.centre).nonempty() ? [S.centre] : undefined, directed: false, spacingFactor: sp, animate: false, padding: 30 };
        case 'circle': return { name: 'circle', spacingFactor: sp, animate: false, padding: 30 };
        case 'grid': return { name: 'grid', spacingFactor: sp, animate: false, padding: 30 };
        default: return { name: 'cose', animate: false, padding: 30, nodeRepulsion: function () { return 9000 * sp * sp; }, idealEdgeLength: function () { return 90 * sp; }, nodeOverlap: 20, gravity: 0.25, numIter: 1500 };
      }
    }
    function ensure() {
      if (cy) return cy;
      cy = cytoscape({ container: $('cy'), style: style(), wheelSensitivity: 0.3, minZoom: 0.05, maxZoom: 4, boxSelectionEnabled: false });
      cy.on('tap', 'node', function (e) { showInfo(e.target.id()); });
      cy.on('dbltap', 'node', function (e) { centreOn(e.target.id()); });
      cy.on('tap', 'edge', function (e) { var h = e.target.data('href'); if (h) window.location.href = h; });
      cy.on('mouseover', 'node', function (e) {
        var n = e.target, near = n.closedNeighborhood();
        cy.elements().not(near).addClass('dim'); n.connectedEdges().addClass('hl');
        var p = e.renderedPosition || n.renderedPosition(); tip(byId.get(n.id()), p.x, p.y);
      });
      cy.on('mouseout', 'node', function () { cy.elements().removeClass('dim hl'); tip(null); });
      cy.on('tap', function (e) { if (e.target === cy) $('info').classList.add('hidden'); });
      return cy;
    }
    function draw(view) {
      ensure();
      cy.batch(function () {
        cy.elements().remove();
        cy.add(view.nodes.map(function (n) {
          var view = n.kind === 'view';
          return { group: 'nodes', classes: n.id === S.centre ? 'centre' : '', data: { id: n.id, label: n.label, kind: n.kind,
            picture: view ? undefined : (n.picture || undefined), glyph: view ? undefined : (n.glyph || undefined), mode: n.mode,
            shape: n.shape, fill: n.fill, line: n.line, w: view ? 120 : n.w, h: view ? 50 : n.h } };
        }));
        cy.add(view.edges.map(function (e) { return { group: 'edges', data: { id: e.id, source: e.source, target: e.target, type: e.type, name: e.name, contains: e.contains, lineStyle: e.lineStyle, sourceDecor: e.sourceDecor, targetDecor: e.targetDecor, line: e.line, href: e.href } }; }));
      });
      layout();
    }
    function layout() { if (!cy || !cy.nodes().length) return; cy.layout(layoutOptions()).run(); cy.fit(undefined, 30); if (cy.zoom() > 1.4) { cy.zoom(1.4); cy.center(); } save(); }
    return {
      draw: draw, layout: layout,
      restyle: function () { if (cy) cy.style(style()); save(); },
      labels: function () { if (cy) cy.style(style()); },
      fit: function () { if (cy) cy.fit(undefined, 30); },
      show: function (on) { $('cy').classList.toggle('hidden', !on); if (on && cy) cy.resize(); },
      exportImage: function () {
        if (!cy) return;
        try { download(cy.png({ full: true, scale: 2, bg: '#ffffff', output: 'blob' }), 'graph.png'); }
        catch (e) { alert('The browser does not let the drawing be saved from a page opened from disk. Serve the site over http to export it.'); }
      },
    };
  })();

  function download(blob, name) {
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = name; a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
  }
  function current() { return S.renderer === 'cy' ? cyr : d3r; }
  function switchRenderer(r) {
    S.renderer = r;
    $('d3-controls').classList.toggle('hidden', r !== 'd3');
    $('cy-controls').classList.toggle('hidden', r !== 'cy');
    $('export').textContent = r === 'cy' ? 'Export PNG' : 'Export SVG';
    d3r.show(r === 'd3'); cyr.show(r === 'cy');
    update();
  }
  function update() {
    save();
    var view = visible();
    var c = byId.get(S.centre), vc = viewCentre();
    $('centre-label').textContent = vc ? 'View ' + vc.label + ': the elements it shows and the relationships between them'
      : S.scope === 'ego' && c ? 'Centred on ' + c.label + ' · depth ' + S.depth : 'Whole graph';
    $('depth').disabled = !!vc;
    $('depth').title = vc ? 'A view in the middle shows what it shows; the depth is for an element in the middle' : '';
    $('stats').textContent = view.nodes.length + ' nodes · ' + view.edges.length + ' edges';
    if (view.nodes.length > 1500 && S.renderer === 'd3') $('stats').textContent += ' — large: try Neighbourhood';
    hint(view, c, vc);
    current().draw(view);
  }

  /** When there is next to nothing to draw, why, and what would show more. */
  function hint(view, c, vc) {
    var box = $('hint');
    if (view.edges.length > 0 || view.nodes.length > 1 || (vc && view.nodes.length > 0)) { box.classList.add('hidden'); return; }
    var why = [], fixes = [];
    if (vc) {
      var all = G.edges.filter(function (e) { return e.contains && e.source === vc.id; }).length;
      why.push(all === 0 ? 'The view ' + esc(vc.label) + ' shows no elements: only figures, such as groups and notes.'
        : 'The view ' + esc(vc.label) + ' shows ' + all + ' element' + (all === 1 ? '' : 's') + ', hidden by the filters.');
    }
    else if (S.scope === 'ego' && c) why.push(esc(c.label) + ' has no relationship to show here.');
    else why.push('Nothing to show with these filters.');
    if (S.relsOff.size) { why.push(S.relsOff.size + ' relationship type' + (S.relsOff.size === 1 ? ' is' : 's are') + ' hidden.'); fixes.push('<button class="btn" data-fix="rels">Show every relationship type</button>'); }
    if (S.typesOff.size) { why.push(S.typesOff.size + ' element type' + (S.typesOff.size === 1 ? ' is' : 's are') + ' hidden.'); fixes.push('<button class="btn" data-fix="types">Show every element type</button>'); }
    if (c && c.kind === 'view' && !S.showViews && !vc) fixes.push('<button class="btn" data-fix="views">Show views, linked to their elements</button>');
    if (S.scope === 'ego') fixes.push('<button class="btn" data-fix="all">Whole graph</button>');
    box.innerHTML = why.join(' ') + (fixes.length ? '<div class="fixes">' + fixes.join('') + '</div>' : '');
    box.classList.remove('hidden');
  }
  $('hint').addEventListener('click', function (e) {
    var b = e.target.closest('[data-fix]');
    if (!b) return;
    var fix = b.getAttribute('data-fix');
    if (fix === 'rels') setRels(true);
    else if (fix === 'types') setTypes(true);
    else if (fix === 'views') { S.showViews = true; $('show-views').checked = true; update(); }
    else if (fix === 'all') document.querySelector('#scope [data-s="all"]').click();
  });
  $('fit').addEventListener('click', function () { current().fit(); });
  $('export').addEventListener('click', function () { current().exportImage(); });
  window.addEventListener('resize', function () { if (S.renderer === 'cy') cyr.fit(); });
  switchRenderer(S.renderer);
})();
