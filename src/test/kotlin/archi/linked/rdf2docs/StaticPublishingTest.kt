package archi.linked.rdf2docs

import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.StringReader

/**
 * The pages a converted model gets, laid out as DD-41 has it (IRI Minting, *Static publishing*): one per
 * resource in its own right, chosen by type; a shape is a fragment of its view's page; a part is shown on its
 * owner's page.
 */
class StaticPublishingTest {

    @TempDir
    lateinit var out: File

    private lateinit var repository: Repository
    private val html get() = File(out, "html")

    @BeforeEach
    fun generate() {
        repository = repositoryOf(GRAPH)
        html.mkdirs()
        HtmlPageGenerator.generate(repository, BASE, html, NS)
        AiContextGenerator.generate(repository, BASE, File(out, "ai-context.md"), null, NS)
    }

    @AfterEach
    fun shutDown() = repository.shutDown()

    private fun page(path: String): String = File(html, "$path/index.html").readText()

    @Test
    fun `pages are for resources in their own right, chosen by type`() {
        val pages = html.walk().filter { it.name == "index.html" }
            .map { it.parentFile.relativeTo(html).invariantSeparatorsPath }.toSortedSet()
        assertEquals(
            sortedSetOf(
                "",                                                  // the index
                "model/shop/model",                                  // the model
                "model/shop",                                        // its name before DD-41
                "model/shop/element/web",
                "model/shop/element/customer",
                "model/shop/stakeholder/jane-doe-example.org",       // arch:Stakeholder, no arch:inModel
                "model/shop/relationship/r1",
                "model/shop/view/main",
                "model/shop/view/sketch",
                "model/shop/documentation/workspace/01-overview.md", // schema:subjectOf
                "model/shop/graph/semantic",                         // prov:Bundle
                "provenance/run/0001",
                "provenance/agent/conv",
                "provenance/source/shop-xml",
                "bpmn/orders/model",
                "bpmn/orders/element/Process_A",                     // a process that is its view: one page
                "bpmn/orders/element/Task_1",
            ),
            pages,
        )
    }

    @Test
    fun `no shape, part, folder or route cell gets a directory, and no directory name holds a fragment`() {
        val dirs = html.walk().filter { it.isDirectory }.map { it.relativeTo(html).invariantSeparatorsPath }.toList()
        assertTrue(dirs.none { '#' in it }, "a directory name holds '#': ${dirs.filter { '#' in it }}")
        listOf("property", "tag", "folder", "item", "points", "point", "style").forEach { part ->
            assertTrue(dirs.none { it.split('/').contains(part) }, "a directory for a part: $part in $dirs")
        }
    }

    @Test
    fun `a view's page draws its shapes inline, each with its fragment as id`() {
        val view = page("model/shop/view/main")
        assertTrue("<svg" in view, "the diagram is not in the page")
        assertFalse("<img src=\"diagram.svg\"" in view, "the diagram is an image, whose inside cannot be a target")
        listOf("node-n1", "node-n2", "link-l1", "link-l1-point-0", "link-l1-point-1").forEach { fragment ->
            assertTrue("id=\"$fragment\"" in view, "no target for #$fragment")
        }
        assertTrue(File(html, "model/shop/view/main/diagram.svg").readText().startsWith("<?xml"))
    }

    @Test
    fun `the bend points of a link are drawn in the order of their list, its ends docked on the shapes`() {
        val view = page("model/shop/view/main")
        val line = Regex("""id="link-l1"[\s\S]*?<polyline class="la-connection" points="([^"]+)"""").find(view)?.groupValues?.get(1)
        assertNotNull(line, "no line for link-l1")
        // In the view's own coordinates: docked on node n1's right edge (x 130), through point 0 at (200,30) and
        // point 1 at (250,40), docked on node n2's left edge (x 300).
        val points = line!!.split(' ').map { p -> p.split(',').let { it[0].toDouble() to it[1].toDouble() } }
        assertEquals(listOf(200.0 to 30.0, 250.0 to 40.0), points.drop(1).dropLast(1))
        assertEquals(130.0, points.first().first)
        assertEquals(300.0, points.last().first)
    }

    @Test
    fun `a view with no layout lists its shapes, each a target`() {
        val sketch = page("model/shop/view/sketch")
        assertTrue("<tr id=\"node-web\">" in sketch, "no target for #node-web")
        assertFalse("<svg" in sketch)
    }

    @Test
    fun `a link to a shape opens its view's page at the shape`() {
        val web = page("model/shop/element/web")
        assertTrue("href=\"../../../../model/shop/view/main/index.html#node-n1\"" in web, "the Views tab does not link to the shape")
    }

    @Test
    fun `a property pair with an IRI is shown on its owner's page`() {
        val web = page("model/shop/element/web")
        assertTrue("<td>costCentre</td><td>CC-42</td>" in web, "the pair is not on the page")
    }

    @Test
    fun `a part is drawn on its owner's page rather than linked`() {
        val web = page("model/shop/element/web")
        assertTrue("<table class=\"part\"" in web && "Critical" in web, "the tag is not drawn on its owner's page")
        assertFalse("tag/bb22/index.html" in web, "a link to a part, which has no page")
    }

    @Test
    fun `a view's facts hold its shapes, and an element's its parts`() {
        fun subjects(path: String): Set<String> =
            Rio.parse(File(html, "$path/facts.ttl").reader(), "", RDFFormat.TURTLE).subjects().map { it.stringValue() }.toSet()
        val view = subjects("model/shop/view/main")
        assertTrue("${BASE}model/shop/view/main#node-n1" in view)
        assertTrue("${BASE}model/shop/view/main#node-n1-style" in view)
        assertTrue("${BASE}model/shop/view/main#link-l1-point-1" in view)
        val web = subjects("model/shop/element/web")
        assertTrue("${BASE}model/shop/element/web/property/aa11" in web)
        assertTrue("${BASE}model/shop/element/web/tag/bb22" in web)
    }

    @Test
    fun `an element's page says where it came from, through its graph`() {
        val web = page("model/shop/element/web")
        assertTrue(">Provenance</button>" in web)
        assertTrue("its graph" in web, "the record is not read from the element's graph")
        assertTrue(">shop.xml</a>" in web)
        assertTrue(">archimate2linkedarchi 1.3.0</a>" in web)
    }

    @Test
    fun `a run's page lists what it generated`() {
        val run = page("provenance/run/0001")
        assertTrue(">Referenced by</button>" in run)
        assertTrue("wasGeneratedBy" in run)
        assertTrue("href=\"../../../model/shop/model/index.html\"" in run, "the model is not linked from its run")
    }

    @Test
    fun `a former name's page says what replaced it`() {
        val former = page("model/shop")
        assertTrue("former name, replaced by <a href=\"../../model/shop/model/index.html\">Shop</a>" in former)
    }

    @Test
    fun `with the model folded into the base, its former name is the index's URL and nothing is skipped`() {
        val folded = File(out, "folded").also { it.mkdirs() }
        val result = HtmlPageGenerator.generate(repository, "${BASE}model/shop", folded, NS)
        assertTrue(result.skipped.isEmpty(), "skipped: ${result.skipped}")
        assertTrue(File(folded, "model/index.html").exists())
        assertTrue("Architecture Index" in File(folded, "index.html").readText(), "a page written over the index")
    }

    @Test
    fun `a process that is its view has one page with its diagram and no tab twice`() {
        val process = page("bpmn/orders/element/Process_A")
        assertTrue("id=\"node-Shape_1\"" in process)
        // A list tab says how many rows it holds: "Elements in view (1)".
        val tabs = Regex("""<button class="tab-btn[^"]*"[^>]*>([^<]+)</button>""").findAll(process)
            .map { it.groupValues[1].replace(Regex(""" \(\d+\)$"""), "") }.toList()
        assertEquals(tabs.distinct(), tabs, "a tab name twice: $tabs")
        assertTrue("Elements in view" in tabs)
    }

    @Test
    fun `the AI context is partitioned by model, read from the triples`() {
        val shop = File(out, "ai-context/model-shop.md").readText()
        val orders = File(out, "ai-context/bpmn-orders.md").readText()
        assertTrue("## Model" in shop)
        // No arch:inModel: in the model whose namespace its IRI is in.
        assertTrue("jane.doe@example.org" in shop)
        assertTrue("## Documentation" in shop && "> # Overview" in shop)
        assertTrue("Order process" in orders && "jane.doe" !in orders)
        // An entry is a heading. The model's entry may still cite its run and its former name.
        listOf(shop, orders).forEach { doc ->
            val headings = doc.lines().filter { it.startsWith("#") }
            assertTrue(headings.none { "${BASE}provenance/" in it }, "a provenance record has an entry")
            assertTrue(headings.none { it.endsWith("](${BASE}model/shop)") }, "the former name has an entry")
        }
    }

    companion object {
        const val BASE = "https://example.org/la/"

        val NS = Namespaces(
            core = "https://meta.linked.archi/core#",
            coreVis = "https://meta.linked.archi/core-vis#",
            archimate = "https://meta.linked.archi/archimate#",
            vocab = "https://meta.linked.archi/core#",
        )

        fun repositoryOf(trig: String): Repository {
            val repo = SailRepository(MemoryStore())
            repo.init()
            repo.connection.use { it.add(StringReader(trig), BASE, RDFFormat.TRIG) }
            return repo
        }

        /** Two models as the converters write them under DD-41, cut down to one of each kind of thing. */
        val GRAPH = """
            @prefix arch: <https://meta.linked.archi/core#> .
            @prefix arch-vis: <https://meta.linked.archi/core-vis#> .
            @prefix am: <https://meta.linked.archi/archimate3/onto#> .
            @prefix bpmn: <https://meta.linked.archi/bpmn/onto#> .
            @prefix lmm: <https://meta.linked.archi/leanix/onto#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix schema: <https://schema.org/> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            @prefix prov: <http://www.w3.org/ns/prov#> .
            @prefix vann: <http://purl.org/vocab/vann/> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @base <https://example.org/la/> .

            <model/shop/graph/model> {
                <model/shop/model> a arch:Model ; skos:prefLabel "Shop"@en ;
                    vann:preferredNamespaceUri "https://example.org/la/model/shop/" ;
                    schema:subjectOf <model/shop/documentation/workspace/01-overview.md> .
                <model/shop/folder/am-root> a arch:Folder ; schema:name "Shop" ;
                    schema:itemListElement <model/shop/folder/am-root/item/cc33> .
                <model/shop/folder/am-root/item/cc33> a schema:ListItem ;
                    schema:item <model/shop/element/web> ; schema:position 1 .
            }

            <model/shop/graph/semantic> {
                <model/shop/element/web> a am:ApplicationComponent, arch:Element, arch:ModelConcept ;
                    skos:prefLabel "Web Shop"@en ; arch:inModel <model/shop/model> ;
                    schema:additionalProperty <model/shop/element/web/property/aa11> ;
                    lmm:hasTag <model/shop/element/web/tag/bb22> .
                <model/shop/element/web/property/aa11> a schema:PropertyValue ;
                    schema:name "costCentre" ; schema:value "CC-42" .
                <model/shop/element/web/tag/bb22> a lmm:Tag ; skos:prefLabel "Critical" .
                <model/shop/element/customer> a am:BusinessActor, arch:Element, arch:ModelConcept ;
                    skos:prefLabel "Customer"@en ; arch:inModel <model/shop/model> .
                <model/shop/relationship/r1> a am:Serving, arch:QualifiedRelationship, arch:ModelConcept ;
                    arch:source <model/shop/element/web> ; arch:target <model/shop/element/customer> ;
                    arch:inModel <model/shop/model> .
                <model/shop/stakeholder/jane-doe-example.org> a arch:Stakeholder ;
                    skos:prefLabel "jane.doe@example.org" .
                <model/shop/documentation/workspace/01-overview.md> a schema:CreativeWork ;
                    schema:name "01-overview.md" ; schema:text "# Overview\n\nThe shop sells to customers." .
            }

            <model/shop/graph/views> {
                <model/shop/view/main> a arch:View, arch:Diagram, arch:ModelConcept ;
                    skos:prefLabel "Main"@en ; arch:inModel <model/shop/model> .
                <model/shop/view/main#node-n1> a arch-vis:ArchNode ; arch-vis:view <model/shop/view/main> ;
                    arch-vis:archElement <model/shop/element/web> ;
                    arch-vis:bounds-x "10.0"^^xsd:double ; arch-vis:bounds-y "10.0"^^xsd:double ;
                    arch-vis:bounds-w "120.0"^^xsd:double ; arch-vis:bounds-h "55.0"^^xsd:double ;
                    arch-vis:style <model/shop/view/main#node-n1-style> .
                <model/shop/view/main#node-n1-style> a arch-vis:Style ; arch-vis:fillColor "#b5ffff" .
                <model/shop/view/main#node-n2> a arch-vis:ArchNode ; arch-vis:view <model/shop/view/main> ;
                    arch-vis:archElement <model/shop/element/customer> ;
                    arch-vis:bounds-x "300.0"^^xsd:double ; arch-vis:bounds-y "10.0"^^xsd:double ;
                    arch-vis:bounds-w "120.0"^^xsd:double ; arch-vis:bounds-h "55.0"^^xsd:double .
                <model/shop/view/main#link-l1> a arch-vis:Link ; arch-vis:view <model/shop/view/main> ;
                    arch-vis:archRelationship <model/shop/relationship/r1> ;
                    arch-vis:source <model/shop/view/main#node-n1> ; arch-vis:target <model/shop/view/main#node-n2> ;
                    arch-vis:points <model/shop/view/main#link-l1-points-0> .
                <model/shop/view/main#link-l1-points-0> rdf:first <model/shop/view/main#link-l1-point-0> ;
                    rdf:rest <model/shop/view/main#link-l1-points-1> .
                <model/shop/view/main#link-l1-points-1> rdf:first <model/shop/view/main#link-l1-point-1> ;
                    rdf:rest rdf:nil .
                <model/shop/view/main#link-l1-point-1> a arch-vis:Point ;
                    arch-vis:point-x "250.0"^^xsd:double ; arch-vis:point-y "40.0"^^xsd:double .
                <model/shop/view/main#link-l1-point-0> a arch-vis:Point ;
                    arch-vis:point-x "200.0"^^xsd:double ; arch-vis:point-y "30.0"^^xsd:double .

                <model/shop/view/sketch> a arch:View, arch:Diagram, arch:ModelConcept ;
                    skos:prefLabel "Sketch"@en ; arch:inModel <model/shop/model> .
                <model/shop/view/sketch#node-web> a arch-vis:ArchNode ; arch-vis:view <model/shop/view/sketch> ;
                    arch-vis:archElement <model/shop/element/web> .
            }

            <model/shop/graph/provenance> {
                <model/shop/graph/semantic> a prov:Bundle ;
                    prov:wasGeneratedBy <provenance/run/0001> ; prov:wasDerivedFrom <provenance/source/shop-xml> .
                <model/shop/model> prov:wasGeneratedBy <provenance/run/0001> ;
                    prov:wasDerivedFrom <provenance/source/shop-xml> ; dcterms:replaces <model/shop> .
                <model/shop> owl:sameAs <model/shop/model> ; dcterms:isReplacedBy <model/shop/model> .
                <provenance/run/0001> a prov:Activity ; prov:used <provenance/source/shop-xml> ;
                    prov:wasAssociatedWith <provenance/agent/conv> ;
                    prov:startedAtTime "2026-11-19T04:37:07Z"^^xsd:dateTime .
                <provenance/agent/conv> a prov:SoftwareAgent, schema:SoftwareApplication ;
                    schema:name "archimate2linkedarchi" ; schema:softwareVersion "1.3.0" .
                <provenance/source/shop-xml> a prov:Entity ; schema:name "shop.xml" .
            }

            <bpmn/orders/graph/semantic> {
                <bpmn/orders/model> a arch:Model ; vann:preferredNamespaceUri "https://example.org/la/bpmn/orders/" .
                <bpmn/orders/element/Process_A> a bpmn:Process, arch:Element, arch:ModelConcept, arch:View, arch:Diagram ;
                    skos:prefLabel "Order process"@en ; arch:inModel <bpmn/orders/model> .
                <bpmn/orders/element/Task_1> a bpmn:Task, arch:Element, arch:ModelConcept ;
                    skos:prefLabel "Place order"@en ; arch:inModel <bpmn/orders/model> .
                <bpmn/orders/element/Process_A#node-Shape_1> a arch-vis:ArchNode ;
                    arch-vis:view <bpmn/orders/element/Process_A> ; arch-vis:archElement <bpmn/orders/element/Task_1> ;
                    arch-vis:bounds-x "0.0"^^xsd:double ; arch-vis:bounds-y "0.0"^^xsd:double ;
                    arch-vis:bounds-w "100.0"^^xsd:double ; arch-vis:bounds-h "80.0"^^xsd:double .
            }
        """.trimIndent()
    }
}

/** Where a page goes, and when an IRI cannot have one. */
class PagePathsTest {

    private val base = "https://example.org/la/"

    @Test
    fun `a page is the IRI below the base, decoded on disk and as it is in links`() {
        val iri = "${base}c4/shop/documentation/workspace/01%20overview.md"
        assertEquals("c4/shop/documentation/workspace/01%20overview.md/", PagePaths.urlDir(iri, base))
        assertEquals("c4/shop/documentation/workspace/01 overview.md/", PagePaths.diskDir(iri, base))
        assertEquals(5, PagePaths.depth(iri, base))
    }

    @Test
    fun `decoding reads percent escapes as UTF-8 and leaves a plus and a stray percent alone`() {
        assertEquals("Größe", PagePaths.decode("Gr%C3%B6%C3%9Fe"))
        assertEquals("a+b", PagePaths.decode("a+b"))
        assertEquals("100%", PagePaths.decode("100%"))
        assertEquals("%zz", PagePaths.decode("%zz"))
    }

    @Test
    fun `an IRI that cannot be a directory of its own is refused, with the reason`() {
        assertNull(PagePaths.unwritableReason("${base}model/x/element/a", base))
        listOf(
            base,                                        // the index
            "${base}model/x/view/v#node-n1",             // a fragment
            "${base}model/x/../../../etc",               // out of the output directory
            "${base}model/x/%2E%2E/y",                   // the same, escaped
            "${base}model/x/a%2Fb",                      // a '/' in one segment
            "${base}model//x",                           // an empty segment
            "${base}model/" + "a".repeat(256),           // over the filesystem limit
        ).forEach { iri -> assertNotNull(PagePaths.unwritableReason(iri, base), iri) }
    }

    @Test
    fun `a part's owner is the closest page it extends, and a former name owns nothing`() {
        fun info(iri: String, kind: HtmlPageGenerator.ResourceKind) = iri to HtmlPageGenerator.SubjectInfo(iri, kind)
        val pages = mapOf(
            info("${base}model/x", HtmlPageGenerator.ResourceKind.FORMER_NAME),
            info("${base}model/x/view/v", HtmlPageGenerator.ResourceKind.VIEW),
            info("${base}model/x/element/a", HtmlPageGenerator.ResourceKind.ELEMENT),
        )
        val owners = Site.owners(
            listOf(
                "${base}model/x/view/v#link-l-points-0",
                "${base}model/x/element/a/tag/t1",
                "${base}model/x/folder/Views/item/i1",
            ),
            pages, base,
        )
        assertEquals(
            mapOf(
                "${base}model/x/view/v#link-l-points-0" to "${base}model/x/view/v",
                "${base}model/x/element/a/tag/t1" to "${base}model/x/element/a",
            ),
            owners,
        )
    }

    @Test
    fun `a link to a shape keeps its fragment, a link to a part goes to its owner`() {
        val view = "${base}model/x/view/v"
        val site = Site(
            base,
            mapOf(view to HtmlPageGenerator.SubjectInfo(view, HtmlPageGenerator.ResourceKind.VIEW)),
            mapOf("$view#node-n1-style" to view),
        )
        assertEquals("../../model/x/view/v/index.html#node-n1", site.href("$view#node-n1", "../../"))
        assertEquals("../../model/x/view/v/index.html#node-n1-style", site.href("$view#node-n1-style", "../../"))
        assertEquals("https://meta.linked.archi/core#Element", site.href("https://meta.linked.archi/core#Element", "../../"))
        assertNull(site.href("${base}model/x/element/described-nowhere", "../../"))
    }
}
