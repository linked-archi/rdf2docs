# Output format reference

## AI Context Document (`ai-context.md`)

Markdown structured for AI assistants and human readers.

### Partitioning

One document per model, because a merged multi-notation graph produces megabytes of Markdown and no
model has a context window that size. A resource's model is read from the triples: the model it states
with `arch:inModel`, else the model whose namespace (`vann:preferredNamespaceUri`) its IRI is in, which is
how a LeanIX stakeholder or a Structurizr documentation section, stating neither, finds its model. The
partition is named by that namespace below the base:

```
output/
├── ai-context.md                     ← index: instructions + one row per partition
└── ai-context/
    ├── plantuml-shop.md              ← https://example.org/la/plantuml/shop/
    ├── bpmn-order-domain.md          ← https://example.org/la/bpmn/order-domain/
    └── model-payment-platform.md     ← https://example.org/la/model/payment-platform/
```

A graph with a single model — including any run where `--model-id` folded the model into `--base` —
is written as one `ai-context.md` with no index and no subdirectory.

### Structure of a partition

The model first, then views, elements and relationships, then the model's documentation. Relationships
come after elements because they are by far the most numerous and only mean anything once their
endpoints have been introduced.

```
# Architecture Knowledge Base — <notation>/<model>
## Instructions for AI Assistant
---
## Model
### [Model Name](IRI)
- IRI, Type, Metadata, Relationships  ← as for an element

## Views
### [View Name](IRI)
- IRI: `...`
- Diagram: <image url>      ← when the view has a rendered image
- Contains:                 ← elements visible in the view
  - [Element Name](IRI)
- Links:                    ← non-visual view properties (wiki, Jira, etc.)
  - <predicate>: <value>

## Elements
### <TypeLocalName>         ← the specific type (Class, ApplicationComponent), never arch:Element
#### [Element Name](IRI)
- IRI: `...`
- Type: ...
- Metadata:
  - <key>: <value>
- Relationships:
  - <predicate>: [Target Label](IRI)

## Relationships
### <TypeLocalName>
#### [Source → Target](IRI) ← endpoints when the relationship has no label of its own

## Documentation
### [01-overview.md](IRI)
- IRI: `...`
- Documents: [Model Name](IRI)
- Format: text/markdown
> the section's text, quoted line by line
```

A relationship rarely carries a `skos:prefLabel`, and its id is synthesized from its endpoints, so
naming it by its endpoints is what makes the section readable. A documentation section is quoted rather
than inlined, so that a heading in its own Markdown does not become a heading of the partition.

### What is included

| Included | Excluded |
|---|---|
| Views, elements and relationships | Shapes, styles and points (anything typed in core-vis) |
| Models | Parts of a resource, such as property pairs and tags |
| Documentation sections | Folders |
| View → element membership | Provenance records: runs, converters, source files, named graphs |
| View external links (vocab properties) | Former names, such as a model's IRI from before DD-41 |

Which resource is which is decided as for the HTML pages, below.

### Relationships section

For each element/relationship, all outgoing IRI-valued triples are listed except `rdf:type` and `dcterms:isPartOf`. If the target has a `skos:prefLabel`, that is used as the link text; otherwise the IRI local name is used.

---

## HTML Pages (`html/`)

A page is written at the resource's IRI relative to `--base`, laid out as IRI Minting has it
([*Static publishing*](https://meta.linked.archi/docs/guide/iri-minting/#static-publishing)): one page for each
resource in its own right, a shape as a fragment of its view's page, a part on its owner's page.

### Which resources get a page

Chosen by what the triples say a resource is, never by the shape of its IRI. A subject under `--base`
gets a page as the first of these it is:

| Kind | Rule |
|---|---|
| (none) | Typed with a core-vis class (a shape, style or point) or `arch:Folder`: no page |
| View | Typed `arch:View`, `arch:Diagram`, `arch:Catalog` or `arch:Matrix`, or with a type called `View` or `Diagram` |
| Relationship | Typed `arch:QualifiedRelationship`, or with an `arch:source` |
| Model | Typed `arch:Model` |
| Element | Typed with any other core class (`arch:Element`, `arch:Stakeholder`, …), or a member of a model by `arch:inModel` or by a folder that lists it |
| Documentation section | The object of `schema:subjectOf` |
| Provenance record | Typed with a PROV class: the run, the converter, a source file, a named graph (`prov:Bundle`), a qualified derivation |
| Former name | With a `dcterms:isReplacedBy`, as a model's IRI from before DD-41 is |

Anything else is a part of a resource — a property pair, a tag, a folder entry, a route cell — and has no
page. A BPMN process that is its collaboration diagram's view is a view, and has one page.

`generation-stats.md` lists the pages written by kind.

### `index.html`

Two ways to list the same resources, *By folder* and *By type*; the one last chosen is kept per reader.

- **By folder**: resources listed in folders appear under their folder, following the `arch:Folder` tree; a
  folder is in its parent's when the parent lists it or when it states `dcterms:isPartOf` the parent. The rest
  are listed by kind: *Models*, *Documentation*, *Unorganised* (views, elements and relationships in no folder)
  and *Provenance*. A folder chosen in the header's *Folders* menu or in the model tree (`index.html#sec-…`) is
  shown alone, until *Show all folders*.
- **By type**: elements, then relationships, in their languages' groups — a layer, a domain, a relationship
  category — and in each group by type; then the views.

Each folder, group and type is a collapsible section with its count, each list a table that sorts by any column.
A row has its type's icon, its name, its type and its group. The search box filters every list by name or type;
a chip for each element type shows or hides its rows. Cards at the top count the elements, relationships, views,
models and documentation sections. Former names are not listed.

### The header and the model tree

Every page has a header: the Index, Graph, SPARQL and RDF pages; a *Models* menu, opening each model's page; and
a *Folders* menu, the model's folders as a tree, with a filter, each opening its section of the index.

The index and every resource's page have the **model tree** on the left, built as la-gram's Model Explorer is:

```
▾ ◆ Archisurance                  the model, opening its page
  ▾ Views (17)                    every view; the page's own selected
  ▾ Elements (122)
    ▸ Business actors (30)        the model's folders first, each folded
    ▾ System Software (1)         then what no folder holds, by type
        Logiciel Financier   ●    ● = in the view this page shows
  ▸ Relationships (178)           by type, "Source → Target"
  ▸ Unclassified                  types no loaded language defines
```

*Folders* shows the groups with the model's folders in them — a folder under the groups of what it holds, a folder
named *Views*, *Elements* or *Relationships* standing for its group, a converter's folder for the model itself
shown as the model; *Types* shows each group by type alone. A model without folders is shown by type. The tree
filters by name or type; each row has its type's icon, as the notation publishes it; a row opens its page. The page's
own row is selected and unfolded to; the grouping, and what is folded, are kept as the reader moves between pages.
☰ in the header, or « in the tree, hides it.

### Per-resource directory (`<path>/`)

Each resource gets its own directory under `html/`. Example:

```
https://example.org/la/model/payment-platform/element/id-ba-merchant
→ html/model/payment-platform/element/id-ba-merchant/
      index.html    ← human-readable page
      facts.ttl     ← Turtle: what the page shows
```

The directory is the decoded IRI path, `element/a b` for `element/a%20b`, because a web server and a
browser reading `file:` URLs decode a path before they look for the file. Links keep the IRI's own form.

`facts.ttl` holds the resource's statements, those of its parts and, for a view, those of its shapes, each
triple once even when a TriG input states it in several graphs. It is written as Turtle 1.2, since a
relationship's `rdf:reifies` names a triple term.

### Resource page sections

Each `index.html` has the header and the model tree; a breadcrumb (the index, the
resource's model, its kind); its name with its type's icon, its type and its group; its IRI, to copy; and links to
`facts.ttl` and, for a concept or a model, a SPARQL `DESCRIBE` of it. Then the sections that apply to it:

| Section | Condition | Content |
|---|---|---|
| Details | always | The resource's own statements, a row a predicate: its name, its prefixed name below. A type is shown with its icon; the core's generic classes by their prefixed names. An IRI with a page is its icon and its name; a shape, the name of what it draws and its fragment; another IRI under the base, its name; an IRI outside it, its name in a loaded language, else its prefixed name — the IRI on hover. A part's statements are drawn inline, as a table; a literal's language follows it |
| Diagram | a view whose shapes have bounds | The diagram in its notation, as inline SVG, each shape with its fragment as `id` and linked to its concept's page, zoomed and panned in its viewport; a toolbar (presentation context, zoom, full screen, names on lines, the SVG to download); a legend of the types it shows, each pointing out its shapes; a link to `diagram.svg` |
| Shapes | a view with shapes the diagram does not draw | A table of them, each row with the shape's fragment as `id` |
| Properties | `schema:additionalProperty` pairs | Key and value |
| Views | an element or relationship shown in a view | Each view, linked at the shape that shows it, and how many shapes show it there |
| Relationships | an `arch:Element` with relationships | *Outgoing* and *Incoming*, each a collapsible group: the relationship with its type's icon, the element at its other end with its own, its type; a filter |
| Endpoints | a relationship | Its `arch:source` and `arch:target`, with their icons and types |
| Elements in view | a view | What its shapes show, by group and type, each collapsible; a filter |
| Relationships in view | a view | Source, relationship and target, with their icons; a filter |
| About | a documentation section | The resources whose `schema:subjectOf` it is |
| Provenance | a view, relationship, element, model or documentation section | The source file, run and converter, read from the resource, else from the named graph that types it, else from its model |
| Referenced by | a provenance record | The statements in the PROV vocabulary whose object it is, so a run lists what it generated |

A former name's page says what replaced it. A tab that lists says how many rows it holds, `Relationships (12)`, and
the tab a reader chose is kept for the page while the browser tab is open. A relationship with no name of its own
is named by its ends, `Customer → Place order`.

### The diagram

Drawn in the notation of the view's language, from the notation pack ([The notation](01-architecture.md#the-notation)):

- **An element** in its notation's shape, fill and line — a core shape, or the notation's own drawn from its path
  templates (ArchiMate's 3D box, C4's person) — as a box with its glyph as a badge, as its glyph alone, or as its
  shape alone, as the notation's rendering mode says; its name inside, wrapped, clear of the badge; in a container,
  at its top.
- **Its own look** in the view over the notation's: the fill, line and text colours, line width, border style,
  font, text alignment and position, gradient, glyph colour, picture, and a shape among the notation's
  alternatives that its `arch-vis:style` sets.
- **A figure** with no element behind it: a group with its name in a tab at its top left, a note folded at its
  top right with its text from the top left, a shape in its core shape, a reference to another view marked with a
  small view and linked to it. The converters' `arch-vis:Node`, an Archi group, is a group.
- **A line** in its notation's line style, with its end decorations drawn from core-vis's published symbols; from
  each end's centre through its bend points, docked where it meets the outline drawn; above every shape. Its name,
  when it has one, shows with *Names on lines*.

**Zoom and pan.** The drawing fills a viewport as tall as it needs, within the window. The wheel, or a pinch on a
trackpad or a touch screen, zooms at the pointer, from 5% to 800%; dragging anywhere pans — over a shape too,
and a drag does not open the shape's page; a sideways scroll pans. A double-click zooms in. With the viewport
focused, `+` and `−` zoom, `0` fits the view, `1` shows it at actual size, and the arrows pan. *Fit*, *1:1*, `+`, `−`
and ⛶ (full screen) are in the toolbar. Zooming changes the drawing's `viewBox`, so it stays sharp. A page opened at
a shape (`#node-…`) is zoomed to it.

**Presentation contexts.** When the model's languages offer presentation contexts, the *Presentation* list
offers them, and the language's notation as the first entry. The drawing for `--presentation-context` (else for
the notation) is the one in the page's HTML, with the shapes' fragments as ids; each other context that draws the
view differently — another notation set, the names, pictures, label expressions and looks the model gives that
audience — is a second drawing, hidden until chosen. A context that draws it alike shows the same drawing. The
choice is kept per reader.

`diagram.svg` is the default drawing, whole. Its glyphs are meta.linked.archi's IRIs, so it shows them wherever
it is opened online.

### `graph.html`

The views, elements and relationships as a graph, drawn two ways, chosen in the header:

- **D3**: each node a circle in its type's colours with its glyph; each edge in its notation's line style with its
  end decorations. The forces are on sliders: link distance and strength, repulsion, collision, gravity and
  friction; *Reheat*, *Freeze* and *Reset*. A node can be dragged; its size can follow its number of connections.
- **Cytoscape.js**: each node its type's picture as its notation publishes it — its whole figure — else its
  notation's shape in its colours with its glyph as a badge, or its glyph alone; each edge in its
  notation's line style with its end decorations. Laid out as a force (cose), rings, a tree, a circle or a grid,
  with the node size and spacing on sliders.

Both show the *Neighbourhood* of a node to a depth, or the *Whole graph*; nodes without edges or not; names on
nodes, relationship types on edges. *Show views, linked to their elements* makes each view a node, linked to every
element it shows. Off, a view in the middle — chosen in the search, the folders or a neighbour list — stands for
what it shows: its elements and the relationships between them, the depth set aside. When there is next to nothing
to draw, a note says why — a node with no relationship, a view of figures only, types hidden — and offers what
would show more. Element types (and view types, in a group of their own), relationship types (each with its icon and count, in its
language's groups, or the graph config's) and models filter what is shown. A relationship is whatever has an
`arch:source` and an `arch:target`, whether or not it is typed `arch:QualifiedRelationship`. A search box, the
folder tree and the info panel's neighbours choose the node in the middle; the info panel shows a node's type,
group, page and relationships. *Export* saves an SVG (D3) or a PNG (Cytoscape). The settings are kept per reader.

### `sparql.html`

SPARQL 1.1 over the whole model, in the page: Oxigraph loads the model's statements, every named graph as it is,
and runs the query. *All graphs as one* queries the default graph and every named graph together; off, `GRAPH`
reaches the named graphs. *Examples* offers queries to start from; *Recent queries* the last twenty run in this
browser; *Add the model's prefixes* puts its prefixes before the query. ⌘/Ctrl+Enter runs it.

A `SELECT` is a sortable, filterable table: an IRI with a page is its icon and name, linked; another, its
prefixed name, linked; a literal, its value with its language or datatype; a triple term, `<<( s p o )>>`. Its
results download as CSV, TSV or JSON. An `ASK` says yes or no; a `CONSTRUCT` or `DESCRIBE` is a table of its
statements, downloaded as Turtle. The address `sparql.html#query=…` opens with the query and runs it.

### `rdf.html`

The whole model as TriG: its prefixes, then a section for each named graph, each subject's statements a block,
highlighted, with a link to its page. The named graphs, each with its number of statements, are chosen in the
side list (`rdf.html#graph=<iri>` opens with one); the search box keeps the blocks that say what it says — as
text, as a regular expression, or in their subjects only — and marks it. *Download the graphs shown* saves them
as one TriG document. `model.trig` is the whole graph.

### `_assets/`

| Path | What |
|---|---|
| `js/site.js`, `js/tree.js`, `js/diagram.js`, `js/graph.js`, `js/graph.css`, `js/sparql.js`, `js/rdf.js` | The pages' scripts |
| `vendor/` | D3 7.9.0, Cytoscape.js 3.34.3, Oxigraph 0.5.11 (its WebAssembly in a script), and their licences |
| `data/tree.js`, `data/graph.js`, `data/model.js` | The data the model tree, the graph, SPARQL and RDF pages read, as scripts |

The data are scripts rather than JSON files, and Oxigraph a classic script, so that the pages work opened from
disk as well as served. The notation's icons, glyphs, pictures and decoration symbols are not in the site: the
pages show them at their IRIs, where meta.linked.archi publishes them, so a page shows them when it is online.
A type's icon is its picture, else its glyph, else none; one meta.linked.archi does not serve is not shown. A
relationship type without a picture is its whole line, drawn from its notation: its line style and colour, with
both ends' decorations.

### Shapes and parts

A shape is named in its view's IRI, `{view}#node-{id}`, `{view}#link-{id}` and `{view}#link-{id}-point-{i}`
(DD-41), so its place is its view's page. The page gives each shape that fragment as `id`, in the SVG or in
the *Shapes* table, and a browser opening `{view}#node-{id}` scrolls to the node; a `:target` style marks
it. A link to a shape is its view's page with the fragment, `view/{v}/index.html#node-{id}`.

A part is named under its owner, `{owner}/{part}`, and is shown on the owner's page: the closest page whose
IRI the part's extends. Everything after a view's `#`, a shape's style and route included, is shown on the
view's page. A former name owns nothing, though it is a prefix of every IRI in its model, so a
part whose closest page is a former name, such as a folder entry, is shown on no page.

An IRI under `--base` that no page shows is written out as text rather than linked.

### Relative linking

All internal links are computed relative to the page's own depth in the directory tree. A page at
`model/payment-platform/element/id-ba-merchant/index.html` (depth 4) uses `../../../../` to reach
`index.html`, and `../../../../model/payment-platform/view/id-v1/index.html#node-id-n1` to reach the shape
of it in a view.

### Resources without a page

A resource is skipped, rather than written, when its IRI cannot be a directory of its own:

- one path segment exceeds 255 bytes, the filesystem limit for a single directory name;
- the IRI is `--base` itself, whose page is the index. A former name there, a model's IRI from before DD-41
  under `--model-id`, is not reported: the index is the page to open at that URL;
- the IRI has a fragment;
- the path has an empty, `.` or `..` segment, escaped or not, which would be written outside the output
  directory, or a segment that decodes to `/`.

Those resources are listed with their reason under **Skipped resources** in `generation-stats.md`, and
logged as warnings.

The run completes and everything else is written. The page is not renamed or hashed to fit: the URL of
a page is the IRI of the resource, and shortening the directory would break the one property the
output exists to have. A skipped resource keeps its entry in the AI context document and is still
linked from other pages, so those links resolve to nothing until the IRI is shortened upstream.
