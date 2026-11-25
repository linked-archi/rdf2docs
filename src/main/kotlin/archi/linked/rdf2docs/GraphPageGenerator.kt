package archi.linked.rdf2docs

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.repository.RepositoryConnection
import java.io.File

/**
 * The graph page: the architecture's elements and views as nodes, its relationships as edges, drawn two ways —
 * D3's force layout, with its forces on sliders, and Cytoscape.js, each node drawn as its notation's figure and each
 * edge in its notation's line style with its end decorations. Both read one data file, filter by type, relationship
 * type, model and folder, and centre on a node to show its neighbourhood, or show the whole graph.
 */
object GraphPageGenerator {

    data class FolderInfo(val iri: String, val name: String, val children: List<FolderInfo>, val memberIris: List<String>)

    private fun j(s: String?) = if (s == null) "null" else SiteAssets.js(s)

    fun generate(
        conn: RepositoryConnection,
        subjects: Map<String, HtmlPageGenerator.SubjectInfo>,
        roots: List<Any>,
        baseIri: String,
        outputDir: File,
        sidebarHtml: String,
        folderData: List<FolderInfo> = emptyList(),
        graphConfig: GraphConfig = GraphConfig(),
        notation: NotationSupport? = null,
        title: String = "Architecture",
    ) {
        val kinds = subjects.mapValues { it.value.kind }
        val nodeInfos = subjects.values.filter { it.kind != HtmlPageGenerator.ResourceKind.RELATIONSHIP }

        // A concept's model, for the model filter.
        val modelOf = HashMap<String, String>()
        conn.prepareTupleQuery("SELECT ?s ?m WHERE { ?s <https://meta.linked.archi/core#inModel> ?m }").evaluate().use { res ->
            res.forEach { b -> val s = b.getValue("s")?.stringValue(); val m = b.getValue("m")?.stringValue(); if (s != null && m != null) modelOf.putIfAbsent(s, m) }
        }

        data class Edge(val id: String, val source: String, val target: String, val rel: String?, val label: String?, val contains: Boolean)
        val edges = mutableListOf<Edge>()
        conn.prepareTupleQuery("""
            PREFIX arch: <https://meta.linked.archi/core#>
            PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
            SELECT DISTINCT ?rel ?src ?tgt ?name WHERE {
                # By its ends, as a page is classified: la-gram states only the language's type (am4:Serving).
                ?rel arch:source ?src ; arch:target ?tgt .
                OPTIONAL { ?rel skos:prefLabel ?name }
            }""".trimIndent()).evaluate().use { res ->
            val seen = HashSet<String>()
            res.forEach { b ->
                val rel = (b.getValue("rel") as? IRI)?.stringValue() ?: return@forEach
                val src = (b.getValue("src") as? IRI)?.stringValue() ?: return@forEach
                val tgt = (b.getValue("tgt") as? IRI)?.stringValue() ?: return@forEach
                if (!seen.add(rel)) return@forEach
                edges.add(Edge(rel, src, tgt, rel, (b.getValue("name") as? Literal)?.stringValue(), false))
            }
        }
        conn.prepareTupleQuery("""
            PREFIX archvis: <https://meta.linked.archi/core-vis#>
            SELECT DISTINCT ?view ?elem WHERE { ?node archvis:view ?view ; archvis:archElement ?elem }""".trimIndent()).evaluate().use { res ->
            res.forEach { b ->
                val v = (b.getValue("view") as? IRI)?.stringValue() ?: return@forEach
                val e = (b.getValue("elem") as? IRI)?.stringValue() ?: return@forEach
                edges.add(Edge("$v|$e", v, e, null, null, true))
            }
        }
        val nodeIds = nodeInfos.mapTo(HashSet()) { it.iri }
        val kept = edges.filter { it.source in nodeIds && it.target in nodeIds && it.source != it.target }

        val excluded = graphConfig.excludedNodeTypes.toSet()
        val json = StringBuilder(64 * 1024)
        json.append("{\"title\":").append(j(title)).append(",\"nodes\":[")
        nodeInfos.forEachIndexed { i, info ->
            if (i > 0) json.append(',')
            val look = notation?.lookOf(info.iri, false)
            val href = PagePaths.urlDir(info.iri, baseIri) + "index.html"
            json.append("{\"id\":").append(j(info.iri))
                .append(",\"label\":").append(j(info.label ?: info.iri.substringAfterLast('/')))
                .append(",\"kind\":").append(j(info.kind.name.lowercase()))
                .append(",\"type\":").append(j(look?.label ?: info.types.firstOrNull() ?: ""))
                .append(",\"typeIri\":").append(j(look?.iri ?: info.typeIris.values.firstOrNull() ?: ""))
                .append(",\"group\":").append(j(look?.group?.label))
                .append(",\"groupOrder\":").append(look?.group?.order ?: 9999)
                .append(",\"href\":").append(j(href))
                .append(",\"icon\":").append(j(look?.iconHtml))
                .append(",\"glyph\":").append(j(look?.glyph))
                .append(",\"picture\":").append(j(look?.picture))
                .append(",\"mode\":").append(j(look?.mode))
                .append(",\"fill\":").append(j(look?.fill))
                .append(",\"line\":").append(j(look?.line))
                .append(",\"shape\":").append(j(look?.shape))
                .append(",\"w\":").append(look?.width ?: 120.0)
                .append(",\"h\":").append(look?.height ?: 55.0)
                .append(",\"model\":").append(j(modelOf[info.iri]))
                .append('}')
        }
        json.append("],\"edges\":[")
        kept.forEachIndexed { i, e ->
            if (i > 0) json.append(',')
            val look = e.rel?.let { notation?.lookOf(it, true) }
            json.append("{\"id\":").append(j(e.id)).append(",\"source\":").append(j(e.source)).append(",\"target\":").append(j(e.target))
                .append(",\"contains\":").append(e.contains)
                .append(",\"type\":").append(j(if (e.contains) "shows" else look?.label ?: "relationship"))
                .append(",\"typeIri\":").append(j(if (e.contains) "view-contains" else look?.iri ?: ""))
                .append(",\"name\":").append(j(e.label))
                .append(",\"href\":").append(j(e.rel?.takeIf { kinds[it] != null }?.let { PagePaths.urlDir(it, baseIri) + "index.html" }))
                .append(",\"lineStyle\":").append(j(look?.lineStyle))
                .append(",\"sourceDecor\":").append(j(look?.sourceDecor))
                .append(",\"targetDecor\":").append(j(look?.targetDecor))
                .append(",\"line\":").append(j(look?.line))
                .append('}')
        }
        // The types, for the filters and the legend, each with its icon and its group.
        json.append("],\"types\":[")
        val typeLooks = nodeInfos.mapNotNull { notation?.lookOf(it.iri, false) }.groupingBy { it }.eachCount()
        // A view's types (arch:Diagram, arch:Catalog) are a group of their own, last: they are no element's.
        val viewTypes = nodeInfos.filter { it.kind == HtmlPageGenerator.ResourceKind.VIEW }.mapNotNull { notation?.typeOf(it.iri) }.toSet()
        fun groupOf(look: TypeLook) = if (look.iri in viewTypes) "Views" else look.group?.label
        typeLooks.entries.sortedWith(compareBy({ it.key.iri in viewTypes }, { it.key.group?.order ?: 9999 }, { it.key.label })).forEachIndexed { i, (look, n) ->
            if (i > 0) json.append(',')
            json.append("{\"iri\":").append(j(look.iri)).append(",\"label\":").append(j(look.label)).append(",\"icon\":").append(j(look.iconHtml))
                .append(",\"group\":").append(j(groupOf(look))).append(",\"count\":").append(n).append(",\"checked\":").append(look.iri !in excluded).append('}')
        }
        json.append("],\"relTypes\":[")
        val relLooks = kept.filter { !it.contains }.mapNotNull { e -> e.rel?.let { notation?.lookOf(it, true) } }.groupingBy { it }.eachCount()
        relLooks.entries.sortedWith(compareBy({ it.key.group?.order ?: 9999 }, { it.key.label })).forEachIndexed { i, (look, n) ->
            if (i > 0) json.append(',')
            json.append("{\"iri\":").append(j(look.iri)).append(",\"label\":").append(j(look.label)).append(",\"icon\":").append(j(look.iconHtml))
                // A relationship is an edge, not a node: excludedNodeTypes does not hide its type.
                .append(",\"group\":").append(j(look.group?.label)).append(",\"count\":").append(n).append(",\"checked\":true}")
        }
        json.append("],\"filterGroups\":[")
        graphConfig.filterGroups.forEachIndexed { i, g ->
            if (i > 0) json.append(',')
            json.append("{\"label\":").append(j(g.label)).append(",\"types\":[").append(g.iris.joinToString(",") { j(it) }).append("]}")
        }
        // The models the nodes are in, by `arch:inModel`, each by its name.
        json.append("],\"models\":[")
        val models = modelOf.filterKeys { it in nodeIds }.values.distinct().sorted()
        models.forEachIndexed { i, m ->
            if (i > 0) json.append(',')
            val name = conn.getStatements(conn.valueFactory.createIRI(m), conn.valueFactory.createIRI("http://www.w3.org/2004/02/skos/core#prefLabel"), null, true)
                .use { res -> res.firstOrNull()?.`object`?.stringValue() } ?: m.trimEnd('/').substringBeforeLast("/model").substringAfterLast('/')
            json.append("{\"iri\":").append(j(m)).append(",\"label\":").append(j(name)).append('}')
        }
        json.append("],\"folders\":[")
        fun visit(f: FolderInfo) {
            json.append("{\"name\":").append(j(f.name)).append(",\"members\":[")
                .append(f.memberIris.filter { it in nodeIds }.joinToString(",") { j(it) }).append("],\"children\":[")
            f.children.forEachIndexed { i, c -> if (i > 0) json.append(','); visit(c) }
            json.append("]}")
        }
        folderData.forEachIndexed { i, f -> if (i > 0) json.append(','); visit(f) }
        json.append("]}")

        SiteAssets.writeData(outputDir, "graph", "LA_GRAPH", json.toString())
        File(outputDir, "graph.html").writeText(buildGraphHtml(title))
    }

    private fun buildGraphHtml(title: String) = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Architecture Graph</title>
    <link rel="stylesheet" href="style.css">
    <link rel="stylesheet" href="${SiteAssets.DIR}/js/graph.css">
</head>
${PageLayout.body("", cls = "graph-page")}
${PageLayout.header("", "graph", extra = "<span class=\"seg\" id=\"renderer\"><button data-r=\"d3\" class=\"on\" title=\"D3 force layout\">D3</button><button data-r=\"cy\" title=\"Cytoscape.js, in the notation\">Cytoscape</button></span>")}
<div class="graph-layout">
<aside class="graph-side" id="side">
  <div class="side-block">
    <input id="search" type="search" placeholder="Find a node…" autocomplete="off">
    <div id="search-results" class="search-results"></div>
  </div>
  <details class="side-block" open><summary>Scope</summary>
    <div class="row"><span class="seg" id="scope"><button data-s="ego" class="on">Neighbourhood</button><button data-s="all">Whole graph</button></span></div>
    <label class="row">Depth <input id="depth" type="range" min="1" max="6" value="2"><span id="depth-val">2</span></label>
    <label class="row" title="Each view a node, linked to the elements it shows. Off, a view in the middle stands for what it shows: its elements and the relationships between them."><input type="checkbox" id="show-views"> Show views, linked to their elements</label>
    <label class="row"><input type="checkbox" id="hide-isolated" checked> Hide nodes without edges</label>
  </details>
  <details class="side-block" open id="d3-controls"><summary>Forces (D3)</summary>
    <label class="row">Link distance <input id="f-distance" type="range" min="20" max="400" step="10" value="90"><span></span></label>
    <label class="row">Link strength <input id="f-strength" type="range" min="0" max="1" step="0.05" value="0.5"><span></span></label>
    <label class="row">Repulsion <input id="f-charge" type="range" min="-1500" max="0" step="25" value="-250"><span></span></label>
    <label class="row">Collision <input id="f-collide" type="range" min="0" max="80" step="2" value="26"><span></span></label>
    <label class="row">Gravity <input id="f-gravity" type="range" min="0" max="0.5" step="0.01" value="0.05"><span></span></label>
    <label class="row">Friction <input id="f-friction" type="range" min="0.05" max="0.9" step="0.05" value="0.4"><span></span></label>
    <div class="row"><button class="btn" id="reheat">Reheat</button><button class="btn" id="freeze">Freeze</button><button class="btn" id="reset-forces">Reset</button></div>
  </details>
  <details class="side-block hidden" open id="cy-controls"><summary>Layout (Cytoscape)</summary>
    <label class="row">Layout <select id="cy-layout"><option value="cose">Force (cose)</option><option value="concentric">Rings</option><option value="breadthfirst">Tree</option><option value="circle">Circle</option><option value="grid">Grid</option></select></label>
    <label class="row">Node size <input id="cy-scale" type="range" min="0.4" max="1.4" step="0.1" value="0.8"><span></span></label>
    <label class="row">Spacing <input id="cy-spacing" type="range" min="0.5" max="3" step="0.1" value="1.2"><span></span></label>
    <div class="row"><button class="btn" id="cy-relayout">Lay out again</button></div>
  </details>
  <details class="side-block" open><summary>Display</summary>
    <label class="row"><input type="checkbox" id="node-labels" checked> Node names</label>
    <label class="row"><input type="checkbox" id="edge-labels"> Relationship types on edges</label>
    <label class="row"><input type="checkbox" id="size-degree" checked> Size by connections (D3)</label>
  </details>
  <details class="side-block" open><summary>Element types <span class="tools"><a href="#" data-all="types">all</a> · <a href="#" data-none="types">none</a></span></summary><div id="f-types" class="checklist"></div></details>
  <details class="side-block"><summary>Relationship types <span class="tools"><a href="#" data-all="rels">all</a> · <a href="#" data-none="rels">none</a></span></summary><div id="f-rels" class="checklist"></div></details>
  <details class="side-block" id="models-block"><summary>Models</summary><div id="f-models" class="checklist"></div></details>
  <details class="side-block"><summary>Folders</summary><div id="folders" class="folders"></div></details>
</aside>
<main class="graph-main">
  <div class="graph-bar"><span id="centre-label" class="faint"></span><span class="spacer"></span><span id="stats" class="faint"></span>
    <button class="btn" id="fit">Fit</button><button class="btn" id="export">Export PNG</button></div>
  <div id="canvas" class="graph-canvas"><div id="hint" class="graph-hint hidden"></div><svg id="d3"></svg><div id="cy" class="hidden"></div><div id="tooltip" class="tooltip"></div></div>
  <div id="info" class="graph-info hidden"></div>
</main>
</div>
${PageLayout.scripts("")}
<script src="${SiteAssets.DIR}/vendor/d3-7.9.0.min.js"></script>
<script src="${SiteAssets.DIR}/vendor/cytoscape-3.34.3.min.js"></script>
<script src="${SiteAssets.DIR}/data/graph.js"></script>
<script src="${SiteAssets.DIR}/js/graph.js"></script>
</body></html>
""".trimIndent()
}
