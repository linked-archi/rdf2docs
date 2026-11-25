# Converter parts now have IRIs

Status: **done** 2026-11-19, with the converters' DD-41 change. Found 2026-11-13. See *Resolution* below.

`converters/linked-archi-converters` no longer writes blank nodes (its 1.3.0 changelog: *No blank nodes:
every part of a resource has an IRI*). Every node that exists only as part of another resource is named
under it:

| Part | IRI |
|---|---|
| folder entry, `schema:ListItem` | `{folder}/item/{digest}` |
| style, `archvis:Style` | `{node}/style`, `{link}/style` |
| route cell, the `rdf:List` of `archvis:points` | `{link}/points/{i}` |
| route point, `archvis:Point` | `{link}/point/{i}` |
| property pair, `schema:PropertyValue` | `{owner}/property/{digest}` |
| BPMN extension group, Backstage link and status item, LeanIX tag and subscription | `{owner}/{kind}/{digest}` |

The pattern is documented in the converters' `docs/architecture/iri-path-shape.md`, *Parts of a resource*.
rdf2docs was written for blank nodes in those places, and two things break.

## What happens

rdf2docs 0.1.0 (`build/libs/rdf2docs-0.1.0-all.jar`, built from `d790a9d`), run with `--base
https://example.org/la/` on the converters' playground ArchiMate models, converted before and after the
change:

| Input | Pages before | Pages after | The extra pages |
|---|---:|---:|---|
| `payment-platform.xml` | 29 | 52 | 12 property pairs, 11 folder entries |
| `archisurance.xml`, with views | 325 | 670 | 303 folder entries, 42 route cells |

And the element `id-ba-merchant`, which has two property pairs (`leanixUrl`, `costCenter`), showed both on
its page before and shows neither now.

### 1. A page for every part

`HtmlPageGenerator.querySubjects` gives a page to every IRI subject under `--base`, except those typed
`archvis:ArchNode`, `archvis:Node`, `archvis:Link`, `archvis:Point`, `archvis:Style` or `arch:Folder`. A
blank node never passed `FILTER(isIRI(?subject))`; a part now does, unless its type is on that list. Styles
and ArchiMate's route points are; folder entries, pairs, route cells and the other parts are not, and
neither are BPMN's route points, which have no type (not measured).

Two ways to fix it, not yet decided:

- **Extend the exclusions:** `schema:ListItem`, `schema:PropertyValue`, `bs:Link`, `lmm:Tag`,
  `lmm:Subscription`, and an untyped subject of `rdf:first`, `rdf:rest` or `archvis:point-x`. Small, but
  untyped parts — a BPMN extension group, a Backstage status item — and any kind a converter adds later get
  pages again.
- **Page what is a resource in its own right:** a model, a model concept or a view (`arch:Model`,
  `arch:ModelConcept`, `arch:View`), and the provenance nodes paged today. Holds for any part, but it changes
  which resources get a page, so it is a decision of its own.

Not by the IRI's path: the converters' documentation asks consumers to read identity from triples, not from
the shape of an IRI.

### 2. Property pairs missing from the page

`HtmlPageGenerator.generateResourceDir` reads the objects of `schema:additionalProperty` with
`as? org.eclipse.rdf4j.model.BNode`, so a pair with an IRI is dropped. The same statements are filtered out
of the main table, so the pair is shown nowhere. Reading them as `Resource` fixes it.

## Not affected

- `ai-context.md`: the same before and after for `payment-platform.xml`; it names no part IRI.
- ArchiMate's styles and route points get no page; their types are already excluded.

Diagram rendering and the other notations were not compared.

## Done when

- The two playground models, converted by linked-archi-converters 1.3.0, give the page sets they gave
  before — 29 and 325 pages, the provenance run's page aside, since its IRI is new each run — and
  `id-ba-merchant`'s page lists `leanixUrl` and `costCenter`.
- A test pins both: a part of each kind gets no page, and a pair with an IRI is shown on its owner's page.

## Resolution

DD-41 settled the open question: a page for what is a resource in its own right, chosen by type. The rules
are in `docs/02-output-format.md`, *Which resources get a page*; the user chose to keep pages for the model,
documentation sections and provenance records as well as for elements, relationships and views.

Measured with the converters' DD-41 build on 2026-11-19, `--base https://example.org/la/`:

| Input | Pages | What they are |
|---|---:|---|
| `payment-platform.xml` | 30 | the 29 above, plus the page of the model's IRI from before DD-41, which says what replaced it |
| `archisurance.xml`, with views | 326 | the 325 above, plus the same; 122 elements, 178 relationships and 17 views, and none of the 237 nodes and 199 connections |

`id-ba-merchant`'s page lists `leanixUrl` and `costCenter`. `StaticPublishingTest` pins both points of *Done
when*: no shape, part, folder or route cell gets a directory, and a pair with an IRI is shown on its owner's
page.

