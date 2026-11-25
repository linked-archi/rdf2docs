# CLI usage

The CLI is implemented using **Picocli**.

## Command structure

```
rdf2docs generate [options]
```

## Global help

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar --help
java -jar build/libs/rdf2docs-0.1.0-all.jar --version
java -jar build/libs/rdf2docs-0.1.0-all.jar generate --help
```

In the container image the same command is `rdf2docs`: `docker run --rm ghcr.io/linked-archi/rdf2docs rdf2docs --help`.

## `generate` command

Loads an RDF file into an in-memory repository and generates an AI context Markdown document and/or static HTML pages.

### Required options

| Option | Description |
|---|---|
| `-i, --input <file>` | Input RDF file (Turtle `.ttl` or TriG `.trig`). Format is inferred from the file extension. |
| `-o, --output-dir <dir>` | Output directory. Created if it does not exist. |
| `--base <iri>` | Base IRI of the graph. Used to scope index queries and resolve relative links in HTML pages. |

### Optional options

| Option | Default | Description |
|---|---:|---|
| `--ai-context` | `true` | Generate `ai-context.md` in the output directory. A graph holding more than one model is written as one document per model under `ai-context/`, with `ai-context.md` as the index. |
| `--html-pages` | `true` | Generate `html/index.html` and per-IRI HTML pages in `html/` under the output directory. |
| `--model-id <id>` | — | Model identifier, joined to `--base` with a `/`: the generator then reads only the IRIs under `{base}/{id}`. A converted model's namespace is `{base}{notation}/{modelId}/`, so `--base https://example.org/la/model --model-id payment-platform` reads the ArchiMate model `payment-platform`. |
| `--html-base-url <url>` | — | Base URL where HTML pages are served. Used to generate data model links in the AI context document. Served at the base IRI, it is the base IRI. |
| `--graph-config <file>` | — | YAML file for the graph page: the element types whose nodes start hidden (`excludedNodeTypes`) — relationships are the graph's edges, and every relationship type starts shown — and the groups of its type filter (`filterGroups`), in place of the groups of the types' languages. `playground/config/graph-config.yml` is an example. |
| `--presentation-context <context>` | *(the language's notation)* | The presentation context the diagrams are drawn for when a page opens: the IRI of one of the model's languages' contexts, its local name or its name, case aside — `ArchitectContext`, `Architect`, `https://meta.linked.archi/archimate4/presentation-contexts#ArchitectContext`. A view page offers the others in its *Presentation* list. A name that matches none is reported, and the language's notation is used. |
| `--ns-core <iri>` | `https://meta.linked.archi/core#` | Linked.Archi core namespace (`arch:Element`, `arch:View`, `arch:Folder`, etc.). |
| `--ns-core-vis <iri>` | `https://meta.linked.archi/core-vis#` | Linked.Archi core-vis namespace (`archvis:ArchNode`, `archvis:view`, `archvis:archElement`, etc.). |
| `--ns-archimate <iri>` | `https://meta.linked.archi/archimate#` | ArchiMate element type namespace. |
| `--ns-vocab <iri>` | *(ns-core)* | Vocabulary namespace for `archiUrl` and custom predicates (e.g. `https://example.org/vocab#`). Falls back to `--ns-core` if not set. |

### Boolean option behaviour

Picocli accepts both flag form and explicit assignment:

```bash
--ai-context          # enable
--html-pages=false    # disable
```

## Examples

### Generate both outputs

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input         model.ttl \
  --output-dir    output \
  --base          https://example.org/la/ \
  --ns-vocab      https://example.org/vocab#
```

### With custom namespaces

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input         model.ttl \
  --output-dir    output \
  --base          https://example.org/la/ \
  --html-base-url https://example.org/la/ \
  --ns-vocab      https://example.org/vocab# \
  --ns-core       https://meta.linked.archi/core# \
  --ns-core-vis   https://meta.linked.archi/core-vis# \
  --ns-archimate  https://meta.linked.archi/archimate#
```

### Diagrams for one audience

`acme-shop.ttl` is a [sample model](https://github.com/linked-archi/sample-models/tree/main/archimate4/acme-shop).

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input                acme-shop.ttl \
  --output-dir           output \
  --base                 https://example.org/acme/shop/ \
  --presentation-context ArchitectContext
```

### AI context only

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input model.ttl \
  --output-dir output \
  --base https://example.org/la/ \
  --html-pages=false
```

### HTML pages only

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input model.ttl \
  --output-dir output \
  --base https://example.org/la/ \
  --ai-context=false
```

## Exit codes

- `0`: success
- non-zero: failure (missing input file, invalid parameters, RDF parse error)
