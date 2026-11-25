# RDF2Docs Generator Features

## Input RDF requirements

The generator is model-agnostic and works with any Turtle or TriG file produced by
the ArchiMate or BPMN converters. It expects the following RDF patterns:

| Pattern | Purpose | Required? |
|---|---|---|
| `?s rdf:type ?t` where `?s` starts with `--base` | Identifies resources, and which kind of page each gets ([the rules](02-output-format.md#which-resources-get-a-page)) | yes |
| `?s skos:prefLabel ?label` | Display name; else `schema:name`, `rdfs:label` or `dcterms:title`; a relationship with none is named by its ends | recommended |
| `?model arch:modelConformsToMetamodel ?mm`, `?s rdf:type ?t` with `?t` a Linked.Archi language's | The notation the diagrams, icons and graph are drawn with | recommended |
| `?node archvis:style ?st . ?st archvis:fillColor …` | A shape's own look in a view; with `arch:inPresentationContext`, for one audience | optional |
| `?s arch:inModel ?model` and `?model vann:preferredNamespaceUri ?ns` | The model a resource belongs to, which partitions the AI context | recommended |
| `?s a arch:Folder ; schema:name ?n ; schema:itemListElement ?li` | The model tree's and the index's folders | recommended |
| `?li schema:item ?member ; schema:position ?pos` | Ordered folder membership | recommended |
| `?folder dcterms:isPartOf ?parent` | A folder's place in its parent folder | optional |
| `?s a arch:Element` | Enables Relationships tab | for elements |
| `?s a arch:QualifiedRelationship ; arch:source ?src ; arch:target ?tgt` | Enables Endpoints tab | for relationships |
| `?node archvis:view ?view ; archvis:archElement ?elem` | Enables Views tab and view content | for views |
| `?node archvis:bounds-x ?x ; archvis:bounds-y ?y ; archvis:bounds-w ?w ; archvis:bounds-h ?h` | SVG diagram rendering | for views |
| `?link archvis:points ( ?p … ) . ?p archvis:point-x ?x ; archvis:point-y ?y` | A link's bend points, in list order | optional |
| `?s schema:additionalProperty ?pair . ?pair schema:name ?k ; schema:value ?v` | Properties tab; the pair an IRI or a blank node | optional |
| `?owner schema:subjectOf ?section` | A documentation section, and its About tab | optional |
| `?s prov:wasDerivedFrom ?source`, `?s prov:wasGeneratedBy ?run`, on the resource, its graph or its model | Provenance tab | optional |
| `?s schema:image ?url` | Thumbnail in index | optional |

A page is for a resource in its own right: a view, relationship, element, model, documentation section,
provenance record or former name. A shape is a fragment of its view's page, and a part, such as a property
pair, is shown on its owner's page; neither gets a page of its own. The rules are in
[Which resources get a page](02-output-format.md#which-resources-get-a-page).

---

## Core Features

### 1. Resource Pages
Each RDF resource gets its own HTML page with:
- **Main Properties Table** - All RDF triples for the resource, with predicate names linked to their full IRI
- **Raw TTL Download** - `facts.ttl` file with complete RDF data
- **Breadcrumb Navigation** - Back to index

### 2. Dynamic Tabs System
Pages automatically generate tabs based on resource type and relationships:

#### Properties Tab
- Custom properties via `schema:additionalProperty`
- Key-value pairs, read from the pair whether it is an IRI (`{owner}/property/{digest}`) or a blank node
- Sorted alphabetically

#### Views Tab (for Elements/Relationships)
- Shows which views contain this resource
- Links to the view's page at the shape that shows it, `view/{v}/index.html#node-{id}`
- Extracted via `archvis:archElement` and `archvis:archRelationship`

#### Relationships Tab (for Elements)
- **Outgoing**: Where this element is `arch:source`
- **Incoming**: Where this element is `arch:target`
- Shows relationship type and target/source element
- Links to relationship and connected element pages

#### Endpoints Tab (for Relationships)
- **Source**: `arch:source` element
- **Target**: `arch:target` element
- Element types and links

#### Elements in view Tab (for Views)
- All elements contained in this view
- Element types and links
- Via `archvis:archElement` connections

#### Relationships in view Tab (for Views)
- All relationships shown in this view
- Source → Relationship → Target format
- Via `archvis:archRelationship` connections

#### About Tab (for Documentation sections)
- The resources the section documents, by `schema:subjectOf`

#### Provenance Tab
- The source file, the conversion run and the converter that made the resource
- Read from the resource itself, else from the named graph that types it, else from its model, and says which

#### Referenced by Tab (for Provenance records)
- Every statement in the PROV vocabulary whose object the record is: what a run generated, what was derived
  from a source file

A part of the resource — a LeanIX tag, a decision's rationale — is drawn inline in the Details table, as the
table of its own statements, since it has no page to link to.

### 3. Index Page with Folder Navigation
- **Model tree** - On the left, the model as la-gram's Model Explorer shows it: views, elements and relationships
  by type, or in the model's own folders; the page's own row selected
- **Header menus** - *Models* and *Folders*, on every page
- **By folder, by type** - The same resources by folder, or by type in their language's groups (a layer, a
  domain, a relationship category)
- **Collapsible groups** - Each folder, group and type a section with its count; *Expand all*, *Collapse all*
- **Search and type chips** - Filter every list by name or type; show or hide an element type's rows
- **Sortable tables** - Each list sorts by any column
- **Type icons** - Each row with its type's icon, its type and its group
- **Thumbnails** - For views with `schema:image`

### 4. Diagrams in Their Notation
For views that have BPMNDI or ArchiMate view geometry, the diagram is drawn as SVG in the view's page, and
written to `diagram.svg` as well. Requires `archvis:bounds-*` on nodes and `archvis:source` /
`archvis:target` on links. Each shape has its fragment as `id`, so `{view}#node-{id}` opens the page at the
node. A view without geometry lists its shapes in a table instead, each row with the same `id`.

Each element is drawn in its language's notation — its shape, fill, line, glyph and label placement — with its own
look in the view over it; each line in its notation's line style and end decorations, docked on the outlines
drawn; nested shapes inside their containers. The languages are those of the notation pack: ArchiMate 3.2 and 4,
BPMN, BPMN Lite, C4, UML, LeanIX, Backstage and Basic. A view's page switches between its language's
presentation contexts, zooms with the wheel or a pinch and pans by dragging, shows the names on the lines, and points out the shapes of a type from
its legend; clicking a shape opens its concept's page. See [The diagram](02-output-format.md#the-diagram).

### 5. Graph
`graph.html` draws the views, elements and relationships with D3 — its forces on sliders — or with
Cytoscape.js, each node its notation's figure; filtered by element type, relationship type and model, around a
node to a depth or whole. See [`graph.html`](02-output-format.md#graphhtml).

### 6. SPARQL and RDF
`sparql.html` runs SPARQL over the whole model in the page, with example queries, the results linked to their
pages and downloaded as CSV, TSV, JSON or Turtle. `rdf.html` shows the model as TriG, a section per named graph,
chosen by graph and searched. `model.trig` is the whole graph. See
[`sparql.html`](02-output-format.md#sparqlhtml) and [`rdf.html`](02-output-format.md#rdfhtml).

## Output structure

The output path for each resource is derived by stripping `--base` from the IRI. The converters mint
`{base}{notation}/{modelId}/` as a model's namespace, and its resources as `{kind}/{id}` in it (DD-41):

```
IRI:  https://example.org/la/model/payment-platform/element/id-ba-merchant
base: https://example.org/la/
→     html/model/payment-platform/element/id-ba-merchant/index.html

IRI:  https://example.org/la/bpmn/order-domain/view/order-fulfillment#node-Shape_Start
→     html/bpmn/order-domain/view/order-fulfillment/index.html, at id="node-Shape_Start"
```

Full output layout:

```
output/
├── ai-context.md                         # AI context document, or its index when partitioned
├── ai-context/                           # one document per model (multi-model graphs only)
│   ├── model-payment-platform.md
│   └── bpmn-order-domain.md
├── generation-stats.md                   # Conversion statistics, including skipped resources
├── html/
│   ├── index.html                        # the index, with the model tree
│   ├── graph.html                        # the graph, D3 or Cytoscape.js
│   ├── sparql.html                       # SPARQL over the whole model
│   ├── rdf.html                          # the model as TriG, by named graph
│   ├── model.trig                        # the whole graph
│   ├── style.css                         # Shared stylesheet
│   ├── _assets/                          # scripts, libraries, the pages' data (no icons: meta.linked.archi's)
│   ├── model/payment-platform/           # an ArchiMate model ({notation} is "model")
│   │   ├── model/                        # the model
│   │   ├── element/id-ba-merchant/
│   │   │   ├── index.html
│   │   │   └── facts.ttl
│   │   ├── relationship/id-rel-1/
│   │   ├── graph/semantic/               # a named graph: a provenance record
│   │   └── index.html                    # the model's IRI from before DD-41: a former name
│   ├── bpmn/order-domain/                # a BPMN model
│   │   ├── element/Task_CheckInventory/
│   │   ├── relationship/Flow_1/
│   │   └── view/order-fulfillment/
│   │       ├── index.html                # the diagram inline, each shape a fragment
│   │       ├── facts.ttl                 # the view's statements and its shapes'
│   │       └── diagram.svg               # when view geometry is present
│   └── provenance/                       # runs, converters and source files
│       ├── run/2a58705d/
│       ├── agent/fffb195e/
│       └── source/payment-platform-xml/
```
