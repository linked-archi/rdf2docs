package archi.linked.rdf2docs

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings
import java.io.File
import java.io.StringWriter

/**
 * The model as data: `model.trig`, the whole graph as TriG, and two pages that read it —
 *
 * - `rdf.html`: the model's TriG, highlighted, a section per named graph, filtered by graph and searched by text;
 * - `sparql.html`: SPARQL over the whole model, run in the page by Oxigraph, with example queries, the results linked
 *   to the pages of what they name, and the results to download.
 *
 * Both read `_assets/data/model.js`: the prefixes and, for each graph, each subject's statements as a Turtle block.
 * The SPARQL page loads them into its store as TriG; the RDF page shows and filters them. A classic script, so that
 * both work in a page opened from disk.
 */
object DataPagesGenerator {

    private const val SKOS = "http://www.w3.org/2004/02/skos/core#"

    internal fun generate(conn: RepositoryConnection, notation: NotationSupport, site: Site, subjects: Map<String, HtmlPageGenerator.SubjectInfo>, outputDir: File, title: String) {
        val prefixes = notation.prefixes
        val model = notation.model

        // model.trig: the whole graph, every named graph as it is.
        StringWriter().also { w ->
            val writer = Rio.createWriter(RDFFormat.TRIG, w)
            writer.writerConfig.set(BasicWriterSettings.PRETTY_PRINT, true)
            writer.startRDF()
            prefixes.forEach { (p, ns) -> writer.handleNamespace(p, ns) }
            model.forEach { writer.handleStatement(it) }
            writer.endRDF()
            File(outputDir, "model.trig").writeText(w.toString())
        }

        // Each graph's statements by subject, a Turtle block each.
        val byGraph = linkedMapOf<Resource?, LinkedHashMap<Resource, MutableList<Statement>>>()
        model.forEach { st: Statement -> byGraph.getOrPut(st.context) { linkedMapOf() }.getOrPut(st.subject) { mutableListOf() }.add(st) }
        val graphs = byGraph.entries.sortedWith(compareBy({ it.key != null }, { it.key?.stringValue() ?: "" }))

        val json = StringBuilder(model.size * 120)
        json.append("{\"title\":").append(SiteAssets.js(title)).append(",\"base\":").append(SiteAssets.js(site.baseIri)).append(",\"prefixes\":{")
        prefixes.entries.forEachIndexed { i, (p, ns) -> if (i > 0) json.append(','); json.append(SiteAssets.js(p)).append(':').append(SiteAssets.js(ns)) }
        json.append("},\"triples\":").append(model.size).append(",\"graphs\":[")
        graphs.forEachIndexed { gi, (graph, bySubject) ->
            if (gi > 0) json.append(',')
            val iri = graph?.stringValue()
            val label = iri?.let { graphLabel(notation, it, site.baseIri) } ?: "Default graph"
            val page = iri?.let { site.href(it, "") }
            json.append("{\"iri\":").append(if (iri == null) "null" else SiteAssets.js(iri))
                .append(",\"label\":").append(SiteAssets.js(label))
                .append(",\"page\":").append(if (page == null) "null" else SiteAssets.js(page))
                .append(",\"size\":").append(bySubject.values.sumOf { it.size })
                .append(",\"blocks\":[")
            bySubject.entries.sortedBy { it.key.stringValue() }.forEachIndexed { bi, (subject, stmts) ->
                if (bi > 0) json.append(',')
                json.append('[').append(SiteAssets.js(subject.stringValue())).append(',').append(SiteAssets.js(turtleBlock(stmts, prefixes))).append(']')
            }
            json.append("]}")
        }
        // Where each resource with a page is, for linking query results and subjects to it, and what it is called and
        // its type's icon, so that a result names it as the pages do.
        json.append("],\"pages\":{")
        subjects.values.forEachIndexed { i, info ->
            if (i > 0) json.append(',')
            val look = if (info.kind in HtmlPageGenerator.CONCEPT_KINDS) notation.lookOf(info.iri, info.kind == HtmlPageGenerator.ResourceKind.RELATIONSHIP) else null
            json.append(SiteAssets.js(info.iri)).append(":[").append(SiteAssets.js(PagePaths.urlDir(info.iri, site.baseIri) + "index.html"))
                .append(',').append(SiteAssets.js(info.label ?: info.iri.substringAfterLast('/')))
                .append(',').append(if (look == null) "null" else SiteAssets.js(look.iconHtml)).append(']')
        }
        json.append("},\"examples\":[")
        examples(prefixes).forEachIndexed { i, (name, q) -> if (i > 0) json.append(','); json.append("{\"title\":").append(SiteAssets.js(name)).append(",\"query\":").append(SiteAssets.js(q)).append('}') }
        json.append("]}")
        SiteAssets.writeData(outputDir, "model", "LA_MODEL", json.toString())

        File(outputDir, "sparql.html").writeText(sparqlHtml())
        File(outputDir, "rdf.html").writeText(rdfHtml())
    }

    /** A named graph's name: its label, else its path under the base, else its IRI's last segment. */
    private fun graphLabel(notation: NotationSupport, iri: String, base: String): String {
        val s = archi.linked.rdf2docs.notation.iri(iri)
        for (p in listOf(SKOS + "prefLabel", "http://www.w3.org/2000/01/rdf-schema#label", "http://purl.org/dc/terms/title")) {
            notation.t.objects(s, p).filterIsInstance<Literal>().firstOrNull()?.let { return it.stringValue() }
        }
        return if (iri.startsWith(base)) iri.removePrefix(base).trimStart('/') else iri
    }

    /**
     * One subject's statements as Turtle, its directives left out: the prefixes are the page's, declared once, and
     * the RDF 1.2 `@version` the writer opens with may not stand inside a named graph's braces, where the SPARQL page
     * puts the block.
     */
    private fun turtleBlock(stmts: List<Statement>, prefixes: Map<String, String>): String {
        val w = StringWriter()
        val writer = Rio.createWriter(RDFFormat.TURTLE, w)
        writer.writerConfig.set(BasicWriterSettings.PRETTY_PRINT, true)
        writer.writerConfig.set(BasicWriterSettings.INLINE_BLANK_NODES, false)
        writer.startRDF()
        prefixes.forEach { (p, ns) -> writer.handleNamespace(p, ns) }
        stmts.distinctBy { Triple(it.subject, it.predicate, it.`object`) }.forEach { writer.handleStatement(it) }
        writer.endRDF()
        val directive = Regex("^\\s*(@prefix|@base|@version|PREFIX|BASE|VERSION)\\b", RegexOption.IGNORE_CASE)
        return w.toString().lines().filterNot { directive.containsMatchIn(it) }.dropWhile { it.isBlank() }.joinToString("\n").trimEnd()
    }

    /**
     * Questions to start from: what the model holds, by type; its relationships; its views; its graphs. An element is
     * what is in a model and is neither a relationship nor a view, and a relationship what has two ends, as the pages
     * have them: not every tool states `arch:Element` or `arch:QualifiedRelationship` — la-gram states the language's
     * type alone.
     */
    private fun examples(prefixes: Map<String, String>): List<Pair<String, String>> {
        val head = listOf("rdf", "rdfs", "skos", "arch", "arch-vis", "schema").mapNotNull { p -> prefixes[p]?.let { "PREFIX $p: <$it>" } }.joinToString("\n")
        val element = """?element arch:inModel ?model .
  FILTER NOT EXISTS { ?element arch:source ?anySource }
  FILTER NOT EXISTS { ?element a arch:View }"""
        return listOf(
            "Elements by type" to """$head

SELECT ?type (COUNT(DISTINCT ?element) AS ?elements) WHERE {
  $element
  ?element a ?type .
  FILTER(!STRSTARTS(STR(?type), STR(arch:)))
} GROUP BY ?type ORDER BY DESC(?elements)""",
            "Elements, with their names and types" to """$head

SELECT ?element ?name ?type WHERE {
  $element
  ?element a ?type .
  OPTIONAL { ?element skos:prefLabel ?name }
  FILTER(!STRSTARTS(STR(?type), STR(arch:)))
} ORDER BY ?type ?name LIMIT 500""",
            "Relationships: source, type, target" to """$head

SELECT ?source ?sourceName ?relationship ?type ?target ?targetName WHERE {
  ?relationship arch:source ?source ; arch:target ?target ; a ?type .
  FILTER(!STRSTARTS(STR(?type), STR(arch:)))
  OPTIONAL { ?source skos:prefLabel ?sourceName }
  OPTIONAL { ?target skos:prefLabel ?targetName }
} ORDER BY ?sourceName ?type LIMIT 500""",
            "Views and how much each shows" to """$head

SELECT ?view ?name (COUNT(DISTINCT ?element) AS ?elements) (COUNT(DISTINCT ?relationship) AS ?relationships) WHERE {
  ?shape arch-vis:view ?view .
  OPTIONAL { ?view skos:prefLabel ?name }
  OPTIONAL { ?shape arch-vis:archElement ?element }
  OPTIONAL { ?shape arch-vis:archRelationship ?relationship }
} GROUP BY ?view ?name ORDER BY DESC(?elements)""",
            "Elements in no view" to """$head

SELECT ?element ?name WHERE {
  $element
  OPTIONAL { ?element skos:prefLabel ?name }
  FILTER NOT EXISTS { ?shape arch-vis:archElement ?element }
} ORDER BY ?name""",
            "Most connected elements" to """$head

SELECT ?element ?name (COUNT(?relationship) AS ?relationships) WHERE {
  { ?relationship arch:source ?element } UNION { ?relationship arch:target ?element }
  $element
  OPTIONAL { ?element skos:prefLabel ?name }
} GROUP BY ?element ?name ORDER BY DESC(?relationships) LIMIT 25""",
            "Properties of the elements" to """$head

SELECT ?element ?name ?key ?value WHERE {
  ?element schema:additionalProperty ?pair .
  ?pair schema:name ?key .
  OPTIONAL { ?pair schema:value ?value }
  OPTIONAL { ?element skos:prefLabel ?name }
} ORDER BY ?name ?key LIMIT 500""",
            "Named graphs and their sizes (all graphs off)" to """SELECT ?graph (COUNT(*) AS ?triples) WHERE {
  GRAPH ?graph { ?s ?p ?o }
} GROUP BY ?graph ORDER BY DESC(?triples)""",
        )
    }

    private fun sparqlHtml() = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SPARQL</title>
    <link rel="stylesheet" href="style.css">
    <style>
      #query { width: 100%; min-height: 220px; font-family: var(--mono); font-size: 13px; line-height: 1.5; padding: 10px 12px; border: 1px solid var(--line-strong); border-radius: var(--radius); resize: vertical; tab-size: 2; }
      .status { font-size: .86em; color: var(--text-soft); }
      .status.error { color: #b42318; white-space: pre-wrap; font-family: var(--mono); }
      #results { margin-top: 12px; overflow: auto; }
      #results table td { font-size: .9em; word-break: break-word; }
      #results .lit { color: #a31515; } #results .bn { color: #6e7781; } #results .dt { color: var(--text-faint); font-size: .8em; }
      .history { font-size: .85em; }
    </style>
</head>
${PageLayout.body("")}
${PageLayout.header("", "sparql")}
<main class="page">
  <h1>SPARQL</h1>
  <p class="faint">Query the whole model in the page. The store is loaded from the site itself: nothing is sent anywhere.</p>
  <div class="toolbar">
    <select id="examples"><option value="">Examples…</option></select>
    <select id="history"><option value="">Recent queries…</option></select>
    <button class="btn" id="prefixes">Add the model's prefixes</button>
    <label title="Query the default graph and every named graph as one graph"><input type="checkbox" id="union" checked> All graphs as one</label>
    <span class="spacer" style="flex:1"></span>
    <span id="status" class="status">Loading the model…</span>
  </div>
  <textarea id="query" spellcheck="false" placeholder="SELECT * WHERE { ?s ?p ?o } LIMIT 100"></textarea>
  <div class="toolbar">
    <button class="btn primary" id="run">Run ⌘/Ctrl+Enter</button>
    <span id="took" class="status"></span>
    <span class="spacer" style="flex:1"></span>
    <input type="search" id="filter" placeholder="Filter results…" data-filter="#results" class="hidden">
    <button class="btn hidden" id="csv">CSV</button><button class="btn hidden" id="tsv">TSV</button><button class="btn hidden" id="json">JSON</button><button class="btn hidden" id="ttl">Turtle</button>
  </div>
  <div id="results"></div>
</main>
${PageLayout.scripts("")}
<script src="${SiteAssets.DIR}/data/model.js"></script>
<script src="${SiteAssets.DIR}/vendor/oxigraph-0.5.11.js"></script>
<script src="${SiteAssets.DIR}/vendor/oxigraph-0.5.11-wasm.js"></script>
<script src="${SiteAssets.DIR}/js/sparql.js"></script>
</body></html>
""".trimIndent()

    private fun rdfHtml() = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>RDF</title>
    <link rel="stylesheet" href="style.css">
    <style>
      .rdf-layout { display: flex; gap: 18px; align-items: flex-start; }
      .rdf-graphs { width: 300px; flex-shrink: 0; position: sticky; top: 64px; max-height: calc(100vh - 80px); overflow-y: auto; font-size: .88em; }
      .rdf-graphs label { display: flex; gap: 6px; align-items: flex-start; padding: 3px 0; cursor: pointer; }
      .rdf-graphs label small { margin-left: auto; white-space: nowrap; }
      .rdf-graphs .name { overflow-wrap: anywhere; }
      .rdf-main { flex: 1; min-width: 0; }
      .graph-head { font-family: var(--mono); font-size: .86em; }
      .block { border-bottom: 1px dashed var(--line); padding: 4px 0; }
      .block:last-child { border-bottom: 0; }
      .block .open { font-family: var(--font); font-size: .78em; margin-left: 8px; }
      .more { margin: 8px 0; }
      @media (max-width: 820px) { .rdf-layout { flex-direction: column; } .rdf-graphs { width: 100%; position: static; max-height: 30vh; } }
    </style>
</head>
${PageLayout.body("")}
${PageLayout.header("", "rdf")}
<main class="page">
  <h1>The model as RDF</h1>
  <p class="faint">Every statement of the model, as TriG, a section for each named graph. <a href="model.trig" download>Download model.trig</a></p>
  <div class="toolbar">
    <input type="search" id="q" placeholder="Search the statements: an IRI, a name, a value…">
    <label><input type="checkbox" id="regex"> Regular expression</label>
    <label><input type="checkbox" id="subjects-only"> In subjects only</label>
    <span id="hits" class="status faint"></span>
    <span class="spacer" style="flex:1"></span>
    <button class="btn" id="download-selected">Download the graphs shown</button>
  </div>
  <div class="rdf-layout">
    <aside class="rdf-graphs">
      <div class="toolbar" style="margin:0 0 6px"><strong>Named graphs</strong><span class="spacer" style="flex:1"></span><a href="#" id="all">all</a> · <a href="#" id="none">none</a></div>
      <input type="search" id="graph-filter" placeholder="Filter graphs…" style="width:100%;padding:5px 8px;border:1px solid var(--line-strong);border-radius:6px;margin-bottom:6px">
      <div id="graph-list"></div>
    </aside>
    <div class="rdf-main">
      <pre id="prefixes" class="code"></pre>
      <div id="graphs"></div>
    </div>
  </div>
</main>
${PageLayout.scripts("")}
<script src="${SiteAssets.DIR}/data/model.js"></script>
<script src="${SiteAssets.DIR}/js/rdf.js"></script>
</body></html>
""".trimIndent()
}
