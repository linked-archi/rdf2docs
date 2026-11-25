#!/usr/bin/env bash
# Refreshes the notation pack the generator draws with (src/main/resources/notation/) from linked-archi-meta.
#
# The pack is laid out as https://meta.linked.archi/ is published, one folder per language. Each language brings what
# drawing it needs and nothing else: its manifest (the notation set and the presentation contexts it names), its
# ontology (the class hierarchy a notation is inherited through), its notation, its presentation contexts and its
# taxonomy (the groups the lists are sorted into). No icons: the pages show the notation's icons, glyphs and
# decoration symbols where meta.linked.archi publishes them, at their IRIs.
#
# Usage: scripts/sync-notation.sh [path/to/linked-archi-meta]
set -euo pipefail
cd "$(dirname "$0")/.."
META="${1:-${META_DIR:-../../../linked-archi-meta}}"
[ -d "$META/modelingLanguages" ] || { echo "linked-archi-meta not found at $META" >&2; exit 1; }
OUT=src/main/resources/notation
rm -rf "$OUT"
mkdir -p "$OUT"
MANIFEST="$OUT/packs.txt"
: > "$MANIFEST"

# pack <id> <source dir> <ttl files...>: copies the files and lists them in the manifest.
pack() {
  local id="$1" src="$2"; shift 2
  mkdir -p "$OUT/$id"
  local files=()
  for f in "$@"; do
    cp "$src/$f" "$OUT/$id/$f"
    files+=("$id/$f")
  done
  echo "$id ${files[*]}" >> "$MANIFEST"
}

pack core "$META/core" core-onto.ttl core-vis-onto.ttl core-notation.ttl

L="$META/modelingLanguages"
pack archimate3 "$L/archimate/3.2" archimate3.2-metamodel.ttl archimate3.2-onto.ttl archimate3.2-notation.ttl \
  archimate3.2-presentation-contexts.ttl archimate3.2-tax.ttl
pack archimate4 "$L/archimate/4.0" archimate4-metamodel.ttl archimate4-onto.ttl archimate4-notation.ttl \
  archimate4-presentation-contexts.ttl archimate4-tax.ttl
pack bpmn "$L/bpmn" bpmn-metamodel.ttl linkedarchi-bpmn-onto.ttl linkedarchi-bpmn-infra-onto.ttl bpmn-notation.ttl bpmn-tax.ttl
pack bpmn-lite "$L/bpmn-lite" bpmn-lite-metamodel.ttl linkedarchi-bpmn-lite-onto.ttl bpmn-lite-notation.ttl bpmn-lite-tax.ttl
pack c4 "$L/c4" c4-metamodel.ttl c4-onto.ttl structurizr-onto.ttl c4-notation.ttl c4-presentation-contexts.ttl c4-tax.ttl
pack uml "$L/uml" uml-metamodel.ttl uml-onto.ttl uml-notation.ttl uml-tax.ttl
pack leanix "$L/leanIX" leanIX-metamodel.ttl leanIX-v4-onto.ttl leanIX-notation.ttl leanIX-tax.ttl
pack backstage "$L/backstage" backstage-metamodel.ttl backstage-onto.ttl backstage-notation.ttl \
  backstage-presentation-contexts.ttl backstage-tax.ttl
pack basic "$L/basic" basic-metamodel.ttl basic-onto.ttl basic-notation.ttl basic-presentation-contexts.ttl basic-tax.ttl
mkdir -p "$OUT/basic/notation-bubble"
cp "$L/basic/notation-bubble/basic-notation-bubble.ttl" "$OUT/basic/notation-bubble/"
sed -i.bak 's|^basic \(.*\)$|basic \1 basic/notation-bubble/basic-notation-bubble.ttl|' "$MANIFEST" && rm "$MANIFEST.bak"

# The Turtle files and the manifest only.
find "$OUT" -type f ! -name '*.ttl' ! -name 'packs.txt' -delete
echo "Notation pack written to $OUT ($(du -sh "$OUT" | cut -f1))"
