/* rdf2docs — the model tree in the left panel, built as la-gram's Model Explorer builds it (ui/panels/model-explorer.ts,
 * and the kernel's model-manager/folders.ts for the folders):
 *
 *   ▾ Model
 *     ▾ Views                 every view; the one this page shows bold
 *     ▾ Elements              grouped by type, labelled by the metamodel
 *       ▾ Business Actor (3)
 *           Customer   ●      ● = in the view this page shows
 *     ▸ Relationships         grouped by type: "Source → Target"
 *     ▸ Unclassified          types no loaded metamodel defines
 *
 * With the model's own folders (Folders | Types), each group has its folders first, each starting folded, then
 * what none of them holds, by type. Each row shows its type's icon from the metamodel's notation, as
 * meta.linked.archi publishes it; a row opens its page. Also builds the header's Models and Folders menus.
 * Reads window.LA_TREE. */
(function () {
  'use strict';
  var T = window.LA_TREE;
  if (!T) return;
  var body = document.body, ROOT = body.getAttribute('data-root') || '', CURRENT = body.getAttribute('data-iri') || '';
  var KINDS = ['view', 'element', 'relationship'];
  var KIND_FOLDER_NAMES = { view: 'Views', element: 'Elements', relationship: 'Relationships' };
  var GROUPING_KEY = 'rdf2docs-tree-grouping', STATE_KEY = 'rdf2docs-tree-state', HIDDEN_KEY = 'rdf2docs-tree-hidden';
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function get(store, key) { try { return window[store].getItem(key); } catch (e) { return null; } }
  function set(store, key, value) { try { window[store].setItem(key, value); } catch (e) { /* not remembered */ } }
  function localName(iri) { return (iri || '').replace(/[#/]+$/, '').split(/[#/]/).pop(); }
  function byLabel(a, b) { return a.label.localeCompare(b.label) || String(a.id).localeCompare(String(b.id)); }

  // ── The header's menus ─────────────────────────────────────────────────
  function buildMenus() {
    var host = document.getElementById('site-menus');
    if (!host) return;
    var models = T.models.filter(function (m) { return m.href; });
    var html = '';
    if (models.length) {
      html += '<div class="menu-drop"><button class="menu-btn" type="button">Models ▾</button><div class="menu-panel">' +
        models.map(function (m) { return '<a href="' + esc(ROOT + m.href) + '"><span class="ti mark">◆</span>' + esc(m.label) + '</a>'; }).join('') + '</div></div>';
    }
    var withFolders = T.models.filter(function (m) { return m.folders.length; });
    if (withFolders.length) {
      html += '<div class="menu-drop"><button class="menu-btn" type="button">Folders ▾</button><div class="menu-panel wide">' +
        '<input type="search" class="menu-filter" placeholder="Filter folders…"><div class="menu-tree">' +
        withFolders.map(function (m) {
          var byParent = {}, known = {};
          m.folders.forEach(function (f) { known[f[0]] = true; });
          m.folders.forEach(function (f) { var p = known[f[2]] ? f[2] : ''; (byParent[p] = byParent[p] || []).push(f); });
          function list(parent) {
            var items = (byParent[parent] || []).slice().sort(function (a, b) { return a[1].localeCompare(b[1]); });
            if (!items.length) return '';
            return '<ul>' + items.map(function (f) {
              return '<li><a href="' + esc(ROOT + 'index.html#' + f[4]) + '" title="' + esc(f[1]) + '">📁 ' + esc(f[1]) + '</a>' + list(f[0]) + '</li>';
            }).join('') + '</ul>';
          }
          return (withFolders.length > 1 ? '<div class="menu-head">' + esc(m.label) + '</div>' : '') + list('');
        }).join('') + '</div></div></div>';
    }
    host.innerHTML = html;
    host.querySelectorAll('.menu-drop').forEach(function (drop) {
      drop.querySelector('.menu-btn').addEventListener('click', function (e) {
        e.stopPropagation();
        var open = !drop.classList.contains('open');
        host.querySelectorAll('.menu-drop.open').forEach(function (d) { d.classList.remove('open'); });
        drop.classList.toggle('open', open);
        var f = drop.querySelector('.menu-filter');
        if (open && f) f.focus();
      });
      var filter = drop.querySelector('.menu-filter');
      if (filter) filter.addEventListener('input', function () {
        var q = filter.value.trim().toLowerCase();
        drop.querySelectorAll('.menu-tree li').forEach(function (li) { li.classList.toggle('hidden', !!q && li.textContent.toLowerCase().indexOf(q) < 0); });
      });
    });
    document.addEventListener('click', function (e) {
      if (!e.target.closest('.menu-drop')) host.querySelectorAll('.menu-drop.open').forEach(function (d) { d.classList.remove('open'); });
    });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') host.querySelectorAll('.menu-drop.open').forEach(function (d) { d.classList.remove('open'); }); });
  }
  buildMenus();

  var panel = document.getElementById('model-tree');
  if (!panel) return;

  // ── The tree, as la-gram builds it ─────────────────────────────────────
  function typeLabel(key) { return key && T.types[key] ? T.types[key][0] : 'Untyped'; }
  function conceptNode(c, kind) { return { id: c[0], iri: c[0], kind: kind, label: c[1], href: c[2], typeKey: c[3], children: [] }; }
  function viewNode(v) { return { id: v[0], iri: v[0], kind: 'view', label: v[1], href: v[2], typeKey: v[3], detail: v[4], active: v[0] === CURRENT, children: [] }; }

  function groupByType(items, kind, prefix) {
    var groups = new Map();
    items.forEach(function (c) {
      var key = c[3] || '';
      var g = groups.get(key);
      if (!g) { g = { id: prefix + ':' + key, kind: 'folder', label: typeLabel(c[3]), typeKey: c[3], children: [] }; groups.set(key, g); }
      g.children.push(conceptNode(c, kind));
    });
    var list = Array.from(groups.values()).sort(byLabel);
    list.forEach(function (g) { g.children.sort(byLabel); g.detail = String(g.children.length); });
    return list;
  }

  /** Views, Elements, Relationships — by type — and Unclassified, when there is any; a group's folders first. */
  function typeGroups(stated, folders) {
    function group(kind, label, own, count) {
      return { id: 'folder:' + (kind === 'view' ? 'views' : kind === 'element' ? 'elements' : 'relationships'), kind: 'folder', folderKind: kind,
        label: label, detail: String(count + (folders ? folders.counts[kind] : 0)), children: (folders ? folders.nodes[kind] : []).concat(own) };
    }
    var groups = [
      group('view', 'Views', stated.views.map(viewNode).sort(byLabel), stated.views.length),
      group('element', 'Elements', groupByType(stated.elements, 'element', 'elements'), stated.elements.length),
      group('relationship', 'Relationships', groupByType(stated.relationships, 'relationship', 'relationships'), stated.relationships.length),
    ];
    if (stated.unclassified.length) {
      groups.push({ id: 'folder:unclassified', kind: 'folder', label: 'Unclassified', detail: String(stated.unclassified.length),
        children: groupByType(stated.unclassified, 'unclassified', 'unclassified') });
    }
    return groups;
  }

  /** A converter's folder for the model itself: the one folder at the top, named as the model, holding only folders. */
  function modelFolderOf(m, folders) {
    var known = new Set(folders.map(function (f) { return f.iri; }));
    var top = folders.filter(function (f) { return !f.parentIri || !known.has(f.parentIri); });
    if (top.length !== 1) return undefined;
    var folder = top[0];
    var names = [m.label, m.iri ? localName(m.iri) : null].filter(Boolean).map(function (n) { return n.toLowerCase(); });
    if (names.indexOf(folder.label.toLowerCase()) < 0) return undefined;
    if (!folders.some(function (f) { return f.parentIri === folder.iri; })) return undefined;
    var listed = new Set([].concat(m.views, m.elements, m.relationships, m.unclassified).map(function (c) { return c[0]; }));
    return folder.members.some(function (x) { return listed.has(x); }) ? undefined : folder;
  }

  /** The model's folders under the tree's groups: a folder is under the groups of what it holds, an empty one its parent's. */
  function folderLayout(m) {
    var folders = m.folders.map(function (f) { return { iri: f[0], label: f[1], parentIri: f[2], members: f[3], sec: f[4] }; });
    var byIri = new Map(folders.map(function (f) { return [f.iri, f]; }));
    var inside = new Map(), topLevel = [];
    folders.forEach(function (f) {
      if (f.parentIri && byIri.has(f.parentIri)) { (inside.get(f.parentIri) || inside.set(f.parentIri, []).get(f.parentIri)).push(f); }
      else topLevel.push(f);
    });
    var kindOf = new Map();
    m.views.forEach(function (c) { kindOf.set(c[0], 'view'); });
    m.elements.forEach(function (c) { kindOf.set(c[0], 'element'); });
    m.unclassified.forEach(function (c) { kindOf.set(c[0], 'element'); });
    m.relationships.forEach(function (c) { kindOf.set(c[0], 'relationship'); });
    var modelFolder = modelFolderOf(m, folders);
    var level = (modelFolder ? (inside.get(modelFolder.iri) || []) : topLevel).slice().sort(function (a, b) { return a.iri.localeCompare(b.iri); });
    var roots = {};
    KINDS.forEach(function (kind) {
      var root = level.filter(function (f) { return f.label.trim().toLowerCase() === KIND_FOLDER_NAMES[kind].toLowerCase(); })[0];
      if (root) roots[kind] = root;
    });
    var rootIris = new Set(Object.keys(roots).map(function (k) { return roots[k].iri; }));
    var held = new Map();
    function holds(f, trail) {
      if (held.has(f.iri)) return held.get(f.iri);
      var out = new Set();
      f.members.forEach(function (x) { var k = kindOf.get(x); if (k) out.add(k); });
      trail.add(f.iri);
      (inside.get(f.iri) || []).forEach(function (s) { if (!trail.has(s.iri)) holds(s, trail).forEach(function (k) { out.add(k); }); });
      held.set(f.iri, out);
      return out;
    }
    var kinds = new Map();
    function place(f, around, fixed) {
      if (kinds.has(f.iri) || rootIris.has(f.iri)) return;
      var own = holds(f, new Set());
      var shown = fixed || own.size === 0 ? around : own;
      kinds.set(f.iri, shown);
      (inside.get(f.iri) || []).forEach(function (s) { place(s, shown, fixed); });
    }
    KINDS.forEach(function (kind) { var root = roots[kind]; if (root) (inside.get(root.iri) || []).forEach(function (s) { place(s, new Set([kind]), true); }); });
    level.forEach(function (f) { place(f, new Set(['element']), false); });
    var top = {};
    KINDS.forEach(function (kind) {
      var root = roots[kind];
      top[kind] = (root ? (inside.get(root.iri) || []) : []).concat(level.filter(function (f) { return !rootIris.has(f.iri); }))
        .filter(function (f) { var k = kinds.get(f.iri); return k && k.has(kind); });
    });
    return { kinds: kinds, top: top, inside: inside, kindOf: kindOf };
  }

  /** The groups with the model's own folders in them: each folder starting folded, its folders then its concepts. */
  function folderGroups(m) {
    var layout = folderLayout(m);
    var rows = new Map();
    m.views.forEach(function (v) { rows.set(v[0], function () { return viewNode(v); }); });
    function concept(kind) { return function (c) { rows.set(c[0], function () { return conceptNode(c, kind); }); }; }
    m.elements.forEach(concept('element'));
    m.relationships.forEach(concept('relationship'));
    m.unclassified.forEach(concept('unclassified'));
    var filed = new Set();
    function build(f, kind, trail) {
      var within = new Set(trail); within.add(f.iri);
      var subs = (layout.inside.get(f.iri) || []).filter(function (s) { var k = layout.kinds.get(s.iri); return k && k.has(kind) && !within.has(s.iri); })
        .sort(function (a, b) { return a.label.localeCompare(b.label) || a.iri.localeCompare(b.iri); })
        .map(function (s) { return build(s, kind, within); });
      var members = f.members.filter(function (x) { return layout.kindOf.get(x) === kind && rows.has(x); })
        .map(function (x) { filed.add(x); return rows.get(x)(); }).sort(byLabel);
      var count = members.length + subs.reduce(function (n, b) { return n + b.count; }, 0);
      return { count: count, node: { id: 'folder-iri:' + kind + ':' + f.iri, folderIri: f.iri, folderKind: kind, kind: 'folder', label: f.label,
        detail: String(count), folded: true, sec: f.sec, children: subs.map(function (b) { return b.node; }).concat(members) } };
    }
    var nodes = {}, counts = {};
    KINDS.forEach(function (kind) {
      var built = layout.top[kind].slice().sort(function (a, b) { return a.label.localeCompare(b.label) || a.iri.localeCompare(b.iri); })
        .map(function (f) { return build(f, kind, new Set()); });
      nodes[kind] = built.map(function (b) { return b.node; });
      counts[kind] = built.reduce(function (n, b) { return n + b.count; }, 0);
    });
    function unfiled(list) { return list.filter(function (c) { return !filed.has(c[0]); }); }
    return typeGroups({ views: unfiled(m.views), elements: unfiled(m.elements), relationships: unfiled(m.relationships), unclassified: unfiled(m.unclassified) },
      { nodes: nodes, counts: counts });
  }

  function hasFolders() { return T.models.some(function (m) { return m.folders.length > 0; }); }

  function buildTree() {
    var folders = grouping === 'folders' && hasFolders();
    return T.models.map(function (m) {
      return { id: m.iri || 'model:other', iri: m.iri, kind: 'model', label: m.label, href: m.href,
        children: folders && m.folders.length ? folderGroups(m) : typeGroups(m) };
    });
  }

  /** Rows whose label or detail matches, with their ancestors; a folder that matches keeps its whole subtree. */
  function filterTree(nodes, query) {
    var q = query.trim().toLowerCase();
    if (!q) return nodes;
    function visit(node) {
      var self = node.label.toLowerCase().indexOf(q) >= 0 || (node.detail || '').toLowerCase().indexOf(q) >= 0;
      if (self && node.kind === 'folder') return node;
      var children = node.children.map(visit).filter(Boolean);
      if (self || children.length) return Object.assign({}, node, { children: children });
      return null;
    }
    return nodes.map(visit).filter(Boolean);
  }

  // ── State ──────────────────────────────────────────────────────────────
  var grouping = get('localStorage', GROUPING_KEY) === 'types' ? 'types' : 'folders';
  var saved = null;
  try { saved = JSON.parse(get('sessionStorage', STATE_KEY) || 'null'); } catch (e) { saved = null; }
  // As la-gram starts: Relationships folded, the rest open, each folder folded.
  var collapsed = new Set(saved ? saved.collapsed : ['folder:relationships']);
  var expanded = new Set(saved ? saved.expanded : []);
  var query = '';
  var placed = new Set();
  function saveState() { set('sessionStorage', STATE_KEY, JSON.stringify({ collapsed: Array.from(collapsed), expanded: Array.from(expanded) })); }
  function isFolded(node) {
    if (query) return false;
    return node.folded ? !expanded.has(node.id) : collapsed.has(node.id);
  }
  function toggle(node) {
    if (node.folded) { if (expanded.has(node.id)) expanded.delete(node.id); else expanded.add(node.id); }
    else { if (collapsed.has(node.id)) collapsed.delete(node.id); else collapsed.add(node.id); }
    saveState();
    render();
  }

  /** Unfold the way to the row of this page, so that it shows. */
  function reveal(nodes) {
    function visit(node, path) {
      if (node.iri && node.iri === CURRENT && node.kind !== 'model') {
        path.forEach(function (p) { if (p.folded) expanded.add(p.id); else collapsed.delete(p.id); });
        return true;
      }
      for (var i = 0; i < node.children.length; i++) if (visit(node.children[i], path.concat([node]))) return true;
      return false;
    }
    nodes.forEach(function (n) { visit(n, []); });
  }

  // ── Rendering ──────────────────────────────────────────────────────────
  var byId = new Map();
  function iconOf(node) {
    if (node.kind === 'model') return '<span class="ti mark">◆</span>';
    if (node.typeKey && T.types[node.typeKey]) return T.types[node.typeKey][1];
    if (node.kind === 'view') return '<span class="ti mark">' + (node.detail === 'catalog' ? '☰' : node.detail === 'matrix' ? '▤' : '▦') + '</span>';
    if (node.kind === 'element' || node.kind === 'unclassified') return '<span class="ti mark">' + (node.kind === 'unclassified' ? '?' : '■') + '</span>';
    if (node.kind === 'relationship') return '<span class="ti mark">→</span>';
    return '<span class="ti none"></span>';
  }
  function renderNode(node) {
    byId.set(node.id, node);
    var folded = isFolded(node), has = node.children.length > 0;
    var selected = node.iri && node.iri === CURRENT;
    var label = node.href ? '<a class="label" href="' + esc(ROOT + node.href) + '">' + esc(node.label) + '</a>' : '<span class="label">' + esc(node.label) + '</span>';
    if (node.sec && !node.href) label = '<span class="label">' + esc(node.label) + '</span>';
    return '<li><div class="row ' + node.kind + (selected ? ' selected' : '') + (node.active ? ' active' : '') + '" data-id="' + esc(node.id) + '" title="' + esc(node.iri || node.label) + '">' +
      '<span class="toggle">' + (has ? (folded ? '▶' : '▼') : '') + '</span>' + iconOf(node) + label +
      (node.iri && placed.has(node.iri) ? '<span class="placed" title="In this view"></span>' : '') +
      (node.detail && node.kind !== 'view' ? '<span class="detail">' + esc(node.detail) + '</span>' : '') + '</div>' +
      (has && !folded ? '<ul>' + node.children.map(renderNode).join('') + '</ul>' : '') + '</li>';
  }
  var tree = buildTree();
  var revealed = false;
  function render() {
    byId.clear();
    var shown = filterTree(tree, query);
    var folders = hasFolders();
    var g = folders ? grouping : 'types';
    panel.querySelector('.grouping').innerHTML =
      '<button type="button" data-grouping="folders" aria-pressed="' + (g === 'folders') + '"' + (folders ? ' title="In the model\'s own folders"' : ' disabled title="This model has no folders"') + '>Folders</button>' +
      '<button type="button" data-grouping="types" aria-pressed="' + (g === 'types') + '" title="By kind and type">Types</button>';
    panel.querySelector('.tree-scroll').innerHTML = shown.length ? '<ul class="tree">' + shown.map(renderNode).join('') + '</ul>'
      : '<div class="empty">' + (query ? 'Nothing matches.' : 'No model.') + '</div>';
    if (!revealed) {
      revealed = true;
      var row = panel.querySelector('.row.selected');
      if (row) row.scrollIntoView({ block: 'center' });
    }
  }

  panel.innerHTML = '<div class="explorer-header"><span>Model</span><span class="grouping" role="group" aria-label="Group the model"></span>' +
    '<button type="button" class="tree-hide" title="Hide the tree">«</button></div>' +
    '<input class="filter-input" type="search" placeholder="Filter by name or type…"><div class="tree-scroll"></div>';
  panel.addEventListener('click', function (e) {
    var b = e.target.closest('[data-grouping]');
    if (b && !b.disabled) {
      grouping = b.getAttribute('data-grouping');
      set('localStorage', GROUPING_KEY, grouping);
      tree = buildTree(); reveal(tree); render();
      return;
    }
    if (e.target.closest('.tree-hide')) { setHidden(true); return; }
    var row = e.target.closest('.row');
    if (!row) return;
    var node = byId.get(row.getAttribute('data-id'));
    if (!node) return;
    // A folder, a group, or the toggle: fold or unfold; a concept's name: its page.
    if (e.target.closest('.toggle') || (!e.target.closest('a') && (node.kind === 'folder' || node.children.length))) {
      if (node.children.length) toggle(node);
      if (node.sec && !e.target.closest('.toggle') && document.getElementById(node.sec) && window.selectFolder) window.selectFolder(node.sec);
    } else if (!e.target.closest('a') && node.href) {
      window.location.href = ROOT + node.href;
    }
  });
  panel.querySelector('.filter-input').addEventListener('input', function () { query = this.value; render(); });

  function setHidden(hidden) {
    body.classList.toggle('tree-hidden', hidden);
    set('localStorage', HIDDEN_KEY, hidden ? '1' : '');
  }
  var opener = document.getElementById('tree-toggle');
  if (opener) opener.addEventListener('click', function () { setHidden(!body.classList.contains('tree-hidden')); });
  if (get('localStorage', HIDDEN_KEY) === '1') body.classList.add('tree-hidden');

  // On a view's page, the concepts it draws are marked, as la-gram marks those in the open view.
  function collectPlaced() {
    placed = new Set();
    var svg = document.querySelector('.diagram svg.la-diagram:not(.hidden)');
    if (!svg) return;
    svg.querySelectorAll('[data-element-iri],[data-relationship-iri]').forEach(function (el) {
      placed.add(el.getAttribute('data-element-iri') || el.getAttribute('data-relationship-iri'));
    });
  }
  collectPlaced();
  reveal(tree);
  saveState();
  render();
  window.rdf2docsTree = { refreshPlaced: function () { collectPlaced(); render(); } };
})();
