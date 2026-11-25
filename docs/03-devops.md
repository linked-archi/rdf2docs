# Build and DevOps

## Build

Requires **JDK 25**, to build and to run: RDF4J 6.x is compiled for Java 25.

Gradle finds the JDK among those installed on the machine but will not download one. If it reports
"No matching toolchain could be found" for a JDK you know is installed in a non-standard location
(Homebrew, for instance), point Gradle at it from your *user* `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

```bash
./gradlew clean test shadowJar
```

Fat jar output: `build/libs/rdf2docs-<version>-all.jar`. The version is `gradle.properties`' `version`; a release
sets it from its tag (`-Pversion=0.2.0`), and `rdf2docs --version` prints it, read from the jar's manifest.

## Run locally

```bash
java -jar build/libs/rdf2docs-0.1.0-all.jar generate \
  --input model.ttl \
  --output-dir output \
  --base https://example.org/la/
```

`playground/run-sample.sh` documents the models of [linked-archi/sample-models](https://github.com/linked-archi/sample-models)
into `playground/out/<name>/`:

```bash
playground/run-sample.sh                     # every sample
playground/run-sample.sh archisurance        # ArchiMate 3.1, BPMN, PlantUML, LeanIX and Backstage in one graph
playground/run-sample.sh vio                 # ArchiMate 3.2, C4, BPMN, LeanIX, Backstage and decisions in one graph
playground/run-sample.sh acme-shop           # ArchiMate 4, drawn in la-gram
playground/run-sample.sh payment-platform    # ArchiMate 3.2, from the ArchiMate Exchange Format
playground/run-sample.sh order-domain        # BPMN, two processes collected as one model's views
playground/run-sample.sh uml-shop            # UML, three PlantUML diagrams sharing their classes
SAMPLE_MODELS=../sample-models playground/run-sample.sh   # from a local checkout
```

`playground/samples.txt` lists the samples: each one's name, file, base IRI, and what the demo's index says of it.
A sample added there is documented by the script and listed by the demo. The script downloads a model into
`playground/models/` the first time (`SAMPLE_MODELS_REF` picks a branch or tag other than `main`). When the
default `java` is older than 25, it runs the Java 25 that macOS's `java_home` finds, and stops with an error when
there is none.

`playground/demo-site.sh` then assembles the demo site from what it generated, into `playground/site/`: a folder
per sample and an index of them, with each one's languages, sources, counts and licence. The demo workflow runs
the two.

The generator accepts Turtle and TriG. A TriG file's named graphs are loaded into the same in-memory repository and
queried together.

## The container image

`ghcr.io/linked-archi/rdf2docs` is Java 25's runtime (Eclipse Temurin) with the jar, run as `rdf2docs`, for
`linux/amd64` and `linux/arm64`. It has no entrypoint of its own, so that a CI job's script runs in its shell.
`RDF2DOCS_JAVA_OPTS` passes options to the JVM, such as `-Xmx4g` for a large model. Each release is tagged with its
version (`0.1.0`), its minor version (`0.1`, the latest of its fixes) and `latest`.

```bash
docker run --rm -v "$PWD:/work" -w /work ghcr.io/linked-archi/rdf2docs:0.1 \
  rdf2docs generate --input model.ttl --output-dir site --base https://example.org/la/
```

On Linux, add `--user "$(id -u):$(id -g)"` so that what it writes is yours rather than root's.

The `Dockerfile` copies a built jar rather than building one, so the image runs the jar the release attaches. By
hand: `./gradlew clean shadowJar && docker build --tag rdf2docs .`.

## Continuous integration and releases

Three GitHub Actions workflows, in `.github/workflows/`:

| Workflow | Runs on | Does |
| --- | --- | --- |
| `ci.yml` | every push to `main`, every pull request | the tests, the jar, and the image built from it and run |
| `release.yml` | a tag `v<major>.<minor>.<patch>` | the jar at the tag's version, attached to a GitHub release with generated notes; the image pushed to GHCR; then the demo site |
| `demo.yml` | each release, and by hand | every sample model's documentation, one folder each, on GitHub Pages |

To release:

1. In `CHANGELOG.md`, turn `## Unreleased` into `## <version> — <date>`.
2. Set `version=<version>` in `gradle.properties`. The release builds the jar at the tag's version whatever this
   says, but a build from source between releases reads it.
3. A new minor or major version: change the image tag the CI templates and examples name, `:0.1` and `0.1.x`, to
   the new one (`grep -rn '0\.1' README.md ci docs/03-devops.md` finds them). A patch version needs no change:
   `:0.1` is the latest 0.1.x, so the pipelines that use the templates get it at their next run.
4. Commit, push to `main`, and wait for CI to pass.
5. Tag and push the tag:

   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```

A sample model needs no release. The demo builds rdf2docs from the revision it runs on and downloads the models
from sample-models' `main`: add the model there, a line for it to `playground/samples.txt` here, push both, and
run the demo by hand. Run by hand, the demo is built from `main`, so it also shows what `main` has that no release
has yet.

Once per repository:

- *Settings → Pages → Source: GitHub Actions*, for the demo site. The demo is published by a release, or by
  `gh workflow run demo.yml` (*Actions → Demo site → Run workflow*).
- A tag rule `v*` under *Settings → Environments → github-pages → Deployment branches and tags*. Pages deploys
  only from `main` until then, and a release deploys the demo from its tag.
- After the first release, the `rdf2docs` package's visibility set to *Public* under the organisation's
  *Packages*, so that anyone can pull the image.

## Documentation in other repositories' pipelines

[`ci/`](../ci/README.md) has the templates a repository holding a model copies to publish its documentation at every
push: a GitHub Actions workflow and a GitLab CI job, each running the image.

## Looking at the site

Open `html/index.html` in a browser: every page, the graph, SPARQL and RDF pages included, works from disk; the
notation's icons come from meta.linked.archi. To serve it — at its base IRI, as published — any static server will
do; the JDK has one:

```bash
jwebserver -d "$PWD/playground/out/acme-shop/html" -p 8000   # http://localhost:8000/
```

## The notation pack

`src/main/resources/notation/` holds the languages the diagrams, icons and graph are drawn with — their Turtle
files, laid out as `https://meta.linked.archi/` is published, with `packs.txt` listing each language's files. It is a
copy of linked-archi-meta's files; refresh it from a checkout of it rather than editing it:

```bash
scripts/sync-notation.sh /path/to/linked-archi-meta
```

For each language it copies the manifest, the ontology, the notation, the presentation contexts and the taxonomy;
for the core, `core-onto`, `core-vis-onto` and `core-notation`. A language added to the script is loaded for a
model whose IRIs are under `https://meta.linked.archi/{language}/`.

No icon is copied: the pages show the notation's glyphs, pictures and decoration symbols at their IRIs, where
meta.linked.archi publishes them. A run reads the decoration symbols there to draw the lines' ends, and asks
whether each picture and glyph is served, leaving out one that is not; without a network it draws the ends from
their built-in geometry, links the pictures unchecked, and the pages show no icons until they are online.

## The libraries the pages carry

`src/main/resources/site/vendor/` holds D3 7.9.0, Cytoscape.js 3.34.3 and Oxigraph 0.5.11, with their licences in
`LICENSES.md`. Oxigraph's web build is an ES module that fetches its `.wasm` file, neither of which loads in a page
opened from disk; it is kept as a classic script, its exports `window.oxigraph`, and its WebAssembly as base64 in
`oxigraph-0.5.11-wasm.js`. To update it, take `web.js` and `web_bg.wasm` from the `oxigraph` npm package, drop the
`export` keywords and the `import.meta.url` default (the page passes the bytes to `initSync`), and set
`window.oxigraph` to its exports, as `LICENSES.md` describes.
