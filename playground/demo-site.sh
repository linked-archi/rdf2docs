#!/usr/bin/env bash
# Assembles the demo site from what playground/run-sample.sh generated: a folder per sample, its pages and its AI
# context, and an index page that says what each sample is.
#
# Usage: playground/demo-site.sh [site dir]        default: playground/site
#
# Run playground/run-sample.sh first. A sample in playground/samples.txt that it has not generated is left out.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
site=$(mkdir -p "${1:-$here/site}" && cd "${1:-$here/site}" && pwd)
ref=${SAMPLE_MODELS_REF:-main}

samples() { grep -vE '^[[:space:]]*(#|$)' "$here/samples.txt" | sed -E 's/[[:space:]]*\|[[:space:]]*/|/g; s/^[[:space:]]+//; s/[[:space:]]+$//'; }
esc() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' <<< "$1"; }
thousands() { awk -v n="$1" 'BEGIN { s = ""; while (length(n) > 3) { s = "," substr(n, length(n) - 2) s; n = substr(n, 1, length(n) - 3) } print n s }'; }
metric() {  # metric <generation-stats.md> <metric>: the count the stats report gives the metric, 0 when none
  local n; n=$(awk -F'|' -v m="$2" '$2 ~ m { gsub(/[^0-9]/, "", $3); print $3; exit }' "$1")
  echo "${n:-0}"
}
lang_class() {  # the colour of a language's label
  case "$1" in ArchiMate*) echo archimate ;; BPMN*) echo bpmn ;; UML*) echo uml ;; C4*) echo c4 ;;
    LeanIX*) echo leanix ;; Backstage*) echo backstage ;; *) echo other ;; esac
}

version=$(ls -t "$here"/../build/libs/rdf2docs-*-all.jar 2>/dev/null | head -1 | sed -E 's/.*rdf2docs-(.*)-all\.jar/\1/' || true)

card() {  # card <name> <file> <base> <title> <languages> <made from> <about> <credit>: one sample's card
  local name=$1 file=$2 base=$3 title=$4 languages=$5 made=$6 about=$7 credit=$8 out="$here/out/$1"
  local stats="$out/generation-stats.md" chips="" lang
  IFS=',' read -ra langs <<< "$languages"
  for lang in "${langs[@]}"; do
    lang=$(sed -E 's/^ +| +$//g' <<< "$lang")
    chips+="<li class=\"lang $(lang_class "$lang")\">$(esc "$lang")</li>"
  done
  cat <<EOF
    <article class="card">
      <h3><a href="$name/index.html">$(esc "$title")</a></h3>
      <ul class="langs">$chips</ul>
      <p class="about">$(esc "$about")</p>
      <p class="stats"><span><b>$(thousands "$(metric "$stats" 'Elements extracted')")</b> elements</span><span><b>$(thousands "$(metric "$stats" 'Relationships extracted')")</b> relationships</span><span><b>$(thousands "$(metric "$stats" 'Views extracted')")</b> views</span></p>
      <dl>
        <dt>Made from</dt><dd>$(esc "$made")</dd>
        <dt>Base IRI</dt><dd><code>$(esc "$base")</code></dd>
      </dl>
      <p class="links">
        <a class="primary" href="$name/index.html">Documentation</a>
        <a href="$name/ai-context.md">AI context</a>
        <a href="https://github.com/linked-archi/sample-models/tree/$ref/$(dirname "$file")">Model</a>
      </p>
EOF
  [ -z "$credit" ] || echo "      <p class=\"credit\">$(esc "$credit")</p>"
  echo "    </article>"
}

joined="" single="" count=0
while IFS='|' read -r name file base title languages made about credit; do
  out="$here/out/$name"
  [ -d "$out/html" ] || { echo "Leaving out $name: playground/out/$name has no pages" >&2; continue; }
  rm -rf "${site:?}/$name"
  cp -R "$out/html" "$site/$name"
  cp -R "$out"/ai-context* "$site/$name/"   # ai-context.md, and ai-context/ for a graph of several models
  if [[ "$languages" == *,* ]]; then joined+=$(card "$name" "$file" "$base" "$title" "$languages" "$made" "$about" "$credit")$'\n'
  else single+=$(card "$name" "$file" "$base" "$title" "$languages" "$made" "$about" "$credit")$'\n'; fi
  count=$((count + 1))
done < <(samples)
[ "$count" -gt 0 ] || { echo "No sample has been generated: run playground/run-sample.sh first" >&2; exit 1; }

section() {  # section <heading> <intro> <cards>
  [ -n "$3" ] || return 0
  printf '  <section>\n    <h2>%s</h2>\n    <p class="intro">%s</p>\n    <div class="cards">\n%s    </div>\n  </section>\n' "$1" "$2" "$3"
}

{
cat <<'EOF'
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>rdf2docs demo</title>
<meta name="description" content="Documentation sites rdf2docs generates from architecture models in ArchiMate, BPMN, UML, C4 and more.">
<style>
  :root {
    --bg: #ffffff; --bg-soft: #f6f7f9; --line: #e3e6ea; --text: #1f2328; --text-soft: #57606a; --text-faint: #8b949e;
    --accent: #0b63ce; --accent-soft: #e6f0fc; --radius: 8px;
    --font: system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
    --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }
  * { box-sizing: border-box; }
  body { margin: 0; font: 15px/1.5 var(--font); color: var(--text); background: var(--bg-soft); }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  code, pre { font-family: var(--mono); font-size: .86em; }
  .bar { background: var(--bg); border-bottom: 1px solid var(--line); }
  .bar .in { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; padding: 10px 16px; }
  .bar .brand { font-weight: 700; color: var(--text); }
  .bar nav { display: flex; gap: 4px; flex-wrap: wrap; margin-left: auto; }
  .bar nav a { padding: 5px 10px; border-radius: 6px; color: var(--text-soft); font-size: .92em; }
  .bar nav a:hover { background: var(--accent-soft); color: var(--accent); text-decoration: none; }
  .in { max-width: 1180px; margin: 0 auto; }
  header.hero { background: var(--bg); border-bottom: 1px solid var(--line); }
  header.hero .in { padding: 36px 16px 32px; }
  header.hero h1 { font-size: 2em; margin: 0 0 .3em; line-height: 1.2; }
  header.hero p { margin: 0; max-width: 46rem; color: var(--text-soft); font-size: 1.05em; }
  header.hero ul { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 18px 0 0; padding: 0; list-style: none;
    color: var(--text-soft); font-size: .92em; }
  header.hero li::before { content: "✓ "; color: var(--accent); font-weight: 700; }
  main.in { padding: 8px 16px 40px; }
  section h2 { font-size: 1.25em; margin: 30px 0 2px; }
  section .intro { margin: 0 0 14px; color: var(--text-soft); }
  .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 340px), 1fr)); gap: 16px; }
  .card { display: flex; flex-direction: column; gap: 10px; background: var(--bg); border: 1px solid var(--line);
    border-radius: var(--radius); padding: 18px 18px 16px; }
  .card:hover { border-color: #b9c9de; box-shadow: 0 2px 10px rgba(16, 40, 80, .06); }
  .card h3 { margin: 0; font-size: 1.15em; }
  .card h3 a { color: var(--text); }
  .card p { margin: 0; }
  .card .about { color: var(--text-soft); }
  .langs { display: flex; flex-wrap: wrap; gap: 6px; margin: 0; padding: 0; list-style: none; }
  .lang { font-size: .8em; font-weight: 600; padding: 2px 9px; border-radius: 999px; border: 1px solid transparent; }
  .lang.archimate { background: #fff6d1; color: #6b5200; border-color: #f0dd8c; }
  .lang.bpmn      { background: #e4f5ea; color: #19613a; border-color: #b6e0c4; }
  .lang.uml       { background: #eceeff; color: #363fa0; border-color: #c9cdf5; }
  .lang.c4        { background: #e3effb; color: #0b4c86; border-color: #b8d3ef; }
  .lang.leanix    { background: #fdece3; color: #8f3a10; border-color: #f3c8b0; }
  .lang.backstage { background: #e0f4f2; color: #0c625d; border-color: #acdcd6; }
  .lang.other     { background: var(--bg-soft); color: var(--text-soft); border-color: var(--line); }
  .stats { display: flex; flex-wrap: wrap; gap: 4px 16px; color: var(--text-soft); font-size: .92em; }
  .stats b { color: var(--text); font-variant-numeric: tabular-nums; }
  dl { display: grid; grid-template-columns: auto 1fr; gap: 3px 12px; margin: 0; font-size: .88em; }
  dt { color: var(--text-faint); }
  dd { margin: 0; color: var(--text-soft); overflow-wrap: anywhere; }
  .links { display: flex; flex-wrap: wrap; gap: 8px; margin-top: auto !important; padding-top: 4px; }
  .links a { padding: 5px 12px; border: 1px solid var(--line); border-radius: 6px; font-size: .9em; }
  .links a:hover { background: var(--accent-soft); border-color: var(--accent-soft); text-decoration: none; }
  .links a.primary { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
  .links a.primary:hover { background: #0a55b0; }
  .credit { font-size: .8em; color: var(--text-faint); }
  .try { margin-top: 36px; background: var(--bg); border: 1px solid var(--line); border-radius: var(--radius); padding: 18px; }
  .try h2 { margin: 0 0 6px; font-size: 1.1em; }
  .try p { margin: 0 0 10px; color: var(--text-soft); }
  .try pre { margin: 0; padding: 12px 14px; background: var(--bg-soft); border-radius: 6px; overflow-x: auto; }
  footer { padding: 18px 16px 30px; color: var(--text-faint); font-size: .85em; }
</style>
</head>
<body>
<div class="bar"><div class="in">
  <span class="brand">rdf2docs</span>
  <nav>
    <a href="https://github.com/linked-archi/rdf2docs">GitHub</a>
    <a href="https://github.com/linked-archi/rdf2docs/tree/main/ci">Use it in CI</a>
    <a href="https://github.com/linked-archi/sample-models">Sample models</a>
    <a href="https://meta.linked.archi/">Linked.Archi</a>
  </nav>
</div></div>
<header class="hero"><div class="in">
  <h1>Architecture documentation, generated from the model</h1>
  <p>rdf2docs turns an architecture model in Linked.Archi RDF into a static documentation site and a document an AI
  assistant can read. These are the sites it generates for the sample models: open one to browse it.</p>
  <ul>
    <li>A page for every element, relationship and view</li>
    <li>Views drawn in their own notation</li>
    <li>An interactive graph</li>
    <li>SPARQL in the browser</li>
    <li>An AI context document</li>
  </ul>
</div></header>
<main class="in">
EOF
section "Several languages, one graph" "Case studies whose models, each in its own language, are converted to RDF and linked into one graph: one site documents them all, and a page follows the links across languages." "$joined"
section "One language each" "Small models, invented for trying the tools, each in a single modelling language." "$single"
cat <<'EOF'
  <div class="try">
    <h2>Your own model</h2>
    <p>The container image runs rdf2docs on a Turtle or TriG file; the CI templates publish the site at every push.</p>
    <pre>docker run --rm -v "$PWD:/work" -w /work ghcr.io/linked-archi/rdf2docs \
  rdf2docs generate --input model.ttl --output-dir site --base https://example.org/my-model/</pre>
  </div>
</main>
EOF
echo "<footer class=\"in\">Generated by rdf2docs${version:+ $version} on $(date -u +%Y-%m-%d) from <a href=\"https://github.com/linked-archi/sample-models/tree/$ref\">linked-archi/sample-models</a>. Each sample's licence is in its folder there.</footer>"
echo "</body>"
echo "</html>"
} > "$site/index.html"
echo "Demo site with $count samples: $site/index.html"
