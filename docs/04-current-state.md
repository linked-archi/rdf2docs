# Current State

This page describes the state of `rdf2docs` after validating the issues recorded in
[`todo/UPSTREAM-ISSUES.md`](../todo/UPSTREAM-ISSUES.md). It distinguishes current behavior from
legacy converter behavior and records what has been implemented, verified, and left open.

**Status date:** 2026-11-25  
**Application version:** `0.1.0`  
**Implementation status:** present in the current working tree; not necessarily released or committed

## Summary

`rdf2docs` currently:

- reads Turtle and TriG datasets produced by the current Linked.Archi converters;
- generates static, dereferenceable HTML pages and `facts.ttl` sidecars, laid out as DD-41 has it: a page
  for each resource in its own right, chosen by type, a shape as a fragment of its view's page, a part on
  its owner's page;
- shows where each resource came from, read from the converters' PROV statements;
- draws each view in its language's visual notation, from a notation pack of the Linked.Archi languages carried
  in the jar, with a switch between the language's presentation contexts, zoomed and panned in its viewport;
- shows the model tree on the left of the index and every resource's page, built as la-gram's Model Explorer is,
  and the *Models* and *Folders* menus in every page's header;
- shows the notation's icons, glyphs and pictures where meta.linked.archi publishes them: none is copied;
- shows each concept with its type's icon, lists by folder or by type in collapsible, sortable, filterable groups;
- writes a graph page drawn by D3 or Cytoscape.js, a SPARQL page that runs Oxigraph in the browser, an RDF page
  showing the model's TriG by named graph, and `model.trig`; every page works served or opened from disk;
- generates AI context Markdown partitioned by model for multi-model datasets, the model read from the
  triples;
- isolates per-resource HTML failures instead of aborting an entire run;
- reports skipped resources and AI-context partitions in `generation-stats.md`;
- uses RDF4J 6.0.0, matching `converters/linked-archi-converters`;
- requires JDK 25 to build and run;
- ignores `bin/`, which is generated IDE output rather than source.

The tool still identifies itself as version `0.1.0`. The changes documented here are unreleased
working-tree changes until they are committed and included in a release.

## Build and runtime baseline

| Concern | Current state |
|---|---|
| Java | JDK 25 for both build and runtime |
| Kotlin | 2.3.0 |
| Gradle wrapper | 9.6.1 |
| Shadow plugin | `com.gradleup.shadow` 9.3.1 |
| RDF4J | 6.0.0, managed through the RDF4J BOM |
| CLI library | Picocli 4.7.7 |
| Supported RDF input | Turtle (`.ttl`) and TriG (`.trig`) |
| Fat JAR | `build/libs/rdf2docs-0.1.0-all.jar` |

Build with:

```bash
./gradlew clean build
```

Run with a Java 25 runtime:

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input model.trig \
  --output-dir output \
  --base https://example.org/architecture/
```

RDF4J 6.x classes use Java class-file version 69. Running the fat JAR with Java 21 fails with
`UnsupportedClassVersionError`; this is expected rather than a packaging defect.

Gradle does not download a JDK automatically. For a Homebrew JDK that Gradle cannot discover, set the
following in the user-level `~/.gradle/gradle.properties` rather than in the repository:

```properties
org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

## Current output behavior

### AI context

For a dataset containing one model, the generator writes one document:

```text
output/
└── ai-context.md
```

For a dataset containing multiple models, it writes an index plus one partition per model:

```text
output/
├── ai-context.md
└── ai-context/
    ├── bpmn-order-fulfillment.md
    ├── model-payment-platform.md
    └── plantuml-shop.md
```

`ai-context.md` remains the stable entry point. In a multi-model run it lists each partition and its
view, element, and relationship counts.

Each partition is ordered for consumption rather than alphabetically across all resource types:

1. the model;
2. views;
3. elements, grouped by specific RDF type;
4. relationships, grouped by specific RDF type;
5. the model's documentation sections, quoted.

Provenance records and former names are not in it.

Generic types such as `arch:Element`, `arch:ModelConcept`, and `arch:QualifiedRelationship` are used
only when no more specific type is available. An unlabeled relationship is headed by its endpoints,
for example `Customer → Web Shop`, instead of by an opaque synthesized IRI segment.

A resource is in the partition of the model it states with `arch:inModel`, else of the model whose
namespace (`vann:preferredNamespaceUri`) its IRI is in. The partition is named by that namespace below the
base. It was the IRI's path less its last two segments, which put each Backstage element, whose id has
three segments, in a partition of its own: a graph merged from five converted models gave 47 partitions,
and now gives 5. When `--model-id` has already folded the model into `--base`, all matching resources
remain in one document.

### HTML pages

HTML output remains under `output/html/`:

```text
output/html/
├── index.html
├── style.css
└── <relative-resource-IRI>/
    ├── index.html
    ├── facts.ttl
    └── diagram.svg      ← a view with geometry; the same drawing is inline in index.html
```

The directory path is the resource IRI relative to `--base`, decoded. It is deliberately not shortened or
hashed: the page URL is intended to be the resource IRI.

A page is for a view, relationship, element, model, documentation section, provenance record or former
name, chosen by type ([the rules](02-output-format.md#which-resources-get-a-page)). A shape has no page: its
view's page draws the diagram inline and gives each shape its fragment as `id`, so `{view}#node-{id}` opens
the page at the shape. A part has no page either, and is shown on its owner's page.

Generation uses parallel batches. Each batch opens its own RDF4J repository connection because a
connection is not thread-safe and must not be shared by concurrent coroutines. The shared label cache
is a `ConcurrentHashMap`.

### Failure isolation and reporting

A resource that cannot be represented as a filesystem path no longer terminates the run. The primary
known case is one IRI path segment longer than 255 UTF-8 bytes, the portable limit used by the
generator.

For such a resource, `rdf2docs` now:

1. logs a warning containing the IRI and reason;
2. skips only that resource page;
3. continues generating all other pages;
4. exits successfully if no run-level error occurs;
5. lists the resource under **Skipped resources** in `generation-stats.md`.

The generator does not shorten the path because that would break the page-URL-to-resource-IRI
identity. The resource remains present in AI context and may remain linked from other generated pages;
those links cannot resolve until the source IRI is shortened upstream.

`generation-stats.md` currently reports:

- elements extracted;
- views extracted;
- relationships extracted;
- HTML pages generated;
- resources skipped;
- AI context partitions;
- phase timings and total duration.

## RDF4J alignment and fat-JAR services

`rdf2docs` previously used RDF4J 5.1.0 while the current converter monorepo used 6.0.0. It now consumes
RDF4J 6.0.0 from the BOM, so the generator and current converters agree on the RDF version they can
read and write, including RDF 1.2 support introduced in RDF4J 6.

The legacy standalone converter repositories are still on other versions:

| Component | RDF4J version |
|---|---:|
| `converters/linked-archi-converters` | 6.0.0 |
| legacy `converters/archimate` | 5.3.1 |
| legacy `converters/bpmn` | 5.2.2 |
| legacy `converters/plantuml` | 5.2.2 |
| `generators/rdf2docs` | 6.0.0 |

The fat JAR contains explicit service-provider files for RDF4J Turtle, TriG, and N-Triples parser and
writer factories under `src/main/resources/META-INF/services/`. These declarations are required
because shading discards RDF4J's JPMS `module-info` service declarations. `mergeServiceFiles()` alone
cannot restore declarations that are not supplied as ordinary service files.

Without the explicit files, the classes are present in the fat JAR but RDF4J's registry fails with
errors such as:

```text
UnsupportedRDFormatException: Did not recognise RDF format object Turtle
```

## `bin/` directory

`bin/` is not a second source tree and is not used by Gradle. It is generated by the Eclipse JDT
language server used by Java support in VS Code-based editors. JDT imports the Gradle source sets and,
because it does not compile Kotlin, copies `.kt` files as resources into `bin/main` and `bin/test`.
The result looks like a duplicate of `src/` because it is one.

Current policy:

- `src/` is the source of truth;
- Gradle output is under `build/`;
- `bin/` may be deleted at any time;
- `bin/` is listed in `.gitignore` because the language server can recreate it;
- changes must never be made in `bin/`.

## Validated issue status

The original issue report mixed defects in `rdf2docs` with behavior from the legacy standalone
converters. The current status is:

| Issue | Current conclusion |
|---|---|
| 1. Whole run aborts on over-long IRI segment | Confirmed against pristine `rdf2docs` HEAD; fixed with per-resource failure isolation and per-batch connections |
| 2. PlantUML relationship ids use endpoint pair only | True of legacy standalone converter; current converter adds label or relationship kind as a discriminator |
| 3. Distinct PlantUML messages collapse | Cross-file collision was possible in the legacy converter; fixed by the current relationship-id discriminator |
| 4. State transitions emitted as associations and `[*]` untyped | True of legacy converter; current converter emits `uml:Transition`, `uml:Pseudostate`, and `uml:FinalState` |
| 5. BPMN data output association lacks source | True of legacy converter; current converter resolves implicit output source and input target from containment |
| 6. RDF4J versions are split | Confirmed, but there were four versions rather than two; `rdf2docs` is now aligned with the current converter monorepo |
| 7. AI context is too large | Confirmed; fixed by per-model partitioning and a stable index |

See [`todo/UPSTREAM-ISSUES.md`](../todo/UPSTREAM-ISSUES.md) for the evidence and corrections behind
each conclusion.

## Remaining limitations and open work

### Converter parts with IRIs

Resolved on 2026-11-19, with the converters' DD-41 change. Pages are chosen by type, so a part gets none and
is shown on its owner's page, and a property pair with an IRI is on the *Properties* tab again. ArchiSurance
gives 326 pages, the 325 it gave before the converters named their parts plus its former model IRI's page;
see [`todo/CONVERTER-PART-IRIS.md`](../todo/CONVERTER-PART-IRIS.md).

### SHACL result truncation

RDF4J's SHACL engine stops collecting results for one constraint after 1,000 results. The current
converter validator neither raises that limit nor reports that it was reached. Validation totals at or
above that boundary must therefore be treated as lower bounds.

This is a converter validation concern, not an `rdf2docs` generation failure. It is the one item from
the validation work still worth raising upstream.

### Skipped-resource links

Failure isolation allows a run to complete, but references to a skipped resource are not rewritten or
removed. They intentionally remain the original IRI and may point to a page that could not be created.
The source converter or source model must mint a shorter IRI to make the page dereferenceable.

### The notation's gaps and choices

Recorded 2026-11-25:

- **BPMN's flows have no notation.** linked-archi-meta's `bpmn-notation.ttl` describes the BPMN elements but none
  of its relationships, so a sequence or message flow is drawn as a plain line, with no arrowhead. The notation is
  meta's to extend; the diagram follows it.
- **A BPMN event's name is inside its circle,** wrapped to its width, since core-vis has no placement below a
  shape; a 36-pixel event's name wraps a few characters a line.
- **Text is measured, not laid out by a browser.** A label in the notation's own font is wrapped by la-gram's
  estimate, 0.55em a character; one in a font a view sets is measured with the JVM's font metrics. A browser's
  metrics can differ by a few pixels, so a line may wrap one word earlier or later than in la-gram.
- **A picture in a node** (`arch-vis:image`) is fitted to its box, since its own size is not known without loading
  it.
- **Presentation contexts.** ArchiMate's contexts all use its standard notation set, so a view draws alike in each
  unless the model gives an audience names, pictures, label expressions or looks of its own; the page then has one
  drawing for every context.
- **Icons** are what meta.linked.archi publishes for a type: its picture, else its glyph alone, else none. A
  picture is the whole figure, so at 32 × 16 it reads as a bar in the type's colours with a small glyph. ArchiMate
  4's types have pictures; BPMN's tasks and gateways a glyph only; BPMN's events neither, and have no icon. Basic
  publishes pictures of its relationship types, which are shown; ArchiMate's and BPMN's publish none, so each is its
  line drawn from its notation, with its end decorations — BPMN's flows, which have no notation, plain lines.
- **A picture meta.linked.archi does not serve is passed over.** ArchiMate 3.x's ontologies named pictures under
  `https://meta.linked.archi/archimate/v3/img/`, which it answers 404 for; the eleven of the relationship types were
  reached, since 3.2's notation gives a relationship no picture. linked-archi-meta was corrected on 2026-11-25 —
  the element types' pictures are those under `archimate3/icons/` its notation gives, the relationship types have
  none — and the pack refreshed: a 3.2 relationship is drawn as its line, as ArchiMate 4's is. The check
  stays, for any other; it costs a run about 2.5 s (one `HEAD` request a picture or glyph).
- **Offline**, the pages show no icons, and a run links the pictures unchecked and draws the lines' end decorations
  from their built-in geometry rather than their published symbols.
- **Cytoscape's PNG export** cannot include pictures from another site, which a browser keeps out of a canvas:
  with meta.linked.archi's pictures on its nodes, *Export PNG* says so rather than saving.

### Version and release state

The version is `0.1.0`, in `gradle.properties`; nothing has been released yet. A release is a tag `v<version>`
pushed to [github.com/linked-archi/rdf2docs](https://github.com/linked-archi/rdf2docs): the release workflow
builds the jar at the tag's version, attaches it to a GitHub release, pushes the image
`ghcr.io/linked-archi/rdf2docs` and publishes the demo site ([Build and DevOps](03-devops.md)). The demo site and
`playground/run-sample.sh` read [linked-archi/sample-models](https://github.com/linked-archi/sample-models).

### Legacy converters

Only `converters/linked-archi-converters` reflects the corrected PlantUML and BPMN behavior described
above. Running the legacy standalone converter repositories can still reproduce issues 2–5 and can
produce graphs using older RDF4J versions.

## Verification performed

The current working tree was verified with:

1. a clean Gradle build and test run under JDK 25;
2. a multi-model TriG generation run;
3. a Turtle generation run;
4. a single-model run using `--model-id`;
5. a synthetic 314-byte IRI segment reproducing the original path failure;
6. inspection of the fat JAR to confirm RDF4J 6.0.0 and the explicit Rio service providers.

Observed results:

- clean build and tests completed successfully;
- Turtle and TriG inputs completed successfully;
- multi-model AI context produced an index and model partitions;
- single-model AI context remained one `ai-context.md` file;
- the over-long resource was the only skipped resource in the synthetic run;
- the process completed successfully and all representable pages were written;
- the fat JAR registered Turtle and TriG parsing and Turtle sidecar writing correctly.

No new unit tests were added as part of the validation work. The existing test suite passed, and the
new behavior was exercised through the end-to-end runs above.

The DD-41 work of 2026-11-19 was verified with:

1. `StaticPublishingTest` and `PagePathsTest`, which run the generator on a graph cut down to one of each
   kind of thing: which resources get a page, the shape fragments, the bend point order, the parts on their
   owner's page, provenance, the AI context partitions, and the IRIs that cannot have a page;
2. the converters' playground models, converted by the converters' DD-41 build: `payment-platform.xml`
   gives 30 pages and ArchiSurance 326, every element, relationship and view of ArchiSurance has a page
   and none of its shapes does, and `id-ba-merchant`'s page lists `leanixUrl` and `costCenter`;
3. a link check over the sites generated from the ArchiMate, BPMN, LeanIX, Backstage, Structurizr and
   PlantUML playground models: every internal link opens a file, every fragment has its `id`, and no page
   has an `id` twice;
4. a graph merged from five converted models, whose AI context has one partition per model.

The notation, graph, SPARQL and RDF work of 2026-11-25 was verified with:

1. `NotationTest`: template expressions, a notation's own shapes and their fallback, label wrapping and badge
   placement, the notation lookup (a language's notation, the core's for a core relationship, a superclass's),
   the presentation contexts offered, label expressions, a view drawn in its notation with its own look and its
   lines' published end symbols, a context that draws a view differently as a second drawing with the fragments
   on the shown one only, and the data pages' files; with `StaticPublishingTest`, whose bend point test now reads
   the line's points and checks its ends docked on the shapes, and whose tab names carry their counts;
2. the playground models — the la-gram shop (ArchiMate 4), ArchiSurance and ArchiMetal (ArchiMate 3.2), BPMN,
   LeanIX, Backstage, Structurizr (C4) and two PlantUML (UML) models — each generated with its language's pack, and
   each site link-checked: every internal link, icon and script opens a file, every fragment has its `id`, no page
   has an `id` twice (ArchiMetal: 1,417 pages, 69,583 links, generated in 3.2 s);
3. each page opened in Chrome from disk, with no script error: the views of the shop, ArchiSurance and BPMN, an
   element, the index, the graph with D3 and with Cytoscape.js, SPARQL (ArchiMetal's 45,626 statements loaded in
   under 0.2 s) and RDF.

The model tree, the header's menus, the icons from meta.linked.archi and the diagram's zoom and pan (also
2026-11-25) were verified with `NotationTest` (a type's icon: its published picture, else its glyph, else none; a
relationship's line, drawn character for character as la-gram draws it; the tree's data; every page with the tree's panel and the menus; no icon copied into the site), the
playground sites link-checked again, and Chrome driving a view's diagram: five wheel steps zoom to 246%, a drag
pans it and opens nothing, the page's view is selected in the tree and the concepts it draws are marked.

## Sources of truth

Use these files when behavior and documentation disagree:

| Topic | Source |
|---|---|
| Dependency and toolchain versions | `build.gradle.kts`, `gradle.properties` |
| HTML generation and failure handling | `src/main/kotlin/archi/linked/rdf2docs/HtmlPageGenerator.kt` |
| AI context partitioning and ordering | `src/main/kotlin/archi/linked/rdf2docs/AiContextGenerator.kt` |
| Statistics and CLI orchestration | `src/main/kotlin/archi/linked/rdf2docs/GenerateCommand.kt`, `GenerationStats.kt` |
| RDF4J service registration | `src/main/resources/META-INF/services/` |
| The notation: lookup, geometry, drawing | `src/main/kotlin/archi/linked/rdf2docs/notation/` |
| The notation pack | `src/main/resources/notation/`, refreshed by `scripts/sync-notation.sh` |
| The graph, SPARQL and RDF pages | `GraphPageGenerator.kt`, `DataPagesGenerator.kt`, `src/main/resources/site/js/` |
| The model tree and the header | `TreeDataGenerator.kt`, `PageLayout.kt`, `src/main/resources/site/js/tree.js` |
| The diagram's zoom and pan | `src/main/resources/site/js/diagram.js` |
| Detailed issue evidence | `todo/UPSTREAM-ISSUES.md` |
| User-facing output contracts | `docs/02-output-format.md` |
