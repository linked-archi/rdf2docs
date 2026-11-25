# rdf2docs in CI

Two templates publish the documentation of a model kept in a repository, rebuilt at every push to its main
branch: the site, a page at every IRI of the model, with the AI context document beside it.

| File | For | Publishes on |
|---|---|---|
| [`github/rdf2docs.yml`](github/rdf2docs.yml) | GitHub Actions | GitHub Pages |
| [`gitlab/rdf2docs.gitlab-ci.yml`](gitlab/rdf2docs.gitlab-ci.yml) | GitLab CI, gitlab.com and self-managed | GitLab Pages |

Each runs the container image `ghcr.io/linked-archi/rdf2docs`, so the runner needs no Java. Set two values in the
file:

| GitHub | GitLab | Value | Example |
|---|---|---|---|
| `MODEL` | `RDF2DOCS_MODEL` | The model file, Turtle or TriG, from the repository's root | `architecture/model.trig` |
| `BASE` | `RDF2DOCS_BASE` | The base IRI the model's resources are named under | `https://example.org/la/` |
| `OPTIONS` | `RDF2DOCS_OPTIONS` | More options, empty by default ([CLI usage](../docs/00-cli.md)) | `--presentation-context ArchitectContext` |

The model can be one a modeller saves in the repository, or one an earlier step of the same pipeline writes, such
as a conversion from another format.

**Setting it up on GitHub:** copy `github/rdf2docs.yml` into `.github/workflows/`, set the values, and choose
*GitHub Actions* as the source under *Settings → Pages*. Push; the site is at the address the workflow's *deploy*
job shows.

**Setting it up on GitLab:** copy `gitlab/rdf2docs.gitlab-ci.yml` beside `.gitlab-ci.yml`, include it from there
as the file's header shows, and set the values. Push; the site is at the address under *Deploy → Pages*.

**The version.** The templates run `:0.1`, the latest 0.1.x release: its fixes, never a change of behaviour.
`:0.1.0` pins one release; `:latest` follows every release.

## Any other CI

The image runs anywhere a container does. `rdf2docs` is on its `PATH`, and it has no entrypoint of its own:

```bash
docker run --rm -v "$PWD:/work" -w /work ghcr.io/linked-archi/rdf2docs:0.1 \
  rdf2docs generate --input model.ttl --output-dir site --base https://example.org/la/
```

`site/html/` is the site: copy it to any static host. Every link in it is relative, so it works under any path,
and opened from disk. Without Docker, the jar attached to each [release](https://github.com/linked-archi/rdf2docs/releases)
runs on Java 25: `java -jar rdf2docs-0.1.0-all.jar generate …`.

The pages show the notation's icons from <https://meta.linked.archi/>, and a run asks it which pictures it
serves. Without access to it, the run still succeeds, and the pages show no icons.
