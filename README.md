# rdf2docs

Generates architecture documentation from [Linked.Archi](https://meta.linked.archi/) models in RDF: a static site
with a page at the IRI of every element, relationship and view, the views drawn in their notation, and an AI
context document. It runs from the command line, in a container and in CI, so a model kept in a repository is
documented at every push.

**Demo:** [linked-archi.github.io/rdf2docs](https://linked-archi.github.io/rdf2docs/), the documentation of the
[sample models](https://github.com/linked-archi/sample-models).

## Features

1. **AI Context Document**: Generates a Markdown document optimized for AI chatbot agents to answer architectural questions with concrete IRI references
2. **HTML Pages**: Generates a static HTML page at the IRI of each resource in the graph, so that the site,
   served at the base IRI, answers the IRIs themselves
3. **Diagrams in their notation**: each view drawn in its language's visual notation — shapes, colours, badges,
   line styles and end decorations, from the Linked.Archi notation of ArchiMate 3.2 and 4, BPMN, C4, UML, LeanIX,
   Backstage or Basic — with the view's own look over it, and a switch between the language's presentation
   contexts; zoomed with the wheel or a pinch and panned by dragging
4. **The model tree**: on the left of every page, the model as la-gram's Model Explorer shows it — views,
   elements and relationships by type, or in the model's own folders
5. **Graph**: the architecture as a graph, drawn by D3 with its forces on sliders or by Cytoscape.js with each node
   its notation's figure; filtered by element type, relationship type, model and folder
6. **SPARQL**: queries over the whole model, run in the page, the results linked to the pages of what they name
7. **RDF**: the whole model as TriG, a section per named graph, filtered by graph and searched

The site works served over HTTP and opened straight from disk: its libraries are part of it. The notation's icons,
glyphs and pictures are shown where meta.linked.archi publishes them, at their IRIs; none is copied into the site.
A type's icon is its published picture, else its glyph, else none; a relationship type's, else its whole line drawn
from its notation, with its end decorations.

## Quick start

With Docker — the image has Java and rdf2docs:

```bash
curl -fsSLO https://raw.githubusercontent.com/linked-archi/sample-models/main/archimate4/acme-shop/acme-shop.ttl
docker run --rm -v "$PWD:/work" -w /work ghcr.io/linked-archi/rdf2docs:0.1 \
  rdf2docs generate --input acme-shop.ttl --output-dir site --base https://example.org/acme/shop/
```

Open `site/html/index.html`. With Java 25, the jar attached to each
[release](https://github.com/linked-archi/rdf2docs/releases) does the same:

```bash
java -jar rdf2docs-0.1.0-all.jar generate --input acme-shop.ttl --output-dir site --base https://example.org/acme/shop/
```

## In a pipeline

[`ci/`](ci/) has a GitHub Actions workflow and a GitLab CI job that publish the documentation of a model kept in a
repository on GitHub or GitLab Pages, at every push: copy one, name the model file and its base IRI. Any other CI
runs the image the same way.

## Usage

```bash
rdf2docs generate --input model.ttl --output-dir output --base https://example.org/la/
```

### Options

- `-i, --input`: Input RDF file (Turtle or TriG format)
- `-o, --output-dir`: Output directory for generated files
- `--base`: Base IRI of the graph
- `--ai-context`: Generate AI context document (default: true)
- `--html-pages`: Generate HTML pages (default: true)
- `--presentation-context`: The presentation context the diagrams open in, such as `ArchitectContext` (default:
  the language's own notation)
- `--graph-config`: YAML file for the graph page: the types hidden at first, and the groups of its type filter

All options: [CLI usage](docs/00-cli.md).

## Output

### AI Context Document (`ai-context.md`)

One Markdown document per model, indexed from `ai-context.md`, so each one fits in a context window.
A resource's model is read from its `arch:inModel`, else from the namespace its IRI is in. A single-model
graph is written as one file with no index. Each document contains:

- Instructions for AI assistants on how to use the information
- The **Model**
- A **Views** section listing each view with its contained elements and external links
- **Elements** grouped by their specific type
- **Relationships**, named by their endpoints when they carry no label of their own
- The model's **Documentation** sections, quoted

### HTML Pages (`html/`)

Laid out as the IRI Minting guide has it,
[*Static publishing*](https://meta.linked.archi/docs/guide/iri-minting/#static-publishing):

- One page for each element, relationship, view, model, documentation section and provenance record, at its
  IRI relative to `--base`, with a `facts.ttl` beside it; each concept shown with its type's icon
- A shape is a fragment of its view's page: the diagram is in the page as SVG, and `{view}#node-{id}` opens
  the page at the node
- A part of a resource, such as a property pair or a tag, is shown on its owner's page
- `index.html` listing the resources by folder or by type, in their language's groups (a layer, a domain), each
  group collapsible
- On every page, a header with the Index, Graph, SPARQL and RDF pages and the *Models* and *Folders* menus; on the
  index and the resources' pages, the model tree on the left
- `graph.html`, `sparql.html` and `rdf.html`, and `model.trig`, the whole graph
- `_assets/`: the pages' scripts, the libraries and the data the pages read

To look at it, open `html/index.html`, or serve `html/` — `jwebserver -d "$PWD/output/html"` comes with the JDK.

## Examples

```bash
# The site and the AI context, the diagrams opening for architects
rdf2docs generate -i model.trig -o output --base https://example.org/la/ --presentation-context ArchitectContext

# The AI context only
rdf2docs generate -i model.ttl -o output --base https://example.org/ --html-pages=false
```

`playground/run-sample.sh` documents the [sample models](https://github.com/linked-archi/sample-models) into
`playground/out/`, downloading them on first use: ArchiMate 4 and 3.2, BPMN and UML models, and two case studies,
ArchiSurance and VIO Group, that join several languages in one graph.

## Build from source

Requires JDK 25: RDF4J 6.x is compiled for it. See [Build and DevOps](docs/03-devops.md) if Gradle cannot find
your JDK.

```bash
./gradlew shadowJar          # build/libs/rdf2docs-<version>-all.jar
./gradlew test
```

## Releasing a version

A release is a tag. Pushing `v<major>.<minor>.<patch>` to GitHub builds the jar at that version and attaches it to
a GitHub release, pushes the image `ghcr.io/linked-archi/rdf2docs` tagged `<version>`, `<major>.<minor>` and
`latest`, and republishes the demo site.

1. In `CHANGELOG.md`, turn `## Unreleased` into `## <version> — <date>`.
2. Set `version=<version>` in `gradle.properties`.
3. A new minor or major version: change the image tag the CI templates and examples name, `:0.1` and `0.1.x`, to
   the new one (`grep -rn '0\.1' README.md ci docs/03-devops.md` finds them). A patch version needs no change:
   `:0.1` is the latest 0.1.x.
4. Commit, push to `main`, and wait for CI to pass.
5. Tag and push the tag:

   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```

A sample model needs no release: add it to [sample-models](https://github.com/linked-archi/sample-models) and a
line for it to `playground/samples.txt`, push both, and run the demo by hand (`gh workflow run demo.yml`, or
*Actions → Demo site → Run workflow*). [Build and DevOps](docs/03-devops.md) has the details.

## Documentation

- [Current implementation state](docs/04-current-state.md) — verified behavior, dependencies, resolved issues, limitations, and validation evidence
- [CLI usage](docs/00-cli.md)
- [Architecture and flow](docs/01-architecture.md)
- [Output format reference](docs/02-output-format.md)
- [Features](docs/02-features.md)
- [Build and DevOps](docs/03-devops.md) — building, the container image, releases, and refreshing the notation pack
- [rdf2docs in CI](ci/README.md)
- [Changelog](CHANGELOG.md)

## Licence

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE), which lists what the jar and the sites it
generates carry: D3, Cytoscape.js and Oxigraph, and the Linked.Archi notation (CC BY 4.0).
