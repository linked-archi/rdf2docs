package archi.linked.rdf2docs

import archi.linked.rdf2docs.notation.ClassHierarchy
import archi.linked.rdf2docs.notation.CustomShape
import archi.linked.rdf2docs.notation.Defaults
import archi.linked.rdf2docs.notation.ModelLabels
import archi.linked.rdf2docs.notation.NotationPack
import archi.linked.rdf2docs.notation.Shapes
import archi.linked.rdf2docs.notation.ShapeTemplateError
import archi.linked.rdf2docs.notation.Triples
import archi.linked.rdf2docs.notation.xml
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.StringReader
import java.net.URLDecoder

/** The notation la-gram draws with, ported: its lookup, its geometry, and the drawings and pages made with it. */
class NotationTest {

    @TempDir
    lateinit var out: File

    private val am4 = "https://meta.linked.archi/archimate4/onto#"
    private val am3 = "https://meta.linked.archi/archimate3/onto#"

    private fun pack(vararg languages: String) =
        NotationPack.load(languages.map { "https://meta.linked.archi/$it/onto#X" }.toSet(), languages.map { "https://meta.linked.archi/$it/metamodel#M" })

    @Test
    fun `a template expression is read, not run, and a mistake in it is reported`() {
        assertEquals(10.4, Shapes.evaluateExpression("min(h * 10.4 / 60, 14)", mapOf("h" to 60.0)), 1e-9)
        assertEquals(7.0, Shapes.evaluateExpression("max(1, w - (3 + 2))", mapOf("w" to 12.0)), 1e-9)
        assertEquals(-4.0, Shapes.evaluateExpression("-(2 * 2)", emptyMap()), 1e-9)
        assertThrows<ShapeTemplateError> { Shapes.evaluateExpression("w + x", mapOf("w" to 1.0)) }
        assertThrows<ShapeTemplateError> { Shapes.evaluateExpression("System.exit(1)", emptyMap()) }
    }

    @Test
    fun `a notation's own shape is drawn from its templates, else as its fallback`() {
        val box = CustomShape("x:Box3D", fallback = "Rectangle", variables = "d = min(h * 0.2, w * 0.2, 12)",
            outline = "M0,{d} L{d},0 L{w},0 L{w},{h - d} L{w - d},{h} L0,{h} Z", details = "M0,{d} L{w - d},{d}", contentBox = "0 {d} {w - d} {h - d}")
        val g = Shapes.shapeGeometry(null, box, 120.0, 55.0)
        assertEquals("path", g.tag)
        assertEquals("M0,11L11,0L120,0L120,44L109,55L0,55Z", g.attrs.single { it.first == "d" }.second)
        assertEquals("M0,11L109,11", g.details)
        assertEquals(11.0, Shapes.shapeLayout(null, box, 120.0, 55.0).content.y)
        val broken = box.copy(iri = "x:Broken", outline = "M0,{nope} Z")
        assertEquals("rect", Shapes.shapeGeometry(null, broken, 120.0, 55.0).tag)
    }

    @Test
    fun `a label is wrapped as la-gram wraps it, and a badge placed where it says`() {
        // 0.55em a character: 19 characters fit 120 pixels at 11 pixels; a word longer than a line is broken.
        assertEquals(listOf("Application", "component"), Shapes.wrapLabel("Application component", 120.0))
        assertEquals(listOf("Applicati", "on"), Shapes.wrapLabel("Application", 60.0))
        val b = Shapes.badgeBox("TopRight", 120.0, 55.0)
        assertEquals(96.0, b.x); assertEquals(4.0, b.y); assertEquals(20.0, b.size)
    }

    @Test
    fun `a type takes its language's notation, a core relationship the core's, a subclass its parent's`() {
        val styles = pack("archimate4").styles(null)
        val actor = styles.styleFor(am4 + "BusinessActor")!!
        assertEquals("Rectangle", actor.shapeType)
        assertEquals("#FFFFB5", actor.fillColor)
        assertTrue(actor.iconSymbol!!.endsWith("glyphs/actor.svg"))
        assertEquals("ShapeWithBadge", styles.renderingModeFor(am4 + "BusinessActor"))
        assertEquals("OpenArrowhead", styles.relationshipStyleFor(am4 + "Serving")!!.targetDecor)
        assertEquals("CircleDot", styles.relationshipStyleFor(am4 + "Assignment")!!.sourceDecor)
        // No language describes arch:DependsOn: the core notation does, dashed.
        assertEquals("DashedLine", styles.relationshipStyleFor("https://meta.linked.archi/core#DependsOn")!!.lineStyle)
        assertEquals(listOf("b", "c", "d"), ClassHierarchy.superClassesNearestFirst("a", mapOf("a" to listOf("c", "b"), "b" to listOf("d")), emptySet()))
    }

    @Test
    fun `the presentation contexts of a model's language are offered`() {
        val contexts = pack("archimate4").contexts.map { it.iri.substringAfter('#') }
        assertTrue("ArchitectContext" in contexts && "ExecutiveContext" in contexts, "$contexts")
    }

    @Test
    fun `a label expression names, types and follows paths, and leaves out a line with nothing to show`() {
        val model = LinkedHashModel(Rio.parse(StringReader("""
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> . @prefix ex: <http://ex/> .
            ex:a skos:prefLabel "Shop"@en ; ex:owner ex:b . ex:b skos:prefLabel "Team"@en .
        """.trimIndent()), "", RDFFormat.TURTLE))
        val labels = ModelLabels(Triples(model), { "Component" }, mapOf("ex" to "http://ex/"))
        assertEquals("Shop\n«Component»\nby Team", labels.evaluate("{name}\n«{type}»\nby {ex:owner}\n{ex:none}", "http://ex/a", "t", null))
        assertEquals("Shop {x}", labels.evaluate("{name} {{x}}", "http://ex/a", null, null))
    }

    @Test
    fun `a view is drawn in its notation, its own look over it, its lines with their ends`() {
        val repo = StaticPublishingTest.repositoryOf(StaticPublishingTest.GRAPH)
        try {
            val html = File(out, "html").also { it.mkdirs() }
            HtmlPageGenerator.generate(repo, StaticPublishingTest.BASE, html, StaticPublishingTest.NS)
            val view = File(html, "model/shop/view/main/index.html").readText()
            // The web shop's own fill over ArchiMate 3.2's application blue; the customer in the notation's yellow.
            assertTrue("fill=\"#b5ffff\"" in view, "the node's own fill is not drawn")
            assertTrue(Regex("""data-element-iri="[^"]*customer"[^>]*>[\s\S]*?fill="#FFFFB5"""").containsMatchIn(view), "the notation's fill is not drawn")
            // Each with its notation's glyph as a badge, where meta.linked.archi publishes it; no icon is copied.
            assertTrue("la-shape-badge" in view && "href=\"https://meta.linked.archi/archimate3/icons/glyphs/" in view)
            assertFalse(File(html, "_assets/notation").exists() || File(html, "_assets/types").exists() || File(html, "_assets/figures").exists())
            // Serving: an open arrowhead at its target — drawn from core-vis's published symbol, or from the same
            // geometry built in when meta.linked.archi cannot be reached.
            assertTrue(Regex("""marker-end="url\(#d0-la-marker-OpenArrowhead-target-""").containsMatchIn(view))
            assertTrue(File(html, "model/shop/view/main/diagram.svg").readText().contains("la-diagram"))
        } finally { repo.shutDown() }
    }

    @Test
    fun `a presentation context that draws a view differently is a second drawing on its page`() {
        val trig = """
            @prefix arch: <https://meta.linked.archi/core#> . @prefix arch-vis: <https://meta.linked.archi/core-vis#> .
            @prefix am4: <https://meta.linked.archi/archimate4/onto#> . @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix am4pc: <https://meta.linked.archi/archimate4/presentation-contexts#> . @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix skosxl: <http://www.w3.org/2008/05/skos-xl#> .
            @base <https://example.org/la/> .
            <m/graph> {
              <m/model> a arch:Model ; skos:prefLabel "M"@en ; arch:modelConformsToMetamodel <https://meta.linked.archi/archimate4/metamodel#ArchiMate4> .
              <m/element/a> a am4:BusinessActor, arch:Element ; skos:prefLabel "Customer"@en ; arch:inModel <m/model> ;
                  skosxl:altLabel <m/element/a/name/1> .
              <m/element/a/name/1> a skosxl:Label ; skosxl:literalForm "Client (exec)"@en ; arch:inPresentationContext am4pc:ExecutiveContext .
              <m/view/v> a arch:View, arch:Diagram ; skos:prefLabel "V"@en ; arch:inModel <m/model> .
              <m/view/v#node-a> a arch-vis:ArchNode ; arch-vis:view <m/view/v> ; arch-vis:archElement <m/element/a> ;
                  arch-vis:bounds-x "0"^^xsd:double ; arch-vis:bounds-y "0"^^xsd:double ; arch-vis:bounds-w "120"^^xsd:double ; arch-vis:bounds-h "55"^^xsd:double ;
                  arch-vis:style <m/view/v#node-a-style-1> .
              <m/view/v#node-a-style-1> a arch-vis:Style ; arch:inPresentationContext am4pc:ArchitectContext ; arch-vis:fillColor "#ff0000" .
            }
        """.trimIndent()
        val repo = StaticPublishingTest.repositoryOf(trig)
        try {
            val html = File(out, "ctx").also { it.mkdirs() }
            HtmlPageGenerator.generate(repo, StaticPublishingTest.BASE, html, StaticPublishingTest.NS)
            val view = File(html, "m/view/v/index.html").readText()
            assertTrue("class=\"context\"" in view, "no context switch")
            // The default drawing, the Architect's red one and the Executive's with its own name: three drawings.
            assertEquals(3, Regex("""<svg [^>]*class="la-diagram""").findAll(view).count())
            assertTrue("fill=\"#ff0000\"" in view && "Client (exec)" in view)
            // Only the drawing shown carries the shapes' fragments as ids.
            assertEquals(1, Regex("""id="node-a"""").findAll(view).count())
            assertTrue(Regex("""ArchitectContext" data-variant="[12]"""").containsMatchIn(view))
        } finally { repo.shutDown() }
    }

    @Test
    fun `the data pages carry the model, its graphs as TriG the query engine reads`() {
        val repo = StaticPublishingTest.repositoryOf(StaticPublishingTest.GRAPH)
        try {
            val html = File(out, "data").also { it.mkdirs() }
            HtmlPageGenerator.generate(repo, StaticPublishingTest.BASE, html, StaticPublishingTest.NS)
            listOf("sparql.html", "rdf.html", "graph.html", "model.trig", "_assets/data/model.js", "_assets/data/graph.js",
                "_assets/vendor/oxigraph-0.5.11.js", "_assets/vendor/oxigraph-0.5.11-wasm.js", "_assets/vendor/cytoscape-3.34.3.min.js",
                "_assets/vendor/d3-7.9.0.min.js", "_assets/js/site.js", "_assets/js/tree.js", "_assets/js/diagram.js",
                "_assets/data/tree.js").forEach { assertTrue(File(html, it).isFile, "no $it") }
            val data = File(html, "_assets/data/model.js").readText()
            assertTrue(data.startsWith("window.LA_MODEL = {"))
            assertFalse("@version" in data, "a directive inside a block, which a named graph's braces do not take")
            assertTrue("model/shop/graph/semantic" in data && "costCentre" in data)
            // model.trig is the whole graph.
            val trig = LinkedHashModel(Rio.parse(File(html, "model.trig").reader(), "", RDFFormat.TRIG))
            assertEquals(repo.connection.use { it.size() }.toInt(), trig.size)
            assertNotNull(Regex("\"LA_GRAPH\"|window.LA_GRAPH").find(File(html, "_assets/data/graph.js").readText()))
        } finally { repo.shutDown() }
    }

    @Test
    fun `a relationship's icon is drawn as la-gram draws it, character for character`() {
        // The expected strings are la-gram's relationshipIconDataUri for the same styles.
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"24\" height=\"24\">"
        val open = "<marker id=\"target\" viewBox=\"0 0 12 12\" markerWidth=\"9\" markerHeight=\"9\" refX=\"10\" refY=\"6\" markerUnits=\"userSpaceOnUse\" orient=\"auto\">" +
            "<path d=\"M 1 1 L 10 6 L 1 11\" stroke=\"#000000\" stroke-width=\"2\" fill=\"none\"/></marker>"
        val line = "<line x1=\"3\" y1=\"21\" x2=\"21\" y2=\"3\" stroke=\"#000000\" stroke-width=\"2\""
        fun drawn(style: String?, source: String?, target: String?) =
            URLDecoder.decode(Shapes.relationshipIconDataUri("#000000", style, source, target).removePrefix("data:image/svg+xml;charset=utf-8,"), Charsets.UTF_8)
        assertEquals("$svg<defs>$open</defs>$line marker-end=\"url(#target)\"/></svg>", drawn("SolidLine", "NoDecoration", "OpenArrowhead"))
        assertEquals("$svg<defs>$open</defs>$line stroke-dasharray=\"4.8 2.4\" marker-end=\"url(#target)\"/></svg>", drawn("DashedLine", "NoDecoration", "OpenArrowhead"))
        assertEquals("$svg<defs><marker id=\"source\" viewBox=\"0 0 16 14\" markerWidth=\"12\" markerHeight=\"10.5\" refX=\"15\" refY=\"7\" markerUnits=\"userSpaceOnUse\" orient=\"auto-start-reverse\">" +
            "<path d=\"M 1 7 L 8 1 L 15 7 L 8 13 z\" stroke=\"#000000\" stroke-width=\"2\" fill=\"#000000\"/></marker></defs>$line marker-start=\"url(#source)\"/></svg>",
            drawn("SolidLine", "FilledDiamond", "NoDecoration"))
        assertEquals("$svg$line/></svg>", drawn(null, null, null))
        // Encoded as encodeURIComponent encodes it.
        assertEquals("data:image/svg+xml;charset=utf-8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2024%2024%22%20width%3D%2224%22%20height%3D%2224%22%3E%3Cline%20x1%3D%223%22%20y1%3D%2221%22%20x2%3D%2221%22%20y2%3D%223%22%20stroke%3D%22%23000000%22%20stroke-width%3D%222%22%2F%3E%3C%2Fsvg%3E",
            Shapes.relationshipIconDataUri(null, null, null, null))
        assertTrue(Shapes.relationshipIconDataUri("#000000", "SolidLine", null, "OpenArrowhead").endsWith("marker-end%3D%22url(%23target)%22%2F%3E%3C%2Fsvg%3E"))
    }

    @Test
    fun `a type's icon is what meta linked archi publishes for it - its picture, else its glyph, else none`() {
        val pack = pack("archimate4")
        val looks = TypeLooks(pack, pack.styles(null))
        // The picture the notation publishes of the type: its whole figure.
        val actor = looks.look(am4 + "BusinessActor", false)
        assertEquals("<span class=\"ti pic\" title=\"Business Actor\"><img src=\"https://meta.linked.archi/archimate4/icons/business-actor.svg\" alt=\"\"></span>", actor.iconHtml)
        // A relationship type publishes no picture: its whole line, drawn from its notation.
        val serving = looks.look(am4 + "Serving", true)
        val servingStyle = pack.styles(null).relationshipStyleFor(am4 + "Serving")
        val servingIcon = Shapes.relationshipIconDataUri(servingStyle?.lineColor ?: Defaults.LINE, servingStyle?.lineStyle, servingStyle?.sourceDecor, servingStyle?.targetDecor)
        assertEquals("<span class=\"ti bare line\" title=\"Serving\"><img src=\"${xml(servingIcon)}\" alt=\"\"></span>", serving.iconHtml)
        assertTrue("open-arrowhead" !in serving.iconHtml, "the decoration is drawn on the line, not shown alone")
        // A line with no decoration is a plain line.
        assertTrue("marker" !in URLDecoder.decode(looks.look(am4 + "Association", true).iconHtml.substringAfter(","), Charsets.UTF_8))
        assertTrue(looks.look("https://meta.linked.archi/core#Diagram", false).iconHtml.contains("▦"))
        // BPMN publishes glyphs and no pictures: a task its glyph alone, an event nothing.
        val bpmn = pack("bpmn")
        val bpmnLooks = TypeLooks(bpmn, bpmn.styles(null))
        assertTrue(bpmnLooks.look("https://meta.linked.archi/bpmn/onto#UserTask", false).iconHtml.contains("class=\"ti bare\""))
        assertTrue(bpmnLooks.look("https://meta.linked.archi/bpmn/onto#StartEvent", false).iconHtml.startsWith("<span class=\"ti none\""))
        assertFalse(actor.iconHtml.contains("style="), "no colour or shape of rdf2docs's own")
    }

    @Test
    fun `the model tree's data has each model's views, elements, relationships and folders`() {
        val repo = StaticPublishingTest.repositoryOf(StaticPublishingTest.GRAPH)
        try {
            val html = File(out, "tree").also { it.mkdirs() }
            HtmlPageGenerator.generate(repo, StaticPublishingTest.BASE, html, StaticPublishingTest.NS)
            val tree = File(html, "_assets/data/tree.js").readText()
            assertTrue(tree.startsWith("window.LA_TREE = {"))
            // The shop: its two views, its elements, its relationship named by its ends, its folder holding the web shop.
            assertTrue("\"Main\"" in tree && "\"Sketch\"" in tree && "\"Web Shop\"" in tree && "\"Web Shop → Customer\"" in tree, tree.take(400))
            assertTrue(Regex(""""Shop",null,\["https://example.org/la/model/shop/element/web"]""").containsMatchIn(tree), "the folder and its member")
            // Every page has the tree's panel and the header's menus.
            val web = File(html, "model/shop/element/web/index.html").readText()
            assertTrue("id=\"model-tree\"" in web && "id=\"site-menus\"" in web && "data-iri=\"${StaticPublishingTest.BASE}model/shop/element/web\"" in web)
            assertTrue("id=\"model-tree\"" in File(html, "index.html").readText())
        } finally { repo.shutDown() }
    }

    @Test
    fun `a relationship stated as la-gram states it, by its language's type and its ends, is an edge and a tab`() {
        // No arch:Element, no arch:QualifiedRelationship: the language's types alone.
        val trig = """
            @prefix arch: <https://meta.linked.archi/core#> . @prefix am4: <https://meta.linked.archi/archimate4/onto#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> . @base <https://example.org/la/> .
            <m/graph> {
              <m/model> a arch:Model ; skos:prefLabel "M"@en .
              <m/element/a> a am4:BusinessActor ; skos:prefLabel "Customer"@en ; arch:inModel <m/model> .
              <m/element/b> a am4:Process ; skos:prefLabel "Place order"@en ; arch:inModel <m/model> .
              <m/relationship/r> a am4:Assignment ; arch:source <m/element/a> ; arch:target <m/element/b> ; arch:inModel <m/model> .
            }
        """.trimIndent()
        val repo = StaticPublishingTest.repositoryOf(trig)
        try {
            val html = File(out, "lagram").also { it.mkdirs() }
            HtmlPageGenerator.generate(repo, StaticPublishingTest.BASE, html, StaticPublishingTest.NS)
            val graph = File(html, "_assets/data/graph.js").readText()
            assertTrue(Regex(""""source":"https://example.org/la/m/element/a","target":"https://example.org/la/m/element/b"""").containsMatchIn(graph), "no edge")
            assertTrue(">Relationships (1)</button>" in File(html, "m/element/a/index.html").readText(), "no Relationships tab")
            assertTrue(">Endpoints</button>" in File(html, "m/relationship/r/index.html").readText(), "no Endpoints tab")
        } finally { repo.shutDown() }
    }

    @Test
    fun `the graph config hides element types, never relationships, which are its edges`() {
        val repo = StaticPublishingTest.repositoryOf(StaticPublishingTest.GRAPH)
        try {
            val html = File(out, "config").also { it.mkdirs() }
            val config = GraphConfig(excludedNodeTypes = listOf("https://meta.linked.archi/archimate3/onto#Serving", "https://meta.linked.archi/archimate3/onto#BusinessActor"))
            HtmlPageGenerator.generate(repo, StaticPublishingTest.BASE, html, StaticPublishingTest.NS, config)
            val graph = File(html, "_assets/data/graph.js").readText()
            assertTrue(Regex(""""iri":"https://meta.linked.archi/archimate3/onto#BusinessActor"[^}]*"checked":false""").containsMatchIn(graph))
            assertTrue(Regex(""""iri":"https://meta.linked.archi/archimate3/onto#Serving"[^}]*"checked":true""").containsMatchIn(graph))
        } finally { repo.shutDown() }
    }
}
