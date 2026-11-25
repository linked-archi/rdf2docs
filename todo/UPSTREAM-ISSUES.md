# Upstream issues — validated

Defects found while adopting the converters and `rdf2docs` against a real multi-notation graph, each
one checked against the source it accuses before being raised.

**Five of the seven do not need raising.** Four (2–5) were already fixed in
`converters/linked-archi-converters`; the write-ups described the *legacy standalone* converter repos
(`converters/plantuml`, `converters/bpmn`, `converters/archimate`), which are a previous generation of
the same tools. Two (1, 7) were real and were `rdf2docs`' own, so they are fixed here rather than
raised anywhere. One (6) was real but misdescribed, and is now closed for this tool. One item at the
end is still open.

Organisation-specific names, host names and identifiers are masked throughout — the technical shape of
each example is preserved, the content is not real.

| # | Claim | Status |
|---|---|---|
| 1 | `rdf2docs` aborts the whole run on an over-long IRI path segment | **Confirmed, reproduced, fixed in `rdf2docs`** |
| 2 | PlantUML mints relationship IRIs by concatenating endpoint ids | Fixed upstream; write-up partly inaccurate |
| 3 | Two distinct PlantUML messages collapse onto one IRI | Fixed upstream; write-up partly inaccurate |
| 4 | State-diagram transitions emitted as `uml:Association`, `[*]` untyped | Fixed upstream |
| 5 | `bpmn:dataOutputAssociation` emitted with no `arch:source` | Fixed upstream; write-up understated it |
| 6 | The tool family is split across rdf4j versions | **Confirmed, but not as described; fixed for `rdf2docs`** |
| 7 | `ai-context.md` is too large to use as an AI context document | **Confirmed, fixed in `rdf2docs`** |
| — | rdf4j's SHACL engine truncates at 1,000 results per constraint, silently | **Open — worth raising** |

Masking key used below:

| Real | Masked as |
|---|---|
| graph base IRI | `https://graph.example.org/` |
| model identifiers | `model-a`, `web-frontend`, … |
| element / relationship ids | kept structurally, content generic |
| internal host names | `git.example.org`, `wiki.example.org` |

---

## 1. `rdf2docs` aborted the whole run on an over-long IRI path segment

**Component:** `generators/rdf2docs` 0.1.0
**Severity:** high — one resource killed the entire generation run
**Status:** confirmed, reproduced deterministically, **fixed**

### What happened

`HtmlPageGenerator` derives an output directory per resource by stripping `--base` from the IRI and
using the remainder as a filesystem path. When any single IRI path segment exceeded the filesystem
limit for one path component, the write threw and the run aborted.

Reproduced against a merged four-model graph with one synthetic 314-byte relationship id — the same
length as the real case — on the pre-fix build:

```
java.io.FileNotFoundException: out/html/plantuml/shop/relationship/<314-byte segment>/facts.ttl (File name too long)
    at java.base/java.io.FileOutputStream.open0(Native Method)
    at kotlin.io.FilesKt__FileReadWriteKt.writeText(FileReadWrite.kt:141)
    at archi.linked.rdf2docs.HtmlPageGenerator.generateResourceDir(HtmlPageGenerator.kt:130)
    at archi.linked.rdf2docs.HtmlPageGenerator$generate$1$1$jobs$1$1.invokeSuspend(HtmlPageGenerator.kt:61)
```

Exit code 1, with 161 files already written — an output directory left in a partial state that looks
plausible and is incomplete. The line numbers in the original report were exact.

The cascade the report describes is also real, and is visible in the code rather than in that trace:
generation ran in coroutines over the caller's single `SailRepository` connection inside
`repository.connection.use { … }`, so the first failure propagates out of `awaitAll`, `use` closes the
connection, and every job still in flight then fails with `IllegalStateException: Connection has been
closed` from wherever it happened to be. It did not appear in the small reproduction because the whole
graph fitted in one batch; on a 15,000-resource graph it is what produces the suppressed exceptions in
the original report. Sharing one connection was independently wrong: an rdf4j connection is not
thread-safe.

### One correction to the report

The limit is per path *component* and is counted in bytes — that part is right, and worth keeping,
because non-ASCII labels reach it sooner than they look like they should. But it is not 255 bytes
everywhere: it is 255 bytes on ext4 and overlayfs, and 255 UTF-8 *characters* on APFS. The fix uses
255 bytes as the portable floor — a segment that fits it fits everywhere — which matters for output
generated on one machine and served from another.

### What was done

Option 1 from the report, which is also the option it preferred. Each resource is written
independently and each batch gets its own connection, so no single failure can abort the run or close a
connection another coroutine is reading from. Unwritable resources are counted, logged with the reason,
and listed under *Skipped resources* in `generation-stats.md`:

```
| IRI | Reason |
| `https://…/relationship/https_git.example.org_…_beta-endpoint` | IRI path segment is 314 bytes, over the 255-byte filesystem limit for one directory name |
```

Option 2 — shortening segments deterministically — was rejected for the reason the report gives: the
URL of a page equalling the IRI of the resource is the property the output exists to have. A skipped
resource still has an entry in the AI context document and is still linked from other pages, so those
links resolve to nothing until the IRI is shortened upstream. That is the honest failure mode, and it
is now reported rather than silent.

---

## 2. PlantUML relationship IRIs — fixed upstream

**Component:** `converters/plantuml` (legacy standalone repo)
**Status:** fixed in `converters/linked-archi-converters`; nothing to raise

The claim holds for the legacy repo. `PlantUmlParser.kt:109-111` (entity diagrams) and `:154-156`
(sequence diagrams) both mint the local id from the ordered endpoint pair:

```kotlin
val baseId = "${srcId}__${tgtId}"
val count = relCounter.merge(baseId, 1, Int::plus)!!
val relId = if (count == 1) baseId else "${baseId}_$count"
```

The current converter adds a third segment, so endpoints no longer identify the edge on their own
(`converter-plantuml/.../PlantUmlParser.kt:414-423`):

```kotlin
val discriminator = label?.let { labelSlug(it) } ?: relType.idKind()
val baseId = "${srcId}__${tgtId}__${discriminator}"
```

That is the report's own suggested direction — keep the readable form, stop letting the endpoint pair
be the identity — taken without resorting to a hash.

Two details of the write-up were wrong and should not be repeated if any of this is ever quoted:

- **There is no rule replacing `:` and `/` with `_`.** `slugify` is an allow-list that maps everything
  outside `[a-zA-Z0-9_.-]` to `_`, then collapses runs and trims. So a URL-shaped participant becomes
  `https_git.example.org_group-one_…`, not one underscore per punctuation character. The over-long
  segment is real; the mechanism described for it is not.
- **The suffix is `_N`, not `_2`** — it is the occurrence count. And the counter is local to one parse
  call, so it is scoped per file while the IRI namespace is per model. That mismatch is what made
  cross-view collisions possible at all, which matters for issue 3.

---

## 3. Two distinct PlantUML messages collapsing onto one IRI — fixed upstream

**Component:** `converters/plantuml` (legacy standalone repo)
**Status:** fixed in `converters/linked-archi-converters`; nothing to raise

Same minting strategy, and the current converter's own docstring names this as the defect the third id
segment was introduced to fix — *"a call in one view and a reply in another collided on one resource
and accumulated both labels and both `uml:messageSort` values"*.

`umlsh:MessageShape` (`uml-shapes.ttl:453`) does constrain `uml:messageSort` to exactly one value, so
the violation the report saw was reported for the right reason, and the diagnosis — two interactions
merged into one graph node — was correct.

Three corrections:

- **A call and its reply between the same two participants do not normally collide.** `A -> B` then
  `B --> A` have reversed endpoints, so they mint different ids either way. The collision needs the
  *cross-file* setup: the counter resets per file while the namespace is per model, so the same
  ordered pair drawn in two views of one model mints one IRI. That is a narrower and more surprising
  bug than the report states, and worth stating precisely.
- **The `_N` suffix does apply to messages**, using the same three lines as entity diagrams. Within one
  `.puml` two arrows with the same ordered pair do not collapse.
- **The legacy converter emits no `uml:messageSort` at all**, and the `urd:` prefix in the report —
  `uml/reference-data#` — is a retired namespace. `messageSort` exists only in the current converter,
  which emits the published individuals in `uml/onto#`. The `urd:` form in the example turtle would
  have produced terms nothing defines.

---

## 4. State-diagram transitions and `[*]` — fixed upstream

**Component:** `converters/plantuml` (legacy standalone repo)
**Status:** fixed in `converters/linked-archi-converters`; nothing to raise

Confirmed for the legacy repo: `linkToRelType` takes no diagram type, so an undecorated `-->` falls
through to `RelType.ASSOCIATION`, and `[*]` is dropped as an empty-named leaf and then reappears as an
endpoint through the emitter's mint-on-demand fallback, with no `rdf:type` at all. The `.puml` in the
report is correct — `[*]` is *the* way to write an initial or final state.

The current converter does exactly what the report suggests:

| Concern | Current behaviour |
|---|---|
| transition type | `RelType.STATE_TRANSITION` → `uml:Transition`, already an `arch:QualifiedRelationship` upstream |
| `[*]` as source | `uml:Pseudostate` + `uml:pseudostateKind uml:InitialPseudostate` |
| `[*]` as target | `uml:FinalState` |

Retyping removes `umlsh:AssociationShape` from scope entirely, which is what made the six violations
go away: `uml:State` is a `Vertex`, not a `Classifier`, so an association endpoint constraint could
never have been satisfied by a correct state diagram. `umlsh:TransitionShape` constrains only
`uml:transitionKind` and says nothing about endpoints, leaving just the core source/target contract.

One correction: in the legacy repo the emitted class is `puml:Association`, not `uml:Association` —
its default `nsPuml` is `https://meta.linked.archi/plantuml#`. The `uml:` reading applies to the
current converter, whose default is `uml/onto#`.

---

## 5. `bpmn:dataOutputAssociation` with no `arch:source` — fixed upstream

**Component:** `converters/bpmn` (legacy standalone repo)
**Status:** fixed in `converters/linked-archi-converters`; nothing to raise

The structural cause in the report is exactly right: BPMN writes one end of a data association as a
child element and leaves the other to containment, so there is no `sourceRef` to read and the generic
text-reference path resolves one endpoint and leaves the other empty.
`core-shapes#QualifiedRelationshipShape` does require exactly one `arch:source` pointing at an
`arch:ModelConcept`, as quoted.

The report **understates** it in one way and overstates it in another:

- `dataInputAssociation` is not just "the mirror shape", it has the mirror *defect*: it nests only
  `sourceRef`, so it was emitted with a source and no target. Both directions were broken.
- In the legacy repo the emitter writes `arch:relSource`/`arch:relTarget`, not `arch:source`/
  `arch:target`, so those resources never engaged `QualifiedRelationshipShape` at all. The three
  violations counted must have come from a build already using the current predicate names.

The current converter fills the implicit end at parse time from the enclosing flow node, for both
directions, and only when the file is silent — an explicit `sourceRef` on a `dataOutputAssociation`,
which the specification permits and which is more precise, still wins.

---

## 6. The tool family was split across rdf4j versions

**Components:** `generators/rdf2docs` and every converter
**Severity:** medium — a correctness trap rather than a crash
**Status:** confirmed, **but the version table in the report was wrong**; closed for `rdf2docs`

The split was real and worse than reported. It is not two versions, it is four:

| Artefact | Bundled rdf4j |
|---|---|
| `converters/linked-archi-converters/*` — all five `*2linkedarchi` CLIs | 6.0.0 |
| `converters/archimate` (legacy standalone) | 5.3.1 |
| `converters/bpmn`, `converters/plantuml` (legacy standalone) | 5.2.2 |
| `rdf2docs` | 5.1.0 → **6.0.0** |

The report attributes 6.0.0 to the converters as a family. Only the current monorepo is on 6.0.0, and
it is the only one of the four with a single source of truth for the version (`rdf4jVersion` in
`gradle.properties`); the legacy repos pin per module. So a build-time assertion that every jar agrees
on its rdf4j version will still fail in an image that mixes legacy standalone converters with anything
current — aligning `rdf2docs` does not fix that, and it was never `rdf2docs`' to fix.

The RDF 1.2 argument for closing it stands as written: 6.0.0 adds triple terms and base-direction
literals, 5.1 has neither, so a graph the converters can legitimately produce was a graph the
generator could not read. Nothing emits RDF 1.2 today, which is exactly why it was worth fixing before
something does.

### What was done

`rdf2docs` now builds against rdf4j 6.0.0 from the same BOM, which pulls Kotlin 2.3.0, Gradle 9.6.1,
Shadow 9.3.1 and a **JDK 25** toolchain with it — Java 25 is not a preference, it is what rdf4j 6.x is
compiled for, and it is what the converters already require.

Aligning immediately surfaced a second defect that the version split had been hiding, worth recording
because it is a trap for any tool in this family that shades rdf4j: **rdf4j declares its Rio parser and
writer factories through JPMS `module-info`, and shading discards those declarations.** With only
`mergeServiceFiles()`, `ServiceLoader` found a single surviving legacy service file, and every
`facts.ttl` write failed with `UnsupportedRDFormatException: Did not recognise RDF format object
Turtle` — 82 of 83 resources skipped, and a `.ttl` input would not have parsed at all. The failure
isolation from issue 1 is the only reason it surfaced as a report instead of a crash. The remedy is the
one `converters/core` already uses: author `META-INF/services` files in `src/main/resources` and list
the factories explicitly.

---

## 7. `ai-context.md` was too large to use as an AI context document

**Component:** `generators/rdf2docs`
**Severity:** low — design feedback rather than a defect
**Status:** confirmed, **fixed**

Generated from the merged graph, `ai-context.md` was **9.8 MB / 163,797 lines** — far past any current
model's context window, so it could not be used for the purpose its own header describes ("Instructions
for AI Assistant … always cite specific IRIs").

### One correction to the diagnosis

The report says the file "opens with thousands of relationship entries … because relationships sort
before elements alphabetically by type". The ordering was purely alphabetical by type name, so
relationships were *interleaved* with elements rather than ahead of them — `Access` before
`ApplicationComponent`, `Flow` between `ExclusiveGateway` and `Model`. The complaint is right and the
explanation is not, which matters because it had a second cause the report could not have seen: the
generic core types (`arch:Element`, `arch:ModelConcept`, `arch:QualifiedRelationship`) were winning the
type slot over the specific one, so hundreds of resources were filed under `ModelConcept` instead of
`Class`. Opaque ids under a meaningless heading, from two directions at once.

### What was done

The report's suggested direction, all three parts:

- **Partitioned per model** — `ai-context/{notation}-{model}.md`, derived by dropping `kind/id` from
  the relative IRI path. On the four-model verification graph: 16.7 KB, 16.8 KB, 8.8 KB and 1.3 KB
  instead of one 41 KB file, and proportionally on a real graph. A single-model run is still one file
  with no index, so nothing changes for the common case.
- **An index at `ai-context.md`** listing every partition with its counts, so the "one document to hand
  an agent" property survives and the path existing scripts point at still resolves.
- **Elements and views lead** — views, then elements, then relationships, each grouped by its specific
  type. Relationships come last because they are the most numerous and mean nothing until their
  endpoints have been introduced. A relationship with no `skos:prefLabel` is now headed by its
  endpoints (`Customer → Web Shop`) rather than by its synthesised id.

---

## Still open: rdf4j's SHACL engine truncates silently

**Component:** `converters/linked-archi-converters` — `core/.../validation/ShaclValidator.kt`
**Severity:** low, but it makes every violation total in this document a floor rather than a number

rdf4j's SHACL engine stops collecting results for a single constraint at 1,000 and reports nothing
about having truncated, so any rule sitting exactly on 1,000 has almost certainly been cut short.
`ShaclValidator` neither raises the limit nor checks whether it was hit — there is no reference to a
results limit anywhere in it — so a reader takes the total at face value.

A warning when the limit is reached would be enough. This is the one item in this file still worth
raising.

---

## Context for the measurements above

Measured against a merged multi-notation graph, produced by the **legacy standalone** converters — the
counts of PlantUML and BPMN violations are not expected to reproduce against the current ones.

| | |
|---|---|
| Sources | ArchiMate (1 model), BPMN (20 diagrams), PlantUML (112 views) |
| Merged dataset | 451,542 statements across 121 named graphs |
| Distinct subject IRIs | 40,909 |
| Page-worthy resources | 15,390 (the rest is diagram geometry and styling) |
| Over-long IRIs | 16, all PlantUML relationships — 0.1% of resources, 100% of the run |
| Validation | ArchiMate ≥1,102 · BPMN 45 · PlantUML ≥1,055 violations |

The two ≥ figures are floors, for the reason in the open item above.
