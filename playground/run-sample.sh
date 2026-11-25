#!/usr/bin/env bash
# Generates the documentation of the sample models in https://github.com/linked-archi/sample-models, each into
# playground/out/<name>/: open playground/out/<name>/html/index.html.
#
# Usage: playground/run-sample.sh [name ...]        no name: every sample
#
# The samples, their names and base IRIs are listed in playground/samples.txt.
#
# The models are downloaded into playground/models/ on first use. SAMPLE_MODELS=<dir> reads a local checkout of
# sample-models instead; SAMPLE_MODELS_REF=<branch or tag> downloads another revision (default: main).
# Build the jar first (./gradlew shadowJar). It needs Java 25.
set -euo pipefail
cd "$(dirname "$0")"

# samples.txt without its comments, a line per sample, its fields separated by | without the spaces around them
samples() { grep -vE '^[[:space:]]*(#|$)' samples.txt | sed -E 's/[[:space:]]*\|[[:space:]]*/|/g; s/^[[:space:]]+//; s/[[:space:]]+$//'; }

JAR=$(ls -t ../build/libs/rdf2docs-*-all.jar 2>/dev/null | head -1 || true)
[ -n "$JAR" ] || { echo "No jar in build/libs: run ./gradlew shadowJar first" >&2; exit 1; }

# The jar needs Java 25. When the default java is older, use a Java 25 that macOS knows of.
JAVA=java
if ! java -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])'; then
  JDK25=$( (/usr/libexec/java_home -v 25+ 2>/dev/null) || true)
  [ -n "$JDK25" ] || { echo "rdf2docs needs Java 25 or later, and java is $(java -version 2>&1 | head -1)" >&2; exit 1; }
  JAVA="$JDK25/bin/java"
fi

model() {  # model <file in sample-models>: the local path of the model, downloaded if need be
  if [ -n "${SAMPLE_MODELS:-}" ]; then echo "$SAMPLE_MODELS/$1"; return; fi
  local local="models/$1"
  if [ ! -f "$local" ]; then
    mkdir -p "$(dirname "$local")"
    curl -fsSL "https://raw.githubusercontent.com/linked-archi/sample-models/${SAMPLE_MODELS_REF:-main}/$1" -o "$local.part" \
      || { rm -f "$local.part"; echo "Could not download $1 from linked-archi/sample-models" >&2; return 1; }
    mv "$local.part" "$local"
  fi
  echo "$local"
}

wanted=("$@")
found=0
while IFS='|' read -r name file base _title languages _rest; do
  if [ ${#wanted[@]} -gt 0 ] && [[ ! " ${wanted[*]} " == *" $name "* ]]; then continue; fi
  found=$((found + 1))
  input=$(model "$file")
  extra=()
  # The graph of an ArchiMate 3 model starts without Grouping and Location, its types grouped by layer.
  case "$languages" in *"ArchiMate 3"*) extra=(--graph-config config/graph-config.yml) ;; esac
  echo "▸ $name ($file)"
  rm -rf "out/$name"
  "$JAVA" -jar "$JAR" generate --input "$input" --output-dir "out/$name" --base "$base" ${extra[@]+"${extra[@]}"}
done < <(samples)
[ "$found" -gt 0 ] || { echo "No sample named: $*" >&2; exit 1; }
