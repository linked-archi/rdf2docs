# Changelog

## Unreleased

### Added: releases, a container image and CI templates

- **Licence.** Apache License 2.0 (`LICENSE`); `NOTICE` lists what the jar and the sites carry: D3, Cytoscape.js,
  Oxigraph, and the Linked.Archi notation under CC BY 4.0.
- **The container image** `ghcr.io/linked-archi/rdf2docs`: Java 25's runtime and the jar, run as `rdf2docs`, for
  `linux/amd64` and `linux/arm64`, with no entrypoint of its own so that a CI job's script runs in it.
  `RDF2DOCS_JAVA_OPTS` passes options to the JVM.
- **GitHub Actions** (`.github/workflows/`): `ci.yml` tests and builds every push and pull request, image included;
  `release.yml`, on a tag `v<version>`, builds the jar at that version, attaches it to a GitHub release, pushes
  the image tagged with the version, its minor version and `latest`, and publishes the demo site (`demo.yml`), the
  documentation of every sample model on GitHub Pages.
- **CI templates** (`ci/`): a GitHub Actions workflow and a GitLab CI job that publish the documentation of a model
  kept in a repository on GitHub or GitLab Pages at every push, with its AI context beside it.
- **`--version`** prints the version the build wrote into the jar's manifest, `gradle.properties`' `version` or
  the release tag's.
- **The sample models** are [linked-archi/sample-models](https://github.com/linked-archi/sample-models)' — an
  ArchiMate 4 shop drawn in la-gram, an ArchiMate 3.2 payment platform, a BPMN order domain and a UML shop.
  `playground/run-sample.sh` documents them, downloading each on first use (or reading a local checkout,
  `SAMPLE_MODELS`); it replaces the three scripts that read models from other repositories' working trees.

### Changed: a type's icon is what meta.linked.archi publishes

- **As published.** A type's icon in the tree, the lists, the legends and the details was its glyph on a tile drawn
  in the type's colours. It is now what meta.linked.archi publishes for the type, unchanged: its picture
  (`arch:prefVisNotation`), the whole figure, drawn 32 × 16; else its glyph (`arch-vis:iconSymbol`) alone; else
  none, an empty place so that names line up. A view keeps the tree's mark, ▦.
- **A relationship is its whole line.** A relationship type without a picture — ArchiMate's and BPMN's have none — is drawn from
  its notation: a short diagonal in its line style and colour with both ends' decorations, a 24 × 24 SVG inline as a
  `data:` URI. *Serving* is a line with an open arrowhead, *Aggregation* one with a hollow diamond at its source,
  *Realization* a dotted one with a hollow arrowhead, *Association* a plain line.
- **What is not served is not shown.** A run asks meta.linked.archi once for each picture and glyph (a `HEAD`
  request) and passes over one it answers 404 for — ArchiMate 3.2's relationship pictures, under
  `https://meta.linked.archi/archimate/v3/img/` — for the next choice, in the icons and in the diagrams' badges.
  When meta.linked.archi cannot be reached, they are linked unchecked.
- **The notation pack is refreshed** from linked-archi-meta, whose ArchiMate 3.x ontologies no longer name
  unpublished pictures: an element type's is the one under `archimate3/icons/` its notation gives, and a
  relationship type has none, so it is drawn as its line, as ArchiMate 4's is.

*NotationTest*.

### Fixed: the graph showed one node

- **Relationships by their ends.** The graph's edges, an element's *Relationships* tab, a relationship's *Endpoints*
  tab and the SPARQL examples asked for `arch:QualifiedRelationship` and `arch:Element`, which la-gram does not state:
  it states the language's type alone (`am4:Serving`). A model saved from la-gram had no edges, so a node stood
  alone. A relationship is now whatever has an `arch:source` and an `arch:target`, as its page is classified.
- **The graph config hides element types only.** `excludedNodeTypes` was read for relationship types too, which in
  the graph are edges: the playground's config, listing ArchiMate's relationship types, hid every edge of
  ArchiSurance. Every relationship type now starts shown; the playground's config hides *Grouping* and *Location*.
  What a reader's browser remembered under the old rule is not carried over.
- **A view in the middle.** With views not shown as nodes, a view in the middle stood alone; it now stands for
  what it shows — its elements and the relationships between them. The option reads *Show views, linked to their
  elements*, and view types are a group of their own in the type filter. When there is next to nothing to draw, a
  note says why and offers what would show more.

*NotationTest*.

### Added: the model tree, the header's menus, the notation's icons from meta.linked.archi, zoom and pan

- **The model tree** is on the left of the index and of every resource's page, built as la-gram's Model Explorer
  builds it: the model, then *Views*, *Elements* and *Relationships* by type, and *Unclassified*; with the model's
  own folders (*Folders* | *Types*), each group's folders first, folded, a folder under the groups of what it
  holds. It filters by name or type; a row opens its page; the page's own row is selected and unfolded to, and on a
  view's page the concepts it draws are marked. The grouping and what is folded are kept from page to page.
  `_assets/data/tree.js` and `_assets/js/tree.js`.
- **The header** has what the index's left menu had: *Models* and *Folders* menus, on every page; a folder opens its
  section of the index. The index's sidebar is gone.
- **The notation's icons are meta.linked.archi's.** A glyph, a type's picture and a decoration's symbol are shown at
  their IRIs, where meta.linked.archi publishes them. The notation pack holds the languages' Turtle files only, and
  no icon is copied into the site or drawn by rdf2docs: `_assets/notation/`, `_assets/types/` and `_assets/figures/`
  are gone. Cytoscape draws a node as its type's published picture, else its shape with its glyph as a badge. The
  lines' end decorations are read from their published symbols when a view is drawn, and drawn from their built-in
  geometry without a network.
- **The diagram zooms and pans**: the wheel or a pinch zooms at the pointer, dragging anywhere pans and opens
  nothing, a double-click zooms in; `+`, `−`, `0`, `1` and the arrows; *Fit*, *1:1* and full screen. The zoom is the
  drawing's `viewBox`, so it stays sharp; a page opened at a shape is zoomed to it.

*NotationTest*.

### Added: diagrams in their notation, the graph in two renderers, SPARQL and the model as RDF

The diagrams were grey boxes joined by grey arrows: every element the same rectangle, every relationship the
same arrowhead, whatever its language. The graph page had one renderer and three controls, and the model's own
data could be read only a resource at a time, in its `facts.ttl`.

- **The notation.** A view is drawn in its language's visual notation: each element in its notation's shape, fill,
  line and glyph, as a box with a badge, as its glyph or as its shape, its name laid out clear of the badge, in a
  container at its top; each line in its notation's line style and end decorations, drawn from core-vis's
  published symbols, docked on the outline drawn, above every shape; groups, notes, shapes and references as
  figures. A node's or a line's own look in the view is drawn over the notation's. The lookup, the geometry and
  the drawing are ported from the notation layer la-gram draws with (`notation/`), and the languages are a
  notation pack in the jar, refreshed from linked-archi-meta by `scripts/sync-notation.sh`: ArchiMate 3.2 and 4,
  BPMN, BPMN Lite, C4, UML, LeanIX, Backstage and Basic, loaded as the model's IRIs need them.
- **Presentation contexts.** A view's page switches between its languages' contexts: the notation set each uses,
  and the names, pictures, label expressions and looks the model gives each audience. `--presentation-context`
  chooses the one a page opens with; only that drawing carries the shapes' fragments as ids.
- **The diagram** zooms, fits and pans, shows the names on its lines, points out the shapes of a type from its
  legend, and downloads as SVG; a shape opens its concept's page.
- **Icons and groups.** Each concept is shown with its type's icon, its type
  and its group in its language's taxonomy (a layer, a domain, a relationship category). The index lists by folder
  or by type, every folder, group and type a collapsible section with its count; every list sorts and filters; type
  chips show or hide an element type. The details table names each predicate in words, a type by its icon and
  name, a concept by its icon and name, a shape by what it draws, a folder by its name. A relationship with no name
  is named by its ends, `Customer → Place order`.
- **The graph** is drawn by D3, its link distance and strength, repulsion, collision, gravity and friction on
  sliders, or by Cytoscape.js, each node its type's figure and each edge in its notation, laid out as a force,
  rings, a tree, a circle or a grid. Both filter by element type, relationship type and model, show a node's
  neighbourhood to a depth or the whole graph, find a node, centre on one from the folder tree, and show what is
  selected. The graph config's `filterGroups` group the type filter when it gives any.
- **SPARQL** (`sparql.html`): Oxigraph, in the page, over every named graph; example queries, recent queries, the
  results linked to the pages of what they name and downloaded as CSV, TSV, JSON or Turtle.
- **RDF** (`rdf.html`): the model as TriG, a section per named graph, chosen by graph, searched as text or as a
  regular expression, and downloaded; `model.trig`, the whole graph.
- **From disk.** D3, Cytoscape.js and Oxigraph are carried in `_assets/vendor/`, Oxigraph as a classic script with
  its WebAssembly inlined, and the pages' data are scripts: every page works opened from disk as well as served.
  `graph-nodes.json`, `graph-edges.json`, `graph-folders.json` and `graph-filters.json` are replaced by
  `_assets/data/graph.js`.

*NotationTest*; *StaticPublishingTest*, whose bend point test reads the line's points and checks its ends docked
on the shapes.

### Changed: pages follow the IRI layout of DD-41

The converters now write no blank nodes and name a view's shapes in the view's IRI, `{view}#node-{id}`
(DD-41, and the converters' changelog). rdf2docs gave a page to every IRI subject under `--base` except six
excluded types, so each part and route cell became a page of its own. On the converters' current output it
wrote 53 pages for `payment-platform.xml` and 671 for ArchiSurance, where it had written 29 and 325 before
the converters named their parts, and a directory named `view/{v}#link-{id}-points-0` for each of
ArchiSurance's 42 route cells.

- **Pages are chosen by type.** A page is for a view, a relationship, an element (any core class, or a member
  of a model by `arch:inModel` or by a folder), a model, a documentation section (the object of
  `schema:subjectOf`), a provenance record (a PROV class: the run, the converter, the source file, the named
  graphs) and a former name (`dcterms:isReplacedBy`), so that a model IRI from before DD-41 still opens a
  page, which says what replaced it; with `--model-id` that IRI is the base itself, the index's URL, and gets
  no page of its own. Nothing else gets one. Measured on the converters' playground:
  `payment-platform.xml` 30 pages and ArchiSurance 326, the 29 and 325 above plus the former name's page;
  every one of ArchiSurance's 122 elements, 178 relationships and 17 views has a page, and none of its 237
  nodes and 199 connections does.
- **A shape is a fragment of its view's page.** The diagram is in the page as inline SVG, not an `<img>`, and
  each shape has its fragment as `id`: `node-{id}`, `link-{id}`, `link-{id}-point-{i}`. `{view}#node-{id}`
  opens the page at the shape, and a `:target` style marks it. A view with no layout to draw (PlantUML,
  Structurizr) lists its shapes in a table whose rows have the same ids. `diagram.svg` is still written.
- **A link to a shape is its view's page and the fragment**, `view/{v}/index.html#node-{id}`. An element's
  *Views* tab links to the shape that shows it. No directory name holds a `#`.
- **A part is shown on its owner's page.** A property pair with an IRI is on the *Properties* tab again (it
  was read only as a blank node, so `id-ba-merchant`'s `leanixUrl` and `costCenter` were shown nowhere).
  Another part, such as a LeanIX tag or a decision's rationale, is drawn inline as the table of its own
  statements. An IRI under the base that nothing shows is written out rather than linked to a page that does
  not exist.
- **`facts.ttl` holds what its page shows**: the resource's statements, those of its parts and, for a view,
  those of its shapes, each triple once even when a TriG input states it in several graphs. It said "direct
  triples plus a 1-hop neighbourhood" here, which it had not been for some time.
- **A *Provenance* tab** on the page of a view, relationship, element, model or documentation section: the
  source file, the run and the converter that made it, read from the resource itself, else from the named
  graph that types it, else from its model. A provenance record's page has a *Referenced by* tab instead, so
  a run lists what it generated.
- **The AI context is partitioned by model, read from the triples**: `arch:inModel`, else the model whose
  `vann:preferredNamespaceUri` the IRI is in. It dropped the last two segments of the path, which put a
  Backstage element (`element/component/default/payment-api`) in a partition of its own: a graph merged from
  five converted models gave 47 partitions, and now gives 5. Each partition starts with its model and ends
  with the model's documentation sections, quoted; provenance records and former names are not in it.
- The index lists models, documentation sections and provenance records in sections of their own instead of
  under *Unorganised*, and does not list former names. The graph view shows views, elements and
  relationships only.
- A view's own tabs are *Elements in view* and *Relationships in view*, so that a BPMN process that is its
  view has no two tabs called *Relationships*.

### Fixed

- **A folder placed in its parent by `dcterms:isPartOf` alone is in the folder tree.** The converters' standard
  *Elements*, *Relationships* and *Views* folders are placed that way, so every member of a BPMN, LeanIX,
  Backstage, PlantUML or Structurizr model was listed under *Unorganised*.
- **Bend points are drawn in the order of their list.** They were read with an `rdf:rest*/rdf:first` path,
  which returns a list's members in no particular order.
- A node is drawn once even when its element has two labels or its style two values; it was drawn once per
  row of the query.
- Coordinates are read whatever their datatype: the converters write `xsd:double`.
- **The directory of a page is the decoded IRI path**, `element/a b` for `element/a%20b`, since a web server
  and a browser reading `file:` URLs decode a path before they look for the file. Links keep the IRI's form.
- A resource whose IRI cannot be a directory of its own is listed under *Skipped resources* with the reason
  instead of being written somewhere else: the base IRI itself, whose page is the index; an IRI with a
  fragment; a path with an empty, `.` or `..` segment, escaped or not, which would have been written outside
  the output directory; a segment that decodes to `/`.
- A resource with no `skos:prefLabel` is named by its `schema:name`, `rdfs:label` or `dcterms:title`, and a
  model with none of these by the namespace it states. Every DD-41 model IRI ends in `/model`.
- `generation-stats.md` lists the pages written by kind.

### Playground and examples

- `playground/run-archimate-model.sh` and `run-bpmn-model.sh` document the converters' own playground models,
  ArchiSurance and a BPMN model of two processes. They run from any directory and write to
  `playground/out/archisurance` and `playground/out/order-domain`.
- `playground/config/graph-config.yml` groups and hides ArchiMate 3.2's published types.
- The examples in the README and `docs/` use `example.org` IRIs and the jar's real name,
  `rdf2docs-0.1.0-all.jar`. `docs/00-cli.md` lists `--model-id` and `--graph-config`.

### Other unreleased changes

- **A single unwritable resource no longer aborts the run.** `HtmlPageGenerator` derives an output
  directory per resource by stripping `--base` from the IRI, so an IRI path segment longer than the
  filesystem allows for one directory name (255 bytes) threw `FileNotFoundException`. Because the
  coroutines shared the caller's `SailRepository` connection, the first failure closed that connection
  and every job still in flight failed with "Connection has been closed"; the process exited 1 with a
  partially written output directory that looked plausible. Each batch now opens its own connection —
  an RDF4J connection is not thread-safe either — and each resource is written independently. Resources
  that cannot be written are counted, logged, and listed with their reason under *Skipped resources* in
  `generation-stats.md`. Pages are not hashed or truncated to fit: the URL of a page is the IRI of the
  resource, and that is the property the output exists to have.
- `HtmlPageGenerator`: `labelCache` is a `ConcurrentHashMap`. It was a plain map read and written from
  every generation coroutine.
- **`ai-context.md` is partitioned, one document per model, indexed from `ai-context.md`.** Generated
  from a merged multi-notation graph it was a single 9.8 MB / 163,797-line file — far past any model's
  context window, so it could not be used for the purpose its own header describes. A single-model
  graph is still written as one file with no index, so single-model runs are unchanged.
- `AiContextGenerator`: sections are ordered views, elements, relationships. Relationships were
  interleaved with elements in one alphabetical run of type headings, which put thousands of entries
  with opaque synthesised ids ahead of the content a reader wants first.
- `AiContextGenerator`: a relationship with no `skos:prefLabel` is headed by its endpoints
  (`Customer → Web Shop`) rather than by the last segment of its synthesised IRI.
- `HtmlPageGenerator`: type headings and type tags show the specific type. Generic core types
  (`arch:Element`, `arch:ModelConcept`, `arch:QualifiedRelationship`) were added to a resource's type
  set as they arrived, so whichever came first stayed at the head of the set and every heading derived
  from it read "ModelConcept" instead of "Class". They are now used only when nothing more specific
  exists.
- `generation-stats.md` reports relationships separately from elements, lists the AI context
  partitions, and lists skipped resources.
- **Aligned on RDF4J 6.0.0**, the version the converters publish with; this tool was pinned to 5.1.0.
  It reads the graphs they write, so a graph they can legitimately emit — RDF 1.2 triple terms and
  base-direction literals, added in 6.0.0 — was a graph this tool could not parse. Requires JDK 25 as
  a consequence, which RDF4J 6.x is compiled for. Also bumps Kotlin to 2.3.0, Gradle to 9.6.1 and the
  Shadow plugin to `com.gradleup.shadow` 9.3.1, matching the converters.
- **Fat jar registers its Rio parser and writer factories explicitly.** RDF4J declares them through
  JPMS `module-info`, which shading discards, so `ServiceLoader` found only whichever legacy service
  file survived the merge: every `facts.ttl` write failed with `UnsupportedRDFormatException: Did not
  recognise RDF format object Turtle`, and a `.ttl` input would not have parsed at all.
  `mergeServiceFiles()` cannot repair that on its own. `META-INF/services` files are now authored in
  `src/main/resources`, as the converters' `core` module does.
- `.gitignore`: ignore `bin/` — the byte-identical copy of `src/` that the Eclipse JDT language server
  writes and that was sitting untracked in the repo — and `out/`.
- `HtmlPageGenerator`: switched from flat `<path>.html` files to per-resource directories (`<path>/index.html` + `facts.ttl`).
- `HtmlPageGenerator`: `facts.ttl` sidecar contains direct triples + 1-hop neighbourhood for each resource.
- `HtmlPageGenerator`: index page now groups resources by RDF type with counts, live search box, and type dropdown filter.
- `HtmlPageGenerator`: resource pages show contextual sections — *Used in views* (via `archvis:archElement`), *Relationships* (for elements: all connected `arch:Relationship` instances with direction and other endpoint), *Connects* (for relationships: source and target elements).
- `HtmlPageGenerator`: all relative links depth-aware (correct `../` prefix based on directory depth).
- `playground/run.sh`: added `rm -rf` cleanup before regenerating output.

## 0.1.0

- Initial implementation: AI context Markdown generator and static HTML page generator from RDF (Turtle/TriG).
